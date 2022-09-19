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
import bisq.core.payment.payload.PaymentMethod;
import bisq.core.proto.CoreProtoResolver;
import bisq.core.support.dispute.mediation.MediationResultState;
import bisq.core.support.dispute.refund.RefundResultState;
import bisq.core.support.messages.ChatMessage;
import bisq.core.trade.messages.TradeMessage;
import bisq.core.trade.protocol.ProcessModel;
import bisq.core.trade.protocol.ProcessModelServiceProvider;
import bisq.core.trade.protocol.TradeListener;
import bisq.core.trade.protocol.TradingPeer;
import bisq.core.trade.txproof.AssetTxProofResult;
import bisq.core.util.ParsingUtils;
import bisq.core.util.VolumeUtil;
import bisq.network.p2p.AckMessage;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.P2PService;
import bisq.common.UserThread;
import bisq.common.crypto.PubKeyRing;
import bisq.common.proto.ProtoUtil;
import bisq.common.taskrunner.Model;
import bisq.common.util.Utilities;
import com.google.common.base.Preconditions;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Transaction;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkNotNull;



import monero.common.MoneroError;
import monero.daemon.MoneroDaemon;
import monero.daemon.model.MoneroTx;
import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroDestination;
import monero.wallet.model.MoneroMultisigSignResult;
import monero.wallet.model.MoneroTxConfig;
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
        SAW_ARRIVED_PUBLISH_DEPOSIT_TX_REQUEST(Phase.DEPOSIT_REQUESTED),
        STORED_IN_MAILBOX_PUBLISH_DEPOSIT_TX_REQUEST(Phase.DEPOSIT_REQUESTED), // not a mailbox msg, not used... remove
        SEND_FAILED_PUBLISH_DEPOSIT_TX_REQUEST(Phase.DEPOSIT_REQUESTED),

        // deposit published
        DEPOSIT_TXS_SEEN_IN_BLOCKCHAIN(Phase.DEPOSITS_PUBLISHED), // TODO: seeing in network usually happens after arbitrator publishes
        ARBITRATOR_PUBLISHED_DEPOSIT_TXS(Phase.DEPOSITS_PUBLISHED),

        // deposit confirmed
        DEPOSIT_TXS_CONFIRMED_IN_BLOCKCHAIN(Phase.DEPOSITS_CONFIRMED),

        // deposit unlocked
        DEPOSIT_TXS_UNLOCKED_IN_BLOCKCHAIN(Phase.DEPOSITS_UNLOCKED),

        // payment sent
        BUYER_CONFIRMED_IN_UI_PAYMENT_SENT(Phase.PAYMENT_SENT),
        BUYER_SENT_PAYMENT_SENT_MSG(Phase.PAYMENT_SENT),
        BUYER_SAW_ARRIVED_PAYMENT_SENT_MSG(Phase.PAYMENT_SENT),
        BUYER_STORED_IN_MAILBOX_PAYMENT_SENT_MSG(Phase.PAYMENT_SENT),
        BUYER_SEND_FAILED_PAYMENT_SENT_MSG(Phase.PAYMENT_SENT),
        SELLER_RECEIVED_PAYMENT_SENT_MSG(Phase.PAYMENT_SENT),

        // payment received
        SELLER_CONFIRMED_IN_UI_PAYMENT_RECEIPT(Phase.PAYMENT_RECEIVED),
        SELLER_SENT_PAYMENT_RECEIVED_MSG(Phase.PAYMENT_RECEIVED),
        SELLER_SAW_ARRIVED_PAYMENT_RECEIVED_MSG(Phase.PAYMENT_RECEIVED),
        SELLER_STORED_IN_MAILBOX_PAYMENT_RECEIVED_MSG(Phase.PAYMENT_RECEIVED),
        SELLER_SEND_FAILED_PAYMENT_RECEIVED_MSG(Phase.PAYMENT_RECEIVED),

        // payout published
        SELLER_PUBLISHED_PAYOUT_TX(Phase.PAYOUT_PUBLISHED), // TODO (woodser): this enum is over used, like during arbitration
        SELLER_SENT_PAYOUT_TX_PUBLISHED_MSG(Phase.PAYOUT_PUBLISHED),
        SELLER_SAW_ARRIVED_PAYOUT_TX_PUBLISHED_MSG(Phase.PAYOUT_PUBLISHED),
        SELLER_STORED_IN_MAILBOX_PAYOUT_TX_PUBLISHED_MSG(Phase.PAYOUT_PUBLISHED),
        SELLER_SEND_FAILED_PAYOUT_TX_PUBLISHED_MSG(Phase.PAYOUT_PUBLISHED),
        BUYER_RECEIVED_PAYOUT_TX_PUBLISHED_MSG(Phase.PAYOUT_PUBLISHED),
        BUYER_SAW_PAYOUT_TX_IN_NETWORK(Phase.PAYOUT_PUBLISHED),
        BUYER_PUBLISHED_PAYOUT_TX(Phase.PAYOUT_PUBLISHED),

        // trade completed
        WITHDRAW_COMPLETED(Phase.WITHDRAWN);

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
        DEPOSIT_REQUESTED, // TODO (woodser): remove unused phases
        DEPOSITS_PUBLISHED,
        DEPOSITS_CONFIRMED,
        DEPOSITS_UNLOCKED,
        PAYMENT_SENT,
        PAYMENT_RECEIVED,
        PAYOUT_PUBLISHED,
        WITHDRAWN;

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

    public enum DisputeState {
        NO_DISPUTE,
        // arbitration
        DISPUTE_REQUESTED,
        DISPUTE_STARTED_BY_PEER,
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
            return this == Trade.DisputeState.DISPUTE_REQUESTED ||
                    this == Trade.DisputeState.DISPUTE_STARTED_BY_PEER ||
                    this == Trade.DisputeState.DISPUTE_CLOSED ||
                    this == Trade.DisputeState.REFUND_REQUESTED ||
                    this == Trade.DisputeState.REFUND_REQUEST_STARTED_BY_PEER ||
                    this == Trade.DisputeState.REFUND_REQUEST_CLOSED;
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
    @Nullable
    @Getter
    @Setter
    private String takerFeeTxId;
    @Nullable
    @Getter
    @Setter
    private String payoutTxId;
    @Getter
    @Setter
    private long amountAsLong;
    @Setter
    private long price;
    @Nullable
    @Getter
    private State state = State.PREPARATION;
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
    @Getter
    @Setter
    private NodeAddress arbitratorNodeAddress;
    @Nullable
    @Getter
    @Setter
    private PubKeyRing arbitratorPubKeyRing;
    @Nullable
    @Getter
    @Setter
    private String takerPaymentAccountId;
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
    transient final private ObjectProperty<Phase> statePhaseProperty = new SimpleObjectProperty<>(state.phase);
    transient final private ObjectProperty<DisputeState> disputeStateProperty = new SimpleObjectProperty<>(disputeState);
    transient final private ObjectProperty<TradePeriodState> tradePeriodStateProperty = new SimpleObjectProperty<>(periodState);
    transient final private StringProperty errorMessageProperty = new SimpleStringProperty();
    
    //  Mutable
    @Getter
    transient private boolean isInitialized;

    // Added in v1.2.0
    @Nullable
    transient private Transaction delayedPayoutTx;

    @Nullable
    transient private MoneroTxWallet payoutTx;
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
    @Nullable
    @Getter
    @Setter
    private byte[] delayedPayoutTxBytes;
    @Nullable
    @Getter
    @Setter
    private NodeAddress refundAgentNodeAddress;
    @Nullable
    @Getter
    @Setter
    private PubKeyRing refundAgentPubKeyRing;
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
    @Getter
    @Setter
    private NodeAddress makerNodeAddress;
    @Getter
    @Setter
    private NodeAddress takerNodeAddress;
    @Getter
    @Setter
    private PubKeyRing makerPubKeyRing;
    @Getter
    @Setter
    private PubKeyRing takerPubKeyRing;
    transient MoneroWalletListener depositTxListener;
    transient Boolean makerDepositLocked; // null when unknown, true while locked, false when unlocked
    transient Boolean takerDepositLocked;
    transient private MoneroTx makerDepositTx;
    transient private MoneroTx takerDepositTx;
    private Long startTime; // cache

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, initialization
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
        
        this.makerNodeAddress = makerNodeAddress;
        this.takerNodeAddress = takerNodeAddress;
        this.arbitratorNodeAddress = arbitratorNodeAddress;
        
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
                .setDisputeState(Trade.DisputeState.toProtoMessage(disputeState))
                .setPeriodState(Trade.TradePeriodState.toProtoMessage(periodState))
                .addAllChatMessage(chatMessages.stream()
                        .map(msg -> msg.toProtoNetworkEnvelope().getChatMessage())
                        .collect(Collectors.toList()))
                .setLockTime(lockTime)
                .setUid(uid);

        Optional.ofNullable(takerFeeTxId).ifPresent(builder::setTakerFeeTxId);
        Optional.ofNullable(payoutTxId).ifPresent(builder::setPayoutTxId);
        Optional.ofNullable(contract).ifPresent(e -> builder.setContract(contract.toProtoMessage()));
        Optional.ofNullable(contractAsJson).ifPresent(builder::setContractAsJson);
        Optional.ofNullable(contractHash).ifPresent(e -> builder.setContractHash(ByteString.copyFrom(contractHash)));
        Optional.ofNullable(arbitratorNodeAddress).ifPresent(e -> builder.setArbitratorNodeAddress(arbitratorNodeAddress.toProtoMessage()));
        Optional.ofNullable(refundAgentNodeAddress).ifPresent(e -> builder.setRefundAgentNodeAddress(refundAgentNodeAddress.toProtoMessage()));
        Optional.ofNullable(takerPaymentAccountId).ifPresent(builder::setTakerPaymentAccountId);
        Optional.ofNullable(errorMessage).ifPresent(builder::setErrorMessage);
        Optional.ofNullable(arbitratorPubKeyRing).ifPresent(e -> builder.setArbitratorPubKeyRing(arbitratorPubKeyRing.toProtoMessage()));
        Optional.ofNullable(refundAgentPubKeyRing).ifPresent(e -> builder.setRefundAgentPubKeyRing(refundAgentPubKeyRing.toProtoMessage()));
        Optional.ofNullable(counterCurrencyTxId).ifPresent(e -> builder.setCounterCurrencyTxId(counterCurrencyTxId));
        Optional.ofNullable(mediationResultState).ifPresent(e -> builder.setMediationResultState(MediationResultState.toProtoMessage(mediationResultState)));
        Optional.ofNullable(refundResultState).ifPresent(e -> builder.setRefundResultState(RefundResultState.toProtoMessage(refundResultState)));
        Optional.ofNullable(delayedPayoutTxBytes).ifPresent(e -> builder.setDelayedPayoutTxBytes(ByteString.copyFrom(delayedPayoutTxBytes)));
        Optional.ofNullable(counterCurrencyExtraData).ifPresent(e -> builder.setCounterCurrencyExtraData(counterCurrencyExtraData));
        Optional.ofNullable(assetTxProofResult).ifPresent(e -> builder.setAssetTxProofResult(assetTxProofResult.name()));
        Optional.ofNullable(makerNodeAddress).ifPresent(e -> builder.setMakerNodeAddress(makerNodeAddress.toProtoMessage()));
        Optional.ofNullable(makerPubKeyRing).ifPresent(e -> builder.setMakerPubKeyRing(makerPubKeyRing.toProtoMessage()));
        Optional.ofNullable(takerNodeAddress).ifPresent(e -> builder.setTakerNodeAddress(takerNodeAddress.toProtoMessage()));
        Optional.ofNullable(takerPubKeyRing).ifPresent(e -> builder.setTakerPubKeyRing(takerPubKeyRing.toProtoMessage()));
        return builder.build();
    }

    public static Trade fromProto(Trade trade, protobuf.Trade proto, CoreProtoResolver coreProtoResolver) {
        trade.setTakeOfferDate(proto.getTakeOfferDate());
        trade.setState(State.fromProto(proto.getState()));
        trade.setDisputeState(DisputeState.fromProto(proto.getDisputeState()));
        trade.setPeriodState(TradePeriodState.fromProto(proto.getPeriodState()));
        trade.setTakerFeeTxId(ProtoUtil.stringOrNullFromProto(proto.getTakerFeeTxId()));
        trade.setPayoutTxId(ProtoUtil.stringOrNullFromProto(proto.getPayoutTxId()));
        trade.setContract(proto.hasContract() ? Contract.fromProto(proto.getContract(), coreProtoResolver) : null);
        trade.setContractAsJson(ProtoUtil.stringOrNullFromProto(proto.getContractAsJson()));
        trade.setContractHash(ProtoUtil.byteArrayOrNullFromProto(proto.getContractHash()));
        trade.setArbitratorNodeAddress(proto.hasArbitratorNodeAddress() ? NodeAddress.fromProto(proto.getArbitratorNodeAddress()) : null);
        trade.setRefundAgentNodeAddress(proto.hasRefundAgentNodeAddress() ? NodeAddress.fromProto(proto.getRefundAgentNodeAddress()) : null);
        trade.setTakerPaymentAccountId(ProtoUtil.stringOrNullFromProto(proto.getTakerPaymentAccountId()));
        trade.setErrorMessage(ProtoUtil.stringOrNullFromProto(proto.getErrorMessage()));
        trade.setArbitratorPubKeyRing(proto.hasArbitratorPubKeyRing() ? PubKeyRing.fromProto(proto.getArbitratorPubKeyRing()) : null);
        trade.setRefundAgentPubKeyRing(proto.hasRefundAgentPubKeyRing() ? PubKeyRing.fromProto(proto.getRefundAgentPubKeyRing()) : null);
        trade.setCounterCurrencyTxId(proto.getCounterCurrencyTxId().isEmpty() ? null : proto.getCounterCurrencyTxId());
        trade.setMediationResultState(MediationResultState.fromProto(proto.getMediationResultState()));
        trade.setRefundResultState(RefundResultState.fromProto(proto.getRefundResultState()));
        trade.setDelayedPayoutTxBytes(ProtoUtil.byteArrayOrNullFromProto(proto.getDelayedPayoutTxBytes()));
        trade.setLockTime(proto.getLockTime());
        trade.setCounterCurrencyExtraData(ProtoUtil.stringOrNullFromProto(proto.getCounterCurrencyExtraData()));

        AssetTxProofResult persistedAssetTxProofResult = ProtoUtil.enumFromProto(AssetTxProofResult.class, proto.getAssetTxProofResult());
        // We do not want to show the user the last pending state when he starts up the app again, so we clear it.
        if (persistedAssetTxProofResult == AssetTxProofResult.PENDING) {
            persistedAssetTxProofResult = null;
        }
        trade.setAssetTxProofResult(persistedAssetTxProofResult);
        trade.setMakerNodeAddress(NodeAddress.fromProto(proto.getMakerNodeAddress()));
        trade.setMakerPubKeyRing(proto.hasMakerPubKeyRing() ? PubKeyRing.fromProto(proto.getMakerPubKeyRing()) : null);
        trade.setTakerNodeAddress(NodeAddress.fromProto(proto.getTakerNodeAddress()));
        trade.setTakerPubKeyRing(proto.hasTakerPubKeyRing() ? PubKeyRing.fromProto(proto.getTakerPubKeyRing()) : null);

        trade.chatMessages.addAll(proto.getChatMessageList().stream()
                .map(ChatMessage::fromPayloadProto)
                .collect(Collectors.toList()));

        return trade;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void initialize(ProcessModelServiceProvider serviceProvider) {
        serviceProvider.getArbitratorManager().getDisputeAgentByNodeAddress(arbitratorNodeAddress).ifPresent(arbitrator -> {
            arbitratorPubKeyRing = arbitrator.getPubKeyRing();
        });

        isInitialized = true;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void setMyNodeAddress() {
      if (this instanceof MakerTrade) makerNodeAddress = P2PService.getMyNodeAddress();
      else if (this instanceof TakerTrade) takerNodeAddress = P2PService.getMyNodeAddress();
      else if (this instanceof ArbitratorTrade) arbitratorNodeAddress = P2PService.getMyNodeAddress();
      else throw new RuntimeException("Must be maker, taker, or arbitrator to set own address");
    }

    public void setTradingPeerNodeAddress(NodeAddress peerAddress) {
      if (this instanceof MakerTrade) takerNodeAddress = peerAddress;
      else if (this instanceof TakerTrade) makerNodeAddress = peerAddress;
      else throw new RuntimeException("Must be maker or taker to set peer address");
    }

    public NodeAddress getTradingPeerNodeAddress() {
      if (this instanceof MakerTrade) return takerNodeAddress;
      else if (this instanceof TakerTrade) return makerNodeAddress;
      else if (this instanceof ArbitratorTrade) return null;
      else throw new RuntimeException("Unknown trade type: " + this.getClass().getName());
    }

    public void setTradingPeerPubKeyRing(PubKeyRing peerPubKeyRing) {
      if (this instanceof MakerTrade) takerPubKeyRing = peerPubKeyRing;
      else if (this instanceof TakerTrade) makerPubKeyRing = peerPubKeyRing;
      else throw new RuntimeException("Must be maker or taker to set peer address");
    }

    public PubKeyRing getTradingPeerPubKeyRing() {
      if (this instanceof MakerTrade) return takerPubKeyRing;
      else if (this instanceof TakerTrade) return makerPubKeyRing;
      else if (this instanceof ArbitratorTrade) return null;
      else throw new RuntimeException("Unknown trade type: " + this.getClass().getName());
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
                isBuyerMakerAndSellerTaker ? getMakerNodeAddress() : getTakerNodeAddress(), // buyer node address // TODO (woodser): use maker and taker node address instead of buyer and seller node address for consistency
                isBuyerMakerAndSellerTaker ? getTakerNodeAddress() : getMakerNodeAddress(), // seller node address
                getArbitratorNodeAddress(),
                isBuyerMakerAndSellerTaker,
                this instanceof MakerTrade ? processModel.getAccountId() : getMaker().getAccountId(), // maker account id
                this instanceof TakerTrade ? processModel.getAccountId() : getTaker().getAccountId(), // taker account id
                checkNotNull(this instanceof MakerTrade ? processModel.getPaymentAccountPayload(this).getPaymentMethodId() : getOffer().getOfferPayload().getPaymentMethodId()), // maker payment method id
                checkNotNull(this instanceof TakerTrade ? processModel.getPaymentAccountPayload(this).getPaymentMethodId() : getTaker().getPaymentMethodId()), // taker payment method id
                this instanceof MakerTrade ? processModel.getPaymentAccountPayload(this).getHash() : getMaker().getPaymentAccountPayloadHash(), // maker payment account payload hash
                this instanceof TakerTrade ? processModel.getPaymentAccountPayload(this).getHash() : getTaker().getPaymentAccountPayloadHash(), // maker payment account payload hash
                getMakerPubKeyRing(),
                getTakerPubKeyRing(),
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

        // gather relevant info
        XmrWalletService walletService = processModel.getProvider().getXmrWalletService();
        MoneroWallet multisigWallet = walletService.getMultisigWallet(this.getId());
        if (multisigWallet.isMultisigImportNeeded()) throw new RuntimeException("Cannot create payout tx because multisig import is needed");
        String sellerPayoutAddress = this.getSeller().getPayoutAddressString();
        String buyerPayoutAddress = this.getBuyer().getPayoutAddressString();
        Preconditions.checkNotNull(sellerPayoutAddress, "Seller payout address must not be null");
        Preconditions.checkNotNull(buyerPayoutAddress, "Buyer payout address must not be null");
        BigInteger sellerDepositAmount = multisigWallet.getTx(this.getSeller().getDepositTxHash()).getIncomingAmount();
        BigInteger buyerDepositAmount = multisigWallet.getTx(this.getBuyer().getDepositTxHash()).getIncomingAmount();
        BigInteger tradeAmount = ParsingUtils.coinToAtomicUnits(this.getAmount());
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
        log.info("Payout transaction generated on attempt {}: {}", numAttempts, payoutTx);
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
        BigInteger tradeAmount = ParsingUtils.coinToAtomicUnits(getAmount());

        // parse payout tx
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
        getSelf().setPayoutTxHex(payoutTxHex);
        setPayoutTx(describedTxSet.getTxs().get(0));
        setPayoutTxId(describedTxSet.getTxs().get(0).getHash());

        // submit payout tx
        if (publish) {
            multisigWallet.submitMultisigTxHex(payoutTxHex);
            setState(isArbitrator() ? Trade.State.WITHDRAW_COMPLETED : isBuyer() ? Trade.State.BUYER_PUBLISHED_PAYOUT_TX : Trade.State.SELLER_PUBLISHED_PAYOUT_TX);
        }
        walletService.closeMultisigWallet(getId());
    }

    /**
     * Listen for deposit transactions to unlock and then apply the transactions.
     * 
     * TODO: adopt for general purpose scheduling
     * TODO: check and notify if deposits are dropped due to re-org
     */
    public void listenForDepositTxs() {
        log.info("Listening for deposit txs to unlock for trade {}", getId());

        // ignore if already listening
        if (depositTxListener != null) {
            log.warn("Trade {} already listening for deposit txs", getId());
            return;
        }

        // get daemon and primary wallet
        MoneroWallet havenoWallet = processModel.getXmrWalletService().getWallet();

        // fetch deposit txs from daemon
        List<MoneroTx> txs = xmrWalletService.getTxs(Arrays.asList(processModel.getMaker().getDepositTxHash(), processModel.getTaker().getDepositTxHash()));

        // handle deposit txs seen
        if (txs.size() == 2) {
            setStatePublished();
            boolean makerFirst = txs.get(0).getHash().equals(processModel.getMaker().getDepositTxHash());
            makerDepositTx = makerFirst ? txs.get(0) : txs.get(1);
            takerDepositTx = makerFirst ? txs.get(1) : txs.get(0);

            // check if deposit txs unlocked
            if (txs.get(0).isConfirmed() && txs.get(1).isConfirmed()) {
                setStateConfirmed();
                long unlockHeight = Math.max(txs.get(0).getHeight(), txs.get(1).getHeight()) + XmrWalletService.NUM_BLOCKS_UNLOCK;
                if (havenoWallet.getHeight() >= unlockHeight) {
                    setStateUnlocked();
                    return;
                }
            }
        }

        // create block listener
        depositTxListener = new MoneroWalletListener() {
            Long unlockHeight = null;

            @Override
            public void onNewBlock(long height) {

                // skip if no longer listening
                if (depositTxListener == null) return;

                // use latest height
                height = havenoWallet.getHeight();

                // skip if before unlock height
                if (unlockHeight != null && height < unlockHeight) return;

                // fetch txs from daemon
                List<MoneroTx> txs = xmrWalletService.getTxs(Arrays.asList(processModel.getMaker().getDepositTxHash(), processModel.getTaker().getDepositTxHash()));

                // skip if deposit txs not seen
                if (txs.size() != 2) return;
                setStatePublished();

                // update deposit txs
                boolean makerFirst = txs.get(0).getHash().equals(processModel.getMaker().getDepositTxHash());
                makerDepositTx = makerFirst ? txs.get(0) : txs.get(1);
                takerDepositTx = makerFirst ? txs.get(1) : txs.get(0);

                // check if deposit txs confirmed and compute unlock height
                if (txs.size() == 2 && txs.get(0).isConfirmed() && txs.get(1).isConfirmed() && unlockHeight == null) {
                    log.info("Multisig deposits confirmed for trade {}", getId());
                    setStateConfirmed();
                    unlockHeight = Math.max(txs.get(0).getHeight(), txs.get(1).getHeight()) + XmrWalletService.NUM_BLOCKS_UNLOCK;
                }

                // check if deposit txs unlocked
                if (unlockHeight != null && height >= unlockHeight) {
                    log.info("Multisig deposits unlocked for trade {}", getId());
                    xmrWalletService.removeWalletListener(depositTxListener); // remove listener when notified
                    depositTxListener = null; // prevent re-applying trade state in subsequent requests
                    setStateUnlocked();
                }
            }
        };

        // register wallet listener
        xmrWalletService.addWalletListener(depositTxListener);
    }

    @Nullable
    public MoneroTx getTakerDepositTx() {
        String depositTxHash = getProcessModel().getTaker().getDepositTxHash();
        try {
            if (takerDepositTx == null) takerDepositTx = depositTxHash == null ? null : getXmrWalletService().getTxWithCache(depositTxHash);
            return takerDepositTx;
        } catch (MoneroError e) {
            log.error("Wallet is missing taker deposit tx " + depositTxHash);
            return null;
        }
    }

    @Nullable
    public MoneroTx getMakerDepositTx() {
        String depositTxHash = getProcessModel().getMaker().getDepositTxHash();
        try {
            if (makerDepositTx == null) makerDepositTx = depositTxHash == null ? null : getXmrWalletService().getTxWithCache(depositTxHash);
            return makerDepositTx;
        } catch (MoneroError e) {
            log.error("Wallet is missing maker deposit tx " + depositTxHash);
            return null;
        }
    }

    public void applyDelayedPayoutTx(Transaction delayedPayoutTx) {
        this.delayedPayoutTx = delayedPayoutTx;
        this.delayedPayoutTxBytes = delayedPayoutTx.bitcoinSerialize();
    }

    public void applyDelayedPayoutTxBytes(byte[] delayedPayoutTxBytes) {
        this.delayedPayoutTxBytes = delayedPayoutTxBytes;
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
            statePhaseProperty.set(state.getPhase());
        });
    }

    public void setDisputeState(DisputeState disputeState) {
        this.disputeState = disputeState;
        disputeStateProperty.set(disputeState);
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

    /**
     * Get the taker if maker, maker if taker, null if arbitrator.
     * 
     * @return the trade peer
     */
    public TradingPeer getTradingPeer() {
      if (this instanceof MakerTrade) return processModel.getTaker();
      else if (this instanceof TakerTrade) return processModel.getMaker();
      else if (this instanceof ArbitratorTrade) return null;
      else throw new RuntimeException("Unknown trade type: " + getClass().getName());
    }
    
    /**
     * Get the peer with the given address which can be self.
     * 
     * TODO (woodser): this naming convention is confusing
     * 
     * @param address is the address of the peer to get
     * @return the trade peer
     */
    public TradingPeer getTradingPeer(NodeAddress address) {
        if (address.equals(getMakerNodeAddress())) return processModel.getMaker();
        if (address.equals(getTakerNodeAddress())) return processModel.getTaker();
        if (address.equals(getArbitratorNodeAddress())) return processModel.getArbitrator();
        throw new RuntimeException("No protocol participant has node address: " + address);
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
        final MoneroTx takerDepositTx = getTakerDepositTx();
        final MoneroTx makerDepositTx = getMakerDepositTx();
        if (makerDepositTx != null && takerDepositTx != null && getTakeOfferDate() != null) {
            if (isDepositUnlocked()) {
                final long tradeTime = getTakeOfferDate().getTime();
                long maxHeight = Math.max(makerDepositTx.getHeight(), takerDepositTx.getHeight());
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
                log.debug("depositTx not confirmed yet. We don't start counting remaining trade period yet. makerTxId={}, takerTxId={}", makerDepositTx.getHash(), takerDepositTx.getHash());
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

    public boolean isTakerFeePublished() {
        return getState().getPhase().ordinal() >= Phase.DEPOSIT_REQUESTED.ordinal();
    }

    public boolean isDepositPublished() {
        return getState().getPhase().ordinal() >= Phase.DEPOSITS_PUBLISHED.ordinal();
    }

    public boolean isFundsLockedIn() {
        // If no deposit tx was published we have no funds locked in
        if (!isDepositPublished()) {
            return false;
        }

        // If we have the payout tx published (non disputed case) we have no funds locked in. Here we might have more
        // complex cases where users open a mediation but continue the trade to finalize it without mediated payout.
        // The trade state handles that but does not handle mediated payouts or refund agents payouts.
        if (isPayoutPublished()) {
            return false;
        }

        // check for closed disputed case
        if (disputeState == DisputeState.DISPUTE_CLOSED) return false;

        // In mediation case we check for the mediationResultState. As there are multiple sub-states we use ordinal.
        if (disputeState == DisputeState.MEDIATION_CLOSED) {
            if (mediationResultState != null &&
                    mediationResultState.ordinal() >= MediationResultState.PAYOUT_TX_PUBLISHED.ordinal()) {
                return false;
            }
        }

        // In refund agent case the funds are spent anyway with the time locked payout. We do not consider that as
        // locked in funds.
        return disputeState != DisputeState.REFUND_REQUESTED &&
                disputeState != DisputeState.REFUND_REQUEST_STARTED_BY_PEER &&
                disputeState != DisputeState.REFUND_REQUEST_CLOSED;
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

    public boolean isPayoutPublished() {
        if (getState() == Trade.State.SELLER_SAW_ARRIVED_PAYMENT_RECEIVED_MSG) return true; // TODO: this is a hack because seller has not seen signed payout tx. replace when payout process refactored
        return getState().getPhase().ordinal() >= Phase.PAYOUT_PUBLISHED.ordinal() || isWithdrawn();
    }

    public boolean isCompleted() {
        return isPayoutPublished();
    }

    public boolean isWithdrawn() {
        return getState().getPhase().ordinal() == Phase.WITHDRAWN.ordinal();
    }

    public ReadOnlyObjectProperty<State> stateProperty() {
        return stateProperty;
    }

    public ReadOnlyObjectProperty<Phase> statePhaseProperty() {
        return statePhaseProperty;
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
                getTakerFeeTxId() == null ||
                processModel.getMaker().getDepositTxHash() == null ||
                processModel.getMaker().getDepositTxHash() == null ||
                getDelayedPayoutTxBytes() == null;
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

    private void setStatePublished() {
        if (!isDepositPublished()) setState(State.DEPOSIT_TXS_SEEN_IN_BLOCKCHAIN);
    }

    private void setStateConfirmed() {
        if (!isDepositConfirmed()) setState(State.DEPOSIT_TXS_CONFIRMED_IN_BLOCKCHAIN);
    }

    private void setStateUnlocked() {
        if (!isDepositUnlocked()) setState(State.DEPOSIT_TXS_UNLOCKED_IN_BLOCKCHAIN);
    }

    @Override
    public String toString() {
        return "Trade{" +
                "\n     offer=" + offer +
                ",\n     txFeeAsLong=" + txFeeAsLong +
                ",\n     takerFeeAsLong=" + takerFeeAsLong +
                ",\n     takeOfferDate=" + takeOfferDate +
                ",\n     processModel=" + processModel +
                ",\n     takerFeeTxId='" + takerFeeTxId + '\'' +
                ",\n     payoutTxId='" + payoutTxId + '\'' +
                ",\n     tradeAmountAsLong=" + amountAsLong +
                ",\n     tradePrice=" + price +
                ",\n     state=" + state +
                ",\n     disputeState=" + disputeState +
                ",\n     tradePeriodState=" + periodState +
                ",\n     contract=" + contract +
                ",\n     contractAsJson='" + contractAsJson + '\'' +
                ",\n     contractHash=" + Utilities.bytesAsHexString(contractHash) +
                ",\n     takerPaymentAccountId='" + takerPaymentAccountId + '\'' +
                ",\n     errorMessage='" + errorMessage + '\'' +
                ",\n     counterCurrencyTxId='" + counterCurrencyTxId + '\'' +
                ",\n     counterCurrencyExtraData='" + counterCurrencyExtraData + '\'' +
                ",\n     assetTxProofResult='" + assetTxProofResult + '\'' +
                ",\n     chatMessages=" + chatMessages +
                ",\n     txFee=" + txFee +
                ",\n     takerFee=" + takerFee +
                ",\n     xmrWalletService=" + xmrWalletService +
                ",\n     stateProperty=" + stateProperty +
                ",\n     statePhaseProperty=" + statePhaseProperty +
                ",\n     disputeStateProperty=" + disputeStateProperty +
                ",\n     tradePeriodStateProperty=" + tradePeriodStateProperty +
                ",\n     errorMessageProperty=" + errorMessageProperty +
                ",\n     depositTx=" + takerDepositTx +
                ",\n     delayedPayoutTx=" + delayedPayoutTx +
                ",\n     payoutTx=" + payoutTx +
                ",\n     tradeAmount=" + tradeAmount +
                ",\n     tradeAmountProperty=" + tradeAmountProperty +
                ",\n     tradeVolumeProperty=" + tradeVolumeProperty +
                ",\n     mediationResultState=" + mediationResultState +
                ",\n     mediationResultStateProperty=" + mediationResultStateProperty +
                ",\n     lockTime=" + lockTime +
                ",\n     delayedPayoutTxBytes=" + Utilities.bytesAsHexString(delayedPayoutTxBytes) +
                ",\n     refundAgentNodeAddress=" + refundAgentNodeAddress +
                ",\n     refundAgentPubKeyRing=" + refundAgentPubKeyRing +
                ",\n     refundResultState=" + refundResultState +
                ",\n     refundResultStateProperty=" + refundResultStateProperty +
                ",\n     makerNodeAddress=" + makerNodeAddress +
                ",\n     makerPubKeyRing=" + makerPubKeyRing +
                ",\n     takerNodeAddress=" + takerNodeAddress +
                ",\n     takerPubKeyRing=" + takerPubKeyRing +
                ",\n     arbitratorNodeAddress=" + arbitratorNodeAddress +
                ",\n     arbitratorPubKeyRing=" + arbitratorPubKeyRing +
                "\n}";
    }
}
