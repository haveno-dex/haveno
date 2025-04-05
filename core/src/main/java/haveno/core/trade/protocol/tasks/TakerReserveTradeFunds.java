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
import haveno.core.offer.OfferDirection;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.TakerTrade;
import haveno.core.trade.Trade;
import haveno.core.trade.protocol.TradeProtocol;
import haveno.core.xmr.model.XmrAddressEntry;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroRpcConnection;
import monero.wallet.model.MoneroTxWallet;

import java.math.BigInteger;

@Slf4j
public class TakerReserveTradeFunds extends TradeTask {

    public TakerReserveTradeFunds(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            // taker trade expected
            if (!(trade instanceof TakerTrade)) {
                throw new RuntimeException("Expected taker trade but was " + trade.getClass().getSimpleName() + " " + trade.getShortId() + ". That should never happen.");
            }

            // create reserve tx unless deposit not required from buyer as taker
            MoneroTxWallet reserveTx = null;
            if (!trade.isBuyerAsTakerWithoutDeposit()) {
                synchronized (HavenoUtils.xmrWalletService.getWalletLock()) {

                    // check for timeout
                    if (isTimedOut()) throw new RuntimeException("Trade protocol has timed out while getting lock to create reserve tx, tradeId=" + trade.getShortId());
                    trade.startProtocolTimeout();

                    // collect relevant info
                    BigInteger penaltyFee = HavenoUtils.multiply(trade.getAmount(), trade.getOffer().getPenaltyFeePct());
                    BigInteger takerFee = trade.getTakerFee();
                    BigInteger sendAmount = trade.getOffer().getDirection() == OfferDirection.BUY ? trade.getAmount() : BigInteger.ZERO;
                    BigInteger securityDeposit = trade.getSecurityDepositBeforeMiningFee();
                    String returnAddress = trade.getXmrWalletService().getOrCreateAddressEntry(trade.getOffer().getId(), XmrAddressEntry.Context.TRADE_PAYOUT).getAddressString();

                    // attempt creating reserve tx
                    try {
                        synchronized (HavenoUtils.getWalletFunctionLock()) {
                            for (int i = 0; i < TradeProtocol.MAX_ATTEMPTS; i++) {
                                MoneroRpcConnection sourceConnection = trade.getXmrConnectionService().getConnection();
                                try {
                                    reserveTx = model.getXmrWalletService().createReserveTx(penaltyFee, takerFee, sendAmount, securityDeposit, returnAddress, false, null);
                                } catch (IllegalStateException e) {
                                    log.warn("Illegal state creating reserve tx, offerId={}, error={}", trade.getShortId(), i + 1, e.getMessage());
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

                        // reset state with wallet lock
                        model.getXmrWalletService().swapPayoutAddressEntryToAvailable(trade.getId());
                        if (reserveTx != null) {
                            model.getXmrWalletService().thawOutputs(HavenoUtils.getInputKeyImages(reserveTx));
                            trade.getSelf().setReserveTxKeyImages(null);
                        }

                        throw e;
                    }

                    // reset protocol timeout
                    trade.startProtocolTimeout();

                    // update trade state
                    trade.getTaker().setReserveTxHash(reserveTx.getHash());
                    trade.getTaker().setReserveTxHex(reserveTx.getFullHex());
                    trade.getTaker().setReserveTxKey(reserveTx.getKey());
                    trade.getTaker().setReserveTxKeyImages(HavenoUtils.getInputKeyImages(reserveTx));
                }
            }

            // save process state
            processModel.setReserveTx(reserveTx); // TODO: remove this? how is it used?
            processModel.getTradeManager().requestPersistence();
            trade.addInitProgressStep();
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
