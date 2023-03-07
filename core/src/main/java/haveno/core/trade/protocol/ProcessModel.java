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

package haveno.core.trade.protocol;

import com.google.protobuf.ByteString;
import haveno.common.app.Version;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.PubKeyRing;
import haveno.common.proto.ProtoUtil;
import haveno.common.proto.persistable.PersistablePayload;
import haveno.common.taskrunner.Model;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.btc.model.RawTransactionInput;
import haveno.core.btc.wallet.BtcWalletService;
import haveno.core.btc.wallet.TradeWalletService;
import haveno.core.btc.wallet.XmrWalletService;
import haveno.core.filter.FilterManager;
import haveno.core.network.MessageState;
import haveno.core.offer.Offer;
import haveno.core.offer.OpenOfferManager;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.proto.CoreProtoResolver;
import haveno.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import haveno.core.support.dispute.mediation.mediator.MediatorManager;
import haveno.core.support.dispute.messages.DisputeClosedMessage;
import haveno.core.support.dispute.refund.refundagent.RefundAgentManager;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.trade.messages.PaymentReceivedMessage;
import haveno.core.trade.messages.PaymentSentMessage;
import haveno.core.trade.messages.TradeMessage;
import haveno.core.trade.statistics.ReferralIdService;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.core.user.User;
import haveno.network.p2p.AckMessage;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Transaction;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;



import monero.wallet.model.MoneroTxWallet;

// Fields marked as transient are only used during protocol execution which are based on directMessages so we do not
// persist them.

/**
 * This is the base model for the trade protocol. It is persisted with the trade (non transient fields).
 * It uses the {@link ProcessModelServiceProvider} for access to domain services.
 */

@Getter
@Slf4j
public class ProcessModel implements Model, PersistablePayload {
    // Transient/Immutable (net set in constructor so they are not final, but at init)
    transient private ProcessModelServiceProvider provider;
    transient private TradeManager tradeManager;
    transient private Offer offer;

    // Added in v1.2.0
    @Setter
    @Nullable
    transient private byte[] delayedPayoutTxSignature;
    @Setter
    @Nullable
    transient private Transaction preparedDelayedPayoutTx;

    // Added in v1.4.0
    // MessageState of the last message sent from the seller to the buyer in the take offer process.
    // It is used only in a task which would not be executed after restart, so no need to persist it.
    @Setter
    transient private ObjectProperty<MessageState> depositTxMessageStateProperty = new SimpleObjectProperty<>(MessageState.UNDEFINED);
    @Setter
    @Getter
    transient private Transaction depositTx; // TODO (woodser): remove and rename depositTxBtc with depositTx

    // Persistable Immutable (private setter only used by PB method)
    private TradePeer maker = new TradePeer();
    private TradePeer taker = new TradePeer();
    private TradePeer arbitrator = new TradePeer();
    private String offerId;
    private String accountId;
    private PubKeyRing pubKeyRing;

    // Persistable Mutable
    @Nullable
    @Setter
    private String takeOfferFeeTxId;
    @Nullable
    @Setter
    private byte[] payoutTxSignature;
    @Nullable
    @Setter
    private byte[] preparedDepositTx;
    @Nullable
    @Setter
    private List<RawTransactionInput> rawTransactionInputs;
    @Setter
    private long changeOutputValue;
    @Nullable
    @Setter
    private String changeOutputAddress;
    @Setter
    private boolean useSavingsWallet;
    @Setter
    private long fundsNeededForTradeAsLong;
    @Nullable
    @Setter
    private byte[] myMultiSigPubKey;
    // that is used to store temp. the peers address when we get an incoming message before the message is verified.
    // After successful verified we copy that over to the trade.tradePeerAddress
    @Nullable
    @Setter
    private NodeAddress tempTradePeerNodeAddress; // TODO (woodser): remove entirely?

    // Added in v.1.1.6
    @Nullable
    @Setter
    private byte[] mediatedPayoutTxSignature;
    @Setter
    private long buyerPayoutAmountFromMediation;
    @Setter
    private long sellerPayoutAmountFromMediation;

