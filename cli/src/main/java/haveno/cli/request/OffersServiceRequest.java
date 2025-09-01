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

package haveno.cli.request;

import haveno.cli.GrpcStubs;
import haveno.proto.grpc.CancelOfferRequest;
import haveno.proto.grpc.GetMyOfferRequest;
import haveno.proto.grpc.GetMyOffersRequest;
import haveno.proto.grpc.GetOfferRequest;
import haveno.proto.grpc.GetOffersRequest;
import haveno.proto.grpc.GetOffersReply;
import haveno.proto.grpc.GetMyOffersReply;
import haveno.proto.grpc.GetMyOfferReply;
import haveno.proto.grpc.GetOfferReply;
import haveno.proto.grpc.OfferInfo;
import haveno.proto.grpc.PostOfferRequest;
import haveno.proto.grpc.PostOfferReply;



import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.stream.Collectors;

import static protobuf.OfferDirection.BUY;
import static protobuf.OfferDirection.SELL;

public class OffersServiceRequest {

    private final GrpcStubs grpcStubs;

    public OffersServiceRequest(GrpcStubs grpcStubs) {
        this.grpcStubs = grpcStubs;
    }

    @SuppressWarnings("unused")
    public OfferInfo createFixedPricedOffer(String direction,
                                            String currencyCode,
                                            long amount,
                                            long minAmount,
                                            String fixedPrice,
                                            double securityDepositPct,
                                            String paymentAcctId,
                                            String makerFeeCurrencyCode) {
        return createOffer(direction,
                currencyCode,
                amount,
                minAmount,
                false,
                fixedPrice,
                0.00,
                securityDepositPct,
                paymentAcctId,
                "0", /* no trigger price */
                false, /* reserveExactAmount */
                false, /* isPrivateOffer */
                false, /* buyerAsTakerWithoutDeposit */
                "", /* extraInfo */
                "" /* sourceOfferId */);
    }

    public OfferInfo createOffer(String direction,
                                 String currencyCode,
                                 long amount,
                                 long minAmount,
                                 boolean useMarketBasedPrice,
                                 String fixedPrice,
                                 double marketPriceMarginPct,
                                 double securityDepositPct,
                                 String paymentAcctId,
                                 String triggerPrice,
                                 boolean reserveExactAmount,
                                 boolean isPrivateOffer,
                                 boolean buyerAsTakerWithoutDeposit,
                                 String extraInfo,
                                 String sourceOfferId) {
        PostOfferRequest request = PostOfferRequest.newBuilder()
                .setCurrencyCode(currencyCode)
                .setDirection(direction)
                .setPrice(fixedPrice)
                .setUseMarketBasedPrice(useMarketBasedPrice)
                .setMarketPriceMarginPct(marketPriceMarginPct)
                .setAmount(amount)
                .setMinAmount(minAmount)
                .setSecurityDepositPct(securityDepositPct)
                .setTriggerPrice(triggerPrice)
                .setReserveExactAmount(reserveExactAmount)
                .setPaymentAccountId(paymentAcctId)
                .setIsPrivateOffer(isPrivateOffer)
                .setBuyerAsTakerWithoutDeposit(buyerAsTakerWithoutDeposit)
                .setExtraInfo(extraInfo)
                .setSourceOfferId(sourceOfferId)
                .build();
        PostOfferReply reply = grpcStubs.offersService.postOffer(request);
        return reply.getOffer();
    }

    public void cancelOffer(String offerId) {
        CancelOfferRequest request = CancelOfferRequest.newBuilder()
                .setId(offerId)
                .build();
        grpcStubs.offersService.cancelOffer(request);
    }

    public OfferInfo getOffer(String offerId) {
        GetOfferRequest request = GetOfferRequest.newBuilder()
                .setId(offerId)
                .build();
        GetOfferReply reply = grpcStubs.offersService.getOffer(request);
        return reply.getOffer();
    }

    public OfferInfo getMyOffer(String offerId) {
        GetMyOfferRequest request = GetMyOfferRequest.newBuilder()
                .setId(offerId)
                .build();
        GetMyOfferReply reply = grpcStubs.offersService.getMyOffer(request);
        return reply.getOffer();
    }

    public List<OfferInfo> getOffers(String direction, String currencyCode) {
        GetOffersRequest request = GetOffersRequest.newBuilder()
                .setDirection(direction)
                .setCurrencyCode(currencyCode)
                .build();
        GetOffersReply reply = grpcStubs.offersService.getOffers(request);
        return reply.getOffersList();
    }

    public List<OfferInfo> getOffersSortedByDate(String currencyCode) {
        ArrayList<OfferInfo> offers = new ArrayList<>();
        offers.addAll(getOffers(BUY.name(), currencyCode));
        offers.addAll(getOffers(SELL.name(), currencyCode));
        return offers.isEmpty() ? offers : sortOffersByDate(offers);
    }

    public List<OfferInfo> getOffersSortedByDate(String direction, String currencyCode) {
        List<OfferInfo> offers = getOffers(direction, currencyCode);
        return offers.isEmpty() ? offers : sortOffersByDate(offers);
    }

    public List<OfferInfo> getMyOffers(String direction, String currencyCode) {
        GetMyOffersRequest request = GetMyOffersRequest.newBuilder()
                .setDirection(direction)
                .setCurrencyCode(currencyCode)
                .build();
        GetMyOffersReply reply = grpcStubs.offersService.getMyOffers(request);
        return reply.getOffersList();
    }

    public List<OfferInfo> getMyOffersSortedByDate(String currencyCode) {
        ArrayList<OfferInfo> offers = new ArrayList<>();
        offers.addAll(getMyOffers(BUY.name(), currencyCode));
        offers.addAll(getMyOffers(SELL.name(), currencyCode));
        return offers.isEmpty() ? offers : sortOffersByDate(offers);
    }

    public List<OfferInfo> getMyOffersSortedByDate(String direction, String currencyCode) {
        List<OfferInfo> offers = getMyOffers(direction, currencyCode);
        return offers.isEmpty() ? offers : sortOffersByDate(offers);
    }

    public OfferInfo getMostRecentOffer(String direction, String currencyCode) {
        List<OfferInfo> offers = getOffersSortedByDate(direction, currencyCode);
        return offers.isEmpty() ? null : offers.get(offers.size() - 1);
    }

    public List<OfferInfo> sortOffersByDate(List<OfferInfo> offerInfoList) {
        return offerInfoList.stream()
                .sorted(Comparator.comparing(OfferInfo::getDate))
                .collect(Collectors.toList());
    }
}
