/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.core.trade;

import com.google.common.base.Preconditions;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import common.utils.GenUtils;
import haveno.common.UserThread;
import haveno.common.config.Config;
import haveno.common.crypto.Encryption;
import haveno.common.crypto.PubKeyRing;
import haveno.common.proto.ProtoUtil;
import haveno.common.taskrunner.Model;
import haveno.common.util.Utilities;
import haveno.core.api.XmrConnectionService;
import haveno.core.monetary.Price;
import haveno.core.monetary.Volume;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.proto.CoreProtoResolver;
import haveno.core.proto.network.CoreNetworkProtoResolver;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.DisputeResult;
import haveno.core.support.dispute.arbitration.ArbitrationManager;
import haveno.core.support.dispute.mediation.MediationResultState;
import haveno.core.support.dispute.refund.RefundResultState;
import haveno.core.support.messages.ChatMessage;
import haveno.core.trade.messages.TradeMessage;
import haveno.core.trade.protocol.ProcessModel;
import haveno.core.trade.protocol.ProcessModelServiceProvider;
import haveno.core.trade.protocol.TradeListener;
import haveno.core.trade.protocol.TradePeer;
import haveno.core.trade.protocol.TradeProtocol;
import haveno.core.util.VolumeUtil;
import haveno.core.xmr.model.XmrAddressEntry;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.AckMessage;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroError;
import monero.common.MoneroRpcConnection;
import monero.common.TaskLooper;
import monero.daemon.MoneroDaemon;
import monero.daemon.model.MoneroDaemonInfo;
import monero.daemon.model.MoneroKeyImage;
import monero.daemon.model.MoneroTx;
import monero.wallet.MoneroWallet;
import monero.wallet.MoneroWalletRpc;
import monero.wallet.model.MoneroDestination;
import monero.wallet.model.MoneroMultisigSignResult;
import monero.wallet.model.MoneroOutputQuery;
import monero.wallet.model.MoneroOutputWallet;
import monero.wallet.model.MoneroTxConfig;
import monero.wallet.model.MoneroTxQuery;
import monero.wallet.model.MoneroTxSet;
import monero.wallet.model.MoneroTxWallet;
import monero.wallet.model.MoneroWalletListener;

import org.apache.commons.lang3.StringUtils;
import org.bitcoinj.core.Coin;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;
import java.math.BigInteger;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Holds all data which are relevant to the trade, but not those which are only needed in the trade process as shared data between tasks. Those data are
 * stored in the task model.
 */
@Slf4j
public abstract class Trade implements Tradable, Model {

    private static final String MONERO_TRADE_WALLET_PREFIX = "xmr_trade_";
    private MoneroWallet wallet; // trade wallet
    private Object walletLock = new Object();
    boolean wasWalletSynced = false;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Enums
    ///////////////////////////////////////////////////////////////////////////////////////////

    public enum State {

        // trade initialization
        PREPARATION(Phase.INIT),
        MULTISIG_PREPARED(Phase.INIT),
        MULTISIG_MADE(Phase.INIT),
        MULTISIG_EXCHANGED(Phase.INIT),
        MULTISIG_COMPLETED(Phase.INIT),
        CONTRACT_SIGNATURE_REQUESTED(Phase.INIT),
        CONTRACT_SIGNED(Phase.INIT),

        // deposit requested
        SENT_PUBLISH_DEPOSIT_TX_REQUEST(Phase.DEPOSIT_REQUESTED),
        SEND_FAILED_PUBLISH_DEPOSIT_TX_REQUEST(Phase.DEPOSIT_REQUESTED),
        SAW_ARRIVED_PUBLISH_DEPOSIT_TX_REQUEST(Phase.DEPOSIT_REQUESTED),
        PUBLISH_DEPOSIT_TX_REQUEST_FAILED(Phase.DEPOSIT_REQUESTED),

        // deposit published
        ARBITRATOR_PUBLISHED_DEPOSIT_TXS(Phase.DEPOSITS_PUBLISHED),
        DEPOSIT_TXS_SEEN_IN_NETWORK(Phase.DEPOSITS_PUBLISHED),

        // deposit confirmed
        DEPOSIT_TXS_CONFIRMED_IN_BLOCKCHAIN(Phase.DEPOSITS_CONFIRMED),

        // deposit unlocked
        DEPOSIT_TXS_UNLOCKED_IN_BLOCKCHAIN(Phase.DEPOSITS_UNLOCKED),

        // payment sent
        BUYER_CONFIRMED_IN_UI_PAYMENT_SENT(Phase.PAYMENT_SENT),
        BUYER_SENT_PAYMENT_SENT_MSG(Phase.PAYMENT_SENT),
        BUYER_SEND_FAILED_PAYMENT_SENT_MSG(Phase.PAYMENT_SENT),
        BUYER_STORED_IN_MAILBOX_PAYMENT_SENT_MSG(Phase.PAYMENT_SENT),
        BUYER_SAW_ARRIVED_PAYMENT_SENT_MSG(Phase.PAYMENT_SENT),
        SELLER_RECEIVED_PAYMENT_SENT_MSG(Phase.PAYMENT_SENT),

        // payment received
        SELLER_CONFIRMED_IN_UI_PAYMENT_RECEIPT(Phase.PAYMENT_RECEIVED),
        SELLER_SENT_PAYMENT_RECEIVED_MSG(Phase.PAYMENT_RECEIVED),
        SELLER_SEND_FAILED_PAYMENT_RECEIVED_MSG(Phase.PAYMENT_RECEIVED),
        SELLER_STORED_IN_MAILBOX_PAYMENT_RECEIVED_MSG(Phase.PAYMENT_RECEIVED),
        SELLER_SAW_ARRIVED_PAYMENT_RECEIVED_MSG(Phase.PAYMENT_RECEIVED),

        // trade completed
        TRADE_COMPLETED(Phase.COMPLETED);

        @NotNull
        public Phase getPhase() {
            return phase;
        }

        @NotNull
        private final Phase phase;

        State(@NotNull Phase phase) {
            this.phase = phase;
        }

        public static Trade.State fromProto(protobuf.Trade.State state) {
            return ProtoUtil.enumFromProto(Trade.State.class, state.name());
        }

        public static protobuf.Trade.State toProtoMessage(Trade.State state) {
            return protobuf.Trade.State.valueOf(state.name());
        }


        // We allow a state change only if the phase is the next phase or if we do not change the phase by the
        // state change (e.g. detail change inside the same phase)
        public boolean isValidTransitionTo(State newState) {
            Phase newPhase = newState.getPhase();
            Phase currentPhase = this.getPhase();
            return currentPhase.isValidTransitionTo(newPhase) || newPhase.equals(currentPhase);
        }
    }

    public enum Phase {
        INIT,
        DEPOSIT_REQUESTED,
        DEPOSITS_PUBLISHED,
        DEPOSITS_CONFIRMED,
        DEPOSITS_UNLOCKED,
        PAYMENT_SENT,
        PAYMENT_RECEIVED,
        COMPLETED;

        public static Trade.Phase fromProto(protobuf.Trade.Phase phase) {
            return ProtoUtil.enumFromProto(Trade.Phase.class, phase.name());
        }

        public static protobuf.Trade.Phase toProtoMessage(Trade.Phase phase) {
            return protobuf.Trade.Phase.valueOf(phase.name());
        }

        // We allow a phase change only if the phase a future phase (we cannot limit it to next phase as we have cases where
        // we skip a phase as it is only relevant to one role -> states and phases need a redesign ;-( )
        public boolean isValidTransitionTo(Phase newPhase) {
            // this is current phase
            return newPhase.ordinal() > this.ordinal();
        }
    }

    public enum PayoutState {
        PAYOUT_UNPUBLISHED,
        PAYOUT_PUBLISHED,
        PAYOUT_CONFIRMED,
        PAYOUT_UNLOCKED;

        public static Trade.PayoutState fromProto(protobuf.Trade.PayoutState state) {
            return ProtoUtil.enumFromProto(Trade.PayoutState.class, state.name());
        }

        public static protobuf.Trade.PayoutState toProtoMessage(Trade.PayoutState state) {
            return protobuf.Trade.PayoutState.valueOf(state.name());
        }

        public boolean isValidTransitionTo(PayoutState newState) {
            return newState.ordinal() > this.ordinal();
        }
    }

    public enum DisputeState {
        NO_DISPUTE,
        DISPUTE_REQUESTED,
        DISPUTE_OPENED,
        ARBITRATOR_SENT_DISPUTE_CLOSED_MSG,
        ARBITRATOR_SEND_FAILED_DISPUTE_CLOSED_MSG,
        ARBITRATOR_STORED_IN_MAILBOX_DISPUTE_CLOSED_MSG,
        ARBITRATOR_SAW_ARRIVED_DISPUTE_CLOSED_MSG,
        DISPUTE_CLOSED,

        // mediation
        MEDIATION_REQUESTED,
        MEDIATION_STARTED_BY_PEER,
        MEDIATION_CLOSED,

        // refund
        REFUND_REQUESTED,
        REFUND_REQUEST_STARTED_BY_PEER,
        REFUND_REQUEST_CLOSED;

        public static Trade.DisputeState fromProto(protobuf.Trade.DisputeState disputeState) {
            return ProtoUtil.enumFromProto(Trade.DisputeState.class, disputeState.name());
        }

        public static protobuf.Trade.DisputeState toProtoMessage(Trade.DisputeState disputeState) {
            return protobuf.Trade.DisputeState.valueOf(disputeState.name());
        }

        public boolean isNotDisputed() {
            return this == Trade.DisputeState.NO_DISPUTE;
        }

        public boolean isMediated() {
            return this == Trade.DisputeState.MEDIATION_REQUESTED ||
                    this == Trade.DisputeState.MEDIATION_STARTED_BY_PEER ||
                    this == Trade.DisputeState.MEDIATION_CLOSED;
        }

        public boolean isArbitrated() {
            if (isMediated()) return false; // TODO: remove mediation?
            return this.ordinal() >= DisputeState.DISPUTE_REQUESTED.ordinal();
        }

        public boolean isRequested() {
            return ordinal() >= DisputeState.DISPUTE_REQUESTED.ordinal();
        }

        public boolean isOpen() {
            return this == DisputeState.DISPUTE_OPENED;
        }

        public boolean isClosed() {
            return this == DisputeState.DISPUTE_CLOSED;
        }
    }

    public enum TradePeriodState {
        FIRST_HALF,
        SECOND_HALF,
        TRADE_PERIOD_OVER;

        public static Trade.TradePeriodState fromProto(protobuf.Trade.TradePeriodState tradePeriodState) {
            return ProtoUtil.enumFromProto(Trade.TradePeriodState.class, tradePeriodState.name());
        }

