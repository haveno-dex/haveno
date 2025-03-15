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

package haveno.core.trade.protocol;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import haveno.common.app.Version;
import haveno.common.crypto.PubKeyRing;
import haveno.common.proto.ProtoUtil;
import haveno.common.proto.persistable.PersistablePayload;
import haveno.core.account.witness.AccountAgeWitness;
import haveno.core.network.MessageState;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.proto.CoreProtoResolver;
import haveno.core.support.dispute.messages.DisputeClosedMessage;
import haveno.core.trade.TradeManager;
import haveno.core.trade.messages.PaymentReceivedMessage;
import haveno.core.trade.messages.PaymentSentMessage;
import haveno.network.p2p.AckMessage;
import haveno.network.p2p.NodeAddress;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.model.MoneroTxWallet;

import javax.annotation.Nullable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// Fields marked as transient are only used during protocol execution which are based on directMessages so we do not
// persist them.
//todo clean up older fields as well to make most transient
@Slf4j
@Getter
@Setter
public final class TradePeer implements PersistablePayload {
    // Transient/Mutable
    // Added in v1.2.0
    @Setter
    @Nullable
    transient private byte[] delayedPayoutTxSignature;
    @Setter
    @Nullable
    transient private byte[] preparedDepositTx;
    transient private MoneroTxWallet depositTx;
    transient private TradeManager tradeManager;

    // Persistable mutable
    @Nullable
    private NodeAddress nodeAddress;
    @Nullable
    private PubKeyRing pubKeyRing;
    @Nullable
    private String accountId;
    @Nullable
    private String paymentAccountId;
    @Nullable
    private String paymentMethodId;
    @Nullable
    private byte[] paymentAccountPayloadHash;
    @Nullable
    private byte[] encryptedPaymentAccountPayload;
    @Nullable
    private byte[] paymentAccountKey;
    @Nullable
    private PaymentAccountPayload paymentAccountPayload;
    @Nullable
    private String payoutAddressString;
    @Nullable
    private String contractAsJson;
    @Nullable
    private byte[] contractSignature;
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

    // added in v 0.6
    @Nullable
    private byte[] accountAgeWitnessNonce;
    @Nullable
    private byte[] accountAgeWitnessSignature;
    @Getter
    @Setter
    @Nullable
    private AccountAgeWitness accountAgeWitness;
    private long currentDate;

    // Added in v.1.1.6
    @Nullable
    private byte[] mediatedPayoutTxSignature;

    // Added for XMR integration
    @Nullable
    private String reserveTxHash;
    @Nullable
    private String reserveTxHex;
    @Nullable
    private String reserveTxKey;
    @Nullable
    private List<String> reserveTxKeyImages = new ArrayList<>();
    @Nullable
    private String preparedMultisigHex;
    @Nullable
    private String madeMultisigHex;
    @Nullable
    private String exchangedMultisigHex;
    @Nullable
    private String depositTxHash;
    @Nullable
    private String depositTxHex;
    @Nullable
    private String depositTxKey;
    private long depositTxFee;
    private long securityDeposit;
    @Nullable
    @Setter
    private String unsignedPayoutTxHex;
    private long payoutTxFee;
    private long payoutAmount;
    @Nullable
    private String updatedMultisigHex;
    @Deprecated
    private boolean depositsConfirmedMessageAcked;
    
    // We want to indicate the user the state of the message delivery of the payment
    // confirmation messages. We do an automatic re-send in case it was not ACKed yet.
    // To enable that even after restart we persist the state.
    @Setter
    private ObjectProperty<MessageState> depositsConfirmedMessageStateProperty = new SimpleObjectProperty<>(MessageState.UNDEFINED);
    @Setter
    private ObjectProperty<MessageState> paymentSentMessageStateProperty = new SimpleObjectProperty<>(MessageState.UNDEFINED);
    @Setter
    private ObjectProperty<MessageState> paymentReceivedMessageStateProperty = new SimpleObjectProperty<>(MessageState.UNDEFINED);

