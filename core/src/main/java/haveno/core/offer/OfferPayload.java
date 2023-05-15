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

package haveno.core.offer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.protobuf.ByteString;
import haveno.common.crypto.Hash;
import haveno.common.crypto.PubKeyRing;
import haveno.common.proto.ProtoUtil;
import haveno.common.util.CollectionUtils;
import haveno.common.util.Hex;
import haveno.common.util.JsonExclude;
import haveno.common.util.Utilities;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.storage.payload.ExpirablePayload;
import haveno.network.p2p.storage.payload.ProtectedStoragePayload;
import haveno.network.p2p.storage.payload.RequiresOwnerIsOnlinePayload;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.lang.reflect.Type;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

// OfferPayload has about 1.4 kb. We should look into options to make it smaller but will be hard to do it in a
// backward compatible way. Maybe a candidate when segwit activation is done as hardfork?
@EqualsAndHashCode(exclude = {"hash"})
@Getter
@Slf4j
public final class OfferPayload implements ProtectedStoragePayload, ExpirablePayload, RequiresOwnerIsOnlinePayload {
    public static final long TTL = TimeUnit.MINUTES.toMillis(9);

    protected final String id;
    protected final long date;
    // For traditional offer the baseCurrencyCode is XMR and the counterCurrencyCode is the traditional currency
    // For crypto offers it is the opposite. baseCurrencyCode is the crypto and the counterCurrencyCode is XMR.
    protected final String baseCurrencyCode;
    protected final String counterCurrencyCode;
    // price if fixed price is used (usePercentageBasedPrice = false), otherwise 0
    protected final long price;
    protected final long amount;
    protected final long minAmount;
    protected final String paymentMethodId;
    protected final String makerPaymentAccountId;
    protected final NodeAddress ownerNodeAddress;
    protected final OfferDirection direction;
    protected final String versionNr;
    protected final int protocolVersion;
    @JsonExclude
    protected final PubKeyRing pubKeyRing;
    // cache
    protected transient byte[] hash;
    @Nullable
    protected final Map<String, String> extraDataMap;

    // address and signature of signing arbitrator
    @Setter
    @Nullable
    protected NodeAddress arbitratorSigner;
    @Setter
    @Nullable
    protected byte[] arbitratorSignature;
    @Setter
    @Nullable
    protected List<String> reserveTxKeyImages;

    // Keys for extra map
    // Only set for traditional offers
    public static final String ACCOUNT_AGE_WITNESS_HASH = "accountAgeWitnessHash";
    public static final String REFERRAL_ID = "referralId";
    // Only used in payment method F2F
    public static final String F2F_CITY = "f2fCity";
    public static final String F2F_EXTRA_INFO = "f2fExtraInfo";
    public static final String CASH_BY_MAIL_EXTRA_INFO = "cashByMailExtraInfo";

    // Comma separated list of ordinal of a haveno.common.app.Capability. E.g. ordinal of
    // Capability.SIGNED_ACCOUNT_AGE_WITNESS is 11 and Capability.MEDIATION is 12 so if we want to signal that maker
    // of the offer supports both capabilities we add "11, 12" to capabilities.
    public static final String CAPABILITIES = "capabilities";
    // If maker is seller and has xmrAutoConf enabled it is set to "1" otherwise it is not set
    public static final String XMR_AUTO_CONF = "xmrAutoConf";
    public static final String XMR_AUTO_CONF_ENABLED_VALUE = "1";


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Instance fields
    ///////////////////////////////////////////////////////////////////////////////////////////

    // Distance form market price if percentage based price is used (usePercentageBasedPrice = true), otherwise 0.
    // E.g. 0.1 -> 10%. Can be negative as well. Depending on direction the marketPriceMargin is above or below the market price.
    // Positive values is always the usual case where you want a better price as the market.
    // E.g. Buy offer with market price 400.- leads to a 360.- price.
    // Sell offer with market price 400.- leads to a 440.- price.
    private final double marketPriceMarginPct;
    // We use 2 type of prices: fixed price or price based on distance from market price
    private final boolean useMarketBasedPrice;

