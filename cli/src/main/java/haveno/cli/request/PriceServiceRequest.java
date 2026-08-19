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
import haveno.proto.grpc.MarketDepthInfo;
import haveno.proto.grpc.MarketDepthRequest;
import haveno.proto.grpc.MarketPriceInfo;
import haveno.proto.grpc.MarketPriceRequest;
import haveno.proto.grpc.MarketPricesRequest;

import java.util.List;

public class PriceServiceRequest {

    private final GrpcStubs grpcStubs;

    public PriceServiceRequest(GrpcStubs grpcStubs) {
        this.grpcStubs = grpcStubs;
    }

    public double getXmrPrice(String currencyCode) {
        var request = MarketPriceRequest.newBuilder()
                .setCurrencyCode(currencyCode)
                .build();
        return grpcStubs.priceService.getMarketPrice(request).getPrice();
    }

    public List<MarketPriceInfo> getXmrPrices() {
        var request = MarketPricesRequest.newBuilder().build();
        return grpcStubs.priceService.getMarketPrices(request).getMarketPriceList();
    }

    public MarketDepthInfo getMarketDepth(String currencyCode) {
        var request = MarketDepthRequest.newBuilder()
                .setCurrencyCode(currencyCode)
                .build();
        return grpcStubs.priceService.getMarketDepth(request).getMarketDepth();
    }
}
