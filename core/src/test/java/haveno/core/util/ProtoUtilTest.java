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

package haveno.core.util;

import haveno.core.offer.OfferDirection;
import haveno.core.offer.OpenOffer;
import org.junit.jupiter.api.Test;
import protobuf.OpenOffer.State;

import static haveno.common.proto.ProtoUtil.enumFromProto;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@SuppressWarnings("UnusedAssignment")
public class ProtoUtilTest {

    //TODO Use NetworkProtoResolver, PersistenceProtoResolver or ProtoResolver which are all in haveno.common.
    @Test
    public void testEnum() {
        OfferDirection direction = OfferDirection.SELL;
        OfferDirection direction2 = OfferDirection.BUY;
        OfferDirection realDirection = getDirection(direction);
        OfferDirection realDirection2 = getDirection(direction2);
        assertEquals("SELL", realDirection.name());
        assertEquals("BUY", realDirection2.name());
    }

    @Test
    public void testUnknownEnum() {
        State result = State.PB_ERROR;
        try {
            OpenOffer.State.valueOf(result.name());
            fail();
        } catch (IllegalArgumentException ignore) {
        }
    }

    @Test
    public void testUnknownEnumFix() {
        State result = State.PB_ERROR;
        try {
            enumFromProto(OpenOffer.State.class, result.name());
            assertEquals(OpenOffer.State.AVAILABLE, enumFromProto(OpenOffer.State.class, "AVAILABLE"));
        } catch (IllegalArgumentException e) {
            fail();
        }
    }

    public static OfferDirection getDirection(OfferDirection direction) {
        return OfferDirection.valueOf(direction.name());
    }
}
