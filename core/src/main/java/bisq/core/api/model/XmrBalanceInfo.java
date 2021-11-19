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
    private final long unlockedBalance;
    private final long lockedBalance;
    private final long reservedOfferBalance;
    private final long reservedTradeBalance;

    public XmrBalanceInfo(long balance,
                          long unlockedBalance,
                          long lockedBalance,
                          long reservedOfferBalance,
                          long reservedTradeBalance) {
        this.balance = balance;
        this.unlockedBalance = unlockedBalance;
        this.lockedBalance = lockedBalance;
        this.reservedOfferBalance = reservedOfferBalance;
        this.reservedTradeBalance = reservedTradeBalance;
    }

    @VisibleForTesting
    public static XmrBalanceInfo valueOf(long balance,
                                         long unlockedBalance,
                                         long lockedBalance,
                                         long reservedOfferBalance,
                                         long reservedTradeBalance) {
        return new XmrBalanceInfo(balance,
                unlockedBalance,
                lockedBalance,
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
                .setUnlockedBalance(unlockedBalance)
                .setLockedBalance(lockedBalance)
                .setReservedOfferBalance(reservedOfferBalance)
                .setReservedTradeBalance(reservedTradeBalance)
                .build();
    }

    public static XmrBalanceInfo fromProto(bisq.proto.grpc.XmrBalanceInfo proto) {
        return new XmrBalanceInfo(proto.getBalance(),
                proto.getUnlockedBalance(),
                proto.getLockedBalance(),
                proto.getReservedOfferBalance(),
                proto.getReservedTradeBalance());
    }

    @Override
    public String toString() {
        return "XmrBalanceInfo{" +
                "balance=" + balance +
                "unlockedBalance=" + unlockedBalance +
                ", lockedBalance=" + lockedBalance +
                ", reservedOfferBalance=" + reservedOfferBalance +
                ", reservedTradeBalance=" + reservedTradeBalance +
                '}';
    }
}
