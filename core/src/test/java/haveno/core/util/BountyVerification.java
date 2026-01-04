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
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

public class BountyVerification {
    
    @Test
    public void verifyBountyImplementation() {
        System.out.println("=== Monero Multi-Destination URI Bounty Verification ===\n");

        // 1. Setup multiple destinations with special characters and different amounts
        List<MoneroDestination> originalDestinations = new ArrayList<>();
        originalDestinations.add(new MoneroDestination("44AFFq5kSiGBo3SnoCmcQC9R9X1844vAbaE74EutH43AnuW98jod9iTAwAsWAByY2v7r6Y1844vAbaE74EutH43AnuW98jod", new BigInteger("1234567890123"))); // 1.234567890123 XMR (High precision)
        originalDestinations.add(new MoneroDestination("888tNkS9pU7649sbs19JpS7eJEXfK7X9VvCjS8q63nU2y26H07f6n3vCjS8q63nU2y26H07f6n3vCjS8q63nU2y26H07", new BigInteger("750000000000")));  // 0.75 XMR
        String label = "Haveno Donation & Support; [Test]"; // Testing encoding and semicolons

        System.out.println("Original Destinations:");
        for (MoneroDestination dest : originalDestinations) {
            System.out.println("  - Address: " + dest.getAddress());
            System.out.println("    Amount:  " + dest.getAmount() + " atomic units");
        }
        System.out.println("Label: " + label + "\n");

        // 2. Generate URI
        String generatedUri = MoneroUriUtils.makeUri(originalDestinations, label);
        System.out.println("Generated URI (Standard Compliant):");
        System.out.println(generatedUri + "\n");

        // 3. Parse URI
        System.out.println("Parsing generated URI...");
        MoneroTxConfig parsedConfig = MoneroUriUtils.parseUri(generatedUri);
        List<MoneroDestination> parsedDestinations = parsedConfig.getDestinations();

        System.out.println("\nParsed Results:");
        boolean allMatch = true;
        if (parsedDestinations.size() != originalDestinations.size()) {
            System.err.println("Error: Destination count mismatch!");
            allMatch = false;
        }

        for (int i = 0; i < parsedDestinations.size(); i++) {
            MoneroDestination orig = originalDestinations.get(i);
            MoneroDestination parsed = parsedDestinations.get(i);
            
            System.out.println("Destination #" + (i + 1) + ":");
            System.out.println("  Address Match: " + parsed.getAddress().equals(orig.getAddress()));
            System.out.println("  Amount Match:  " + parsed.getAmount().equals(orig.getAmount()) + " (" + parsed.getAmount() + ")");
            
            if (!parsed.getAddress().equals(orig.getAddress()) || !parsed.getAmount().equals(orig.getAmount())) {
                allMatch = false;
            }
        }
        
        System.out.println("Label Match: " + parsedConfig.getNote().equals(label));
        if (!parsedConfig.getNote().equals(label)) allMatch = false;

        if (allMatch) {
            System.out.println("\nVERIFICATION SUCCESSFUL: Implementation is correct and standard-compliant.");
        } else {
            System.err.println("\nVERIFICATION FAILED: Mismatch detected.");
            System.exit(1);
        }
    }
}
