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
import haveno.core.xmr.wallet.XmrWalletService;
import lombok.extern.slf4j.Slf4j;
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

            // verify monero connection
            model.getXmrWalletService().getConnectionService().verifyConnection();

            // create reserve tx
            MoneroTxWallet reserveTx = null;
            synchronized (XmrWalletService.WALLET_LOCK) {

                // collect relevant info
                BigInteger penaltyFee = HavenoUtils.multiply(offer.getAmount(), offer.getPenaltyFeePct());
                BigInteger makerFee = offer.getMaxMakerFee();
                BigInteger sendAmount = offer.getDirection() == OfferDirection.BUY ? BigInteger.ZERO : offer.getAmount();
                BigInteger securityDeposit = offer.getDirection() == OfferDirection.BUY ? offer.getMaxBuyerSecurityDeposit() : offer.getMaxSellerSecurityDeposit();
                String returnAddress = model.getXmrWalletService().getOrCreateAddressEntry(offer.getId(), XmrAddressEntry.Context.TRADE_PAYOUT).getAddressString();
                XmrAddressEntry fundingEntry = model.getXmrWalletService().getAddressEntry(offer.getId(), XmrAddressEntry.Context.OFFER_FUNDING).orElse(null);
                Integer preferredSubaddressIndex = fundingEntry == null ? null : fundingEntry.getSubaddressIndex();

                // attempt creating reserve tx
                synchronized (HavenoUtils.getWalletFunctionLock()) {
                    for (int i = 0; i < TradeProtocol.MAX_ATTEMPTS; i++) {
                        try {
                            reserveTx = model.getXmrWalletService().createReserveTx(penaltyFee, makerFee, sendAmount, securityDeposit, returnAddress, openOffer.isReserveExactAmount(), preferredSubaddressIndex);
                        } catch (Exception e) {
                            log.warn("Error creating reserve tx, attempt={}/{}, offerId={}, error={}", i + 1, TradeProtocol.MAX_ATTEMPTS, openOffer.getShortId(), e.getMessage());
                            if (i == TradeProtocol.MAX_ATTEMPTS - 1) throw e;
                            HavenoUtils.waitFor(TradeProtocol.REPROCESS_DELAY_MS); // wait before retrying
                        }
    
                        // check for error in case creating reserve tx exceeded timeout // TODO: better way?
                        if (!model.getXmrWalletService().getAddressEntry(offer.getId(), XmrAddressEntry.Context.TRADE_PAYOUT).isPresent()) {
                            throw new RuntimeException("An error has occurred posting offer " + offer.getId() + " causing its subaddress entry to be deleted");
                        }
                        if (reserveTx != null) break;
                    }
                }

                // collect reserved key images
                List<String> reservedKeyImages = new ArrayList<String>();
                for (MoneroOutput input : reserveTx.getInputs()) reservedKeyImages.add(input.getKeyImage().getHex());

                // update offer state
                openOffer.setReserveTxHash(reserveTx.getHash());
                openOffer.setReserveTxHex(reserveTx.getFullHex());
                openOffer.setReserveTxKey(reserveTx.getKey());
                offer.getOfferPayload().setReserveTxKeyImages(reservedKeyImages);
            }

            // reset protocol timeout
            model.getProtocol().startTimeoutTimer();
            model.setReserveTx(reserveTx);
            complete();
        } catch (Throwable t) {
            offer.setErrorMessage("An error occurred.\n" +
                "Error message:\n"
                + t.getMessage());
            failed(t);
        }
    }
}
