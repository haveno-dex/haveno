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

import haveno.proto.grpc.ContractInfo;
import haveno.proto.grpc.OfferInfo;
import haveno.proto.grpc.TradeInfo;
import haveno.cli.table.column.AltcoinVolumeColumn;
import haveno.cli.table.column.BooleanColumn;
import haveno.cli.table.column.BtcColumn;
import haveno.cli.table.column.Column;
import haveno.cli.table.column.Iso8601DateTimeColumn;
import haveno.cli.table.column.MixedTradeFeeColumn;
import haveno.cli.table.column.SatoshiColumn;
import haveno.cli.table.column.StringColumn;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

import static haveno.cli.table.builder.TableBuilderConstants.*;
import static haveno.cli.table.builder.TableType.CLOSED_TRADES_TBL;
import static haveno.cli.table.builder.TableType.FAILED_TRADES_TBL;
import static haveno.cli.table.builder.TableType.OPEN_TRADES_TBL;
import static haveno.cli.table.builder.TableType.TRADE_DETAIL_TBL;
import static haveno.cli.table.column.AltcoinVolumeColumn.DISPLAY_MODE.ALTCOIN_VOLUME;
import static haveno.cli.table.column.AltcoinVolumeColumn.DISPLAY_MODE.BSQ_VOLUME;
import static haveno.cli.table.column.Column.JUSTIFICATION.LEFT;
import static haveno.cli.table.column.Column.JUSTIFICATION.RIGHT;
import static java.lang.String.format;

/**
 * Convenience for supplying column definitions to
 * open/closed/failed/detail trade table builders.
 */
@Slf4j
class TradeTableColumnSupplier {

    @Getter
    private final TableType tableType;
    @Getter
    private final List<TradeInfo> trades;

    public TradeTableColumnSupplier(TableType tableType, List<TradeInfo> trades) {
        this.tableType = tableType;
        this.trades = trades;
    }

    private final Supplier<Boolean> isTradeDetailTblBuilder = () -> getTableType().equals(TRADE_DETAIL_TBL);
    private final Supplier<Boolean> isOpenTradeTblBuilder = () -> getTableType().equals(OPEN_TRADES_TBL);
    private final Supplier<Boolean> isClosedTradeTblBuilder = () -> getTableType().equals(CLOSED_TRADES_TBL);
    private final Supplier<Boolean> isFailedTradeTblBuilder = () -> getTableType().equals(FAILED_TRADES_TBL);
    private final Supplier<TradeInfo> firstRow = () -> getTrades().get(0);
    private final Predicate<OfferInfo> isFiatOffer = (o) -> o.getBaseCurrencyCode().equals("BTC");
    private final Predicate<TradeInfo> isFiatTrade = (t) -> isFiatOffer.test(t.getOffer());
    private final Predicate<TradeInfo> isTaker = (t) -> t.getRole().toLowerCase().contains("taker");

    final Supplier<StringColumn> tradeIdColumn = () -> isTradeDetailTblBuilder.get()
            ? new StringColumn(COL_HEADER_TRADE_SHORT_ID)
            : new StringColumn(COL_HEADER_TRADE_ID);

    final Supplier<Iso8601DateTimeColumn> createDateColumn = () -> isTradeDetailTblBuilder.get()
            ? null
            : new Iso8601DateTimeColumn(COL_HEADER_DATE_TIME);

    final Supplier<StringColumn> marketColumn = () -> isTradeDetailTblBuilder.get()
            ? null
            : new StringColumn(COL_HEADER_MARKET);

    private final Function<TradeInfo, Column<String>> toDetailedPriceColumn = (t) -> {
        String colHeader = isFiatTrade.test(t)
                ? format(COL_HEADER_DETAILED_PRICE, t.getOffer().getCounterCurrencyCode())
                : format(COL_HEADER_DETAILED_PRICE_OF_ALTCOIN, t.getOffer().getBaseCurrencyCode());
        return new StringColumn(colHeader, RIGHT);
    };

