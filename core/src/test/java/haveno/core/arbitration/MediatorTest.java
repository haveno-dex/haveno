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

package haveno.core.arbitration;

import com.google.common.collect.Lists;
import haveno.common.crypto.PubKeyRing;
import haveno.core.support.dispute.mediation.mediator.Mediator;
import haveno.network.p2p.NodeAddress;
import org.junit.jupiter.api.Disabled;

import java.util.Date;

import static haveno.core.arbitration.ArbitratorTest.getBytes;

public class MediatorTest {

    @Disabled("TODO InvalidKeySpecException at haveno.common.crypto.Sig.getPublicKeyFromBytes(Sig.java:135)")
    public void testRoundtrip() {
        Mediator Mediator = getMediatorMock();


        //noinspection AccessStaticViaInstance
        Mediator.fromProto(Mediator.toProtoMessage().getMediator());
    }

    public static Mediator getMediatorMock() {
        return new Mediator(new NodeAddress("host", 1000),
                new PubKeyRing(getBytes(100), getBytes(100)),
                Lists.newArrayList(),
                new Date().getTime(),
                getBytes(100),
                "registrationSignature",
                "email",
                "info",
                null);
    }
}
