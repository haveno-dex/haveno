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
import com.google.protobuf.Message;
import haveno.common.crypto.PubKeyRing;
import haveno.common.proto.ProtoUtil;
import haveno.common.proto.persistable.PersistablePayload;
import haveno.core.account.witness.AccountAgeWitness;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.proto.CoreProtoResolver;
import haveno.core.xmr.model.RawTransactionInput;
import haveno.network.p2p.NodeAddress;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import monero.daemon.model.MoneroTx;
import javax.annotation.Nullable;

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
    transient private MoneroTx depositTx;

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
    private String contractSignature;
    @Nullable
    private byte[] signature;
    @Nullable
    private byte[] multiSigPubKey;
    @Nullable
    private List<RawTransactionInput> rawTransactionInputs;
    private long changeOutputValue;
    @Nullable
    private String changeOutputAddress;

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
    private long securityDeposit;
    @Nullable
    private String updatedMultisigHex;
    
    public TradePeer() {
    }

    public BigInteger getSecurityDeposit() {
        return BigInteger.valueOf(securityDeposit);
    }

    public void setSecurityDeposit(BigInteger securityDeposit) {
        this.securityDeposit = securityDeposit.longValueExact();
    }

    @Override
    public Message toProtoMessage() {
        final protobuf.TradePeer.Builder builder = protobuf.TradePeer.newBuilder()
                .setChangeOutputValue(changeOutputValue);
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
        Optional.ofNullable(contractSignature).ifPresent(builder::setContractSignature);
        Optional.ofNullable(signature).ifPresent(e -> builder.setSignature(ByteString.copyFrom(e)));
        Optional.ofNullable(pubKeyRing).ifPresent(e -> builder.setPubKeyRing(e.toProtoMessage()));
        Optional.ofNullable(multiSigPubKey).ifPresent(e -> builder.setMultiSigPubKey(ByteString.copyFrom(e)));
        Optional.ofNullable(rawTransactionInputs).ifPresent(e -> builder.addAllRawTransactionInputs(ProtoUtil.collectionToProto(e, protobuf.RawTransactionInput.class)));
        Optional.ofNullable(changeOutputAddress).ifPresent(builder::setChangeOutputAddress);
        Optional.ofNullable(accountAgeWitnessNonce).ifPresent(e -> builder.setAccountAgeWitnessNonce(ByteString.copyFrom(e)));
        Optional.ofNullable(accountAgeWitnessSignature).ifPresent(e -> builder.setAccountAgeWitnessSignature(ByteString.copyFrom(e)));
        Optional.ofNullable(accountAgeWitness).ifPresent(e -> builder.setAccountAgeWitness(accountAgeWitness.toProtoAccountAgeWitness()));
        Optional.ofNullable(mediatedPayoutTxSignature).ifPresent(e -> builder.setMediatedPayoutTxSignature(ByteString.copyFrom(e)));
        Optional.ofNullable(reserveTxHash).ifPresent(e -> builder.setReserveTxHash(reserveTxHash));
        Optional.ofNullable(reserveTxHex).ifPresent(e -> builder.setReserveTxHex(reserveTxHex));
        Optional.ofNullable(reserveTxKey).ifPresent(e -> builder.setReserveTxKey(reserveTxKey));
        Optional.ofNullable(reserveTxKeyImages).ifPresent(e -> builder.addAllReserveTxKeyImages(reserveTxKeyImages));
        Optional.ofNullable(preparedMultisigHex).ifPresent(e -> builder.setPreparedMultisigHex(preparedMultisigHex));
        Optional.ofNullable(madeMultisigHex).ifPresent(e -> builder.setMadeMultisigHex(madeMultisigHex));
        Optional.ofNullable(exchangedMultisigHex).ifPresent(e -> builder.setExchangedMultisigHex(exchangedMultisigHex));
        Optional.ofNullable(depositTxHash).ifPresent(e -> builder.setDepositTxHash(depositTxHash));
        Optional.ofNullable(depositTxHex).ifPresent(e -> builder.setDepositTxHex(depositTxHex));
        Optional.ofNullable(depositTxKey).ifPresent(e -> builder.setDepositTxKey(depositTxKey));
        Optional.ofNullable(securityDeposit).ifPresent(e -> builder.setSecurityDeposit(securityDeposit));
        Optional.ofNullable(updatedMultisigHex).ifPresent(e -> builder.setUpdatedMultisigHex(updatedMultisigHex));

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
            tradePeer.setChangeOutputValue(proto.getChangeOutputValue());
            tradePeer.setAccountId(ProtoUtil.stringOrNullFromProto(proto.getAccountId()));
            tradePeer.setPaymentAccountId(ProtoUtil.stringOrNullFromProto(proto.getPaymentAccountId()));
            tradePeer.setPaymentMethodId(ProtoUtil.stringOrNullFromProto(proto.getPaymentMethodId()));
            tradePeer.setPaymentAccountPayloadHash(proto.getPaymentAccountPayloadHash().toByteArray());
            tradePeer.setEncryptedPaymentAccountPayload(proto.getEncryptedPaymentAccountPayload().toByteArray());
            tradePeer.setPaymentAccountKey(ProtoUtil.byteArrayOrNullFromProto(proto.getPaymentAccountKey()));
            tradePeer.setPaymentAccountPayload(proto.hasPaymentAccountPayload() ? coreProtoResolver.fromProto(proto.getPaymentAccountPayload()) : null);
            tradePeer.setPayoutAddressString(ProtoUtil.stringOrNullFromProto(proto.getPayoutAddressString()));
            tradePeer.setContractAsJson(ProtoUtil.stringOrNullFromProto(proto.getContractAsJson()));
            tradePeer.setContractSignature(ProtoUtil.stringOrNullFromProto(proto.getContractSignature()));
            tradePeer.setSignature(ProtoUtil.byteArrayOrNullFromProto(proto.getSignature()));
            tradePeer.setPubKeyRing(proto.hasPubKeyRing() ? PubKeyRing.fromProto(proto.getPubKeyRing()) : null);
            tradePeer.setMultiSigPubKey(ProtoUtil.byteArrayOrNullFromProto(proto.getMultiSigPubKey()));
            List<RawTransactionInput> rawTransactionInputs = proto.getRawTransactionInputsList().isEmpty() ?
                    null :
                    proto.getRawTransactionInputsList().stream()
                            .map(RawTransactionInput::fromProto)
                            .collect(Collectors.toList());
            tradePeer.setRawTransactionInputs(rawTransactionInputs);
            tradePeer.setChangeOutputAddress(ProtoUtil.stringOrNullFromProto(proto.getChangeOutputAddress()));
            tradePeer.setAccountAgeWitnessNonce(ProtoUtil.byteArrayOrNullFromProto(proto.getAccountAgeWitnessNonce()));
            tradePeer.setAccountAgeWitnessSignature(ProtoUtil.byteArrayOrNullFromProto(proto.getAccountAgeWitnessSignature()));
            protobuf.AccountAgeWitness protoAccountAgeWitness = proto.getAccountAgeWitness();
            tradePeer.setAccountAgeWitness(protoAccountAgeWitness.getHash().isEmpty() ? null : AccountAgeWitness.fromProto(protoAccountAgeWitness));
            tradePeer.setCurrentDate(proto.getCurrentDate());
            tradePeer.setMediatedPayoutTxSignature(ProtoUtil.byteArrayOrNullFromProto(proto.getMediatedPayoutTxSignature()));
            tradePeer.setReserveTxHash(ProtoUtil.stringOrNullFromProto(proto.getReserveTxHash()));
            tradePeer.setReserveTxHex(ProtoUtil.stringOrNullFromProto(proto.getReserveTxHex()));
            tradePeer.setReserveTxKey(ProtoUtil.stringOrNullFromProto(proto.getReserveTxKey()));
            tradePeer.setReserveTxKeyImages(proto.getReserveTxKeyImagesList());
            tradePeer.setPreparedMultisigHex(ProtoUtil.stringOrNullFromProto(proto.getPreparedMultisigHex()));
            tradePeer.setMadeMultisigHex(ProtoUtil.stringOrNullFromProto(proto.getMadeMultisigHex()));
            tradePeer.setExchangedMultisigHex(ProtoUtil.stringOrNullFromProto(proto.getExchangedMultisigHex()));
            tradePeer.setDepositTxHash(ProtoUtil.stringOrNullFromProto(proto.getDepositTxHash()));
            tradePeer.setDepositTxHex(ProtoUtil.stringOrNullFromProto(proto.getDepositTxHex()));
            tradePeer.setDepositTxKey(ProtoUtil.stringOrNullFromProto(proto.getDepositTxKey()));
            tradePeer.setSecurityDeposit(BigInteger.valueOf(proto.getSecurityDeposit()));
            tradePeer.setUpdatedMultisigHex(ProtoUtil.stringOrNullFromProto(proto.getUpdatedMultisigHex()));
            return tradePeer;
        }
    }
}
