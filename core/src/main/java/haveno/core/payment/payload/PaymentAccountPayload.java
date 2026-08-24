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

package haveno.core.payment.payload;

import com.google.gson.GsonBuilder;
import haveno.common.consensus.UsedForTradeContractJson;
import haveno.common.crypto.CryptoUtils;
import haveno.common.crypto.Hash;
import haveno.common.proto.network.NetworkPayload;
import haveno.common.util.JsonExclude;
import haveno.common.util.Utilities;
import haveno.core.locale.Res;
import haveno.core.proto.CoreProtoResolver;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;

// That class is used in the contract for creating the contract json. Any change will break the contract.
// If a field gets added it need to be be annotated with @JsonExclude (excluded from contract).
// We should add an extraDataMap as in StoragePayload objects

@Getter
@EqualsAndHashCode
@ToString
@Slf4j
public abstract class PaymentAccountPayload implements NetworkPayload, UsedForTradeContractJson {

    // Keys for excludeFromJsonDataMap
    public static final String SALT = "salt";

    protected final String paymentMethodId;
    protected final String id;

    // Is just kept for not breaking backward compatibility. Set to -1 to indicate it is no used anymore.
    protected final long maxTradePeriod;

    // In v0.6 we removed maxTradePeriod but we need to keep it in the PB file for backward compatibility
    // protected final long maxTradePeriod;

    // Used for new data (e.g. salt introduced in v0.6) which would break backward compatibility as
    // PaymentAccountPayload is used for the json contract and a trade with a user who has an older version would
    // fail the contract verification.
    @JsonExclude
    protected final Map<String, String> excludeFromJsonDataMap;

