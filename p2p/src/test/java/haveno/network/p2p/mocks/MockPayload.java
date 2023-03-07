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

package haveno.network.p2p.mocks;

import haveno.common.app.Version;
import haveno.common.proto.network.NetworkEnvelope;
import haveno.network.p2p.storage.payload.ExpirablePayload;
import org.apache.commons.lang3.NotImplementedException;

@SuppressWarnings("ALL")
public final class MockPayload extends NetworkEnvelope implements ExpirablePayload {
    public final String msg;
    public long ttl;
    private final String messageVersion = Version.getP2PMessageVersion();

    public MockPayload(String msg) {
        super("0");
        this.msg = msg;
    }

    @Override
    public String getMessageVersion() {
        return messageVersion;
    }

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        throw new NotImplementedException("toProtoNetworkEnvelope not impl.");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MockPayload)) return false;

        MockPayload that = (MockPayload) o;

        return !(msg != null ? !msg.equals(that.msg) : that.msg != null);

    }

    @Override
    public int hashCode() {
        return msg != null ? msg.hashCode() : 0;
    }

    @Override
    public String toString() {
        return "MockData{" +
                "msg='" + msg + '\'' +
                '}';
    }

    @Override
    public long getTTL() {
        return ttl;
    }

}