    // Added for XMR integration
    @Getter
    transient private MoneroTxWallet takeOfferFeeTx; // TODO (woodser): remove
    @Setter
    transient private TradeMessage tradeMessage;
    @Getter
    @Setter
    private String makerSignature;
    @Nullable
    @Getter
    @Setter
    transient private MoneroTxWallet reserveTx;
    @Getter
    @Setter
    transient private MoneroTxWallet depositTxXmr;
    @Getter
    @Setter
    transient private MoneroTxWallet unsignedPayoutTx;
    @Nullable
    @Getter
    @Setter
    private String multisigAddress;
    @Nullable
    @Getter
    @Setter
    private boolean isDepositsConfirmedMessagesDelivered;
    @Nullable
    @Setter
    @Getter
    private PaymentSentMessage paymentSentMessage;
    @Nullable
    @Setter
    @Getter
    private PaymentReceivedMessage paymentReceivedMessage;
    @Nullable
    @Setter
    @Getter
    private DisputeClosedMessage disputeClosedMessage;

    // We want to indicate the user the state of the message delivery of the
    // PaymentSentMessage. As well we do an automatic re-send in case it was not ACKed yet.
    // To enable that even after restart we persist the state.
    @Setter
    private ObjectProperty<MessageState> paymentSentMessageStateProperty = new SimpleObjectProperty<>(MessageState.UNDEFINED);

    public ProcessModel(String offerId, String accountId, PubKeyRing pubKeyRing) {
        this(offerId, accountId, pubKeyRing, new TradePeer(), new TradePeer(), new TradePeer());
    }

    public ProcessModel(String offerId, String accountId, PubKeyRing pubKeyRing, TradePeer arbitrator, TradePeer maker, TradePeer taker) {
        this.offerId = offerId;
        this.accountId = accountId;
        this.pubKeyRing = pubKeyRing;
        // If tradePeer was null in persisted data from some error cases we set a new one to not cause nullPointers
        this.arbitrator = arbitrator != null ? arbitrator : new TradePeer();
        this.maker = maker != null ? maker : new TradePeer();
        this.taker = taker != null ? taker : new TradePeer();
    }

