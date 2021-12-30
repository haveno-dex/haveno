package bisq.core.api.model;

import static bisq.core.api.model.XmrDestination.toXmrDestination;

import bisq.common.Payload;
import bisq.common.proto.ProtoUtil;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import lombok.Getter;
import monero.wallet.model.MoneroOutgoingTransfer;

@Getter
public class XmrOutgoingTransfer implements Payload {

    private final BigInteger amount;
    private final Integer accountIndex;
    @Nullable
    private final List<Integer> subaddressIndices;
    @Nullable
    private final List<XmrDestination> destinations;

    public XmrOutgoingTransfer(XmrOutgoingTransferBuilder builder) {
        this.amount = builder.amount;
        this.accountIndex = builder.accountIndex;
        this.subaddressIndices = builder.subaddressIndices;
        this.destinations = builder.destinations;
    }

    public static XmrOutgoingTransfer toXmrOutgoingTransfer(MoneroOutgoingTransfer transfer) {
        List<XmrDestination> destinations = transfer.getDestinations() == null ? null :
                transfer.getDestinations().stream()
                .map(s -> toXmrDestination(s))
                .collect(Collectors.toList());
        XmrOutgoingTransferBuilder builder = new XmrOutgoingTransferBuilder()
                .withAmount(transfer.getAmount())
                .withAccountIndex(transfer.getAccountIndex())
                .withSubaddressIndices(transfer.getSubaddressIndices())
                .withDestinations(destinations);
        return builder.build();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public bisq.proto.grpc.XmrOutgoingTransfer toProtoMessage() {
        var builder = bisq.proto.grpc.XmrOutgoingTransfer.newBuilder()
                .setAmount(amount.toString())
                .setAccountIndex(accountIndex);
        Optional.ofNullable(subaddressIndices).ifPresent(e -> builder.addAllSubaddressIndices(subaddressIndices));
        Optional.ofNullable(destinations).ifPresent(e -> builder.addAllDestinations(ProtoUtil.collectionToProto(destinations, bisq.proto.grpc.XmrDestination.class)));
        return builder.build();
    }

    public static XmrOutgoingTransfer fromProto(bisq.proto.grpc.XmrOutgoingTransfer proto) {
        List<XmrDestination> destinations = proto.getDestinationsList().isEmpty() ?
                null : proto.getDestinationsList().stream()
                .map(XmrDestination::fromProto).collect(Collectors.toList());
        return new XmrOutgoingTransferBuilder()
                .withAmount(new BigInteger(proto.getAmount()))
                .withAccountIndex(proto.getAccountIndex())
                .withSubaddressIndices(proto.getSubaddressIndicesList())
                .withDestinations(destinations)
                .build();
    }

    public static class XmrOutgoingTransferBuilder {
        private BigInteger amount;
        private Integer accountIndex;
        private List<Integer> subaddressIndices;
        private List<XmrDestination> destinations;

        public XmrOutgoingTransferBuilder withAmount(BigInteger amount) {
            this.amount = amount;
            return this;
        }

        public XmrOutgoingTransferBuilder withAccountIndex(Integer accountIndex) {
            this.accountIndex = accountIndex;
            return this;
        }

        public XmrOutgoingTransferBuilder withSubaddressIndices(List<Integer> subaddressIndices) {
            this.subaddressIndices = subaddressIndices;
            return this;
        }

        public XmrOutgoingTransferBuilder withDestinations(List<XmrDestination> destinations) {
            this.destinations = destinations;
            return this;
        }

        public XmrOutgoingTransfer build() {
            return new XmrOutgoingTransfer(this);
        }
    }

    @Override
    public String toString() {
        return "XmrOutgoingTransfer{" +
                "amount=" + amount +
                ", accountIndex=" + accountIndex +
                ", subaddressIndices=" + subaddressIndices +
                ", destinations=" + destinations +
                '}';
    }
}
