/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

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
import haveno.common.ThreadUtils;
import haveno.common.UserThread;
import haveno.common.config.Config;
import haveno.common.crypto.Encryption;
import haveno.common.crypto.PubKeyRing;
import haveno.common.proto.ProtoUtil;
import haveno.common.taskrunner.Model;
import haveno.common.util.Utilities;
import haveno.core.locale.Res;
import haveno.core.monetary.Price;
import haveno.core.monetary.Volume;
import haveno.core.network.MessageState;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OpenOffer;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.proto.CoreProtoResolver;
import haveno.core.proto.network.CoreNetworkProtoResolver;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.DisputeResult;
import haveno.core.support.dispute.DisputeResult.Winner;
import haveno.core.support.dispute.mediation.MediationResultState;
import haveno.core.support.dispute.refund.RefundResultState;
import haveno.core.support.messages.ChatMessage;
import haveno.core.trade.messages.TradeMessage;
import haveno.core.trade.protocol.ProcessModel;
import haveno.core.trade.protocol.ProcessModelServiceProvider;
import haveno.core.trade.protocol.SellerProtocol;
import haveno.core.trade.protocol.TradeListener;
import haveno.core.trade.protocol.TradePeer;
import haveno.core.trade.protocol.TradeProtocol;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.core.util.PriceUtil;
import haveno.core.util.VolumeUtil;
import haveno.core.xmr.model.XmrAddressEntry;
import haveno.core.xmr.wallet.XmrWalletBase;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.AckMessage;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.network.TorNetworkNode;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
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
import monero.daemon.model.MoneroKeyImage;
import monero.daemon.model.MoneroTx;
import monero.wallet.MoneroWallet;
import monero.wallet.MoneroWalletRpc;
import monero.wallet.model.MoneroDestination;
import monero.wallet.model.MoneroMultisigSignResult;
import monero.wallet.model.MoneroOutputQuery;
import monero.wallet.model.MoneroOutputWallet;
import monero.wallet.model.MoneroSyncResult;
import monero.wallet.model.MoneroTxConfig;
import monero.wallet.model.MoneroTxQuery;
import monero.wallet.model.MoneroTxSet;
import monero.wallet.model.MoneroTxWallet;
import monero.wallet.model.MoneroWalletListener;

import org.bitcoinj.core.Coin;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Holds all data which are relevant to the trade, but not those which are only needed in the trade process as shared data between tasks. Those data are
 * stored in the task model.
 */
@Slf4j
public abstract class Trade extends XmrWalletBase implements Tradable, Model {

    @Getter
    public final Object lock = new Object();
    private static final String MONERO_TRADE_WALLET_PREFIX = "xmr_trade_";
    private static final long SHUTDOWN_TIMEOUT_MS = Config.baseCurrencyNetwork().isTestnet() ? 20000 : 60000;
    private static final long SYNC_EVERY_NUM_BLOCKS = Config.baseCurrencyNetwork().isTestnet() ? 40 : 360; // ~1/2 day
    private static final long DELETE_AFTER_NUM_BLOCKS = 2; // if deposit requested but not published
    private static final long EXTENDED_RPC_TIMEOUT = 600000; // 10 minutes
    private static final long DELETE_AFTER_MS = TradeProtocol.TRADE_STEP_TIMEOUT_SECONDS;
    private static final int NUM_CONFIRMATIONS_FOR_SCHEDULED_IMPORT = 5;
    public static final int NUM_BLOCKS_DEPOSITS_FINALIZED = 30; // ~1 hour before deposits are considered finalized
    public static final int NUM_BLOCKS_PAYOUT_FINALIZED = Config.baseCurrencyNetwork().isTestnet() ? 60 : 720; // ~1 day before payout is considered finalized and multisig wallet deleted
    public static final long DEFER_PUBLISH_MS = 25000; // 25 seconds
    public static final long POLL_WALLET_NORMALLY_DEFAULT_PERIOD_MS = 120000; // 2 minutes
    private static final long IDLE_SYNC_PERIOD_MS = Config.baseCurrencyNetwork().isTestnet() ? 60000 : 28 * 60 * 1000; // 28 minutes (monero's default connection timeout is 30 minutes on a local connection, so beyond this the wallets will disconnect)
    private static final long MAX_REPROCESS_DELAY_SECONDS = 7200; // max delay to reprocess messages (once per 2 hours)
    private static final long REVERT_AFTER_NUM_CONFIRMATIONS = 2;
    protected final Object pollLock = new Object();
    private final Object removeTradeOnErrorLock = new Object();
    private boolean pollInProgress;
    private boolean restartInProgress;
    private Subscription protocolErrorStateSubscription;
    private Subscription protocolErrorHeightSubscription;
    public static final String PROTOCOL_VERSION = "protocolVersion"; // key for extraDataMap in trade statistics
    public BooleanProperty wasWalletPolledProperty = new SimpleBooleanProperty(false);
    public BooleanProperty wasWalletSyncedAndPolledProperty = new SimpleBooleanProperty(false);
    private static final long MISSING_TXS_DELAY_MS = Config.baseCurrencyNetwork().isTestnet() ? 5000 : 30000;
    private Long firstDepositTxMissingHeight; // height when we first saw missing deposit txs (to wait for a confirmation before reverting state)
    private Long firstPayoutTxMissingHeight; // height when we first saw missing payout tx (to wait for a confirmation before reverting state)
    private boolean skipNextPollLoop = false;

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

        // deposits published
        ARBITRATOR_PUBLISHED_DEPOSIT_TXS(Phase.DEPOSITS_PUBLISHED),
        DEPOSIT_TXS_SEEN_IN_NETWORK(Phase.DEPOSITS_PUBLISHED),

        // deposits confirmed
        DEPOSIT_TXS_CONFIRMED_IN_BLOCKCHAIN(Phase.DEPOSITS_CONFIRMED),

        // deposits unlocked
        DEPOSIT_TXS_UNLOCKED_IN_BLOCKCHAIN(Phase.DEPOSITS_UNLOCKED),

        // deposits finalized
        DEPOSIT_TXS_FINALIZED_IN_BLOCKCHAIN(Phase.DEPOSITS_FINALIZED),

        // payment sent
        BUYER_CONFIRMED_PAYMENT_SENT(Phase.PAYMENT_SENT),
        BUYER_SENT_PAYMENT_SENT_MSG(Phase.PAYMENT_SENT),
        BUYER_SEND_FAILED_PAYMENT_SENT_MSG(Phase.PAYMENT_SENT),
        BUYER_STORED_IN_MAILBOX_PAYMENT_SENT_MSG(Phase.PAYMENT_SENT),
        BUYER_SAW_ARRIVED_PAYMENT_SENT_MSG(Phase.PAYMENT_SENT),
        SELLER_RECEIVED_PAYMENT_SENT_MSG(Phase.PAYMENT_SENT),

        // payment received
        SELLER_CONFIRMED_PAYMENT_RECEIPT(Phase.PAYMENT_RECEIVED),
        SELLER_SENT_PAYMENT_RECEIVED_MSG(Phase.PAYMENT_RECEIVED),
        SELLER_SEND_FAILED_PAYMENT_RECEIVED_MSG(Phase.PAYMENT_RECEIVED),
        SELLER_STORED_IN_MAILBOX_PAYMENT_RECEIVED_MSG(Phase.PAYMENT_RECEIVED),
        SELLER_SAW_ARRIVED_PAYMENT_RECEIVED_MSG(Phase.PAYMENT_RECEIVED),
        BUYER_RECEIVED_PAYMENT_RECEIVED_MSG(Phase.PAYMENT_RECEIVED);

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
        DEPOSITS_FINALIZED,
        PAYMENT_SENT,
        PAYMENT_RECEIVED;

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
        PAYOUT_UNLOCKED,
        PAYOUT_FINALIZED;

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
        DISPUTE_PREPARING,
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

        public boolean isMediated() {
            return this == Trade.DisputeState.MEDIATION_REQUESTED ||
                    this == Trade.DisputeState.MEDIATION_STARTED_BY_PEER ||
                    this == Trade.DisputeState.MEDIATION_CLOSED;
        }

        public boolean isDisputed() {
            return this.ordinal() >= DisputeState.DISPUTE_PREPARING.ordinal();
        }

        public boolean isPreparing() {
            return this == DisputeState.DISPUTE_PREPARING;
        }

        public boolean isRequested() {
            return ordinal() >= DisputeState.DISPUTE_REQUESTED.ordinal();
        }

        public boolean isOpen() {
            return ordinal() >= DisputeState.DISPUTE_OPENED.ordinal() && !isClosed();
        }

