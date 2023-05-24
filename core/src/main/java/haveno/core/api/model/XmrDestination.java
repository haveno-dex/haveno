package haveno.core.api.model;

import haveno.common.Payload;
import lombok.Getter;
import monero.wallet.model.MoneroDestination;

import java.math.BigInteger;

@Getter
public class XmrDestination implements Payload {

    private final String address;
    private final BigInteger amount;

    public XmrDestination(XmrDestinationBuilder builder) {
        this.address = builder.address;
        this.amount = builder.amount;
    }

    public static XmrDestination toXmrDestination(MoneroDestination dst) {
        return new XmrDestinationBuilder()
                .withAddress(dst.getAddress())
                .withAmount(dst.getAmount())
                .build();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public haveno.proto.grpc.XmrDestination toProtoMessage() {
        return haveno.proto.grpc.XmrDestination.newBuilder()
                .setAddress(address)
                .setAmount(amount.toString())
                .build();
    }

    public static XmrDestination fromProto(haveno.proto.grpc.XmrDestination proto) {
        return new XmrDestinationBuilder()
                .withAddress(proto.getAddress())
                .withAmount(new BigInteger(proto.getAmount()))
                .build();
    }

    public static class XmrDestinationBuilder {
        private String address;
        private BigInteger amount;

        public XmrDestinationBuilder withAddress(String address) {
            this.address = address;
            return this;
        }

        public XmrDestinationBuilder withAmount(BigInteger amount) {
            this.amount = amount;
            return this;
        }

        public XmrDestination build() { return new XmrDestination(this); }
    }

    @Override
    public String toString() {
        return "XmrDestination{" +
                "address=" + address +
                ", amount" + amount +
                '}';
    }
}