    public TradePeer() {
    }

    public void applyTransient(TradeManager tradeManager) {
        this.tradeManager = tradeManager;

        // migrate deprecated fields to new model for v1.0.19
        if (depositsConfirmedMessageAcked && depositsConfirmedMessageStateProperty.get() == MessageState.UNDEFINED) {
            depositsConfirmedMessageStateProperty.set(MessageState.ACKNOWLEDGED);
            tradeManager.requestPersistence();
        }
    }

    public BigInteger getDepositTxFee() {
        return BigInteger.valueOf(depositTxFee);
    }

    public void setDepositTxFee(BigInteger depositTxFee) {
        this.depositTxFee = depositTxFee.longValueExact();
    }

    public BigInteger getSecurityDeposit() {
        return BigInteger.valueOf(securityDeposit);
    }

    public void setSecurityDeposit(BigInteger securityDeposit) {
        this.securityDeposit = securityDeposit.longValueExact();
    }

    public BigInteger getPayoutTxFee() {
        return BigInteger.valueOf(payoutTxFee);
    }

    public void setPayoutTxFee(BigInteger payoutTxFee) {
        this.payoutTxFee = payoutTxFee.longValueExact();
    }

    public BigInteger getPayoutAmount() {
        return BigInteger.valueOf(payoutAmount);
    }

    public void setPayoutAmount(BigInteger payoutAmount) {
        this.payoutAmount = payoutAmount.longValueExact();
    }

    void setDepositsConfirmedAckMessage(AckMessage ackMessage) {
        MessageState messageState = ackMessage.isSuccess() ?
                MessageState.ACKNOWLEDGED :
                MessageState.FAILED;
        setDepositsConfirmedMessageState(messageState);
    }

    void setPaymentSentAckMessage(AckMessage ackMessage) {
        MessageState messageState = ackMessage.isSuccess() ?
                MessageState.ACKNOWLEDGED :
                MessageState.FAILED;
        setPaymentSentMessageState(messageState);
    }

    void setPaymentReceivedAckMessage(AckMessage ackMessage) {
        MessageState messageState = ackMessage.isSuccess() ?
                MessageState.ACKNOWLEDGED :
                MessageState.FAILED;
        setPaymentReceivedMessageState(messageState);
    }

    public void setDepositsConfirmedMessageState(MessageState depositsConfirmedMessageStateProperty) {
        this.depositsConfirmedMessageStateProperty.set(depositsConfirmedMessageStateProperty);
        if (tradeManager != null) {
            tradeManager.requestPersistence();
        }
    }

    public void setPaymentSentMessageState(MessageState paymentSentMessageStateProperty) {
        this.paymentSentMessageStateProperty.set(paymentSentMessageStateProperty);
        if (tradeManager != null) {
            tradeManager.requestPersistence();
        }
    }

    public void setPaymentReceivedMessageState(MessageState paymentReceivedMessageStateProperty) {
        this.paymentReceivedMessageStateProperty.set(paymentReceivedMessageStateProperty);
        if (tradeManager != null) {
            tradeManager.requestPersistence();
        }
    }

    public boolean isDepositsConfirmedMessageAcked() {
        return depositsConfirmedMessageStateProperty.get() == MessageState.ACKNOWLEDGED;
    }

    public boolean isPaymentSentMessageAcked() {
        return paymentSentMessageStateProperty.get() == MessageState.ACKNOWLEDGED;
    }

    public boolean isPaymentReceivedMessageReceived() {
        return paymentReceivedMessageStateProperty.get() == MessageState.ACKNOWLEDGED || paymentReceivedMessageStateProperty.get() == MessageState.STORED_IN_MAILBOX;
    }

