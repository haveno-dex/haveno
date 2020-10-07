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

package bisq.core.trade.protocol;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Transaction;

import com.google.protobuf.ByteString;

import bisq.common.crypto.KeyRing;
import bisq.common.crypto.PubKeyRing;
import bisq.common.proto.ProtoUtil;
import bisq.common.proto.persistable.PersistablePayload;
import bisq.common.taskrunner.Model;
import bisq.core.account.witness.AccountAgeWitnessService;
import bisq.core.btc.model.RawTransactionInput;
import bisq.core.btc.wallet.BsqWalletService;
import bisq.core.btc.wallet.BtcWalletService;
import bisq.core.btc.wallet.TradeWalletService;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.dao.DaoFacade;
import bisq.core.filter.FilterManager;
import bisq.core.network.MessageState;
import bisq.core.offer.Offer;
import bisq.core.offer.OfferPayload;
import bisq.core.offer.OpenOfferManager;
import bisq.core.offer.OfferPayload.Direction;
import bisq.core.payment.PaymentAccount;
import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.proto.CoreProtoResolver;
import bisq.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import bisq.core.support.dispute.mediation.mediator.MediatorManager;
import bisq.core.support.dispute.refund.refundagent.RefundAgentManager;
import bisq.core.trade.ArbitratorTrade;
import bisq.core.trade.MakerTrade;
import bisq.core.trade.TakerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.TradeManager;
import bisq.core.trade.messages.TradeMessage;
import bisq.core.trade.statistics.ReferralIdService;
import bisq.core.trade.statistics.TradeStatisticsManager;
import bisq.core.user.User;
import bisq.network.p2p.AckMessage;
import bisq.network.p2p.DecryptedMessageWithPubKey;
import bisq.network.p2p.MailboxMessage;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.P2PService;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.model.MoneroTxWallet;

// Fields marked as transient are only used during protocol execution which are based on directMessages so we do not
// persist them.
//todo clean up older fields as well to make most transient

@Getter
@Slf4j
public class ProcessModel implements Model, PersistablePayload {
    // Transient/Immutable (net set in constructor so they are not final, but at init)
    transient private TradeManager tradeManager;
    transient private Trade trade;
    transient private OpenOfferManager openOfferManager;
    transient private BtcWalletService btcWalletService;
    transient private XmrWalletService xmrWalletService;
    transient private BsqWalletService bsqWalletService;
    transient private TradeWalletService tradeWalletService;
    transient private DaoFacade daoFacade;
    transient private Offer offer;
    transient private User user;
    transient private FilterManager filterManager;
    transient private AccountAgeWitnessService accountAgeWitnessService;
    transient private TradeStatisticsManager tradeStatisticsManager;
    transient private ArbitratorManager arbitratorManager;
    transient private MediatorManager mediatorManager;
    transient private RefundAgentManager refundAgentManager;
    transient private KeyRing keyRing;
    transient private P2PService p2PService;
    transient private ReferralIdService referralIdService;

    // Transient/Mutable
    @Getter
    transient private MoneroTxWallet takeOfferFeeTx;
    @Setter
    transient private TradeMessage tradeMessage;
    @Setter
    transient private DecryptedMessageWithPubKey decryptedMessageWithPubKey;

    // Added in v1.2.0
    @Setter
    @Nullable
    transient private byte[] delayedPayoutTxSignature;
    @Setter
    @Nullable
    transient private Transaction preparedDelayedPayoutTx;

    // Persistable Immutable (private setter only used by PB method)
    private TradingPeer maker = new TradingPeer();
    private TradingPeer taker = new TradingPeer();
    private TradingPeer arbitrator = new TradingPeer();
    private String offerId;
    private String accountId;
    private PubKeyRing pubKeyRing;

    // Persistable Mutable
    @Nullable
    @Setter()
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
    // After successful verified we copy that over to the trade.tradingPeerAddress
    @Nullable
    @Setter
    private NodeAddress tempMakerNodeAddress;
    @Nullable
    @Setter
    private NodeAddress tempTakerNodeAddress;
    @Nullable
    @Setter
    private NodeAddress tempArbitratorNodeAddress;

