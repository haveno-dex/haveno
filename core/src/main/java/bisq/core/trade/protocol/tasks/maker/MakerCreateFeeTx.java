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

package bisq.core.trade.protocol.tasks.maker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bisq.common.UserThread;
import bisq.common.taskrunner.Task;
import bisq.common.taskrunner.TaskRunner;
import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.btc.wallet.TradeWalletService;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.offer.Offer;
import bisq.core.offer.placeoffer.PlaceOfferModel;
import monero.wallet.model.MoneroTxWallet;

public class MakerCreateFeeTx extends Task<PlaceOfferModel> {
    private static final Logger log = LoggerFactory.getLogger(MakerCreateFeeTx.class);

    @SuppressWarnings({"unused"})
    public MakerCreateFeeTx(TaskRunner taskHandler, PlaceOfferModel model) {
        super(taskHandler, model);
    }

    @Override
    protected void run() {
        Offer offer = model.getOffer();

        try {
            runInterceptHook();

            String id = offer.getId();
            XmrWalletService walletService = model.getXmrWalletService();

            String reservedForTradeAddress = walletService.getOrCreateAddressEntry(id, XmrAddressEntry.Context.RESERVED_FOR_TRADE).getAddressString();

            TradeWalletService tradeWalletService = model.getTradeWalletService();
            String feeReceiver = "52FnB7ABUrKJzVQRpbMNrqDFWbcKLjFUq8Rgek7jZEuB6WE2ZggXaTf4FK6H8gQymvSrruHHrEuKhMN3qTMiBYzREKsmRKM"; // TODO (woodser): don't hardcode

            if (offer.isCurrencyForMakerFeeBtc()) {
                try {
                  MoneroTxWallet tx = tradeWalletService.createXmrTradingFeeTx(
                          reservedForTradeAddress,
                          model.getReservedFundsForOffer(),
                          offer.getMakerFee(),
                          offer.getTxFee(),
                          feeReceiver,
                          true);
                  System.out.println("SUCCESS CREATING XMR TRADING FEE TX!");
                  System.out.println(tx);

                  // we delay one render frame to be sure we don't get called before the method call has
                  // returned (tradeFeeTx would be null in that case)
                  UserThread.execute(() -> {
                      if (!completed) {
                          offer.setOfferFeePaymentTxId(tx.getHash());
                          model.setXmrTransaction(tx);
                          walletService.swapTradeEntryToAvailableEntry(id, XmrAddressEntry.Context.OFFER_FUNDING);

                          model.getOffer().setState(Offer.State.OFFER_FEE_PAID);

                          complete();
                      } else {
                          log.warn("We got the onSuccess callback called after the timeout has been triggered a complete().");
                      }
                  });
                } catch (Exception e) {
                  System.out.println("FAILURE CREATING XMR TRADING FEE TX!");
                  if (!completed) {
                      failed(e);
                  } else {
                      log.warn("We got the onFailure callback called after the timeout has been triggered a complete().");
                  }
                }

//                tradeWalletService.createBtcTradingFeeTx(
//                        fundingAddress,
//                        reservedForTradeAddress,
//                        changeAddress,
//                        model.getReservedFundsForOffer(),
//                        model.isUseSavingsWallet(),
//                        offer.getMakerFee(),
//                        offer.getTxFee(),
//                        feeReceiver,
//                        true,
//                        new TxBroadcaster.Callback() {
//                            @Override
//                            public void onSuccess(Transaction transaction) {
//                                // we delay one render frame to be sure we don't get called before the method call has
//                                // returned (tradeFeeTx would be null in that case)
//                                UserThread.execute(() -> {
//                                    if (!completed) {
//                                        offer.setOfferFeePaymentTxId(transaction.getHashAsString());
//                                        model.setTransaction(transaction);
//                                        walletService.swapTradeEntryToAvailableEntry(id, AddressEntry.Context.OFFER_FUNDING);
//
//                                        model.getOffer().setState(Offer.State.OFFER_FEE_PAID);
//
//                                        complete();
//                                    } else {
//                                        log.warn("We got the onSuccess callback called after the timeout has been triggered a complete().");
//                                    }
//                                });
//                            }
//
//                            @Override
//                            public void onFailure(TxBroadcastException exception) {
//                                if (!completed) {
//                                    failed(exception);
//                                } else {
//                                    log.warn("We got the onFailure callback called after the timeout has been triggered a complete().");
//                                }
//                            }
//                        });
            }
        } catch (Throwable t) {
            offer.setErrorMessage("An error occurred.\n" +
                    "Error message:\n"
                    + t.getMessage());
            failed(t);
        }
    }
}
