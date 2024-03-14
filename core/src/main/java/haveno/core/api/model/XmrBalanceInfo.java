package haveno.core.api.model;

import java.math.BigInteger;

import com.google.common.annotations.VisibleForTesting;
import haveno.common.Payload;

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
    private final long reservedBalance;

    public XmrBalanceInfo(long balance,
                          long unlockedBalance,
                          long pendingBalance,
                          long reservedOfferBalance,
                          long reservedTradeBalance) {
        this.balance = balance;
        this.availableBalance = unlockedBalance;
        this.pendingBalance = pendingBalance;
        this.reservedOfferBalance = reservedOfferBalance;
        this.reservedTradeBalance = reservedTradeBalance;
        this.reservedBalance = reservedOfferBalance + reservedTradeBalance;
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

    public BigInteger getBalance() {
        return BigInteger.valueOf(balance);
    }

    public BigInteger getAvailableBalance() {
        return BigInteger.valueOf(availableBalance);
    }

    public BigInteger getPendingBalance() {
        return BigInteger.valueOf(pendingBalance);
    }

    public BigInteger getReservedOfferBalance() {
        return BigInteger.valueOf(reservedOfferBalance);
    }

    public BigInteger getReservedTradeBalance() {
        return BigInteger.valueOf(reservedTradeBalance);
    }

    public BigInteger getReservedBalance() {
        return BigInteger.valueOf(reservedBalance);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public haveno.proto.grpc.XmrBalanceInfo toProtoMessage() {
        return haveno.proto.grpc.XmrBalanceInfo.newBuilder()
                .setBalance(balance)
                .setAvailableBalance(availableBalance)
                .setPendingBalance(pendingBalance)
                .setReservedOfferBalance(reservedOfferBalance)
                .setReservedTradeBalance(reservedTradeBalance)
                .build();
    }

    public static XmrBalanceInfo fromProto(haveno.proto.grpc.XmrBalanceInfo proto) {
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
