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

import haveno.cli.table.column.Column;
import haveno.cli.table.column.MixedTradeFeeColumn;
import haveno.proto.grpc.ContractInfo;
import haveno.proto.grpc.TradeInfo;

import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static haveno.cli.CurrencyFormat.formatSatoshis;
import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_BUYER_DEPOSIT;
import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_SELLER_DEPOSIT;
import static haveno.cli.table.builder.TableType.TRADE_DETAIL_TBL;
import static java.lang.String.format;
import static protobuf.OfferDirection.SELL;

abstract class AbstractTradeListBuilder extends AbstractTableBuilder {

    protected final List<TradeInfo> trades;

    protected final TradeTableColumnSupplier colSupplier;

    protected final Column<String> colTradeId;
    @Nullable
    protected final Column<Long> colCreateDate;
    @Nullable
    protected final Column<String> colMarket;
    protected final Column<String> colPrice;
    @Nullable
    protected final Column<String> colPriceDeviation;
    @Nullable
    protected final Column<String> colCurrency;
    @Nullable
    protected final Column<Long> colAmount;
    @Nullable
    protected final Column<String> colMixedAmount;
    @Nullable
    protected final MixedTradeFeeColumn colMixedTradeFee;
    @Nullable
    protected final Column<Double> colBuyerDeposit;
    @Nullable
    protected final Column<Double> colSellerDeposit;
    @Nullable
    protected final Column<String> colPaymentMethod;
    @Nullable
    protected final Column<String> colRole;
    @Nullable
    protected final Column<String> colOfferType;
    @Nullable
    protected final Column<String> colClosingStatus;

    // Trade detail tbl specific columns

    @Nullable
    protected final Column<Boolean> colIsDepositPublished;
    @Nullable
    protected final Column<Boolean> colIsDepositConfirmed;
    @Nullable
    protected final Column<Boolean> colIsPayoutPublished;
    @Nullable
    protected final Column<Boolean> colIsCompleted;
    @Nullable
    protected final Column<Long> colHavenoTradeFee;
    @Nullable
    protected final Column<String> colTradeCost;
    @Nullable
    protected final Column<Boolean> colIsPaymentSentMessageSent;
    @Nullable
    protected final Column<Boolean> colIsPaymentReceivedMessageSent;
    @Nullable
    protected final Column<String> colCryptoReceiveAddressColumn;

    AbstractTradeListBuilder(TableType tableType, List<?> protos) {
        super(tableType, protos);
        validate();

        this.trades = protos.stream().map(p -> (TradeInfo) p).collect(Collectors.toList());
        this.colSupplier = new TradeTableColumnSupplier(tableType, trades);

        this.colTradeId = colSupplier.tradeIdColumn.get();
        this.colCreateDate = colSupplier.createDateColumn.get();
        this.colMarket = colSupplier.marketColumn.get();
        this.colPrice = colSupplier.priceColumn.get();
        this.colPriceDeviation = colSupplier.priceDeviationColumn.get();
        this.colCurrency = colSupplier.currencyColumn.get();
        this.colAmount = colSupplier.amountColumn.get();
        this.colMixedAmount = colSupplier.mixedAmountColumn.get();
        this.colMixedTradeFee = colSupplier.mixedTradeFeeColumn.get();
        this.colBuyerDeposit = colSupplier.toSecurityDepositColumn.apply(COL_HEADER_BUYER_DEPOSIT);
        this.colSellerDeposit = colSupplier.toSecurityDepositColumn.apply(COL_HEADER_SELLER_DEPOSIT);
        this.colPaymentMethod = colSupplier.paymentMethodColumn.get();
        this.colRole = colSupplier.roleColumn.get();
        this.colOfferType = colSupplier.offerTypeColumn.get();
        this.colClosingStatus = colSupplier.statusDescriptionColumn.get();

        // Trade detail specific columns, some in common with BSQ swap trades detail.

        this.colIsDepositPublished = colSupplier.depositPublishedColumn.get();
        this.colIsDepositConfirmed = colSupplier.depositConfirmedColumn.get();
        this.colIsPayoutPublished = colSupplier.payoutPublishedColumn.get();
        this.colIsCompleted = colSupplier.fundsWithdrawnColumn.get();
        this.colHavenoTradeFee = colSupplier.havenoTradeDetailFeeColumn.get();
        this.colTradeCost = colSupplier.tradeCostColumn.get();
        this.colIsPaymentSentMessageSent = colSupplier.paymentSentMessageSentColumn.get();
        this.colIsPaymentReceivedMessageSent = colSupplier.paymentReceivedMessageSentColumn.get();
        //noinspection ConstantConditions
        this.colCryptoReceiveAddressColumn = colSupplier.cryptoReceiveAddressColumn.get();
    }

