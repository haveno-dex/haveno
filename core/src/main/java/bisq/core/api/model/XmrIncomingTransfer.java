package bisq.core.api.model;

import bisq.common.Payload;
import java.math.BigInteger;
import lombok.Getter;
import monero.wallet.model.MoneroIncomingTransfer;

@Getter
public class XmrIncomingTransfer implements Payload {
    
    private final BigInteger amount;
    private final Integer accountIndex;
    private final Integer subaddressIndex;
    private final String address;
    private final Long numSuggestedConfirmations;

    public XmrIncomingTransfer(XmrIncomingTransferBuilder builder) {
        this.amount = builder.amount;
        this.accountIndex = builder.accountIndex;
        this.subaddressIndex = builder.subaddressIndex;
        this.address = builder.address;
        this.numSuggestedConfirmations = builder.numSuggestedConfirmations;
    }

    public static XmrIncomingTransfer toXmrIncomingTransfer(MoneroIncomingTransfer transfer) {
        return new XmrIncomingTransferBuilder()
                .withAmount(transfer.getAmount())
                .withAccountIndex(transfer.getAccountIndex())
                .withSubaddressIndex(transfer.getSubaddressIndex())
                .withAddress(transfer.getAddress())
                .withNumSuggestedConfirmations(transfer.getNumSuggestedConfirmations())
                .build();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public bisq.proto.grpc.XmrIncomingTransfer toProtoMessage() {
        return bisq.proto.grpc.XmrIncomingTransfer.newBuilder()
                .setAmount(amount.toString())
                .setAccountIndex(accountIndex)
                .setSubaddressIndex(subaddressIndex)
                .setAddress(address)
                .setNumSuggestedConfirmations(numSuggestedConfirmations)
                .build();
    }

    public static XmrIncomingTransfer fromProto(bisq.proto.grpc.XmrIncomingTransfer proto) {
        return new XmrIncomingTransferBuilder()
                .withAmount(new BigInteger(proto.getAmount()))
                .withAccountIndex(proto.getAccountIndex())
                .withSubaddressIndex(proto.getSubaddressIndex())
                .withAddress(proto.getAddress())
                .withNumSuggestedConfirmations(proto.getNumSuggestedConfirmations())
                .build();
    }

    public static class XmrIncomingTransferBuilder {
        private BigInteger amount;
        private Integer accountIndex;
        private Integer subaddressIndex;
        private String address;
        private Long numSuggestedConfirmations;
        
        public XmrIncomingTransferBuilder withAmount(BigInteger amount) {
            this.amount = amount;
            return this;
        }

        public XmrIncomingTransferBuilder withAccountIndex(Integer accountIndex) {
            this.accountIndex = accountIndex;
            return this;
        }

        public XmrIncomingTransferBuilder withSubaddressIndex(Integer subaddressIndex) {
            this.subaddressIndex = subaddressIndex;
            return this;
        }

        public XmrIncomingTransferBuilder withAddress(String address) {
            this.address = address;
            return this;
        }

        public XmrIncomingTransferBuilder withNumSuggestedConfirmations(Long numSuggestedConfirmations) {
            this.numSuggestedConfirmations = numSuggestedConfirmations;
            return this;
        }

        public XmrIncomingTransfer build() {
            return new XmrIncomingTransfer(this);
        }
    }

    @Override
    public String toString() {
        return "XmrIncomingTransfer{" +
                "amount=" + amount +
                ", accountIndex=" + accountIndex +
                ", subaddressIndex=" + subaddressIndex +
                ", address=" + address +
                ", numSuggestedConfirmations=" + numSuggestedConfirmations +
                '}';
    }
}
