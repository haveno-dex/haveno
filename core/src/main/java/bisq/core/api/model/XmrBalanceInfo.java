package bisq.core.api.model;

import bisq.common.Payload;

import com.google.common.annotations.VisibleForTesting;

import lombok.Getter;

@Getter
public class XmrBalanceInfo implements Payload {

    public static final XmrBalanceInfo EMPTY = new XmrBalanceInfo(-1,
            -1,
            -1,
            -1,
            -1);

    // all balances are in atomic units
    private final long balance;
    private final long availableBalance;
    private final long pendingBalance;
    private final long reservedOfferBalance;
    private final long reservedTradeBalance;

    public XmrBalanceInfo(long balance,
                          long unlockedBalance,
                          long lockedBalance,
                          long reservedOfferBalance,
                          long reservedTradeBalance) {
        this.balance = balance;
        this.availableBalance = unlockedBalance;
        this.pendingBalance = lockedBalance;
        this.reservedOfferBalance = reservedOfferBalance;
        this.reservedTradeBalance = reservedTradeBalance;
    }

    @VisibleForTesting
    public static XmrBalanceInfo valueOf(long balance,
                                         long availableBalance,
                                         long pendingBalance,
                                         long reservedOfferBalance,
                                         long reservedTradeBalance) {
        return new XmrBalanceInfo(balance,
                availableBalance,
                pendingBalance,
                reservedOfferBalance,
                reservedTradeBalance);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public bisq.proto.grpc.XmrBalanceInfo toProtoMessage() {
        return bisq.proto.grpc.XmrBalanceInfo.newBuilder()
                .setBalance(balance)
                .setAvailableBalance(availableBalance)
                .setPendingBalance(pendingBalance)
                .setReservedOfferBalance(reservedOfferBalance)
                .setReservedTradeBalance(reservedTradeBalance)
                .build();
    }

    public static XmrBalanceInfo fromProto(bisq.proto.grpc.XmrBalanceInfo proto) {
        return new XmrBalanceInfo(proto.getBalance(),
                proto.getAvailableBalance(),
                proto.getPendingBalance(),
                proto.getReservedOfferBalance(),
                proto.getReservedTradeBalance());
    }

    @Override
    public String toString() {
        return "XmrBalanceInfo{" +
                "balance=" + balance +
                "unlockedBalance=" + availableBalance +
                ", lockedBalance=" + pendingBalance +
                ", reservedOfferBalance=" + reservedOfferBalance +
                ", reservedTradeBalance=" + reservedTradeBalance +
                '}';
    }
}