    protected void validate() {
        if (isTradeDetailTblBuilder.get()) {
            if (protos.size() != 1)
                throw new IllegalArgumentException("trade detail tbl can have only one row");
        } else if (protos.isEmpty()) {
            throw new IllegalArgumentException("trade tbl has no rows");
        }
    }

    // Helper Functions

    private final Supplier<Boolean> isTradeDetailTblBuilder = () -> tableType.equals(TRADE_DETAIL_TBL);
    protected final Predicate<TradeInfo> isTraditionalTrade = (t) -> isTraditionalOffer.test(t.getOffer());
    protected final Predicate<TradeInfo> isMyOffer = (t) -> t.getOffer().getIsMyOffer();
    protected final Predicate<TradeInfo> isTaker = (t) -> t.getRole().toLowerCase().contains("taker");
    protected final Predicate<TradeInfo> isSellOffer = (t) -> t.getOffer().getDirection().equals(SELL.name());
    protected final Predicate<TradeInfo> isBtcSeller = (t) -> (isMyOffer.test(t) && isSellOffer.test(t))
            || (!isMyOffer.test(t) && !isSellOffer.test(t));


    // Column Value Functions

    // Crypto volumes from server are string representations of decimals.
    // Converting them to longs ("sats") requires shifting the decimal points
    // to left:  2 for BSQ, 8 for other cryptos.
    protected final Function<TradeInfo, Long> toCryptoTradeVolumeAsLong = (t) -> new BigDecimal(t.getTradeVolume()).movePointRight(8).longValue();

    protected final Function<TradeInfo, String> toTradeVolumeAsString = (t) ->
            isTraditionalTrade.test(t)
                    ? t.getTradeVolume()
                    : formatSatoshis(t.getAmount());

    protected final Function<TradeInfo, Long> toTradeVolumeAsLong = (t) ->
            isTraditionalTrade.test(t)
                    ? Long.parseLong(t.getTradeVolume())
                    : toCryptoTradeVolumeAsLong.apply(t);

    protected final Function<TradeInfo, Long> toTradeAmount = (t) ->
            isTraditionalTrade.test(t)
                    ? t.getAmount()
                    : toTradeVolumeAsLong.apply(t);

    protected final Function<TradeInfo, String> toMarket = (t) ->
            t.getOffer().getBaseCurrencyCode() + "/"
                    + t.getOffer().getCounterCurrencyCode();

    protected final Function<TradeInfo, String> toPaymentCurrencyCode = (t) ->
            isTraditionalTrade.test(t)
                    ? t.getOffer().getCounterCurrencyCode()
                    : t.getOffer().getBaseCurrencyCode();

    protected final Function<TradeInfo, String> toPriceDeviation = (t) ->
            t.getOffer().getUseMarketBasedPrice()
                    ? format("%.2f%s", t.getOffer().getMarketPriceMarginPct(), "%")
                    : "N/A";

    protected final Function<TradeInfo, Long> toTradeFeeBtc = (t) -> {
        var isMyOffer = t.getOffer().getIsMyOffer();
        if (isMyOffer) {
            return t.getOffer().getMakerFee();
        } else {
            return t.getTakerFee();
        }
    };

    protected final Function<TradeInfo, Long> toMyMakerOrTakerFee = (t) -> {
        return isTaker.test(t)
                ? t.getTakerFee()
                : t.getOffer().getMakerFee();
    };

    protected final Function<TradeInfo, String> toOfferType = (t) -> {
        if (isTraditionalTrade.test(t)) {
            return t.getOffer().getDirection() + " " + t.getOffer().getBaseCurrencyCode();
        } else {
            if (t.getOffer().getDirection().equals("BUY")) {
                return "SELL " + t.getOffer().getBaseCurrencyCode();
            } else {
                return "BUY " + t.getOffer().getBaseCurrencyCode();
            }
        }
    };

    protected final Predicate<TradeInfo> showCryptoBuyerAddress = (t) -> {
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

    protected final Function<TradeInfo, String> toCryptoReceiveAddress = (t) -> {
        if (showCryptoBuyerAddress.test(t)) {
            ContractInfo contract = t.getContract();
            boolean isBuyerMakerAndSellerTaker = contract.getIsBuyerMakerAndSellerTaker();
            return isBuyerMakerAndSellerTaker  // (is BTC buyer / maker)
                    ? contract.getTakerPaymentAccountPayload().getCryptoCurrencyAccountPayload().getAddress()
                    : contract.getMakerPaymentAccountPayload().getCryptoCurrencyAccountPayload().getAddress();
        } else {
            return "";
        }
    };
}
