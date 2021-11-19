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

package bisq.core.api.model;

import bisq.common.Payload;

import lombok.AllArgsConstructor;
import lombok.ToString;

@ToString
@AllArgsConstructor
public class MarketPrice implements Payload {

    private final String currencyCode;
    private final double price;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public bisq.proto.grpc.MarketPrice toProtoMessage() {
        return bisq.proto.grpc.MarketPrice.newBuilder()
                .setPrice(price)
                .setCurrencyCode(currencyCode)
                .build();
    }

    public static MarketPrice fromProto(bisq.proto.grpc.MarketPrice proto) {
        return new MarketPrice(proto.getCurrencyCode(),
                proto.getPrice());
    }
}
