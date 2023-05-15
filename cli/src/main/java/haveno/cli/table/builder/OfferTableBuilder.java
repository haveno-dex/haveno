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
import haveno.cli.table.column.Column;
import haveno.cli.table.column.Iso8601DateTimeColumn;
import haveno.cli.table.column.SatoshiColumn;
import haveno.cli.table.column.StringColumn;
import haveno.cli.table.column.ZippedStringColumns;
import haveno.proto.grpc.OfferInfo;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_AMOUNT_RANGE;
import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_CREATION_DATE;
import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_DETAILED_PRICE;
import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_DETAILED_PRICE_OF_CRYPTO;
import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_DIRECTION;
import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_ENABLED;
import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_PAYMENT_METHOD;
import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_TRIGGER_PRICE;
import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_UUID;
import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_VOLUME_RANGE;
import static haveno.cli.table.builder.TableType.OFFER_TBL;
import static haveno.cli.table.column.Column.JUSTIFICATION.LEFT;
import static haveno.cli.table.column.Column.JUSTIFICATION.NONE;
import static haveno.cli.table.column.Column.JUSTIFICATION.RIGHT;
import static haveno.cli.table.column.ZippedStringColumns.DUPLICATION_MODE.EXCLUDE_DUPLICATES;
import static java.lang.String.format;
import static protobuf.OfferDirection.BUY;
import static protobuf.OfferDirection.SELL;

/**
 * Builds a {@code haveno.cli.table.Table} from a List of
 * {@code haveno.proto.grpc.OfferInfo} objects.
 */
class OfferTableBuilder extends AbstractTableBuilder {

    // Columns common to both traditional and cryptocurrency offers.
    private final Column<String> colOfferId = new StringColumn(COL_HEADER_UUID, LEFT);
    private final Column<String> colDirection = new StringColumn(COL_HEADER_DIRECTION, LEFT);
    private final Column<Long> colAmount = new SatoshiColumn("Temp Amount", NONE);
    private final Column<Long> colMinAmount = new SatoshiColumn("Temp Min Amount", NONE);
    private final Column<String> colPaymentMethod = new StringColumn(COL_HEADER_PAYMENT_METHOD, LEFT);
    private final Column<Long> colCreateDate = new Iso8601DateTimeColumn(COL_HEADER_CREATION_DATE);

    OfferTableBuilder(List<?> protos) {
        super(OFFER_TBL, protos);
    }

    @Override
    public Table build() {
        List<OfferInfo> offers = protos.stream().map(p -> (OfferInfo) p).collect(Collectors.toList());
        return isShowingTraditionalOffers.get()
                ? buildTraditionalOfferTable(offers)
                : buildCryptoCurrencyOfferTable(offers);
    }

