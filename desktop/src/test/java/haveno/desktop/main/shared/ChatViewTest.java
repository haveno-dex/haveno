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

package haveno.desktop.main.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ChatViewTest {

    @Test
    public void bareFileNameKeepsOrdinaryNames() {
        assertEquals("receipt.pdf", ChatView.bareFileName("receipt.pdf"));
        assertEquals("invoice 2026-11.pdf", ChatView.bareFileName("invoice 2026-11.pdf"));
        assertEquals("Überweisung.pdf", ChatView.bareFileName("Überweisung.pdf"));
    }

    @Test
    public void bareFileNameDropsDirectories() {
        assertEquals("receipt.pdf", ChatView.bareFileName(".config/autostart/receipt.pdf"));
        assertEquals(".bashrc", ChatView.bareFileName("../../../.bashrc"));
        assertEquals("authorized_keys", ChatView.bareFileName("/home/user/.ssh/authorized_keys"));
        assertEquals("evil.dll", ChatView.bareFileName("..\\..\\windows\\system32\\evil.dll"));
        assertEquals("c", ChatView.bareFileName("a/b/c/"));
    }

    @Test
    public void bareFileNameFallsBackWhenNothingUsableRemains() {
        assertEquals("attachment", ChatView.bareFileName(null));
        assertEquals("attachment", ChatView.bareFileName(""));
        assertEquals("attachment", ChatView.bareFileName("   "));
        assertEquals("attachment", ChatView.bareFileName("."));
        assertEquals("attachment", ChatView.bareFileName(".."));
        assertEquals("attachment", ChatView.bareFileName("/"));
    }
}
