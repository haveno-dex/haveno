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

import monero.wallet.model.MoneroDestination;
import monero.wallet.model.MoneroTxConfig;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class MoneroUriUtilsTest {

    @Test
    public void testMakeUriSingle() {
        List<MoneroDestination> destinations = List.of(new MoneroDestination("addr1", new BigInteger("1000000000000")));
        String uri = MoneroUriUtils.makeUri(destinations, "test");
        assertEquals("monero:addr1?tx_amount=1.0&tx_description=test", uri);
    }

    @Test
    public void testMakeUriMultiple() {
        List<MoneroDestination> destinations = new ArrayList<>();
        destinations.add(new MoneroDestination("addr1", new BigInteger("1000000000000")));
        destinations.add(new MoneroDestination("addr2", new BigInteger("500000000000")));
        String uri = MoneroUriUtils.makeUri(destinations, "donations");
        assertEquals("monero:addr1;addr2?tx_amount=1.0;0.5&tx_description=donations", uri);
    }

    @Test
    public void testParseUriMultiple() {
        String uri = "monero:addr1;addr2?tx_amount=1.0;0.5&tx_description=donations";
        MoneroTxConfig config = MoneroUriUtils.parseUri(uri);
        assertEquals(2, config.getDestinations().size());
        assertEquals("addr1", config.getDestinations().get(0).getAddress());
        assertEquals(new BigInteger("1000000000000"), config.getDestinations().get(0).getAmount());
        assertEquals("addr2", config.getDestinations().get(1).getAddress());
        assertEquals(new BigInteger("500000000000"), config.getDestinations().get(1).getAmount());
        assertEquals("donations", config.getNote());
    }

    @Test
    public void testParseUriWithNoAmounts() {
        String uri = "monero:addr1;addr2?tx_description=test";
        MoneroTxConfig config = MoneroUriUtils.parseUri(uri);
        assertEquals(2, config.getDestinations().size());
        assertNull(config.getDestinations().get(0).getAmount());
        assertNull(config.getDestinations().get(1).getAmount());
    }
}
