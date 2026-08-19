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

package haveno.cli.request;

import haveno.cli.GrpcStubs;
import haveno.proto.grpc.ActivateOfferRequest;
import haveno.proto.grpc.CancelOfferRequest;
import haveno.proto.grpc.DeactivateOfferRequest;
import haveno.proto.grpc.EditOfferRequest;
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
                                            String paymentAcctId) {
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
        return createOffer(direction,
                currencyCode,
                amount,
                minAmount,
                useMarketBasedPrice,
                fixedPrice,
                marketPriceMarginPct,
                securityDepositPct,
                paymentAcctId,
                triggerPrice,
                false,
                "");
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
                                 String extraInfo) {
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
                .setReserveExactAmount(reserveExactAmount)
                .setExtraInfo(extraInfo)
                .build();
        return grpcStubs.offersService.postOffer(request).getOffer();
    }

    public OfferInfo editOffer(String offerId,
                               String currencyCode,
                               String price,
                               boolean useMarketBasedPrice,
                               double marketPriceMarginPct,
                               String triggerPrice,
                               String paymentAcctId,
                               String extraInfo) {
        var request = EditOfferRequest.newBuilder()
                .setOfferId(offerId)
                .setCurrencyCode(currencyCode)
                .setPrice(price)
                .setUseMarketBasedPrice(useMarketBasedPrice)
                .setMarketPriceMarginPct(marketPriceMarginPct)
                .setTriggerPrice(triggerPrice)
                .setPaymentAccountId(paymentAcctId)
                .setExtraInfo(extraInfo)
                .build();
        return grpcStubs.offersService.editOffer(request).getOffer();
    }

    public void activateOffer(String offerId) {
        var request = ActivateOfferRequest.newBuilder()
                .setOfferId(offerId)
                .build();
        //noinspection ResultOfMethodCallIgnored
        grpcStubs.offersService.activateOffer(request);
    }

    public void deactivateOffer(String offerId) {
        var request = DeactivateOfferRequest.newBuilder()
                .setOfferId(offerId)
                .build();
        //noinspection ResultOfMethodCallIgnored
        grpcStubs.offersService.deactivateOffer(request);
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

    @Deprecated // Since 5-Dec-2021.
    // Endpoint to be removed from future version.  Use getOffer service method instead.
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
        return sortOffersByDate(offers);
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
        return sortOffersByDate(offers);
    }

    public List<OfferInfo> getMyOffersSortedByDate(String direction, String currencyCode) {
        var offers = getMyOffers(direction, currencyCode);
        return offers.isEmpty() ? offers : sortOffersByDate(offers);
    }

    public List<OfferInfo> sortOffersByDate(List<OfferInfo> offerInfoList) {
        return offerInfoList.stream()
                .sorted(comparing(OfferInfo::getDate))
                .collect(toList());
    }
}
