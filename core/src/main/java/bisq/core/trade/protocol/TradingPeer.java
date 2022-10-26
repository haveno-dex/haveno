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

package bisq.core.trade.protocol;

import bisq.core.btc.model.RawTransactionInput;
import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.proto.CoreProtoResolver;
import bisq.network.p2p.NodeAddress;
import bisq.common.crypto.PubKeyRing;
import bisq.common.proto.ProtoUtil;
import bisq.common.proto.persistable.PersistablePayload;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
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
public final class TradingPeer implements PersistablePayload {
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
    @Nullable
    private String updatedMultisigHex;
    
    public TradingPeer() {
    }

    @Override
    public Message toProtoMessage() {
        final protobuf.TradingPeer.Builder builder = protobuf.TradingPeer.newBuilder()
                .setChangeOutputValue(changeOutputValue)
                .addAllReserveTxKeyImages(reserveTxKeyImages);
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
        Optional.ofNullable(mediatedPayoutTxSignature).ifPresent(e -> builder.setMediatedPayoutTxSignature(ByteString.copyFrom(e)));
        Optional.ofNullable(reserveTxHash).ifPresent(e -> builder.setReserveTxHash(reserveTxHash));
        Optional.ofNullable(reserveTxHex).ifPresent(e -> builder.setReserveTxHex(reserveTxHex));
        Optional.ofNullable(reserveTxKey).ifPresent(e -> builder.setReserveTxKey(reserveTxKey));
        Optional.ofNullable(preparedMultisigHex).ifPresent(e -> builder.setPreparedMultisigHex(preparedMultisigHex));
        Optional.ofNullable(madeMultisigHex).ifPresent(e -> builder.setMadeMultisigHex(madeMultisigHex));
        Optional.ofNullable(exchangedMultisigHex).ifPresent(e -> builder.setExchangedMultisigHex(exchangedMultisigHex));
        Optional.ofNullable(depositTxHash).ifPresent(e -> builder.setDepositTxHash(depositTxHash));
        Optional.ofNullable(depositTxHex).ifPresent(e -> builder.setDepositTxHex(depositTxHex));
        Optional.ofNullable(depositTxKey).ifPresent(e -> builder.setDepositTxKey(depositTxKey));
        Optional.ofNullable(updatedMultisigHex).ifPresent(e -> builder.setUpdatedMultisigHex(updatedMultisigHex));

        builder.setCurrentDate(currentDate);
        return builder.build();
    }

    public static TradingPeer fromProto(protobuf.TradingPeer proto, CoreProtoResolver coreProtoResolver) {
        if (proto.getDefaultInstanceForType().equals(proto)) {
            return null;
        } else {
            TradingPeer tradingPeer = new TradingPeer();
            tradingPeer.setNodeAddress(proto.hasNodeAddress() ? NodeAddress.fromProto(proto.getNodeAddress()) : null);
            tradingPeer.setPubKeyRing(proto.hasPubKeyRing() ? PubKeyRing.fromProto(proto.getPubKeyRing()) : null);
            tradingPeer.setChangeOutputValue(proto.getChangeOutputValue());
            tradingPeer.setAccountId(ProtoUtil.stringOrNullFromProto(proto.getAccountId()));
            tradingPeer.setPaymentAccountId(ProtoUtil.stringOrNullFromProto(proto.getPaymentAccountId()));
            tradingPeer.setPaymentMethodId(ProtoUtil.stringOrNullFromProto(proto.getPaymentMethodId()));
            tradingPeer.setPaymentAccountPayloadHash(proto.getPaymentAccountPayloadHash().toByteArray());
            tradingPeer.setEncryptedPaymentAccountPayload(proto.getEncryptedPaymentAccountPayload().toByteArray());
            tradingPeer.setPaymentAccountKey(ProtoUtil.byteArrayOrNullFromProto(proto.getPaymentAccountKey()));
            tradingPeer.setPaymentAccountPayload(proto.hasPaymentAccountPayload() ? coreProtoResolver.fromProto(proto.getPaymentAccountPayload()) : null);
            tradingPeer.setPayoutAddressString(ProtoUtil.stringOrNullFromProto(proto.getPayoutAddressString()));
            tradingPeer.setContractAsJson(ProtoUtil.stringOrNullFromProto(proto.getContractAsJson()));
            tradingPeer.setContractSignature(ProtoUtil.stringOrNullFromProto(proto.getContractSignature()));
            tradingPeer.setSignature(ProtoUtil.byteArrayOrNullFromProto(proto.getSignature()));
            tradingPeer.setPubKeyRing(proto.hasPubKeyRing() ? PubKeyRing.fromProto(proto.getPubKeyRing()) : null);
            tradingPeer.setMultiSigPubKey(ProtoUtil.byteArrayOrNullFromProto(proto.getMultiSigPubKey()));
            List<RawTransactionInput> rawTransactionInputs = proto.getRawTransactionInputsList().isEmpty() ?
                    null :
                    proto.getRawTransactionInputsList().stream()
                            .map(RawTransactionInput::fromProto)
                            .collect(Collectors.toList());
            tradingPeer.setRawTransactionInputs(rawTransactionInputs);
            tradingPeer.setChangeOutputAddress(ProtoUtil.stringOrNullFromProto(proto.getChangeOutputAddress()));
            tradingPeer.setAccountAgeWitnessNonce(ProtoUtil.byteArrayOrNullFromProto(proto.getAccountAgeWitnessNonce()));
            tradingPeer.setAccountAgeWitnessSignature(ProtoUtil.byteArrayOrNullFromProto(proto.getAccountAgeWitnessSignature()));
            tradingPeer.setCurrentDate(proto.getCurrentDate());
            tradingPeer.setMediatedPayoutTxSignature(ProtoUtil.byteArrayOrNullFromProto(proto.getMediatedPayoutTxSignature()));
            tradingPeer.setReserveTxHash(ProtoUtil.stringOrNullFromProto(proto.getReserveTxHash()));
            tradingPeer.setReserveTxHex(ProtoUtil.stringOrNullFromProto(proto.getReserveTxHex()));
            tradingPeer.setReserveTxKey(ProtoUtil.stringOrNullFromProto(proto.getReserveTxKey()));
            tradingPeer.setReserveTxKeyImages(proto.getReserveTxKeyImagesList());
            tradingPeer.setPreparedMultisigHex(ProtoUtil.stringOrNullFromProto(proto.getPreparedMultisigHex()));
            tradingPeer.setMadeMultisigHex(ProtoUtil.stringOrNullFromProto(proto.getMadeMultisigHex()));
            tradingPeer.setExchangedMultisigHex(ProtoUtil.stringOrNullFromProto(proto.getExchangedMultisigHex()));
            tradingPeer.setDepositTxHash(ProtoUtil.stringOrNullFromProto(proto.getDepositTxHash()));
            tradingPeer.setDepositTxHex(ProtoUtil.stringOrNullFromProto(proto.getDepositTxHex()));
            tradingPeer.setDepositTxKey(ProtoUtil.stringOrNullFromProto(proto.getDepositTxKey()));
            tradingPeer.setUpdatedMultisigHex(ProtoUtil.stringOrNullFromProto(proto.getUpdatedMultisigHex()));
            return tradingPeer;
        }
    }
}
