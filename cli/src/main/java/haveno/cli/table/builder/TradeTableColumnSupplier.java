package haveno.cli.table.builder;

import haveno.cli.table.column.BooleanColumn;
import haveno.cli.table.column.Column;
import haveno.cli.table.column.CryptoVolumeColumn;
import haveno.cli.table.column.DoubleColumn;
import haveno.cli.table.column.Iso8601DateTimeColumn;
import haveno.cli.table.column.MixedTradeFeeColumn;
import haveno.cli.table.column.PiconeroColumn;
import haveno.cli.table.column.StringColumn;
import haveno.cli.table.column.XmrColumn;
import haveno.proto.grpc.ContractInfo;
import haveno.proto.grpc.OfferInfo;
import haveno.proto.grpc.TradeInfo;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

// Suppressed: Star imports required for compilation due to many constants
// CHECKSTYLE:OFF AvoidStarImport
import static haveno.cli.table.builder.TableBuilderConstants.*;
import static haveno.cli.table.builder.TableType.*;
// CHECKSTYLE:ON AvoidStarImport
import static haveno.cli.table.column.CryptoVolumeColumn.DISPLAY_MODE.BSQ_VOLUME;
import static haveno.cli.table.column.CryptoVolumeColumn.DISPLAY_MODE.CRYPTO_VOLUME;
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

    private final Supplier<Boolean> isTradeDetailTblBuilder = () -> this.getTableType().equals(TRADE_DETAIL_TBL);
    private final Supplier<Boolean> isOpenTradeTblBuilder = () -> this.getTableType().equals(OPEN_TRADES_TBL);
    private final Supplier<Boolean> isClosedTradeTblBuilder = () -> this.getTableType().equals(CLOSED_TRADES_TBL);
    private final Supplier<Boolean> isFailedTradeTblBuilder = () -> this.getTableType().equals(FAILED_TRADES_TBL);
    private final Supplier<TradeInfo> firstRow = () -> this.getTrades().get(0);
    private final Predicate<OfferInfo> isTraditionalOffer = (o) -> o.getBaseCurrencyCode().equals("XMR");
    private final Predicate<TradeInfo> isTraditionalTrade = (t) -> isTraditionalOffer.test(t.getOffer());
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
        String colHeader = isTraditionalTrade.test(t)
                ? format(COL_HEADER_DETAILED_PRICE, t.getOffer().getCounterCurrencyCode())
                : format(COL_HEADER_DETAILED_PRICE_OF_CRYPTO, t.getOffer().getBaseCurrencyCode());
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
        CryptoVolumeColumn.DISPLAY_MODE displayMode = headerCurrencyCode.equals("BSQ") ? BSQ_VOLUME : CRYPTO_VOLUME;
        return isTraditionalTrade.test(t)
                ? new PiconeroColumn(colHeader)
                : new CryptoVolumeColumn(colHeader, displayMode);
    };

    final Supplier<Column<Long>> amountColumn = () -> isTradeDetailTblBuilder.get()
            ? toDetailedAmountColumn.apply(firstRow.get())
            : new XmrColumn(COL_HEADER_AMOUNT_IN_XMR);

    final Supplier<StringColumn> mixedAmountColumn = () -> isTradeDetailTblBuilder.get()
            ? null
            : new StringColumn(COL_HEADER_AMOUNT, RIGHT);

    final Supplier<Column<Long>> minerTxFeeColumn = () -> isTradeDetailTblBuilder.get() || isClosedTradeTblBuilder.get()
            ? new PiconeroColumn(COL_HEADER_TX_FEE)
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

    final Function<String, Column<Double>> toSecurityDepositColumn = (name) -> isClosedTradeTblBuilder.get()
            ? new DoubleColumn(name)
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
            return new PiconeroColumn(colHeader, RIGHT);
        } else {
            return null;
        }
    };

    final Function<TradeInfo, String> toPaymentCurrencyCode = (t) ->
            isTraditionalTrade.test(t)
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

    final Predicate<TradeInfo> showCryptoBuyerAddress = (t) -> {
        if (isTraditionalTrade.test(t)) {
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
    final Supplier<Column<String>> cryptoReceiveAddressColumn = () -> {
        if (isTradeDetailTblBuilder.get()) {
            TradeInfo t = firstRow.get();
            if (showCryptoBuyerAddress.test(t)) {
                String headerCurrencyCode = toPaymentCurrencyCode.apply(t);
                String colHeader = format(COL_HEADER_TRADE_CRYPTO_BUYER_ADDRESS, headerCurrencyCode);
                return new StringColumn(colHeader);
            } else {
                return null;
            }
        } else {
            return null;
        }
    };
}
