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

package haveno.core.payment;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import haveno.common.util.MathUtils;
import haveno.core.trade.HavenoUtils;
import java.math.BigInteger;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class TradeLimits {
    private static final BigInteger MAX_TRADE_LIMIT = HavenoUtils.xmrToAtomicUnits(96.0); // max trade limit for lowest risk payment method. Others will get derived from that.
    @Nullable
    @Getter
    private static TradeLimits INSTANCE;

    @Inject
    public TradeLimits() {
        INSTANCE = this;
    }

    public void onAllServicesInitialized() {
        // Do nothing but required to enforce class creation by guice.
        // The TradeLimits is used by PaymentMethod via the static INSTANCE and this would not trigger class creation by
        // guice.
    }


    /**
     * The default trade limits.
     *
     * @see haveno.core.payment.payload.PaymentMethod
     * @return the maximum trade limit
     */
    public BigInteger getMaxTradeLimit() {
       return MAX_TRADE_LIMIT;
    }

    // We possibly rounded value for the first month gets multiplied by 4 to get the trade limit after the account
    // age witness is not considered anymore (> 2 months).

    /**
     *
     * @param maxLimit          Satoshi value of max trade limit
     * @param riskFactor        Risk factor to decrease trade limit for higher risk payment methods
     * @return Possibly adjusted trade limit to avoid that in first month trade limit get precision < 4.
     */
    public long getRoundedRiskBasedTradeLimit(long maxLimit, long riskFactor) {
        return getFirstMonthRiskBasedTradeLimit(maxLimit, riskFactor) * 4;
    }

    // The first month we allow only 0.25% of the trade limit. We want to ensure that precision is <=4 otherwise we round.

    /**
     *
     * @param maxLimit          Satoshi value of max trade limit
     * @param riskFactor        Risk factor to decrease trade limit for higher risk payment methods
     * @return Rounded trade limit for first month to avoid BTC value with precision < 4.
     */
    @VisibleForTesting
    long getFirstMonthRiskBasedTradeLimit(long maxLimit, long riskFactor) {
        // The first month we use 1/4 of the max limit. We multiply with riskFactor, so 1/ (4 * 8) is smallest limit in
        // first month of a maxTradeLimitHighRisk method
        long smallestLimit = maxLimit / (4 * riskFactor);  // e.g. 100000000 / 32 = 3125000
        // We want to avoid more than 4 decimal places (100000000 / 32 = 3125000 or 1 BTC / 32 = 0.03125 BTC).
        // We want rounding to 0.0313 BTC
        double decimalForm = MathUtils.scaleDownByPowerOf10((double) smallestLimit, 8);
        double rounded = MathUtils.roundDouble(decimalForm, 4);
        return MathUtils.roundDoubleToLong(MathUtils.scaleUpByPowerOf10(rounded, 8));
    }
}