    // Mutable property. Has to be set before offer is saved in P2P network as it changes the payload hash!
    @Setter
    @Nullable
    private String offerFeeTxId;
    @Nullable
    private final String countryCode;
    @Nullable
    private final List<String> acceptedCountryCodes;
    @Nullable
    private final String bankId;
    @Nullable
    private final List<String> acceptedBankIds;
    private final long blockHeightAtOfferCreation;
    private final long makerFee;
    private final long buyerSecurityDeposit;
    private final long sellerSecurityDeposit;
    private final long maxTradeLimit;
    private final long maxTradePeriod;

    // reserved for future use cases
    // Close offer when certain price is reached
    private final boolean useAutoClose;
    // If useReOpenAfterAutoClose=true we re-open a new offer with the remaining funds if the trade amount
    // was less than the offer's max. trade amount.
    private final boolean useReOpenAfterAutoClose;
    // Used when useAutoClose is set for canceling the offer when lowerClosePrice is triggered
    private final long lowerClosePrice;
    // Used when useAutoClose is set for canceling the offer when upperClosePrice is triggered
    private final long upperClosePrice;
    // Reserved for possible future use to support private trades where the taker needs to have an accessKey
    private final boolean isPrivateOffer;
    @Nullable
    private final String hashOfChallenge;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public OfferPayload(String id,
                        long date,
                        NodeAddress ownerNodeAddress,
                        PubKeyRing pubKeyRing,
                        OfferDirection direction,
                        long price,
                        double marketPriceMarginPct,
                        boolean useMarketBasedPrice,
                        long amount,
                        long minAmount,
                        String baseCurrencyCode,
                        String counterCurrencyCode,
                        String paymentMethodId,
                        String makerPaymentAccountId,
                        @Nullable String offerFeeTxId,
                        @Nullable String countryCode,
                        @Nullable List<String> acceptedCountryCodes,
                        @Nullable String bankId,
                        @Nullable List<String> acceptedBankIds,
                        String versionNr,
                        long blockHeightAtOfferCreation,
                        long makerFee,
                        long buyerSecurityDeposit,
                        long sellerSecurityDeposit,
                        long maxTradeLimit,
                        long maxTradePeriod,
                        boolean useAutoClose,
                        boolean useReOpenAfterAutoClose,
                        long lowerClosePrice,
                        long upperClosePrice,
                        boolean isPrivateOffer,
                        @Nullable String hashOfChallenge,
                        @Nullable Map<String, String> extraDataMap,
                        int protocolVersion,
                        @Nullable NodeAddress arbitratorSigner,
                        @Nullable byte[] arbitratorSignature,
                        @Nullable List<String> reserveTxKeyImages) {
        this.id = id;
        this.date = date;
        this.ownerNodeAddress = ownerNodeAddress;
        this.pubKeyRing = pubKeyRing;
        this.baseCurrencyCode = baseCurrencyCode;
        this.counterCurrencyCode = counterCurrencyCode;
        this.direction = direction;
        this.price = price;
        this.amount = amount;
        this.minAmount = minAmount;
        this.paymentMethodId = paymentMethodId;
        this.makerPaymentAccountId = makerPaymentAccountId;
        this.extraDataMap = extraDataMap;
        this.versionNr = versionNr;
        this.protocolVersion = protocolVersion;
        this.arbitratorSigner = arbitratorSigner;
        this.arbitratorSignature = arbitratorSignature;
        this.reserveTxKeyImages = reserveTxKeyImages;
        this.marketPriceMarginPct = marketPriceMarginPct;
        this.useMarketBasedPrice = useMarketBasedPrice;
        this.offerFeeTxId = offerFeeTxId;
        this.countryCode = countryCode;
        this.acceptedCountryCodes = acceptedCountryCodes;
        this.bankId = bankId;
        this.acceptedBankIds = acceptedBankIds;
        this.blockHeightAtOfferCreation = blockHeightAtOfferCreation;
        this.makerFee = makerFee;
        this.buyerSecurityDeposit = buyerSecurityDeposit;
        this.sellerSecurityDeposit = sellerSecurityDeposit;
        this.maxTradeLimit = maxTradeLimit;
        this.maxTradePeriod = maxTradePeriod;
        this.useAutoClose = useAutoClose;
        this.useReOpenAfterAutoClose = useReOpenAfterAutoClose;
        this.lowerClosePrice = lowerClosePrice;
        this.upperClosePrice = upperClosePrice;
        this.isPrivateOffer = isPrivateOffer;
        this.hashOfChallenge = hashOfChallenge;
    }

