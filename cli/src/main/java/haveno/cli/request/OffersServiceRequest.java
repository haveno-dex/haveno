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
import haveno.proto.grpc.OfferInfo;
import haveno.proto.grpc.PostOfferRequest;

import java.util.ArrayList;
import java.util.List;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;
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
                "0" /* no trigger price */);
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
                                 String triggerPrice) {
        var request = PostOfferRequest.newBuilder()
                .setDirection(direction)
                .setCurrencyCode(currencyCode)
                .setAmount(amount)
                .setMinAmount(minAmount)
                .setUseMarketBasedPrice(useMarketBasedPrice)
                .setPrice(fixedPrice)
                .setMarketPriceMarginPct(marketPriceMarginPct)
                .setSecurityDepositPct(securityDepositPct)
                .setPaymentAccountId(paymentAcctId)
                .setTriggerPrice(triggerPrice)
                .build();
        return grpcStubs.offersService.postOffer(request).getOffer();
    }

    public void cancelOffer(String offerId) {
        var request = CancelOfferRequest.newBuilder()
                .setId(offerId)
                .build();
        //noinspection ResultOfMethodCallIgnored
        grpcStubs.offersService.cancelOffer(request);
    }

    public OfferInfo getOffer(String offerId) {
        var request = GetOfferRequest.newBuilder()
                .setId(offerId)
                .build();
        return grpcStubs.offersService.getOffer(request).getOffer();
    }

    public OfferInfo getMyOffer(String offerId) {
        var request = GetMyOfferRequest.newBuilder()
                .setId(offerId)
                .build();
        return grpcStubs.offersService.getMyOffer(request).getOffer();
    }

    public List<OfferInfo> getOffers(String direction, String currencyCode) {
        var request = GetOffersRequest.newBuilder()
                .setDirection(direction)
                .setCurrencyCode(currencyCode)
                .build();
        return grpcStubs.offersService.getOffers(request).getOffersList();
    }

    public List<OfferInfo> getOffersSortedByDate(String currencyCode) {
        ArrayList<OfferInfo> offers = new ArrayList<>();
        offers.addAll(getOffers(BUY.name(), currencyCode));
        offers.addAll(getOffers(SELL.name(), currencyCode));
        return offers.isEmpty() ? offers : sortOffersByDate(offers);
    }

    public List<OfferInfo> getOffersSortedByDate(String direction, String currencyCode) {
        var offers = getOffers(direction, currencyCode);
        return offers.isEmpty() ? offers : sortOffersByDate(offers);
    }

    public List<OfferInfo> getMyOffers(String direction, String currencyCode) {
        var request = GetMyOffersRequest.newBuilder()
                .setDirection(direction)
                .setCurrencyCode(currencyCode)
                .build();
        return grpcStubs.offersService.getMyOffers(request).getOffersList();
    }

    public List<OfferInfo> getMyOffersSortedByDate(String currencyCode) {
        ArrayList<OfferInfo> offers = new ArrayList<>();
        offers.addAll(getMyOffers(BUY.name(), currencyCode));
        offers.addAll(getMyOffers(SELL.name(), currencyCode));
        return offers.isEmpty() ? offers : sortOffersByDate(offers);
    }

    public List<OfferInfo> getMyOffersSortedByDate(String direction, String currencyCode) {
        var offers = getMyOffers(direction, currencyCode);
        return offers.isEmpty() ? offers : sortOffersByDate(offers);
    }

    public OfferInfo getMostRecentOffer(String direction, String currencyCode) {
        List<OfferInfo> offers = getOffersSortedByDate(direction, currencyCode);
        return offers.isEmpty() ? null : offers.get(offers.size() - 1);
    }

    public List<OfferInfo> sortOffersByDate(List<OfferInfo> offerInfoList) {
        return offerInfoList.stream()
                .sorted(comparing(OfferInfo::getDate))
                .collect(toList());
    }
}
