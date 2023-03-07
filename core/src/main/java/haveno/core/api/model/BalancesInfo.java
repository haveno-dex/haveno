package haveno.core.api.model;

import haveno.common.Payload;
import lombok.Getter;

@Getter
public class BalancesInfo implements Payload {

    // Getter names are shortened for readability's sake, i.e.,
    // balancesInfo.getBtc().getAvailableBalance() is cleaner than
    // balancesInfo.getBtcBalanceInfo().getAvailableBalance().
    private final BtcBalanceInfo btc;
    private final XmrBalanceInfo xmr;

    public BalancesInfo(BtcBalanceInfo btc, XmrBalanceInfo xmr) {
        this.btc = btc;
        this.xmr = xmr;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public haveno.proto.grpc.BalancesInfo toProtoMessage() {
        return haveno.proto.grpc.BalancesInfo.newBuilder()
                .setBtc(btc.toProtoMessage())
                .setXmr(xmr.toProtoMessage())
                .build();
    }

    public static BalancesInfo fromProto(haveno.proto.grpc.BalancesInfo proto) {
        return new BalancesInfo(
                BtcBalanceInfo.fromProto(proto.getBtc()),
                XmrBalanceInfo.fromProto(proto.getXmr()));
    }

    @Override
    public String toString() {
        return "BalancesInfo{" + "\n" +
                " " + btc.toString() + "\n" +
                ", " + xmr.toString() + "\n" +
                '}';
    }
}
