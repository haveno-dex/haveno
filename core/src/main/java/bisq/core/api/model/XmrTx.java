package bisq.core.api.model;

import static bisq.core.api.model.XmrIncomingTransfer.toXmrIncomingTransfer;
import static bisq.core.api.model.XmrOutgoingTransfer.toXmrOutgoingTransfer;

import bisq.common.Payload;
import bisq.common.proto.ProtoUtil;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import lombok.Getter;
import monero.wallet.model.MoneroTxWallet;

@Getter
public class XmrTx implements Payload {

    private final String hash;
    private final BigInteger fee;
    private final boolean isConfirmed;
    private final boolean isLocked;
    @Nullable
    private final Long height;
    @Nullable
    private final Long timestamp;
    @Nullable
    private final List<XmrIncomingTransfer> incomingTransfers;
    @Nullable
    private final XmrOutgoingTransfer outgoingTransfer;
    @Nullable
    private final String metadata;

    public XmrTx(XmrTxBuilder builder) {
        this.hash = builder.hash;
        this.fee = builder.fee;
        this.isConfirmed = builder.isConfirmed;
        this.isLocked = builder.isLocked;
        this.height = builder.height;
        this.timestamp = builder.timestamp;
        this.incomingTransfers = builder.incomingTransfers;
        this.outgoingTransfer = builder.outgoingTransfer;
        this.metadata = builder.metadata;
    }

    public static XmrTx toXmrTx(MoneroTxWallet tx){
        Long timestamp = tx.getBlock() == null ? null : tx.getBlock().getTimestamp();
        List<XmrIncomingTransfer> incomingTransfers = tx.getIncomingTransfers() == null ? null :
                tx.getIncomingTransfers().stream()
                .map(s -> toXmrIncomingTransfer(s))
                .collect(Collectors.toList());
        XmrOutgoingTransfer outgoingTransfer = tx.getOutgoingTransfer() == null ? null :
                toXmrOutgoingTransfer(tx.getOutgoingTransfer());
        XmrTxBuilder builder = new XmrTxBuilder()
                .withHash(tx.getHash())
                .withFee(tx.getFee())
                .withIsConfirmed(tx.isConfirmed())
                .withIsLocked(tx.isLocked());
        Optional.ofNullable(tx.getHeight()).ifPresent(e ->builder.withHeight(tx.getHeight()));
        Optional.ofNullable(timestamp).ifPresent(e ->builder.withTimestamp(timestamp));
        Optional.ofNullable(outgoingTransfer).ifPresent(e ->builder.withOutgoingTransfer(outgoingTransfer));
        Optional.ofNullable(incomingTransfers).ifPresent(e ->builder.withIncomingTransfers(incomingTransfers));
        Optional.ofNullable(tx.getMetadata()).ifPresent(e ->builder.withMetadata(tx.getMetadata()));
        return builder.build();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public bisq.proto.grpc.XmrTx toProtoMessage() {
        bisq.proto.grpc.XmrTx.Builder builder = bisq.proto.grpc.XmrTx.newBuilder()
                .setHash(hash)
                .setFee(fee.toString())
                .setIsConfirmed(isConfirmed)
                .setIsLocked(isLocked);
        Optional.ofNullable(height).ifPresent(e -> builder.setHeight(height));
        Optional.ofNullable(timestamp).ifPresent(e -> builder.setTimestamp(timestamp));
        Optional.ofNullable(outgoingTransfer).ifPresent(e -> builder.setOutgoingTransfer(outgoingTransfer.toProtoMessage()));
        Optional.ofNullable(incomingTransfers).ifPresent(e -> builder.addAllIncomingTransfers(ProtoUtil.collectionToProto(incomingTransfers, bisq.proto.grpc.XmrIncomingTransfer.class)));
        Optional.ofNullable(metadata).ifPresent(e -> builder.setMetadata(metadata));
        return builder.build();
    }

    public static XmrTx fromProto(bisq.proto.grpc.XmrTx proto) {
        return new XmrTxBuilder()
                .withHash(proto.getHash())
                .withFee(new BigInteger(proto.getFee()))
                .withIsConfirmed(proto.getIsConfirmed())
                .withIsLocked(proto.getIsLocked())
                .withHeight(proto.getHeight())
                .withTimestamp(proto.getTimestamp())
                .withIncomingTransfers(
                    proto.getIncomingTransfersList().stream()
                        .map(XmrIncomingTransfer::fromProto)
                        .collect(Collectors.toList()))
                .withOutgoingTransfer(XmrOutgoingTransfer.fromProto(proto.getOutgoingTransfer()))
                .withMetadata(proto.getMetadata())
                .build();
    }

    public static class XmrTxBuilder {
        private String hash;
        private BigInteger fee;
        private boolean isConfirmed;
        private boolean isLocked;
        private Long height;
        private Long timestamp;
        private List<XmrIncomingTransfer> incomingTransfers;
        private XmrOutgoingTransfer outgoingTransfer;
        private String metadata;

        public XmrTxBuilder withHash(String hash) {
            this.hash = hash;
            return this;
        }

        public XmrTxBuilder withFee(BigInteger fee) {
            this.fee = fee;
            return this;
        }

        public XmrTxBuilder withIsConfirmed(boolean isConfirmed) {
            this.isConfirmed = isConfirmed;
            return this;
        }

        public XmrTxBuilder withIsLocked(boolean isLocked) {
            this.isLocked = isLocked;
            return this;
        }

        public XmrTxBuilder withHeight(Long height) {
            this.height = height;
            return this;
        }

        public XmrTxBuilder withTimestamp(Long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public XmrTxBuilder withIncomingTransfers(List<XmrIncomingTransfer> incomingTransfers) {
            this.incomingTransfers = incomingTransfers;
            return this;
        }

        public XmrTxBuilder withOutgoingTransfer(XmrOutgoingTransfer outgoingTransfer) {
            this.outgoingTransfer = outgoingTransfer;
            return this;
        }

        public XmrTxBuilder withMetadata(String metadata) {
            this.metadata = metadata;
            return this;
        }

        public XmrTx build() { return new XmrTx(this); }
    }

    @Override
    public String toString() {
        return "XmrTx{" +
                "hash=" + hash +
                ", fee=" + timestamp +
                ", isConfirmed=" + isConfirmed +
                ", isLocked=" + isLocked +
                ", height=" + height +
                ", timestamp=" + timestamp +
                ", incomingTransfers=" + incomingTransfers +
                ", outgoingTransfer=" + outgoingTransfer +
                ", metadata=" + metadata +
                '}';
    }
}
