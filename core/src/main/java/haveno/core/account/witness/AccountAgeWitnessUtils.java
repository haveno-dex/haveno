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

package haveno.core.account.witness;

import haveno.common.crypto.Hash;
import haveno.common.util.Utilities;
import haveno.core.account.sign.SignedWitness;
import haveno.core.account.sign.SignedWitnessService;
import haveno.network.p2p.storage.P2PDataStorage;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Collection;
import java.util.Stack;

@Slf4j
public class AccountAgeWitnessUtils {
    private final AccountAgeWitnessService accountAgeWitnessService;
    private final SignedWitnessService signedWitnessService;

    AccountAgeWitnessUtils(AccountAgeWitnessService accountAgeWitnessService,
                           SignedWitnessService signedWitnessService) {
        this.accountAgeWitnessService = accountAgeWitnessService;
        this.signedWitnessService = signedWitnessService;
    }

    // Log tree of signed witnesses
    public void logSignedWitnesses() {
        var orphanSigners = signedWitnessService.getRootSignedWitnessSet(true);
        log.info("Orphaned signed account age witnesses:");
        orphanSigners.forEach(w -> {
            log.info("{}: Signer PKH: {} Owner PKH: {} time: {}", w.getVerificationMethod().toString(),
                    Utilities.bytesAsHexString(Hash.getRipemd160hash(w.getSignerPubKey())).substring(0, 7),
                    Utilities.bytesAsHexString(Hash.getRipemd160hash(w.getWitnessOwnerPubKey())).substring(0, 7),
                    w.getDate());
            logChild(w, "  ", new Stack<>());
        });
    }

    private void logChild(SignedWitness sigWit, String initString, Stack<P2PDataStorage.ByteArray> excluded) {
        log.info("{}AEW: {} PKH: {} time: {}", initString,
                Utilities.bytesAsHexString(sigWit.getAccountAgeWitnessHash()).substring(0, 7),
                Utilities.bytesAsHexString(Hash.getRipemd160hash(sigWit.getWitnessOwnerPubKey())).substring(0, 7),
                sigWit.getDate());
        signedWitnessService.getSignedWitnessMapValues().forEach(w -> {
            if (!excluded.contains(new P2PDataStorage.ByteArray(w.getWitnessOwnerPubKey())) &&
                    Arrays.equals(w.getSignerPubKey(), sigWit.getWitnessOwnerPubKey())) {
                excluded.push(new P2PDataStorage.ByteArray(w.getWitnessOwnerPubKey()));
                logChild(w, initString + "  ", excluded);
                excluded.pop();
            }
        });
    }

    // Log signers per
    public void logSigners() {
        log.info("Signers per AEW");
        Collection<SignedWitness> signedWitnessMapValues = signedWitnessService.getSignedWitnessMapValues();
        signedWitnessMapValues.forEach(w -> {
                    log.info("AEW {}", Utilities.bytesAsHexString(w.getAccountAgeWitnessHash()));
                    signedWitnessMapValues.forEach(ww -> {
                        if (Arrays.equals(w.getSignerPubKey(), ww.getWitnessOwnerPubKey())) {
                            log.info("  {}", Utilities.bytesAsHexString(ww.getAccountAgeWitnessHash()));
                        }
                    });
                }
        );
    }

    public void logUnsignedSignerPubKeys() {
        log.info("Unsigned signer pubkeys");
        signedWitnessService.getUnsignedSignerPubKeys().forEach(signedWitness ->
                log.info("PK hash {} date {}",
                        Utilities.bytesAsHexString(Hash.getRipemd160hash(signedWitness.getSignerPubKey())),
                        signedWitness.getDate()));
    }
}