        public boolean isCloseRequested() {
            return this.ordinal() >= DisputeState.ARBITRATOR_SENT_DISPUTE_CLOSED_MSG.ordinal();
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

    // Added in 1.5.1
    @Getter
    private final String uid;

    @Setter
    private long takeOfferDate;

    // Initialization
    private static final int TOTAL_INIT_STEPS = 24; // total estimated steps
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
    @Getter
    transient public final IntegerProperty depositTxsUpdateCounter = new SimpleIntegerProperty(0);
    transient final private StringProperty errorMessageProperty = new SimpleStringProperty();
    transient private Subscription tradeStateSubscription;
    transient private Subscription tradePhaseSubscription;
    transient private Subscription payoutStateSubscription;
    transient private Subscription disputeStateSubscription;
    transient private TaskLooper pollLooper;
    transient private Long pollPeriodMs;
    transient private Long pollNormalStartTimeMs;

    //  Mutable
    @Getter
    transient private boolean isInitialized;
    transient private boolean isFullyInitialized;

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
    private final Object startTimeLock = new Object();
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
    private String payoutTxHex; // signed payout tx hex
    @Getter
    @Setter
    private String payoutTxKey;
    private long payoutTxFee;
    private Long payoutHeight;
    private IdleSyncer idleSyncer;
    @Getter
    private boolean isCompleted;
    @Getter
    private final String challenge;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructors
    ///////////////////////////////////////////////////////////////////////////////////////////

    // maker
    protected Trade(Offer offer,
                    BigInteger tradeAmount,
                    long tradePrice,
                    XmrWalletService xmrWalletService,
                    ProcessModel processModel,
                    String uid,
                    @Nullable NodeAddress makerNodeAddress,
                    @Nullable NodeAddress takerNodeAddress,
                    @Nullable NodeAddress arbitratorNodeAddress,
                    @Nullable String challenge) {
        super();
        this.offer = offer;
        this.amount = tradeAmount.longValueExact();
        this.price = tradePrice;
        this.xmrWalletService = xmrWalletService;
        this.xmrConnectionService = xmrWalletService.getXmrConnectionService();
        this.processModel = processModel;
        this.uid = uid;
        this.takeOfferDate = new Date().getTime();
        this.tradeListeners = new ArrayList<TradeListener>();
        this.challenge = challenge;

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
                    long tradePrice,
                    @Nullable NodeAddress mediatorNodeAddress, // TODO (woodser): remove mediator, refund agent from trade
                    @Nullable NodeAddress refundAgentNodeAddress,
                    XmrWalletService xmrWalletService,
                    ProcessModel processModel,
                    String uid,
                    @Nullable NodeAddress makerNodeAddress,
                    @Nullable NodeAddress takerNodeAddress,
                    @Nullable NodeAddress arbitratorNodeAddress,
                    @Nullable String challenge) {

        this(offer,
                tradeAmount,
                tradePrice,
                xmrWalletService,
                processModel,
                uid,
                makerNodeAddress,
                takerNodeAddress,
                arbitratorNodeAddress,
                challenge);
    }

    // TODO: remove these constructors
    // arbitrator
    @SuppressWarnings("NullableProblems")
    protected Trade(Offer offer,
                    BigInteger tradeAmount,
                    Coin txFee,
                    long tradePrice,
                    NodeAddress makerNodeAddress,
                    NodeAddress takerNodeAddress,
                    NodeAddress arbitratorNodeAddress,
                    XmrWalletService xmrWalletService,
                    ProcessModel processModel,
                    String uid,
                    @Nullable String challenge) {

      this(offer,
              tradeAmount,
              tradePrice,
              xmrWalletService,
              processModel,
              uid,
              makerNodeAddress,
              takerNodeAddress,
              arbitratorNodeAddress,
              challenge);

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
        if (isInitialized) throw new IllegalStateException(getClass().getSimpleName() + " " + getId() + " is already initialized");

        // reset shut down state
        isShutDownStarted = false;
        isShutDown = false;

        // skip initialization if trade is complete
        // starting in v1.0.19, seller resends payment received message until acked or stored in mailbox
        if (isFinished()) {
            clearAndShutDown();
            return;
        }

        // set arbitrator pub key ring once known
        serviceProvider.getArbitratorManager().getDisputeAgentByNodeAddress(getArbitratorNodeAddress()).ifPresent(arbitrator -> {
            getArbitrator().setPubKeyRing(arbitrator.getPubKeyRing());
        });

        // handle connection change on dedicated thread
        xmrConnectionService.addConnectionListener(connection -> {
            ThreadUtils.execute(() -> onConnectionChanged(connection), getId());
        });

        // reset states if not awaiting processing
        if (!isPayoutPublished()) {

            // reset buyer's payment sent state
            if (this instanceof BuyerTrade && (getState().ordinal() == Trade.State.BUYER_CONFIRMED_PAYMENT_SENT.ordinal() || getState() == State.BUYER_SEND_FAILED_PAYMENT_SENT_MSG)) {
                log.warn("Resetting state of {} {} from {} to {} because sending PaymentSentMessage failed", getClass().getSimpleName(), getId(), getState(), Trade.State.DEPOSIT_TXS_UNLOCKED_IN_BLOCKCHAIN);
                setState(Trade.State.DEPOSIT_TXS_UNLOCKED_IN_BLOCKCHAIN);
            }

            // reset seller's payment received state
            if (this instanceof SellerTrade && (getState().ordinal() == Trade.State.SELLER_CONFIRMED_PAYMENT_RECEIPT.ordinal() || getState() == State.SELLER_SEND_FAILED_PAYMENT_RECEIVED_MSG)) {
                log.warn("Resetting state of {} {} from {} to {} because sending PaymentReceivedMessage failed", getClass().getSimpleName(), getId(), getState(), Trade.State.BUYER_SENT_PAYMENT_SENT_MSG);
                resetToPaymentSentState();
            }
        }

        // handle trade state events
        tradeStateSubscription = EasyBind.subscribe(stateProperty, newValue -> {
            if (!isInitialized || isShutDownStarted) return;
            // no processing
        });

        // handle trade phase events
        tradePhaseSubscription = EasyBind.subscribe(phaseProperty, newValue -> {
            if (!isInitialized || isShutDownStarted) return;
            ThreadUtils.submitToPool(() -> {
                awaitInitialized();
                if (newValue == Trade.Phase.DEPOSIT_REQUESTED) onDepositRequested();
                if (newValue == Trade.Phase.DEPOSITS_PUBLISHED) onDepositsPublished();
                if (newValue == Trade.Phase.DEPOSITS_CONFIRMED) onDepositsConfirmed();
                if (newValue == Trade.Phase.DEPOSITS_UNLOCKED) onDepositsUnlocked();
                if (newValue == Trade.Phase.DEPOSITS_FINALIZED) onDepositsFinalized();
                if (newValue == Trade.Phase.PAYMENT_SENT) onPaymentSent();
                if (isDepositsPublished() && !isPayoutFinalized()) updatePollPeriod();
                if (isPaymentReceived()) {
                    UserThread.execute(() -> {
                        if (tradePhaseSubscription != null) {
                            tradePhaseSubscription.unsubscribe();
                            tradePhaseSubscription = null;
                        }
                    });
                }
            });
        });

        // handle payout events
        payoutStateSubscription = EasyBind.subscribe(payoutStateProperty, newValue -> {
            if (!isInitialized || isShutDownStarted) return;
            ThreadUtils.submitToPool(() -> {
                awaitInitialized();
                updatePollPeriod();

                // handle when payout published
                if (newValue == Trade.PayoutState.PAYOUT_PUBLISHED) {
                    log.info("Payout published for {} {}", getClass().getSimpleName(), getId());

                    // sync main wallet to update pending balance
                    ThreadUtils.submitToPool(() -> {
                        HavenoUtils.waitFor(1000);
                        if (isPayoutConfirmed()) return;
                        if (isShutDownStarted) return;
                        if (xmrConnectionService.isConnected()) xmrWalletService.doPollWallet(true);
                    });

                    // complete disputed trade
                    if (getDisputeState().isDisputed() && !getDisputeState().isClosed()) {
                        processModel.getTradeManager().closeDisputedTrade(getId(), Trade.DisputeState.DISPUTE_CLOSED);
                        if (!isArbitrator()) for (Dispute dispute : getDisputes()) dispute.setIsClosed(); // auto close trader tickets
                    }

                    // auto complete arbitrator trade
                    if (isArbitrator() && !isCompleted()) processModel.getTradeManager().onTradeCompleted(this);

                    // maybe publish trade statistic
                    maybePublishTradeStatistics();

                    // reset address entries
                    processModel.getXmrWalletService().swapPayoutAddressEntryToAvailable(getId());
                }

                // handle when payout finalized
                if (newValue == Trade.PayoutState.PAYOUT_FINALIZED) {
                    if (!isInitialized) return;
                    log.info("Payout finalized for {} {}, deleting multisig wallet", getClass().getSimpleName(), getId());
                    if (isInitialized && isFinished()) clearAndShutDown();
                    else ThreadUtils.execute(() -> deleteWallet(), getId());
                }
            });
        });

        // handle dispute events
        disputeStateSubscription = EasyBind.subscribe(disputeStateProperty, newValue -> {
            if (!isInitialized || isShutDownStarted) return;
            ThreadUtils.submitToPool(() -> {
                awaitInitialized();
                if (isDisputeClosed()) maybePublishTradeStatistics();
            });
        });

        // listen to wallet events to sync while idling
        idleSyncer = new IdleSyncer();
        xmrWalletService.addWalletListener(idleSyncer);

        // TODO: buyer's payment sent message state property became unsynced if shut down while awaiting ack from seller. fixed mismatch in v1.0.19, but can this check be removed?
        if (isBuyer()) {
            MessageState expectedState = getPaymentSentMessageState();
            if (expectedState != null && expectedState != getSeller().getPaymentSentMessageStateProperty().get()) {
                log.warn("Updating unexpected payment sent message state for {} {}, expected={}, actual={}", getClass().getSimpleName(), getId(), expectedState, processModel.getPaymentSentMessageStatePropertySeller().get());
                getSeller().getPaymentSentMessageStateProperty().set(expectedState);
            }
        }

        // handle confirmations
        walletHeight.addListener((observable, oldValue, newValue) -> {
            importMultisigHexIfScheduled();
        });

        // done if deposit not requested or payout finalized
        if (!isDepositRequested() || isPayoutFinalized()) {
            isInitialized = true;
            isFullyInitialized = true;
            return;
        }

        // open wallet or done if wallet does not exist
        if (!walletExists()) {
            MoneroTx payoutTx = getPayoutTx();
            if (payoutTx != null) {

                // update payout state if necessary
                if (payoutTx.getNumConfirmations() != null) {
                    if (!isPayoutUnlocked() && payoutTx.getNumConfirmations() >= XmrWalletService.NUM_BLOCKS_UNLOCK) {
                        log.warn("Payout state for {} {} is {} but payout is unlocked, updating state", getClass().getSimpleName(), getId(), getPayoutState());
                        setPayoutStateUnlocked();
                    }
                    if (!isPayoutFinalized() && payoutTx.getNumConfirmations() >= NUM_BLOCKS_PAYOUT_FINALIZED) {
                        log.warn("Payout state for {} {} is {} but payout is finalized, updating state", getClass().getSimpleName(), getId(), getPayoutState());
                        setPayoutStateFinalized();
                    }
                }
                isInitialized = true;
                isFullyInitialized = true;
                return;
            } else {
                throw new RuntimeException("Missing trade wallet for " + getClass().getSimpleName() + " " + getShortId() + ", state=" + getState() + ", marked completed=" + isCompleted());
            }
        }

        // trade is initialized
        isInitialized = true;

        // init syncing if deposit requested
        maybeInitSyncing();
        isFullyInitialized = true;
    }

    // Note that this function is overriden by subclasses.
    public boolean isFinished() {
        if (!isCompleted()) return false;
        if (isPayoutUnlocked() && !walletExistsNoSync()) return true;
        return isPayoutFinalized();
    }

    public void resetToPaymentSentState() {
        setState(Trade.State.BUYER_SENT_PAYMENT_SENT_MSG);
        for (TradePeer peer : getAllPeers()) {
            peer.setPaymentReceivedMessage(null);
        }
        setPayoutTxHex(null);
    }

    public void initializeAfterMailboxMessages() {

        // handle when wallet first synced
        if (wasWalletSyncedAndPolledProperty.get()) onWalletFirstSynced();
        else {
            wasWalletSyncedAndPolledProperty.addListener((observable, oldValue, newValue) -> {
                if (newValue) onWalletFirstSynced();
            });
        }
    }

    private void onWalletFirstSynced() {
        requestSaveWallet();
        checkForUnconfirmedTimeout();
    }

    private void checkForUnconfirmedTimeout() {
        if (isDepositsConfirmed()) return;
        long unconfirmedHours = Duration.between(getDate().toInstant(), Instant.now()).toHours();
        if (unconfirmedHours >= 3 && !hasFailed()) {
            String errorMessage = Res.get("portfolio.pending.unconfirmedTooLong", getShortId(), unconfirmedHours);
            prependErrorMessage(errorMessage);
        }
    }

    public void awaitInitialized() {
        while (!isFullyInitialized) HavenoUtils.waitFor(100); // TODO: use proper notification and refactor isInitialized, fullyInitialized, and arbitrator idling
    }

    // TODO: throw if trade manager is null
    public void requestPersistence() {
        if (processModel.getTradeManager() != null) processModel.getTradeManager().requestPersistence();
    }

    // TODO: throw if trade manager is null
    public void persistNow(@Nullable Runnable completeHandler) {
        if (processModel.getTradeManager() != null) processModel.getTradeManager().persistNow(completeHandler);
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

    public void setCompleted(boolean completed) {
        this.isCompleted = completed;
        if (isInitialized && isFinished()) clearAndShutDown();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // WALLET MANAGEMENT
    ///////////////////////////////////////////////////////////////////////////////////////////

    public boolean walletExists() {
        synchronized (walletLock) {
            return xmrWalletService.walletExists(getWalletName());
        }
    }

    protected boolean walletExistsNoSync() {
        return xmrWalletService.walletExists(getWalletName());
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

    /**
     * Get the trade wallet, opening the wallet if necessary.
     * 
     * @return the trade wallet, or null if the wallet does not exist
     */
    public MoneroWallet getWallet() {
        synchronized (walletLock) {
            if (wallet != null) return wallet;
            if (!walletExists()) return null;
            if (isShutDownStarted) throw new RuntimeException("Cannot open wallet for " + getClass().getSimpleName() + " " + getId() + " because shut down is started");
            log.debug("Opening wallet for {} {}", getClass().getSimpleName(), getId());
            wallet = xmrWalletService.openWallet(getWalletName(), xmrWalletService.isProxyApplied(wasWalletSynced));
            walletHeight.set(wallet.getHeight());
            doPollWallet(true); // poll wallet without network calls
            return wallet;
        }
    }

    public long getHeight() {
        return walletHeight.get();
    }

    private String getWalletName() {
        return MONERO_TRADE_WALLET_PREFIX + getShortId() + "_" + getShortUid();
    }

    public void verifyDaemonConnection() {
        if (!Boolean.TRUE.equals(xmrConnectionService.isConnected())) throw new RuntimeException("Connection service is not connected to a Monero node");
    }

    public boolean isWalletConnectedToDaemon() {
        synchronized (walletLock) {
            try {
                if (wallet == null) return false;
                return wallet.isConnectedToDaemon();
            } catch (Exception e) {
                return false;
            }
        }
    }

    public boolean isIdling() {
        if (pollNormalStartTimeMs != null) return false;
        if (!walletExistsNoSync()) return false;
        if (isPayoutUnlocked()) return true;
        if (isPayoutPublished()) return false;
        if (isArbitrator() && isDepositsConfirmed()) return true;
        if (!isDepositsUnlocked()) return false;
        if (isPaymentReceived() || isDisputeClosed()) return false;
        if (isBuyer()) return isPaymentSent();
        if (isSeller()) {
            if (!isPaymentSent()) return true;
            if (isPaymentSent() && !isPaymentReceived()) return false;
        }
        if (!isArbitrator() && (isPaymentReceived() || isDisputeClosed())) return false;
        return true;
    }

    public boolean isSyncedWithinTolerance() {
        synchronized (walletLock) {
            if (!xmrConnectionService.isSyncedWithinTolerance()) return false;
            Long targetHeight = xmrConnectionService.getTargetHeight();
            if (targetHeight == null) return false;
            if (targetHeight - walletHeight.get() <= 3) return true; // synced if within 3 blocks of target height
            return false;
        }
    }

    public void syncAndPollWallet() {
        syncWallet(true);
    }

    public void pollWalletNormallyForMs(long pollNormalDuration) {
        pollNormalStartTimeMs = System.currentTimeMillis();

        // override wallet poll period
        setPollPeriod(xmrConnectionService.getRefreshPeriodMs());

        // reset wallet poll period after duration 
        new Thread(() -> {
            HavenoUtils.waitFor(pollNormalDuration);
            Long pollNormalStartTimeMsCopy = pollNormalStartTimeMs; // copy to avoid race condition
            if (pollNormalStartTimeMsCopy == null) return;
            if (!isShutDown && System.currentTimeMillis() >= pollNormalStartTimeMsCopy + pollNormalDuration) {
                pollNormalStartTimeMs = null;
                updatePollPeriod();
            }
        }).start();
    }

    // TODO: checking error strings isn't robust, but the library doesn't provide a way to check if multisig hex is invalid. throw IllegalArgumentException from library on invalid multisig hex?
    private boolean isInvalidImportError(String errMsg) {
        return errMsg.contains("Failed to parse hex") || errMsg.contains("Multisig info is for a different account");
    }

    public void changeWalletPassword(String oldPassword, String newPassword) {
        synchronized (walletLock) {
            getWallet().changePassword(oldPassword, newPassword);
            saveWallet();
        }
    }

    @Override
    public void requestSaveWalletIfElapsedTime() {
        ThreadUtils.submitToPool(() -> {
            synchronized (walletLock) {
                if (walletExists()) saveWalletIfElapsedTime();
            }
        });
    }

    private void requestSaveWallet() {
        ThreadUtils.submitToPool(() -> {
            synchronized (walletLock) {
                if (walletExists()) saveWallet();
            }
        });
    }

    @Override
    public void saveWallet() {
        synchronized (walletLock) {
            if (!walletExists()) {
                log.warn("Cannot save wallet for {} {} because it does not exist", getClass().getSimpleName(), getShortId());
                return;
            }
            if (wallet == null) throw new RuntimeException("Trade wallet is not open for trade " + getShortId());
            xmrWalletService.saveWallet(wallet);
            lastSaveTimeMs = System.currentTimeMillis();
            maybeBackupWallet();
        }
    }

    private void maybeBackupWallet() {
        boolean createBackup = !isArbitrator() && !(Utilities.isWindows() && isWalletOpen()); // create backup unless arbitrator or windows and wallet is open (cannot copy file while open on windows)
        if (createBackup) xmrWalletService.backupWallet(getWalletName());
    }

    private boolean isWalletOpen() {
        synchronized (walletLock) {
            return wallet != null;
        }
    }

    private void closeWallet() {
        closeWallet(true);
    }

    private void closeWallet(boolean stopPolling) {
        synchronized (walletLock) {
            if (stopPolling) {
                stopPolling();
                pollPeriodMs = null;
            }
            if (wallet == null) return; // already closed
            log.debug("Closing wallet for {} {}", getClass().getSimpleName(), getId());
            maybeBackupWallet();
            xmrWalletService.closeWallet(wallet, true);
            wallet = null;
        }
    }

    private void forceCloseWallet() {
        if (wallet != null) {
            MoneroWallet walletRef = wallet;
            wallet = null; // nullify wallet before force closing so state is updated for error handling
            try {
                xmrWalletService.forceCloseWallet(walletRef, walletRef.getPath());
            } catch (Exception e) {
                log.warn("Error force closing wallet for {} {}: {}", getClass().getSimpleName(), getId(), e.getMessage());
            }
            stopPolling();
        }
    }

    public void deleteWallet() {
        synchronized (walletLock) {
            if (walletExists()) {
                try {

                    // check wallet state if deposit requested and payout not finalized
                    if (isDepositRequested() && !isPayoutFinalized()) {

                        // ensure wallet is initialized
                        boolean syncedWallet = false;
                        if (wallet == null) {
                            log.warn("Wallet is not initialized for {} {}, opening", getClass().getSimpleName(), getId());
                            getWallet();
                            syncWallet(true);
                            syncedWallet = true;
                        }
    
                        // sync wallet if deposit requested
                        if (!syncedWallet) {
                            log.warn("Syncing wallet on deletion for trade {} {}, syncing", getClass().getSimpleName(), getId());
                            syncWallet(true);
                        }
    
                        // check if deposits published
                        if (isDepositsPublished() && !isPayoutFinalized()) {
                            throw new IllegalStateException("Refusing to delete wallet for " + getClass().getSimpleName() + " " + getId() + " because the deposit txs have been published but payout tx has not finalized");
                        }
    
                        // check for balance
                        if (wallet.getBalance().compareTo(BigInteger.ZERO) > 0) {
                            log.warn("Rescanning spent outputs for {} {}", getClass().getSimpleName(), getId());
                            rescanSpent(false);
                            if (wallet.getBalance().compareTo(BigInteger.ZERO) > 0) {
                                if (isBuyer()) {
                                    processBuyerPayout(payoutTxId); // process payout to main wallet
                                    log.warn("Trade wallet for " + getClass().getSimpleName() + " " + getId() + " has a balance of " + wallet.getBalance() + ", but payout tx " + payoutTxId + " is verified, so proceeding to delete wallet");
                                } else {
                                    throw new IllegalStateException("Refusing to delete wallet for " + getClass().getSimpleName() + " " + getId() + " because it has a balance of " + wallet.getBalance());
                                }
                            }
                        }
                    }

                    // force close wallet without warning
                    forceCloseWallet();

                    // delete wallet
                    log.info("Deleting wallet and backups for {} {}", getClass().getSimpleName(), getId());
                    xmrWalletService.deleteWallet(getWalletName());
                    xmrWalletService.deleteWalletBackups(getWalletName());
                } catch (Exception e) {
                    log.warn("Error deleting wallet for {} {}: {}\n", getClass().getSimpleName(), getId(), e.getMessage(), e);
                    prependErrorMessage(e.getMessage());
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

    public MoneroTxWallet createTx(MoneroTxConfig txConfig) {
        synchronized (walletLock) {
            if (getWallet() == null) throw new IllegalStateException("Cannot create transaction for trade wallet because it doesn't exist for " + getClass().getSimpleName() + " " + getId());
            synchronized (HavenoUtils.getWalletFunctionLock()) {
                MoneroTxWallet tx = wallet.createTx(txConfig);
                exportMultisigHex();
                return tx;
            }
        }
    }

    public void exportMultisigHex() {
        synchronized (walletLock) {
            log.info("Exporting multisig info for {} {}", getClass().getSimpleName(), getShortId());
            if (getWallet() == null) throw new IllegalStateException("Cannot export multisig hex for trade wallet because it doesn't exist for " + getClass().getSimpleName() + " " + getId());
            getSelf().setUpdatedMultisigHex(wallet.exportMultisigHex());
            saveWallet();
        }
    }

    public void importMultisigHexIfNeeded() {
        synchronized (walletLock) {
            if (getWallet() == null) throw new IllegalStateException("Cannot import multisig hex if needed for trade wallet because it doesn't exist for " + getClass().getSimpleName() + " " + getId());
            if (wallet.isMultisigImportNeeded()) {
                importMultisigHex();
            }
        }
    }

    public void scheduleImportMultisigHex() {
        processModel.setImportMultisigHexScheduled(true);
        requestPersistence();
    }

    private void importMultisigHexIfScheduled() {
        if (!isInitialized || isShutDownStarted) return;
        MoneroTxWallet makerDepositTx = getMaker().getDepositTx();
        if (!isDepositsConfirmed() || makerDepositTx == null) return;
        if (walletHeight.get() - makerDepositTx.getHeight() < NUM_CONFIRMATIONS_FOR_SCHEDULED_IMPORT) return;
        ThreadUtils.execute(() -> {
            if (!isInitialized || isShutDownStarted) return;
            synchronized (getLock()) {
                if (processModel.isImportMultisigHexScheduled()) {
                    importMultisigHex();
                    processModel.setImportMultisigHexScheduled(false);
                }
            }
        }, getId());
    }

    public void importMultisigHex() {
        synchronized (walletLock) {
            if (getWallet() == null) throw new IllegalStateException("Cannot import multisig hex for trade wallet because it doesn't exist for " + getClass().getSimpleName() + " " + getId());
            synchronized (HavenoUtils.getDaemonLock()) { // lock on daemon because import calls full refresh
                synchronized (HavenoUtils.getImportMultisigLock()) {
                    for (int i = 0; i < TradeProtocol.MAX_ATTEMPTS; i++) {
                        MoneroRpcConnection sourceConnection = xmrConnectionService.getConnection();
                        try {
                            doImportMultisigHex();
                            break;
                        } catch (IllegalArgumentException | IllegalStateException e) {
                            log.warn("Illegal error importing multisig hex for {} {} on attempt {}/{}: {}", getClass().getSimpleName(), getShortId(), i + 1, TradeProtocol.MAX_ATTEMPTS, e.getMessage());
                            throw e;
                        } catch (Exception e) {
                            log.warn("Error importing multisig hex for {} {} on attempt {}/{}: {}", getClass().getSimpleName(), getShortId(), i + 1, TradeProtocol.MAX_ATTEMPTS, e.getMessage());
                            if (isShutDownStarted && wallet == null) {
                                log.warn("Aborting import of multisig hex for {} {} because shut down is started and wallet is closed", getClass().getSimpleName(), getShortId());
                                break;
                            }
                            handleWalletError(e, sourceConnection, i + 1);
                            doPollWallet();
                            if (isPayoutPublished()) break;
                            if (i == TradeProtocol.MAX_ATTEMPTS - 1) throw e;
                            HavenoUtils.waitFor(TradeProtocol.REPROCESS_DELAY_MS); // wait before retrying
                        }
                    }
                }
            }
        }
    }

    private void doImportMultisigHex() {

        // sync and poll wallet if deposits not confirmed (unless only one deposit unlocked)
        if (!isDepositsConfirmed() && !hasUnlockedTx()) syncAndPollWallet();

        // collect multisig hex from peers
        List<String> multisigHexes = new ArrayList<String>();
        for (TradePeer peer : getOtherPeers()) if (peer.getUpdatedMultisigHex() != null) multisigHexes.add(peer.getUpdatedMultisigHex());

        // import multisig hex
        log.info("Importing multisig hexes for {} {}, count={}", getClass().getSimpleName(), getShortId(), multisigHexes.size());
        long startTime = System.currentTimeMillis();
        if (!multisigHexes.isEmpty()) {
            try {
                wallet.importMultisigHex(multisigHexes.toArray(new String[0]));

                // check if import is still needed // TODO: we once received a multisig hex which was too short, causing import to still be needed
                if (wallet.isMultisigImportNeeded()) {
                    String errorMessage = "Multisig import still needed for " + getClass().getSimpleName() + " " + getShortId() + " after already importing, multisigHexes=" + multisigHexes;
                    log.warn(errorMessage);

                    // remove shortest multisig hex if applicable
                    int maxLength = 0;
                    String shortestMultisigHex = null;
                    for (String hex : multisigHexes) {
                        if (shortestMultisigHex == null || hex.length() < shortestMultisigHex.length()) shortestMultisigHex = hex;
                        if (hex.length() > maxLength) maxLength = hex.length();
                    }
                    if (shortestMultisigHex.length() < maxLength) {
                        log.warn("Removing multisig hex from " + getMultisigHexRole(shortestMultisigHex) + " for " + getClass().getSimpleName() + " " + getShortId() + " because it's the shortest, multisigHex=" + shortestMultisigHex);
                        multisigHexes.remove(shortestMultisigHex);
                        wallet.importMultisigHex(multisigHexes.toArray(new String[0]));
                    }

                    // throw if multisig import still needed
                    if (wallet.isMultisigImportNeeded()) throw new IllegalStateException(errorMessage);
                }

                // remove scheduled import
                processModel.setImportMultisigHexScheduled(false);
            } catch (MoneroError e) {

                // import multisig hex individually if one is invalid
                if (isInvalidImportError(e.getMessage())) {
                    log.warn("Peer has invalid multisig hex for {} {}, importing individually", getClass().getSimpleName(), getShortId());
                    boolean imported = false;
                    Exception lastError = null;
                    for (TradePeer peer : getOtherPeers()) {
                        if (peer.getUpdatedMultisigHex() == null) continue;
                        try {
                            wallet.importMultisigHex(peer.getUpdatedMultisigHex());
                            imported = true;
                        } catch (MoneroError e2) {
                            lastError = e2;
                            if (isInvalidImportError(e2.getMessage())) {
                                log.warn("{} has invalid multisig hex for {} {}, error={}, multisigHex={}", getPeerRole(peer), getClass().getSimpleName(), getShortId(), e2.getMessage(), peer.getUpdatedMultisigHex());
                            } else {
                                throw e2;
                            }
                        }
                    }
                    if (!imported) throw new IllegalArgumentException("Could not import any multisig hexes for " + getClass().getSimpleName() + " " + getShortId(), lastError);
                } else {
                    throw e;
                }
            }
            saveWallet();
        }
        log.info("Done importing multisig hexes for {} {} in {} ms, count={}", getClass().getSimpleName(), getShortId(), System.currentTimeMillis() - startTime, multisigHexes.size());
    }

    private void handleWalletError(Exception e, MoneroRpcConnection sourceConnection, int numAttempts) {
        if (HavenoUtils.isUnresponsive(e)) forceCloseWallet(); // wallet can be stuck a while
        if (numAttempts % TradeProtocol.REQUEST_CONNECTION_SWITCH_EVERY_NUM_ATTEMPTS == 0 && !HavenoUtils.isIllegal(e) && xmrConnectionService.isConnected()) requestSwitchToNextBestConnection(sourceConnection); // request connection switch every n attempts
        if (!isShutDownStarted) getWallet(); // re-open wallet
    }

    private String getMultisigHexRole(String multisigHex) {
        if (multisigHex.equals(getArbitrator().getUpdatedMultisigHex())) return "arbitrator";
        if (multisigHex.equals(getBuyer().getUpdatedMultisigHex())) return "buyer";
        if (multisigHex.equals(getSeller().getUpdatedMultisigHex())) return "seller";
        throw new IllegalArgumentException("Multisig hex does not belong to any peer");
    }

    /**
     * Create the payout tx.
     *
     * @return the payout tx when the trade is successfully completed
     */
    public MoneroTxWallet createPayoutTx() {

        // check connection to monero daemon
        verifyDaemonConnection();

        // create payout tx
        synchronized (walletLock) {
            synchronized (HavenoUtils.getWalletFunctionLock()) {

                // import multisig hex if needed
                importMultisigHexIfNeeded();

                // create payout tx
                for (int i = 0; i < TradeProtocol.MAX_ATTEMPTS; i++) {
                    MoneroRpcConnection sourceConnection = xmrConnectionService.getConnection();
                    try {
                        MoneroTxWallet unsignedPayoutTx = doCreatePayoutTx();
                        log.info("Done creating unsigned payout tx for {} {}", getClass().getSimpleName(), getShortId());
                        return unsignedPayoutTx;
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        throw e;
                    } catch (Exception e) {
                        handleWalletError(e, sourceConnection, i + 1);
                        doPollWallet();
                        if (isPayoutPublished()) break;
                        log.warn("Failed to create payout tx, tradeId={}, attempt={}/{}, error={}", getShortId(), i + 1, TradeProtocol.MAX_ATTEMPTS, e.getMessage());
                        if (i == TradeProtocol.MAX_ATTEMPTS - 1) throw e;
                        HavenoUtils.waitFor(TradeProtocol.REPROCESS_DELAY_MS); // wait before retrying
                    }
                }
                throw new RuntimeException("Failed to create payout tx for " + getClass().getSimpleName() + " " + getId());
            }
        }
    }

    private MoneroTxWallet doCreatePayoutTx() {

        // check if multisig import needed
        if (wallet.isMultisigImportNeeded()) throw new IllegalStateException("Cannot create payout tx because multisig import is needed for " + getClass().getSimpleName() + " " + getShortId());

        // recover if missing wallet data
        recoverIfMissingWalletData();

        // gather info
        String sellerPayoutAddress = getSeller().getPayoutAddressString();
        String buyerPayoutAddress = getBuyer().getPayoutAddressString();
        Preconditions.checkNotNull(sellerPayoutAddress, "Seller payout address must not be null");
        Preconditions.checkNotNull(buyerPayoutAddress, "Buyer payout address must not be null");
        BigInteger sellerDepositAmount = getSeller().getDepositTx().getIncomingAmount();
        BigInteger buyerDepositAmount = hasBuyerAsTakerWithoutDeposit() ? BigInteger.ZERO : getBuyer().getDepositTx().getIncomingAmount();
        BigInteger tradeAmount = getAmount();
        BigInteger buyerPayoutAmount = buyerDepositAmount.add(tradeAmount);
        BigInteger sellerPayoutAmount = sellerDepositAmount.subtract(tradeAmount);

        // create payout tx
        MoneroTxWallet payoutTx;
        try {
            payoutTx = createTx(new MoneroTxConfig()
                .setAccountIndex(0)
                .addDestination(buyerPayoutAddress, buyerPayoutAmount)
                .addDestination(sellerPayoutAddress, sellerPayoutAmount)
                .setSubtractFeeFrom(0, 1) // split tx fee
                .setRelay(false)
                .setPriority(XmrWalletService.PROTOCOL_FEE_PRIORITY));
        } catch (Exception e) {
            if (HavenoUtils.isMultisigError(e)) throw new IllegalStateException(e);
            else throw e;
        }

        // update state
        BigInteger payoutTxFeeSplit = payoutTx.getFee().divide(BigInteger.valueOf(2));
        getBuyer().setPayoutTxFee(payoutTxFeeSplit);
        getBuyer().setPayoutAmount(HavenoUtils.getDestination(buyerPayoutAddress, payoutTx).getAmount());
        getSeller().setPayoutTxFee(payoutTxFeeSplit);
        getSeller().setPayoutAmount(HavenoUtils.getDestination(sellerPayoutAddress, payoutTx).getAmount());
        return payoutTx;
    }

    public MoneroTxWallet createDisputePayoutTx(Contract contract, DisputeResult disputeResult, boolean updateState) {
        synchronized (walletLock) {

            // import multisig hex
            importMultisigHex();

            // sync and poll
            syncAndPollWallet();

            // recover if missing wallet data
            recoverIfMissingWalletData();

            // check if payout tx already published
            String alreadyPublishedMsg = "Cannot create dispute payout tx because payout tx is already published for trade " + getId();
            if (isPayoutPublished()) throw new RuntimeException(alreadyPublishedMsg);

            // create unsigned dispute payout tx
            if (updateState) log.info("Creating unsigned dispute payout tx for trade {}", getId());
            try {

                // trade wallet must be synced
                if (getWallet().isMultisigImportNeeded()) throw new RuntimeException("Arbitrator's wallet needs updated multisig hex to create payout tx which means a trader must have already broadcast the payout tx for trade " + getId());

                // check amounts
                if (disputeResult.getBuyerPayoutAmountBeforeCost().compareTo(BigInteger.ZERO) < 0) throw new RuntimeException("Buyer payout cannot be negative");
                if (disputeResult.getSellerPayoutAmountBeforeCost().compareTo(BigInteger.ZERO) < 0) throw new RuntimeException("Seller payout cannot be negative");
                if (disputeResult.getBuyerPayoutAmountBeforeCost().add(disputeResult.getSellerPayoutAmountBeforeCost()).compareTo(getWallet().getUnlockedBalance()) > 0) {
                    throw new RuntimeException("The payout amounts are more than the wallet's unlocked balance, unlocked balance=" + getWallet().getUnlockedBalance() + " vs " + disputeResult.getBuyerPayoutAmountBeforeCost() + " + " + disputeResult.getSellerPayoutAmountBeforeCost() + " = " + (disputeResult.getBuyerPayoutAmountBeforeCost().add(disputeResult.getSellerPayoutAmountBeforeCost())));
                }

                // create dispute payout tx config
                MoneroTxConfig txConfig = new MoneroTxConfig().setAccountIndex(0);
                String buyerPayoutAddress = contract.isBuyerMakerAndSellerTaker() ? contract.getMakerPayoutAddressString() : contract.getTakerPayoutAddressString();
                String sellerPayoutAddress = contract.isBuyerMakerAndSellerTaker() ? contract.getTakerPayoutAddressString() : contract.getMakerPayoutAddressString();
                txConfig.setPriority(XmrWalletService.PROTOCOL_FEE_PRIORITY);
                if (disputeResult.getBuyerPayoutAmountBeforeCost().compareTo(BigInteger.ZERO) > 0) txConfig.addDestination(buyerPayoutAddress, disputeResult.getBuyerPayoutAmountBeforeCost());
                if (disputeResult.getSellerPayoutAmountBeforeCost().compareTo(BigInteger.ZERO) > 0) txConfig.addDestination(sellerPayoutAddress, disputeResult.getSellerPayoutAmountBeforeCost());

                // configure who pays mining fee
                BigInteger loserPayoutAmount = disputeResult.getWinner() == Winner.BUYER ? disputeResult.getSellerPayoutAmountBeforeCost() : disputeResult.getBuyerPayoutAmountBeforeCost();
                if (loserPayoutAmount.equals(BigInteger.ZERO)) txConfig.setSubtractFeeFrom(0); // winner pays fee if loser gets 0
                else {
                    switch (disputeResult.getSubtractFeeFrom()) {
                        case BUYER_AND_SELLER:
                            txConfig.setSubtractFeeFrom(0, 1);
                            break;
                        case BUYER_ONLY:
                            txConfig.setSubtractFeeFrom(0);
                            break;
                        case SELLER_ONLY:
                            txConfig.setSubtractFeeFrom(1);
                            break;
                    }
                }

                // create dispute payout tx
                MoneroTxWallet payoutTx = createDisputePayoutTx(txConfig);

                // update trade state
                if (updateState) {
                    getProcessModel().setUnsignedPayoutTx(payoutTx);
                    setPayoutTx(payoutTx);
                    if (getBuyer().getUpdatedMultisigHex() != null) getBuyer().setUnsignedPayoutTxHex(payoutTx.getTxSet().getMultisigTxHex());
                    if (getSeller().getUpdatedMultisigHex() != null) getSeller().setUnsignedPayoutTxHex(payoutTx.getTxSet().getMultisigTxHex());
                }
                requestPersistence();
                return payoutTx;
            } catch (Exception e) {
                syncAndPollWallet();
                if (isPayoutPublished()) throw new IllegalStateException(alreadyPublishedMsg);
                throw e;
            } catch (AssertionError e) { // tx creation throws assertion error with invalid config
                syncAndPollWallet();
                if (isPayoutPublished()) throw new IllegalStateException(alreadyPublishedMsg);
                throw new RuntimeException(e);
            }
        }
    }

    private MoneroTxWallet createDisputePayoutTx(MoneroTxConfig txConfig) {
        synchronized (walletLock) {
            if (getWallet() == null) throw new IllegalStateException("Cannot create dispute payout tx for trade wallet because it doesn't exist for " + getClass().getSimpleName() + " " + getId());
            synchronized (HavenoUtils.getWalletFunctionLock()) {
                for (int i = 0; i < TradeProtocol.MAX_ATTEMPTS; i++) {
                    MoneroRpcConnection sourceConnection = xmrConnectionService.getConnection();
                    try {
                        if (wallet.isMultisigImportNeeded()) throw new IllegalStateException("Cannot create dispute payout tx because multisig import is needed for " + getClass().getSimpleName() + " " + getShortId());
                        return createTx(txConfig);
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        throw e;
                    } catch (Exception e) {
                        if (HavenoUtils.isMultisigError(e)) throw new IllegalStateException(e);
                        if (e.getMessage().contains("not possible")) throw new IllegalArgumentException("Loser payout is too small to cover the mining fee");
                        handleWalletError(e, sourceConnection, i + 1);
                        doPollWallet();
                        if (isPayoutPublished()) break;
                        log.warn("Failed to create dispute payout tx, tradeId={}, attempt={}/{}, error={}", getShortId(), i + 1, TradeProtocol.MAX_ATTEMPTS, e.getMessage());
                        if (i == TradeProtocol.MAX_ATTEMPTS - 1) throw e;
                        HavenoUtils.waitFor(TradeProtocol.REPROCESS_DELAY_MS); // wait before retrying
                    }
                }
                throw new RuntimeException("Failed to create payout tx for " + getClass().getSimpleName() + " " + getId());
            }
        }
    }

    /**
     * Process a payout tx.
     *
     * @param payoutTxHex is the payout tx hex to verify
     * @param sign signs the payout tx if true
     * @param publish publishes the signed payout tx if true
     */
    public void processPayoutTx(String payoutTxHex, boolean sign, boolean publish) {
        synchronized (walletLock) {
            synchronized (HavenoUtils.getWalletFunctionLock()) {
                for (int i = 0; i < TradeProtocol.MAX_ATTEMPTS; i++) {
                    MoneroRpcConnection sourceConnection = xmrConnectionService.getConnection();
                    try {
                        doProcessPayoutTx(payoutTxHex, sign, publish);
                        break;
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        throw e;
                    } catch (Exception e) {
                        handleWalletError(e, sourceConnection, i + 1);
                        doPollWallet();
                        if (isPayoutPublished()) break;
                        log.warn("Failed to process payout tx, tradeId={}, attempt={}/{}, error={}", getShortId(), i + 1, TradeProtocol.MAX_ATTEMPTS, e.getMessage(), e);
                        if (i == TradeProtocol.MAX_ATTEMPTS - 1) throw e;
                        HavenoUtils.waitFor(TradeProtocol.REPROCESS_DELAY_MS); // wait before retrying
                    } finally {
                        saveWallet();
                        persistNow(null);
                    }
                }
            }
        }
    }

    private void doProcessPayoutTx(String payoutTxHex, boolean sign, boolean publish) {
        log.info("Processing payout tx for {} {}", getClass().getSimpleName(), getId());

        // recover if missing wallet data
        recoverIfMissingWalletData();

        // gather relevant info
        getWallet();
        Contract contract = getContract();
        BigInteger sellerDepositAmount = getSeller().getDepositTx().getIncomingAmount();
        BigInteger buyerDepositAmount = hasBuyerAsTakerWithoutDeposit() ? BigInteger.ZERO : getBuyer().getDepositTx().getIncomingAmount();
        BigInteger tradeAmount = getAmount();

        // describe payout tx
        MoneroTxSet describedTxSet = wallet.describeTxSet(new MoneroTxSet().setMultisigTxHex(payoutTxHex));
        if (describedTxSet.getTxs() == null || describedTxSet.getTxs().size() != 1) throw new IllegalArgumentException("Bad payout tx"); // TODO (woodser): test nack
        MoneroTxWallet payoutTx = describedTxSet.getTxs().get(0);
        if (payoutTxId == null) setPayoutTx(payoutTx); // update payout tx if id currently unknown

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

        // update payout tx
        setPayoutTx(payoutTx);

        // check connection
        boolean doSign = sign && getPayoutTxHex() == null;
        if (doSign || publish) verifyDaemonConnection();

        // handle tx signing
        if (doSign) {

            // sign tx
            String signedPayoutTxHex;
            try {
                MoneroMultisigSignResult result = wallet.signMultisigTxHex(payoutTxHex);
                if (result.getSignedMultisigTxHex() == null) throw new IllegalArgumentException("Error signing payout tx, signed multisig hex is null");
                signedPayoutTxHex = result.getSignedMultisigTxHex();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }

            // verify miner fee is within tolerance unless outdated offer version
            if (getOffer().getOfferPayload().getProtocolVersion() >= 2) {

                // verify fee is within tolerance by recreating payout tx
                // TODO (monero-project): creating tx will require exchanging updated multisig hex if message needs reprocessed. provide weight with describe_transfer so fee can be estimated?
                log.info("Creating fee estimate tx for {} {}", getClass().getSimpleName(), getShortId());
                saveWallet(); // save wallet before creating fee estimate tx
                MoneroTxWallet feeEstimateTx = createPayoutTx();
                HavenoUtils.verifyMinerFee(feeEstimateTx.getFee(), payoutTx.getFee());
                log.info("Payout tx fee is within tolerance for {} {}", getClass().getSimpleName(), getShortId());
            }

            // set signed payout tx hex
            setPayoutTxHex(signedPayoutTxHex);

            // describe result
            describedTxSet = wallet.describeMultisigTxSet(getPayoutTxHex());
            payoutTx = describedTxSet.getTxs().get(0);
            setPayoutTx(payoutTx);
        }

        // save trade state
        saveWallet();
        requestPersistence();

        // submit payout tx
        boolean doPublish = publish && !isPayoutPublished();
        if (doPublish) {
            try {
                List<String> payoutTxIds = wallet.submitMultisigTxHex(getPayoutTxHex());
                payoutTxId = payoutTxIds.get(0);
                setPayoutStatePublished();
            } catch (Exception e) {
                if (!isPayoutPublished()) {
                    if (HavenoUtils.isTransactionRejected(e) || HavenoUtils.isMultisigError(e)) throw new IllegalArgumentException(e);
                    throw new RuntimeException("Failed to submit payout tx for " + getClass().getSimpleName() + " " + getId() + ", error=" + e.getMessage(), e);
                }
            }
        }
    }

    public void processDisputePayoutTx() {
        synchronized (walletLock) {
            synchronized (HavenoUtils.getWalletFunctionLock()) {
                for (int i = 0; i < TradeProtocol.MAX_ATTEMPTS; i++) {
                    MoneroRpcConnection sourceConnection = xmrConnectionService.getConnection();
                    try {
                        doProcessDisputePayoutTx();
                        break;
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        throw e;
                    } catch (Exception e) {
                        handleWalletError(e, sourceConnection, i + 1);
                        doPollWallet();
                        if (isPayoutPublished()) break;
                        log.warn("Failed to process dispute payout tx, tradeId={}, attempt={}/{}, error={}", getShortId(), i + 1, TradeProtocol.MAX_ATTEMPTS, e.getMessage(), e);
                        if (i == TradeProtocol.MAX_ATTEMPTS - 1) throw e;
                        HavenoUtils.waitFor(TradeProtocol.REPROCESS_DELAY_MS); // wait before retrying
                    } finally {
                        saveWallet();
                        persistNow(null);
                    }
                }
            }
        }
    }

    private MoneroTxSet doProcessDisputePayoutTx() {
        log.info("Processing dispute payout tx for {} {}", getClass().getSimpleName(), getId());

        // recover if missing wallet data
        recoverIfMissingWalletData();

        // gather trade info
        MoneroWallet multisigWallet = getWallet();
        Optional<Dispute> disputeOptional = HavenoUtils.arbitrationManager.findDispute(getId());
        if (!disputeOptional.isPresent()) throw new IllegalArgumentException("Trader has no dispute when signing dispute payout tx. This should never happen. TradeId = " + getId());
        Dispute dispute = disputeOptional.get();
        Contract contract = dispute.getContract();
        DisputeResult disputeResult = dispute.getDisputeResultProperty().get();
        String unsignedPayoutTxHex = getArbitrator().getDisputeClosedMessage().getUnsignedPayoutTxHex();

        // Offer offer = checkNotNull(trade.getOffer(), "offer must not be null");
        // BigInteger sellerDepositAmount = multisigWallet.getTx(trade instanceof MakerTrade ? trade.getMaker().getDepositTxHash() : trade.getTaker().getDepositTxHash()).getIncomingAmount();   // TODO (woodser): use contract instead of trade to get deposit tx ids when contract has deposit tx ids
        // BigInteger buyerDepositAmount = multisigWallet.getTx(trade instanceof MakerTrade ? trade.getTaker().getDepositTxHash() : trade.getMaker().getDepositTxHash()).getIncomingAmount();
        // BigInteger tradeAmount = BigInteger.valueOf(contract.getTradeAmount().value).multiply(ParsingUtils.XMR_SATOSHI_MULTIPLIER);

        // parse arbitrator-signed payout tx
        MoneroTxSet disputeTxSet = multisigWallet.describeTxSet(new MoneroTxSet().setMultisigTxHex(unsignedPayoutTxHex));
        if (disputeTxSet.getTxs() == null || disputeTxSet.getTxs().size() != 1) throw new IllegalArgumentException("Bad arbitrator-signed payout tx");  // TODO (woodser): nack
        MoneroTxWallet arbitratorSignedPayoutTx = disputeTxSet.getTxs().get(0);

        // verify payout tx has 1 or 2 destinations
        int numDestinations = arbitratorSignedPayoutTx.getOutgoingTransfer() == null || arbitratorSignedPayoutTx.getOutgoingTransfer().getDestinations() == null ? 0 : arbitratorSignedPayoutTx.getOutgoingTransfer().getDestinations().size();
        if (numDestinations != 1 && numDestinations != 2) throw new IllegalArgumentException("Buyer-signed payout tx does not have 1 or 2 destinations");

        // get buyer and seller destinations (order not preserved)
        List<MoneroDestination> destinations = arbitratorSignedPayoutTx.getOutgoingTransfer().getDestinations();
        boolean buyerFirst = destinations.get(0).getAddress().equals(contract.getBuyerPayoutAddressString());
        MoneroDestination buyerPayoutDestination = buyerFirst ? destinations.get(0) : numDestinations == 2 ? destinations.get(1) : null;
        MoneroDestination sellerPayoutDestination = buyerFirst ? (numDestinations == 2 ? destinations.get(1) : null) : destinations.get(0);

        // verify payout addresses
        if (buyerPayoutDestination != null && !buyerPayoutDestination.getAddress().equals(contract.getBuyerPayoutAddressString())) throw new IllegalArgumentException("Buyer payout address does not match contract");
        if (sellerPayoutDestination != null && !sellerPayoutDestination.getAddress().equals(contract.getSellerPayoutAddressString())) throw new IllegalArgumentException("Seller payout address does not match contract");

        // verify change address is multisig's primary address
        if (!arbitratorSignedPayoutTx.getChangeAmount().equals(BigInteger.ZERO) && !arbitratorSignedPayoutTx.getChangeAddress().equals(multisigWallet.getPrimaryAddress())) throw new IllegalArgumentException("Change address is not multisig wallet's primary address");

        // verify sum of outputs = destination amounts + change amount
        BigInteger destinationSum = (buyerPayoutDestination == null ? BigInteger.ZERO : buyerPayoutDestination.getAmount()).add(sellerPayoutDestination == null ? BigInteger.ZERO : sellerPayoutDestination.getAmount());
        if (!arbitratorSignedPayoutTx.getOutputSum().equals(destinationSum.add(arbitratorSignedPayoutTx.getChangeAmount()))) throw new IllegalArgumentException("Sum of outputs != destination amounts + change amount");

        // get actual payout amounts
        BigInteger actualBuyerAmount = buyerPayoutDestination == null ? BigInteger.ZERO : buyerPayoutDestination.getAmount();
        BigInteger actualSellerAmount = sellerPayoutDestination == null ? BigInteger.ZERO : sellerPayoutDestination.getAmount();

        // verify payouts sum to unlocked balance within loss of precision due to conversion to centineros
        BigInteger txCost = arbitratorSignedPayoutTx.getFee().add(arbitratorSignedPayoutTx.getChangeAmount()); // cost = fee + lost dust change
        if (!arbitratorSignedPayoutTx.getChangeAmount().equals(BigInteger.ZERO)) log.warn("Dust left in multisig wallet for {} {}: {}", getClass().getSimpleName(), getId(), arbitratorSignedPayoutTx.getChangeAmount());
        if (getWallet().getUnlockedBalance().subtract(actualBuyerAmount.add(actualSellerAmount).add(txCost)).compareTo(BigInteger.ZERO) > 0) {
            throw new IllegalArgumentException("The dispute payout amounts do not sum to the wallet's unlocked balance while verifying the dispute payout tx, unlocked balance=" + getWallet().getUnlockedBalance() + " vs sum payout amount=" + actualBuyerAmount.add(actualSellerAmount) + ", buyer payout=" + actualBuyerAmount + ", seller payout=" + actualSellerAmount);
        }

        // verify payout amounts
        BigInteger[] buyerSellerPayoutTxCost = getBuyerSellerPayoutTxCost(disputeResult, txCost);
        BigInteger expectedBuyerAmount = disputeResult.getBuyerPayoutAmountBeforeCost().subtract(buyerSellerPayoutTxCost[0]);
        BigInteger expectedSellerAmount = disputeResult.getSellerPayoutAmountBeforeCost().subtract(buyerSellerPayoutTxCost[1]);
        if (!expectedBuyerAmount.equals(actualBuyerAmount)) throw new IllegalArgumentException("Unexpected buyer payout: " + expectedBuyerAmount + " vs " + actualBuyerAmount);
        if (!expectedSellerAmount.equals(actualSellerAmount)) throw new IllegalArgumentException("Unexpected seller payout: " + expectedSellerAmount + " vs " + actualSellerAmount);

        // check daemon connection
        verifyDaemonConnection();

        // sign arbitrator-signed payout tx
        if (getPayoutTxHex() == null) {
            try {
                log.info("Signing dispute payout tx for {} {}", getClass().getSimpleName(), getShortId());
                MoneroMultisigSignResult result = multisigWallet.signMultisigTxHex(unsignedPayoutTxHex);
                if (result.getSignedMultisigTxHex() == null) throw new RuntimeException("Error signing arbitrator-signed payout tx");
                String signedMultisigTxHex = result.getSignedMultisigTxHex();
                disputeTxSet.setMultisigTxHex(signedMultisigTxHex);
                setPayoutTxHex(signedMultisigTxHex);
                HavenoUtils.arbitrationManager.requestPersistence(this); // TODO: no need to update disputes so far?
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage());
            }

            // verify mining fee is within tolerance by recreating payout tx
            // TODO (monero-project): creating tx will require exchanging updated multisig hex if message needs reprocessed. provide weight with describe_transfer so fee can be estimated?
            MoneroTxWallet feeEstimateTx = null;
            try {
                log.info("Creating dispute fee estimate tx for {} {}", getClass().getSimpleName(), getShortId());
                feeEstimateTx = createDisputePayoutTx(dispute.getContract(), disputeResult, false);
            } catch (Exception e) {
                if (isPayoutPublished()) log.warn("Payout tx already published for {} {}, skipping fee verification", getClass().getSimpleName(), getShortId());
                else throw new RuntimeException("Could not recreate dispute payout tx to verify fee: " + e.getMessage(), e);
            }
            if (feeEstimateTx != null) {
                HavenoUtils.verifyMinerFee(feeEstimateTx.getFee(), arbitratorSignedPayoutTx.getFee());
                log.info("Dispute payout tx fee is within tolerance for {} {}", getClass().getSimpleName(), getShortId());
            }
        } else {
            log.warn("Payout tx already signed for {} {}, skipping signing", getClass().getSimpleName(), getShortId());
            disputeTxSet.setMultisigTxHex(getPayoutTxHex());
        }

        // submit fully signed payout tx to the network
        for (int i = 0; i < TradeProtocol.MAX_ATTEMPTS; i++) {
            MoneroRpcConnection sourceConnection = xmrConnectionService.getConnection();
            try {
                List<String> txHashes = multisigWallet.submitMultisigTxHex(disputeTxSet.getMultisigTxHex());
                disputeTxSet.getTxs().get(0).setHash(txHashes.get(0)); // manually update hash which is known after signed
                break;
            } catch (Exception e) {
                if (isPayoutPublished()) return null;
                if (HavenoUtils.isMultisigError(e)) throw new IllegalArgumentException(e);
                log.warn("Failed to submit dispute payout tx, tradeId={}, attempt={}/{}, error={}", getShortId(), i + 1, TradeProtocol.MAX_ATTEMPTS, e.getMessage());
                if (i == TradeProtocol.MAX_ATTEMPTS - 1) throw e;
                if (getXmrConnectionService().isConnected()) requestSwitchToNextBestConnection(sourceConnection);
                HavenoUtils.waitFor(TradeProtocol.REPROCESS_DELAY_MS); // wait before retrying
            }
        }

        // update state
        setPayoutTx(disputeTxSet.getTxs().get(0));
        setPayoutState(Trade.PayoutState.PAYOUT_PUBLISHED);
        dispute.setDisputePayoutTxId(disputeTxSet.getTxs().get(0).getHash());
        HavenoUtils.arbitrationManager.requestPersistence(this);
        return disputeTxSet;
    }

    private static BigInteger[] getBuyerSellerPayoutTxCost(DisputeResult disputeResult, BigInteger payoutTxCost) {
        boolean isBuyerWinner = disputeResult.getWinner() == Winner.BUYER;
        BigInteger loserAmount = isBuyerWinner ? disputeResult.getSellerPayoutAmountBeforeCost() : disputeResult.getBuyerPayoutAmountBeforeCost();
        if (loserAmount.equals(BigInteger.ZERO)) {
            BigInteger buyerPayoutTxFee = isBuyerWinner ? payoutTxCost : BigInteger.ZERO;
            BigInteger sellerPayoutTxFee = isBuyerWinner ? BigInteger.ZERO : payoutTxCost;
            return new BigInteger[] { buyerPayoutTxFee, sellerPayoutTxFee };
        } else {
            switch (disputeResult.getSubtractFeeFrom()) {
                case BUYER_AND_SELLER:
                    BigInteger payoutTxFeeSplit = payoutTxCost.divide(BigInteger.valueOf(2));
                    return new BigInteger[] { payoutTxFeeSplit, payoutTxFeeSplit };
                case BUYER_ONLY:
                    return new BigInteger[] { payoutTxCost, BigInteger.ZERO };
                case SELLER_ONLY:
                    return new BigInteger[] { BigInteger.ZERO, payoutTxCost };
                default:
                    throw new RuntimeException("Unsupported subtract fee from: " + disputeResult.getSubtractFeeFrom());
            }
        }
    }

    /**
     * In case there's a problem observing the payout tx (e.g. due to stale multisig state),
     * peers can communicate the payout tx id.
     * 
     * @param payoutTxId is the payout tx id to process
     */
    public void processBuyerPayout(String payoutTxId) {
        if (payoutTxId == null) throw new IllegalArgumentException("Payout tx id cannot be null");
        if (!isBuyer()) throw new IllegalStateException("Only buyer can process buyer payout tx for " + getClass().getSimpleName() + " " + getShortId());

        // poll the main wallet
        log.warn("Processing payout tx for {} {} by polling main wallet", getClass().getSimpleName(), getShortId());
        xmrWalletService.doPollWallet(true);

        // fetch payout tx from main wallet
        MoneroTxWallet payoutTx = xmrWalletService.getWallet().getTx(payoutTxId);
        if (payoutTx == null) throw new IllegalStateException("Payout tx id " + payoutTxId + " not found for " + getClass().getSimpleName() + " " + getId());
        if (payoutTx.isFailed()) throw new IllegalStateException("Payout tx " + payoutTxId + " is failed for " + getClass().getSimpleName() + " " + getId());

        // verify incoming amount
        BigInteger txCost = payoutTx.getFee();
        BigInteger txCostSplit = txCost.divide(BigInteger.valueOf(2));
        BigInteger expectedAmount = getBuyer().getSecurityDeposit().add(getAmount()).subtract(txCostSplit);
        if (!payoutTx.getIncomingAmount().equals(expectedAmount)) throw new IllegalStateException("Payout tx incoming amount is not deposit amount + trade amount - 1/2 tx costs, " + payoutTx.getIncomingAmount() + " vs " + getBuyer().getSecurityDeposit().add(getAmount()).subtract(txCostSplit));

        // update payout tx
        setPayoutTx(payoutTx);
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
            processModel.getPaymentAccountDecryptedProperty().set(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nullable
    public MoneroTxWallet getTakerDepositTx() {
        return getTaker().getDepositTx();
    }

    @Nullable
    public MoneroTxWallet getMakerDepositTx() {
        return getMaker().getDepositTx();
    }

    private Long getDepositsNumConfirmations() {
        MoneroTxWallet makerDepositTx = getMakerDepositTx();
        MoneroTxWallet takerDepositTx = getTakerDepositTx();
        if (makerDepositTx == null || (takerDepositTx == null && !hasBuyerAsTakerWithoutDeposit())) return null;
        if (Boolean.TRUE.equals(makerDepositTx.isFailed()) || (takerDepositTx != null && Boolean.TRUE.equals(takerDepositTx.isFailed()))) return null;
        if (makerDepositTx.getHeight() == null || (takerDepositTx != null && takerDepositTx.getHeight() == null)) return null;
        return hasBuyerAsTakerWithoutDeposit() ? makerDepositTx.getNumConfirmations() : Math.min(makerDepositTx.getNumConfirmations(), takerDepositTx.getNumConfirmations());
    }

    private Long getDepositsConfirmedHeight() {
        MoneroTxWallet makerDepositTx = getMakerDepositTx();
        MoneroTxWallet takerDepositTx = getTakerDepositTx();
        if (makerDepositTx == null || (takerDepositTx == null && !hasBuyerAsTakerWithoutDeposit())) return null;
        if (Boolean.TRUE.equals(makerDepositTx.isFailed()) || (takerDepositTx != null && Boolean.TRUE.equals(takerDepositTx.isFailed()))) return null;
        if (makerDepositTx.getHeight() == null || (takerDepositTx != null && takerDepositTx.getHeight() == null)) return null;
        return Math.max(makerDepositTx.getHeight(), hasBuyerAsTakerWithoutDeposit() ? 0l : takerDepositTx.getHeight());
    }

    private Long getDepositsFinalizedHeight() {
        Long depositsConfirmedHeight = getDepositsConfirmedHeight();
        if (depositsConfirmedHeight == null) return null;
        return depositsConfirmedHeight + NUM_BLOCKS_DEPOSITS_FINALIZED - 1;
    }

    public void addAndPersistChatMessage(ChatMessage chatMessage) {
        synchronized (chatMessages) {
            if (!chatMessages.contains(chatMessage)) {
                chatMessages.add(chatMessage);
            } else {
                log.error("Trade ChatMessage already exists");
            }
        }
    }

    public boolean removeAllChatMessages() {
        synchronized (chatMessages) {
            if (chatMessages.size() > 0) {
                chatMessages.clear();
                return true;
            }
            return false;
        }
    }

    public boolean mediationResultAppliedPenaltyToSeller() {
        // If mediated payout is same or more then normal payout we enable otherwise a penalty was applied
        // by mediators and we keep the confirm disabled to avoid that the seller can complete the trade
        // without the penalty.
        long payoutAmountFromMediation = processModel.getSellerPayoutAmountFromMediation();
        long normalPayoutAmount = getSeller().getSecurityDeposit().longValueExact();
        return payoutAmountFromMediation < normalPayoutAmount;
    }

    public void clearAndShutDown() {

        // unregister p2p message listener immediately
        removeDecryptedDirectMessageListener();
        
        // clear process data and shut down trade
        ThreadUtils.execute(() -> {
            clearProcessData();
            onShutDownStarted();
            ThreadUtils.submitToPool(() -> shutDown()); // run off trade thread
        }, getId());
    }

    private void clearProcessData() {

        // delete trade wallet
        synchronized (walletLock) {
            if (!walletExists()) return; // done if already cleared
            deleteWallet();
        }

        // TODO: clear other process data
        if (isPayoutFinalized() || processModel.isPaymentReceivedMessagesAcked()) setPayoutTxHex(null);
        for (TradePeer peer : getAllPeers()) {
            peer.setUpdatedMultisigHex(null);
            peer.setDisputeClosedMessage(null);
            peer.setPaymentSentMessage(null);
            peer.setDepositTxHex(null);
            peer.setDepositTxKey(null);
            if (peer.isPaymentReceivedMessageAckedOrNacked() || isPayoutFinalized()) peer.setUnsignedPayoutTxHex(null);
            if (peer.isPaymentReceivedMessageAckedOrNacked()) peer.setPaymentReceivedMessage(null);
        }
    }

    private void removeDecryptedDirectMessageListener() {
        if (getProcessModel() == null || getProcessModel().getProvider() == null || getProcessModel().getP2PService() == null) return;
        getProcessModel().getP2PService().removeDecryptedDirectMessageListener(getProtocol());
    }

    public void maybeClearSensitiveData() {
        String change = "";
        if (contract != null && contract.maybeClearSensitiveData()) {
            change += "contract;";
        }
        if (processModel != null && processModel.maybeClearSensitiveData()) {
            change += "processModel;";
        }
        if (contractAsJson != null) {
            String edited = Contract.sanitizeContractAsJson(contractAsJson);
            if (!edited.equals(contractAsJson)) {
                contractAsJson = edited;
                change += "contractAsJson;";
            }
        }
        if (removeAllChatMessages()) {
            change += "chat messages;";
        }
        if (change.length() > 0) {
            log.info("Cleared sensitive data from {} of {} {}", change, getClass().getSimpleName(), getShortId());
        }
    }

    public void onShutDownStarted() {
        if (!isShutDownStarted) {
            if (wallet != null) log.info("Preparing to shut down {} {}", getClass().getSimpleName(), getId());
            isShutDownStarted = true;
            stopPolling();
        }
    }

    public void shutDown() {
        if (isShutDown) return; // ignore if already shut down
        onShutDownStarted();
        boolean isUnlockedAndDeleted = isPayoutUnlocked() && !walletExistsNoSync(); // previous versions deleted wallet after payout unlocked
        if (!isPayoutFinalized() && !isUnlockedAndDeleted) log.info("Shutting down {} {}", getClass().getSimpleName(), getId());

        // unregister p2p message listener
        removeDecryptedDirectMessageListener();

        // create task to shut down trade
        Runnable shutDownTask = () -> {

            // repeatedly acquire lock to clear tasks
            for (int i = 0; i < 20; i++) {
                synchronized (getLock()) {
                    HavenoUtils.waitFor(10);
                }
            }

            // shut down trade threads
            isShutDown = true;
            List<Runnable> shutDownThreads = new ArrayList<>();
            shutDownThreads.add(() -> ThreadUtils.shutDown(getId()));
            ThreadUtils.awaitTasks(shutDownThreads);
            stopProtocolTimeout();
            isInitialized = false;

            // save and close
            if (wallet != null) {
                try {
                    closeWallet();
                } catch (Exception e) {
                    // warning will be logged for main wallet, so skip logging here
                    //log.warn("Error closing monero-wallet-rpc subprocess for {} {}: {}. Was Haveno stopped manually with ctrl+c?", getClass().getSimpleName(), getId(), e.getMessage());
                }
            }
        };

        // shut down trade with timeout
        try {
            ThreadUtils.awaitTask(shutDownTask, SHUTDOWN_TIMEOUT_MS);
        } catch (Exception e) {
            log.warn("Error shutting down {} {}: {}\n", getClass().getSimpleName(), getId(), e.getMessage(), e);

            // force close wallet
            forceCloseWallet();
        }

        // de-initialize
        if (idleSyncer != null) {
            xmrWalletService.removeWalletListener(idleSyncer);
            idleSyncer = null;
        }
        UserThread.execute(() -> {
            if (tradeStateSubscription != null) tradeStateSubscription.unsubscribe();
            if (tradePhaseSubscription != null) tradePhaseSubscription.unsubscribe();
            if (payoutStateSubscription != null) payoutStateSubscription.unsubscribe();
            if (disputeStateSubscription != null) disputeStateSubscription.unsubscribe();
        });
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Trade error cleanup
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void onProtocolInitializationError() {

        // check if deposits published
        if (isDepositsPublished()) {
            restoreDepositsPublishedTrade();
            return;
        }

        // remove if deposit not requested or is failed
        if (!isDepositRequested() || isDepositRequestFailed()) {
            removeTradeOnError();
            return;
        }

        // done if wallet already deleted
        if (!walletExists()) {
            removeTradeOnError();
            return;
        }

        // set error height
        if (processModel.getTradeProtocolErrorHeight() == 0) {
            log.warn("Scheduling to remove trade if unfunded for {} {} from height {}", getClass().getSimpleName(), getId(), xmrConnectionService.getLastInfo().getHeight());
            processModel.setTradeProtocolErrorHeight(xmrConnectionService.getLastInfo().getHeight()); // height denotes scheduled error handling
        }

        // move to failed trades
        processModel.getTradeManager().onMoveInvalidTradeToFailedTrades(this);
        requestPersistence();

        // listen for deposits published to restore trade
        protocolErrorStateSubscription = EasyBind.subscribe(stateProperty(), state -> {
            if (isDepositsPublished()) {
                restoreDepositsPublishedTrade();
                if (protocolErrorStateSubscription != null) {    // unsubscribe
                    protocolErrorStateSubscription.unsubscribe();
                    protocolErrorStateSubscription = null;
                }
            }
        });

        // listen for block confirmations to remove trade
        long startTime = System.currentTimeMillis();
        protocolErrorHeightSubscription = EasyBind.subscribe(walletHeight, lastWalletHeight -> {
            if (isShutDown || isDepositsPublished()) return;
            if (lastWalletHeight.longValue() < processModel.getTradeProtocolErrorHeight() + DELETE_AFTER_NUM_BLOCKS) return;
            if (System.currentTimeMillis() - startTime < DELETE_AFTER_MS) return;

            // remove on trade thread
            ThreadUtils.execute(() -> {

                // get trade's deposit txs from daemon
                MoneroTx makerDepositTx = getMaker().getDepositTxHash() == null ? null : xmrWalletService.getMonerod().getTx(getMaker().getDepositTxHash());
                MoneroTx takerDepositTx = getTaker().getDepositTxHash() == null ? null : xmrWalletService.getMonerod().getTx(getTaker().getDepositTxHash());

                // remove trade and wallet if neither deposit tx published
                if (makerDepositTx == null && takerDepositTx == null) {
                    log.warn("Deleting {} {} after protocol error", getClass().getSimpleName(), getId());
                    if (this instanceof ArbitratorTrade && (getMaker().getReserveTxHash() != null || getTaker().getReserveTxHash() != null)) {
                        processModel.getTradeManager().onMoveInvalidTradeToFailedTrades(this); // arbitrator retains trades with reserved funds for analysis and penalty
                        deleteWallet();
                        onShutDownStarted();
                        ThreadUtils.submitToPool(() -> shutDown()); // run off thread
                    } else {
                        removeTradeOnError();
                    }
                } else if (!isPayoutPublished()) {

                    // set error if wallet may be partially funded
                    String errorMessage = "Refusing to delete " + getClass().getSimpleName() + " " + getId() + " after protocol error because its wallet might be funded";
                    prependErrorMessage(errorMessage);
                    log.warn(errorMessage);
                }

                // unsubscribe
                if (protocolErrorHeightSubscription != null) {
                    protocolErrorHeightSubscription.unsubscribe();
                    protocolErrorHeightSubscription = null;
                }
            }, getId());
        });
    }

    public boolean isProtocolErrorHandlingScheduled() {
        return processModel.getTradeProtocolErrorHeight() > 0;
    }

    private void restoreDepositsPublishedTrade() {

        // close open offer
        if (this instanceof MakerTrade && processModel.getOpenOfferManager().getOpenOffer(getId()).isPresent()) {
            log.info("Closing open offer because {} {} was restored after protocol error", getClass().getSimpleName(), getShortId());
            processModel.getOpenOfferManager().closeSpentOffer(checkNotNull(getOffer()));
        }

        // re-freeze outputs
        xmrWalletService.freezeOutputs(getSelf().getReserveTxKeyImages());

        // restore trade from failed trades
        processModel.getTradeManager().onMoveFailedTradeToPendingTrades(this);
    }

    private void removeTradeOnError() {
        synchronized (removeTradeOnErrorLock) {

            // skip if already shut down or removed
            if (isShutDown || !processModel.getTradeManager().hasTrade(getId())) return;
            log.warn("removeTradeOnError() trade={}, tradeId={}, state={}", getClass().getSimpleName(), getShortId(), getState());

            // force close and re-open wallet in case stuck
            forceCloseWallet();
            if (isDepositRequested()) getWallet();

            // unreserve taker's key images
            if (this instanceof TakerTrade) {
                ThreadUtils.submitToPool(() -> {
                    xmrWalletService.thawOutputs(getSelf().getReserveTxKeyImages());
                });
            }

            // unreserve maker's open offer
            Optional<OpenOffer> openOffer = processModel.getOpenOfferManager().getOpenOffer(this.getId());
            if (this instanceof MakerTrade && openOffer.isPresent()) {
                processModel.getOpenOfferManager().unreserveOpenOffer(openOffer.get());
            }

            // clear and shut down trade
            onShutDownStarted();
            clearAndShutDown();

            // shut down trade thread
            try {
                ThreadUtils.shutDown(getId(), 5000l);
            } catch (Exception e) {
                log.warn("Error shutting down trade thread for {} {}: {}", getClass().getSimpleName(), getId(), e.getMessage());
            }

            // unregister trade
            processModel.getTradeManager().unregisterTrade(this);
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

    public void addInitProgressStep() {
        startProtocolTimeout();
        initProgress = Math.min(1.0, (double) ++initStep / TOTAL_INIT_STEPS);
        //if (this instanceof TakerTrade) log.warn("Init step count: " + initStep); // log init step count for taker trades in order to update total steps
        UserThread.execute(() -> initProgressProperty.set(initProgress));
    }

    public void startProtocolTimeout() {
        getProtocol().startTimeout(TradeProtocol.TRADE_STEP_TIMEOUT_SECONDS);
    }

    public void stopProtocolTimeout() {
        if (!isInitialized) return;
        TradeProtocol protocol = getProtocol();
        if (protocol == null) return;
        protocol.stopTimeout();
    }

    public void setStateIfValidTransitionTo(State newState) {
        if (state.isValidTransitionTo(newState)) {
            setState(newState);
        }
    }

    public void setState(State state) {

        // skip if no change
        if (state.ordinal() == this.state.ordinal()) return;

        // skip logging until initialized
        if (isInitialized) {
            log.info("Set new state for trade {} {}: {}", getShortId(), this.getClass().getSimpleName(), state);
        }

        // warn if reverting state
        if (state.getPhase().ordinal() < this.state.getPhase().ordinal()) {
            String message = "We got a state change to a previous phase for " + getClass().getSimpleName() + " " + getShortId() + "\n" +
                    "Old state is: " + this.state + ". New state is: " + state;
            log.warn(message);
        }

        this.state = state;

        persistNow(null);
        UserThread.execute(() -> {
            stateProperty.set(state);
            phaseProperty.set(state.getPhase());
        });

        // automatically advance unlocked state to finalized if sufficient confirmations
        if (state == State.DEPOSIT_TXS_UNLOCKED_IN_BLOCKCHAIN) {
            Long numDepositsConfirmations = getDepositsNumConfirmations();
            if (numDepositsConfirmations != null && numDepositsConfirmations >= NUM_BLOCKS_DEPOSITS_FINALIZED) {
                log.info("Auto-advancing state to {} for {} {} because deposits are unlocked and have at least {} confirmations", State.DEPOSIT_TXS_FINALIZED_IN_BLOCKCHAIN, this.getClass().getSimpleName(), getShortId(), NUM_BLOCKS_DEPOSITS_FINALIZED);
                setStateDepositsFinalized();
            }
        }
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

        // skip if no change
        if (payoutState.ordinal() == this.payoutState.ordinal()) return;

        // warn if reverting state
        if (payoutState.ordinal() < this.payoutState.ordinal()) {
            log.warn("Reverting payout state from {} to {} for trade {} {}. Possible reorg?", this.payoutState, payoutState, this.getClass().getSimpleName(), getShortId());
        }

        // skip logging until initialized
        if (isInitialized) {
            log.info("Set new payout state for trade {} {}: {}", getShortId(), this.getClass().getSimpleName(), payoutState);
        }

        this.payoutState = payoutState;
        persistNow(null);
        UserThread.execute(() -> payoutStateProperty.set(payoutState));
    }

    public void setDisputeState(DisputeState disputeState) {

        // skip if no change
        if (disputeState.ordinal() == this.disputeState.ordinal()) return;

        // skip logging until initialized
        if (isInitialized) {
            log.info("Set new dispute state for trade {} {}: {}", getShortId(), this.getClass().getSimpleName(), disputeState);
        }

        // warn if reverting state
        if (disputeState.ordinal() < this.disputeState.ordinal()) {
            String message = "We got a dispute state change to a previous state (id=" + getShortId() + ").\n" +
                    "Old dispute state is: " + this.disputeState + ". New dispute state is: " + disputeState;
            log.warn(message);
        }

        this.disputeState = disputeState;
        persistNow(null);
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

    public DisputeResult getDisputeResult() {
        if (getDisputes().isEmpty()) return null;
        return getDisputes().get(getDisputes().size() - 1).getDisputeResultProperty().get();
    }

    @Nullable
    public MoneroTx getPayoutTx() {
        if (payoutTx == null && payoutTxId != null) {
            if (this instanceof ArbitratorTrade) {
                payoutTx = xmrWalletService.getDaemonTxWithCache(payoutTxId);
            } else {
                payoutTx = xmrWalletService.getTx(payoutTxId);
                if (payoutTx == null) {
                    log.warn("Main wallet is missing payout tx for {} {}, fetching from daemon", getClass().getSimpleName(), getShortId());
                    payoutTx = xmrWalletService.getDaemonTxWithCache(payoutTxId);
                }
            }
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

    public List<TradePeer> getOtherPeers() {
        List<TradePeer> peers = getAllPeers();
        if (!peers.remove(getSelf())) throw new IllegalStateException("Failed to remove self from list of peers");
        return peers;
    }

    public List<TradePeer> getAllPeers() {
        List<TradePeer> peers = new ArrayList<TradePeer>();
        peers.add(getMaker());
        peers.add(getTaker());
        peers.add(getArbitrator());
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

    public TradePeer getOtherPeer(TradePeer peer) {
        List<TradePeer> peers = getAllPeers();
        if (!peers.remove(peer)) throw new IllegalArgumentException("Peer is not maker, taker, or arbitrator");
        if (!peers.remove(getSelf())) throw new IllegalStateException("Self is not maker, taker, or arbitrator");
        if (peers.size() != 1) throw new IllegalStateException("There should be exactly one other peer");
        return peers.get(0);
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

    private MessageState getPaymentSentMessageState() {
        if (isPaymentReceived()) return MessageState.ACKNOWLEDGED;
        if (getSeller().getPaymentSentMessageStateProperty().get() == MessageState.ACKNOWLEDGED) return MessageState.ACKNOWLEDGED;
        if (getSeller().getPaymentSentMessageStateProperty().get() == MessageState.NACKED) return MessageState.NACKED;
        switch (state) {
            case BUYER_SENT_PAYMENT_SENT_MSG:
                return MessageState.SENT;
            case BUYER_SAW_ARRIVED_PAYMENT_SENT_MSG:
                return MessageState.ARRIVED;
            case BUYER_STORED_IN_MAILBOX_PAYMENT_SENT_MSG:
                return MessageState.STORED_IN_MAILBOX;
            case SELLER_RECEIVED_PAYMENT_SENT_MSG:
                return MessageState.ACKNOWLEDGED;
            case BUYER_SEND_FAILED_PAYMENT_SENT_MSG:
                return MessageState.FAILED;
            default:
                return null;
        }
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

    public void maybeUpdateTradePeriod() {
        synchronized (startTimeLock) {
            if (startTime > 0) return; // already set
            if (getTakeOfferDate() == null) return; // trade not started yet
            if (!isDepositsFinalized()) return; // deposits not finalized yet

            long now = System.currentTimeMillis();
            long tradeTime = getTakeOfferDate().getTime();
            MoneroDaemon monerod = xmrWalletService.getMonerod();
            if (monerod == null) throw new RuntimeException("Cannot set start time for trade " + getId() + " because it has no connection to monerod");

            // get finalize time of last deposit tx
            if (getWallet() == null) throw new RuntimeException("Cannot set start time for trade " + getId() + " because cannot get its wallet");
            Long finalizeHeight = getDepositsFinalizedHeight();
            if (finalizeHeight == null || finalizeHeight > xmrConnectionService.getTargetHeight() - 1) return; // TODO: isDepositsFinalized() can assume true, so skip if finalized height not reached
            long finalizeTime = monerod.getBlockByHeight(finalizeHeight).getTimestamp() * 1000;

            // If block date is in future (Date in blocks can be off by +/- 2 hours) we use our current date.
            // If block date is earlier than our trade date we use our trade date.
            if (finalizeTime > now)
                startTime = now;
            else
                startTime = Math.max(finalizeTime, tradeTime);

            log.debug("We set the start for the trade period to {}. Trade started at: {}. Block got mined at: {}",
                    new Date(startTime), new Date(tradeTime), new Date(finalizeTime));
        }
    }

    public long getMaxTradePeriod() {
        return getOffer().getPaymentMethod().getMaxTradePeriod();
    }

    public Date getHalfTradePeriodDate() {
        return new Date(getEffectiveStartTime() + getMaxTradePeriod() / 2);
    }

    public Date getMaxTradePeriodDate() {
        return new Date(getEffectiveStartTime() + getMaxTradePeriod());
    }

    public Date getStartDate() {
        return new Date(getEffectiveStartTime());
    }

    /**
     * Returns the effective start time for the trade period.
     * Returns the current time until the deposits are finalized.
     */
    private long getEffectiveStartTime() {
        synchronized (startTimeLock) {
            return startTime > 0 ? startTime : System.currentTimeMillis();
        }
    }

    public boolean hasFailed() {
        return errorMessageProperty().get() != null;
    }

    public boolean isInPreparation() {
        return getState().getPhase().ordinal() == Phase.INIT.ordinal();
    }

    public boolean isReservingMainWallet() {
        if (isArbitrator()) return false;
        return getState().ordinal() >= State.PREPARATION.ordinal() && getState().ordinal() < State.CONTRACT_SIGNATURE_REQUESTED.ordinal(); // reserve main wallet during initialization protocol until deposit tx created
    }

    public boolean isFundsLockedIn() {
        return isDepositsPublished() && !isPayoutPublished();
    }

    public boolean isDepositRequested() {
        return getState().getPhase().ordinal() >= Phase.DEPOSIT_REQUESTED.ordinal();
    }

    public boolean isDepositRequestFailed() {
        return getState() == Trade.State.PUBLISH_DEPOSIT_TX_REQUEST_FAILED;
    }

    public boolean isDepositTxMissing() {
        if (!wasWalletPolledProperty.get()) throw new IllegalStateException("Cannot determine if deposit tx is missing because wallet has not been polled");
        MoneroTxWallet makerDepositTx = getMakerDepositTx();
        MoneroTxWallet takerDepositTx = getTakerDepositTx();
        boolean hasUnlockedDepositTx = (makerDepositTx != null && Boolean.FALSE.equals(makerDepositTx.isLocked())) || (takerDepositTx != null && Boolean.FALSE.equals(takerDepositTx.isLocked()));
        if (!hasUnlockedDepositTx) return false;
        boolean hasMissingDepositTx = makerDepositTx == null || (!hasBuyerAsTakerWithoutDeposit() && takerDepositTx == null);
        return hasMissingDepositTx;
    }

    public boolean isDepositsPublished() {
        if (isDepositRequestFailed()) return false;
        return getState().getPhase().ordinal() >= Phase.DEPOSITS_PUBLISHED.ordinal() && getMaker().getDepositTxHash() != null && (getTaker().getDepositTxHash() != null || hasBuyerAsTakerWithoutDeposit());
    }

    public boolean isDepositsSeen() {
        return isDepositsPublished() && getState().ordinal() >= State.DEPOSIT_TXS_SEEN_IN_NETWORK.ordinal();
    }

    public boolean isDepositsConfirmed() {
        return isDepositsPublished() && getState().getPhase().ordinal() >= Phase.DEPOSITS_CONFIRMED.ordinal();
    }

    // TODO: hacky way to check for deposits confirmed acks, redundant with getDepositsConfirmedTasks()
    public boolean isDepositsConfirmedAcked() {
        if (this instanceof BuyerTrade) {
            return getArbitrator().isDepositsConfirmedMessageAcked();
        } else {
            for (TradePeer peer : getOtherPeers()) if (!peer.isDepositsConfirmedMessageAcked()) return false;
            return true;
        }
    }

    public boolean isDepositsUnlocked() {
        return isDepositsPublished() && getState().getPhase().ordinal() >= Phase.DEPOSITS_UNLOCKED.ordinal();
    }

    public boolean isDepositsFinalized() {
        if (getState().getPhase().ordinal() < Phase.DEPOSITS_FINALIZED.ordinal()) return false;
        else if (getState().getPhase() == Phase.DEPOSITS_FINALIZED) return true;
        else if (isPayoutFinalized()) return true;
        else {

            // TODO: state can be past finalized (e.g. payment_sent) before the deposits are finalized, ideally use separate enum for deposits, or a single published state + num confirmations
            Long numDepositsConfirmations = getDepositsNumConfirmations();
            if (numDepositsConfirmations == null) {
                if (isBuyer()) { // log a warning for the buyer, since only they are at risk of reorg after payment sent
                    log.warn("Assuming that deposit txs are finalized for trade {} {} because trade is in state {} but has unknown confirmations", getClass().getSimpleName(), getShortId(), getState());
                    Thread.dumpStack();
                }
                return true;
            } else {
                return numDepositsConfirmations >= NUM_BLOCKS_DEPOSITS_FINALIZED;
            }
        }
    }

    public boolean hasPaymentSentMessage() {
        return (isBuyer() ? getSeller() : getBuyer()).getPaymentSentMessage() != null; // buyer stores message to seller and arbitrator, peers store message from buyer
    }

    public boolean hasPaymentReceivedMessage() {
        return (isSeller() ? getBuyer() : getSeller()).getPaymentReceivedMessage() != null; // seller stores message to buyer and arbitrator, peers store message from seller
    }

    public boolean hasDisputeClosedMessage() {

        // arbitrator stores message to buyer and seller, peers store message from arbitrator
        return isArbitrator() ? getBuyer().getDisputeClosedMessage() != null || getSeller().getDisputeClosedMessage() != null : getArbitrator().getDisputeClosedMessage() != null;
    }

    public boolean isDisputeClosed() {
        return getDisputeState().isClosed();
    }

    public boolean isPaymentMarkedSent() {
        return getState().getPhase().ordinal() >= Phase.PAYMENT_SENT.ordinal();
    }

    public boolean isPaymentSent() {
        return getState().getPhase().ordinal() >= Phase.PAYMENT_SENT.ordinal() && getState() != State.BUYER_SEND_FAILED_PAYMENT_SENT_MSG;
    }

    public boolean isPaymentSentMessageProcessed() {
        if (isPaymentReceived()) return true;
        if (isBuyer()) return getState() == State.SELLER_RECEIVED_PAYMENT_SENT_MSG;
        return getState() == Trade.State.BUYER_SENT_PAYMENT_SENT_MSG;
    }

    public boolean isPaymentMarkedReceived() {
        return getState().getPhase().ordinal() >= Phase.PAYMENT_RECEIVED.ordinal();
    }

    public boolean isPaymentReceived() {
        return getState().getPhase().ordinal() >= Phase.PAYMENT_RECEIVED.ordinal() && getState() != State.SELLER_SEND_FAILED_PAYMENT_RECEIVED_MSG;
    }

    public boolean isPaymentReceivedMessageProcessed() {
        if (isSeller()) return getState() == State.BUYER_RECEIVED_PAYMENT_RECEIVED_MSG;
        return getState() == Trade.State.SELLER_SENT_PAYMENT_RECEIVED_MSG;
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

    public boolean isPayoutFinalized() {
        return getPayoutState().ordinal() >= PayoutState.PAYOUT_FINALIZED.ordinal();
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

    public String getShortUid() {
        return Utilities.getShortId(getUid());
    }

    public BigInteger getFrozenAmount() {
        BigInteger sum = BigInteger.ZERO;
        if (getSelf().getReserveTxKeyImages() != null) {
            for (String keyImage : getSelf().getReserveTxKeyImages()) {
                List<MoneroOutputWallet> outputs = xmrWalletService.getOutputs(new MoneroOutputQuery().setIsFrozen(true).setIsSpent(false).setKeyImage(new MoneroKeyImage(keyImage)));
                if (!outputs.isEmpty()) sum = sum.add(outputs.get(0).getAmount());
            }
        }
        return sum;
    }

    public BigInteger getReservedAmount() {
        if (isArbitrator() || !isDepositsPublished() || isPayoutPublished()) return BigInteger.ZERO;
        return isBuyer() ? getBuyer().getSecurityDeposit() : getAmount().add(getSeller().getSecurityDeposit());
    }

    /**
     * Returns the price as XMR/QUOTE.
     */
    public Price getPrice() {
        boolean isInverted = getOffer().isInverted(); // return uninverted price
        return Price.valueOf(offer.getCounterCurrencyCode(), isInverted ? PriceUtil.invertLongPrice(price, offer.getCounterCurrencyCode()) : price);
    }

    public Price getRawPrice() {
        return Price.valueOf(offer.getCounterCurrencyCode(), price);
    }

    @Nullable
    public BigInteger getAmount() {
        return BigInteger.valueOf(amount);
    }

    public BigInteger getMakerFee() {
        return offer.getMakerFee(getAmount());
    }

    public BigInteger getTakerFee() {
        return hasBuyerAsTakerWithoutDeposit() ? BigInteger.ZERO : offer.getTakerFee(getAmount());
    }

    public BigInteger getSecurityDepositBeforeMiningFee() {
        return isBuyer() ? getBuyerSecurityDepositBeforeMiningFee() : getSellerSecurityDepositBeforeMiningFee();
    }

    public BigInteger getBuyerSecurityDepositBeforeMiningFee() {
        return offer.getOfferPayload().getBuyerSecurityDepositForTradeAmount(getAmount());
    }

    public BigInteger getSellerSecurityDepositBeforeMiningFee() {
        return offer.getOfferPayload().getSellerSecurityDepositForTradeAmount(getAmount());
    }

    public boolean isBuyerAsTakerWithoutDeposit() {
        return isBuyer() && isTaker() && BigInteger.ZERO.equals(getBuyerSecurityDepositBeforeMiningFee());
    }

    public boolean hasBuyerAsTakerWithoutDeposit() {
        return getOffer().getOfferPayload().isBuyerAsTakerWithoutDeposit();
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
        return processModel.getMaker().getDepositTxHash() == null || (processModel.getTaker().getDepositTxHash() == null && !hasBuyerAsTakerWithoutDeposit());
    }

    /**
     * Get the duration to delay reprocessing a message based on its reprocess count.
     *
     * @return the duration to delay in seconds
     */
    public long getReprocessDelayInSeconds(int reprocessCount) {
        int retryCycles = 3; // reprocess on next refresh periods for first few attempts (app might auto switch to a good connection)
        if (reprocessCount < retryCycles) return xmrConnectionService.getRefreshPeriodMs() / 1000;
        long delay = 60;
        for (int i = retryCycles; i < reprocessCount; i++) delay *= 2;
        return Math.min(MAX_REPROCESS_DELAY_SECONDS, delay);
    }

    public void maybePublishTradeStatistics() {
        if (shouldPublishTradeStatistics()) {

            // publish after random delay within 24 hours
            UserThread.runAfterRandomDelay(() -> {
                if (!isShutDownStarted) doPublishTradeStatistics();
            }, 0, TradeStatisticsManager.PUBLISH_STATS_RANDOM_DELAY_HOURS * 60 * 60 * 1000, TimeUnit.MILLISECONDS);
        }
    }

    public boolean shouldPublishTradeStatistics() {

        // do not publish if funds not transferred
        if (!tradeAmountTransferred()) return false;

        // only seller or arbitrator publish trade stats
        if (!isSeller() && !isArbitrator()) return false;

        // prior to v3 protocol, only seller publishes trade stats
        if (getOffer().getOfferPayload().getProtocolVersion() < 3 && !isSeller()) return false;

        return true;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////
    
    private boolean tradeAmountTransferred() {
        return isPayoutPublished() && (isPaymentReceived() || (getDisputeResult() != null && getDisputeResult().getWinner() == DisputeResult.Winner.SELLER));
    }

    private void doPublishTradeStatistics() {
        String referralId = processModel.getReferralIdService().getOptionalReferralId().orElse(null);
        boolean isTorNetworkNode = getProcessModel().getP2PService().getNetworkNode() instanceof TorNetworkNode;
        HavenoUtils.tradeStatisticsManager.maybePublishTradeStatistics(this, referralId, isTorNetworkNode);
    }

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

    @Override
    protected void onConnectionChanged(MoneroRpcConnection connection) {
        synchronized (walletLock) {

            // ignore if wallet is not open
            if (wallet == null) return;

            // configure current connection
            connection = xmrConnectionService.getConnection();
            if (!xmrWalletService.isProxyApplied(wasWalletSynced)) connection.setProxyUri(null);

            // check if ignored
            if (isShutDownStarted) return;
            if (getWallet() == null) return;
            if (HavenoUtils.connectionConfigsEqual(connection, wallet.getDaemonConnection())) {
                updatePollPeriod();
                return;
            }

            // set daemon connection
            log.info("Setting daemon connection for {} {}: uri={}, proxyUri={}", getClass().getSimpleName(), getId() , connection == null ? null : connection.getUri(), connection == null ? null : connection.getProxyUri());
            wallet.setDaemonConnection(connection);

            // sync and reprocess messages on new thread
            if (isInitialized && connection != null && !Boolean.FALSE.equals(xmrConnectionService.isConnected())) {
                ThreadUtils.execute(() -> maybeInitSyncing(), getId());
            }

            log.info("Done setting daemon connection for {} {}", getClass().getSimpleName(), getId());
        }
    }

    private void maybeInitSyncing() {
        if (isShutDownStarted || !isDepositRequested()) return;

        // start polling
        if (isArbitrator() && isIdling()) {
            long startSyncingInSec = Math.max(1, ThreadLocalRandom.current().nextLong(0, getPollPeriod()) / 1000l); // random seconds to start polling
            UserThread.runAfter(() -> ThreadUtils.execute(() -> {
                if (!isShutDownStarted) doTryInitSyncing();
            }, getId()), startSyncingInSec);
        } else {
            doTryInitSyncing(); // traders start syncing immediately
        }
    }
    
    private void doTryInitSyncing() {
        getWallet(); // ensure wallet is initialized
        updatePollPeriod();
        startPolling();
    }

    private void trySyncWallet(boolean pollWallet) {
        try {
            syncWallet(pollWallet);
        } catch (Exception e) {
            if (!isShutDownStarted && walletExists()) {
                log.warn("Error syncing trade wallet for {} {}: {}", getClass().getSimpleName(), getId(), e.getMessage());
            }
        }
    }

    private void syncWallet(boolean pollWallet) {
        MoneroRpcConnection sourceConnection = xmrConnectionService.getConnection();
        MoneroWallet sourceWallet = wallet;
        try {
            synchronized (walletLock) {
                if (getWallet() == null) throw new IllegalStateException("Cannot sync trade wallet because it doesn't exist for " + getClass().getSimpleName() + " " + getId());
                if (getWallet().getDaemonConnection() == null) throw new RuntimeException("Cannot sync trade wallet because it's not connected to a Monero daemon for " + getClass().getSimpleName() + " " + getId());
                if (!isDepositRequested()) throw new IllegalStateException("Cannot sync trade wallet because deposit txs are not requested for " + getClass().getSimpleName() + " " + getId());
                if (isWalletBehind()) {
                    log.info("Syncing wallet for {} {} from height {}", getShortId(), getClass().getSimpleName(), walletHeight.get());
                    long startTime = System.currentTimeMillis();
                    syncWalletIfBehind();
                    log.info("Done syncing wallet for {} {} in {} ms", getShortId(), getClass().getSimpleName(), System.currentTimeMillis() - startTime);
                }
            }
    
            // apply tor after wallet synced depending on configuration
            if (!wasWalletSynced) {
                wasWalletSynced = true;
                if (xmrWalletService.isProxyApplied(wasWalletSynced)) {
                    onConnectionChanged(xmrConnectionService.getConnection());
                }
            }
    
            if (pollWallet) doPollWallet();
        } catch (Exception e) {
            if (wallet == null || wallet != sourceWallet) throw e;
            if (!(e instanceof IllegalStateException) && !isShutDownStarted) {
                ThreadUtils.execute(() -> requestSwitchToNextBestConnection(sourceConnection), getId());
            }
            if (HavenoUtils.isUnresponsive(e)) { // wallet can be stuck a while
                if (isShutDownStarted) forceCloseWallet();
                else forceRestartTradeWallet();
            }
            throw e;
        }
    }

    public void updatePollPeriod() {
        if (isShutDownStarted) return;
        setPollPeriod(getPollPeriod());
    }

    private void setPollPeriod(long pollPeriodMs) {
        synchronized (pollLock) {
            if (this.isShutDownStarted) return;
            if (this.pollPeriodMs != null && this.pollPeriodMs == pollPeriodMs) return;
            this.pollPeriodMs = pollPeriodMs;
            if (isPolling()) resetPolling(false);
        }
    }

    private long getPollPeriod() {
        if (isIdling()) return IDLE_SYNC_PERIOD_MS;
        return xmrConnectionService.getRefreshPeriodMs();
    }

    private void startPolling() {
        startPolling(false);
    }

    private void startPolling(boolean skipFirstPoll) {
        synchronized (pollLock) {
            if (isShutDownStarted || isPolling()) return;
            updatePollPeriod();
            skipNextPollLoop = skipFirstPoll;
            if (!skipFirstPoll) log.info("Starting to poll wallet for {} {}", getClass().getSimpleName(), getId());
            pollLooper = new TaskLooper(() -> new Thread(() -> {
                if (skipNextPollLoop) {
                    skipNextPollLoop = false;
                    return;
                }
                pollWallet();
            }).start());
            pollLooper.start(pollPeriodMs);
        }
    }

    private void stopPolling() {
        synchronized (pollLock) {
            if (isPolling()) {
                pollLooper.stop();
                pollLooper = null;
            }
        }
    }

    private void resetPolling(boolean skipFirstPoll) {
        synchronized (pollLock) {
            if (isShutDownStarted) return;
            stopPolling();
            startPolling(skipFirstPoll);
        }
    }
    
    private boolean isPolling() {
        synchronized (pollLock) {
            return pollLooper != null;
        }
    }

    private void pollWallet() {
        synchronized (pollLock) {
            if (pollInProgress) {
                maybeCloseIdlingWallet();
                return;
            }
        }
        doPollWallet();
        maybeCloseIdlingWallet();
    }

    private void maybeCloseIdlingWallet() {
        
        // close arbitrator trade wallet while idling
        if (isArbitrator()) {
            ThreadUtils.execute(() -> {
                if (isIdling() && !isPayoutFinalized()) {
                    closeWallet(false);
                }
            }, getId());
        }
    }

    private void doPollWallet() {
        doPollWallet(false);
    }

    private void doPollWallet(boolean offlinePoll) {
        MoneroWallet sourceWallet = wallet;

        // skip if shut down started
        if (isShutDownStarted) return;

        // set poll in progress
        boolean pollInProgressSet = false;
        synchronized (pollLock) {
            if (!pollInProgress) pollInProgressSet = true;
            pollInProgress = true;
        }

        // poll wallet
        MoneroRpcConnection sourceConnection = xmrConnectionService.getConnection();
        try {

            // skip if shut down started
            if (isShutDownStarted) return;

            // skip if payout finalized
            if (isPayoutFinalized()) return;

            // skip if deposit txs unknown or not expected
            if (!isDepositRequested() || isDepositRequestFailed() || processModel.getMaker().getDepositTxHash() == null || (processModel.getTaker().getDepositTxHash() == null && !hasBuyerAsTakerWithoutDeposit())) return;

            // skip if daemon not synced
            if (!offlinePoll && (xmrConnectionService.getTargetHeight() == null || !xmrConnectionService.isSyncedWithinTolerance())) return;

            // sync if wallet too far behind daemon
            boolean longSync = false;
            if (!offlinePoll) {
                if (!wasWalletSynced || walletHeight.get() < xmrConnectionService.getTargetHeight() - SYNC_EVERY_NUM_BLOCKS) {
                    longSync = true;
                    syncWallet(false);
                } else {
                    syncWalletIfBehind();
                }
            }

            // update deposit txs
            boolean depositTxsUninitialized = isDepositRequested() && (getMaker().getDepositTx() == null || (getTaker().getDepositTx() == null && !hasBuyerAsTakerWithoutDeposit()));
            if (depositTxsUninitialized || !isDepositsFinalized()) {

                // set deposit txs from trade wallet
                List<MoneroTxWallet> txs = getTxs(false);
                if (hasDepositTxs(txs)) {
                    setDepositTxs(txs, false);
                } else if (!offlinePoll) {
                    txs = getTxs(true);

                    // txs may not be fetched if confirmed after last sync
                    if (isDepositsSeen() && !hasDepositTxs(txs)) {
                        log.info("Deposits are missing for {} {} after being published, resyncing", getClass().getSimpleName(), getId());
                        HavenoUtils.waitFor(MISSING_TXS_DELAY_MS);
                        sync();
                        txs = getTxs(true);
                    }
                    setDepositTxs(txs, true);
                }
            }

            // update payout tx
            boolean hasUnlockedDeposit = hasUnlockedTx();
            if (isDepositsUnlocked() || hasUnlockedDeposit) { // arbitrator idles so these may not be the same

                // determine if payout tx expected
                boolean isPayoutExpected = isPaymentReceived() || hasPaymentReceivedMessage() || hasDisputeClosedMessage() || disputeState.ordinal() >= DisputeState.ARBITRATOR_SENT_DISPUTE_CLOSED_MSG.ordinal();

                // rescan spent outputs to detect unconfirmed payout tx
                if (getPayoutState() == PayoutState.PAYOUT_PUBLISHED || (isPayoutExpected && wallet.getBalance().compareTo(BigInteger.ZERO) > 0) || (isDepositsPublished() && longSync)) {
                    try {
                        rescanSpent(true);
                    } catch (Exception e) {
                        // TODO: rescan error is common, e.g. "no connection to daemon"
                        //ThreadUtils.submitToPool(() -> requestSwitchToNextBestConnection(sourceConnection)); // do not block polling thread
                    }
                }

                // get txs from trade wallet
                boolean checkPool = !offlinePoll && isPayoutExpected && !isPayoutConfirmed();
                List<MoneroTxWallet> txs = null;
                if (depositTxsUninitialized || wallet != null) { // get txs if deposits uninitialized or wallet is open
                    txs = getTxs(checkPool);
                }

                // txs may not be fetched if confirmed after last sync
                if (!offlinePoll && isPayoutPublished() && getPayoutTxId() != null && !hasPayoutTx(txs)) {
                    log.info("Payout is missing for {} {} after being published, resyncing", getClass().getSimpleName(), getId());
                    HavenoUtils.waitFor(MISSING_TXS_DELAY_MS);
                    sync();
                    txs = getTxs(true);
                    checkPool = true;
                }

                // set deposit and payout txs
                setDepositTxs(txs, checkPool);
                setPayoutTx(txs, checkPool);
            }

            // update trade period and poll properties
            if (!offlinePoll) maybeUpdateTradePeriod(); // no update possible if offline poll (also avoids deadlock because setting trade period state depends on locking wallet within another lock)
            wasWalletPolledProperty.set(true);
            if (!offlinePoll) {
                wasWalletSyncedAndPolledProperty.set(true);
                resetPolling(true); // do not poll again until next period
            }
        } catch (Exception e) {
            if (wallet == null || wallet != sourceWallet) return; // skip error handling if another thread force restarts while polling
            if (!(e instanceof IllegalStateException) && !isShutDownStarted && !offlinePoll && !wasWalletSyncedAndPolledProperty.get()) { // request connection switch on failure until synced and polled
                ThreadUtils.execute(() -> requestSwitchToNextBestConnection(sourceConnection), getId());
            }
            if (HavenoUtils.isUnresponsive(e)) { // wallet can be stuck a while
                if (wallet != null && !isShutDownStarted) {
                    log.warn("Error polling unresponsive trade wallet for {} {}, errorMessage={}. Monerod={}", getClass().getSimpleName(), getShortId(), e.getMessage(), wallet.getDaemonConnection());
                }
                if (isShutDownStarted) forceCloseWallet();
                else forceRestartTradeWallet();
            } else {
                boolean isWalletConnected = isWalletConnectedToDaemon();
                if (!isShutDownStarted && isWalletConnected) {
                    if (isExpectedWalletError(e)) {
                        log.warn("Error polling trade wallet for {} {}, errorMessage={}. Monerod={}", getClass().getSimpleName(), getShortId(), e.getMessage(), wallet.getDaemonConnection());
                    } else {
                        log.warn("Error polling trade wallet for {} {}, errorMessage={}. Monerod={}", getClass().getSimpleName(), getShortId(), e.getMessage(), wallet.getDaemonConnection(), e); // include stack trace for unexpected errors
                    }
                }
            }
        } finally {
            if (pollInProgressSet) {
                synchronized (pollLock) {
                    pollInProgress = false;
                }
            }
            requestSaveWalletIfElapsedTime();
        }
    }

    private boolean isWalletBehind() {
        return walletHeight.get() < xmrConnectionService.getTargetHeight();
    }

    private boolean syncWalletIfBehind() {
        synchronized (walletLock) {
            if (!isDepositRequested()) throw new IllegalStateException("Cannot sync trade wallet because deposit txs are not requested for " + getClass().getSimpleName() + ", " + getId());
            if (isWalletBehind()) {
                syncWithProgress();
                return true;
            } else {
                return false;
            }
        }
    }

    public MoneroSyncResult sync() {
        synchronized (walletLock) {
            if (!isDepositRequested()) throw new IllegalStateException("Cannot sync trade wallet because deposit txs are not requested for " + getClass().getSimpleName() + ", " + getId());
            log.info("Syncing wallet directly for {} {}", getClass().getSimpleName(), getShortId());
            MoneroSyncResult result = super.sync();
            log.info("Done syncing wallet directly for {} {}", getClass().getSimpleName(), getShortId());
            return result;
        }
    }

    private List<MoneroTxWallet> getTxs(boolean checkPool) {
        MoneroTxQuery query = new MoneroTxQuery().setIncludeOutputs(true);
        if (!checkPool) query.setInTxPool(false);
        synchronized (walletLock) {
            if (getWallet() == null) throw new IllegalStateException("Cannot get transactions from trade wallet because it doesn't exist for " + getClass().getSimpleName() + ", " + getId());
            if (checkPool) {
                synchronized (HavenoUtils.getDaemonLock()) {
                    return wallet.getTxs(query);
                }
            } else {
                return wallet.getTxs(query);
            }
        }
    }

    private void setDepositTxs(List<MoneroTxWallet> txs, boolean poolChecked) {

        // get deposit txs
        MoneroTxWallet makerDepositTx = getMakerDepositTx(txs);
        MoneroTxWallet takerDepositTx = getTakerDepositTx(txs);
        MoneroTxWallet buyerDepositTx = getBuyerDepositTx(txs);
        MoneroTxWallet sellerDepositTx = getSellerDepositTx(txs);

        // set txs if known
        if (makerDepositTx != null) getMaker().setDepositTx(makerDepositTx);
        if (takerDepositTx != null) getTaker().setDepositTx(takerDepositTx);

        // set actual buyer security deposit
        if (isSeen(buyerDepositTx)) {
            BigInteger buyerSecurityDeposit = buyerDepositTx.getIncomingAmount();
            if (!getBuyer().getSecurityDeposit().equals(BigInteger.ZERO) && !buyerSecurityDeposit.equals(getBuyer().getSecurityDeposit())) {
                log.warn("Overwriting buyer security deposit for {} {}, old={}, new={}", getClass().getSimpleName(), getShortId(), getBuyer().getSecurityDeposit(), buyerSecurityDeposit);
            }
            getBuyer().setSecurityDeposit(buyerSecurityDeposit);
        }

        // set actual seller security deposit
        if (isSeen(sellerDepositTx)) {
            BigInteger sellerSecurityDeposit = sellerDepositTx.getIncomingAmount().subtract(getAmount());
            if (!getSeller().getSecurityDeposit().equals(BigInteger.ZERO) && !sellerSecurityDeposit.equals(getSeller().getSecurityDeposit())) {
                log.warn("Overwriting seller security deposit for {} {}, old={}, new={}", getClass().getSimpleName(), getShortId(), getSeller().getSecurityDeposit(), sellerSecurityDeposit);
            }
            getSeller().setSecurityDeposit(sellerSecurityDeposit);
        }

        // advance deposit state
        if (isSeen(makerDepositTx) && (hasBuyerAsTakerWithoutDeposit() || isSeen(takerDepositTx))) {
            setStateDepositsSeen();

            // check for deposit txs confirmed
            if (makerDepositTx.isConfirmed() && (hasBuyerAsTakerWithoutDeposit() || takerDepositTx.isConfirmed())) {
                setStateDepositsConfirmed();
            }

            if (makerDepositTx.getNumConfirmations() != null) {

                // check for deposit txs unlocked
                if (makerDepositTx.getNumConfirmations() >= XmrWalletService.NUM_BLOCKS_UNLOCK && (hasBuyerAsTakerWithoutDeposit() || (takerDepositTx.getNumConfirmations() != null && takerDepositTx.getNumConfirmations() >= XmrWalletService.NUM_BLOCKS_UNLOCK))) {
                    setStateDepositsUnlocked();
                }

                // check for deposit txs finalized
                if (makerDepositTx.getNumConfirmations() >= NUM_BLOCKS_DEPOSITS_FINALIZED && (hasBuyerAsTakerWithoutDeposit() || (takerDepositTx.getNumConfirmations() != null && takerDepositTx.getNumConfirmations() >= NUM_BLOCKS_DEPOSITS_FINALIZED))) {
                    setStateDepositsFinalized();
                }
            }
        }

        // revert deposit state if necessary
        State depositsState = getDepositsState(makerDepositTx, takerDepositTx);
        State minDepositsState = isPaymentSent() ? State.DEPOSIT_TXS_UNLOCKED_IN_BLOCKCHAIN : getState();
        if (depositsState.ordinal() >= minDepositsState.ordinal()) {
            firstDepositTxMissingHeight = null;
        } else if (poolChecked) {

            // skip reverting state until next confirmation // TODO: sometimes txs are missing from the wallet and reappear without reorg
            if (firstDepositTxMissingHeight != null && walletHeight.get() > firstDepositTxMissingHeight + REVERT_AFTER_NUM_CONFIRMATIONS - 1) {
                log.warn("Reverting deposits state from {} to {} for {} {}. Possible reorg?", minDepositsState, depositsState, getClass().getSimpleName(), getShortId());
                getMaker().setDepositTx(makerDepositTx);
                getTaker().setDepositTx(takerDepositTx);
                if (depositsState == State.ARBITRATOR_PUBLISHED_DEPOSIT_TXS) setErrorMessage("Deposit transactions are missing for trade " + getShortId() + ". This can happen after a blockchain reorganization.\n\nIf the issue continues, you can contact support or mark the trade as failed.");
                if (!isPaymentSent()) setState(depositsState); // only revert state if payment not sent
            } else if (firstDepositTxMissingHeight == null) {
                log.warn("Missing deposit txs for {} {} at height {}. Waiting {} confirmation before reverting state", getClass().getSimpleName(), getShortId(), walletHeight.get(), REVERT_AFTER_NUM_CONFIRMATIONS);
                firstDepositTxMissingHeight = walletHeight.get();
            }
        }

        // announce deposits update
        depositTxsUpdateCounter.set(depositTxsUpdateCounter.get() + 1);
    }

    private void setPayoutTx(List<MoneroTxWallet> txs, boolean poolChecked) {

        // collect payout info
        boolean hasPayoutTx = false;
        MoneroTxWallet payoutTx = null;
        for (MoneroTxWallet tx : txs) {
            if (!Boolean.TRUE.equals(tx.isIncoming()) && !tx.isFailed()) {
                payoutTx = tx;
                hasPayoutTx = true;
                break;
            } else {
                for (MoneroOutputWallet output : tx.getOutputsWallet()) {
                    if (Boolean.TRUE.equals(output.isSpent())) hasPayoutTx = true; // spent outputs observed on payout published (after rescanning)
                }
            }
        }

        // set payout state
        if (payoutTx != null) {
            firstPayoutTxMissingHeight = null;
            setPayoutTx(payoutTx);
        } else if (hasPayoutTx) {
            firstPayoutTxMissingHeight = null;
            setPayoutState(PayoutState.PAYOUT_PUBLISHED);
        } else if (poolChecked && isPayoutPublished()) { // payout tx seen then lost (e.g. reorg)

            // skip reverting state until confirmations
            if (firstPayoutTxMissingHeight != null && walletHeight.get() > firstPayoutTxMissingHeight + REVERT_AFTER_NUM_CONFIRMATIONS - 1) {

                // reset payment received and dispute closed messages
                for (TradePeer peer : getAllPeers()) {
                    peer.setPaymentReceivedMessage(null);
                    peer.setPaymentReceivedMessageState(MessageState.UNDEFINED);
                    peer.setDisputeClosedMessage(null);
                }

                // revert state
                setPayoutState(PayoutState.PAYOUT_UNPUBLISHED);
                String errorMsg = "The payout transaction is not seen for trade " + getShortId() + ". This can happen after a blockchain reorganization..\n\nIf the payout does not confirm automatically, you can contact support or mark the trade as failed.";
                if (getState().ordinal() >= State.SELLER_SENT_PAYMENT_RECEIVED_MSG.ordinal()) {
                    log.warn("Reverting state of {} {} from {} to {} because payout is unseen. Possible reorg?", getClass().getSimpleName(), getId(), getState(), Trade.State.SELLER_CONFIRMED_PAYMENT_RECEIPT);
                    setState(State.SELLER_CONFIRMED_PAYMENT_RECEIPT);
                    if (isSeller()) onPayoutError(false, true, null);
                    setErrorMessage(errorMsg);
                }

                // move trade back to pending if marked completed
                if (isCompleted()) processModel.getTradeManager().onMoveClosedTradeToPendingTrades(this);
            } else if (firstPayoutTxMissingHeight == null) {
                log.warn("Missing payout tx for {} {} at height {}. Waiting {} confirmations before reverting state", getClass().getSimpleName(), getShortId(), walletHeight.get(), REVERT_AFTER_NUM_CONFIRMATIONS);
                firstPayoutTxMissingHeight = walletHeight.get();
            }
        }
    }

    public void setPayoutTx(MoneroTx payoutTx) {

        // set payout tx fields
        this.payoutTx = payoutTx;
        this.payoutTxId = payoutTx.getHash();
        this.payoutTxFee = payoutTx.getFee() == null ? 0 : payoutTx.getFee().longValueExact();
        this.payoutTxKey = payoutTx.getKey();
        if ("".equals(payoutTxId)) this.payoutTxId = null; // tx id is empty until signed

        // set payout tx id in dispute(s)
        for (Dispute dispute : getDisputes()) dispute.setDisputePayoutTxId(payoutTxId);

        // set final payout amounts
        if (isPaymentReceived()) {
            BigInteger splitTxFee = payoutTx.getFee().divide(BigInteger.valueOf(2));
            getBuyer().setPayoutTxFee(splitTxFee);
            getSeller().setPayoutTxFee(splitTxFee);
            getBuyer().setPayoutAmount(getBuyer().getSecurityDeposit().subtract(getBuyer().getPayoutTxFee()).add(getAmount()));
            getSeller().setPayoutAmount(getSeller().getSecurityDeposit().subtract(getSeller().getPayoutTxFee()));
        } else {
            DisputeResult disputeResult = getDisputeResult();
            if (disputeResult != null) {
                BigInteger[] buyerSellerPayoutTxFees = getBuyerSellerPayoutTxCost(disputeResult, payoutTx.getFee());
                getBuyer().setPayoutTxFee(buyerSellerPayoutTxFees[0]);
                getSeller().setPayoutTxFee(buyerSellerPayoutTxFees[1]);
                getBuyer().setPayoutAmount(disputeResult.getBuyerPayoutAmountBeforeCost().subtract(getBuyer().getPayoutTxFee()));
                getSeller().setPayoutAmount(disputeResult.getSellerPayoutAmountBeforeCost().subtract(getSeller().getPayoutTxFee()));
            }
        }

        // advance payout state
        if (Boolean.TRUE.equals(payoutTx.isRelayed()) || Boolean.TRUE.equals(payoutTx.inTxPool())) setPayoutStatePublished();
        if (payoutTx.isConfirmed()) setPayoutStateConfirmed();
        if (payoutTx.getNumConfirmations() != null) {
            if (payoutTx.getNumConfirmations() >= XmrWalletService.NUM_BLOCKS_UNLOCK) setPayoutStateUnlocked();
            if (payoutTx.getNumConfirmations() >= NUM_BLOCKS_PAYOUT_FINALIZED) setPayoutStateFinalized();
        }

        // revert payout state if necessary
        if (getPayoutState() != getPayoutState(payoutTx)) setPayoutState(getPayoutState(payoutTx));
    }

    /**
     * Handle a payout error due to NACK or the transaction failing (e.g. due to reorg).
     * 
     * @param syncAndPoll whether to sync and poll
     * @param resendPaymentReceivedMessages whether to resend payment received messages if previously confirmed
     * @param paymentReceivedNackSender the peer that sent the payment received NACK, or null if not applicable
     * @return true if the payment received were resent, false otherwise
     */
    public boolean onPayoutError(boolean syncAndPoll, boolean resendPaymentReceivedMessages, TradePeer paymentReceivedNackSender) {
        log.warn("Handling payout error for {} {}", getClass().getSimpleName(), getId());
        if (syncAndPoll) {
            try {
                syncAndPollWallet();
            } catch (Exception e) {
                log.warn("Error syncing and polling wallet for {} {}: {}", getClass().getSimpleName(), getId(), e.getMessage());
            }
        }

        // reset trade state
        log.warn("Resetting trade state after payout error for {} {}, nackSender={}", getClass().getSimpleName(), getId(), paymentReceivedNackSender == null ? null : getPeerRole(paymentReceivedNackSender));
        processModel.setPaymentSentPayoutTxStale(true);
        if (paymentReceivedNackSender != null) {
            paymentReceivedNackSender.setPaymentReceivedMessage(null);
            paymentReceivedNackSender.setPaymentReceivedMessageState(MessageState.NACKED);
        }
        if (!isPayoutFinalized()) { // payout tx may be stale and show as published after reorg, so clear local info for reprocessing until finalized
            log.warn("Resetting missing or failed payout tx after payout error for {} {}", getClass().getSimpleName(), getId());
            getSelf().setUnsignedPayoutTxHex(null);
            setPayoutTxHex(null);
            setPayoutTxId(null);
        }

        persistNow(null);

        // send updated payment received message if applicable
        if (resendPaymentReceivedMessages && walletExists()) {
            if (!isSeller()) throw new IllegalArgumentException("Only the seller can resend PaymentReceivedMessages after a payout error for " + getClass().getSimpleName() + " " + getId());
            if (!isPaymentReceived()) throw new IllegalStateException("Cannot resend PaymentReceivedMessages after a payout error for " + getClass().getSimpleName() + " " + getId() + " because payment not marked received");
            log.warn("Sending updated PaymentReceivedMessages for {} {} after payout error", getClass().getSimpleName(), getId());
            ((SellerProtocol) getProtocol()).onPaymentReceived(() -> {
                log.info("Done sending updated PaymentReceivedMessages on payout error for {} {}", getClass().getSimpleName(), getId());
            }, (errorMessage) -> {
                log.warn("Error sending updated PaymentReceivedMessages on payout error for {} {}: {}", getClass().getSimpleName(), getId(), errorMessage);
            });
            return true;
        }
        return false;
    }

    private boolean hasDepositTxs(List<MoneroTxWallet> txs) {
        return getMakerDepositTx(txs) != null && (getTakerDepositTx(txs) != null || hasBuyerAsTakerWithoutDeposit());
    }

    private boolean hasPayoutTx(List<MoneroTxWallet> txs) {
        for (MoneroTxWallet tx : txs) {
            if (tx.getHash().equals(getPayoutTxId())) return true;
        }
        return false;
    }

    private static boolean isUnlocked(MoneroTx tx) {
        if (tx == null) return false;
        if (tx.getNumConfirmations() == null || tx.getNumConfirmations() < XmrWalletService.NUM_BLOCKS_UNLOCK) return false;
        return true;
    }

    private boolean hasUnlockedTx() {
        return isUnlocked(getMaker().getDepositTx()) || isUnlocked(getTaker().getDepositTx());
    }

    private MoneroTxWallet getMakerDepositTx(List<MoneroTxWallet> txs) {
        return getValidDepositTx(txs, getMaker());
    }

    private MoneroTxWallet getTakerDepositTx(List<MoneroTxWallet> txs) {
        return getValidDepositTx(txs, getTaker());
    }

    private MoneroTxWallet getBuyerDepositTx(List<MoneroTxWallet> txs) {
        return getValidDepositTx(txs, getBuyer());
    }

    private MoneroTxWallet getSellerDepositTx(List<MoneroTxWallet> txs) {
        return getValidDepositTx(txs, getSeller());
    }

    private MoneroTxWallet getValidDepositTx(List<MoneroTxWallet> txs, TradePeer peer) {
        for (MoneroTxWallet tx : txs) {
            if (tx.getHash().equals(peer.getDepositTxHash()) && !Boolean.TRUE.equals(tx.isFailed())) {
                return tx;
            }
        }
        return null;
    }

    private static boolean isSeen(MoneroTx tx) {
        if (tx == null) return false;
        if (Boolean.TRUE.equals(tx.isFailed())) return false;
        if (!Boolean.TRUE.equals(tx.inTxPool()) && !Boolean.TRUE.equals(tx.isConfirmed())) return false;
        return true;
    }

    private State getDepositsState(MoneroTxWallet makerDepositTx, MoneroTxWallet takerDepositTx) {
        if (makerDepositTx == null || (!hasBuyerAsTakerWithoutDeposit() && takerDepositTx == null)) return State.ARBITRATOR_PUBLISHED_DEPOSIT_TXS;
        if (makerDepositTx.isFailed() || (!hasBuyerAsTakerWithoutDeposit() && takerDepositTx.isFailed())) return State.ARBITRATOR_PUBLISHED_DEPOSIT_TXS;
        if (makerDepositTx.getNumConfirmations() == null || (!hasBuyerAsTakerWithoutDeposit() && takerDepositTx.getNumConfirmations() == null)) return State.ARBITRATOR_PUBLISHED_DEPOSIT_TXS;
        if (makerDepositTx.getNumConfirmations() >= NUM_BLOCKS_DEPOSITS_FINALIZED && (hasBuyerAsTakerWithoutDeposit() || takerDepositTx.getNumConfirmations() >= NUM_BLOCKS_DEPOSITS_FINALIZED)) return State.DEPOSIT_TXS_FINALIZED_IN_BLOCKCHAIN;
        if (makerDepositTx.getNumConfirmations() >= XmrWalletService.NUM_BLOCKS_UNLOCK && (hasBuyerAsTakerWithoutDeposit() || takerDepositTx.getNumConfirmations() >= XmrWalletService.NUM_BLOCKS_UNLOCK)) return State.DEPOSIT_TXS_UNLOCKED_IN_BLOCKCHAIN;
        if (makerDepositTx.isConfirmed() && (hasBuyerAsTakerWithoutDeposit() || takerDepositTx.isConfirmed())) return State.DEPOSIT_TXS_CONFIRMED_IN_BLOCKCHAIN;
        if (isSeen(makerDepositTx) && (hasBuyerAsTakerWithoutDeposit() || isSeen(takerDepositTx))) return State.DEPOSIT_TXS_SEEN_IN_NETWORK;
        return State.ARBITRATOR_PUBLISHED_DEPOSIT_TXS;
    }

    private static PayoutState getPayoutState(MoneroTx payoutTx) {
        if (payoutTx.getHash() == null) return PayoutState.PAYOUT_UNPUBLISHED;
        if (Boolean.TRUE.equals(payoutTx.isFailed())) return PayoutState.PAYOUT_UNPUBLISHED;
        if (payoutTx.getNumConfirmations() != null) {
            if (payoutTx.getNumConfirmations() >= NUM_BLOCKS_PAYOUT_FINALIZED) return PayoutState.PAYOUT_FINALIZED;
            if (payoutTx.getNumConfirmations() >= XmrWalletService.NUM_BLOCKS_UNLOCK) return PayoutState.PAYOUT_UNLOCKED;
        }
        if (payoutTx.isConfirmed()) return PayoutState.PAYOUT_CONFIRMED;
        if (Boolean.TRUE.equals(payoutTx.isRelayed()) || Boolean.TRUE.equals(payoutTx.inTxPool())) return PayoutState.PAYOUT_PUBLISHED;
        return PayoutState.PAYOUT_UNPUBLISHED;
    }

    // TODO: wallet is sometimes missing balance or deposits, due to reorgs, specific daemon connections, not saving?
    public void recoverIfMissingWalletData() {
        synchronized (walletLock) {
            if (isWalletMissingData()) {
                log.warn("Wallet is missing data for {} {}, attempting to recover", getClass().getSimpleName(), getShortId());

                // force restart wallet
                forceRestartTradeWallet();

                // skip if payout published in the meantime
                if (isPayoutPublished()) return;

                // rescan blockchain
                rescanBlockchain();

                // must import multisig hex after rescan
                log.warn("Importing multisig hex after rescanning blockchain for {} {}", getClass().getSimpleName(), getShortId());
                importMultisigHex();

                // poll wallet
                doPollWallet();

                // check again if missing data
                if (isWalletMissingData()) throw new IllegalStateException("Wallet is still missing data after attempting recovery for " + getClass().getSimpleName() + " " + getShortId());
            }
        }
    }

    public void rescanBlockchain() {
        synchronized (walletLock) {
            synchronized (HavenoUtils.getDaemonLock()) {
                if (getWallet() == null) throw new IllegalStateException("Cannot rescan blockchain because trade wallet doesn't exist for " + getClass().getSimpleName() + ", " + getId());
                if (getWallet().getDaemonConnection() == null) throw new RuntimeException("Cannot rescan blockchain because trade wallet is not connected to a Monero daemon for " + getClass().getSimpleName() + ", " + getId());
                Long timeout = null;
                try {

                    // extend rpc timeout for rescan
                    if (wallet instanceof MoneroWalletRpc) {
                        timeout = ((MoneroWalletRpc) wallet).getRpcConnection().getTimeout();
                        ((MoneroWalletRpc) wallet).getRpcConnection().setTimeout(EXTENDED_RPC_TIMEOUT);
                    }

                    // rescan blockchain
                    log.warn("Rescanning blockchain for {} {}", getClass().getSimpleName(), getShortId());
                    wallet.rescanBlockchain();
                } catch (Exception e) {
                    log.warn("Error rescanning blockchain for {} {}, errorMessage={}", getClass().getSimpleName(), getShortId(), e.getMessage());
                    if (HavenoUtils.isUnresponsive(e)) forceRestartTradeWallet(); // wallet can be stuck a while
                    throw e;
                } finally {

                    // restore rpc timeout
                    if (wallet instanceof MoneroWalletRpc) {
                        ((MoneroWalletRpc) wallet).getRpcConnection().setTimeout(timeout);
                    }
                }
            }
        }
    }

    public void rescanSpent(boolean skipLog) {
        synchronized (walletLock) {
            if (getWallet() == null) throw new IllegalStateException("Cannot rescan spent outputs because trade wallet doesn't exist for " + getClass().getSimpleName() + ", " + getId());
            if (getWallet().getDaemonConnection() == null) throw new RuntimeException("Cannot rescan spent outputs because trade wallet is not connected to a Monero daemon for " + getClass().getSimpleName() + ", " + getId());
            Long timeout = null;
            try {

                // extend rpc timeout for rescan
                if (wallet instanceof MoneroWalletRpc) {
                    timeout = ((MoneroWalletRpc) wallet).getRpcConnection().getTimeout();
                    ((MoneroWalletRpc) wallet).getRpcConnection().setTimeout(EXTENDED_RPC_TIMEOUT);
                }

                // rescan spent outputs
                if (!skipLog) log.info("Rescanning spent outputs for {} {}", getClass().getSimpleName(), getShortId());
                wallet.rescanSpent();
                if (!skipLog) log.info("Done rescanning spent outputs for {} {}", getClass().getSimpleName(), getShortId());
                saveWalletIfElapsedTime();
            } catch (Exception e) {
                log.warn("Error rescanning spent outputs for {} {}, errorMessage={}", getClass().getSimpleName(), getShortId(), e.getMessage());
                if (HavenoUtils.isUnresponsive(e)) forceRestartTradeWallet(); // wallet can be stuck a while
                throw e;
            } finally {

                // restore rpc timeout
                if (wallet instanceof MoneroWalletRpc) {
                    ((MoneroWalletRpc) wallet).getRpcConnection().setTimeout(timeout);
                }
            }
        }
    }

    private boolean isWalletMissingData() {
        synchronized (walletLock) {
            if (getWallet() == null) throw new IllegalStateException("Cannot check for missing wallet data for trade wallet because it doesn't exist for " + getClass().getSimpleName() + " " + getId());
            if (!isDepositsUnlocked() || isPayoutPublished()) return false;
            if (getMakerDepositTx() == null) {
                log.warn("Missing maker deposit tx for {} {}", getClass().getSimpleName(), getId());
                return true;
            }
            if (getTakerDepositTx() == null && !hasBuyerAsTakerWithoutDeposit()) {
                log.warn("Missing taker deposit tx for {} {}", getClass().getSimpleName(), getId());
                return true;
            }
            if (wallet.getBalance().equals(BigInteger.ZERO)) {
                doPollWallet(); // poll once more to be sure
                if (isPayoutPublished()) return false; // payout can become published while checking balance
                log.warn("Wallet balance is zero for {} {}", getClass().getSimpleName(), getId());
                return true;
            }
            return false;
        }
    }

    private void forceRestartTradeWallet() {
        if (isShutDownStarted || restartInProgress) return;
        log.warn("Force restarting trade wallet for {} {}", getClass().getSimpleName(), getId());
        restartInProgress = true;
        stopPolling();
        forceCloseWallet();
        if (!isShutDownStarted) wallet = getWallet();
        restartInProgress = false;
        ThreadUtils.execute(() -> maybeInitSyncing(), getId());
    }

    private void setStateDepositsSeen() {
        if (getState().ordinal() < State.DEPOSIT_TXS_SEEN_IN_NETWORK.ordinal()) setState(State.DEPOSIT_TXS_SEEN_IN_NETWORK);
    }

    private void setStateDepositsConfirmed() {
        if (!isDepositsConfirmed()) setState(State.DEPOSIT_TXS_CONFIRMED_IN_BLOCKCHAIN);
    }

    private void setStateDepositsUnlocked() {
        if (!isDepositsUnlocked()) setState(State.DEPOSIT_TXS_UNLOCKED_IN_BLOCKCHAIN);
    }

    private void setStateDepositsFinalized() {
        if (!isPaymentSent() && !isDepositsFinalized()) setStateIfValidTransitionTo(State.DEPOSIT_TXS_FINALIZED_IN_BLOCKCHAIN); // do not revert state if payment already sent
        try {
            maybeUpdateTradePeriod();
        } catch (Exception e) {
            log.warn("Error updating trade period after deposits finalized for {} {}: {}", getClass().getSimpleName(), getId(), e.getMessage());
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

    private void setPayoutStateFinalized() {
        if (!isPayoutFinalized()) setPayoutState(PayoutState.PAYOUT_FINALIZED);
    }

    private Trade getTrade() {
        return this;
    }

    /**
     * Listen to block notifications from the main wallet in order to sync
     * idling trade wallets if necessary.
     */
    private class IdleSyncer extends MoneroWalletListener {

        boolean processing = false;

        @Override
        public void onNewBlock(long height) {
            ThreadUtils.execute(() -> { // allow rapid notifications

                // skip rapid succession blocks
                if (processing) return;
                processing = true;

                // skip if not idling or not waiting for finalization
                if (!isIdling() || (isDepositsFinalized() && (!isPayoutPublished() || isPayoutFinalized())))  {
                    processing = false;
                    return;
                }

                try {

                    // get payout height if unknown
                    if (payoutHeight == null && getPayoutTxId() != null && isPayoutPublished()) {
                        MoneroTx tx = xmrWalletService.getMonerod().getTx(getPayoutTxId());
                        if (tx == null) log.warn("Payout tx not found for {} {}, txId={}", getTrade().getClass().getSimpleName(), getId(), getPayoutTxId());
                        else if (tx.isConfirmed()) payoutHeight = tx.getHeight();
                    }

                    // sync wallet if confirm, unlock, or finalize expected
                    long currentHeight = xmrWalletService.getMonerod().getHeight();
                    if (!isPayoutConfirmed() ||
                            (!isDepositsFinalized() && (currentHeight - getDepositsConfirmedHeight() >= NUM_BLOCKS_DEPOSITS_FINALIZED)) ||
                            (payoutHeight != null && 
                            ((!isPayoutUnlocked() && currentHeight >= payoutHeight + XmrWalletService.NUM_BLOCKS_UNLOCK) ||
                            (!isPayoutFinalized() && currentHeight >= payoutHeight + NUM_BLOCKS_PAYOUT_FINALIZED)))) {
                        log.info("Syncing idle trade wallet for {} {}", getTrade().getClass().getSimpleName(), getId());
                        syncAndPollWallet();
                    }
                    processing = false;
                } catch (Exception e) {
                    processing = false;
                    if (!isInitialized || isShutDownStarted) return;
                    if (isWalletConnectedToDaemon()) {
                        log.warn("Error polling idle trade for {} {}: {}. Monerod={}\n", getClass().getSimpleName(), getId(), e.getMessage(), getXmrWalletService().getXmrConnectionService().getConnection(), e);
                    };
                }
            }, getId());
        }
    }

    private void onDepositRequested() {
        if (!isArbitrator()) startPolling(); // peers start polling after deposits requested
    }

    private void onDepositsPublished() {

        // arbitrator starts polling after deposits published
        if (isArbitrator()) {
            startPolling();
            return;
        }

        // close open offer or reset address entries
        if (this instanceof MakerTrade) {
            processModel.getOpenOfferManager().closeSpentOffer(getOffer());

            // TODO: use language translation
            // TODO: this will send notification if deposits are reverted to published
            HavenoUtils.notificationService.sendTradeNotification(this, Phase.DEPOSITS_PUBLISHED, "Offer Taken", "Your offer " + offer.getId() + " has been accepted"); 
        } else {
            getXmrWalletService().resetAddressEntriesForOpenOffer(getId());
        }

        // freeze outputs until spent
        ThreadUtils.submitToPool(() -> xmrWalletService.freezeOutputs(getSelf().getReserveTxKeyImages()));
    }

    private void onDepositsConfirmed() {
        HavenoUtils.notificationService.sendTradeNotification(this, Phase.DEPOSITS_CONFIRMED, "Trade Deposits Confirmed", "The deposit transactions have confirmed");
    }

    private void onDepositsUnlocked() {
        HavenoUtils.notificationService.sendTradeNotification(this, Phase.DEPOSITS_UNLOCKED, "Trade Deposits Unlocked", "The deposit transactions have unlocked");
    }

    private void onDepositsFinalized() {
        HavenoUtils.notificationService.sendTradeNotification(this, Phase.DEPOSITS_FINALIZED, "Trade Deposits Finalized", "The deposit transactions have finalized");
    }

    private void onPaymentSent() {
        HavenoUtils.notificationService.sendTradeNotification(this, Phase.PAYMENT_SENT, "Payment Sent", "The buyer has sent the payment");
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public Message toProtoMessage() {
        protobuf.Trade.Builder builder = protobuf.Trade.newBuilder()
                .setOffer(offer.toProtoMessage())
                .setTakeOfferDate(takeOfferDate)
                .setProcessModel(processModel.toProtoMessage())
                .setAmount(amount)
                .setPrice(price)
                .setState(Trade.State.toProtoMessage(state))
                .setPayoutState(Trade.PayoutState.toProtoMessage(payoutState))
                .setDisputeState(Trade.DisputeState.toProtoMessage(disputeState))
                .setPeriodState(Trade.TradePeriodState.toProtoMessage(periodState))
                .addAllChatMessage(getChatMessages().stream()
                        .map(msg -> msg.toProtoNetworkEnvelope().getChatMessage())
                        .collect(Collectors.toList()))
                .setLockTime(lockTime)
                .setStartTime(startTime)
                .setUid(uid)
                .setIsCompleted(isCompleted);

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
        Optional.ofNullable(challenge).ifPresent(e -> builder.setChallenge(challenge));
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
        trade.setCompleted(proto.getIsCompleted());

        trade.chatMessages.addAll(proto.getChatMessageList().stream()
                .map(ChatMessage::fromPayloadProto)
                .collect(Collectors.toList()));

        return trade;
    }

    @Override
    public String toString() {
        return "Trade{" +
                "\n     offer=" + offer +
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
                ",\n     isCompleted=" + isCompleted +
                ",\n     challenge='" + challenge + '\'' +
                "\n}";
    }
}
