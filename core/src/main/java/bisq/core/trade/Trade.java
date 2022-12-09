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

package bisq.core.trade;

import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.locale.CurrencyUtil;
import bisq.core.monetary.Price;
import bisq.core.monetary.Volume;
import bisq.core.offer.Offer;
import bisq.core.offer.OfferDirection;
import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.payment.payload.PaymentMethod;
import bisq.core.proto.CoreProtoResolver;
import bisq.core.proto.network.CoreNetworkProtoResolver;
import bisq.core.support.dispute.mediation.MediationResultState;
import bisq.core.support.dispute.refund.RefundResultState;
import bisq.core.support.messages.ChatMessage;
import bisq.core.trade.messages.TradeMessage;
import bisq.core.trade.protocol.ProcessModel;
import bisq.core.trade.protocol.ProcessModelServiceProvider;
import bisq.core.trade.protocol.TradeListener;
import bisq.core.trade.protocol.TradeProtocol;
import bisq.core.trade.protocol.TradingPeer;
import bisq.core.trade.txproof.AssetTxProofResult;
import bisq.core.util.ParsingUtils;
import bisq.core.util.VolumeUtil;
import bisq.network.p2p.AckMessage;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.P2PService;
import bisq.common.UserThread;
import bisq.common.crypto.Encryption;
import bisq.common.crypto.PubKeyRing;
import bisq.common.proto.ProtoUtil;
import bisq.common.taskrunner.Model;
import bisq.common.util.Utilities;
import com.google.common.base.Preconditions;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import org.bitcoinj.core.Coin;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import java.math.BigInteger;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;

import static com.google.common.base.Preconditions.checkNotNull;



import monero.common.MoneroError;
import monero.common.MoneroRpcConnection;
import monero.common.TaskLooper;
import monero.daemon.MoneroDaemon;
import monero.daemon.model.MoneroTx;
import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroDestination;
import monero.wallet.model.MoneroMultisigSignResult;
import monero.wallet.model.MoneroOutputWallet;
import monero.wallet.model.MoneroTxConfig;
import monero.wallet.model.MoneroTxQuery;
import monero.wallet.model.MoneroTxSet;
import monero.wallet.model.MoneroTxWallet;
import monero.wallet.model.MoneroWalletListener;

/**
 * Holds all data which are relevant to the trade, but not those which are only needed in the trade process as shared data between tasks. Those data are
 * stored in the task model.
 */
@Slf4j
public abstract class Trade implements Tradable, Model {

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
        DISPUTE_REQUESTED, // TODO: not currently used; can use by subscribing to chat message ack in DisputeManager
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
    @Getter
    private final long txFeeAsLong;
    @Getter
    private final long takerFeeAsLong;

    // Added in 1.5.1
    @Getter
    private final String uid;

    @Setter
    private long takeOfferDate;

    //  Mutable
    @Getter
    @Setter
    private long amountAsLong;
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
    transient final private Coin txFee;
    @Getter
    transient final private Coin takerFee;
    @Getter
    transient final private XmrWalletService xmrWalletService;

    transient final private ObjectProperty<State> stateProperty = new SimpleObjectProperty<>(state);
    transient final private ObjectProperty<Phase> phaseProperty = new SimpleObjectProperty<>(state.phase);
    transient final private ObjectProperty<PayoutState> payoutStateProperty = new SimpleObjectProperty<>(payoutState);
    transient final private ObjectProperty<DisputeState> disputeStateProperty = new SimpleObjectProperty<>(disputeState);
    transient final private ObjectProperty<TradePeriodState> tradePeriodStateProperty = new SimpleObjectProperty<>(periodState);
    transient final private StringProperty errorMessageProperty = new SimpleStringProperty();
    transient private Subscription tradePhaseSubscription;
    transient private Subscription payoutStateSubscription;
    transient private TaskLooper txPollLooper;
    transient private Long walletRefreshPeriod;
    transient private Long syncNormalStartTime;
    private static final long IDLE_SYNC_PERIOD_MS = 3600000; // 1 hour
    public static final long DEFER_PUBLISH_MS = 25000; // 25 seconds
    
    //  Mutable
    @Getter
    transient private boolean isInitialized;

    // Added in v1.2.0
    @Nullable
    transient private Coin tradeAmount;

    transient private ObjectProperty<Coin> tradeAmountProperty;
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
    @Getter
    @Nullable
    private RefundResultState refundResultState = RefundResultState.UNDEFINED_REFUND_RESULT;
    transient final private ObjectProperty<RefundResultState> refundResultStateProperty = new SimpleObjectProperty<>(refundResultState);

    // Added at v1.3.8
    // We use that for the XMR txKey but want to keep it generic to be flexible for other payment methods or assets.
    @Getter
    @Setter
    private String counterCurrencyExtraData;

    // Added at v1.3.8
    // Generic tx proof result. We persist name if AssetTxProofResult enum. Other fields in the enum are not persisted
    // as they are not very relevant as historical data (e.g. number of confirmations)
    @Nullable
    @Getter
    private AssetTxProofResult assetTxProofResult;
    // ObjectProperty with AssetTxProofResult does not notify changeListeners. Probably because AssetTxProofResult is
    // an enum and enum does not support EqualsAndHashCode. Alternatively we could add a addListener and removeListener
    // method and a listener interface, but the IntegerProperty seems to be less boilerplate.
    @Getter
    transient final private IntegerProperty assetTxProofResultUpdateProperty = new SimpleIntegerProperty();

