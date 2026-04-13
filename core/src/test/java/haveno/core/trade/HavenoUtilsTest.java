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

package haveno.core.trade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HavenoUtilsTest {

    @Test
    public void testIsLocalHostSupportsIpv6LoopbackWithoutScheme() {
        assertTrue(HavenoUtils.isLocalHost("[::1]:18081"));
        assertTrue(HavenoUtils.isLocalHost("http://[::1]:18081"));
    }

    @Test
    public void testIsPrivateIpSupportsIpv6WithoutScheme() {
        assertTrue(HavenoUtils.isPrivateIp("[fe80::1]:18081"));
        assertTrue(HavenoUtils.isPrivateIp("http://[fe80::1]:18081"));
        assertFalse(HavenoUtils.isPrivateIp("[2607:3c40:1900:33e0::1]:18089"));
    }
}
