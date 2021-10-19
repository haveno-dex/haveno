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

package bisq.desktop.main.offer;

import bisq.desktop.util.DisplayUtils;
import bisq.desktop.util.GUIUtil;

import bisq.core.locale.Res;
import bisq.core.monetary.Volume;
import bisq.core.offer.OfferUtil;
import bisq.core.util.coin.CoinFormatter;

import org.bitcoinj.core.Coin;

import java.util.Optional;

public class FeeUtil {
    public static String getTradeFeeWithFiatEquivalent(OfferUtil offerUtil,
                                                       Coin tradeFee,
                                                       CoinFormatter formatter) {

        Optional<Volume> optionalBtcFeeInFiat = offerUtil.getFeeInUserFiatCurrency(tradeFee);
        return DisplayUtils.getFeeWithFiatAmount(tradeFee, optionalBtcFeeInFiat, formatter);
    }

    public static String getTradeFeeWithFiatEquivalentAndPercentage(OfferUtil offerUtil,
                                                                    Coin tradeFee,
                                                                    Coin tradeAmount,
                                                                    CoinFormatter formatter,
                                                                    Coin minTradeFee) {
            String feeAsBtc = formatter.formatCoinWithCode(tradeFee);
            String percentage;
            if (!tradeFee.isGreaterThan(minTradeFee)) {
                percentage = Res.get("guiUtil.requiredMinimum")
                        .replace("(", "")
                        .replace(")", "");
            } else {
                percentage = GUIUtil.getPercentage(tradeFee, tradeAmount) +
                        " " + Res.get("guiUtil.ofTradeAmount");
            }
            return offerUtil.getFeeInUserFiatCurrency(tradeFee)
                    .map(DisplayUtils::formatAverageVolumeWithCode)
                    .map(feeInFiat -> Res.get("feeOptionWindow.btcFeeWithFiatAndPercentage", feeAsBtc, feeInFiat, percentage))
                    .orElseGet(() -> Res.get("feeOptionWindow.btcFeeWithPercentage", feeAsBtc, percentage));
    }
}
