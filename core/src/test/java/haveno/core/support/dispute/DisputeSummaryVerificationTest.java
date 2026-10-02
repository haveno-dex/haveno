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

package haveno.core.support.dispute;

import haveno.common.crypto.Hash;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.util.Utilities;
import haveno.core.locale.Res;
import haveno.core.support.dispute.arbitration.arbitrator.Arbitrator;
import haveno.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import haveno.core.trade.HavenoUtils;
import haveno.network.p2p.NodeAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.List;
import java.util.Optional;
import java.util.PropertyResourceBundle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DisputeSummaryVerificationTest {

    private static final String BEGIN = "\n-----BEGIN SIGNATURE-----\n";
    private static final String END = "\n-----END SIGNATURE-----\n";

    @Test
    public void testSignaturesFromEverySummaryLocale(@TempDir File directory) throws Exception {
        Res.setBaseCurrencyCode("XMR");
        Res.setBaseCurrencyName("Monero");
        KeyRing keyRing = new KeyRing(new KeyStorage(directory), null, true);
        NodeAddress address = new NodeAddress("127.0.0.1", 9998);
        Arbitrator arbitrator = mock(Arbitrator.class);
        when(arbitrator.getPubKeyRing()).thenReturn(keyRing.getPubKeyRing());
        ArbitratorManager manager = mock(ArbitratorManager.class);
        when(manager.getDisputeAgentByNodeAddress(address)).thenReturn(Optional.of(arbitrator));

        for (String locale : List.of("", "_cs", "_de", "_es", "_fa", "_fr", "_it", "_ja",
                "_pt", "_pt-br", "_ru", "_th", "_tr", "_vi", "_zh-hans", "_zh-hant")) {
            try (var reader = new InputStreamReader(getClass().getResourceAsStream(
                    "/i18n/displayStrings" + locale + ".properties"), StandardCharsets.UTF_8)) {
                String pattern = new PropertyResourceBundle(reader).getString("disputeSummaryWindow.close.msg");
                String text = MessageFormat.format(pattern.replace("'", "''"), "date", "arbitrator",
                        address.getFullAddress(), "trade-id", "USD", "reason", "1 XMR", "0.6 XMR", "0.4 XMR", "notes");
                byte[] signature = HavenoUtils.sign(keyRing.getSignatureKeyPair().getPrivate(), Hash.getSha256Hash(text));
                String summary = text + BEGIN + Utilities.encodeToHex(signature) + END;
                assertDoesNotThrow(() -> DisputeSummaryVerification.verifySignature(summary, manager), locale);
            }
        }
    }

    @Test
    public void testSignedSummaryIsWellFormed() {
        assertTrue(DisputeSummaryVerification.isWellFormed("summary" + BEGIN + "abcd" + END));
    }

    @Test
    public void testSignedTextMayContainEndSeparator() {
        assertTrue(DisputeSummaryVerification.isWellFormed("notes" + END + "more" + BEGIN + "abcd" + END));
    }

    @Test
    public void testContentOutsideSignatureBlockIsRejected() {
        assertFalse(DisputeSummaryVerification.isWellFormed("summary" + BEGIN + "abcd" + END + "planted"));
        assertFalse(DisputeSummaryVerification.isWellFormed("summary" + BEGIN + "abcd" + END + "planted" + END));
        assertFalse(DisputeSummaryVerification.isWellFormed("summary" + BEGIN + "abcd" + BEGIN + "efgh" + END));
        assertFalse(DisputeSummaryVerification.isWellFormed("summary" + BEGIN + "abcd"));
    }
}