    public void applyTransient(ProcessModelServiceProvider provider,
                               TradeManager tradeManager,
                               Offer offer) {
        this.offer = offer;
        this.provider = provider;
        this.tradeManager = tradeManager;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.ProcessModel toProtoMessage() {
        protobuf.ProcessModel.Builder builder = protobuf.ProcessModel.newBuilder()
                .setOfferId(offerId)
                .setAccountId(accountId)
                .setPubKeyRing(pubKeyRing.toProtoMessage())
                .setChangeOutputValue(changeOutputValue)
                .setUseSavingsWallet(useSavingsWallet)
                .setFundsNeededForTradeAsLong(fundsNeededForTradeAsLong)
                .setPaymentSentMessageState(paymentSentMessageStateProperty.get().name())
                .setBuyerPayoutAmountFromMediation(buyerPayoutAmountFromMediation)
                .setSellerPayoutAmountFromMediation(sellerPayoutAmountFromMediation)
                .setDepositsConfirmedMessagesDelivered(isDepositsConfirmedMessagesDelivered);
        Optional.ofNullable(maker).ifPresent(e -> builder.setMaker((protobuf.TradePeer) maker.toProtoMessage()));
        Optional.ofNullable(taker).ifPresent(e -> builder.setTaker((protobuf.TradePeer) taker.toProtoMessage()));
        Optional.ofNullable(arbitrator).ifPresent(e -> builder.setArbitrator((protobuf.TradePeer) arbitrator.toProtoMessage()));
        Optional.ofNullable(takeOfferFeeTxId).ifPresent(builder::setTakeOfferFeeTxId);
        Optional.ofNullable(payoutTxSignature).ifPresent(e -> builder.setPayoutTxSignature(ByteString.copyFrom(payoutTxSignature)));
        Optional.ofNullable(rawTransactionInputs).ifPresent(e -> builder.addAllRawTransactionInputs(ProtoUtil.collectionToProto(rawTransactionInputs, protobuf.RawTransactionInput.class)));
        Optional.ofNullable(changeOutputAddress).ifPresent(builder::setChangeOutputAddress);
        Optional.ofNullable(myMultiSigPubKey).ifPresent(e -> builder.setMyMultiSigPubKey(ByteString.copyFrom(myMultiSigPubKey)));
        Optional.ofNullable(tempTradePeerNodeAddress).ifPresent(e -> builder.setTempTradePeerNodeAddress(tempTradePeerNodeAddress.toProtoMessage()));
        Optional.ofNullable(makerSignature).ifPresent(e -> builder.setMakerSignature(makerSignature));
        Optional.ofNullable(multisigAddress).ifPresent(e -> builder.setMultisigAddress(multisigAddress));
        Optional.ofNullable(paymentSentMessage).ifPresent(e -> builder.setPaymentSentMessage(paymentSentMessage.toProtoNetworkEnvelope().getPaymentSentMessage()));
        Optional.ofNullable(paymentReceivedMessage).ifPresent(e -> builder.setPaymentReceivedMessage(paymentReceivedMessage.toProtoNetworkEnvelope().getPaymentReceivedMessage()));
        Optional.ofNullable(disputeClosedMessage).ifPresent(e -> builder.setDisputeClosedMessage(disputeClosedMessage.toProtoNetworkEnvelope().getDisputeClosedMessage()));
        return builder.build();
    }

    public static ProcessModel fromProto(protobuf.ProcessModel proto, CoreProtoResolver coreProtoResolver) {
        TradePeer arbitrator = TradePeer.fromProto(proto.getArbitrator(), coreProtoResolver);
        TradePeer maker = TradePeer.fromProto(proto.getMaker(), coreProtoResolver);
        TradePeer taker = TradePeer.fromProto(proto.getTaker(), coreProtoResolver);
        PubKeyRing pubKeyRing = PubKeyRing.fromProto(proto.getPubKeyRing());
        ProcessModel processModel = new ProcessModel(proto.getOfferId(), proto.getAccountId(), pubKeyRing, arbitrator, maker, taker);
        processModel.setChangeOutputValue(proto.getChangeOutputValue());
        processModel.setUseSavingsWallet(proto.getUseSavingsWallet());
        processModel.setFundsNeededForTradeAsLong(proto.getFundsNeededForTradeAsLong());
        processModel.setBuyerPayoutAmountFromMediation(proto.getBuyerPayoutAmountFromMediation());
        processModel.setSellerPayoutAmountFromMediation(proto.getSellerPayoutAmountFromMediation());
        processModel.setDepositsConfirmedMessagesDelivered(proto.getDepositsConfirmedMessagesDelivered());

        // nullable
        processModel.setTakeOfferFeeTxId(ProtoUtil.stringOrNullFromProto(proto.getTakeOfferFeeTxId()));
        processModel.setPayoutTxSignature(ProtoUtil.byteArrayOrNullFromProto(proto.getPayoutTxSignature()));
        List<RawTransactionInput> rawTransactionInputs = proto.getRawTransactionInputsList().isEmpty() ?
                null : proto.getRawTransactionInputsList().stream()
                .map(RawTransactionInput::fromProto).collect(Collectors.toList());
        processModel.setRawTransactionInputs(rawTransactionInputs);
        processModel.setChangeOutputAddress(ProtoUtil.stringOrNullFromProto(proto.getChangeOutputAddress()));
        processModel.setMyMultiSigPubKey(ProtoUtil.byteArrayOrNullFromProto(proto.getMyMultiSigPubKey()));
        processModel.setTempTradePeerNodeAddress(proto.hasTempTradePeerNodeAddress() ? NodeAddress.fromProto(proto.getTempTradePeerNodeAddress()) : null);
        processModel.setMediatedPayoutTxSignature(ProtoUtil.byteArrayOrNullFromProto(proto.getMediatedPayoutTxSignature()));
        processModel.setMakerSignature(proto.getMakerSignature());
        processModel.setMultisigAddress(ProtoUtil.stringOrNullFromProto(proto.getMultisigAddress()));

        String paymentSentMessageStateString = ProtoUtil.stringOrNullFromProto(proto.getPaymentSentMessageState());
        MessageState paymentSentMessageState = ProtoUtil.enumFromProto(MessageState.class, paymentSentMessageStateString);
        processModel.setPaymentSentMessageState(paymentSentMessageState);

        processModel.setPaymentSentMessage(proto.hasPaymentSentMessage() ? PaymentSentMessage.fromProto(proto.getPaymentSentMessage(), Version.getP2PMessageVersion()) : null);
        processModel.setPaymentReceivedMessage(proto.hasPaymentReceivedMessage() ? PaymentReceivedMessage.fromProto(proto.getPaymentReceivedMessage(), Version.getP2PMessageVersion()) : null);
        processModel.setDisputeClosedMessage(proto.hasDisputeClosedMessage() ? DisputeClosedMessage.fromProto(proto.getDisputeClosedMessage(), Version.getP2PMessageVersion()) : null);
        return processModel;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onComplete() {
    }

    public void setTakeOfferFeeTx(MoneroTxWallet takeOfferFeeTx) {
        this.takeOfferFeeTx = takeOfferFeeTx;
        takeOfferFeeTxId = takeOfferFeeTx.getHash();
    }

    @Nullable
    public PaymentAccountPayload getPaymentAccountPayload(String paymentAccountId) {
        PaymentAccount paymentAccount = getUser().getPaymentAccount(paymentAccountId);
        return paymentAccount != null ? paymentAccount.getPaymentAccountPayload() : null;
    }

    public Coin getFundsNeededForTrade() {
        return Coin.valueOf(fundsNeededForTradeAsLong);
    }

    public MoneroTxWallet resolveTakeOfferFeeTx(Trade trade) {
      if (takeOfferFeeTx == null) {
        takeOfferFeeTx = provider.getXmrWalletService().getWallet().getTx(takeOfferFeeTxId);
      }
      return takeOfferFeeTx;
    }

    public NodeAddress getMyNodeAddress() {
        return getP2PService().getAddress();
    }

    void setPaymentSentAckMessage(AckMessage ackMessage) {
        MessageState messageState = ackMessage.isSuccess() ?
                MessageState.ACKNOWLEDGED :
                MessageState.FAILED;
        setPaymentSentMessageState(messageState);
    }

    public void setPaymentSentMessageState(MessageState paymentSentMessageStateProperty) {
        this.paymentSentMessageStateProperty.set(paymentSentMessageStateProperty);
        if (tradeManager != null) {
            tradeManager.requestPersistence();
        }
    }
    
    void setDepositTxSentAckMessage(AckMessage ackMessage) {
        MessageState messageState = ackMessage.isSuccess() ?
                MessageState.ACKNOWLEDGED :
                MessageState.FAILED;
        setDepositTxMessageState(messageState);
    }

    public void setDepositTxMessageState(MessageState messageState) {
        this.depositTxMessageStateProperty.set(messageState);
        if (tradeManager != null) {
            tradeManager.requestPersistence();
        }
    }

    void witnessDebugLog(Trade trade) {
        getAccountAgeWitnessService().getAccountAgeWitnessUtils().witnessDebugLog(trade, null);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Delegates
    ///////////////////////////////////////////////////////////////////////////////////////////
    
    public XmrWalletService getXmrWalletService() {
        return provider.getXmrWalletService();
    }

    public BtcWalletService getBtcWalletService() {
        return provider.getBtcWalletService();
    }

    public AccountAgeWitnessService getAccountAgeWitnessService() {
        return provider.getAccountAgeWitnessService();
    }

    public P2PService getP2PService() {
        return provider.getP2PService();
    }

    public TradeWalletService getTradeWalletService() {
        return provider.getTradeWalletService();
    }

    public User getUser() {
        return provider.getUser();
    }

    public OpenOfferManager getOpenOfferManager() {
        return provider.getOpenOfferManager();
    }

    public ReferralIdService getReferralIdService() {
        return provider.getReferralIdService();
    }

    public FilterManager getFilterManager() {
        return provider.getFilterManager();
    }

    public TradeStatisticsManager getTradeStatisticsManager() {
        return provider.getTradeStatisticsManager();
    }

    public ArbitratorManager getArbitratorManager() {
        return provider.getArbitratorManager();
    }

    public MediatorManager getMediatorManager() {
        return provider.getMediatorManager();
    }

    public RefundAgentManager getRefundAgentManager() {
        return provider.getRefundAgentManager();
    }

    public KeyRing getKeyRing() {
        return provider.getKeyRing();
    }
}