    final Supplier<Column<String>> priceColumn = () -> isTradeDetailTblBuilder.get()
            ? toDetailedPriceColumn.apply(firstRow.get())
            : new StringColumn(COL_HEADER_PRICE, RIGHT);

    final Supplier<Column<String>> priceDeviationColumn = () -> isTradeDetailTblBuilder.get()
            ? null
            : new StringColumn(COL_HEADER_DEVIATION, RIGHT);

    final Supplier<StringColumn> currencyColumn = () -> isTradeDetailTblBuilder.get()
            ? null
            : new StringColumn(COL_HEADER_CURRENCY);

    private final Function<TradeInfo, Column<Long>> toDetailedAmountColumn = (t) -> {
        String headerCurrencyCode = t.getOffer().getBaseCurrencyCode();
        String colHeader = format(COL_HEADER_DETAILED_AMOUNT, headerCurrencyCode);
        AltcoinVolumeColumn.DISPLAY_MODE displayMode = headerCurrencyCode.equals("BSQ") ? BSQ_VOLUME : ALTCOIN_VOLUME;
        return isFiatTrade.test(t)
                ? new SatoshiColumn(colHeader)
                : new AltcoinVolumeColumn(colHeader, displayMode);
    };

    // Can be fiat, btc or altcoin amount represented as longs.  Placing the decimal
    // in the displayed string representation is done in the Column implementation.
    final Supplier<Column<Long>> amountColumn = () -> isTradeDetailTblBuilder.get()
            ? toDetailedAmountColumn.apply(firstRow.get())
            : new BtcColumn(COL_HEADER_AMOUNT_IN_BTC);

    final Supplier<StringColumn> mixedAmountColumn = () -> isTradeDetailTblBuilder.get()
            ? null
            : new StringColumn(COL_HEADER_AMOUNT, RIGHT);

    final Supplier<Column<Long>> minerTxFeeColumn = () -> isTradeDetailTblBuilder.get() || isClosedTradeTblBuilder.get()
            ? new SatoshiColumn(COL_HEADER_TX_FEE)
            : null;

    final Supplier<MixedTradeFeeColumn> mixedTradeFeeColumn = () -> isTradeDetailTblBuilder.get()
            ? null
            : new MixedTradeFeeColumn(COL_HEADER_TRADE_FEE);

    final Supplier<StringColumn> paymentMethodColumn = () -> isTradeDetailTblBuilder.get() || isClosedTradeTblBuilder.get()
            ? null
            : new StringColumn(COL_HEADER_PAYMENT_METHOD, LEFT);

    final Supplier<StringColumn> roleColumn = () -> {
        return isTradeDetailTblBuilder.get() || isOpenTradeTblBuilder.get() || isFailedTradeTblBuilder.get()
                ? new StringColumn(COL_HEADER_TRADE_ROLE)
                : null;
    };

    final Function<String, Column<Long>> toSecurityDepositColumn = (name) -> isClosedTradeTblBuilder.get()
            ? new SatoshiColumn(name)
            : null;

    final Supplier<StringColumn> offerTypeColumn = () -> isTradeDetailTblBuilder.get()
            ? null
            : new StringColumn(COL_HEADER_OFFER_TYPE);

    final Supplier<StringColumn> statusDescriptionColumn = () -> isTradeDetailTblBuilder.get()
            ? null
            : new StringColumn(COL_HEADER_STATUS);

    private final Function<String, Column<Boolean>> toBooleanColumn = BooleanColumn::new;

    final Supplier<Column<Boolean>> depositPublishedColumn = () -> {
        return isTradeDetailTblBuilder.get()
                ? toBooleanColumn.apply(COL_HEADER_TRADE_DEPOSIT_PUBLISHED)
                : null;
    };