    @SuppressWarnings("ConstantConditions")
    public Table buildTraditionalOfferTable(List<OfferInfo> offers) {
        @Nullable
        Column<String> colEnabled = enabledColumn.get(); // Not boolean: "YES", "NO", or "PENDING"
        Column<String> colTraditionalPrice = new StringColumn(format(COL_HEADER_DETAILED_PRICE, traditionalTradeCurrency.get()), RIGHT);
        Column<String> colVolume = new StringColumn(format("Temp Volume (%s)", traditionalTradeCurrency.get()), NONE);
        Column<String> colMinVolume = new StringColumn(format("Temp Min Volume (%s)", traditionalTradeCurrency.get()), NONE);
        @Nullable
        Column<String> colTriggerPrice = traditionalTriggerPriceColumn.get();

        // Populate columns with offer info.

        offers.forEach(o -> {
            if (colEnabled != null)
                colEnabled.addRow(toEnabled.apply(o));

            colDirection.addRow(o.getDirection());
            colTraditionalPrice.addRow(o.getPrice());
            colMinAmount.addRow(o.getMinAmount());
            colAmount.addRow(o.getAmount());
            colVolume.addRow(o.getVolume());
            colMinVolume.addRow(o.getMinVolume());

            if (colTriggerPrice != null)
                colTriggerPrice.addRow(toBlankOrNonZeroValue.apply(o.getTriggerPrice()));

            colPaymentMethod.addRow(o.getPaymentMethodShortName());
            colCreateDate.addRow(o.getDate());
            colOfferId.addRow(o.getId());
        });

        ZippedStringColumns amountRange = zippedAmountRangeColumns.get();
        ZippedStringColumns volumeRange =
                new ZippedStringColumns(format(COL_HEADER_VOLUME_RANGE, traditionalTradeCurrency.get()),
                        RIGHT,
                        " - ",
                        colMinVolume.asStringColumn(),
                        colVolume.asStringColumn());

        // Define and return the table instance with populated columns.

        if (isShowingMyOffers.get()) {
            return new Table(colEnabled.asStringColumn(),
                    colDirection,
                    colTraditionalPrice.justify(),
                    amountRange.asStringColumn(EXCLUDE_DUPLICATES),
                    volumeRange.asStringColumn(EXCLUDE_DUPLICATES),
                    colTriggerPrice.justify(),
                    colPaymentMethod,
                    colCreateDate.asStringColumn(),
                    colOfferId);
        } else {
            return new Table(colDirection,
                    colTraditionalPrice.justify(),
                    amountRange.asStringColumn(EXCLUDE_DUPLICATES),
                    volumeRange.asStringColumn(EXCLUDE_DUPLICATES),
                    colPaymentMethod,
                    colCreateDate.asStringColumn(),
                    colOfferId);
        }
    }

    @SuppressWarnings("ConstantConditions")
    public Table buildCryptoCurrencyOfferTable(List<OfferInfo> offers) {
        @Nullable
        Column<String> colEnabled = enabledColumn.get(); // Not boolean: YES, NO, or PENDING
        Column<String> colBtcPrice = new StringColumn(format(COL_HEADER_DETAILED_PRICE_OF_CRYPTO, cryptoTradeCurrency.get()), RIGHT);
        Column<String> colVolume = new StringColumn(format("Temp Volume (%s)", cryptoTradeCurrency.get()), NONE);
        Column<String> colMinVolume = new StringColumn(format("Temp Min Volume (%s)", cryptoTradeCurrency.get()), NONE);
        @Nullable
        Column<String> colTriggerPrice = cryptoTriggerPriceColumn.get();

        // Populate columns with offer info.

        offers.forEach(o -> {
            if (colEnabled != null)
                colEnabled.addRow(toEnabled.apply(o));

            colDirection.addRow(directionFormat.apply(o));
            colBtcPrice.addRow(o.getPrice());
            colAmount.addRow(o.getAmount());
            colMinAmount.addRow(o.getMinAmount());
            colVolume.addRow(o.getVolume());
            colMinVolume.addRow(o.getMinVolume());

            if (colTriggerPrice != null)
                colTriggerPrice.addRow(toBlankOrNonZeroValue.apply(o.getTriggerPrice()));

            colPaymentMethod.addRow(o.getPaymentMethodShortName());
            colCreateDate.addRow(o.getDate());
            colOfferId.addRow(o.getId());
        });

        ZippedStringColumns amountRange = zippedAmountRangeColumns.get();
        ZippedStringColumns volumeRange =
                new ZippedStringColumns(format(COL_HEADER_VOLUME_RANGE, cryptoTradeCurrency.get()),
                        RIGHT,
                        " - ",
                        colMinVolume.asStringColumn(),
                        colVolume.asStringColumn());

        // Define and return the table instance with populated columns.

        if (isShowingMyOffers.get()) {
            if (isShowingBsqOffers.get()) {
                return new Table(colEnabled.asStringColumn(),
                        colDirection,
                        colBtcPrice.justify(),
                        amountRange.asStringColumn(EXCLUDE_DUPLICATES),
                        volumeRange.asStringColumn(EXCLUDE_DUPLICATES),
                        colPaymentMethod,
                        colCreateDate.asStringColumn(),
                        colOfferId);
            } else {
                return new Table(colEnabled.asStringColumn(),
                        colDirection,
                        colBtcPrice.justify(),
                        amountRange.asStringColumn(EXCLUDE_DUPLICATES),
                        volumeRange.asStringColumn(EXCLUDE_DUPLICATES),
                        colTriggerPrice.justify(),
                        colPaymentMethod,
                        colCreateDate.asStringColumn(),
                        colOfferId);
            }
        } else {
            return new Table(colDirection,
                    colBtcPrice.justify(),
                    amountRange.asStringColumn(EXCLUDE_DUPLICATES),
                    volumeRange.asStringColumn(EXCLUDE_DUPLICATES),
                    colPaymentMethod,
                    colCreateDate.asStringColumn(),
                    colOfferId);
        }
    }

