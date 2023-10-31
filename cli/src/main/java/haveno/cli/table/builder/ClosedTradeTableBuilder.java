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

package haveno.cli.table.builder;

import haveno.cli.table.Table;

import java.util.List;

import static haveno.cli.table.builder.TableType.CLOSED_TRADES_TBL;

@SuppressWarnings("ConstantConditions")
class ClosedTradeTableBuilder extends AbstractTradeListBuilder {

    ClosedTradeTableBuilder(List<?> protos) {
        super(CLOSED_TRADES_TBL, protos);
    }

    @Override
    public Table build() {
        populateColumns();
        return new Table(colTradeId,
                colCreateDate.asStringColumn(),
                colMarket,
                colPrice.justify(),
                colPriceDeviation.justify(),
                colAmount.asStringColumn(),
                colMixedAmount.justify(),
                colCurrency,
                colMixedTradeFee.asStringColumn(),
                colBuyerDeposit.asStringColumn(),
                colSellerDeposit.asStringColumn(),
                colOfferType,
                colClosingStatus);
    }

    private void populateColumns() {
        trades.forEach(t -> {
            colTradeId.addRow(t.getTradeId());
            colCreateDate.addRow(t.getDate());
            colMarket.addRow(toMarket.apply(t));
            colPrice.addRow(t.getPrice());
            colPriceDeviation.addRow(toPriceDeviation.apply(t));
            colAmount.addRow(t.getAmount());
            colMixedAmount.addRow(t.getTradeVolume());
            colCurrency.addRow(toPaymentCurrencyCode.apply(t));

            colMixedTradeFee.addRow(toTradeFeeBtc.apply(t), false);

            colBuyerDeposit.addRow(t.getOffer().getBuyerSecurityDepositPct());
            colSellerDeposit.addRow(t.getOffer().getSellerSecurityDepositPct());
            colOfferType.addRow(toOfferType.apply(t));
        });
    }
}
