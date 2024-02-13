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

package haveno.core.api.model;

import lombok.AllArgsConstructor;
import lombok.ToString;

import java.util.Arrays;

@ToString
@AllArgsConstructor
public class MarketDepthInfo {
    public final String currencyCode;
    public final Double[] buyPrices;
    public final Double[] buyDepth;
    public final Double[] sellPrices;
    public final Double[] sellDepth;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    // @Override
    public haveno.proto.grpc.MarketDepthInfo toProtoMessage() {
        return haveno.proto.grpc.MarketDepthInfo.newBuilder()
        .setCurrencyCode(currencyCode)
        .addAllBuyPrices(Arrays.asList(buyPrices))
        .addAllBuyDepth(Arrays.asList((buyDepth)))
        .addAllSellPrices(Arrays.asList(sellPrices))
        .addAllSellDepth(Arrays.asList(sellDepth))
        .build();
    }
}
