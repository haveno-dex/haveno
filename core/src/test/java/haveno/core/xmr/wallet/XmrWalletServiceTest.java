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

package haveno.core.xmr.wallet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class XmrWalletServiceTest {

    @Test
    public void testGetWalletRpcProxyUriAddsSocks5SchemeWhenSupported() {
        assertEquals("socks5://127.0.0.1:9050", XmrWalletRpcUtils.getProxyUri("127.0.0.1:9050", true));
        assertEquals("socks5://[::1]:9050", XmrWalletRpcUtils.getProxyUri("[::1]:9050", true));
    }

    @Test
    public void testGetWalletRpcProxyUriKeepsLegacyProxyUriWhenSocks5SchemeIsUnsupported() {
        assertEquals("127.0.0.1:9050", XmrWalletRpcUtils.getProxyUri("127.0.0.1:9050", false));
    }

    @Test
    public void testGetWalletRpcProxyUriDoesNotDuplicateExistingScheme() {
        assertEquals("socks5://127.0.0.1:9050", XmrWalletRpcUtils.getProxyUri("socks5://127.0.0.1:9050", true));
    }
}
