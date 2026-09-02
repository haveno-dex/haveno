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

package haveno.desktop.main;

import haveno.common.UserThread;
import haveno.core.locale.Res;
import haveno.core.offer.OpenOfferManager;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.app.HavenoApp;
import haveno.desktop.main.overlays.popups.Popup;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;

/**
 * This serves as shared space for static methods used from different views where no common parent view would fit as
 * owner of that code. We keep it strictly static. It should replace GUIUtil for those methods which are not utility
 * methods.
 */
@Slf4j
public class SharedPresentation {

    // collapse whitespace so seeds pasted with line breaks or double spaces validate
    public static String normalizeSeedWords(String seedWords) {
        return seedWords == null ? "" : seedWords.trim().replaceAll("\\s+", " ");
    }

    public static void restoreSeedWords(XmrWalletService xmrWalletService,
                                        OpenOfferManager openOfferManager,
                                        String seedWords,
                                        Long restoreHeight,
                                        LocalDate restoreDate) {
        // validate the seed off the user thread before destructive steps like removing open offers
        new Thread(() -> {
            boolean valid = isSeedValid(xmrWalletService, seedWords);
            UserThread.execute(() -> {
                if (!valid) {
                    new Popup().warning(Res.get("seed.restore.seedInvalid")).show();
                } else if (!openOfferManager.getObservableList().isEmpty()) {
                    new Popup().warning(Res.get("seed.restore.openOffers.warn"))
                            .actionButtonText(Res.get("shared.yes"))
                            .onAction(() -> openOfferManager.removeAllOpenOffers(() ->
                                    doRestoreSeedWords(xmrWalletService, seedWords, restoreHeight, restoreDate)))
                            .show();
                } else {
                    doRestoreSeedWords(xmrWalletService, seedWords, restoreHeight, restoreDate);
                }
            });
        }, "ValidateSeedWords").start();
    }

    // defer to validation at wallet creation if the seed cannot be checked
    private static boolean isSeedValid(XmrWalletService xmrWalletService, String seedWords) {
        try {
            return xmrWalletService.isSeedValid(seedWords);
        } catch (Exception e) {
            log.warn("Could not validate seed, deferring to wallet creation, error={}", e.getMessage());
            return true;
        }
    }

    private static void doRestoreSeedWords(XmrWalletService xmrWalletService, String seedWords, Long restoreHeight, LocalDate restoreDate) {
        // restore off the user thread since wallet creation blocks on the network
        new Thread(() -> {
            try {
                xmrWalletService.restoreWalletFromSeed(seedWords, restoreHeight, restoreDate);
                UserThread.execute(() -> {
                    log.info("Wallet restored with seed words");
                    new Popup().feedback(Res.get("seed.restore.success")).hideCloseButton().show();
                    HavenoApp.getShutDownHandler().run();
                });
            } catch (Throwable t) {
                log.error(t.toString(), t);
                UserThread.execute(() ->
                        new Popup().error(Res.get("seed.restore.error", Res.get("shared.errorMessageInline", t))).show());
            }
        }, "RestoreXmrWallet").start();
    }
}