    private static final GsonBuilder gsonBuilder = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls();


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    PaymentAccountPayload(String paymentMethodId, String id) {
        this(paymentMethodId,
                id,
                -1,
                new HashMap<>());
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected PaymentAccountPayload(String paymentMethodId,
                                    String id,
                                    long maxTradePeriod,
                                    Map<String, String> excludeFromJsonDataMapParam) {
        this.paymentMethodId = paymentMethodId;
        this.id = id;
        this.maxTradePeriod = maxTradePeriod;
        this.excludeFromJsonDataMap = excludeFromJsonDataMapParam;

        // If not set (old versions) we set by default a random 256 bit salt.
        // User can set salt as well by hex string.
        // Persisted value will overwrite that
        if (!this.excludeFromJsonDataMap.containsKey(SALT))
            this.excludeFromJsonDataMap.put(SALT, Utilities.encodeToHex(CryptoUtils.getRandomBytes(32)));
    }

    protected protobuf.PaymentAccountPayload.Builder getPaymentAccountPayloadBuilder() {
        final protobuf.PaymentAccountPayload.Builder builder = protobuf.PaymentAccountPayload.newBuilder()
                .setPaymentMethodId(paymentMethodId)
                .setMaxTradePeriod(maxTradePeriod)
                .setId(id);

        builder.putAllExcludeFromJsonData(excludeFromJsonDataMap);

        return builder;
    }

    public static PaymentAccountPayload fromProto(protobuf.PaymentAccountPayload proto, CoreProtoResolver coreProtoResolver) {
        return coreProtoResolver.fromProto(proto);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public String toJson() {
        return gsonBuilder.create().toJson(this);
    }

    public abstract String getPaymentDetails();

    public abstract String getPaymentDetailsForTradePopup();

    public byte[] getHash() {
        return Hash.getRipemd160hash(this.toProtoMessage().toByteArray()); // TODO: adopt serializeForHash() from Bisq?
    }

    public byte[] getSalt() {
        checkArgument(excludeFromJsonDataMap.containsKey(SALT), "Salt must have been set in excludeFromJsonDataMap.");
        return Utilities.decodeFromHex(excludeFromJsonDataMap.get(SALT));
    }

    public void setSalt(byte[] salt) {
        excludeFromJsonDataMap.put(SALT, Utilities.encodeToHex(salt));
    }

    // Identifying data of payment account (e.g. IBAN).
    // This is critical code for verifying age of payment account.
    // Any change would break validation of historical data!
    public abstract byte[] getAgeWitnessInputData();

    protected byte[] getAgeWitnessInputData(byte[] data) {
        return ArrayUtils.addAll(paymentMethodId.getBytes(StandardCharsets.UTF_8), data);
    }

    // Data identifying the external transfer endpoint for detecting trades with indistinguishable payments,
    // canonicalized independent of cosmetic formatting. Null if the method has no explicitly classified stable
    // endpoint, which conservatively treats all accounts of the method as the same endpoint.
    public byte[] getPaymentEndpointData() {
        return null;
    }

    // canonicalize and length-prefix endpoint fields under the endpoint namespace, keeping only letters and
    // digits, uppercased; over-merging cosmetic variants errs conservative for detection
    protected byte[] getPaymentEndpointData(String... fields) {
        StringBuilder sb = new StringBuilder(getPaymentEndpointNamespace()).append('|');
        for (String field : fields) {
            String normalized = field == null ? "" : field.replaceAll("[^\\p{L}\\p{N}]", "").toUpperCase(Locale.ROOT);
            sb.append(normalized.length()).append(':').append(normalized).append('|');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // namespace separating endpoints of unrelated methods; methods reaching the same transfer rail share one
    protected String getPaymentEndpointNamespace() {
        return paymentMethodId;
    }

    // normalize a mobile number with a known national number length to country-qualified digits, accepting
    // international dialing prefixes and optional trunk zeros, and country-qualifying national numbers even
    // when they start with the country calling code
    protected static String normalizeMobileNr(String mobileNr, String countryCallingCode, int nationalNrLength) {
        String digits = mobileNr == null ? "" : mobileNr.replaceAll("\\D", "");
        if (digits.startsWith("00")) digits = digits.substring(2);
        else if (digits.startsWith("0")) digits = digits.substring(1);
        if (digits.length() == nationalNrLength) return countryCallingCode + digits;
        if (digits.startsWith(countryCallingCode + "0") && digits.length() == countryCallingCode.length() + 1 + nationalNrLength)
            return countryCallingCode + digits.substring(countryCallingCode.length() + 1); // optional trunk zero after the calling code
        return digits.startsWith(countryCallingCode) ? digits : countryCallingCode + digits;
    }

    // normalize NANP phone forms to country-qualified digits and other values (e.g. emails) to lower case
    protected static String normalizeNanpEmailOrMobileNr(String value) {
        if (value == null) return "";
        if (value.matches("\\+?1?[\\s().-]*(\\d[\\s().-]*){10}")) {
            String digits = value.replaceAll("\\D", "");
            return digits.length() == 10 ? "1" + digits : digits;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    // true when the value looks like a phone number in a non-NANP dialing form, whose national and
    // international representations cannot be canonicalized without a known region
    protected static boolean isAmbiguousPhoneNr(String value) {
        if (value == null) return false;
        if (value.matches("\\+?1?[\\s().-]*(\\d[\\s().-]*){10}")) return false; // NANP forms are canonicalized
        return isPhoneShaped(value);
    }

    protected static boolean isPhoneShaped(String value) {
        return value != null && value.matches("[+0-9\\s().-]+");
    }

    // normalize a mixed email-or-mobile value of a fixed-region method: phone-shaped values are
    // country-qualified and other values (e.g. emails) lower-cased
    protected static String normalizeEmailOrMobileNr(String value, String countryCallingCode, int nationalNrLength) {
        if (value == null) return "";
        if (isPhoneShaped(value)) return normalizeMobileNr(value, countryCallingCode, nationalNrLength);
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public String getOwnerId() {
        return null;
    }

    public static String getHolderNameOrPromptIfEmpty(String holderName) {
        return holderName.isEmpty() ? Res.get("payment.account.owner.ask") : holderName;
    }
}
