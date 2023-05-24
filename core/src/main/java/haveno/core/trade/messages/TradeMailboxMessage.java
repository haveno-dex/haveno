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

package haveno.core.trade.messages;

import haveno.network.p2p.mailbox.MailboxMessage;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.concurrent.TimeUnit;

@EqualsAndHashCode(callSuper = true)
@ToString
public abstract class TradeMailboxMessage extends TradeMessage implements MailboxMessage {
    public static final long TTL = TimeUnit.DAYS.toMillis(15);

    protected TradeMailboxMessage(String messageVersion, String tradeId, String uid) {
        super(messageVersion, tradeId, uid);
    }

    @Override
    public long getTTL() {
        return TTL;
    }

}