    // Added in XMR integration
    private transient List<TradeListener> tradeListeners; // notified on fully validated trade messages
    transient MoneroWalletListener depositTxListener;
    transient MoneroWalletListener payoutTxListener;
    transient Boolean makerDepositLocked; // null when unknown, true while locked, false when unlocked
    transient Boolean takerDepositLocked;
    @Nullable
    transient private MoneroTxWallet payoutTx;
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
    private Long startTime; // cache

    
    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructors
    ///////////////////////////////////////////////////////////////////////////////////////////

    // maker
    protected Trade(Offer offer,
                    Coin tradeAmount,
                    Coin takerFee, // TODO (woodser): makerFee, takerFee, but not given one during construction
                    long tradePrice,
                    XmrWalletService xmrWalletService,
                    ProcessModel processModel,
                    String uid,
                    @Nullable NodeAddress makerNodeAddress,
                    @Nullable NodeAddress takerNodeAddress,
                    @Nullable NodeAddress arbitratorNodeAddress) {
        this.offer = offer;
        this.tradeAmount = tradeAmount;
        this.txFee = Coin.valueOf(0);   // TODO (woodser): remove this field
        this.takerFee = takerFee;
        this.price = tradePrice;
        this.xmrWalletService = xmrWalletService;
        this.processModel = processModel;
        this.uid = uid;

        this.txFeeAsLong = txFee.value;
        this.takerFeeAsLong = takerFee.value;
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
                    Coin tradeAmount,
                    Coin txFee,
                    Coin takerFee,
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
                    Coin tradeAmount,
                    Coin txFee,
                    Coin takerFee,
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
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void initialize(ProcessModelServiceProvider serviceProvider) {
        serviceProvider.getArbitratorManager().getDisputeAgentByNodeAddress(getArbitratorNodeAddress()).ifPresent(arbitrator -> {
            getArbitrator().setPubKeyRing(arbitrator.getPubKeyRing());
        });

        // listen to daemon connection
        xmrWalletService.getConnectionsService().addListener(newConnection -> setDaemonConnection(newConnection));

        // check if done
        if (isPayoutUnlocked()) return;

        // handle trade state events
        tradePhaseSubscription = EasyBind.subscribe(phaseProperty, newValue -> {
            if (!isInitialized) return;
            if (isDepositPublished() && !isPayoutUnlocked()) updateWalletRefreshPeriod();
            if (isCompleted()) {
                UserThread.execute(() -> {
                    if (tradePhaseSubscription != null) {
                        tradePhaseSubscription.unsubscribe();
                        tradePhaseSubscription = null;
                    }
                });
            }
        });

        // handle payout state events
        payoutStateSubscription = EasyBind.subscribe(payoutStateProperty, newValue -> {
            if (!isInitialized) return;
            if (isPayoutPublished()) updateWalletRefreshPeriod();

            // cleanup when payout published
            if (newValue == Trade.PayoutState.PAYOUT_PUBLISHED) {
                log.info("Payout published for {} {}", getClass().getSimpleName(), getId());

                // complete disputed trade
                if (getDisputeState().isArbitrated() && !getDisputeState().isClosed()) processModel.getTradeManager().closeDisputedTrade(getId(), Trade.DisputeState.DISPUTE_CLOSED);

                // complete arbitrator trade
                if (isArbitrator() && !isCompleted()) processModel.getTradeManager().onTradeCompleted(this);
                processModel.getXmrWalletService().resetAddressEntriesForPendingTrade(getId());
            }

            // cleanup when payout unlocks
            if (newValue == Trade.PayoutState.PAYOUT_UNLOCKED) {
                log.info("Payout unlocked for {} {}, deleting multisig wallet", getClass().getSimpleName(), getId()); // TODO: retain backup for some time?
                deleteWallet();
                if (txPollLooper != null) {
                    txPollLooper.stop();
                    txPollLooper = null;
                }
                UserThread.execute(() -> {
                    if (payoutStateSubscription != null) {
                        payoutStateSubscription.unsubscribe();
                        payoutStateSubscription = null;
                    }
                });
            }
        });

        isInitialized = true;

        // start listening to trade wallet
        if (isDepositPublished()) {
            updateSyncing();

            // allow state notifications to process before returning
            CountDownLatch latch = new CountDownLatch(1);
            UserThread.execute(() -> latch.countDown());
            HavenoUtils.awaitLatch(latch);
        }
    }

    public TradeProtocol getProtocol() {
        return processModel.getTradeManager().getTradeProtocol(this);
    }

    public void setMyNodeAddress() {
        getSelf().setNodeAddress(P2PService.getMyNodeAddress());
    }

    public NodeAddress getTradingPeerNodeAddress() {
        return getTradingPeer() == null ? null : getTradingPeer().getNodeAddress();
    }

    public NodeAddress getArbitratorNodeAddress() {
        return getArbitrator() == null ? null : getArbitrator().getNodeAddress();
    }

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
                checkNotNull(getAmount()).value,
                getPrice().getValue(),
                (isBuyerMakerAndSellerTaker ? getMaker() : getTaker()).getNodeAddress(), // buyer node address // TODO (woodser): use maker and taker node address instead of buyer and seller node address for consistency
                (isBuyerMakerAndSellerTaker ? getTaker() : getMaker()).getNodeAddress(), // seller node address
                getArbitrator().getNodeAddress(),
                isBuyerMakerAndSellerTaker,
                this instanceof MakerTrade ? processModel.getAccountId() : getMaker().getAccountId(), // maker account id
                this instanceof TakerTrade ? processModel.getAccountId() : getTaker().getAccountId(), // taker account id
                checkNotNull(this instanceof MakerTrade ? processModel.getPaymentAccountPayload(this).getPaymentMethodId() : getOffer().getOfferPayload().getPaymentMethodId()), // maker payment method id
                checkNotNull(this instanceof TakerTrade ? processModel.getPaymentAccountPayload(this).getPaymentMethodId() : getTaker().getPaymentMethodId()), // taker payment method id
                this instanceof MakerTrade ? processModel.getPaymentAccountPayload(this).getHash() : getMaker().getPaymentAccountPayloadHash(), // maker payment account payload hash
                this instanceof TakerTrade ? processModel.getPaymentAccountPayload(this).getHash() : getTaker().getPaymentAccountPayloadHash(), // maker payment account payload hash
                getMaker().getPubKeyRing(),
                getTaker().getPubKeyRing(),
                this instanceof MakerTrade ? xmrWalletService.getAddressEntry(getId(), XmrAddressEntry.Context.TRADE_PAYOUT).get().getAddressString() : getMaker().getPayoutAddressString(), // maker payout address
                this instanceof TakerTrade ? xmrWalletService.getAddressEntry(getId(), XmrAddressEntry.Context.TRADE_PAYOUT).get().getAddressString() : getTaker().getPayoutAddressString(), // taker payout address
                getLockTime(),
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

        // gather info
        XmrWalletService walletService = processModel.getProvider().getXmrWalletService();
        MoneroWallet multisigWallet = getWallet();
        if (multisigWallet.isMultisigImportNeeded()) throw new RuntimeException("Cannot create payout tx because multisig import is needed");
        String sellerPayoutAddress = this.getSeller().getPayoutAddressString();
        String buyerPayoutAddress = this.getBuyer().getPayoutAddressString();
        Preconditions.checkNotNull(sellerPayoutAddress, "Seller payout address must not be null");
        Preconditions.checkNotNull(buyerPayoutAddress, "Buyer payout address must not be null");
        BigInteger sellerDepositAmount = multisigWallet.getTx(this.getSeller().getDepositTxHash()).getIncomingAmount();
        BigInteger buyerDepositAmount = multisigWallet.getTx(this.getBuyer().getDepositTxHash()).getIncomingAmount();
        BigInteger tradeAmount = HavenoUtils.coinToAtomicUnits(this.getAmount());
        BigInteger buyerPayoutAmount = buyerDepositAmount.add(tradeAmount);
        BigInteger sellerPayoutAmount = sellerDepositAmount.subtract(tradeAmount);

        // create transaction to get fee estimate
        if (multisigWallet.isMultisigImportNeeded()) throw new RuntimeException("Cannot create payout tx because multisig import is needed");
        MoneroTxWallet feeEstimateTx = multisigWallet.createTx(new MoneroTxConfig()
                .setAccountIndex(0)
                .addDestination(buyerPayoutAddress, buyerPayoutAmount.multiply(BigInteger.valueOf(9)).divide(BigInteger.valueOf(10))) // reduce payment amount to compute fee of similar tx
                .addDestination(sellerPayoutAddress, sellerPayoutAmount.multiply(BigInteger.valueOf(9)).divide(BigInteger.valueOf(10)))
                .setRelay(false)
        );

        // attempt to create payout tx by increasing estimated fee until successful
        MoneroTxWallet payoutTx = null;
        int numAttempts = 0;
        while (payoutTx == null && numAttempts < 50) {
          BigInteger feeEstimate = feeEstimateTx.getFee().add(feeEstimateTx.getFee().multiply(BigInteger.valueOf(numAttempts)).divide(BigInteger.valueOf(10))); // add 1/10 of fee until tx is successful
          try {
            numAttempts++;
            payoutTx = multisigWallet.createTx(new MoneroTxConfig()
                    .setAccountIndex(0)
                    .addDestination(buyerPayoutAddress, buyerPayoutAmount.subtract(feeEstimate.divide(BigInteger.valueOf(2)))) // split fee subtracted from each payout amount
                    .addDestination(sellerPayoutAddress, sellerPayoutAmount.subtract(feeEstimate.divide(BigInteger.valueOf(2))))
                    .setRelay(false));
          } catch (MoneroError e) {
            // exception expected
          }
        }
        if (payoutTx == null) throw new RuntimeException("Failed to generate payout tx after " + numAttempts + " attempts");
        log.info("Payout transaction generated on attempt {}", numAttempts);

        // save updated multisig hex
        getSelf().setUpdatedMultisigHex(multisigWallet.exportMultisigHex());
        return payoutTx;
    }

