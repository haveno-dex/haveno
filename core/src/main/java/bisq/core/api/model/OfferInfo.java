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

package bisq.core.api.model;

import bisq.core.api.model.builder.OfferInfoBuilder;
import bisq.core.monetary.Price;
import bisq.core.offer.Offer;
import bisq.core.offer.OpenOffer;
import bisq.core.trade.HavenoUtils;
import bisq.common.Payload;
import bisq.common.proto.ProtoUtil;
import java.util.Optional;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import static bisq.common.util.MathUtils.exactMultiply;
import static bisq.core.util.PriceUtil.reformatMarketPrice;
import static bisq.core.util.VolumeUtil.formatVolume;
import static java.util.Objects.requireNonNull;

@EqualsAndHashCode
@ToString
@Getter
public class OfferInfo implements Payload {

    // The client cannot see bisq.core.Offer or its fromProto method.  We use the lighter
    // weight OfferInfo proto wrapper instead, containing just enough fields to view,
    // create and take offers.

    private final String id;
    private final String direction;
    private final String price;
    private final boolean useMarketBasedPrice;
    private final double marketPriceMarginPct;
    private final long amount;
    private final long minAmount;
    private final String volume;
    private final String minVolume;
    private final long txFee;
    private final long makerFee;
    @Nullable
    private final String offerFeePaymentTxId;
    private final long buyerSecurityDeposit;
    private final long sellerSecurityDeposit;
    private final String triggerPrice;
    private final String paymentAccountId;
    private final String paymentMethodId;
    private final String paymentMethodShortName;
    // For fiat offer the baseCurrencyCode is BTC and the counterCurrencyCode is the fiat currency
    // For altcoin offers it is the opposite. baseCurrencyCode is the altcoin and the counterCurrencyCode is BTC.
    private final String baseCurrencyCode;
    private final String counterCurrencyCode;
    private final long date;
    private final String state;
    private final boolean isActivated;
    private final boolean isMyOffer;
    private final String ownerNodeAddress;
    private final String pubKeyRing;
    private final String versionNumber;
    private final int protocolVersion;
    @Nullable
    private final String arbitratorSigner;

    public OfferInfo(OfferInfoBuilder builder) {
        this.id = builder.getId();
        this.direction = builder.getDirection();
        this.price = builder.getPrice();
        this.useMarketBasedPrice = builder.isUseMarketBasedPrice();
        this.marketPriceMarginPct = builder.getMarketPriceMarginPct();
        this.amount = builder.getAmount();
        this.minAmount = builder.getMinAmount();
        this.volume = builder.getVolume();
        this.minVolume = builder.getMinVolume();
        this.txFee = builder.getTxFee();
        this.makerFee = builder.getMakerFee();
        this.offerFeePaymentTxId = builder.getOfferFeePaymentTxId();
        this.buyerSecurityDeposit = builder.getBuyerSecurityDeposit();
        this.sellerSecurityDeposit = builder.getSellerSecurityDeposit();
        this.triggerPrice = builder.getTriggerPrice();
        this.paymentAccountId = builder.getPaymentAccountId();
        this.paymentMethodId = builder.getPaymentMethodId();
        this.paymentMethodShortName = builder.getPaymentMethodShortName();
        this.baseCurrencyCode = builder.getBaseCurrencyCode();
        this.counterCurrencyCode = builder.getCounterCurrencyCode();
        this.date = builder.getDate();
        this.state = builder.getState();
        this.isActivated = builder.isActivated();
        this.isMyOffer = builder.isMyOffer();
        this.ownerNodeAddress = builder.getOwnerNodeAddress();
        this.pubKeyRing = builder.getPubKeyRing();
        this.versionNumber = builder.getVersionNumber();
        this.protocolVersion = builder.getProtocolVersion();
        this.arbitratorSigner = builder.getArbitratorSigner();
    }

    public static OfferInfo toOfferInfo(Offer offer) {
        return getBuilder(offer)
                .withIsMyOffer(false)
                .withIsActivated(true)
                .build();
    }

    public static OfferInfo toMyOfferInfo(OpenOffer openOffer) {
        // An OpenOffer is always my offer.
        var offer = openOffer.getOffer();
        var currencyCode = offer.getCurrencyCode();
        var isActivated = !openOffer.isDeactivated();
        Optional<Price> optionalTriggerPrice = openOffer.getTriggerPrice() > 0
                ? Optional.of(Price.valueOf(currencyCode, openOffer.getTriggerPrice()))
                : Optional.empty();
        var preciseTriggerPrice = optionalTriggerPrice
                .map(value -> reformatMarketPrice(value.toPlainString(), currencyCode))
                .orElse("0");
        return getBuilder(offer)
                .withTriggerPrice(preciseTriggerPrice)
                .withState(openOffer.getState().name())
                .withIsActivated(isActivated)
                .build();
    }