    // Added in v.1.1.6
    @Nullable
    @Setter
    private byte[] mediatedPayoutTxSignature;
    @Setter
    private long buyerPayoutAmountFromMediation;
    @Setter
    private long sellerPayoutAmountFromMediation;
    
    // Added for XMR integration
    @Nullable
    @Getter
    @Setter
    private String preparedMultisigHex;
    @Nullable
    @Getter
    @Setter
    private String madeMultisigHex;
    @Nullable
    @Getter
    @Setter
    private boolean multisigSetupComplete;
    @Nullable
    @Getter
    @Setter
    private boolean makerReadyToFundMultisig;
    @Getter
    @Setter
    private boolean multisigDepositInitiated;
    @Nullable
    @Setter
    private String makerPreparedDepositTxId;
    @Nullable
    @Setter
    private String takerPreparedDepositTxId;
    @Nullable
    transient private MoneroTxWallet buyerSignedPayoutTx;


    // The only trade message where we want to indicate the user the state of the message delivery is the
    // CounterCurrencyTransferStartedMessage. We persist the state with the processModel.
    @Setter
    private ObjectProperty<MessageState> paymentStartedMessageStateProperty = new SimpleObjectProperty<>(MessageState.UNDEFINED);

    public ProcessModel() {
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.ProcessModel toProtoMessage() {
        final protobuf.ProcessModel.Builder builder = protobuf.ProcessModel.newBuilder()
                .setOfferId(offerId)
                .setAccountId(accountId)
                .setPubKeyRing(pubKeyRing.toProtoMessage())
                .setChangeOutputValue(changeOutputValue)
                .setUseSavingsWallet(useSavingsWallet)
                .setFundsNeededForTradeAsLong(fundsNeededForTradeAsLong)
                .setPaymentStartedMessageState(paymentStartedMessageStateProperty.get().name())
                .setBuyerPayoutAmountFromMediation(buyerPayoutAmountFromMediation)
                .setSellerPayoutAmountFromMediation(sellerPayoutAmountFromMediation);
        Optional.ofNullable(maker).ifPresent(e -> builder.setMaker((protobuf.TradingPeer) maker.toProtoMessage()));
        Optional.ofNullable(taker).ifPresent(e -> builder.setTaker((protobuf.TradingPeer) taker.toProtoMessage()));
        Optional.ofNullable(arbitrator).ifPresent(e -> builder.setArbitrator((protobuf.TradingPeer) arbitrator.toProtoMessage()));
        Optional.ofNullable(takeOfferFeeTxId).ifPresent(builder::setTakeOfferFeeTxId);
        Optional.ofNullable(payoutTxSignature).ifPresent(e -> builder.setPayoutTxSignature(ByteString.copyFrom(payoutTxSignature)));
        Optional.ofNullable(makerPreparedDepositTxId).ifPresent(e -> builder.setMakerPreparedDepositTxId(makerPreparedDepositTxId));
        Optional.ofNullable(takerPreparedDepositTxId).ifPresent(e -> builder.setTakerPreparedDepositTxId(takerPreparedDepositTxId));
        Optional.ofNullable(rawTransactionInputs).ifPresent(e -> builder.addAllRawTransactionInputs(ProtoUtil.collectionToProto(rawTransactionInputs, protobuf.RawTransactionInput.class)));
        Optional.ofNullable(changeOutputAddress).ifPresent(builder::setChangeOutputAddress);
        Optional.ofNullable(myMultiSigPubKey).ifPresent(e -> builder.setMyMultiSigPubKey(ByteString.copyFrom(myMultiSigPubKey)));
        Optional.ofNullable(tempMakerNodeAddress).ifPresent(e -> builder.setTempMakerNodeAddress(tempMakerNodeAddress.toProtoMessage()));
        Optional.ofNullable(tempTakerNodeAddress).ifPresent(e -> builder.setTempTakerNodeAddress(tempTakerNodeAddress.toProtoMessage()));
        Optional.ofNullable(tempArbitratorNodeAddress).ifPresent(e -> builder.setTempArbitratorNodeAddress(tempArbitratorNodeAddress.toProtoMessage()));
        Optional.ofNullable(preparedMultisigHex).ifPresent(e -> builder.setPreparedMultisigHex(preparedMultisigHex));
        Optional.ofNullable(madeMultisigHex).ifPresent(e -> builder.setMadeMultisigHex(madeMultisigHex));
        Optional.ofNullable(multisigSetupComplete).ifPresent(e -> builder.setMultisigSetupComplete(multisigSetupComplete));
        Optional.ofNullable(makerReadyToFundMultisig).ifPresent(e -> builder.setMakerReadyToFundMultisig(makerReadyToFundMultisig));
        Optional.ofNullable(multisigDepositInitiated).ifPresent(e -> builder.setMultisigSetupComplete(multisigDepositInitiated));
        return builder.build();
    }

    public static ProcessModel fromProto(protobuf.ProcessModel proto, CoreProtoResolver coreProtoResolver) {
        ProcessModel processModel = new ProcessModel();
        processModel.setMaker(proto.hasMaker() ? TradingPeer.fromProto(proto.getMaker(), coreProtoResolver) : null);
        processModel.setTaker(proto.hasTaker() ? TradingPeer.fromProto(proto.getTaker(), coreProtoResolver) : null);
        processModel.setArbitrator(proto.hasMaker() ? TradingPeer.fromProto(proto.getArbitrator(), coreProtoResolver) : null);
        processModel.setOfferId(proto.getOfferId());
        processModel.setAccountId(proto.getAccountId());
        processModel.setPubKeyRing(PubKeyRing.fromProto(proto.getPubKeyRing()));
        processModel.setChangeOutputValue(proto.getChangeOutputValue());
        processModel.setUseSavingsWallet(proto.getUseSavingsWallet());
        processModel.setFundsNeededForTradeAsLong(proto.getFundsNeededForTradeAsLong());
        processModel.setBuyerPayoutAmountFromMediation(proto.getBuyerPayoutAmountFromMediation());
        processModel.setSellerPayoutAmountFromMediation(proto.getSellerPayoutAmountFromMediation());

        // nullable
        processModel.setTakeOfferFeeTxId(ProtoUtil.stringOrNullFromProto(proto.getTakeOfferFeeTxId()));
        processModel.setPayoutTxSignature(ProtoUtil.byteArrayOrNullFromProto(proto.getPayoutTxSignature()));
        List<RawTransactionInput> rawTransactionInputs = proto.getRawTransactionInputsList().isEmpty() ?
                null : proto.getRawTransactionInputsList().stream()
                .map(RawTransactionInput::fromProto).collect(Collectors.toList());
        processModel.setRawTransactionInputs(rawTransactionInputs);
        processModel.setChangeOutputAddress(ProtoUtil.stringOrNullFromProto(proto.getChangeOutputAddress()));
        processModel.setMyMultiSigPubKey(ProtoUtil.byteArrayOrNullFromProto(proto.getMyMultiSigPubKey()));
        processModel.setTempMakerNodeAddress(proto.hasTempMakerNodeAddress() ? NodeAddress.fromProto(proto.getTempMakerNodeAddress()) : null);
        processModel.setTempTakerNodeAddress(proto.hasTempTakerNodeAddress() ? NodeAddress.fromProto(proto.getTempTakerNodeAddress()) : null);
        processModel.setTempArbitratorNodeAddress(proto.hasTempArbitratorNodeAddress() ? NodeAddress.fromProto(proto.getTempArbitratorNodeAddress()) : null);
        String paymentStartedMessageState = proto.getPaymentStartedMessageState().isEmpty() ? MessageState.UNDEFINED.name() : proto.getPaymentStartedMessageState();
        ObjectProperty<MessageState> paymentStartedMessageStateProperty = processModel.getPaymentStartedMessageStateProperty();
        paymentStartedMessageStateProperty.set(ProtoUtil.enumFromProto(MessageState.class, paymentStartedMessageState));
        processModel.setMediatedPayoutTxSignature(ProtoUtil.byteArrayOrNullFromProto(proto.getMediatedPayoutTxSignature()));
        processModel.setPreparedMultisigHex(ProtoUtil.stringOrNullFromProto(proto.getPreparedMultisigHex()));
        processModel.setMadeMultisigHex(ProtoUtil.stringOrNullFromProto(proto.getMadeMultisigHex()));
        processModel.setMultisigSetupComplete(proto.getMultisigSetupComplete());
        processModel.setMakerReadyToFundMultisig(proto.getMakerReadyToFundMultisig());
        processModel.setMultisigDepositInitiated(proto.getMultisigDepositInitiated());
        processModel.setMakerPreparedDepositTxId(proto.getMakerPreparedDepositTxId());
        processModel.setTakerPreparedDepositTxId(proto.getTakerPreparedDepositTxId());
        return processModel;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void onAllServicesInitialized(Offer offer,
                                         TradeManager tradeManager,
                                         Trade trade,
                                         OpenOfferManager openOfferManager,
                                         P2PService p2PService,
                                         BtcWalletService walletService,
                                         XmrWalletService xmrWalletService,
                                         BsqWalletService bsqWalletService,
                                         TradeWalletService tradeWalletService,
                                         DaoFacade daoFacade,
                                         ReferralIdService referralIdService,
                                         User user,
                                         FilterManager filterManager,
                                         AccountAgeWitnessService accountAgeWitnessService,
                                         TradeStatisticsManager tradeStatisticsManager,
                                         ArbitratorManager arbitratorManager,
                                         MediatorManager mediatorManager,
                                         RefundAgentManager refundAgentManager,
                                         KeyRing keyRing,
                                         boolean useSavingsWallet,
                                         Coin fundsNeededForTrade) {
        this.offer = offer;
        this.tradeManager = tradeManager;
        this.trade = trade;
        this.openOfferManager = openOfferManager;
        this.btcWalletService = walletService;
        this.xmrWalletService = xmrWalletService;
        this.bsqWalletService = bsqWalletService;
        this.tradeWalletService = tradeWalletService;
        this.daoFacade = daoFacade;
        this.referralIdService = referralIdService;
        this.user = user;
        this.filterManager = filterManager;
        this.accountAgeWitnessService = accountAgeWitnessService;
        this.tradeStatisticsManager = tradeStatisticsManager;
        this.arbitratorManager = arbitratorManager;
        this.mediatorManager = mediatorManager;
        this.refundAgentManager = refundAgentManager;
        this.keyRing = keyRing;
        this.p2PService = p2PService;
        this.useSavingsWallet = useSavingsWallet;

        fundsNeededForTradeAsLong = fundsNeededForTrade.value;
        offerId = offer.getId();
        accountId = user.getAccountId();
        pubKeyRing = keyRing.getPubKeyRing();
    }

    public void removeMailboxMessageAfterProcessing(Trade trade) {
        if (tradeMessage instanceof MailboxMessage &&
                decryptedMessageWithPubKey != null &&
                decryptedMessageWithPubKey.getNetworkEnvelope().equals(tradeMessage)) {
            log.debug("Remove decryptedMsgWithPubKey from P2P network. decryptedMsgWithPubKey = " + decryptedMessageWithPubKey);
            p2PService.removeEntryFromMailbox(decryptedMessageWithPubKey);
            trade.removeDecryptedMessageWithPubKey(decryptedMessageWithPubKey);
        }
    }

    @Override
    public void persist() {
        log.warn("persist is not implemented in that class");
    }

    @Override
    public void onComplete() {
    }

    public void setTakeOfferFeeTx(MoneroTxWallet takeOfferFeeTx) {
        this.takeOfferFeeTx = takeOfferFeeTx;
        takeOfferFeeTxId = takeOfferFeeTx.getHash();
    }

    @Nullable
    public PaymentAccountPayload getPaymentAccountPayload(Trade trade) {
        PaymentAccount paymentAccount;
        if (trade instanceof MakerTrade)
            paymentAccount = user.getPaymentAccount(offer.getMakerPaymentAccountId());
        else
            paymentAccount = user.getPaymentAccount(trade.getTakerPaymentAccountId());
        return paymentAccount != null ? paymentAccount.getPaymentAccountPayload() : null;
    }

    public Coin getFundsNeededForTradeAsLong() {
        return Coin.valueOf(fundsNeededForTradeAsLong);
    }

    public MoneroTxWallet resolveTakeOfferFeeTx(Trade trade) {
        if (takeOfferFeeTx == null) {
          takeOfferFeeTx = xmrWalletService.getWallet().getTx(takeOfferFeeTxId);
        }
        return takeOfferFeeTx;
    }

    public NodeAddress getMyNodeAddress() {
        return p2PService.getAddress();
    }

    void setPaymentStartedAckMessage(AckMessage ackMessage) {
        if (ackMessage.isSuccess()) {
            setPaymentStartedMessageState(MessageState.ACKNOWLEDGED);
        } else {
            setPaymentStartedMessageState(MessageState.FAILED);
        }
    }

    public void setPaymentStartedMessageState(MessageState paymentStartedMessageStateProperty) {
        this.paymentStartedMessageStateProperty.set(paymentStartedMessageStateProperty);
    }
    
    public void setTradingPeer(TradingPeer peer) {
      if (trade instanceof MakerTrade) taker = peer;
      else if (trade instanceof TakerTrade) maker = peer;
      else throw new RuntimeException("Must be maker or taker to set trading peer");
    }
    
    public TradingPeer getTradingPeer() {
      if (trade instanceof MakerTrade) return taker;
      else if (trade instanceof TakerTrade) return maker;
      else if (trade instanceof ArbitratorTrade) return null;
      else if (trade == null) throw new RuntimeException("Cannot get trading peer because trade is null");
      else throw new RuntimeException("Unknown trade type: " + trade.getClass().getName());
    }
    
    public void setTempTradingPeerNodeAddress(NodeAddress peerAddress) {
      if (trade instanceof MakerTrade) tempTakerNodeAddress = peerAddress;
      else if (trade instanceof TakerTrade) tempMakerNodeAddress = peerAddress;
      else throw new RuntimeException("Must be maker or taker to set peer address");
    }
    
    public NodeAddress getTempTradingPeerNodeAddress() {
      if (trade instanceof MakerTrade) return tempTakerNodeAddress;
      else if (trade instanceof TakerTrade) return tempMakerNodeAddress;
      else if (trade instanceof ArbitratorTrade) return null;
      else throw new RuntimeException("Unknown trade type: " + trade.getClass().getName());
    }

    private void setMaker(TradingPeer maker) {
      this.maker = maker;
    }
    
    private void setTaker(TradingPeer taker) {
      this.taker = taker;
    }
    
    private void setArbitrator(TradingPeer arbitrator) {
      this.arbitrator = arbitrator;
    }

    private void setOfferId(String offerId) {
        this.offerId = offerId;
    }

    private void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    private void setPubKeyRing(PubKeyRing pubKeyRing) {
        this.pubKeyRing = pubKeyRing;
    }

    void logTrade(Trade trade) {
        accountAgeWitnessService.witnessDebugLog(trade, null);
    }

    public void setBuyerSignedPayoutTx(MoneroTxWallet buyerSignedPayoutTx) {
        this.buyerSignedPayoutTx = buyerSignedPayoutTx;
    }
    
    @Nullable
    public MoneroTxWallet getBuyerSignedPayoutTx() {
    	return buyerSignedPayoutTx;
    }
}