        public static protobuf.Trade.TradePeriodState toProtoMessage(Trade.TradePeriodState tradePeriodState) {
            return protobuf.Trade.TradePeriodState.valueOf(tradePeriodState.name());
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Fields
    ///////////////////////////////////////////////////////////////////////////////////////////

    // Persistable
    // Immutable
    @Getter
    private final ProcessModel processModel;
    @Getter
    private final Offer offer;
    private final long takerFee;

    // Added in 1.5.1
    @Getter
    private final String uid;

    @Setter
    private long takeOfferDate;

    // Initialization
    private static final int TOTAL_INIT_STEPS = 15; // total estimated steps
    private int initStep = 0;
    @Getter
    private double initProgress = 0;
    @Getter
    @Setter
    private Exception initError;

    //  Mutable
    private long amount;
    @Setter
    private long price;
    @Nullable
    @Getter
    private State state = State.PREPARATION;
    @Getter
    private PayoutState payoutState = PayoutState.PAYOUT_UNPUBLISHED;
    @Getter
    private DisputeState disputeState = DisputeState.NO_DISPUTE;
    @Getter
    private TradePeriodState periodState = TradePeriodState.FIRST_HALF;
    @Nullable
    @Getter
    @Setter
    private Contract contract;
    @Nullable
    @Getter
    @Setter
    private String contractAsJson;
    @Nullable
    @Getter
    @Setter
    private byte[] contractHash;
    @Nullable
    private String errorMessage;
    @Getter
    @Setter
    @Nullable
    private String counterCurrencyTxId;
    @Getter
    private final ObservableList<ChatMessage> chatMessages = FXCollections.observableArrayList();

    // Transient
    // Immutable
    @Getter
    transient final private XmrWalletService xmrWalletService;

    transient final private DoubleProperty initProgressProperty = new SimpleDoubleProperty(0.0);
    transient final private ObjectProperty<State> stateProperty = new SimpleObjectProperty<>(state);
    transient final private ObjectProperty<Phase> phaseProperty = new SimpleObjectProperty<>(state.phase);
    transient final private ObjectProperty<PayoutState> payoutStateProperty = new SimpleObjectProperty<>(payoutState);
    transient final private ObjectProperty<DisputeState> disputeStateProperty = new SimpleObjectProperty<>(disputeState);
    transient final private ObjectProperty<TradePeriodState> tradePeriodStateProperty = new SimpleObjectProperty<>(periodState);
    transient final private StringProperty errorMessageProperty = new SimpleStringProperty();
    transient private Subscription tradeStateSubscription;
    transient private Subscription tradePhaseSubscription;
    transient private Subscription payoutStateSubscription;
    transient private TaskLooper txPollLooper;
    transient private Long walletRefreshPeriodMs;
    transient private Long syncNormalStartTimeMs;

    public static final long DEFER_PUBLISH_MS = 25000; // 25 seconds
    private static final long IDLE_SYNC_PERIOD_MS = 1680000; // 28 minutes (monero's default connection timeout is 30 minutes on a local connection, so beyond this the wallets will disconnect)
    private static final long MAX_REPROCESS_DELAY_SECONDS = 7200; // max delay to reprocess messages (once per 2 hours)

    //  Mutable
    @Getter
    transient private boolean isInitialized;
    @Getter
    transient private boolean isShutDownStarted;
    @Getter
    transient private boolean isShutDown;

    // Added in v1.2.0
    transient private ObjectProperty<BigInteger> tradeAmountProperty;
    transient private ObjectProperty<Volume> tradeVolumeProperty;

    // Added in v1.1.6
    @Getter
    @Nullable
    private MediationResultState mediationResultState = MediationResultState.UNDEFINED_MEDIATION_RESULT;
    transient final private ObjectProperty<MediationResultState> mediationResultStateProperty = new SimpleObjectProperty<>(mediationResultState);

    // Added in v1.2.0
    @Getter
    @Setter
    private long lockTime;
    @Setter
    private long startTime; // added for haveno
    @Getter
    @Nullable
    private RefundResultState refundResultState = RefundResultState.UNDEFINED_REFUND_RESULT;
    transient final private ObjectProperty<RefundResultState> refundResultStateProperty = new SimpleObjectProperty<>(refundResultState);

    // Added at v1.3.8
    // We use that for the XMR txKey but want to keep it generic to be flexible for other payment methods or assets.
    @Getter
    @Setter
    private String counterCurrencyExtraData;

    // Added in XMR integration
    private transient List<TradeListener> tradeListeners; // notified on fully validated trade messages
    transient MoneroWalletListener depositTxListener;
    transient MoneroWalletListener payoutTxListener;
    transient Boolean makerDepositLocked; // null when unknown, true while locked, false when unlocked
    transient Boolean takerDepositLocked;
    @Nullable
    transient private MoneroTx payoutTx;
    @Getter
    @Setter
    private String payoutTxId;
    @Nullable
    @Getter
    @Setter
    private String payoutTxHex;
    @Getter
    @Setter
    private String payoutTxKey;
    private long payoutTxFee;
    private Long payoutHeight;
    private IdlePayoutSyncer idlePayoutSyncer;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructors
    ///////////////////////////////////////////////////////////////////////////////////////////

    // maker
    protected Trade(Offer offer,
                    BigInteger tradeAmount,
                    BigInteger takerFee,
                    long tradePrice,
                    XmrWalletService xmrWalletService,
                    ProcessModel processModel,
                    String uid,
                    @Nullable NodeAddress makerNodeAddress,
                    @Nullable NodeAddress takerNodeAddress,
                    @Nullable NodeAddress arbitratorNodeAddress) {
        this.offer = offer;
        this.amount = tradeAmount.longValueExact();
        this.takerFee = takerFee.longValueExact();
        this.price = tradePrice;
        this.xmrWalletService = xmrWalletService;
        this.processModel = processModel;
        this.uid = uid;
        this.takeOfferDate = new Date().getTime();
        this.tradeListeners = new ArrayList<TradeListener>();

        getMaker().setNodeAddress(makerNodeAddress);
        getTaker().setNodeAddress(takerNodeAddress);
        getArbitrator().setNodeAddress(arbitratorNodeAddress);

        setAmount(tradeAmount);
    }


    // TODO (woodser): this constructor has mediator and refund agent (to be removed), otherwise use common
    // taker
    @SuppressWarnings("NullableProblems")
    protected Trade(Offer offer,
                    BigInteger tradeAmount,
                    BigInteger txFee,
                    BigInteger takerFee,
                    long tradePrice,
                    @Nullable NodeAddress mediatorNodeAddress, // TODO (woodser): remove mediator, refund agent from trade
                    @Nullable NodeAddress refundAgentNodeAddress,
                    XmrWalletService xmrWalletService,
                    ProcessModel processModel,
                    String uid,
                    @Nullable NodeAddress makerNodeAddress,
                    @Nullable NodeAddress takerNodeAddress,
                    @Nullable NodeAddress arbitratorNodeAddress) {

        this(offer,
                tradeAmount,
                takerFee,
                tradePrice,
                xmrWalletService,
                processModel,
                uid,
                makerNodeAddress,
                takerNodeAddress,
                arbitratorNodeAddress);
    }

    // TODO: remove these constructors
    // arbitrator
    @SuppressWarnings("NullableProblems")
    protected Trade(Offer offer,
                    BigInteger tradeAmount,
                    Coin txFee,
                    BigInteger takerFee,
                    long tradePrice,
                    NodeAddress makerNodeAddress,
                    NodeAddress takerNodeAddress,
                    NodeAddress arbitratorNodeAddress,
                    XmrWalletService xmrWalletService,
                    ProcessModel processModel,
                    String uid) {

      this(offer,
              tradeAmount,
              takerFee,
              tradePrice,
              xmrWalletService,
              processModel,
              uid,
              makerNodeAddress,
              takerNodeAddress,
              arbitratorNodeAddress);

        setAmount(tradeAmount);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Listeners
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void addListener(TradeListener listener) {
        tradeListeners.add(listener);
    }

    public void removeListener(TradeListener listener) {
        if (!tradeListeners.remove(listener)) throw new RuntimeException("TradeMessageListener is not registered");
    }

    // notified from TradeProtocol of verified trade messages
    public void onVerifiedTradeMessage(TradeMessage message, NodeAddress sender) {
        for (TradeListener listener : new ArrayList<TradeListener>(tradeListeners)) {  // copy array to allow listener invocation to unregister listener without concurrent modification exception
            listener.onVerifiedTradeMessage(message, sender);
        }
    }

      // notified from TradeProtocol of ack messages
      public void onAckMessage(AckMessage ackMessage, NodeAddress sender) {
        for (TradeListener listener : new ArrayList<TradeListener>(tradeListeners)) {  // copy array to allow listener invocation to unregister listener without concurrent modification exception
            listener.onAckMessage(ackMessage, sender);
        }
      }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // INITIALIZATION
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void initialize(ProcessModelServiceProvider serviceProvider) {
        synchronized (this) {
            if (isInitialized) throw new IllegalStateException(getClass().getSimpleName() + " " + getId() + " is already initialized");

            // set arbitrator pub key ring once known
            serviceProvider.getArbitratorManager().getDisputeAgentByNodeAddress(getArbitratorNodeAddress()).ifPresent(arbitrator -> {
                getArbitrator().setPubKeyRing(arbitrator.getPubKeyRing());
            });

            // handle daemon changes with max parallelization
            xmrWalletService.getConnectionService().addConnectionListener(newConnection -> {
                HavenoUtils.submitTask(() -> onConnectionChanged(newConnection));
            });

            // check if done
            if (isPayoutUnlocked()) {
                maybeClearProcessData();
                return;
            }

            // reset buyer's payment sent state if no ack receive
            if (this instanceof BuyerTrade && getState().ordinal() >= Trade.State.BUYER_CONFIRMED_IN_UI_PAYMENT_SENT.ordinal() && getState().ordinal() < Trade.State.BUYER_STORED_IN_MAILBOX_PAYMENT_SENT_MSG.ordinal()) {
                log.warn("Resetting state of {} {} from {} to {} because no ack was received", getClass().getSimpleName(), getId(), getState(), Trade.State.DEPOSIT_TXS_UNLOCKED_IN_BLOCKCHAIN);
                setState(Trade.State.DEPOSIT_TXS_UNLOCKED_IN_BLOCKCHAIN);
            }

            // reset seller's payment received state if no ack receive
            if (this instanceof SellerTrade && getState().ordinal() >= Trade.State.SELLER_CONFIRMED_IN_UI_PAYMENT_RECEIPT.ordinal() && getState().ordinal() < Trade.State.SELLER_STORED_IN_MAILBOX_PAYMENT_RECEIVED_MSG.ordinal()) {
                log.warn("Resetting state of {} {} from {} to {} because no ack was received", getClass().getSimpleName(), getId(), getState(), Trade.State.BUYER_SENT_PAYMENT_SENT_MSG);
                setState(Trade.State.BUYER_SENT_PAYMENT_SENT_MSG);
            }

            // handle trade state events
            tradeStateSubscription = EasyBind.subscribe(stateProperty, newValue -> {
                if (newValue == Trade.State.MULTISIG_COMPLETED) {
                    updateWalletRefreshPeriod();
                    startPolling();
                }
            });

            // handle trade phase events
            tradePhaseSubscription = EasyBind.subscribe(phaseProperty, newValue -> {
                if (isDepositsPublished() && !isPayoutUnlocked()) updateWalletRefreshPeriod();
                if (isCompleted()) {
                    UserThread.execute(() -> {
                        if (tradePhaseSubscription != null) {
                            tradePhaseSubscription.unsubscribe();
                            tradePhaseSubscription = null;
                        }
                    });
                }
            });

            // handle payout events
            payoutStateSubscription = EasyBind.subscribe(payoutStateProperty, newValue -> {
                if (isPayoutPublished()) updateWalletRefreshPeriod();

                // handle when payout published
                if (newValue == Trade.PayoutState.PAYOUT_PUBLISHED) {
                    log.info("Payout published for {} {}", getClass().getSimpleName(), getId());

                    // sync main wallet to update pending balance
                    new Thread(() -> {
                        GenUtils.waitFor(1000);
                        if (isShutDownStarted) return;
                        if (Boolean.TRUE.equals(xmrWalletService.getConnectionService().isConnected())) xmrWalletService.syncWallet(xmrWalletService.getWallet());
                    }).start();

                    // complete disputed trade
                    if (getDisputeState().isArbitrated() && !getDisputeState().isClosed()) processModel.getTradeManager().closeDisputedTrade(getId(), Trade.DisputeState.DISPUTE_CLOSED);

                    // complete arbitrator trade
                    if (isArbitrator() && !isCompleted()) processModel.getTradeManager().onTradeCompleted(this);

                    // reset address entries
                    processModel.getXmrWalletService().resetAddressEntriesForTrade(getId());
                }

                // handle when payout unlocks
                if (newValue == Trade.PayoutState.PAYOUT_UNLOCKED) {
                    if (!isInitialized) return;
                    log.info("Payout unlocked for {} {}, deleting multisig wallet", getClass().getSimpleName(), getId());
                    deleteWallet();
                    maybeClearProcessData();
                    if (idlePayoutSyncer != null) {
                        xmrWalletService.removeWalletListener(idlePayoutSyncer);
                        idlePayoutSyncer = null;
                    }
                    UserThread.execute(() -> {
                        if (payoutStateSubscription != null) {
                            payoutStateSubscription.unsubscribe();
                            payoutStateSubscription = null;
                        }
                    });
                }
            });

            // arbitrator syncs idle wallet when payout unlock expected
            if (this instanceof ArbitratorTrade) {
                idlePayoutSyncer = new IdlePayoutSyncer();
                xmrWalletService.addWalletListener(idlePayoutSyncer);
            }

            // trade is initialized
            isInitialized = true;

            // done if payout unlocked or deposit not requested
            if (!isDepositRequested() || isPayoutUnlocked()) return;

            // done if wallet does not exist
            if (!walletExists()) {
                MoneroTx payoutTx = getPayoutTx();
                if (payoutTx != null && payoutTx.getNumConfirmations() >= 10) {
                    log.warn("Payout state for {} {} is {} but payout is unlocked, updating state", getClass().getSimpleName(), getId(), getPayoutState());
                    setPayoutStateUnlocked();
                    return;
                } else {
                    throw new IllegalStateException("Missing trade wallet for " + getClass().getSimpleName() + " " + getId());
                }
            }

            // initialize syncing and polling
            initSyncing();
        }
    }

    public void requestPersistence() {
        processModel.getTradeManager().requestPersistence();
    }

    public TradeProtocol getProtocol() {
        return processModel.getTradeManager().getTradeProtocol(this);
    }

    public void setMyNodeAddress() {
        getSelf().setNodeAddress(P2PService.getMyNodeAddress());
    }

    public NodeAddress getTradePeerNodeAddress() {
        return getTradePeer() == null ? null : getTradePeer().getNodeAddress();
    }

    public NodeAddress getArbitratorNodeAddress() {
        return getArbitrator() == null ? null : getArbitrator().getNodeAddress();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // WALLET MANAGEMENT
    ///////////////////////////////////////////////////////////////////////////////////////////

    public boolean walletExists() {
        synchronized (walletLock) {
            return xmrWalletService.walletExists(MONERO_TRADE_WALLET_PREFIX + getId());
        }
    }

    public MoneroWallet createWallet() {
        synchronized (walletLock) {
            if (walletExists()) throw new RuntimeException("Cannot create trade wallet because it already exists");
            long time = System.currentTimeMillis();
            wallet = xmrWalletService.createWallet(getWalletName());
            log.info("{} {} created multisig wallet in {} ms", getClass().getSimpleName(), getId(), System.currentTimeMillis() - time);
            return wallet;
        }
    }

    public MoneroWallet getWallet() {
        synchronized (walletLock) {
            if (wallet != null) return wallet;
            if (!walletExists()) return null;
            if (isShutDownStarted) throw new RuntimeException("Cannot open wallet for " + getClass().getSimpleName() + " " + getId() + " because shut down is started");
            else wallet = xmrWalletService.openWallet(getWalletName(), xmrWalletService.isProxyApplied(wasWalletSynced));
            return wallet;
        }
    }

    private String getWalletName() {
        return MONERO_TRADE_WALLET_PREFIX + getId();
    }

    public void checkDaemonConnection() {
        XmrConnectionService xmrConnectionService = xmrWalletService.getConnectionService();
        xmrConnectionService.checkConnection();
        xmrConnectionService.verifyConnection();
        if (!getWallet().isConnectedToDaemon()) throw new RuntimeException("Trade wallet is not connected to a Monero node");
    }

    public boolean isWalletConnected() {
        try {
            checkDaemonConnection();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isIdling() {
        return this instanceof ArbitratorTrade && isDepositsConfirmed() && walletExists() && syncNormalStartTimeMs == null; // arbitrator idles trade after deposits confirm unless overriden
    }

    public void syncAndPollWallet() {
        syncWallet(true);
    }

    public void syncWalletNormallyForMs(long syncNormalDuration) {
        syncNormalStartTimeMs = System.currentTimeMillis();

        // override wallet refresh period
        setWalletRefreshPeriod(xmrWalletService.getConnectionService().getRefreshPeriodMs());

        // reset wallet refresh period after duration 
        new Thread(() -> {
            GenUtils.waitFor(syncNormalDuration);
            if (!isShutDown && System.currentTimeMillis() >= syncNormalStartTimeMs + syncNormalDuration) {
                syncNormalStartTimeMs = null;
                updateWalletRefreshPeriod();
            }
        }).start();

        // TODO: sync wallet because `auto_refresh` will not sync wallet until end of last sync period (which could be a long idle)
        new Thread(() -> {
            GenUtils.waitFor(1000);
            if (!isShutDownStarted) trySyncWallet(true);
        }).start();
    }

    public void importMultisigHex() {
        synchronized (walletLock) {
            List<String> multisigHexes = new ArrayList<String>();
            if (getBuyer().getUpdatedMultisigHex() != null) multisigHexes.add(getBuyer().getUpdatedMultisigHex());
            if (getSeller().getUpdatedMultisigHex() != null) multisigHexes.add(getSeller().getUpdatedMultisigHex());
            if (getArbitrator().getUpdatedMultisigHex() != null) multisigHexes.add(getArbitrator().getUpdatedMultisigHex());
            if (!multisigHexes.isEmpty()) {
                log.info("Importing multisig hex for {} {}", getClass().getSimpleName(), getId());
                getWallet().importMultisigHex(multisigHexes.toArray(new String[0]));
                log.info("Done importing multisig hex for {} {}", getClass().getSimpleName(), getId());
            }
        }
    }

    public void changeWalletPassword(String oldPassword, String newPassword) {
        synchronized (walletLock) {
            getWallet().changePassword(oldPassword, newPassword);
            saveWallet();
        }
    }

    public void saveWallet() {
        synchronized (walletLock) {
            if (wallet == null) throw new RuntimeException("Trade wallet is not open for trade " + getId());
            xmrWalletService.saveWallet(wallet, true);
        }
    }

    private void closeWallet() {
        synchronized (walletLock) {
            if (wallet == null) throw new RuntimeException("Trade wallet to close is not open for trade " + getId());
            stopPolling();
            xmrWalletService.closeWallet(wallet, true);
            wallet = null;
            walletRefreshPeriodMs = null;
        }
    }

    private void stopWallet() {
        synchronized (walletLock) {
            if (wallet == null) throw new RuntimeException("Trade wallet to close is not open for trade " + getId());
            stopPolling();
            xmrWalletService.stopWallet(wallet, wallet.getPath(), true);
            wallet = null;
        }
    }

    public void deleteWallet() {
        synchronized (walletLock) {
            if (walletExists()) {
                try {

                    // check if funds deposited but payout not unlocked
                    if (isDepositsPublished() && !isPayoutUnlocked()) {
                        throw new RuntimeException("Refusing to delete wallet for " + getClass().getSimpleName() + " " + getId() + " because the deposit txs have been published but payout tx has not unlocked");
                    }

                    // force stop the wallet
                    if (wallet != null) stopWallet();

                    // delete wallet
                    log.info("Deleting wallet for {} {}", getClass().getSimpleName(), getId());
                    xmrWalletService.deleteWallet(getWalletName());

                    // delete trade wallet backups unless deposits requested and payouts not unlocked
                    if (isDepositRequested() && !isDepositFailed() && !isPayoutUnlocked()) {
                        log.warn("Refusing to delete backup wallet for " + getClass().getSimpleName() + " " + getId() + " in the small chance it becomes funded");
                        return;
                    }
                    xmrWalletService.deleteWalletBackups(getWalletName());
                } catch (Exception e) {
                    log.warn(e.getMessage());
                    e.printStackTrace();
                    setErrorMessage(e.getMessage());
                    processModel.getTradeManager().getNotificationService().sendErrorNotification("Error", e.getMessage());
                }
            } else {
                log.warn("Multisig wallet to delete for trade {} does not exist", getId());
            }
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTOCOL API
    ///////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Create a contract based on the current state.
     *
     * @param trade is the trade to create the contract from
     * @return the contract
     */
    public Contract createContract() {
        boolean isBuyerMakerAndSellerTaker = getOffer().getDirection() == OfferDirection.BUY;
        Contract contract = new Contract(
                getOffer().getOfferPayload(),
                checkNotNull(getAmount()).longValueExact(),
                getPrice().getValue(),
                (isBuyerMakerAndSellerTaker ? getMaker() : getTaker()).getNodeAddress(), // buyer node address // TODO (woodser): use maker and taker node address instead of buyer and seller node address for consistency
                (isBuyerMakerAndSellerTaker ? getTaker() : getMaker()).getNodeAddress(), // seller node address
                getArbitrator().getNodeAddress(),
                isBuyerMakerAndSellerTaker,
                this instanceof MakerTrade ? processModel.getAccountId() : getMaker().getAccountId(), // maker account id
                this instanceof TakerTrade ? processModel.getAccountId() : getTaker().getAccountId(), // taker account id
                checkNotNull(this instanceof MakerTrade ? getMaker().getPaymentAccountPayload().getPaymentMethodId() : getOffer().getOfferPayload().getPaymentMethodId()),
                checkNotNull(this instanceof TakerTrade ? getTaker().getPaymentAccountPayload().getPaymentMethodId() : getTaker().getPaymentMethodId()),
                this instanceof MakerTrade ? getMaker().getPaymentAccountPayload().getHash() : getMaker().getPaymentAccountPayloadHash(),
                this instanceof TakerTrade ? getTaker().getPaymentAccountPayload().getHash() : getTaker().getPaymentAccountPayloadHash(),
                getMaker().getPubKeyRing(),
                getTaker().getPubKeyRing(),
                this instanceof MakerTrade ? xmrWalletService.getAddressEntry(getId(), XmrAddressEntry.Context.TRADE_PAYOUT).get().getAddressString() : getMaker().getPayoutAddressString(), // maker payout address
                this instanceof TakerTrade ? xmrWalletService.getAddressEntry(getId(), XmrAddressEntry.Context.TRADE_PAYOUT).get().getAddressString() : getTaker().getPayoutAddressString(), // taker payout address
                getMaker().getDepositTxHash(),
                getTaker().getDepositTxHash()
        );
        return contract;
    }

    /**
     * Create the payout tx.
     *
     * @return MoneroTxWallet the payout tx when the trade is successfully completed
     */
    public MoneroTxWallet createPayoutTx() {

        // check connection to monero daemon
        checkDaemonConnection();

        // check multisig import
        if (getWallet().isMultisigImportNeeded()) throw new RuntimeException("Cannot create payout tx because multisig import is needed");

        // gather info
        MoneroWallet multisigWallet = getWallet();
        String sellerPayoutAddress = this.getSeller().getPayoutAddressString();
        String buyerPayoutAddress = this.getBuyer().getPayoutAddressString();
        Preconditions.checkNotNull(sellerPayoutAddress, "Seller payout address must not be null");
        Preconditions.checkNotNull(buyerPayoutAddress, "Buyer payout address must not be null");
        BigInteger sellerDepositAmount = multisigWallet.getTx(this.getSeller().getDepositTxHash()).getIncomingAmount();
        BigInteger buyerDepositAmount = multisigWallet.getTx(this.getBuyer().getDepositTxHash()).getIncomingAmount();
        BigInteger tradeAmount = getAmount();
        BigInteger buyerPayoutAmount = buyerDepositAmount.add(tradeAmount);
        BigInteger sellerPayoutAmount = sellerDepositAmount.subtract(tradeAmount);

        // create payout tx
        MoneroTxWallet payoutTx = multisigWallet.createTx(new MoneroTxConfig()
                .setAccountIndex(0)
                .addDestination(buyerPayoutAddress, buyerPayoutAmount)
                .addDestination(sellerPayoutAddress, sellerPayoutAmount)
                .setSubtractFeeFrom(0, 1) // split tx fee
                .setRelay(false)
                .setPriority(XmrWalletService.PROTOCOL_FEE_PRIORITY));

        // update state
        BigInteger payoutTxFeeSplit = payoutTx.getFee().divide(BigInteger.valueOf(2));
        getBuyer().setPayoutTxFee(payoutTxFeeSplit);
        getBuyer().setPayoutAmount(HavenoUtils.getDestination(buyerPayoutAddress, payoutTx).getAmount());
        getSeller().setPayoutTxFee(payoutTxFeeSplit);
        getSeller().setPayoutAmount(HavenoUtils.getDestination(sellerPayoutAddress, payoutTx).getAmount());
        getSelf().setUpdatedMultisigHex(multisigWallet.exportMultisigHex());
        return payoutTx;
    }

    /**
     * Process a payout tx.
     *
     * @param payoutTxHex is the payout tx hex to verify
     * @param sign signs the payout tx if true
     * @param publish publishes the signed payout tx if true
     */
    public void processPayoutTx(String payoutTxHex, boolean sign, boolean publish) {
        log.info("Processing payout tx for {} {}", getClass().getSimpleName(), getId());

        // gather relevant info
        MoneroWallet wallet = getWallet();
        Contract contract = getContract();
        BigInteger sellerDepositAmount = wallet.getTx(getSeller().getDepositTxHash()).getIncomingAmount();   // TODO (woodser): redundancy of processModel.getPreparedDepositTxId() vs this.getDepositTxId() necessary or avoidable?
        BigInteger buyerDepositAmount = wallet.getTx(getBuyer().getDepositTxHash()).getIncomingAmount();
        BigInteger tradeAmount = getAmount();

        // describe payout tx
        MoneroTxSet describedTxSet = wallet.describeTxSet(new MoneroTxSet().setMultisigTxHex(payoutTxHex));
        if (describedTxSet.getTxs() == null || describedTxSet.getTxs().size() != 1) throw new IllegalArgumentException("Bad payout tx"); // TODO (woodser): test nack
        MoneroTxWallet payoutTx = describedTxSet.getTxs().get(0);

        // verify payout tx has exactly 2 destinations
        if (payoutTx.getOutgoingTransfer() == null || payoutTx.getOutgoingTransfer().getDestinations() == null || payoutTx.getOutgoingTransfer().getDestinations().size() != 2) throw new IllegalArgumentException("Payout tx does not have exactly two destinations");

        // get buyer and seller destinations (order not preserved)
        boolean buyerFirst = payoutTx.getOutgoingTransfer().getDestinations().get(0).getAddress().equals(contract.getBuyerPayoutAddressString());
        MoneroDestination buyerPayoutDestination = payoutTx.getOutgoingTransfer().getDestinations().get(buyerFirst ? 0 : 1);
        MoneroDestination sellerPayoutDestination = payoutTx.getOutgoingTransfer().getDestinations().get(buyerFirst ? 1 : 0);

        // verify payout addresses
        if (!buyerPayoutDestination.getAddress().equals(contract.getBuyerPayoutAddressString())) throw new IllegalArgumentException("Buyer payout address does not match contract");
        if (!sellerPayoutDestination.getAddress().equals(contract.getSellerPayoutAddressString())) throw new IllegalArgumentException("Seller payout address does not match contract");

        // verify change address is multisig's primary address
        if (!payoutTx.getChangeAmount().equals(BigInteger.ZERO)) log.warn("Dust left in multisig wallet for {} {}: {}", getClass().getSimpleName(), getId(), payoutTx.getChangeAmount());
        if (!payoutTx.getChangeAmount().equals(BigInteger.ZERO) && !payoutTx.getChangeAddress().equals(wallet.getPrimaryAddress())) throw new IllegalArgumentException("Change address is not multisig wallet's primary address");

        // verify sum of outputs = destination amounts + change amount
        if (!payoutTx.getOutputSum().equals(buyerPayoutDestination.getAmount().add(sellerPayoutDestination.getAmount()).add(payoutTx.getChangeAmount()))) throw new IllegalArgumentException("Sum of outputs != destination amounts + change amount");

        // verify buyer destination amount is deposit amount + this amount - 1/2 tx costs
        BigInteger txCost = payoutTx.getFee().add(payoutTx.getChangeAmount());
        BigInteger txCostSplit = txCost.divide(BigInteger.valueOf(2));
        BigInteger expectedBuyerPayout = buyerDepositAmount.add(tradeAmount).subtract(txCostSplit);
        if (!buyerPayoutDestination.getAmount().equals(expectedBuyerPayout)) throw new IllegalArgumentException("Buyer destination amount is not deposit amount + trade amount - 1/2 tx costs, " + buyerPayoutDestination.getAmount() + " vs " + expectedBuyerPayout);

        // verify seller destination amount is deposit amount - this amount - 1/2 tx costs
        BigInteger expectedSellerPayout = sellerDepositAmount.subtract(tradeAmount).subtract(txCostSplit);
        if (!sellerPayoutDestination.getAmount().equals(expectedSellerPayout)) throw new IllegalArgumentException("Seller destination amount is not deposit amount - trade amount - 1/2 tx costs, " + sellerPayoutDestination.getAmount() + " vs " + expectedSellerPayout);

        // check wallet connection
        if (sign || publish) checkDaemonConnection();

        // handle tx signing
        if (sign) {

            // sign tx
            MoneroMultisigSignResult result = wallet.signMultisigTxHex(payoutTxHex);
            if (result.getSignedMultisigTxHex() == null) throw new RuntimeException("Error signing payout tx");
            payoutTxHex = result.getSignedMultisigTxHex();

            // describe result
            describedTxSet = wallet.describeMultisigTxSet(payoutTxHex);
            payoutTx = describedTxSet.getTxs().get(0);

            // verify fee is within tolerance by recreating payout tx
            // TODO (monero-project): creating tx will require exchanging updated multisig hex if message needs reprocessed. provide weight with describe_transfer so fee can be estimated?
            MoneroTxWallet feeEstimateTx = createPayoutTx();;
            BigInteger feeEstimate = feeEstimateTx.getFee();
            double feeDiff = payoutTx.getFee().subtract(feeEstimate).abs().doubleValue() / feeEstimate.doubleValue(); // TODO: use BigDecimal?
            if (feeDiff > XmrWalletService.MINER_FEE_TOLERANCE) throw new IllegalArgumentException("Miner fee is not within " + (XmrWalletService.MINER_FEE_TOLERANCE * 100) + "% of estimated fee, expected " + feeEstimate + " but was " + payoutTx.getFee());
            log.info("Payout tx fee {} is within tolerance, diff %={}", payoutTx.getFee(), feeDiff);
        }

        // update trade state
        setPayoutTx(payoutTx);
        setPayoutTxHex(payoutTxHex);

        // submit payout tx
        if (publish) {
            wallet.submitMultisigTxHex(payoutTxHex);
            pollWallet();
        }
    }

    /**
     * Decrypt the peer's payment account payload using the given key.
     *
     * @param paymentAccountKey is the key to decrypt the payment account payload
     */
    public void decryptPeerPaymentAccountPayload(byte[] paymentAccountKey) {
        try {

            // decrypt payment account payload
            getTradePeer().setPaymentAccountKey(paymentAccountKey);
            SecretKey sk = Encryption.getSecretKeyFromBytes(getTradePeer().getPaymentAccountKey());
            byte[] decryptedPaymentAccountPayload = Encryption.decrypt(getTradePeer().getEncryptedPaymentAccountPayload(), sk);
            CoreNetworkProtoResolver resolver = new CoreNetworkProtoResolver(Clock.systemDefaultZone()); // TODO: reuse resolver from elsewhere?
            PaymentAccountPayload paymentAccountPayload = resolver.fromProto(protobuf.PaymentAccountPayload.parseFrom(decryptedPaymentAccountPayload));

            // verify hash of payment account payload
            byte[] peerPaymentAccountPayloadHash = this instanceof MakerTrade ? getContract().getTakerPaymentAccountPayloadHash() : getContract().getMakerPaymentAccountPayloadHash();
            if (!Arrays.equals(paymentAccountPayload.getHash(), peerPaymentAccountPayloadHash)) throw new RuntimeException("Hash of peer's payment account payload does not match contract");

            // set payment account payload
            getTradePeer().setPaymentAccountPayload(paymentAccountPayload);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nullable
    public MoneroTx getTakerDepositTx() {
        return getDepositTx(getTaker());
    }

    @Nullable
    public MoneroTx getMakerDepositTx() {
        return getDepositTx(getMaker());
    }

    private MoneroTx getDepositTx(TradePeer trader) {
        String depositId = trader.getDepositTxHash();
        if (depositId == null) return null;
        try {
            if (trader.getDepositTx() == null || !trader.getDepositTx().isConfirmed()) {
                MoneroTx depositTx = getDepositTxFromWalletOrDaemon(depositId);
                if (depositTx != null) trader.setDepositTx(depositTx);
            }
            return trader.getDepositTx();
        } catch (MoneroError e) {
            log.error("Error getting {} deposit tx {}: {}", getPeerRole(trader), depositId, e.getMessage()); // TODO: peer.getRole()
            return null;
        }
    }

    private MoneroTx getDepositTxFromWalletOrDaemon(String txId) {
        MoneroTx tx = null;

        // first check wallet
        if (getWallet() != null) {
            List<MoneroTxWallet> filteredTxs = getWallet().getTxs(new MoneroTxQuery()
                    .setHash(txId)
                    .setIsConfirmed(isDepositsConfirmed() ? true : null)); // avoid checking pool if confirmed
            if (filteredTxs.size() == 1) tx = filteredTxs.get(0);
        }

        // then check daemon
        if (tx == null) tx = getXmrWalletService().getTxWithCache(txId);
        return tx;
    }

    public void addAndPersistChatMessage(ChatMessage chatMessage) {
        if (!chatMessages.contains(chatMessage)) {
            chatMessages.add(chatMessage);
        } else {
            log.error("Trade ChatMessage already exists");
        }
    }

    public boolean removeAllChatMessages() {
        if (chatMessages.size() > 0) {
            chatMessages.clear();
            return true;
        }
        return false;
    }

    public boolean mediationResultAppliedPenaltyToSeller() {
        // If mediated payout is same or more then normal payout we enable otherwise a penalty was applied
        // by mediators and we keep the confirm disabled to avoid that the seller can complete the trade
        // without the penalty.
        long payoutAmountFromMediation = processModel.getSellerPayoutAmountFromMediation();
        long normalPayoutAmount = getSeller().getSecurityDeposit().longValueExact();
        return payoutAmountFromMediation < normalPayoutAmount;
    }

    public void maybeClearProcessData() {
        synchronized (walletLock) {

            // skip if already cleared
            if (!walletExists()) return;

            // delete trade wallet
            deleteWallet();

            // TODO: clear other process data
            setPayoutTxHex(null);
            for (TradePeer peer : getPeers()) {
                peer.setUnsignedPayoutTxHex(null);
                peer.setUpdatedMultisigHex(null);
            }
        }
    }

    public void maybeClearSensitiveData() {
        String change = "";
        if (removeAllChatMessages()) {
            change += "chat messages;";
        }
        if (change.length() > 0) {
            log.info("cleared sensitive data from {} of trade {}", change, getShortId());
        }
    }

    public void onShutDownStarted() {
        isShutDownStarted = true;
        if (wallet != null) log.info("{} {} preparing for shut down", getClass().getSimpleName(), getId());
        stopPolling();

        // repeatedly acquire trade lock to allow other threads to finish
        for (int i = 0; i < 20; i++) {
            synchronized (this) {
                synchronized (walletLock) {
                    if (isShutDown) break;
                }
            }
        }
    }

    public void shutDown() {
        if (!isPayoutUnlocked()) log.info("{} {} shutting down", getClass().getSimpleName(), getId());
        synchronized (this) {
            isInitialized = false;
            isShutDown = true;
            synchronized (walletLock) {
                if (wallet != null) {
                    xmrWalletService.saveWallet(wallet, false); // skip backup
                    stopWallet();
                }
            }
            if (tradeStateSubscription != null) tradeStateSubscription.unsubscribe();
            if (tradePhaseSubscription != null) tradePhaseSubscription.unsubscribe();
            if (payoutStateSubscription != null) payoutStateSubscription.unsubscribe();
            idlePayoutSyncer = null; // main wallet removes listener itself
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Model implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onComplete() {
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Abstract
    ///////////////////////////////////////////////////////////////////////////////////////////

    public abstract BigInteger getPayoutAmountBeforeCost();

    public abstract boolean confirmPermitted();


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Setters
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void setStateIfValidTransitionTo(State newState) {
        if (state.isValidTransitionTo(newState)) {
            setState(newState);
        }
    }

    public void addInitProgressStep() {
        initProgress = Math.min(1.0, (double) ++initStep / TOTAL_INIT_STEPS);
        UserThread.execute(() -> initProgressProperty.set(initProgress));
    }

    public void setState(State state) {
        if (isInitialized) {
            // We don't want to log at startup the setState calls from all persisted trades
            log.info("Set new state at {} (id={}): {}", this.getClass().getSimpleName(), getShortId(), state);
        }
        if (state.getPhase().ordinal() < this.state.getPhase().ordinal()) {
            String message = "We got a state change to a previous phase (id=" + getShortId() + ").\n" +
                    "Old state is: " + this.state + ". New state is: " + state;
            log.warn(message);
        }

        this.state = state;
        UserThread.execute(() -> {
            stateProperty.set(state);
            phaseProperty.set(state.getPhase());
        });
    }

    public void advanceState(State state) {
        if (state.ordinal() > getState().ordinal()) setState(state);
    }

    public void setPayoutStateIfValidTransitionTo(PayoutState newPayoutState) {
        if (payoutState.isValidTransitionTo(newPayoutState)) {
            setPayoutState(newPayoutState);
        } else {
            log.warn("Payout state change is not getting applied because it would cause an invalid transition. " +
                    "Trade payout state={}, intended payout state={}", payoutState, newPayoutState);
        }
    }

    public void setPayoutState(PayoutState payoutState) {
        if (isInitialized) {
            // We don't want to log at startup the setState calls from all persisted trades
            log.info("Set new payout state for {} {}: {}", this.getClass().getSimpleName(), getId(), payoutState);
        }
        if (payoutState.ordinal() < this.payoutState.ordinal()) {
            String message = "We got a payout state change to a previous phase (id=" + getShortId() + ").\n" +
                    "Old payout state is: " + this.state + ". New payout state is: " + payoutState;
            log.warn(message);
        }

        this.payoutState = payoutState;
        UserThread.execute(() -> {
            payoutStateProperty.set(payoutState);
        });
    }

    public void setDisputeState(DisputeState disputeState) {
        if (isInitialized) {
            // We don't want to log at startup the setState calls from all persisted trades
            log.info("Set new dispute state for {} {}: {}", this.getClass().getSimpleName(), getShortId(), disputeState);
        }
        if (disputeState.ordinal() < this.disputeState.ordinal()) {
            String message = "We got a dispute state change to a previous state (id=" + getShortId() + ").\n" +
                    "Old dispute state is: " + this.disputeState + ". New dispute state is: " + disputeState;
            log.warn(message);
        }

        this.disputeState = disputeState;
        UserThread.execute(() -> {
            disputeStateProperty.set(disputeState);
        });
    }

    public void advanceDisputeState(DisputeState disputeState) {
        if (disputeState.ordinal() > getDisputeState().ordinal()) setDisputeState(disputeState);
    }

    public List<Dispute> getDisputes() {
        return HavenoUtils.arbitrationManager.findDisputes(getId());
    }

    public void setMediationResultState(MediationResultState mediationResultState) {
        this.mediationResultState = mediationResultState;
        mediationResultStateProperty.set(mediationResultState);
    }

    public void setRefundResultState(RefundResultState refundResultState) {
        this.refundResultState = refundResultState;
        refundResultStateProperty.set(refundResultState);
    }

    public void setPeriodState(TradePeriodState tradePeriodState) {
        this.periodState = tradePeriodState;
        tradePeriodStateProperty.set(tradePeriodState);
    }

    public void setAmount(BigInteger tradeAmount) {
        this.amount = tradeAmount.longValueExact();
        getAmountProperty().set(getAmount());
        getVolumeProperty().set(getVolume());
    }

    public void setPayoutTx(MoneroTxWallet payoutTx) {
        this.payoutTx = payoutTx;
        payoutTxId = payoutTx.getHash();
        if ("".equals(payoutTxId)) payoutTxId = null; // tx id is empty until signed
        payoutTxKey = payoutTx.getKey();
        payoutTxFee = payoutTx.getFee().longValueExact();
        for (Dispute dispute : getDisputes()) dispute.setDisputePayoutTxId(payoutTxId);

        // set final payout amounts
        if (hasPaymentReceivedMessage()) { // TODO: replace with isPaymentReceived() but only if correct when trade completes with dispute
            BigInteger splitTxFee = payoutTx.getFee().divide(BigInteger.valueOf(2));
            getBuyer().setPayoutTxFee(splitTxFee);
            getSeller().setPayoutTxFee(splitTxFee);
            getBuyer().setPayoutAmount(getBuyer().getSecurityDeposit().subtract(getBuyer().getPayoutTxFee()).add(getAmount()));
            getSeller().setPayoutAmount(getSeller().getSecurityDeposit().subtract(getSeller().getPayoutTxFee()));
        } else if (getDisputeState().isClosed()) {
            DisputeResult disputeResult = getDisputeResult();
            if (disputeResult == null) log.warn("Dispute result is not set for {} {}", getClass().getSimpleName(), getId());
            else {
                BigInteger[] buyerSellerPayoutTxFees = ArbitrationManager.getBuyerSellerPayoutTxCost(disputeResult, payoutTx.getFee());
                getBuyer().setPayoutTxFee(buyerSellerPayoutTxFees[0]);
                getSeller().setPayoutTxFee(buyerSellerPayoutTxFees[1]);
                getBuyer().setPayoutAmount(disputeResult.getBuyerPayoutAmountBeforeCost().subtract(getBuyer().getPayoutTxFee()));
                getSeller().setPayoutAmount(disputeResult.getSellerPayoutAmountBeforeCost().subtract(getSeller().getPayoutTxFee()));
            }
        }
    }

    public DisputeResult getDisputeResult() {
        if (getDisputes().isEmpty()) return null;
        return getDisputes().get(getDisputes().size() - 1).getDisputeResultProperty().get();
    }

    @Nullable
    public MoneroTx getPayoutTx() {
        if (payoutTx == null) {
            payoutTx = payoutTxId == null ? null : (this instanceof ArbitratorTrade) ? xmrWalletService.getTxWithCache(payoutTxId) : xmrWalletService.getWallet().getTx(payoutTxId);
        }
        return payoutTx;
    }

    public void setPayoutTxFee(BigInteger payoutTxFee) {
        this.payoutTxFee = payoutTxFee.longValueExact();
    }

    public BigInteger getPayoutTxFee() {
        return BigInteger.valueOf(payoutTxFee);
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
        errorMessageProperty.set(errorMessage);
    }

    public void prependErrorMessage(String errorMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append(errorMessage);
        if (this.errorMessage != null && !this.errorMessage.isEmpty()) {
            sb.append("\n\n---- Previous Error -----\n\n");
            sb.append(this.errorMessage);
        }
        String appendedErrorMessage = sb.toString();
        this.errorMessage = appendedErrorMessage;
        errorMessageProperty.set(appendedErrorMessage);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getter
    ///////////////////////////////////////////////////////////////////////////////////////////

    public boolean isArbitrator() {
        return this instanceof ArbitratorTrade;
    }

    public boolean isBuyer() {
        return getBuyer() == getSelf();
    }

    public boolean isSeller() {
        return getSeller() == getSelf();
    }

    public boolean isMaker() {
        return this instanceof MakerTrade;
    }

    public boolean isTaker() {
        return this instanceof TakerTrade;
    }

    public TradePeer getSelf() {
        if (this instanceof MakerTrade) return processModel.getMaker();
        if (this instanceof TakerTrade) return processModel.getTaker();
        if (this instanceof ArbitratorTrade) return processModel.getArbitrator();
        throw new RuntimeException("Trade is not maker, taker, or arbitrator");
    }

    private List<TradePeer> getPeers() {
        List<TradePeer> peers = new ArrayList<TradePeer>();
        peers.add(getMaker());
        peers.add(getTaker());
        peers.add(getArbitrator());
        if (!peers.remove(getSelf())) throw new IllegalStateException("Failed to remove self from list of peers");
        return peers;
    }

    public TradePeer getArbitrator() {
        return processModel.getArbitrator();
    }

    public TradePeer getMaker() {
        return processModel.getMaker();
    }

    public TradePeer getTaker() {
        return processModel.getTaker();
    }

    public TradePeer getBuyer() {
        return offer.getDirection() == OfferDirection.BUY ? processModel.getMaker() : processModel.getTaker();
    }

    public TradePeer getSeller() {
        return offer.getDirection() == OfferDirection.BUY ? processModel.getTaker() : processModel.getMaker();
    }

    // get the taker if maker, maker if taker, null if arbitrator
    public TradePeer getTradePeer() {
        if (this instanceof MakerTrade) return processModel.getTaker();
        else if (this instanceof TakerTrade) return processModel.getMaker();
        else if (this instanceof ArbitratorTrade) return null;
        else throw new RuntimeException("Unknown trade type: " + getClass().getName());
    }

    // TODO (woodser): this naming convention is confusing
    public TradePeer getTradePeer(NodeAddress address) {
        if (address.equals(getMaker().getNodeAddress())) return processModel.getMaker();
        if (address.equals(getTaker().getNodeAddress())) return processModel.getTaker();
        if (address.equals(getArbitrator().getNodeAddress())) return processModel.getArbitrator();
        return null;
    }

    public TradePeer getTradePeer(PubKeyRing pubKeyRing) {
        if (getMaker() != null && getMaker().getPubKeyRing().equals(pubKeyRing)) return getMaker();
        if (getTaker() != null && getTaker().getPubKeyRing().equals(pubKeyRing)) return getTaker();
        if (getArbitrator() != null && getArbitrator().getPubKeyRing().equals(pubKeyRing)) return getArbitrator();
        return null;
    }

    public String getRole() {
        if (isBuyer()) return "Buyer";
        if (isSeller()) return "Seller";
        if (isArbitrator()) return "Arbitrator";
        throw new IllegalArgumentException("Trade is not buyer, seller, or arbitrator");
    }

    public String getPeerRole(TradePeer peer) {
        if (peer == getBuyer()) return "Buyer";
        if (peer == getSeller()) return "Seller";
        if (peer == getArbitrator()) return "Arbitrator";
        throw new IllegalArgumentException("Peer is not buyer, seller, or arbitrator");
    }

    public Date getTakeOfferDate() {
        return new Date(takeOfferDate);
    }

    public Phase getPhase() {
        return state.getPhase();
    }

    @Nullable
    public Volume getVolume() {
        try {
            if (getAmount() != null && getPrice() != null) {
                Volume volumeByAmount = getPrice().getVolumeByAmount(getAmount());
                if (offer != null) volumeByAmount = VolumeUtil.getAdjustedVolume(volumeByAmount, offer.getPaymentMethod().getId());
                return volumeByAmount;
            } else {
                return null;
            }
        } catch (Throwable ignore) {
            return null;
        }
    }

    public Date getHalfTradePeriodDate() {
        return new Date(getStartTime() + getMaxTradePeriod() / 2);
    }

    public Date getMaxTradePeriodDate() {
        return new Date(getStartTime() + getMaxTradePeriod());
    }

    private long getMaxTradePeriod() {
        return getOffer().getPaymentMethod().getMaxTradePeriod();
    }

    private long getStartTime() {
        long now = System.currentTimeMillis();
        if (isDepositsConfirmed() && getTakeOfferDate() != null) {
            if (isDepositsUnlocked()) {
                if (startTime <= 0) setStartTimeFromUnlockedTxs(); // save to model
                return startTime;
            } else {
                log.debug("depositTx not confirmed yet. We don't start counting remaining trade period yet. makerTxId={}, takerTxId={}", getMaker().getDepositTxHash(), getTaker().getDepositTxHash());
                return now;
            }
        } else {
            return now;
        }
    }

    private void setStartTimeFromUnlockedTxs() {
        long now = System.currentTimeMillis();
        final long tradeTime = getTakeOfferDate().getTime();
        MoneroDaemon daemonRpc = xmrWalletService.getDaemon();
        if (daemonRpc == null) throw new RuntimeException("Cannot set start time for trade " + getId() + " because it has no connection to monerod");
        if (getMakerDepositTx() == null || getTakerDepositTx() == null) throw new RuntimeException("Cannot set start time for trade " + getId() + " because its unlocked deposit tx is null. Is client connected to a daemon?");

        long maxHeight = Math.max(getMakerDepositTx().getHeight(), getTakerDepositTx().getHeight());
        long blockTime = daemonRpc.getBlockByHeight(maxHeight).getTimestamp();

        // If block date is in future (Date in blocks can be off by +/- 2 hours) we use our current date.
        // If block date is earlier than our trade date we use our trade date.
        if (blockTime > now)
            startTime = now;
        else
            startTime = Math.max(blockTime, tradeTime);

        log.debug("We set the start for the trade period to {}. Trade started at: {}. Block got mined at: {}",
                new Date(startTime), new Date(tradeTime), new Date(blockTime));
    }

    public boolean hasFailed() {
        return errorMessageProperty().get() != null;
    }

    public boolean isInPreparation() {
        return getState().getPhase().ordinal() == Phase.INIT.ordinal();
    }

    public boolean isDepositRequested() {
        return getState().getPhase().ordinal() >= Phase.DEPOSIT_REQUESTED.ordinal();
    }

    public boolean isDepositFailed() {
        return getState() == Trade.State.PUBLISH_DEPOSIT_TX_REQUEST_FAILED;
    }

    public boolean isDepositsPublished() {
        return getState().getPhase().ordinal() >= Phase.DEPOSITS_PUBLISHED.ordinal() && getMaker().getDepositTxHash() != null && getTaker().getDepositTxHash() != null;
    }

    public boolean isFundsLockedIn() {
        return isDepositsPublished() && !isPayoutPublished();
    }

    public boolean isDepositsConfirmed() {
        return isDepositsPublished() && getState().getPhase().ordinal() >= Phase.DEPOSITS_CONFIRMED.ordinal();
    }

    // TODO: hacky way to check for deposits confirmed acks, redundant with getDepositsConfirmedTasks()
    public boolean isDepositsConfirmedAcked() {
        if (this instanceof BuyerTrade) {
            return getArbitrator().isDepositsConfirmedMessageAcked();
        } else {
            for (TradePeer peer : getPeers()) if (!peer.isDepositsConfirmedMessageAcked()) return false;
            return true;
        }
    }

    public boolean isDepositsUnlocked() {
        return isDepositsPublished() && getState().getPhase().ordinal() >= Phase.DEPOSITS_UNLOCKED.ordinal();
    }

    public boolean isPaymentSent() {
        return getState().getPhase().ordinal() >= Phase.PAYMENT_SENT.ordinal();
    }

    public boolean hasPaymentReceivedMessage() {
        return (isSeller() ? getBuyer() : getSeller()).getPaymentReceivedMessage() != null; // seller stores message to buyer and arbitrator, peers store message from seller
    }

    public boolean isPaymentReceived() {
        return getState().getPhase().ordinal() >= Phase.PAYMENT_RECEIVED.ordinal();
    }

    public boolean isCompleted() {
        return getState().getPhase().ordinal() >= Phase.COMPLETED.ordinal();
    }

    public boolean isPayoutPublished() {
        return getPayoutState().ordinal() >= PayoutState.PAYOUT_PUBLISHED.ordinal();
    }

    public boolean isPayoutConfirmed() {
        return getPayoutState().ordinal() >= PayoutState.PAYOUT_CONFIRMED.ordinal();
    }

    public boolean isPayoutUnlocked() {
        return getPayoutState().ordinal() >= PayoutState.PAYOUT_UNLOCKED.ordinal();
    }

    public ReadOnlyDoubleProperty initProgressProperty() {
        return initProgressProperty;
    }

    public ReadOnlyObjectProperty<State> stateProperty() {
        return stateProperty;
    }

    public ReadOnlyObjectProperty<Phase> statePhaseProperty() {
        return phaseProperty;
    }

    public ReadOnlyObjectProperty<PayoutState> payoutStateProperty() {
        return payoutStateProperty;
    }

    public ReadOnlyObjectProperty<DisputeState> disputeStateProperty() {
        return disputeStateProperty;
    }

    public ReadOnlyObjectProperty<MediationResultState> mediationResultStateProperty() {
        return mediationResultStateProperty;
    }

    public ReadOnlyObjectProperty<RefundResultState> refundResultStateProperty() {
        return refundResultStateProperty;
    }

    public ReadOnlyObjectProperty<TradePeriodState> tradePeriodStateProperty() {
        return tradePeriodStateProperty;
    }

    public ReadOnlyObjectProperty<BigInteger> tradeAmountProperty() {
        return tradeAmountProperty;
    }

    public ReadOnlyObjectProperty<Volume> tradeVolumeProperty() {
        return tradeVolumeProperty;
    }

    public ReadOnlyStringProperty errorMessageProperty() {
        return errorMessageProperty;
    }

    @Override
    public Date getDate() {
        return getTakeOfferDate();
    }

    @Override
    public String getId() {
        return offer.getId();
    }

    @Override
    public String getShortId() {
        return offer.getShortId();
    }

    public BigInteger getFrozenAmount() {
        BigInteger sum = BigInteger.ZERO;
        for (String keyImage : getSelf().getReserveTxKeyImages()) {
            List<MoneroOutputWallet> outputs = xmrWalletService.getWallet().getOutputs(new MoneroOutputQuery().setIsFrozen(true).setIsSpent(false).setKeyImage(new MoneroKeyImage(keyImage))); // TODO: will this check tx pool? avoid
            if (!outputs.isEmpty()) sum = sum.add(outputs.get(0).getAmount());
        }
        return sum;
    }

    public BigInteger getReservedAmount() {
        if (isArbitrator() || !isDepositsPublished() || isPayoutPublished()) return BigInteger.ZERO;
        return isBuyer() ? getBuyer().getSecurityDeposit() : getAmount().add(getSeller().getSecurityDeposit());
    }

    public Price getPrice() {
        return Price.valueOf(offer.getCurrencyCode(), price);
    }

    @Nullable
    public BigInteger getAmount() {
        return BigInteger.valueOf(amount);
    }

    public BigInteger getMakerFee() {
        return offer.getMakerFee();
    }

    public BigInteger getTakerFee() {
        return BigInteger.valueOf(takerFee);
    }

    public BigInteger getBuyerSecurityDepositBeforeMiningFee() {
        return offer.getOfferPayload().getBuyerSecurityDepositForTradeAmount(getAmount());
    }

    public BigInteger getSellerSecurityDepositBeforeMiningFee() {
        return offer.getOfferPayload().getSellerSecurityDepositForTradeAmount(getAmount());
    }

    @Override
    public BigInteger getTotalTxFee() {
        return getSelf().getDepositTxFee().add(getSelf().getPayoutTxFee()); // sum my tx fees
    }

    public boolean hasErrorMessage() {
        return getErrorMessage() != null && !getErrorMessage().isEmpty();
    }

    @Nullable
    public String getErrorMessage() {
        return errorMessageProperty.get();
    }

    public boolean isTxChainInvalid() {
        return processModel.getMaker().getDepositTxHash() == null || processModel.getTaker().getDepositTxHash() == null;
    }

    /**
     * Get the duration to delay reprocessing a message based on its reprocess count.
     *
     * @return the duration to delay in seconds
     */
    public long getReprocessDelayInSeconds(int reprocessCount) {
        int retryCycles = 3; // reprocess on next refresh periods for first few attempts (app might auto switch to a good connection)
        if (reprocessCount < retryCycles) return xmrWalletService.getConnectionService().getRefreshPeriodMs() / 1000;
        long delay = 60;
        for (int i = retryCycles; i < reprocessCount; i++) delay *= 2;
        return Math.min(MAX_REPROCESS_DELAY_SECONDS, delay);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    // lazy initialization
    private ObjectProperty<BigInteger> getAmountProperty() {
        if (tradeAmountProperty == null)
            tradeAmountProperty = getAmount() != null ? new SimpleObjectProperty<>(getAmount()) : new SimpleObjectProperty<>();

        return tradeAmountProperty;
    }

    // lazy initialization
    private ObjectProperty<Volume> getVolumeProperty() {
        if (tradeVolumeProperty == null)
            tradeVolumeProperty = getVolume() != null ? new SimpleObjectProperty<>(getVolume()) : new SimpleObjectProperty<>();
        return tradeVolumeProperty;
    }

    private void onConnectionChanged(MoneroRpcConnection connection) {
        synchronized (walletLock) {

            // check if ignored
            if (isShutDownStarted) return;
            if (getWallet() == null) return;
            if (HavenoUtils.connectionConfigsEqual(connection, wallet.getDaemonConnection())) return;

            // set daemon connection (must restart monero-wallet-rpc if proxy uri changed)
            String oldProxyUri = wallet.getDaemonConnection() == null ? null : wallet.getDaemonConnection().getProxyUri();
            String newProxyUri = connection == null ? null : connection.getProxyUri();
            log.info("Setting daemon connection for trade wallet {}: uri={}, proxyUri={}", getId() , connection == null ? null : connection.getUri(), newProxyUri);
            if (xmrWalletService.isProxyApplied(wasWalletSynced) && wallet instanceof MoneroWalletRpc && !StringUtils.equals(oldProxyUri, newProxyUri)) {
                log.info("Restarting trade wallet {} because proxy URI has changed, old={}, new={}", getId(), oldProxyUri, newProxyUri);
                closeWallet();
                wallet = getWallet();
            } else {
                wallet.setDaemonConnection(connection);
            }

            // sync and reprocess messages on new thread
            if (isInitialized && connection != null && !Boolean.FALSE.equals(connection.isConnected())) {
                HavenoUtils.submitTask(() -> {
                    initSyncing();
                });
            }
        }
    }

    private void initSyncing() {
        if (isShutDownStarted) return;
        if (!isIdling()) {
            initSyncingAux();
        }  else {
            long startSyncingInMs = ThreadLocalRandom.current().nextLong(0, getWalletRefreshPeriod()); // random time to start syncing
            UserThread.runAfter(() -> {
                if (!isShutDownStarted) {
                    initSyncingAux();
                }
            }, startSyncingInMs / 1000l);
        }
    }
    
    private void initSyncingAux() {
        if (!wasWalletSynced) trySyncWallet(false);
        updateWalletRefreshPeriod();
        
        // reprocess pending payout messages
        this.getProtocol().maybeReprocessPaymentReceivedMessage(false);
        HavenoUtils.arbitrationManager.maybeReprocessDisputeClosedMessage(this, false);

        startPolling();
    }

    private void trySyncWallet(boolean pollWallet) {
        try {
            syncWallet(pollWallet);
        } catch (Exception e) {
            if (!isShutDown && walletExists()) {
                log.warn("Error syncing trade wallet for {} {}: {}", getClass().getSimpleName(), getId(), e.getMessage());
            }
        }
    }

    private void syncWallet(boolean pollWallet) {
        if (getWallet() == null) throw new RuntimeException("Cannot sync trade wallet because it doesn't exist for " + getClass().getSimpleName() + ", " + getId());
        if (getWallet().getDaemonConnection() == null) throw new RuntimeException("Cannot sync trade wallet because it's not connected to a Monero daemon for " + getClass().getSimpleName() + ", " + getId());
        log.info("Syncing wallet for {} {}", getClass().getSimpleName(), getId());
        xmrWalletService.syncWallet(getWallet());
        log.info("Done syncing wallet for {} {}", getClass().getSimpleName(), getId());

        // apply tor after wallet synced depending on configuration
        if (!wasWalletSynced) {
            wasWalletSynced = true;
            if (xmrWalletService.isProxyApplied(wasWalletSynced)) {
                onConnectionChanged(xmrWalletService.getConnectionService().getConnection());
            }
        }

        if (pollWallet) pollWallet();
    }

    public void updateWalletRefreshPeriod() {
        if (isShutDownStarted) return;
        setWalletRefreshPeriod(getWalletRefreshPeriod());
    }

    private void setWalletRefreshPeriod(long walletRefreshPeriodMs) {
        synchronized (walletLock) {
            if (this.isShutDownStarted) return;
            if (this.walletRefreshPeriodMs != null && this.walletRefreshPeriodMs == walletRefreshPeriodMs) return;
            this.walletRefreshPeriodMs = walletRefreshPeriodMs;
            if (getWallet() != null) {
                log.info("Setting wallet refresh rate for {} {} to {}", getClass().getSimpleName(), getId(), getWalletRefreshPeriod());
                getWallet().startSyncing(getWalletRefreshPeriod()); // TODO (monero-project): wallet rpc waits until last sync period finishes before starting new sync period
            }
            if (isPolling()) {
                stopPolling();
                startPolling();
            }
        }
    }

    private void startPolling() {
        synchronized (walletLock) {
            if (isShutDownStarted || isPolling()) return;
            log.info("Starting to poll wallet for {} {}", getClass().getSimpleName(), getId());
            txPollLooper = new TaskLooper(() -> pollWallet());
            txPollLooper.start(walletRefreshPeriodMs);
        }
    }

    private void stopPolling() {
        synchronized (walletLock) {
            if (isPolling()) {
                txPollLooper.stop();
                txPollLooper = null;
            }
        }
    }
    
    private boolean isPolling() {
        synchronized (walletLock) {
            return txPollLooper != null;
        }
    }

    private void pollWallet() {
        synchronized (walletLock) {
            MoneroWallet wallet = getWallet();
            try {

                // log warning if wallet is too far behind daemon
                MoneroDaemonInfo lastInfo = xmrWalletService.getConnectionService().getLastInfo();
                long walletHeight = wallet.getHeight();
                if (wasWalletSynced && isDepositsPublished() && lastInfo != null && walletHeight < lastInfo.getHeight() - 3 && !Config.baseCurrencyNetwork().isTestnet()) {
                    log.warn("Wallet is more than 3 blocks behind monerod for {} {}, wallet height={}, monerod height={},", getClass().getSimpleName(), getShortId(), walletHeight, lastInfo.getHeight());
                }

                // skip if either deposit tx id is unknown
                if (processModel.getMaker().getDepositTxHash() == null || processModel.getTaker().getDepositTxHash() == null) return;

                // skip if payout unlocked
                if (isPayoutUnlocked()) return;

                // rescan spent outputs to detect payout tx after deposits unlocked
                if (isDepositsUnlocked() && !isPayoutPublished()) wallet.rescanSpent();

                // get txs from trade wallet
                boolean payoutExpected = isPaymentReceived() || getSeller().getPaymentReceivedMessage() != null || disputeState.ordinal() >= DisputeState.ARBITRATOR_SENT_DISPUTE_CLOSED_MSG.ordinal() || getArbitrator().getDisputeClosedMessage() != null;
                boolean checkPool = !isDepositsConfirmed() || (!isPayoutConfirmed() && payoutExpected);
                MoneroTxQuery query = new MoneroTxQuery().setIncludeOutputs(true);
                if (!checkPool) query.setInTxPool(false); // avoid pool check if possible
                List<MoneroTxWallet> txs = wallet.getTxs(query);

                // warn on double spend // TODO: other handling?
                for (MoneroTxWallet tx : txs) {
                    if (Boolean.TRUE.equals(tx.isDoubleSpendSeen())) log.warn("Double spend seen for tx {} for {} {}", tx.getHash(), getClass().getSimpleName(), getId());
                }

                // check deposit txs
                if (!isDepositsUnlocked()) {
    
                    // update trader txs
                    MoneroTxWallet makerDepositTx = null;
                    MoneroTxWallet takerDepositTx = null;
                    for (MoneroTxWallet tx : txs) {
                        if (tx.getHash().equals(processModel.getMaker().getDepositTxHash())) makerDepositTx = tx;
                        if (tx.getHash().equals(processModel.getTaker().getDepositTxHash())) takerDepositTx = tx;
                    }
                    if (makerDepositTx != null) getMaker().setDepositTx(makerDepositTx);
                    if (takerDepositTx != null) getTaker().setDepositTx(takerDepositTx);

                    // skip if deposit txs not seen
                    if (makerDepositTx == null || takerDepositTx == null) return;

                    // set security deposits
                    if (getBuyer().getSecurityDeposit().longValueExact() == 0) {
                        BigInteger buyerSecurityDeposit = ((MoneroTxWallet) getBuyer().getDepositTx()).getIncomingAmount();
                        BigInteger sellerSecurityDeposit = ((MoneroTxWallet) getSeller().getDepositTx()).getIncomingAmount().subtract(getAmount());
                        getBuyer().setSecurityDeposit(buyerSecurityDeposit);
                        getSeller().setSecurityDeposit(sellerSecurityDeposit);
                    }

                    // update state
                    setStateDepositsPublished();
                    if (makerDepositTx.isConfirmed() && takerDepositTx.isConfirmed()) setStateDepositsConfirmed();
                    if (!makerDepositTx.isLocked() && !takerDepositTx.isLocked()) setStateDepositsUnlocked();
                }

                // check payout tx
                if (isDepositsUnlocked()) {

                    // check if any outputs spent (observed on payout published)
                    for (MoneroTxWallet tx : txs) {
                        for (MoneroOutputWallet output : tx.getOutputsWallet()) {
                            if (Boolean.TRUE.equals(output.isSpent()))  {
                                setPayoutStatePublished();
                            }
                        }
                    }

                    // check for outgoing txs (appears after wallet submits payout tx or on payout confirmed)
                    for (MoneroTxWallet tx : txs) {
                        if (tx.isOutgoing()) {
                            setPayoutTx(tx);
                            setPayoutStatePublished();
                            if (tx.isConfirmed()) setPayoutStateConfirmed();
                            if (!tx.isLocked()) setPayoutStateUnlocked();
                        }
                    }
                }
            } catch (Exception e) {
                if (!isShutDownStarted && wallet != null && isWalletConnected()) {
                    e.printStackTrace();
                    log.warn("Error polling trade wallet for {} {}: {}. Monerod={}", getClass().getSimpleName(), getId(), e.getMessage(), getXmrWalletService().getConnectionService().getConnection());
                }
            }
        }
    }

    private long getWalletRefreshPeriod() {
        if (isIdling()) return IDLE_SYNC_PERIOD_MS;
        return xmrWalletService.getConnectionService().getRefreshPeriodMs();
    }

    private void setStateDepositsPublished() {
        if (!isDepositsPublished()) setState(State.DEPOSIT_TXS_SEEN_IN_NETWORK);
    }

    private void setStateDepositsConfirmed() {
        if (!isDepositsConfirmed()) setState(State.DEPOSIT_TXS_CONFIRMED_IN_BLOCKCHAIN);
    }

    private void setStateDepositsUnlocked() {
        if (!isDepositsUnlocked()) {
            setState(State.DEPOSIT_TXS_UNLOCKED_IN_BLOCKCHAIN);
            setStartTimeFromUnlockedTxs();
        }
    }

    private void setPayoutStatePublished() {
        if (!isPayoutPublished()) setPayoutState(PayoutState.PAYOUT_PUBLISHED);
    }

    private void setPayoutStateConfirmed() {
        if (!isPayoutConfirmed()) setPayoutState(PayoutState.PAYOUT_CONFIRMED);
    }

    private void setPayoutStateUnlocked() {
        if (!isPayoutUnlocked()) setPayoutState(PayoutState.PAYOUT_UNLOCKED);
    }

    /**
     * Listen to block notifications from the main wallet in order to sync
     * idling trade wallets awaiting the payout to confirm or unlock.
     */
    private class IdlePayoutSyncer extends MoneroWalletListener {

        boolean processing = false;

        @Override
        public void onNewBlock(long height) {
            HavenoUtils.submitTask(() -> { // allow rapid notifications

                // skip rapid succession blocks
                synchronized (this) {
                    if (processing) return;
                    processing = true;
                }

                // skip if not idling and not waiting for payout to unlock
                if (!isIdling() || !isPayoutPublished() || isPayoutUnlocked())  {
                    processing = false;
                    return;
                }

                try {

                    // get payout height if unknown
                    if (payoutHeight == null && getPayoutTxId() != null) {
                        MoneroTx tx = xmrWalletService.getDaemon().getTx(getPayoutTxId());
                        if (tx.isConfirmed()) payoutHeight = tx.getHeight();
                    }

                    // sync wallet if confirm or unlock expected
                    long currentHeight = xmrWalletService.getDaemon().getHeight();
                    if (!isPayoutConfirmed() || (payoutHeight != null && currentHeight >= payoutHeight + XmrWalletService.NUM_BLOCKS_UNLOCK)) {
                        log.info("Syncing idle trade wallet to update payout tx, tradeId={}", getId());
                        syncAndPollWallet();
                    }
                    processing = false;
                } catch (Exception e) {
                    processing = false;
                    e.printStackTrace();
                    if (isInitialized && !isShutDownStarted && !isWalletConnected()) throw e;
                }
            });
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public Message toProtoMessage() {
        protobuf.Trade.Builder builder = protobuf.Trade.newBuilder()
                .setOffer(offer.toProtoMessage())
                .setTakerFee(takerFee)
                .setTakeOfferDate(takeOfferDate)
                .setProcessModel(processModel.toProtoMessage())
                .setAmount(amount)
                .setPrice(price)
                .setState(Trade.State.toProtoMessage(state))
                .setPayoutState(Trade.PayoutState.toProtoMessage(payoutState))
                .setDisputeState(Trade.DisputeState.toProtoMessage(disputeState))
                .setPeriodState(Trade.TradePeriodState.toProtoMessage(periodState))
                .addAllChatMessage(chatMessages.stream()
                        .map(msg -> msg.toProtoNetworkEnvelope().getChatMessage())
                        .collect(Collectors.toList()))
                .setLockTime(lockTime)
                .setStartTime(startTime)
                .setUid(uid);

        Optional.ofNullable(payoutTxId).ifPresent(builder::setPayoutTxId);
        Optional.ofNullable(contract).ifPresent(e -> builder.setContract(contract.toProtoMessage()));
        Optional.ofNullable(contractAsJson).ifPresent(builder::setContractAsJson);
        Optional.ofNullable(contractHash).ifPresent(e -> builder.setContractHash(ByteString.copyFrom(contractHash)));
        Optional.ofNullable(errorMessage).ifPresent(builder::setErrorMessage);
        Optional.ofNullable(counterCurrencyTxId).ifPresent(e -> builder.setCounterCurrencyTxId(counterCurrencyTxId));
        Optional.ofNullable(mediationResultState).ifPresent(e -> builder.setMediationResultState(MediationResultState.toProtoMessage(mediationResultState)));
        Optional.ofNullable(refundResultState).ifPresent(e -> builder.setRefundResultState(RefundResultState.toProtoMessage(refundResultState)));
        Optional.ofNullable(payoutTxHex).ifPresent(e -> builder.setPayoutTxHex(payoutTxHex));
        Optional.ofNullable(payoutTxKey).ifPresent(e -> builder.setPayoutTxKey(payoutTxKey));
        Optional.ofNullable(counterCurrencyExtraData).ifPresent(e -> builder.setCounterCurrencyExtraData(counterCurrencyExtraData));
        return builder.build();
    }

    public static Trade fromProto(Trade trade, protobuf.Trade proto, CoreProtoResolver coreProtoResolver) {
        trade.setTakeOfferDate(proto.getTakeOfferDate());
        trade.setState(State.fromProto(proto.getState()));
        trade.setPayoutState(PayoutState.fromProto(proto.getPayoutState()));
        trade.setDisputeState(DisputeState.fromProto(proto.getDisputeState()));
        trade.setPeriodState(TradePeriodState.fromProto(proto.getPeriodState()));
        trade.setPayoutTxId(ProtoUtil.stringOrNullFromProto(proto.getPayoutTxId()));
        trade.setPayoutTxHex(ProtoUtil.stringOrNullFromProto(proto.getPayoutTxHex()));
        trade.setPayoutTxKey(ProtoUtil.stringOrNullFromProto(proto.getPayoutTxKey()));
        trade.setContract(proto.hasContract() ? Contract.fromProto(proto.getContract(), coreProtoResolver) : null);
        trade.setContractAsJson(ProtoUtil.stringOrNullFromProto(proto.getContractAsJson()));
        trade.setContractHash(ProtoUtil.byteArrayOrNullFromProto(proto.getContractHash()));
        trade.setErrorMessage(ProtoUtil.stringOrNullFromProto(proto.getErrorMessage()));
        trade.setCounterCurrencyTxId(proto.getCounterCurrencyTxId().isEmpty() ? null : proto.getCounterCurrencyTxId());
        trade.setMediationResultState(MediationResultState.fromProto(proto.getMediationResultState()));
        trade.setRefundResultState(RefundResultState.fromProto(proto.getRefundResultState()));
        trade.setLockTime(proto.getLockTime());
        trade.setStartTime(proto.getStartTime());
        trade.setCounterCurrencyExtraData(ProtoUtil.stringOrNullFromProto(proto.getCounterCurrencyExtraData()));

        trade.chatMessages.addAll(proto.getChatMessageList().stream()
                .map(ChatMessage::fromPayloadProto)
                .collect(Collectors.toList()));

        return trade;
    }

    @Override
    public String toString() {
        return "Trade{" +
                "\n     offer=" + offer +
                ",\n     takerFee=" + takerFee +
                ",\n     totalTxFee=" + getTotalTxFee() +
                ",\n     takeOfferDate=" + takeOfferDate +
                ",\n     processModel=" + processModel +
                ",\n     payoutTxId='" + payoutTxId + '\'' +
                ",\n     amount=" + amount +
                ",\n     tradePrice=" + price +
                ",\n     state=" + state +
                ",\n     payoutState=" + payoutState +
                ",\n     disputeState=" + disputeState +
                ",\n     tradePeriodState=" + periodState +
                ",\n     contract=" + contract +
                ",\n     contractAsJson='" + contractAsJson + '\'' +
                ",\n     contractHash=" + Utilities.bytesAsHexString(contractHash) +
                ",\n     errorMessage='" + errorMessage + '\'' +
                ",\n     counterCurrencyTxId='" + counterCurrencyTxId + '\'' +
                ",\n     counterCurrencyExtraData='" + counterCurrencyExtraData + '\'' +
                ",\n     chatMessages=" + chatMessages +
                ",\n     takerFee=" + takerFee +
                ",\n     xmrWalletService=" + xmrWalletService +
                ",\n     stateProperty=" + stateProperty +
                ",\n     statePhaseProperty=" + phaseProperty +
                ",\n     disputeStateProperty=" + disputeStateProperty +
                ",\n     tradePeriodStateProperty=" + tradePeriodStateProperty +
                ",\n     errorMessageProperty=" + errorMessageProperty +
                ",\n     payoutTx=" + payoutTx +
                ",\n     amount=" + amount +
                ",\n     tradeAmountProperty=" + tradeAmountProperty +
                ",\n     tradeVolumeProperty=" + tradeVolumeProperty +
                ",\n     mediationResultState=" + mediationResultState +
                ",\n     mediationResultStateProperty=" + mediationResultStateProperty +
                ",\n     lockTime=" + lockTime +
                ",\n     startTime=" + startTime +
                ",\n     refundResultState=" + refundResultState +
                ",\n     refundResultStateProperty=" + refundResultStateProperty +
                "\n}";
    }
}