    final Supplier<Column<Boolean>> depositConfirmedColumn = () -> {
        return isTradeDetailTblBuilder.get()
                ? toBooleanColumn.apply(COL_HEADER_TRADE_DEPOSIT_CONFIRMED)
                : null;

    };

    final Supplier<Column<Boolean>> payoutPublishedColumn = () -> {
        return isTradeDetailTblBuilder.get()
                ? toBooleanColumn.apply(COL_HEADER_TRADE_PAYOUT_PUBLISHED)
                : null;
    };

    final Supplier<Column<Boolean>> fundsWithdrawnColumn = () -> {
        return isTradeDetailTblBuilder.get()
                ? toBooleanColumn.apply(COL_HEADER_TRADE_WITHDRAWN)
                : null;
    };

    final Supplier<Column<Long>> havenoTradeDetailFeeColumn = () -> {
        if (isTradeDetailTblBuilder.get()) {
            TradeInfo t = firstRow.get();
            String headerCurrencyCode = "XMR";
            String colHeader = isTaker.test(t)
                    ? format(COL_HEADER_TRADE_TAKER_FEE, headerCurrencyCode)
                    : format(COL_HEADER_TRADE_MAKER_FEE, headerCurrencyCode);
            return new SatoshiColumn(colHeader, false);
        } else {
            return null;
        }
    };

    final Function<TradeInfo, String> toPaymentCurrencyCode = (t) ->
            isFiatTrade.test(t)
                    ? t.getOffer().getCounterCurrencyCode()
                    : t.getOffer().getBaseCurrencyCode();

    final Supplier<Column<Boolean>> paymentSentMessageSentColumn = () -> {
        if (isTradeDetailTblBuilder.get()) {
            String headerCurrencyCode = toPaymentCurrencyCode.apply(firstRow.get());
            String colHeader = format(COL_HEADER_TRADE_PAYMENT_SENT, headerCurrencyCode);
            return new BooleanColumn(colHeader);
        } else {
            return null;
        }
    };

    final Supplier<Column<Boolean>> paymentReceivedMessageSentColumn = () -> {
        if (isTradeDetailTblBuilder.get()) {
            String headerCurrencyCode = toPaymentCurrencyCode.apply(firstRow.get());
            String colHeader = format(COL_HEADER_TRADE_PAYMENT_RECEIVED, headerCurrencyCode);
            return new BooleanColumn(colHeader);
        } else {
            return null;
        }
    };

    final Supplier<Column<String>> tradeCostColumn = () -> {
        if (isTradeDetailTblBuilder.get()) {
            TradeInfo t = firstRow.get();
            String headerCurrencyCode = t.getOffer().getCounterCurrencyCode();
            String colHeader = format(COL_HEADER_TRADE_BUYER_COST, headerCurrencyCode);
            return new StringColumn(colHeader, RIGHT);
        } else {
            return null;
        }
    };

    final Predicate<TradeInfo> showAltCoinBuyerAddress = (t) -> {
        if (isFiatTrade.test(t)) {
            return false;
        } else {
            ContractInfo contract = t.getContract();
            boolean isBuyerMakerAndSellerTaker = contract.getIsBuyerMakerAndSellerTaker();
            if (isTaker.test(t)) {
                return !isBuyerMakerAndSellerTaker;
            } else {
                return isBuyerMakerAndSellerTaker;
            }
        }
    };

    @Nullable
    final Supplier<Column<String>> altcoinReceiveAddressColumn = () -> {
        if (isTradeDetailTblBuilder.get()) {
            TradeInfo t = firstRow.get();
            if (showAltCoinBuyerAddress.test(t)) {
                String headerCurrencyCode = toPaymentCurrencyCode.apply(t);
                String colHeader = format(COL_HEADER_TRADE_ALTCOIN_BUYER_ADDRESS, headerCurrencyCode);
                return new StringColumn(colHeader);
            } else {
                return null;
            }
        } else {
            return null;
        }
    };
}
