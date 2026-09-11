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

package haveno.core.payment.validation;

import haveno.asset.AddressValidationResult;
import haveno.asset.Asset;
import haveno.asset.AssetRegistry;
import haveno.common.config.BaseCurrencyNetwork;
import haveno.common.config.Config;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CryptoAddressValidatorTest {

    @Test
    public void acceptsErgoMainnetP2pkThroughAssetRegistry() {
        CryptoAddressValidator validator = new CryptoAddressValidator(new AssetRegistry());
        validator.setCurrencyCode("ERG");

        // sigmastate ErgoAddressSpecification public P2PK vector
        assertTrue(validator.validate("9iJd9drp1KR3R7HLi7YmQbB5sJ5HFKZoPb5MxGepamggJs5vDHm").isValid);
        assertTrue(validator.validate("9fRAWhdxEsTcdb8PhGNrZfwqa65zfkuYHAMmkQLcic1gdLSV5vA").isValid);
    }

    @TestFactory
    public Stream<DynamicTest> rejectsMalformedErgoAddresses() {
        // point, network, type and payload-length fixtures have independently recomputed checksums
        return Stream.of(
            "checksum, 9iJd9drp1KR3R7HLi7YmQbB5sJ5HFKZoPb5MxGepamggJs5vDHn",
            "invalid alphabet, 0iJd9drp1KR3R7HLi7YmQbB5sJ5HFKZoPb5MxGepamggJs5vDHm",
            "unknown network, 5tGUvKAeQvdpDyXs38k8Eycm3miwzdzq1XXFGVMgXttTguHv8Y49",
            "unknown type, bTCLdiZLU4pxy9azwJE9vsdqAu2nW8kHiTU3KJwJ22pXJU9mkFS",
            "short payload, 2yV1VEniv65YMsMvQMy8o8HnWX9g2S3uCTkakLQXGMYeWmuhYo",
            "long payload, fTJnZ7grUQH7fQj7zTuD17qWUzMtiCwmkhQFUx5w24E8VefRQtUj",
            "noncanonical zero-prefixed point, 9adaAMuB9v8yX1mZ5PtoB6VFSCeqRGjASd8ZTM6VUkiHLQNBguB",
            "invalid point tag, 9kFNKsJqrk6GYvs3chAmMumrnQgzu2QgBE9UCRLiaRh4oSgXpD8",
            "x outside field, 9gToh4FGiCAevUWfo8ko34HaBYVzNPFnoYLtBoAAiuDDMC3Aduv",
            "point outside curve, 9eX4WpoErmVRnevxtZ8o5jgoGRtGigQv1uGmweUHU4j4KYh8tBA",
            "leading zero byte, 19iJd9drp1KR3R7HLi7YmQbB5sJ5HFKZoPb5MxGepamggJs5vDHm",
            "trailing byte, fTJnZ7grUQH7fQj7zTuD17qWUzMtiCwmkhQFUx5w24E8Vr4hsEyE"
        ).map(fixture -> {
            String[] fields = fixture.split(", ", 2);
            return DynamicTest.dynamicTest(fields[0], () -> {
                AddressValidationResult result = ergoAsset().validateAddress(fields[1]);
                assertFalse(result.isValid());
                assertEquals("validation.crypto.wrongStructure", result.getI18nKey());
                CryptoAddressValidator validator = new CryptoAddressValidator(new AssetRegistry());
                validator.setCurrencyCode("ERG");
                assertFalse(validator.validate(fields[1]).isValid);
            });
        });
    }

    @TestFactory
    public Stream<DynamicTest> reportsUnsupportedErgoAddressPolicy() {
        return Stream.of(
            "canonical identity, 9adaAMuB9v8yX1mZ5PtoB6VFSCeqRGjASd8ZTM6VUkiHLDgh2Rp",
            "mainnet P2SH, 8UApt8czfFVuTgQmMwtsRBZ4nfWquNiSwCWUjMg",
            "mainnet P2S, 4MQyML64GnzMxZgm",
            "testnet P2PK, 3WvsT2Gm4EpsM9Pg18PdY6XyhNNMqXDsvJTbbf6ihLvAmSb7u5RN"
        ).map(fixture -> {
            String[] fields = fixture.split(", ", 2);
            return DynamicTest.dynamicTest(fields[0], () -> {
                AddressValidationResult result = ergoAsset().validateAddress(fields[1]);
                assertFalse(result.isValid());
                assertEquals("validation.crypto.ergo.mainnetP2pk", result.getI18nKey());
            });
        });
    }

    @Test
    public void rejectsEmptyWhitespaceAndOversizedErgoInput() {
        Asset asset = ergoAsset();
        String address = "9iJd9drp1KR3R7HLi7YmQbB5sJ5HFKZoPb5MxGepamggJs5vDHm";
        for (String input : new String[]{null, "", " ", " " + address, address + " ", address + "\n", "9".repeat(10000)}) {
            assertFalse(asset.validateAddress(input).isValid());
        }
    }

    private Asset ergoAsset() {
        Asset asset = new AssetRegistry().stream()
                .filter(candidate -> candidate.getTickerSymbol().equals("ERG"))
                .findFirst().orElse(null);
        assertNotNull(asset, "ERG must be available through the payment asset registry");
        return asset;
    }

    @Test
    public void test() {
        CryptoAddressValidator validator = new CryptoAddressValidator(new AssetRegistry());

        BaseCurrencyNetwork baseCurrencyNetwork = Config.baseCurrencyNetwork();
        String currencyCode = baseCurrencyNetwork.getCurrencyCode();
        Res.setBaseCurrencyCode(currencyCode);
        Res.setBaseCurrencyName(baseCurrencyNetwork.getCurrencyName());
        CurrencyUtil.setBaseCurrencyCode(currencyCode);

        validator.setCurrencyCode("BTC");
        assertTrue(validator.validate("17VZNX1SN5NtKa8UQFxwQbFeFc3iqRYhem").isValid);

        validator.setCurrencyCode("XMR");
        assertTrue(validator.validate("4AuUM6PedofLWKfRCX1fP3SoNZUzq6FSAbpevHRR6tVuMpZc3HznVeudmNGkEB75apjE7WKVgZZh1YvPVxZoHFN88NCdmWw").isValid);

        validator.setCurrencyCode("LTC");
        assertTrue(validator.validate("Lg3PX8wRWmApFCoCMAsPF5P9dPHYQHEWKW").isValid);

        validator.setCurrencyCode("BOGUS");

        assertFalse(validator.validate("1BOGUSADDR").isValid);
    }
}
