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

package haveno.core.trade.protocol;

import com.google.protobuf.ByteString;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.PubKeyRing;
import haveno.common.proto.ProtoUtil;
import haveno.common.proto.persistable.PersistablePayload;
import haveno.common.taskrunner.Model;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.filter.FilterManager;
import haveno.core.network.MessageState;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OpenOfferManager;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.proto.CoreProtoResolver;
import haveno.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import haveno.core.support.dispute.mediation.mediator.MediatorManager;
import haveno.core.support.dispute.refund.refundagent.RefundAgentManager;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.trade.messages.TradeMessage;
import haveno.core.trade.statistics.ReferralIdService;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.core.user.User;
import haveno.core.xmr.wallet.BtcWalletService;
import haveno.core.xmr.wallet.TradeWalletService;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.AckMessage;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.model.MoneroTxWallet;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Transaction;

import javax.annotation.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

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
    transient public Throwable error;

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
    private byte[] payoutTxSignature;
    @Nullable
    @Setter
    private byte[] preparedDepositTx; // TODO: remove this unused field
    @Setter
    private boolean useSavingsWallet;
    @Setter
    private long fundsNeededForTrade;

    // that is used to store temp. the peers address when we get an incoming message before the message is verified.
    // After successful verified we copy that over to the trade.tradePeerAddress
    @Nullable
    @Setter
    private NodeAddress tempTradePeerNodeAddress;

    // Added in v.1.1.6
    @Nullable
    @Setter
    private byte[] mediatedPayoutTxSignature;
    @Setter
    private long buyerPayoutAmountFromMediation;
    @Setter
    private long sellerPayoutAmountFromMediation;

    // Added for XMR integration
    @Setter
    transient private TradeMessage tradeMessage;
    @Getter
    @Setter
    private byte[] makerSignature;
    @Nullable
    @Getter
    @Setter
    transient private MoneroTxWallet reserveTx;
    @Getter
    @Setter
    transient private MoneroTxWallet unsignedPayoutTx;
    @Nullable
    @Getter
    @Setter
    private String tradeFeeAddress;
    @Getter
    @Setter
    private String multisigAddress;
    @Getter
    @Setter
    private long tradeProtocolErrorHeight;
    @Getter
    @Setter
    private boolean importMultisigHexScheduled;
    @Getter
    @Setter
    private boolean paymentSentPayoutTxStale;
    private ObjectProperty<Boolean> paymentAccountDecryptedProperty = new SimpleObjectProperty<>(false);
    @Deprecated
    private ObjectProperty<MessageState> paymentSentMessageStatePropertySeller = new SimpleObjectProperty<>(MessageState.UNDEFINED);
    @Deprecated
    private ObjectProperty<MessageState> paymentSentMessageStatePropertyArbitrator = new SimpleObjectProperty<>(MessageState.UNDEFINED);

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
        for (TradePeer peer : getTradePeers()) {
            peer.applyTransient(tradeManager);
        }

        // migrate deprecated fields to new model for v1.0.19
        if (paymentSentMessageStatePropertySeller.get() != MessageState.UNDEFINED && getSeller().getPaymentSentMessageStateProperty().get() == MessageState.UNDEFINED) {
            getSeller().getPaymentSentMessageStateProperty().set(paymentSentMessageStatePropertySeller.get());
            tradeManager.requestPersistence();
        }
        if (paymentSentMessageStatePropertyArbitrator.get() != MessageState.UNDEFINED && getArbitrator().getPaymentSentMessageStateProperty().get() == MessageState.UNDEFINED) {
            getArbitrator().getPaymentSentMessageStateProperty().set(paymentSentMessageStatePropertyArbitrator.get());
            tradeManager.requestPersistence();
        }
    }

    private List<TradePeer> getTradePeers() {
        return Arrays.asList(maker, taker, arbitrator);
    }

    private TradePeer getBuyer() {
        return offer.getDirection() == OfferDirection.BUY ? maker : taker;
    }

    private TradePeer getSeller() {
        return offer.getDirection() == OfferDirection.BUY ? taker : maker;
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
                .setUseSavingsWallet(useSavingsWallet)
                .setFundsNeededForTrade(fundsNeededForTrade)
                .setPaymentSentMessageStateSeller(paymentSentMessageStatePropertySeller.get().name())
                .setPaymentSentMessageStateArbitrator(paymentSentMessageStatePropertyArbitrator.get().name())
                .setBuyerPayoutAmountFromMediation(buyerPayoutAmountFromMediation)
                .setSellerPayoutAmountFromMediation(sellerPayoutAmountFromMediation)
                .setTradeProtocolErrorHeight(tradeProtocolErrorHeight)
                .setImportMultisigHexScheduled(importMultisigHexScheduled)
                .setPaymentSentPayoutTxStale(paymentSentPayoutTxStale);
        Optional.ofNullable(maker).ifPresent(e -> builder.setMaker((protobuf.TradePeer) maker.toProtoMessage()));
        Optional.ofNullable(taker).ifPresent(e -> builder.setTaker((protobuf.TradePeer) taker.toProtoMessage()));
        Optional.ofNullable(arbitrator).ifPresent(e -> builder.setArbitrator((protobuf.TradePeer) arbitrator.toProtoMessage()));
        Optional.ofNullable(payoutTxSignature).ifPresent(e -> builder.setPayoutTxSignature(ByteString.copyFrom(payoutTxSignature)));
        Optional.ofNullable(tempTradePeerNodeAddress).ifPresent(e -> builder.setTempTradePeerNodeAddress(tempTradePeerNodeAddress.toProtoMessage()));
        Optional.ofNullable(mediatedPayoutTxSignature).ifPresent(e -> builder.setMediatedPayoutTxSignature(ByteString.copyFrom(e)));
        Optional.ofNullable(makerSignature).ifPresent(e -> builder.setMakerSignature(ByteString.copyFrom(e)));
        Optional.ofNullable(tradeFeeAddress).ifPresent(e -> builder.setTradeFeeAddress(tradeFeeAddress));
        Optional.ofNullable(multisigAddress).ifPresent(e -> builder.setMultisigAddress(multisigAddress));
        return builder.build();
    }

    public static ProcessModel fromProto(protobuf.ProcessModel proto, CoreProtoResolver coreProtoResolver) {
        TradePeer arbitrator = TradePeer.fromProto(proto.getArbitrator(), coreProtoResolver);
        TradePeer maker = TradePeer.fromProto(proto.getMaker(), coreProtoResolver);
        TradePeer taker = TradePeer.fromProto(proto.getTaker(), coreProtoResolver);
        PubKeyRing pubKeyRing = PubKeyRing.fromProto(proto.getPubKeyRing());
        ProcessModel processModel = new ProcessModel(proto.getOfferId(), proto.getAccountId(), pubKeyRing, arbitrator, maker, taker);
        processModel.setUseSavingsWallet(proto.getUseSavingsWallet());
        processModel.setFundsNeededForTrade(proto.getFundsNeededForTrade());
        processModel.setBuyerPayoutAmountFromMediation(proto.getBuyerPayoutAmountFromMediation());
        processModel.setSellerPayoutAmountFromMediation(proto.getSellerPayoutAmountFromMediation());
        processModel.setTradeProtocolErrorHeight(proto.getTradeProtocolErrorHeight());
        processModel.setImportMultisigHexScheduled(proto.getImportMultisigHexScheduled());
        processModel.setPaymentSentPayoutTxStale(proto.getPaymentSentPayoutTxStale());

        // nullable
        processModel.setPayoutTxSignature(ProtoUtil.byteArrayOrNullFromProto(proto.getPayoutTxSignature()));
        processModel.setTempTradePeerNodeAddress(proto.hasTempTradePeerNodeAddress() ? NodeAddress.fromProto(proto.getTempTradePeerNodeAddress()) : null);
        processModel.setMediatedPayoutTxSignature(ProtoUtil.byteArrayOrNullFromProto(proto.getMediatedPayoutTxSignature()));
        processModel.setMakerSignature(ProtoUtil.byteArrayOrNullFromProto(proto.getMakerSignature()));
        processModel.setTradeFeeAddress(ProtoUtil.stringOrNullFromProto(proto.getTradeFeeAddress()));
        processModel.setMultisigAddress(ProtoUtil.stringOrNullFromProto(proto.getMultisigAddress()));

        // deprecated fields need to be read in order to migrate to new fields
        String paymentSentMessageStateSellerString = ProtoUtil.stringOrNullFromProto(proto.getPaymentSentMessageStateSeller());
        MessageState paymentSentMessageStateSeller = ProtoUtil.enumFromProto(MessageState.class, paymentSentMessageStateSellerString);
        processModel.paymentSentMessageStatePropertySeller.set(paymentSentMessageStateSeller);
        String paymentSentMessageStateArbitratorString = ProtoUtil.stringOrNullFromProto(proto.getPaymentSentMessageStateArbitrator());
        MessageState paymentSentMessageStateArbitrator = ProtoUtil.enumFromProto(MessageState.class, paymentSentMessageStateArbitratorString);
        processModel.paymentSentMessageStatePropertyArbitrator.set(paymentSentMessageStateArbitrator);
        return processModel;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onComplete() {
    }

    @Nullable
    public PaymentAccountPayload getPaymentAccountPayload(String paymentAccountId) {
        PaymentAccount paymentAccount = getUser().getPaymentAccount(paymentAccountId);
        return paymentAccount != null ? paymentAccount.getPaymentAccountPayload() : null;
    }

    public Coin getFundsNeededForTrade() {
        return Coin.valueOf(fundsNeededForTrade);
    }

    public NodeAddress getMyNodeAddress() {
        return getP2PService().getAddress();
    }

    public boolean isPaymentReceivedMessagesReceived() {
        return getArbitrator().isPaymentReceivedMessageReceived() && getBuyer().isPaymentReceivedMessageReceived();
    }

    void setDepositTxSentAckMessage(AckMessage ackMessage) {
        MessageState messageState = ackMessage.isSuccess() ?
                MessageState.ACKNOWLEDGED :
                MessageState.NACKED;
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

    public boolean maybeClearSensitiveData() {
        boolean changed = false;
        for (TradePeer tradingPeer : getTradePeers()) {
            if (tradingPeer.getPaymentAccountPayload() != null || tradingPeer.getContractAsJson() != null) {
                tradingPeer.setPaymentAccountPayload(null);
                tradingPeer.setContractAsJson(null);
                changed = true;
            }
        }
        return changed;
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
