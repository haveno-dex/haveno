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

package haveno.core.support.dispute.messages;

import haveno.core.support.SupportType;
import haveno.core.support.messages.SupportMessage;

import java.util.concurrent.TimeUnit;

public abstract class DisputeMessage extends SupportMessage {
    public static final long TTL = TimeUnit.DAYS.toMillis(15);

    public DisputeMessage(String messageVersion, String uid, SupportType supportType) {
        super(messageVersion, uid, supportType);
    }

    @Override
    public long getTTL() {
        return TTL;
    }

}
