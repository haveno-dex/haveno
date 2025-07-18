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

package haveno.core.trade.protocol.tasks;

import haveno.common.taskrunner.TaskRunner;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OpenOffer;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.MakerTrade;
import haveno.core.trade.Trade;
import haveno.core.trade.protocol.TradeProtocol;
import haveno.core.xmr.model.XmrAddressEntry;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroRpcConnection;
import monero.wallet.model.MoneroTxWallet;

import java.math.BigInteger;

@Slf4j
public class MakerRecreateReserveTx extends TradeTask {

    public MakerRecreateReserveTx(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            // maker trade expected
            if (!(trade instanceof MakerTrade)) {
                throw new RuntimeException("Expected maker trade but was " + trade.getClass().getSimpleName() + " " + trade.getShortId() + ". That should never happen.");
            }

            // get open offer
            OpenOffer openOffer = HavenoUtils.openOfferManager.getOpenOffer(trade.getOffer().getId()).orElse(null);
            if (openOffer == null) throw new RuntimeException("Open offer not found for " + trade.getClass().getSimpleName() + " " + trade.getId());
            Offer offer = openOffer.getOffer();

            // reset reserve tx state
            trade.getSelf().setReserveTxHex(null);
            trade.getSelf().setReserveTxHash(null);
            trade.getSelf().setReserveTxKey(null);
            trade.getSelf().setReserveTxKeyImages(null);

            // recreate reserve tx
            log.warn("Maker is recreating reserve tx for tradeId={}", trade.getShortId());
            MoneroTxWallet reserveTx = null;
            synchronized (HavenoUtils.xmrWalletService.getWalletLock()) {

                // check for timeout
                if (isTimedOut()) throw new RuntimeException("Trade protocol has timed out while getting lock to create reserve tx, tradeId=" + trade.getShortId());
                trade.startProtocolTimeout();

                // thaw reserved key images
                log.info("Thawing reserve tx key images for tradeId={}", trade.getShortId());
                HavenoUtils.xmrWalletService.thawOutputs(openOffer.getOffer().getOfferPayload().getReserveTxKeyImages());

                // check for timeout
                if (isTimedOut()) throw new RuntimeException("Trade protocol has timed out while thawing key images, tradeId=" + trade.getShortId());
                trade.startProtocolTimeout();

                // collect relevant info
                BigInteger makerFee = offer.getMaxMakerFee();
                BigInteger sendAmount = offer.getDirection() == OfferDirection.BUY ? BigInteger.ZERO : offer.getAmount();
                BigInteger securityDeposit = offer.getDirection() == OfferDirection.BUY ? offer.getMaxBuyerSecurityDeposit() : offer.getMaxSellerSecurityDeposit();
                BigInteger penaltyFee = HavenoUtils.multiply(securityDeposit, offer.getPenaltyFeePct());
                String returnAddress = model.getXmrWalletService().getAddressEntry(offer.getId(), XmrAddressEntry.Context.TRADE_PAYOUT).get().getAddressString();
                XmrAddressEntry fundingEntry = model.getXmrWalletService().getAddressEntry(offer.getId(), XmrAddressEntry.Context.OFFER_FUNDING).orElse(null);
                Integer preferredSubaddressIndex = fundingEntry == null ? null : fundingEntry.getSubaddressIndex();

                // attempt re-creating reserve tx
                try {
                    synchronized (HavenoUtils.getWalletFunctionLock()) {
                        for (int i = 0; i < TradeProtocol.MAX_ATTEMPTS; i++) {
                            MoneroRpcConnection sourceConnection = trade.getXmrConnectionService().getConnection();
                            try {
                                reserveTx = model.getXmrWalletService().createReserveTx(penaltyFee, makerFee, sendAmount, securityDeposit, returnAddress, openOffer.isReserveExactAmount(), preferredSubaddressIndex);
                            } catch (IllegalStateException e) {
                                log.warn("Illegal state creating reserve tx, tradeId={}, error={}", trade.getShortId(), i + 1, e.getMessage());
                                throw e;
                            } catch (Exception e) {
                                log.warn("Error creating reserve tx, tradeId={}, attempt={}/{}, error={}", trade.getShortId(), i + 1, TradeProtocol.MAX_ATTEMPTS, e.getMessage());
                                trade.getXmrWalletService().handleWalletError(e, sourceConnection);
                                if (isTimedOut()) throw new RuntimeException("Trade protocol has timed out while creating reserve tx, tradeId=" + trade.getShortId());
                                if (i == TradeProtocol.MAX_ATTEMPTS - 1) throw e;
                                HavenoUtils.waitFor(TradeProtocol.REPROCESS_DELAY_MS); // wait before retrying
                            }
            
                            // check for timeout
                            if (isTimedOut()) throw new RuntimeException("Trade protocol has timed out while creating reserve tx, tradeId=" + trade.getShortId());
                            if (reserveTx != null) break;
                        }
                    }
                } catch (Exception e) {

                    // reset state
                    if (reserveTx != null) model.getXmrWalletService().thawOutputs(HavenoUtils.getInputKeyImages(reserveTx));
                    model.getXmrWalletService().freezeOutputs(offer.getOfferPayload().getReserveTxKeyImages());
                    trade.getSelf().setReserveTxKeyImages(null);
                    throw e;
                }

                // reset protocol timeout
                trade.startProtocolTimeout();

                // update state
                trade.getSelf().setReserveTxHash(reserveTx.getHash());
                trade.getSelf().setReserveTxHex(reserveTx.getFullHex());
                trade.getSelf().setReserveTxKey(reserveTx.getKey());
                trade.getSelf().setReserveTxKeyImages(HavenoUtils.getInputKeyImages(reserveTx));
                trade.getXmrWalletService().freezeOutputs(HavenoUtils.getInputKeyImages(reserveTx));
            }

            // save process state
            processModel.setReserveTx(reserveTx); // TODO: remove this? how is it used?
            processModel.getTradeManager().requestPersistence();
            complete();
        } catch (Throwable t) {
            trade.setErrorMessage("An error occurred.\n" +
                "Error message:\n"
                + t.getMessage());
            failed(t);
        }
    }

    private boolean isTimedOut() {
        return !processModel.getTradeManager().hasOpenTrade(trade);
    }
}
