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

package haveno.core.support.messages;

import haveno.common.proto.network.NetworkEnvelope;
import haveno.core.support.SupportType;
import haveno.network.p2p.UidMessage;
import haveno.network.p2p.mailbox.MailboxMessage;
import lombok.EqualsAndHashCode;
import lombok.Getter;

@EqualsAndHashCode(callSuper = true)
@Getter
public abstract class SupportMessage extends NetworkEnvelope implements MailboxMessage, UidMessage {
    protected final String uid;

    // Added with v1.1.6. Old clients will not have set that field and we fall back to entry 0 which is ARBITRATION.
    protected final SupportType supportType;

    public SupportMessage(String messageVersion, String uid, SupportType supportType) {
        super(messageVersion);
        this.uid = uid;
        this.supportType = supportType;
    }

    public abstract String getTradeId();

    @Override
    public String toString() {
        return "DisputeMessage{" +
                "\n     uid='" + uid + '\'' +
                ",\n     messageVersion=" + messageVersion +
                ",\n     supportType=" + supportType +
                "\n} " + super.toString();
    }
}
