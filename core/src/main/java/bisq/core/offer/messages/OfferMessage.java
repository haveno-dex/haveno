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

package haveno.core.offer.messages;

import haveno.network.p2p.DirectMessage;
import haveno.network.p2p.UidMessage;

import haveno.common.proto.network.NetworkEnvelope;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import javax.annotation.Nullable;

@EqualsAndHashCode(callSuper = true)
@Getter
@ToString
public abstract class OfferMessage extends NetworkEnvelope implements DirectMessage, UidMessage {
    public final String offerId;

    // Added in version 0.7.1. Can be null if we receive the msg from a peer with an older version
    @Nullable
    protected final String uid;

    protected OfferMessage(int messageVersion, String offerId, @Nullable String uid) {
        super(messageVersion);
        this.offerId = offerId;
        this.uid = uid;
    }
}