    public byte[] getHash() {
        if (this.hash == null) {
            // A proto message can be created only after the offerFeeTxId is
            // set to a non-null value;  now is the time to cache the payload hash.
            this.hash = Hash.getSha256Hash(this.toProtoMessage().toByteArray());
        }
        return this.hash;
    }

    @Override
    public long getTTL() {
        return TTL;
    }

    @Override
    public PublicKey getOwnerPubKey() {
        return pubKeyRing.getSignaturePubKey();
    }

    // In the offer we support base and counter currency
    // Fiat offers have base currency XMR and counterCurrency Fiat
    // Cryptos have base currency Crypto and counterCurrency XMR
    // The rest of the app does not support yet that concept of base currency and counter currencies
    // so we map here for convenience
    public String getCurrencyCode() {
        return getBaseCurrencyCode().equals("XMR") ? getCounterCurrencyCode() : getBaseCurrencyCode();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.StoragePayload toProtoMessage() {
        protobuf.OfferPayload.Builder builder = protobuf.OfferPayload.newBuilder()
                .setId(id)
                .setDate(date)
                .setOwnerNodeAddress(ownerNodeAddress.toProtoMessage())
                .setPubKeyRing(pubKeyRing.toProtoMessage())
                .setDirection(OfferDirection.toProtoMessage(direction))
                .setPrice(price)
                .setMarketPriceMarginPct(marketPriceMarginPct)
                .setUseMarketBasedPrice(useMarketBasedPrice)
                .setAmount(amount)
                .setMinAmount(minAmount)
                .setBaseCurrencyCode(baseCurrencyCode)
                .setCounterCurrencyCode(counterCurrencyCode)
                .setPaymentMethodId(paymentMethodId)
                .setMakerPaymentAccountId(makerPaymentAccountId)
                .setVersionNr(versionNr)
                .setBlockHeightAtOfferCreation(blockHeightAtOfferCreation)
                .setMakerFee(makerFee)
                .setBuyerSecurityDeposit(buyerSecurityDeposit)
                .setSellerSecurityDeposit(sellerSecurityDeposit)
                .setMaxTradeLimit(maxTradeLimit)
                .setMaxTradePeriod(maxTradePeriod)
                .setUseAutoClose(useAutoClose)
                .setUseReOpenAfterAutoClose(useReOpenAfterAutoClose)
                .setLowerClosePrice(lowerClosePrice)
                .setUpperClosePrice(upperClosePrice)
                .setIsPrivateOffer(isPrivateOffer)
                .setProtocolVersion(protocolVersion);
        Optional.ofNullable(arbitratorSigner).ifPresent(e -> builder.setArbitratorSigner(arbitratorSigner.toProtoMessage()));
        Optional.ofNullable(offerFeeTxId).ifPresent(builder::setOfferFeeTxId);
        Optional.ofNullable(countryCode).ifPresent(builder::setCountryCode);
        Optional.ofNullable(bankId).ifPresent(builder::setBankId);
        Optional.ofNullable(acceptedBankIds).ifPresent(builder::addAllAcceptedBankIds);
        Optional.ofNullable(acceptedCountryCodes).ifPresent(builder::addAllAcceptedCountryCodes);
        Optional.ofNullable(hashOfChallenge).ifPresent(builder::setHashOfChallenge);
        Optional.ofNullable(extraDataMap).ifPresent(builder::putAllExtraData);
        Optional.ofNullable(arbitratorSignature).ifPresent(e -> builder.setArbitratorSignature(ByteString.copyFrom(e)));
        Optional.ofNullable(reserveTxKeyImages).ifPresent(builder::addAllReserveTxKeyImages);

        return protobuf.StoragePayload.newBuilder().setOfferPayload(builder).build();
    }

    public static OfferPayload fromProto(protobuf.OfferPayload proto) {
        List<String> acceptedBankIds = proto.getAcceptedBankIdsList().isEmpty() ?
                null : new ArrayList<>(proto.getAcceptedBankIdsList());
        List<String> acceptedCountryCodes = proto.getAcceptedCountryCodesList().isEmpty() ?
                null : new ArrayList<>(proto.getAcceptedCountryCodesList());
        String hashOfChallenge = ProtoUtil.stringOrNullFromProto(proto.getHashOfChallenge());
        Map<String, String> extraDataMapMap = CollectionUtils.isEmpty(proto.getExtraDataMap()) ?
                null : proto.getExtraDataMap();

        return new OfferPayload(proto.getId(),
                proto.getDate(),
                NodeAddress.fromProto(proto.getOwnerNodeAddress()),
                PubKeyRing.fromProto(proto.getPubKeyRing()),
                OfferDirection.fromProto(proto.getDirection()),
                proto.getPrice(),
                proto.getMarketPriceMarginPct(),
                proto.getUseMarketBasedPrice(),
                proto.getAmount(),
                proto.getMinAmount(),
                proto.getBaseCurrencyCode(),
                proto.getCounterCurrencyCode(),
                proto.getPaymentMethodId(),
                proto.getMakerPaymentAccountId(),
                ProtoUtil.stringOrNullFromProto(proto.getOfferFeeTxId()),
                ProtoUtil.stringOrNullFromProto(proto.getCountryCode()),
                acceptedCountryCodes,
                ProtoUtil.stringOrNullFromProto(proto.getBankId()),
                acceptedBankIds,
                proto.getVersionNr(),
                proto.getBlockHeightAtOfferCreation(),
                proto.getMakerFee(),
                proto.getBuyerSecurityDeposit(),
                proto.getSellerSecurityDeposit(),
                proto.getMaxTradeLimit(),
                proto.getMaxTradePeriod(),
                proto.getUseAutoClose(),
                proto.getUseReOpenAfterAutoClose(),
                proto.getLowerClosePrice(),
                proto.getUpperClosePrice(),
                proto.getIsPrivateOffer(),
                hashOfChallenge,
                extraDataMapMap,
                proto.getProtocolVersion(),
                proto.hasArbitratorSigner() ? NodeAddress.fromProto(proto.getArbitratorSigner()) : null,
                ProtoUtil.byteArrayOrNullFromProto(proto.getArbitratorSignature()),
                proto.getReserveTxKeyImagesList() == null ? null : new ArrayList<String>(proto.getReserveTxKeyImagesList()));
    }

    @Override
    public String toString() {
        return "OfferPayload{" +
                "\r\n     id='" + id + '\'' +
                ",\r\n     date=" + date +
                ",\r\n     baseCurrencyCode='" + baseCurrencyCode + '\'' +
                ",\r\n     counterCurrencyCode='" + counterCurrencyCode + '\'' +
                ",\r\n     price=" + price +
                ",\r\n     amount=" + amount +
                ",\r\n     minAmount=" + minAmount +
                ",\r\n     paymentMethodId='" + paymentMethodId + '\'' +
                ",\r\n     makerPaymentAccountId='" + makerPaymentAccountId + '\'' +
                ",\r\n     ownerNodeAddress=" + ownerNodeAddress +
                ",\r\n     direction=" + direction +
                ",\r\n     versionNr='" + versionNr + '\'' +
                ",\r\n     protocolVersion=" + protocolVersion +
                ",\r\n     pubKeyRing=" + pubKeyRing +
                ",\r\n     hash=" + (hash != null ? Hex.encode(hash) : "null") +
                ",\r\n     extraDataMap=" + extraDataMap +
                ",\r\n     reserveTxKeyImages=" + reserveTxKeyImages +
                ",\r\n     marketPriceMargin=" + marketPriceMarginPct +
                ",\r\n     useMarketBasedPrice=" + useMarketBasedPrice +
                ",\r\n     offerFeeTxId='" + offerFeeTxId + '\'' +
                ",\r\n     countryCode='" + countryCode + '\'' +
                ",\r\n     acceptedCountryCodes=" + acceptedCountryCodes +
                ",\r\n     bankId='" + bankId + '\'' +
                ",\r\n     acceptedBankIds=" + acceptedBankIds +
                ",\r\n     blockHeightAtOfferCreation=" + blockHeightAtOfferCreation +
                ",\r\n     makerFee=" + makerFee +
                ",\r\n     buyerSecurityDeposit=" + buyerSecurityDeposit +
                ",\r\n     sellerSecurityDeposit=" + sellerSecurityDeposit +
                ",\r\n     maxTradeLimit=" + maxTradeLimit +
                ",\r\n     maxTradePeriod=" + maxTradePeriod +
                ",\r\n     useAutoClose=" + useAutoClose +
                ",\r\n     useReOpenAfterAutoClose=" + useReOpenAfterAutoClose +
                ",\r\n     lowerClosePrice=" + lowerClosePrice +
                ",\r\n     upperClosePrice=" + upperClosePrice +
                ",\r\n     isPrivateOffer=" + isPrivateOffer +
                ",\r\n     hashOfChallenge='" + hashOfChallenge + '\'' +
                ",\n     arbitratorSigner=" + arbitratorSigner +
                ",\n     arbitratorSignature=" + Utilities.bytesAsHexString(arbitratorSignature) +
                "\r\n} ";
    }

    // For backward compatibility we need to ensure same order for json fields as with 1.7.5. and earlier versions.
    // The json is used for the hash in the contract and change of oder would cause a different hash and
    // therefore a failure during trade.
    public static class JsonSerializer implements com.google.gson.JsonSerializer<OfferPayload> {
        @Override
        public JsonElement serialize(OfferPayload offerPayload, Type type, JsonSerializationContext context) {
            JsonObject object = new JsonObject();
            object.add("id", context.serialize(offerPayload.getId()));
            object.add("date", context.serialize(offerPayload.getDate()));
            object.add("ownerNodeAddress", context.serialize(offerPayload.getOwnerNodeAddress()));
            object.add("direction", context.serialize(offerPayload.getDirection()));
            object.add("price", context.serialize(offerPayload.getPrice()));
            object.add("marketPriceMargin", context.serialize(offerPayload.getMarketPriceMarginPct()));
            object.add("useMarketBasedPrice", context.serialize(offerPayload.isUseMarketBasedPrice()));
            object.add("amount", context.serialize(offerPayload.getAmount()));
            object.add("minAmount", context.serialize(offerPayload.getMinAmount()));
            object.add("baseCurrencyCode", context.serialize(offerPayload.getBaseCurrencyCode()));
            object.add("counterCurrencyCode", context.serialize(offerPayload.getCounterCurrencyCode()));
            object.add("paymentMethodId", context.serialize(offerPayload.getPaymentMethodId()));
            object.add("makerPaymentAccountId", context.serialize(offerPayload.getMakerPaymentAccountId()));
            object.add("offerFeeTxId", context.serialize(offerPayload.getOfferFeeTxId()));
            object.add("versionNr", context.serialize(offerPayload.getVersionNr()));
            object.add("blockHeightAtOfferCreation", context.serialize(offerPayload.getBlockHeightAtOfferCreation()));
            object.add("makerFee", context.serialize(offerPayload.getMakerFee()));
            object.add("buyerSecurityDeposit", context.serialize(offerPayload.getBuyerSecurityDeposit()));
            object.add("sellerSecurityDeposit", context.serialize(offerPayload.getSellerSecurityDeposit()));
            object.add("maxTradeLimit", context.serialize(offerPayload.getMaxTradeLimit()));
            object.add("maxTradePeriod", context.serialize(offerPayload.getMaxTradePeriod()));
            object.add("useAutoClose", context.serialize(offerPayload.isUseAutoClose()));
            object.add("useReOpenAfterAutoClose", context.serialize(offerPayload.isUseReOpenAfterAutoClose()));
            object.add("lowerClosePrice", context.serialize(offerPayload.getLowerClosePrice()));
            object.add("upperClosePrice", context.serialize(offerPayload.getUpperClosePrice()));
            object.add("isPrivateOffer", context.serialize(offerPayload.isPrivateOffer()));
            object.add("extraDataMap", context.serialize(offerPayload.getExtraDataMap()));
            object.add("protocolVersion", context.serialize(offerPayload.getProtocolVersion()));
            object.add("arbitratorSigner", context.serialize(offerPayload.getArbitratorSigner()));
            object.add("arbitratorSignature", context.serialize(offerPayload.getArbitratorSignature()));
            return object;
        }
    }
}
