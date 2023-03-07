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

package haveno.desktop.main.offer;

import haveno.core.locale.Res;
import haveno.core.monetary.Volume;
import haveno.core.offer.OfferUtil;
import haveno.core.trade.HavenoUtils;
import haveno.core.util.VolumeUtil;
import haveno.core.util.coin.CoinFormatter;
import haveno.desktop.util.DisplayUtils;
import haveno.desktop.util.GUIUtil;
import java.math.BigInteger;
import java.util.Optional;

// Shared utils for ViewModels
public class OfferViewModelUtil {
    public static String getTradeFeeWithFiatEquivalent(OfferUtil offerUtil,
                                                       BigInteger tradeFee,
                                                       CoinFormatter formatter) {

        Optional<Volume> optionalBtcFeeInFiat = offerUtil.getFeeInUserFiatCurrency(tradeFee,
                formatter);

        return DisplayUtils.getFeeWithFiatAmount(tradeFee, optionalBtcFeeInFiat, formatter);
    }

    public static String getTradeFeeWithFiatEquivalentAndPercentage(OfferUtil offerUtil,
                                                                    BigInteger tradeFee,
                                                                    BigInteger tradeAmount,
                                                                    CoinFormatter formatter,
                                                                    BigInteger minTradeFee) {
        String feeAsBtc = HavenoUtils.formatXmr(tradeFee, true);
        String percentage;
        if (tradeFee.compareTo(minTradeFee) <= 0) {
            percentage = Res.get("guiUtil.requiredMinimum")
                    .replace("(", "")
                    .replace(")", "");
        } else {
            percentage = GUIUtil.getPercentage(tradeFee, tradeAmount) +
                    " " + Res.get("guiUtil.ofTradeAmount");
        }
        return offerUtil.getFeeInUserFiatCurrency(tradeFee,
                formatter)
                .map(VolumeUtil::formatAverageVolumeWithCode)
                .map(feeInFiat -> Res.get("feeOptionWindow.btcFeeWithFiatAndPercentage", feeAsBtc, feeInFiat, percentage))
                .orElseGet(() -> Res.get("feeOptionWindow.btcFeeWithPercentage", feeAsBtc, percentage));
    }
}
