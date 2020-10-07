/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.trade;

import static com.google.common.base.Preconditions.checkArgument;

import javax.annotation.Nullable;

import org.apache.commons.lang3.StringUtils;
import org.bitcoinj.core.Coin;

import bisq.common.crypto.PubKeyRing;
import bisq.common.proto.network.NetworkPayload;
import bisq.common.util.JsonExclude;
import bisq.common.util.Utilities;
import bisq.core.locale.CurrencyUtil;
import bisq.core.monetary.Price;
import bisq.core.monetary.Volume;
import bisq.core.offer.OfferPayload;
import bisq.core.offer.OfferUtil;
import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.payment.payload.PaymentMethod;
import bisq.core.proto.CoreProtoResolver;
import bisq.network.p2p.NodeAddress;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Value
public final class Contract implements NetworkPayload {
    private final OfferPayload offerPayload;
    private final long tradeAmount;
    private final long tradePrice;
    private final NodeAddress buyerNodeAddress;
    private final NodeAddress sellerNodeAddress;
    private final NodeAddress arbitratorNodeAddress;
    private final boolean isBuyerMakerAndSellerTaker;
    private final String makerAccountId;
    private final String takerAccountId;
    private final PaymentAccountPayload makerPaymentAccountPayload;
    private final PaymentAccountPayload takerPaymentAccountPayload;
    @JsonExclude
    private final PubKeyRing makerPubKeyRing;
    @JsonExclude
    private final PubKeyRing takerPubKeyRing;
    private final String makerPayoutAddressString;
    private final String takerPayoutAddressString;

    // Added in v1.2.0
    private long lockTime;