    private final Function<String, String> toBlankOrNonZeroValue = (s) -> s.trim().equals("0") ? "" : s;
    private final Supplier<OfferInfo> firstOfferInList = () -> (OfferInfo) protos.get(0);
    private final Supplier<Boolean> isShowingMyOffers = () -> firstOfferInList.get().getIsMyOffer();
    private final Supplier<Boolean> isShowingTraditionalOffers = () -> isTraditionalOffer.test(firstOfferInList.get());
    private final Supplier<String> traditionalTradeCurrency = () -> firstOfferInList.get().getCounterCurrencyCode();
    private final Supplier<String> cryptoTradeCurrency = () -> firstOfferInList.get().getBaseCurrencyCode();
    private final Supplier<Boolean> isShowingBsqOffers = () ->
            !isTraditionalOffer.test(firstOfferInList.get()) && cryptoTradeCurrency.get().equals("BSQ");

    @Nullable  // Not a boolean column: YES, NO, or PENDING.
    private final Supplier<StringColumn> enabledColumn = () ->
            isShowingMyOffers.get()
                    ? new StringColumn(COL_HEADER_ENABLED, LEFT)
                    : null;
    @Nullable
    private final Supplier<StringColumn> traditionalTriggerPriceColumn = () ->
            isShowingMyOffers.get()
                    ? new StringColumn(format(COL_HEADER_TRIGGER_PRICE, traditionalTradeCurrency.get()), RIGHT)
                    : null;
    @Nullable
    private final Supplier<StringColumn> cryptoTriggerPriceColumn = () ->
            isShowingMyOffers.get() && !isShowingBsqOffers.get()
                    ? new StringColumn(format(COL_HEADER_TRIGGER_PRICE, cryptoTradeCurrency.get()), RIGHT)
                    : null;

    private final Function<OfferInfo, String> toEnabled = (o) -> {
        return o.getIsActivated() ? "YES" : "NO";
    };

    private final Function<String, String> toMirroredDirection = (d) ->
            d.equalsIgnoreCase(BUY.name()) ? SELL.name() : BUY.name();

    private final Function<OfferInfo, String> directionFormat = (o) -> {
        if (isTraditionalOffer.test(o)) {
            return o.getBaseCurrencyCode();
        } else {
            // Return "Sell BSQ (Buy BTC)", or "Buy BSQ (Sell BTC)".
            String direction = o.getDirection();
            String mirroredDirection = toMirroredDirection.apply(direction);
            Function<String, String> mixedCase = (word) -> word.charAt(0) + word.substring(1).toLowerCase();
            return format("%s %s (%s %s)",
                    mixedCase.apply(mirroredDirection),
                    o.getBaseCurrencyCode(),
                    mixedCase.apply(direction),
                    o.getCounterCurrencyCode());
        }
    };

    private final Supplier<ZippedStringColumns> zippedAmountRangeColumns = () -> {
        if (colMinAmount.isEmpty() || colAmount.isEmpty())
            throw new IllegalStateException("amount columns must have data");

        return new ZippedStringColumns(COL_HEADER_AMOUNT_RANGE,
                RIGHT,
                " - ",
                colMinAmount.asStringColumn(),
                colAmount.asStringColumn());
    };
}