    private static OfferInfoBuilder getBuilder(Offer offer) {
        // OfferInfo protos are passed to API client, and some field
        // values are converted to displayable, unambiguous form.
        var currencyCode = offer.getCurrencyCode();
        var preciseOfferPrice = reformatMarketPrice(
                requireNonNull(offer.getPrice()).toPlainString(),
                currencyCode);
        var marketPriceMarginAsPctLiteral = exactMultiply(offer.getMarketPriceMarginPct(), 100);
        var roundedVolume = formatVolume(requireNonNull(offer.getVolume()));
        var roundedMinVolume = formatVolume(requireNonNull(offer.getMinVolume()));
        return new OfferInfoBuilder()
                .withId(offer.getId())
                .withDirection(offer.getDirection().name())
                .withPrice(preciseOfferPrice)
                .withUseMarketBasedPrice(offer.isUseMarketBasedPrice())
                .withMarketPriceMarginPct(marketPriceMarginAsPctLiteral)
                .withAmount(HavenoUtils.centinerosToAtomicUnits(offer.getAmount().value).longValueExact())
                .withMinAmount(HavenoUtils.centinerosToAtomicUnits(offer.getMinAmount().value).longValueExact())
                .withVolume(roundedVolume)
                .withMinVolume(roundedMinVolume)
                .withMakerFee(HavenoUtils.centinerosToAtomicUnits(offer.getMakerFee().value).longValueExact())
                .withOfferFeePaymentTxId(offer.getOfferFeePaymentTxId())
                .withBuyerSecurityDeposit(HavenoUtils.centinerosToAtomicUnits(offer.getBuyerSecurityDeposit().value).longValueExact())
                .withSellerSecurityDeposit(HavenoUtils.centinerosToAtomicUnits(offer.getSellerSecurityDeposit().value).longValueExact())
                .withPaymentAccountId(offer.getMakerPaymentAccountId())
                .withPaymentMethodId(offer.getPaymentMethod().getId())
                .withPaymentMethodShortName(offer.getPaymentMethod().getShortName())
                .withBaseCurrencyCode(offer.getBaseCurrencyCode())
                .withCounterCurrencyCode(offer.getCounterCurrencyCode())
                .withDate(offer.getDate().getTime())
                .withState(offer.getState().name())
                .withOwnerNodeAddress(offer.getOfferPayload().getOwnerNodeAddress().getFullAddress())
                .withPubKeyRing(offer.getOfferPayload().getPubKeyRing().toString())
                .withVersionNumber(offer.getOfferPayload().getVersionNr())
                .withProtocolVersion(offer.getOfferPayload().getProtocolVersion())
                .withArbitratorSigner(offer.getOfferPayload().getArbitratorSigner() == null ? null : offer.getOfferPayload().getArbitratorSigner().getFullAddress());
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public bisq.proto.grpc.OfferInfo toProtoMessage() {
        bisq.proto.grpc.OfferInfo.Builder builder = bisq.proto.grpc.OfferInfo.newBuilder()
                .setId(id)
                .setDirection(direction)
                .setPrice(price)
                .setUseMarketBasedPrice(useMarketBasedPrice)
                .setMarketPriceMarginPct(marketPriceMarginPct)
                .setAmount(amount)
                .setMinAmount(minAmount)
                .setVolume(volume)
                .setMinVolume(minVolume)
                .setMakerFee(makerFee)
                .setTxFee(txFee)
                .setBuyerSecurityDeposit(buyerSecurityDeposit)
                .setSellerSecurityDeposit(sellerSecurityDeposit)
                .setTriggerPrice(triggerPrice == null ? "0" : triggerPrice)
                .setPaymentAccountId(paymentAccountId)
                .setPaymentMethodId(paymentMethodId)
                .setPaymentMethodShortName(paymentMethodShortName)
                .setBaseCurrencyCode(baseCurrencyCode)
                .setCounterCurrencyCode(counterCurrencyCode)
                .setDate(date)
                .setState(state)
                .setIsActivated(isActivated)
                .setIsMyOffer(isMyOffer)
                .setOwnerNodeAddress(ownerNodeAddress)
                .setPubKeyRing(pubKeyRing)
                .setVersionNr(versionNumber)
                .setProtocolVersion(protocolVersion);
        Optional.ofNullable(arbitratorSigner).ifPresent(builder::setArbitratorSigner);
        Optional.ofNullable(offerFeePaymentTxId).ifPresent(builder::setOfferFeePaymentTxId);
        return builder.build();
    }

    @SuppressWarnings("unused")
    public static OfferInfo fromProto(bisq.proto.grpc.OfferInfo proto) {
        return new OfferInfoBuilder()
                .withId(proto.getId())
                .withDirection(proto.getDirection())
                .withPrice(proto.getPrice())
                .withUseMarketBasedPrice(proto.getUseMarketBasedPrice())
                .withMarketPriceMarginPct(proto.getMarketPriceMarginPct())
                .withAmount(proto.getAmount())
                .withMinAmount(proto.getMinAmount())
                .withVolume(proto.getVolume())
                .withMinVolume(proto.getMinVolume())
                .withMakerFee(proto.getMakerFee())
                .withTxFee(proto.getTxFee())
                .withOfferFeePaymentTxId(ProtoUtil.stringOrNullFromProto(proto.getOfferFeePaymentTxId()))
                .withBuyerSecurityDeposit(proto.getBuyerSecurityDeposit())
                .withSellerSecurityDeposit(proto.getSellerSecurityDeposit())
                .withTriggerPrice(proto.getTriggerPrice())
                .withPaymentAccountId(proto.getPaymentAccountId())
                .withPaymentMethodId(proto.getPaymentMethodId())
                .withPaymentMethodShortName(proto.getPaymentMethodShortName())
                .withBaseCurrencyCode(proto.getBaseCurrencyCode())
                .withCounterCurrencyCode(proto.getCounterCurrencyCode())
                .withDate(proto.getDate())
                .withState(proto.getState())
                .withIsActivated(proto.getIsActivated())
                .withIsMyOffer(proto.getIsMyOffer())
                .withOwnerNodeAddress(proto.getOwnerNodeAddress())
                .withPubKeyRing(proto.getPubKeyRing())
                .withVersionNumber(proto.getVersionNr())
                .withProtocolVersion(proto.getProtocolVersion())
                .withArbitratorSigner(proto.getArbitratorSigner())
                .build();
    }
}