    /**
     * Verify a payout tx.
     *
     * @param payoutTxHex is the payout tx hex to verify
     * @param sign signs the payout tx if true
     * @param publish publishes the signed payout tx if true
     */
    public void verifyPayoutTx(String payoutTxHex, boolean sign, boolean publish) {
        log.info("Verifying payout tx");

        // gather relevant info
        XmrWalletService walletService = processModel.getProvider().getXmrWalletService();
        MoneroWallet multisigWallet = walletService.getMultisigWallet(getId());
        Contract contract = getContract();
        BigInteger sellerDepositAmount = multisigWallet.getTx(getSeller().getDepositTxHash()).getIncomingAmount();   // TODO (woodser): redundancy of processModel.getPreparedDepositTxId() vs this.getDepositTxId() necessary or avoidable?
        BigInteger buyerDepositAmount = multisigWallet.getTx(getBuyer().getDepositTxHash()).getIncomingAmount();
        BigInteger tradeAmount = HavenoUtils.coinToAtomicUnits(getAmount());

        // describe payout tx
        MoneroTxSet describedTxSet = multisigWallet.describeTxSet(new MoneroTxSet().setMultisigTxHex(payoutTxHex));
        if (describedTxSet.getTxs() == null || describedTxSet.getTxs().size() != 1) throw new RuntimeException("Bad payout tx"); // TODO (woodser): test nack
        MoneroTxWallet payoutTx = describedTxSet.getTxs().get(0);

        // verify payout tx has exactly 2 destinations
        if (payoutTx.getOutgoingTransfer() == null || payoutTx.getOutgoingTransfer().getDestinations() == null || payoutTx.getOutgoingTransfer().getDestinations().size() != 2) throw new RuntimeException("Payout tx does not have exactly two destinations");

        // get buyer and seller destinations (order not preserved)
        boolean buyerFirst = payoutTx.getOutgoingTransfer().getDestinations().get(0).getAddress().equals(contract.getBuyerPayoutAddressString());
        MoneroDestination buyerPayoutDestination = payoutTx.getOutgoingTransfer().getDestinations().get(buyerFirst ? 0 : 1);
        MoneroDestination sellerPayoutDestination = payoutTx.getOutgoingTransfer().getDestinations().get(buyerFirst ? 1 : 0);

        // verify payout addresses
        if (!buyerPayoutDestination.getAddress().equals(contract.getBuyerPayoutAddressString())) throw new RuntimeException("Buyer payout address does not match contract");
        if (!sellerPayoutDestination.getAddress().equals(contract.getSellerPayoutAddressString())) throw new RuntimeException("Seller payout address does not match contract");

        // verify change address is multisig's primary address
        if (!payoutTx.getChangeAmount().equals(BigInteger.ZERO) && !payoutTx.getChangeAddress().equals(multisigWallet.getPrimaryAddress())) throw new RuntimeException("Change address is not multisig wallet's primary address");

        // verify sum of outputs = destination amounts + change amount
        if (!payoutTx.getOutputSum().equals(buyerPayoutDestination.getAmount().add(sellerPayoutDestination.getAmount()).add(payoutTx.getChangeAmount()))) throw new RuntimeException("Sum of outputs != destination amounts + change amount");

        // verify buyer destination amount is deposit amount + this amount - 1/2 tx costs
        BigInteger txCost = payoutTx.getFee().add(payoutTx.getChangeAmount());
        BigInteger expectedBuyerPayout = buyerDepositAmount.add(tradeAmount).subtract(txCost.divide(BigInteger.valueOf(2)));
        if (!buyerPayoutDestination.getAmount().equals(expectedBuyerPayout)) throw new RuntimeException("Buyer destination amount is not deposit amount + trade amount - 1/2 tx costs, " + buyerPayoutDestination.getAmount() + " vs " + expectedBuyerPayout);

        // verify seller destination amount is deposit amount - this amount - 1/2 tx costs
        BigInteger expectedSellerPayout = sellerDepositAmount.subtract(tradeAmount).subtract(txCost.divide(BigInteger.valueOf(2)));
        if (!sellerPayoutDestination.getAmount().equals(expectedSellerPayout)) throw new RuntimeException("Seller destination amount is not deposit amount - trade amount - 1/2 tx costs, " + sellerPayoutDestination.getAmount() + " vs " + expectedSellerPayout);

        // TODO (woodser): verify fee is reasonable (e.g. within 2x of fee estimate tx)

        // sign payout tx
        if (sign) {
            MoneroMultisigSignResult result = multisigWallet.signMultisigTxHex(payoutTxHex);
            if (result.getSignedMultisigTxHex() == null) throw new RuntimeException("Error signing payout tx");
            payoutTxHex = result.getSignedMultisigTxHex();
        }

        // update trade state
        setPayoutTx(describedTxSet.getTxs().get(0));
        setPayoutTxHex(payoutTxHex);

        // submit payout tx
        if (publish) {
            multisigWallet.submitMultisigTxHex(payoutTxHex);
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
            getTradingPeer().setPaymentAccountKey(paymentAccountKey);
            SecretKey sk = Encryption.getSecretKeyFromBytes(getTradingPeer().getPaymentAccountKey());
            byte[] decryptedPaymentAccountPayload = Encryption.decrypt(getTradingPeer().getEncryptedPaymentAccountPayload(), sk);
            CoreNetworkProtoResolver resolver = new CoreNetworkProtoResolver(Clock.systemDefaultZone()); // TODO: reuse resolver from elsewhere?
            PaymentAccountPayload paymentAccountPayload = resolver.fromProto(protobuf.PaymentAccountPayload.parseFrom(decryptedPaymentAccountPayload));

            // verify hash of payment account payload
            byte[] peerPaymentAccountPayloadHash = this instanceof MakerTrade ? getContract().getTakerPaymentAccountPayloadHash() : getContract().getMakerPaymentAccountPayloadHash();
            if (!Arrays.equals(paymentAccountPayload.getHash(), peerPaymentAccountPayloadHash)) throw new RuntimeException("Hash of peer's payment account payload does not match contract");

            // set payment account payload
            getTradingPeer().setPaymentAccountPayload(paymentAccountPayload);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nullable
    public MoneroTx getTakerDepositTx() {
        String depositTxHash = getProcessModel().getTaker().getDepositTxHash();
        try {
            if (getTaker().getDepositTx() == null) getTaker().setDepositTx(depositTxHash == null ? null : getXmrWalletService().getTxWithCache(depositTxHash));
            return getTaker().getDepositTx();
        } catch (MoneroError e) {
            log.error("Wallet is missing taker deposit tx " + depositTxHash);
            return null;
        }
    }

    @Nullable
    public MoneroTx getMakerDepositTx() {
        String depositTxHash = getProcessModel().getMaker().getDepositTxHash();
        try {
            if (getMaker().getDepositTx() == null) getMaker().setDepositTx(depositTxHash == null ? null : getXmrWalletService().getTxWithCache(depositTxHash));
            return getMaker().getDepositTx();
        } catch (MoneroError e) {
            log.error("Wallet is missing maker deposit tx " + depositTxHash);
            return null;
        }
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
        long normalPayoutAmount = offer.getSellerSecurityDeposit().value;
        return payoutAmountFromMediation < normalPayoutAmount;
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

    public MoneroWallet getWallet() {
        return xmrWalletService.multisigWalletExists(getId()) ? xmrWalletService.getMultisigWallet(getId()) : null;
    }

    public void syncWallet() {
        if (getWallet() == null) {
            log.warn("Cannot sync multisig wallet because it doesn't exist for {}, {}", getClass().getSimpleName(), getId());
            return;
        }
        if (getWallet().getDaemonConnection() == null) {
            log.warn("Cannot sync multisig wallet because it's not connected to a Monero daemon for {}, {}", getClass().getSimpleName(), getId());
            return;
        }
        log.info("Syncing wallet for {} {}", getClass().getSimpleName(), getId());
        getWallet().sync();
        pollWallet();
        log.info("Done syncing wallet for {} {}", getClass().getSimpleName(), getId());
        updateWalletRefreshPeriod();
    }

    public void syncWalletNormallyForMs(long syncNormalDuration) {
        syncNormalStartTime = System.currentTimeMillis();
        setWalletRefreshPeriod(xmrWalletService.getConnectionsService().getDefaultRefreshPeriodMs());
        UserThread.runAfter(() -> {
            if (isInitialized && System.currentTimeMillis() >= syncNormalStartTime + syncNormalDuration) updateWalletRefreshPeriod();
        }, syncNormalDuration);
    }

    public void saveWallet() {
        xmrWalletService.saveMultisigWallet(getId());
    }

    public void deleteWallet() {
        if (xmrWalletService.multisigWalletExists(getId())) xmrWalletService.deleteMultisigWallet(getId());
        else log.warn("Multisig wallet to delete for trade {} does not exist", getId());
    }

    public void shutDown() {
        isInitialized = false;
        if (txPollLooper != null) {
            txPollLooper.stop();
            txPollLooper = null;
        }
        if (tradePhaseSubscription != null) tradePhaseSubscription.unsubscribe();
        if (payoutStateSubscription != null) payoutStateSubscription.unsubscribe();
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

    public abstract Coin getPayoutAmount();

    public abstract boolean confirmPermitted();


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Setters
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void setStateIfValidTransitionTo(State newState) {
        if (state.isValidTransitionTo(newState)) {
            setState(newState);
        } else {
            log.warn("State change is not getting applied because it would cause an invalid transition. " +
                    "Trade state={}, intended state={}", state, newState);
        }
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

    public void setStateIfProgress(State state) {
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

    public void setDisputeStateIfProgress(DisputeState disputeState) {
        if (disputeState.ordinal() > getDisputeState().ordinal()) setDisputeState(disputeState);
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

    public void setAmount(Coin tradeAmount) {
        this.tradeAmount = tradeAmount;
        amountAsLong = tradeAmount.value;
        getAmountProperty().set(tradeAmount);
        getVolumeProperty().set(getVolume());
    }

    public void setPayoutTx(MoneroTxWallet payoutTx) {
        this.payoutTx = payoutTx;
        payoutTxId = payoutTx.getHash();
        payoutTxKey = payoutTx.getKey();
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
        errorMessageProperty.set(errorMessage);
    }

    public void setAssetTxProofResult(@Nullable AssetTxProofResult assetTxProofResult) {
        this.assetTxProofResult = assetTxProofResult;
        assetTxProofResultUpdateProperty.set(assetTxProofResultUpdateProperty.get() + 1);
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

    public TradingPeer getSelf() {
        if (this instanceof MakerTrade) return processModel.getMaker();
        if (this instanceof TakerTrade) return processModel.getTaker();
        if (this instanceof ArbitratorTrade) return processModel.getArbitrator();
        throw new RuntimeException("Trade is not maker, taker, or arbitrator");
    }

    public TradingPeer getArbitrator() {
        return processModel.getArbitrator();
    }

    public TradingPeer getMaker() {
        return processModel.getMaker();
    }

    public TradingPeer getTaker() {
        return processModel.getTaker();
    }

    public TradingPeer getBuyer() {
        return offer.getDirection() == OfferDirection.BUY ? processModel.getMaker() : processModel.getTaker();
    }

    public TradingPeer getSeller() {
        return offer.getDirection() == OfferDirection.BUY ? processModel.getTaker() : processModel.getMaker();
    }

    // get the taker if maker, maker if taker, null if arbitrator
    public TradingPeer getTradingPeer() {
        if (this instanceof MakerTrade) return processModel.getTaker();
        else if (this instanceof TakerTrade) return processModel.getMaker();
        else if (this instanceof ArbitratorTrade) return null;
        else throw new RuntimeException("Unknown trade type: " + getClass().getName());
    }

    // TODO (woodser): this naming convention is confusing
    public TradingPeer getTradingPeer(NodeAddress address) {
        if (address.equals(getMaker().getNodeAddress())) return processModel.getMaker();
        if (address.equals(getTaker().getNodeAddress())) return processModel.getTaker();
        if (address.equals(getArbitrator().getNodeAddress())) return processModel.getArbitrator();
        return null;
    }

    public TradingPeer getTradingPeer(PubKeyRing pubKeyRing) {
        if (getMaker() != null && getMaker().getPubKeyRing().equals(pubKeyRing)) return getMaker();
        if (getTaker() != null && getTaker().getPubKeyRing().equals(pubKeyRing)) return getTaker();
        if (getArbitrator() != null && getArbitrator().getPubKeyRing().equals(pubKeyRing)) return getArbitrator();
        return null;
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
                if (offer != null) {
                    if (offer.getPaymentMethod().getId().equals(PaymentMethod.HAL_CASH_ID))
                        volumeByAmount = VolumeUtil.getAdjustedVolumeForHalCash(volumeByAmount);
                    else if (CurrencyUtil.isFiatCurrency(offer.getCurrencyCode()))
                        volumeByAmount = VolumeUtil.getRoundedFiatVolume(volumeByAmount);
                }
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
        if (startTime != null) return startTime;
        long now = System.currentTimeMillis();
        if (isDepositConfirmed() && getTakeOfferDate() != null) {
            if (isDepositUnlocked()) {
                final long tradeTime = getTakeOfferDate().getTime();
                long maxHeight = Math.max(getMakerDepositTx().getHeight(), getTakerDepositTx().getHeight());
                MoneroDaemon daemonRpc = xmrWalletService.getDaemon();
                long blockTime = daemonRpc.getBlockByHeight(maxHeight).getTimestamp();

//            if (depositTx.getConfidence().getDepthInBlocks() > 0) {
//                final long tradeTime = getTakeOfferDate().getTime();
//                // Use tx.getIncludedInBestChainAt() when available, otherwise use tx.getUpdateTime()
//                long blockTime = depositTx.getIncludedInBestChainAt() != null ? depositTx.getIncludedInBestChainAt().getTime() : depositTx.getUpdateTime().getTime();
                // If block date is in future (Date in Bitcoin blocks can be off by +/- 2 hours) we use our current date.
                // If block date is earlier than our trade date we use our trade date.
                if (blockTime > now)
                    startTime = now;
                else
                    startTime = Math.max(blockTime, tradeTime);

                log.debug("We set the start for the trade period to {}. Trade started at: {}. Block got mined at: {}",
                        new Date(startTime), new Date(tradeTime), new Date(blockTime));
            } else {
                log.debug("depositTx not confirmed yet. We don't start counting remaining trade period yet. makerTxId={}, takerTxId={}", getMaker().getDepositTxHash(), getTaker().getDepositTxHash());
                startTime = now;
            }
        } else {
            startTime = now;
        }
        return startTime;
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

    public boolean isDepositPublished() {
        return getState().getPhase().ordinal() >= Phase.DEPOSITS_PUBLISHED.ordinal();
    }

    public boolean isFundsLockedIn() {
        return isDepositPublished() && !isPayoutPublished();
    }

    public boolean isDepositConfirmed() {
        return getState().getPhase().ordinal() >= Phase.DEPOSITS_CONFIRMED.ordinal();
    }

    public boolean isDepositUnlocked() {
        return getState().getPhase().ordinal() >= Phase.DEPOSITS_UNLOCKED.ordinal();
    }

    public boolean isPaymentSent() {
        return getState().getPhase().ordinal() >= Phase.PAYMENT_SENT.ordinal();
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

    public ReadOnlyObjectProperty<Coin> tradeAmountProperty() {
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

    public Price getPrice() {
        return Price.valueOf(offer.getCurrencyCode(), price);
    }

    @Nullable
    public Coin getAmount() {
        if (tradeAmount == null)
            tradeAmount = Coin.valueOf(amountAsLong);
        return tradeAmount;
    }

    public Coin getMakerFee() {
        return offer.getMakerFee();
    }

    @Nullable
    public MoneroTxWallet getPayoutTx() {
        if (payoutTx == null)
            payoutTx = payoutTxId != null ? xmrWalletService.getWallet().getTx(payoutTxId) : null;
        return payoutTx;
    }

    public boolean hasErrorMessage() {
        return getErrorMessage() != null && !getErrorMessage().isEmpty();
    }

    @Nullable
    public String getErrorMessage() {
        return errorMessageProperty.get();
    }

    public boolean isTxChainInvalid() {
        return offer.getOfferFeePaymentTxId() == null ||
                processModel.getMaker().getDepositTxHash() == null ||
                processModel.getTaker().getDepositTxHash() == null;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    // lazy initialization
    private ObjectProperty<Coin> getAmountProperty() {
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

    private void setDaemonConnection(MoneroRpcConnection connection) {
        if (getWallet() == null) return;
        log.info("Setting daemon connection for trade wallet {}: {}: ", getId() , connection == null ? null : connection.getUri());
        if (getWallet() != null) getWallet().setDaemonConnection(connection);
        updateSyncing();
    }

    private void updateSyncing() {
        if (!isIdling()) syncWallet();
        else {
            long startSyncingInMs = ThreadLocalRandom.current().nextLong(0, getWalletRefreshPeriod()); // random time to start syncing
            UserThread.runAfter(() -> {
                if (isInitialized) syncWallet();
            }, startSyncingInMs / 1000l);
        }
    }

    private void updateWalletRefreshPeriod() {
        setWalletRefreshPeriod(getWalletRefreshPeriod());
    }

    private void setWalletRefreshPeriod(long walletRefreshPeriod) {
        if (this.walletRefreshPeriod != null && this.walletRefreshPeriod == walletRefreshPeriod) return;
        log.info("Setting wallet refresh rate for {} {} to {}", getClass().getSimpleName(), getId(), walletRefreshPeriod);
        this.walletRefreshPeriod = walletRefreshPeriod;
        getWallet().startSyncing(getWalletRefreshPeriod()); // TODO (monero-project): wallet rpc waits until last sync period finishes before starting new sync period
        if (txPollLooper != null) {
            txPollLooper.stop();
            txPollLooper = null;
        }
        startPolling();
    }

    private void startPolling() {
        if (txPollLooper != null) return;
        log.info("Listening for payout tx for {} {}", getClass().getSimpleName(), getId());
        txPollLooper = new TaskLooper(() -> { pollWallet(); });
        txPollLooper.start(walletRefreshPeriod);
    }

    private void pollWallet() {
        try {

            // skip if payout unlocked
            if (isPayoutUnlocked()) return;

            // rescan spent if deposits unlocked
            if (isDepositUnlocked()) getWallet().rescanSpent();

            // get txs with outputs
            List<MoneroTxWallet> txs = getWallet().getTxs(new MoneroTxQuery()
                    .setHashes(Arrays.asList(processModel.getMaker().getDepositTxHash(), processModel.getTaker().getDepositTxHash()))
                    .setIncludeOutputs(true));

            // check deposit txs
            if (!isDepositUnlocked()) {
                if (txs.size() == 2) {
                    setStateDepositsPublished();
                    boolean makerFirst = txs.get(0).getHash().equals(processModel.getMaker().getDepositTxHash());
                    getMaker().setDepositTx(makerFirst ? txs.get(0) : txs.get(1));
                    getTaker().setDepositTx(makerFirst ? txs.get(1) : txs.get(0));

                    // check if deposit txs confirmed
                    if (txs.get(0).isConfirmed() && txs.get(1).isConfirmed()) setStateDepositsConfirmed();
                    if (!txs.get(0).isLocked() && !txs.get(1).isLocked()) setStateDepositsUnlocked();
                }
            }

            // check payout tx
            else {

                // check if deposit txs spent (appears on payout published)
                for (MoneroTxWallet tx : txs) {
                    for (MoneroOutputWallet output : tx.getOutputsWallet()) {
                        if (Boolean.TRUE.equals(output.isSpent()))  {
                            setPayoutStatePublished();
                        }
                    }
                }

                // check for outgoing txs (appears after wallet submits payout tx or on payout confirmed)
                List<MoneroTxWallet> outgoingTxs = getWallet().getTxs(new MoneroTxQuery().setIsOutgoing(true));
                if (!outgoingTxs.isEmpty()) {
                    MoneroTxWallet payoutTx = outgoingTxs.get(0);
                    setPayoutTx(payoutTx);
                    setPayoutStatePublished();
                    if (payoutTx.isConfirmed()) setPayoutStateConfirmed();
                    if (!payoutTx.isLocked()) setPayoutStateUnlocked();
                }
            }
        } catch (Exception e) {
            if (isInitialized) log.warn("Error polling trade wallet {}: {}", getId(), e.getMessage()); // TODO (monero-java): poller.isPolling() and then don't need to use isInitialized here as shutdown flag
        }
    }


    private long getWalletRefreshPeriod() {
        if (isIdling()) return IDLE_SYNC_PERIOD_MS;
        return xmrWalletService.getConnectionsService().getDefaultRefreshPeriodMs();
    }

    private boolean isIdling() {
        return this instanceof ArbitratorTrade && isDepositConfirmed(); // arbitrator idles after deposits confirm
    }

    private void setStateDepositsPublished() {
        if (!isDepositPublished()) setState(State.DEPOSIT_TXS_SEEN_IN_NETWORK);
    }

    private void setStateDepositsConfirmed() {
        if (!isDepositConfirmed()) setState(State.DEPOSIT_TXS_CONFIRMED_IN_BLOCKCHAIN);
    }

    private void setStateDepositsUnlocked() {
        if (!isDepositUnlocked()) setState(State.DEPOSIT_TXS_UNLOCKED_IN_BLOCKCHAIN);
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

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public Message toProtoMessage() {
        protobuf.Trade.Builder builder = protobuf.Trade.newBuilder()
                .setOffer(offer.toProtoMessage())
                .setTxFeeAsLong(txFeeAsLong)
                .setTakerFeeAsLong(takerFeeAsLong)
                .setTakeOfferDate(takeOfferDate)
                .setProcessModel(processModel.toProtoMessage())
                .setAmountAsLong(amountAsLong)
                .setPrice(price)
                .setState(Trade.State.toProtoMessage(state))
                .setPayoutState(Trade.PayoutState.toProtoMessage(payoutState))
                .setDisputeState(Trade.DisputeState.toProtoMessage(disputeState))
                .setPeriodState(Trade.TradePeriodState.toProtoMessage(periodState))
                .addAllChatMessage(chatMessages.stream()
                        .map(msg -> msg.toProtoNetworkEnvelope().getChatMessage())
                        .collect(Collectors.toList()))
                .setLockTime(lockTime)
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
        Optional.ofNullable(payoutTxKey).ifPresent(e -> builder.setPayoutTxHex(payoutTxKey));
        Optional.ofNullable(counterCurrencyExtraData).ifPresent(e -> builder.setCounterCurrencyExtraData(counterCurrencyExtraData));
        Optional.ofNullable(assetTxProofResult).ifPresent(e -> builder.setAssetTxProofResult(assetTxProofResult.name()));
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
        trade.setCounterCurrencyExtraData(ProtoUtil.stringOrNullFromProto(proto.getCounterCurrencyExtraData()));

        AssetTxProofResult persistedAssetTxProofResult = ProtoUtil.enumFromProto(AssetTxProofResult.class, proto.getAssetTxProofResult());
        // We do not want to show the user the last pending state when he starts up the app again, so we clear it.
        if (persistedAssetTxProofResult == AssetTxProofResult.PENDING) {
            persistedAssetTxProofResult = null;
        }
        trade.setAssetTxProofResult(persistedAssetTxProofResult);

        trade.chatMessages.addAll(proto.getChatMessageList().stream()
                .map(ChatMessage::fromPayloadProto)
                .collect(Collectors.toList()));

        return trade;
    }

    @Override
    public String toString() {
        return "Trade{" +
                "\n     offer=" + offer +
                ",\n     txFeeAsLong=" + txFeeAsLong +
                ",\n     takerFeeAsLong=" + takerFeeAsLong +
                ",\n     takeOfferDate=" + takeOfferDate +
                ",\n     processModel=" + processModel +
                ",\n     payoutTxId='" + payoutTxId + '\'' +
                ",\n     tradeAmountAsLong=" + amountAsLong +
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
                ",\n     assetTxProofResult='" + assetTxProofResult + '\'' +
                ",\n     chatMessages=" + chatMessages +
                ",\n     txFee=" + txFee +
                ",\n     takerFee=" + takerFee +
                ",\n     xmrWalletService=" + xmrWalletService +
                ",\n     stateProperty=" + stateProperty +
                ",\n     statePhaseProperty=" + phaseProperty +
                ",\n     disputeStateProperty=" + disputeStateProperty +
                ",\n     tradePeriodStateProperty=" + tradePeriodStateProperty +
                ",\n     errorMessageProperty=" + errorMessageProperty +
                ",\n     payoutTx=" + payoutTx +
                ",\n     tradeAmount=" + tradeAmount +
                ",\n     tradeAmountProperty=" + tradeAmountProperty +
                ",\n     tradeVolumeProperty=" + tradeVolumeProperty +
                ",\n     mediationResultState=" + mediationResultState +
                ",\n     mediationResultStateProperty=" + mediationResultStateProperty +
                ",\n     lockTime=" + lockTime +
                ",\n     refundResultState=" + refundResultState +
                ",\n     refundResultStateProperty=" + refundResultStateProperty +
                "\n}";
    }
}
