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

package bisq.core.trade.protocol.tasks;

import bisq.core.exceptions.TradePriceOutOfToleranceException;
import bisq.core.offer.Offer;
import bisq.core.trade.ArbitratorTrade;
import bisq.core.trade.MakerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.HavenoUtils;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.protocol.TradingPeer;

import bisq.common.taskrunner.TaskRunner;
import org.bitcoinj.core.Coin;

import com.google.common.base.Charsets;

import lombok.extern.slf4j.Slf4j;

import static bisq.core.util.Validator.checkTradeId;
import static bisq.core.util.Validator.nonEmptyStringOf;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Date;

@Slf4j
public class ProcessInitTradeRequest extends TradeTask {
    @SuppressWarnings({"unused"})
    public ProcessInitTradeRequest(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    // TODO (woodser): synchronize access to setting trade state in case of concurrent requests
    @Override
    protected void run() {
        try {
            runInterceptHook();
            Offer offer = checkNotNull(trade.getOffer(), "Offer must not be null");
            InitTradeRequest request = (InitTradeRequest) processModel.getTradeMessage();
            checkNotNull(request);
            checkTradeId(processModel.getOfferId(), request);

            // handle request as arbitrator
            TradingPeer multisigParticipant;
            if (trade instanceof ArbitratorTrade) {
                trade.getMaker().setPubKeyRing((trade.getOffer().getPubKeyRing()));
                trade.getArbitrator().setPubKeyRing(processModel.getPubKeyRing());
                processModel.getArbitrator().setPubKeyRing(processModel.getPubKeyRing()); // TODO (woodser): why duplicating field in process model
                
                // handle request from taker
                if (request.getSenderNodeAddress().equals(request.getTakerNodeAddress())) {
                    multisigParticipant = processModel.getTaker();
                    if (!trade.getTaker().getNodeAddress().equals(request.getTakerNodeAddress())) throw new RuntimeException("Init trade requests from maker and taker do not agree");
                    if (trade.getTaker().getPubKeyRing() != null) throw new RuntimeException("Pub key ring should not be initialized before processing InitTradeRequest");
                    trade.getTaker().setPubKeyRing(request.getPubKeyRing());
                    if (!HavenoUtils.isMakerSignatureValid(request, request.getMakerSignature(), offer.getPubKeyRing())) throw new RuntimeException("Maker signature is invalid for the trade request"); // verify maker signature

                    // check trade price
                    try {
                        long tradePrice = request.getTradePrice();
                        offer.verifyTakersTradePrice(tradePrice);
                        trade.setPrice(tradePrice);
                    } catch (TradePriceOutOfToleranceException e) {
                        failed(e.getMessage());
                    } catch (Throwable e2) {
                        failed(e2);
                    }
                }
                
                // handle request from maker
                else if (request.getSenderNodeAddress().equals(request.getMakerNodeAddress())) {
                    multisigParticipant = processModel.getMaker();
                    if (!trade.getMaker().getNodeAddress().equals(request.getMakerNodeAddress())) throw new RuntimeException("Init trade requests from maker and taker do not agree"); // TODO (woodser): test when maker and taker do not agree, use proper handling, uninitialize trade for other takers
                    if (trade.getMaker().getPubKeyRing() == null) trade.getMaker().setPubKeyRing(request.getPubKeyRing());
                    else if (!trade.getMaker().getPubKeyRing().equals(request.getPubKeyRing())) throw new RuntimeException("Init trade requests from maker and taker do not agree");  // TODO (woodser): proper handling
                    trade.getMaker().setPubKeyRing(request.getPubKeyRing());
                    if (trade.getPrice().getValue() != request.getTradePrice()) throw new RuntimeException("Maker and taker price do not agree");
                } else {
                    throw new RuntimeException("Sender is not trade's maker or taker");
                }
            }
            
            // handle request as maker
            else if (trade instanceof MakerTrade) {
                multisigParticipant = processModel.getTaker();
                trade.getTaker().setNodeAddress(request.getSenderNodeAddress()); // arbitrator sends maker InitTradeRequest with taker's node address and pub key ring
                trade.getTaker().setPubKeyRing(request.getPubKeyRing());

                // check trade price
                try {
                    long tradePrice = request.getTradePrice();
                    offer.verifyTakersTradePrice(tradePrice);
                    trade.setPrice(tradePrice);
                } catch (TradePriceOutOfToleranceException e) {
                    failed(e.getMessage());
                } catch (Throwable e2) {
                    failed(e2);
                }
            }
            
            // handle invalid trade type
            else {
                throw new RuntimeException("Invalid trade type to process init trade request: " + trade.getClass().getName());
            }

            // set trading peer info
            if (multisigParticipant.getPaymentAccountId() == null) multisigParticipant.setPaymentAccountId(request.getPaymentAccountId());
            else if (multisigParticipant.getPaymentAccountId() != request.getPaymentAccountId()) throw new RuntimeException("Payment account id is different from previous");
            multisigParticipant.setPubKeyRing(checkNotNull(request.getPubKeyRing()));
            multisigParticipant.setAccountId(nonEmptyStringOf(request.getAccountId()));
            multisigParticipant.setPaymentMethodId(nonEmptyStringOf(request.getPaymentMethodId()));
            multisigParticipant.setAccountAgeWitnessNonce(trade.getId().getBytes(Charsets.UTF_8));
            multisigParticipant.setAccountAgeWitnessSignature(request.getAccountAgeWitnessSignatureOfOfferId());
            multisigParticipant.setCurrentDate(request.getCurrentDate());

            // check peer's current date
            processModel.getAccountAgeWitnessService().verifyPeersCurrentDate(new Date(multisigParticipant.getCurrentDate()));

            // check trade amount
            checkArgument(request.getTradeAmount() > 0);
            trade.setAmount(Coin.valueOf(request.getTradeAmount()));

            // persist trade
            processModel.getTradeManager().requestPersistence();
            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }
}