    @Override
    public Message toProtoMessage() {
        final protobuf.TradePeer.Builder builder = protobuf.TradePeer.newBuilder();
        Optional.ofNullable(nodeAddress).ifPresent(e -> builder.setNodeAddress(nodeAddress.toProtoMessage()));
        Optional.ofNullable(pubKeyRing).ifPresent(e -> builder.setPubKeyRing(pubKeyRing.toProtoMessage()));
        Optional.ofNullable(accountId).ifPresent(builder::setAccountId);
        Optional.ofNullable(paymentAccountId).ifPresent(builder::setPaymentAccountId);
        Optional.ofNullable(paymentMethodId).ifPresent(builder::setPaymentMethodId);
        Optional.ofNullable(paymentAccountPayloadHash).ifPresent(e -> builder.setPaymentAccountPayloadHash(ByteString.copyFrom(paymentAccountPayloadHash)));
        Optional.ofNullable(encryptedPaymentAccountPayload).ifPresent(e -> builder.setEncryptedPaymentAccountPayload(ByteString.copyFrom(e)));
        Optional.ofNullable(paymentAccountKey).ifPresent(e -> builder.setPaymentAccountKey(ByteString.copyFrom(e)));
        Optional.ofNullable(paymentAccountPayload).ifPresent(e -> builder.setPaymentAccountPayload((protobuf.PaymentAccountPayload) e.toProtoMessage()));
        Optional.ofNullable(payoutAddressString).ifPresent(builder::setPayoutAddressString);
        Optional.ofNullable(contractAsJson).ifPresent(builder::setContractAsJson);
        Optional.ofNullable(contractSignature).ifPresent(e -> builder.setContractSignature(ByteString.copyFrom(e)));
        Optional.ofNullable(pubKeyRing).ifPresent(e -> builder.setPubKeyRing(e.toProtoMessage()));
        Optional.ofNullable(accountAgeWitnessNonce).ifPresent(e -> builder.setAccountAgeWitnessNonce(ByteString.copyFrom(e)));
        Optional.ofNullable(accountAgeWitnessSignature).ifPresent(e -> builder.setAccountAgeWitnessSignature(ByteString.copyFrom(e)));
        Optional.ofNullable(accountAgeWitness).ifPresent(e -> builder.setAccountAgeWitness(accountAgeWitness.toProtoAccountAgeWitness()));
        Optional.ofNullable(mediatedPayoutTxSignature).ifPresent(e -> builder.setMediatedPayoutTxSignature(ByteString.copyFrom(e)));
        Optional.ofNullable(paymentSentMessage).ifPresent(e -> builder.setPaymentSentMessage(paymentSentMessage.toProtoNetworkEnvelope().getPaymentSentMessage()));
        Optional.ofNullable(paymentReceivedMessage).ifPresent(e -> builder.setPaymentReceivedMessage(paymentReceivedMessage.toProtoNetworkEnvelope().getPaymentReceivedMessage()));
        Optional.ofNullable(disputeClosedMessage).ifPresent(e -> builder.setDisputeClosedMessage(disputeClosedMessage.toProtoNetworkEnvelope().getDisputeClosedMessage()));
        Optional.ofNullable(reserveTxHash).ifPresent(e -> builder.setReserveTxHash(reserveTxHash));
        Optional.ofNullable(reserveTxHex).ifPresent(e -> builder.setReserveTxHex(reserveTxHex));
        Optional.ofNullable(reserveTxKey).ifPresent(e -> builder.setReserveTxKey(reserveTxKey));
        Optional.ofNullable(reserveTxKeyImages).ifPresent(e -> builder.addAllReserveTxKeyImages(reserveTxKeyImages));
        Optional.ofNullable(preparedMultisigHex).ifPresent(e -> builder.setPreparedMultisigHex(preparedMultisigHex));
        Optional.ofNullable(madeMultisigHex).ifPresent(e -> builder.setMadeMultisigHex(madeMultisigHex));
        Optional.ofNullable(exchangedMultisigHex).ifPresent(e -> builder.setExchangedMultisigHex(exchangedMultisigHex));
        Optional.ofNullable(updatedMultisigHex).ifPresent(e -> builder.setUpdatedMultisigHex(updatedMultisigHex));
        Optional.ofNullable(depositTxHash).ifPresent(e -> builder.setDepositTxHash(depositTxHash));
        Optional.ofNullable(depositTxHex).ifPresent(e -> builder.setDepositTxHex(depositTxHex));
        Optional.ofNullable(depositTxKey).ifPresent(e -> builder.setDepositTxKey(depositTxKey));
        Optional.ofNullable(depositTxFee).ifPresent(e -> builder.setDepositTxFee(depositTxFee));
        Optional.ofNullable(securityDeposit).ifPresent(e -> builder.setSecurityDeposit(securityDeposit));
        Optional.ofNullable(unsignedPayoutTxHex).ifPresent(e -> builder.setUnsignedPayoutTxHex(unsignedPayoutTxHex));
        Optional.ofNullable(payoutTxFee).ifPresent(e -> builder.setPayoutTxFee(payoutTxFee));
        Optional.ofNullable(payoutAmount).ifPresent(e -> builder.setPayoutAmount(payoutAmount));
        builder.setDepositsConfirmedMessageAcked(depositsConfirmedMessageAcked);
        builder.setDepositsConfirmedMessageState(depositsConfirmedMessageStateProperty.get().name());
        builder.setPaymentSentMessageState(paymentSentMessageStateProperty.get().name());
        builder.setPaymentReceivedMessageState(paymentReceivedMessageStateProperty.get().name());

        builder.setCurrentDate(currentDate);
        return builder.build();
    }