    public Contract(OfferPayload offerPayload,
                    long tradeAmount,
                    long tradePrice,
                    NodeAddress buyerNodeAddress,
                    NodeAddress sellerNodeAddress,
                    NodeAddress arbitratorNodeAddress,
                    boolean isBuyerMakerAndSellerTaker,
                    String makerAccountId,
                    String takerAccountId,
                    PaymentAccountPayload makerPaymentAccountPayload,
                    PaymentAccountPayload takerPaymentAccountPayload,
                    PubKeyRing makerPubKeyRing,
                    PubKeyRing takerPubKeyRing,
                    String makerPayoutAddressString,
                    String takerPayoutAddressString,
                    long lockTime) {
        this.offerPayload = offerPayload;
        this.tradeAmount = tradeAmount;
        this.tradePrice = tradePrice;
        this.buyerNodeAddress = buyerNodeAddress;
        this.sellerNodeAddress = sellerNodeAddress;
        this.arbitratorNodeAddress = arbitratorNodeAddress;
        this.isBuyerMakerAndSellerTaker = isBuyerMakerAndSellerTaker;
        this.makerAccountId = makerAccountId;
        this.takerAccountId = takerAccountId;
        this.makerPaymentAccountPayload = makerPaymentAccountPayload;
        this.takerPaymentAccountPayload = takerPaymentAccountPayload;
        this.makerPubKeyRing = makerPubKeyRing;
        this.takerPubKeyRing = takerPubKeyRing;
        this.makerPayoutAddressString = makerPayoutAddressString;
        this.takerPayoutAddressString = takerPayoutAddressString;
        this.lockTime = lockTime;

        String makerPaymentMethodId = makerPaymentAccountPayload.getPaymentMethodId();
        String takerPaymentMethodId = takerPaymentAccountPayload.getPaymentMethodId();
        // For SEPA offers we accept also SEPA_INSTANT takers
        // Otherwise both ids need to be the same
        boolean result = (makerPaymentMethodId.equals(PaymentMethod.SEPA_ID) && takerPaymentMethodId.equals(PaymentMethod.SEPA_INSTANT_ID)) ||
                makerPaymentMethodId.equals(takerPaymentMethodId);
        checkArgument(result, "payment methods of maker and taker must be the same.\n" +
                "makerPaymentMethodId=" + makerPaymentMethodId + "\n" +
                "takerPaymentMethodId=" + takerPaymentMethodId);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    public static Contract fromProto(protobuf.Contract proto, CoreProtoResolver coreProtoResolver) {
        return new Contract(OfferPayload.fromProto(proto.getOfferPayload()),
                proto.getTradeAmount(),
                proto.getTradePrice(),
                NodeAddress.fromProto(proto.getBuyerNodeAddress()),
                NodeAddress.fromProto(proto.getSellerNodeAddress()),
                NodeAddress.fromProto(proto.getArbitratorNodeAddress()),
                proto.getIsBuyerMakerAndSellerTaker(),
                proto.getMakerAccountId(),
                proto.getTakerAccountId(),
                coreProtoResolver.fromProto(proto.getMakerPaymentAccountPayload()),
                coreProtoResolver.fromProto(proto.getTakerPaymentAccountPayload()),
                PubKeyRing.fromProto(proto.getMakerPubKeyRing()),
                PubKeyRing.fromProto(proto.getTakerPubKeyRing()),
                proto.getMakerPayoutAddressString(),
                proto.getTakerPayoutAddressString(),
                proto.getLockTime());
    }

    @Override
    public protobuf.Contract toProtoMessage() {
        return protobuf.Contract.newBuilder()
                .setOfferPayload(offerPayload.toProtoMessage().getOfferPayload())
                .setTradeAmount(tradeAmount)
                .setTradePrice(tradePrice)
                .setBuyerNodeAddress(buyerNodeAddress.toProtoMessage())
                .setSellerNodeAddress(sellerNodeAddress.toProtoMessage())
                .setArbitratorNodeAddress(arbitratorNodeAddress.toProtoMessage())
                .setIsBuyerMakerAndSellerTaker(isBuyerMakerAndSellerTaker)
                .setMakerAccountId(makerAccountId)
                .setTakerAccountId(takerAccountId)
                .setMakerPaymentAccountPayload((protobuf.PaymentAccountPayload) makerPaymentAccountPayload.toProtoMessage())
                .setTakerPaymentAccountPayload((protobuf.PaymentAccountPayload) takerPaymentAccountPayload.toProtoMessage())
                .setMakerPubKeyRing(makerPubKeyRing.toProtoMessage())
                .setTakerPubKeyRing(takerPubKeyRing.toProtoMessage())
                .setMakerPayoutAddressString(makerPayoutAddressString)
                .setTakerPayoutAddressString(takerPayoutAddressString)
                .setLockTime(lockTime)
                .build();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public String getBuyerPayoutAddressString() {
        return isBuyerMakerAndSellerTaker ? makerPayoutAddressString : takerPayoutAddressString;
    }

    public String getSellerPayoutAddressString() {
        return isBuyerMakerAndSellerTaker ? takerPayoutAddressString : makerPayoutAddressString;
    }

    public PubKeyRing getBuyerPubKeyRing() {
        return isBuyerMakerAndSellerTaker ? makerPubKeyRing : takerPubKeyRing;
    }

    public PubKeyRing getSellerPubKeyRing() {
        return isBuyerMakerAndSellerTaker ? takerPubKeyRing : makerPubKeyRing;
    }

    public PaymentAccountPayload getBuyerPaymentAccountPayload() {
        return isBuyerMakerAndSellerTaker ? makerPaymentAccountPayload : takerPaymentAccountPayload;
    }

    public PaymentAccountPayload getSellerPaymentAccountPayload() {
        return isBuyerMakerAndSellerTaker ? takerPaymentAccountPayload : makerPaymentAccountPayload;
    }

    public String getPaymentMethodId() {
        return makerPaymentAccountPayload.getPaymentMethodId();
    }

    public Coin getTradeAmount() {
        return Coin.valueOf(tradeAmount);
    }

    public Volume getTradeVolume() {
        Volume volumeByAmount = getTradePrice().getVolumeByAmount(getTradeAmount());

        if (getPaymentMethodId().equals(PaymentMethod.HAL_CASH_ID))
            volumeByAmount = OfferUtil.getAdjustedVolumeForHalCash(volumeByAmount);
        else if (CurrencyUtil.isFiatCurrency(getOfferPayload().getCurrencyCode()))
            volumeByAmount = OfferUtil.getRoundedFiatVolume(volumeByAmount);

        return volumeByAmount;
    }

    public Price getTradePrice() {
        return Price.valueOf(offerPayload.getCurrencyCode(), tradePrice);
    }

    public NodeAddress getMyNodeAddress(PubKeyRing myPubKeyRing) {
        if (myPubKeyRing.equals(getBuyerPubKeyRing()))
            return buyerNodeAddress;
        else
            return sellerNodeAddress;
    }

    public NodeAddress getPeersNodeAddress(PubKeyRing myPubKeyRing) {
        if (myPubKeyRing.equals(getSellerPubKeyRing()))
            return buyerNodeAddress;
        else
            return sellerNodeAddress;
    }

    public PubKeyRing getPeersPubKeyRing(PubKeyRing myPubKeyRing) {
        if (myPubKeyRing.equals(getSellerPubKeyRing()))
            return getBuyerPubKeyRing();
        else
            return getSellerPubKeyRing();
    }

    public boolean isMyRoleBuyer(PubKeyRing myPubKeyRing) {
        return getBuyerPubKeyRing().equals(myPubKeyRing);
    }

    public void printDiff(@Nullable String peersContractAsJson) {
        final String json = Utilities.objectToJson(this);
        String diff = StringUtils.difference(json, peersContractAsJson);
        if (!diff.isEmpty()) {
            log.warn("Diff of both contracts: \n" + diff);
            log.warn("\n\n------------------------------------------------------------\n"
                    + "Contract as json\n"
                    + json
                    + "\n------------------------------------------------------------\n");

            log.warn("\n\n------------------------------------------------------------\n"
                    + "Peers contract as json\n"
                    + peersContractAsJson
                    + "\n------------------------------------------------------------\n");
        } else {
            log.debug("Both contracts are the same");
        }
    }

    @Override
    public String toString() {
        return "Contract{" +
                "\n     offerPayload=" + offerPayload +
                ",\n     tradeAmount=" + tradeAmount +
                ",\n     tradePrice=" + tradePrice +
                ",\n     buyerNodeAddress=" + buyerNodeAddress +
                ",\n     sellerNodeAddress=" + sellerNodeAddress +
                ",\n     arbitratorNodeAddress=" + arbitratorNodeAddress +
                ",\n     isBuyerMakerAndSellerTaker=" + isBuyerMakerAndSellerTaker +
                ",\n     makerAccountId='" + makerAccountId + '\'' +
                ",\n     takerAccountId='" + takerAccountId + '\'' +
                ",\n     makerPaymentAccountPayload=" + makerPaymentAccountPayload +
                ",\n     takerPaymentAccountPayload=" + takerPaymentAccountPayload +
                ",\n     makerPubKeyRing=" + makerPubKeyRing +
                ",\n     takerPubKeyRing=" + takerPubKeyRing +
                ",\n     makerPayoutAddressString='" + makerPayoutAddressString + '\'' +
                ",\n     takerPayoutAddressString='" + takerPayoutAddressString + '\'' +
                ",\n     lockTime=" + lockTime +
                "\n}";
    }
}
