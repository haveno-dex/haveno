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

package haveno.core.xmr.wallet;

import haveno.common.config.Config;
import haveno.core.trade.HavenoUtils;
import org.bitcoinj.core.Coin;

import java.math.BigInteger;

public class Restrictions {
    public static BigInteger MIN_TRADE_AMOUNT = HavenoUtils.xmrToAtomicUnits(0.1);
    public static BigInteger MIN_BUYER_SECURITY_DEPOSIT = HavenoUtils.xmrToAtomicUnits(0.1);
    // For the seller we use a fixed one as there is no way the seller can cancel the trade
    // To make it editable would just increase complexity.
    public static BigInteger SELLER_SECURITY_DEPOSIT = MIN_BUYER_SECURITY_DEPOSIT;
    // At mediation we require a min. payout to the losing party to keep incentive for the trader to accept the
    // mediated payout. For Refund agent cases we do not have that restriction.
    private static BigInteger MIN_REFUND_AT_MEDIATED_DISPUTE;

    public static Coin getMinNonDustOutput() {
        if (minNonDustOutput == null)
            minNonDustOutput = Config.baseCurrencyNetwork().getParameters().getMinNonDustOutput();
        return minNonDustOutput;
    }

    private static Coin minNonDustOutput;

    public static boolean isAboveDust(Coin amount) {
        return amount.compareTo(getMinNonDustOutput()) >= 0;
    }

    public static boolean isDust(Coin amount) {
        return !isAboveDust(amount);
    }

    public static BigInteger getMinTradeAmount() {
        return MIN_TRADE_AMOUNT;
    }

    public static double getDefaultBuyerSecurityDepositAsPercent() {
        return 0.15; // 15% of trade amount.
    }

    public static double getMinBuyerSecurityDepositAsPercent() {
        return 0.15; // 15% of trade amount.
    }

    public static double getMaxBuyerSecurityDepositAsPercent() {
        return 0.5; // 50% of trade amount. For a 1 BTC trade it is about 3500 USD @ 7000 USD/BTC
    }

    // We use MIN_BUYER_SECURITY_DEPOSIT as well as lower bound in case of small trade amounts.
    // So 0.0005 BTC is the min. buyer security deposit even with amount of 0.0001 BTC and 0.05% percentage value.
    public static BigInteger getMinBuyerSecurityDeposit() {
        return MIN_BUYER_SECURITY_DEPOSIT;
    }


    public static double getSellerSecurityDepositAsPercent() {
        return 0.15; // 15% of trade amount.
    }

    public static BigInteger getMinSellerSecurityDeposit() {
        return SELLER_SECURITY_DEPOSIT;
    }

    // This value must be lower than MIN_BUYER_SECURITY_DEPOSIT and SELLER_SECURITY_DEPOSIT
    public static BigInteger getMinRefundAtMediatedDispute() {
        if (MIN_REFUND_AT_MEDIATED_DISPUTE == null)
            MIN_REFUND_AT_MEDIATED_DISPUTE = HavenoUtils.xmrToAtomicUnits(0.0005);
        return MIN_REFUND_AT_MEDIATED_DISPUTE;
    }

    public static int getLockTime(boolean isAsset) {
        // 10 days for cryptos, 20 days for other payment methods
        return isAsset ? 144 * 10 : 144 * 20;
    }
}
