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

package haveno.core.offer.placeoffer.tasks;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import haveno.common.taskrunner.Task;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OpenOffer;
import haveno.core.offer.placeoffer.PlaceOfferModel;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.protocol.TradeProtocol;
import haveno.core.xmr.model.XmrAddressEntry;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroRpcConnection;
import monero.daemon.model.MoneroOutput;
import monero.wallet.model.MoneroTxWallet;

@Slf4j
public class MakerReserveOfferFunds extends Task<PlaceOfferModel> {

    public MakerReserveOfferFunds(TaskRunner taskHandler, PlaceOfferModel model) {
        super(taskHandler, model);
    }

    @Override
    protected void run() {

        OpenOffer openOffer = model.getOpenOffer();
        Offer offer = openOffer.getOffer();

        try {
            runInterceptHook();

            // skip if reserve tx already created
            if (openOffer.getReserveTxHash() != null && !openOffer.getReserveTxHash().isEmpty()) {
                log.info("Reserve tx already created for offerId={}", openOffer.getShortId());
                complete();
                return;
            }

            // verify monero connection
            model.getXmrWalletService().getXmrConnectionService().verifyConnection();

            // create reserve tx
            MoneroTxWallet reserveTx = null;
            synchronized (HavenoUtils.xmrWalletService.getWalletLock()) {

                // reset protocol timeout
                verifyPending();
                model.getProtocol().startTimeoutTimer();

                // collect relevant info
                BigInteger penaltyFee = HavenoUtils.multiply(offer.getAmount(), offer.getPenaltyFeePct());
                BigInteger makerFee = offer.getMaxMakerFee();
                BigInteger sendAmount = offer.getDirection() == OfferDirection.BUY ? BigInteger.ZERO : offer.getAmount();
                BigInteger securityDeposit = offer.getDirection() == OfferDirection.BUY ? offer.getMaxBuyerSecurityDeposit() : offer.getMaxSellerSecurityDeposit();
                String returnAddress = model.getXmrWalletService().getOrCreateAddressEntry(offer.getId(), XmrAddressEntry.Context.TRADE_PAYOUT).getAddressString();
                XmrAddressEntry fundingEntry = model.getXmrWalletService().getAddressEntry(offer.getId(), XmrAddressEntry.Context.OFFER_FUNDING).orElse(null);
                Integer preferredSubaddressIndex = fundingEntry == null ? null : fundingEntry.getSubaddressIndex();

                // attempt creating reserve tx
                try {
                    synchronized (HavenoUtils.getWalletFunctionLock()) {
                        for (int i = 0; i < TradeProtocol.MAX_ATTEMPTS; i++) {
                            MoneroRpcConnection sourceConnection = model.getXmrWalletService().getXmrConnectionService().getConnection();
                            try {
                                //if (true) throw new RuntimeException("Pretend error");
                                reserveTx = model.getXmrWalletService().createReserveTx(penaltyFee, makerFee, sendAmount, securityDeposit, returnAddress, openOffer.isReserveExactAmount(), preferredSubaddressIndex);
                            } catch (Exception e) {
                                log.warn("Error creating reserve tx, offerId={}, attempt={}/{}, error={}", openOffer.getShortId(), i + 1, TradeProtocol.MAX_ATTEMPTS, e.getMessage());
                                model.getXmrWalletService().handleWalletError(e, sourceConnection);
                                verifyPending();
                                if (i == TradeProtocol.MAX_ATTEMPTS - 1) throw e;
                                model.getProtocol().startTimeoutTimer(); // reset protocol timeout
                                HavenoUtils.waitFor(TradeProtocol.REPROCESS_DELAY_MS); // wait before retrying
                            }
        
                            // verify still open
                            verifyPending();
                            if (reserveTx != null) break;
                        }
                    }
                } catch (Exception e) {

                    // reset state with wallet lock
                    model.getXmrWalletService().resetAddressEntriesForOpenOffer(offer.getId());
                    if (reserveTx != null) model.getXmrWalletService().thawOutputs(HavenoUtils.getInputKeyImages(reserveTx));
                    offer.getOfferPayload().setReserveTxKeyImages(null);
                    throw e;
                }

                // reset protocol timeout
                model.getProtocol().startTimeoutTimer();

                // collect reserved key images
                List<String> reservedKeyImages = new ArrayList<String>();
                for (MoneroOutput input : reserveTx.getInputs()) reservedKeyImages.add(input.getKeyImage().getHex());

                // update offer state
                openOffer.setReserveTxHash(reserveTx.getHash());
                openOffer.setReserveTxHex(reserveTx.getFullHex());
                openOffer.setReserveTxKey(reserveTx.getKey());
                offer.getOfferPayload().setReserveTxKeyImages(reservedKeyImages);
            }
            complete();
        } catch (Throwable t) {
            offer.setErrorMessage("An error occurred.\n" +
                "Error message:\n"
                + t.getMessage());
            failed(t);
        }
    }

    private boolean isPending() {
        return model.getOpenOffer().isPending();
    }

    private void verifyPending() {
        if (!isPending()) throw new RuntimeException("Offer " + model.getOpenOffer().getOffer().getId() + " is canceled");
    }
}
