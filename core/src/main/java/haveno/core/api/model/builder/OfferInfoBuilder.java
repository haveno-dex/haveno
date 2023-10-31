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

package haveno.core.api.model.builder;

import haveno.core.api.model.OfferInfo;
import lombok.Getter;

/*
 * A builder helps avoid bungling use of a large OfferInfo constructor
 * argument list.  If consecutive argument values of the same type are not
 * ordered correctly, the compiler won't complain but the resulting bugs could
 * be hard to find and fix.
 */
@Getter
public final class OfferInfoBuilder {

    private String id;
    private String direction;
    private String price;
    private boolean useMarketBasedPrice;
    private double marketPriceMarginPct;
    private long amount;
    private long minAmount;
    private String volume;
    private String minVolume;
    private long makerFee;
    private double buyerSecurityDepositPct;
    private double sellerSecurityDepositPct;
    private String triggerPrice;
    private boolean isCurrencyForMakerFeeBtc;
    private String paymentAccountId;
    private String paymentMethodId;
    private String paymentMethodShortName;
    private String baseCurrencyCode;
    private String counterCurrencyCode;
    private long date;
    private String state;
    private boolean isActivated;
    private boolean isMyOffer;
    private boolean isMyPendingOffer;
    private boolean isBsqSwapOffer;
    private String ownerNodeAddress;
    private String pubKeyRing;
    private String versionNumber;
    private int protocolVersion;
    private String arbitratorSigner;
    private String splitOutputTxHash;
    private long splitOutputTxFee;

    public OfferInfoBuilder withId(String id) {
        this.id = id;
        return this;
    }

    public OfferInfoBuilder withDirection(String direction) {
        this.direction = direction;
        return this;
    }

    public OfferInfoBuilder withPrice(String price) {
        this.price = price;
        return this;
    }

    public OfferInfoBuilder withUseMarketBasedPrice(boolean useMarketBasedPrice) {
        this.useMarketBasedPrice = useMarketBasedPrice;
        return this;
    }

    public OfferInfoBuilder withMarketPriceMarginPct(double marketPriceMarginPct) {
        this.marketPriceMarginPct = marketPriceMarginPct;
        return this;
    }

    public OfferInfoBuilder withAmount(long amount) {
        this.amount = amount;
        return this;
    }

    public OfferInfoBuilder withMinAmount(long minAmount) {
        this.minAmount = minAmount;
        return this;
    }

    public OfferInfoBuilder withVolume(String volume) {
        this.volume = volume;
        return this;
    }

    public OfferInfoBuilder withMinVolume(String minVolume) {
        this.minVolume = minVolume;
        return this;
    }

    public OfferInfoBuilder withMakerFee(long makerFee) {
        this.makerFee = makerFee;
        return this;
    }

    public OfferInfoBuilder withBuyerSecurityDepositPct(double buyerSecurityDepositPct) {
        this.buyerSecurityDepositPct = buyerSecurityDepositPct;
        return this;
    }

    public OfferInfoBuilder withSellerSecurityDepositPct(double sellerSecurityDepositPct) {
        this.sellerSecurityDepositPct = sellerSecurityDepositPct;
        return this;
    }

    public OfferInfoBuilder withTriggerPrice(String triggerPrice) {
        this.triggerPrice = triggerPrice;
        return this;
    }

    public OfferInfoBuilder withIsCurrencyForMakerFeeBtc(boolean isCurrencyForMakerFeeBtc) {
        this.isCurrencyForMakerFeeBtc = isCurrencyForMakerFeeBtc;
        return this;
    }

    public OfferInfoBuilder withPaymentAccountId(String paymentAccountId) {
        this.paymentAccountId = paymentAccountId;
        return this;
    }

    public OfferInfoBuilder withPaymentMethodId(String paymentMethodId) {
        this.paymentMethodId = paymentMethodId;
        return this;
    }

    public OfferInfoBuilder withPaymentMethodShortName(String paymentMethodShortName) {
        this.paymentMethodShortName = paymentMethodShortName;
        return this;
    }

    public OfferInfoBuilder withBaseCurrencyCode(String baseCurrencyCode) {
        this.baseCurrencyCode = baseCurrencyCode;
        return this;
    }

    public OfferInfoBuilder withCounterCurrencyCode(String counterCurrencyCode) {
        this.counterCurrencyCode = counterCurrencyCode;
        return this;
    }

    public OfferInfoBuilder withDate(long date) {
        this.date = date;
        return this;
    }

    public OfferInfoBuilder withState(String state) {
        this.state = state;
        return this;
    }

    public OfferInfoBuilder withIsActivated(boolean isActivated) {
        this.isActivated = isActivated;
        return this;
    }

    public OfferInfoBuilder withIsMyOffer(boolean isMyOffer) {
        this.isMyOffer = isMyOffer;
        return this;
    }

    public OfferInfoBuilder withIsMyPendingOffer(boolean isMyPendingOffer) {
        this.isMyPendingOffer = isMyPendingOffer;
        return this;
    }

    public OfferInfoBuilder withIsBsqSwapOffer(boolean isBsqSwapOffer) {
        this.isBsqSwapOffer = isBsqSwapOffer;
        return this;
    }

    public OfferInfoBuilder withOwnerNodeAddress(String ownerNodeAddress) {
        this.ownerNodeAddress = ownerNodeAddress;
        return this;
    }

    public OfferInfoBuilder withPubKeyRing(String pubKeyRing) {
        this.pubKeyRing = pubKeyRing;
        return this;
    }

    public OfferInfoBuilder withVersionNumber(String versionNumber) {
        this.versionNumber = versionNumber;
        return this;
    }

    public OfferInfoBuilder withProtocolVersion(int protocolVersion) {
        this.protocolVersion = protocolVersion;
        return this;
    }

    public OfferInfoBuilder withArbitratorSigner(String arbitratorSigner) {
        this.arbitratorSigner = arbitratorSigner;
        return this;
    }
    
    public OfferInfoBuilder withSplitOutputTxHash(String splitOutputTxHash) {
        this.splitOutputTxHash = splitOutputTxHash;
        return this;
    }

    public OfferInfoBuilder withSplitOutputTxFee(long splitOutputTxFee) {
        this.splitOutputTxFee = splitOutputTxFee;
        return this;
    }

    public OfferInfo build() {
        return new OfferInfo(this);
    }
}
