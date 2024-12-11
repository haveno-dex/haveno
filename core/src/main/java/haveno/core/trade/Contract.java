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

/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.core.trade;

import com.google.protobuf.ByteString;
import haveno.common.crypto.PubKeyRing;
import haveno.common.proto.ProtoUtil;
import haveno.common.proto.network.NetworkPayload;
import haveno.common.util.JsonExclude;
import haveno.common.util.Utilities;
import haveno.core.monetary.Price;
import haveno.core.monetary.Volume;
import haveno.core.offer.OfferPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.proto.CoreProtoResolver;
import haveno.core.util.JsonUtil;
import haveno.core.util.VolumeUtil;
import haveno.network.p2p.NodeAddress;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;
import java.math.BigInteger;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;

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
    private final String makerPaymentMethodId;
    private final String takerPaymentMethodId;
    private final byte[] makerPaymentAccountPayloadHash;
    private final byte[] takerPaymentAccountPayloadHash;
    @JsonExclude
    private final PubKeyRing makerPubKeyRing;
    @JsonExclude
    private final PubKeyRing takerPubKeyRing;
    private final String makerPayoutAddressString;
    private final String takerPayoutAddressString;
    private final String makerDepositTxHash;
    @Nullable
    private final String takerDepositTxHash;

    public Contract(OfferPayload offerPayload,
                    long tradeAmount,
                    long tradePrice,
                    NodeAddress buyerNodeAddress,
                    NodeAddress sellerNodeAddress,
                    NodeAddress arbitratorNodeAddress,
                    boolean isBuyerMakerAndSellerTaker,
                    String makerAccountId,
                    String takerAccountId,
                    String makerPaymentMethodId,
                    String takerPaymentMethodId,
                    byte[] makerPaymentAccountPayloadHash,
                    byte[] takerPaymentAccountPayloadHash,
                    PubKeyRing makerPubKeyRing,
                    PubKeyRing takerPubKeyRing,
                    String makerPayoutAddressString,
                    String takerPayoutAddressString,
                    String makerDepositTxHash,
                    @Nullable String takerDepositTxHash) {
        this.offerPayload = offerPayload;
        this.tradeAmount = tradeAmount;
        this.tradePrice = tradePrice;
        this.buyerNodeAddress = buyerNodeAddress;
        this.sellerNodeAddress = sellerNodeAddress;
        this.arbitratorNodeAddress = arbitratorNodeAddress;
        this.isBuyerMakerAndSellerTaker = isBuyerMakerAndSellerTaker;
        this.makerAccountId = makerAccountId;
        this.takerAccountId = takerAccountId;
        this.makerPaymentMethodId = makerPaymentMethodId;
        this.takerPaymentMethodId = takerPaymentMethodId;
        this.makerPaymentAccountPayloadHash = makerPaymentAccountPayloadHash;
        this.takerPaymentAccountPayloadHash = takerPaymentAccountPayloadHash;
        this.makerPubKeyRing = makerPubKeyRing;
        this.takerPubKeyRing = takerPubKeyRing;
        this.makerPayoutAddressString = makerPayoutAddressString;
        this.takerPayoutAddressString = takerPayoutAddressString;
        this.makerDepositTxHash = makerDepositTxHash;
        this.takerDepositTxHash = takerDepositTxHash;

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

    @Override
    public protobuf.Contract toProtoMessage() {
        protobuf.Contract.Builder builder = protobuf.Contract.newBuilder()
                .setOfferPayload(offerPayload.toProtoMessage().getOfferPayload())
                .setTradeAmount(tradeAmount)
                .setTradePrice(tradePrice)
                .setBuyerNodeAddress(buyerNodeAddress.toProtoMessage())
                .setSellerNodeAddress(sellerNodeAddress.toProtoMessage())
                .setArbitratorNodeAddress(arbitratorNodeAddress.toProtoMessage())
                .setIsBuyerMakerAndSellerTaker(isBuyerMakerAndSellerTaker)
                .setMakerAccountId(makerAccountId)
                .setTakerAccountId(takerAccountId)
                .setMakerPaymentMethodId(makerPaymentMethodId)
                .setTakerPaymentMethodId(takerPaymentMethodId)
                .setMakerPaymentAccountPayloadHash(ByteString.copyFrom(makerPaymentAccountPayloadHash))
                .setTakerPaymentAccountPayloadHash(ByteString.copyFrom(takerPaymentAccountPayloadHash))
                .setMakerPubKeyRing(makerPubKeyRing.toProtoMessage())
                .setTakerPubKeyRing(takerPubKeyRing.toProtoMessage())
                .setMakerPayoutAddressString(makerPayoutAddressString)
                .setTakerPayoutAddressString(takerPayoutAddressString)
                .setMakerDepositTxHash(makerDepositTxHash);
        Optional.ofNullable(takerDepositTxHash).ifPresent(builder::setTakerDepositTxHash);
        return builder.build();
    }

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
                proto.getMakerPaymentMethodId(),
                proto.getTakerPaymentMethodId(),
                proto.getMakerPaymentAccountPayloadHash().toByteArray(),
                proto.getTakerPaymentAccountPayloadHash().toByteArray(),
                PubKeyRing.fromProto(proto.getMakerPubKeyRing()),
                PubKeyRing.fromProto(proto.getTakerPubKeyRing()),
                proto.getMakerPayoutAddressString(),
                proto.getTakerPayoutAddressString(),
                proto.getMakerDepositTxHash(),
                ProtoUtil.stringOrNullFromProto(proto.getTakerDepositTxHash()));
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

    public byte[] getBuyerPaymentAccountPayloadHash() {
        return isBuyerMakerAndSellerTaker ? makerPaymentAccountPayloadHash : takerPaymentAccountPayloadHash;
    }

    public byte[] getSellerPaymentAccountPayloadHash() {
        return isBuyerMakerAndSellerTaker ? takerPaymentAccountPayloadHash : makerPaymentAccountPayloadHash;
    }

    public String getPaymentMethodId() {
        return makerPaymentMethodId;
    }

    public BigInteger getTradeAmount() {
        return BigInteger.valueOf(tradeAmount);
    }

    public Volume getTradeVolume() {
        Volume volumeByAmount = getPrice().getVolumeByAmount(getTradeAmount());
        volumeByAmount = VolumeUtil.getAdjustedVolume(volumeByAmount, getPaymentMethodId());
        return volumeByAmount;
    }

    public Price getPrice() {
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

    public boolean isMyRoleMaker(PubKeyRing myPubKeyRing) {
        return isBuyerMakerAndSellerTaker() == isMyRoleBuyer(myPubKeyRing);
    }

    public boolean maybeClearSensitiveData() {
        return false; // TODO: anything to clear?
    }

    // edits a contract json string
    public static String sanitizeContractAsJson(String contractAsJson) {
        return contractAsJson
                .replaceAll(
                        "\"takerPaymentAccountPayload\": \\{[^}]*}",
                        "\"takerPaymentAccountPayload\": null")
                .replaceAll(
                        "\"makerPaymentAccountPayload\": \\{[^}]*}",
                        "\"makerPaymentAccountPayload\": null");
    }

    public void printDiff(@Nullable String peersContractAsJson) {
        String json = JsonUtil.objectToJson(this);
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
                ",\n     makerPaymentMethodId='" + makerPaymentMethodId + '\'' +
                ",\n     takerPaymentMethodId='" + takerPaymentMethodId + '\'' +
                ",\n     makerPaymentAccountPayloadHash=" + Utilities.bytesAsHexString(makerPaymentAccountPayloadHash) +
                ",\n     takerPaymentAccountPayloadHash=" + Utilities.bytesAsHexString(takerPaymentAccountPayloadHash) +
                ",\n     makerPubKeyRing=" + makerPubKeyRing +
                ",\n     takerPubKeyRing=" + takerPubKeyRing +
                ",\n     makerPayoutAddressString='" + makerPayoutAddressString + '\'' +
                ",\n     takerPayoutAddressString='" + takerPayoutAddressString + '\'' +
                ",\n     makerDepositTxHash='" + makerDepositTxHash + '\'' +
                ",\n     takerDepositTxHash='" + takerDepositTxHash + '\'' +
                "\n}";
    }
}
