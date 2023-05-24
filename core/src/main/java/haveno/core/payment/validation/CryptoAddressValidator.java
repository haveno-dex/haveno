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

package haveno.core.payment.validation;

import com.google.inject.Inject;
import haveno.asset.AddressValidationResult;
import haveno.asset.Asset;
import haveno.asset.AssetRegistry;
import haveno.common.config.Config;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.util.validation.InputValidator;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
public final class CryptoAddressValidator extends InputValidator {

    private final AssetRegistry assetRegistry;
    private String currencyCode;

    @Inject
    public CryptoAddressValidator(AssetRegistry assetRegistry) {
        this.assetRegistry = assetRegistry;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    @Override
    public ValidationResult validate(String input) {
        ValidationResult validationResult = super.validate(input);
        if (!validationResult.isValid || currencyCode == null)
            return validationResult;

        Optional<Asset> optionalAsset = CurrencyUtil.findAsset(assetRegistry, currencyCode,
                Config.baseCurrencyNetwork());
        if (optionalAsset.isPresent()) {
            Asset asset = optionalAsset.get();
            AddressValidationResult result = asset.validateAddress(input);
            if (!result.isValid()) {
                return new ValidationResult(false, Res.get(result.getI18nKey(), asset.getTickerSymbol(),
                        result.getMessage()));
            }

            return new ValidationResult(true);
        } else {
            return new ValidationResult(false);
        }
    }
}
