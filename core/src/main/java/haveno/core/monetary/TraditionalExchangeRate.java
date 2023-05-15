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

 package haveno.core.monetary;

 import static com.google.common.base.Preconditions.checkArgument;
 
 import java.io.Serializable;
 import java.math.BigInteger;
 
 import org.bitcoinj.core.Coin;
 
 import com.google.common.base.Objects;
 
 /**
  * An exchange rate is expressed as a ratio of a {@link Coin} and a traditional money amount.
  */
 public class TraditionalExchangeRate implements Serializable {
 
     public final Coin coin;
     public final TraditionalMoney traditionalMoney;
 
     /** Construct exchange rate. This amount of coin is worth that amount of money. */
     public TraditionalExchangeRate(Coin coin, TraditionalMoney traditionalMoney) {
         checkArgument(coin.isPositive());
         checkArgument(traditionalMoney.isPositive());
         checkArgument(traditionalMoney.currencyCode != null, "currency code required");
         this.coin = coin;
         this.traditionalMoney = traditionalMoney;
     }
 
     /** Construct exchange rate. One coin is worth this amount of traditional money. */
     public TraditionalExchangeRate(TraditionalMoney traditionalMoney) {
         this(Coin.COIN, traditionalMoney);
     }
 
     /**
      * Convert a coin amount to a traditional money amount using this exchange rate.
      * @throws ArithmeticException if the converted amount is too high or too low.
      */
     public TraditionalMoney coinToTraditionalMoney(Coin convertCoin) {
         // Use BigInteger because it's much easier to maintain full precision without overflowing.
         final BigInteger converted = BigInteger.valueOf(convertCoin.value).multiply(BigInteger.valueOf(traditionalMoney.value))
                 .divide(BigInteger.valueOf(coin.value));
         if (converted.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0
                 || converted.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0)
             throw new ArithmeticException("Overflow");
         return TraditionalMoney.valueOf(traditionalMoney.currencyCode, converted.longValue());
     }
 
     /**
      * Convert a traditional money amount to a coin amount using this exchange rate.
      * @throws ArithmeticException if the converted coin amount is too high or too low.
      */
     public Coin traditionalMoneyToCoin(TraditionalMoney convertTraditionalMoney) {
         checkArgument(convertTraditionalMoney.currencyCode.equals(traditionalMoney.currencyCode), "Currency mismatch: %s vs %s",
                 convertTraditionalMoney.currencyCode, traditionalMoney.currencyCode);
         // Use BigInteger because it's much easier to maintain full precision without overflowing.
         final BigInteger converted = BigInteger.valueOf(convertTraditionalMoney.value).multiply(BigInteger.valueOf(coin.value))
                 .divide(BigInteger.valueOf(traditionalMoney.value));
         if (converted.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0
                 || converted.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0)
             throw new ArithmeticException("Overflow");
         try {
             return Coin.valueOf(converted.longValue());
         } catch (IllegalArgumentException x) {
             throw new ArithmeticException("Overflow: " + x.getMessage());
         }
     }
 
     @Override
     public boolean equals(Object o) {
         if (this == o) return true;
         if (o == null || getClass() != o.getClass()) return false;
         TraditionalExchangeRate other = (TraditionalExchangeRate) o;
         return Objects.equal(this.coin, other.coin) && Objects.equal(this.traditionalMoney, other.traditionalMoney);
     }
 
     @Override
     public int hashCode() {
         return Objects.hashCode(coin, traditionalMoney);
     }
 }
 