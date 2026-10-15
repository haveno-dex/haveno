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

package haveno.desktop.main.overlays.windows;

import haveno.core.monetary.TraditionalMoney;
import haveno.core.monetary.Volume;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class SepaQRCodeWindowTest {

    private static Volume eur(String amount) {
        return new Volume(TraditionalMoney.parseTraditionalMoney("EUR", amount));
    }

    @Test
    public void testEpcPayload() {
        assertEquals("BCD\n001\n1\nSCT\nCOBADEFFXXX\nJörg Müller\nDE89370400440532013000\nEUR120.00",
                SepaQRCodeWindow.getEpcPayload(" Jörg Müller ", "cobadeff xxx", "de89 3704 0044 0532 0130 00", eur("120")));
        assertEquals("BCD\n001\n1\nSCT\nCOBADEFF\n" + "A".repeat(70) + "\nDE89370400440532013000\nEUR0.50",
                SepaQRCodeWindow.getEpcPayload("A".repeat(70), "COBADEFF", "DE89370400440532013000", eur("0.5")));
    }

    @Test
    public void testEpcPayloadRejectsFieldsNotEncodableAsShown() {
        String iban = "DE89370400440532013000";
        assertNull(SepaQRCodeWindow.getEpcPayload("Alice\nFR7630006000011234567890189", "COBADEFF", iban, eur("10")));
        assertNull(SepaQRCodeWindow.getEpcPayload("Ali\u2028ce", "COBADEFF", iban, eur("10")));
        assertNull(SepaQRCodeWindow.getEpcPayload("A".repeat(71), "COBADEFF", iban, eur("10")));
        assertNull(SepaQRCodeWindow.getEpcPayload(" ", "COBADEFF", iban, eur("10")));
        assertNull(SepaQRCodeWindow.getEpcPayload("Alice", "", iban, eur("10")));
        assertNull(SepaQRCodeWindow.getEpcPayload("Alice", "COBADEFFXXXX", iban, eur("10")));
        assertNull(SepaQRCodeWindow.getEpcPayload("Alice", "COBADEFF", iban + "\nEUR9999", eur("10")));
        assertNull(SepaQRCodeWindow.getEpcPayload("Alice", "SOLADE\u00DF", iban, eur("10")));
        assertNull(SepaQRCodeWindow.getEpcPayload("Alice", "COBADEFF", "GB82WE\u017FT12345698765432", eur("10")));
        assertNull(SepaQRCodeWindow.getEpcPayload("Alice", "COBADEFF", iban, eur("1000000000")));
        assertNull(SepaQRCodeWindow.getEpcPayload("Alice", "COBADEFF", iban, new Volume(TraditionalMoney.parseTraditionalMoney("USD", "10"))));
        assertNull(SepaQRCodeWindow.getEpcPayload("Alice", "COBADEFF", iban, null));
    }

    @Test
    public void testEpcPayloadRejectsQrVersionOverLimit() {
        String iban = "DE89370400440532013000";
        assertNotNull(SepaQRCodeWindow.getEpcPayload("\uD83D\uDE00".repeat(60), "COBADEFF", iban, eur("10")));
        assertNull(SepaQRCodeWindow.getEpcPayload("\uD83D\uDE00".repeat(69), "COBADEFF", iban, eur("10")));
    }
}
