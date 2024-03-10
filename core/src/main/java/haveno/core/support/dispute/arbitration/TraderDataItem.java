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

package haveno.core.support.dispute.arbitration;

import haveno.core.account.witness.AccountAgeWitness;
import haveno.core.payment.payload.PaymentAccountPayload;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.math.BigInteger;
import java.security.PublicKey;

// TODO consider to move to signed witness domain
@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class TraderDataItem {
    private final PaymentAccountPayload paymentAccountPayload;
    @EqualsAndHashCode.Include
    private final AccountAgeWitness accountAgeWitness;
    private final BigInteger tradeAmount;
    private final PublicKey peersPubKey;

    public TraderDataItem(PaymentAccountPayload paymentAccountPayload,
                          AccountAgeWitness accountAgeWitness,
                          BigInteger tradeAmount,
                          PublicKey peersPubKey) {
        this.paymentAccountPayload = paymentAccountPayload;
        this.accountAgeWitness = accountAgeWitness;
        this.tradeAmount = tradeAmount;
        this.peersPubKey = peersPubKey;
    }
}
