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

    // All balances are in XMR centineros: https://www.getmonero.org/resources/moneropedia/denominations.html
    private final long balance;
    private final long availableBalance;
    private final long lockedBalance;
    private final long reservedBalance;
    private final long totalBalance; // balance + reserved

    public XmrBalanceInfo(long balance,
                          long availableBalance,
                          long lockedBalance,
                          long reservedBalance,
                          long totalBalance) {
        this.balance = balance;
        this.availableBalance = availableBalance;
        this.lockedBalance = lockedBalance;
        this.reservedBalance = reservedBalance;
        this.totalBalance = totalBalance;
    }

    @VisibleForTesting
    public static XmrBalanceInfo valueOf(long balance,
                                         long availableBalance,
                                         long lockedBalance,
                                         long reservedBalance,
                                         long totalBalance) {
        // Convenience for creating a model instance instead of a proto.
        return new XmrBalanceInfo(balance,
                availableBalance,
                lockedBalance,
                reservedBalance,
                totalBalance);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public bisq.proto.grpc.XmrBalanceInfo toProtoMessage() {
        return bisq.proto.grpc.XmrBalanceInfo.newBuilder()
                .setBalance(balance)
                .setAvailableBalance(availableBalance)
                .setLockedBalance(lockedBalance)
                .setReservedBalance(reservedBalance)
                .setTotalBalance(totalBalance)
                .build();
    }

    public static XmrBalanceInfo fromProto(bisq.proto.grpc.XmrBalanceInfo proto) {
        return new XmrBalanceInfo(proto.getBalance(),
                proto.getAvailableBalance(),
                proto.getLockedBalance(),
                proto.getReservedBalance(),
                proto.getTotalBalance());
    }

    @Override
    public String toString() {
        return "BtcBalanceInfo{" +
                "balance=" + balance +
                ", availableBalance=" + availableBalance +
                ", lockedBalance=" + lockedBalance +
                ", reservedBalance=" + reservedBalance +
                ", totalBalance=" + totalBalance +
                '}';
    }
}