    public static TradePeer fromProto(protobuf.TradePeer proto, CoreProtoResolver coreProtoResolver) {
        if (proto.getDefaultInstanceForType().equals(proto)) {
            return null;
        } else {
            TradePeer tradePeer = new TradePeer();
            tradePeer.setNodeAddress(proto.hasNodeAddress() ? NodeAddress.fromProto(proto.getNodeAddress()) : null);
            tradePeer.setPubKeyRing(proto.hasPubKeyRing() ? PubKeyRing.fromProto(proto.getPubKeyRing()) : null);
            tradePeer.setAccountId(ProtoUtil.stringOrNullFromProto(proto.getAccountId()));
            tradePeer.setPaymentAccountId(ProtoUtil.stringOrNullFromProto(proto.getPaymentAccountId()));
            tradePeer.setPaymentMethodId(ProtoUtil.stringOrNullFromProto(proto.getPaymentMethodId()));
            tradePeer.setPaymentAccountPayloadHash(proto.getPaymentAccountPayloadHash().toByteArray());
            tradePeer.setEncryptedPaymentAccountPayload(proto.getEncryptedPaymentAccountPayload().toByteArray());
            tradePeer.setPaymentAccountKey(ProtoUtil.byteArrayOrNullFromProto(proto.getPaymentAccountKey()));
            tradePeer.setPaymentAccountPayload(proto.hasPaymentAccountPayload() ? coreProtoResolver.fromProto(proto.getPaymentAccountPayload()) : null);
            tradePeer.setPayoutAddressString(ProtoUtil.stringOrNullFromProto(proto.getPayoutAddressString()));
            tradePeer.setContractAsJson(ProtoUtil.stringOrNullFromProto(proto.getContractAsJson()));
            tradePeer.setContractSignature(ProtoUtil.byteArrayOrNullFromProto(proto.getContractSignature()));
            tradePeer.setPubKeyRing(proto.hasPubKeyRing() ? PubKeyRing.fromProto(proto.getPubKeyRing()) : null);
            tradePeer.setAccountAgeWitnessNonce(ProtoUtil.byteArrayOrNullFromProto(proto.getAccountAgeWitnessNonce()));
            tradePeer.setAccountAgeWitnessSignature(ProtoUtil.byteArrayOrNullFromProto(proto.getAccountAgeWitnessSignature()));
            protobuf.AccountAgeWitness protoAccountAgeWitness = proto.getAccountAgeWitness();
            tradePeer.setAccountAgeWitness(protoAccountAgeWitness.getHash().isEmpty() ? null : AccountAgeWitness.fromProto(protoAccountAgeWitness));
            tradePeer.setCurrentDate(proto.getCurrentDate());
            tradePeer.setMediatedPayoutTxSignature(ProtoUtil.byteArrayOrNullFromProto(proto.getMediatedPayoutTxSignature()));
            tradePeer.setPaymentSentMessage(proto.hasPaymentSentMessage() ? PaymentSentMessage.fromProto(proto.getPaymentSentMessage(), Version.getP2PMessageVersion()) : null);
            tradePeer.setPaymentReceivedMessage(proto.hasPaymentReceivedMessage() ? PaymentReceivedMessage.fromProto(proto.getPaymentReceivedMessage(), Version.getP2PMessageVersion()) : null);
            tradePeer.setDisputeClosedMessage(proto.hasDisputeClosedMessage() ? DisputeClosedMessage.fromProto(proto.getDisputeClosedMessage(), Version.getP2PMessageVersion()) : null);
            tradePeer.setReserveTxHash(ProtoUtil.stringOrNullFromProto(proto.getReserveTxHash()));
            tradePeer.setReserveTxHex(ProtoUtil.stringOrNullFromProto(proto.getReserveTxHex()));
            tradePeer.setReserveTxKey(ProtoUtil.stringOrNullFromProto(proto.getReserveTxKey()));
            tradePeer.setReserveTxKeyImages(proto.getReserveTxKeyImagesList());
            tradePeer.setPreparedMultisigHex(ProtoUtil.stringOrNullFromProto(proto.getPreparedMultisigHex()));
            tradePeer.setMadeMultisigHex(ProtoUtil.stringOrNullFromProto(proto.getMadeMultisigHex()));
            tradePeer.setExchangedMultisigHex(ProtoUtil.stringOrNullFromProto(proto.getExchangedMultisigHex()));
            tradePeer.setUpdatedMultisigHex(ProtoUtil.stringOrNullFromProto(proto.getUpdatedMultisigHex()));
            tradePeer.setDepositsConfirmedMessageAcked(proto.getDepositsConfirmedMessageAcked());
            tradePeer.setDepositTxHash(ProtoUtil.stringOrNullFromProto(proto.getDepositTxHash()));
            tradePeer.setDepositTxHex(ProtoUtil.stringOrNullFromProto(proto.getDepositTxHex()));
            tradePeer.setDepositTxKey(ProtoUtil.stringOrNullFromProto(proto.getDepositTxKey()));
            tradePeer.setDepositTxFee(BigInteger.valueOf(proto.getDepositTxFee()));
            tradePeer.setSecurityDeposit(BigInteger.valueOf(proto.getSecurityDeposit()));
            tradePeer.setUnsignedPayoutTxHex(ProtoUtil.stringOrNullFromProto(proto.getUnsignedPayoutTxHex()));
            tradePeer.setPayoutTxFee(BigInteger.valueOf(proto.getPayoutTxFee()));
            tradePeer.setPayoutAmount(BigInteger.valueOf(proto.getPayoutAmount()));

            String depositsConfirmedMessageStateString = ProtoUtil.stringOrNullFromProto(proto.getDepositsConfirmedMessageState());
            MessageState depositsConfirmedMessageState = ProtoUtil.enumFromProto(MessageState.class, depositsConfirmedMessageStateString);
            tradePeer.setDepositsConfirmedMessageState(depositsConfirmedMessageState);

            String paymentSentMessageStateString = ProtoUtil.stringOrNullFromProto(proto.getPaymentSentMessageState());
            MessageState paymentSentMessageState = ProtoUtil.enumFromProto(MessageState.class, paymentSentMessageStateString);
            tradePeer.setPaymentSentMessageState(paymentSentMessageState);

            String paymentReceivedMessageStateString = ProtoUtil.stringOrNullFromProto(proto.getPaymentReceivedMessageState());
            MessageState paymentReceivedMessageState = ProtoUtil.enumFromProto(MessageState.class, paymentReceivedMessageStateString);
            tradePeer.setPaymentReceivedMessageState(paymentReceivedMessageState);

            return tradePeer;
        }
    }
}
