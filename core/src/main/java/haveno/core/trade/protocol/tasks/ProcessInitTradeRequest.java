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

import com.google.common.base.Charsets;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.exceptions.TradePriceOutOfToleranceException;
import haveno.core.offer.Offer;
import haveno.core.support.dispute.arbitration.arbitrator.Arbitrator;
import haveno.core.trade.ArbitratorTrade;
import haveno.core.trade.MakerTrade;
import haveno.core.trade.TakerTrade;
import haveno.core.trade.Trade;
import haveno.core.trade.messages.InitTradeRequest;
import haveno.core.trade.messages.TradeProtocolVersion;
import haveno.core.trade.protocol.TradePeer;
import lombok.extern.slf4j.Slf4j;

import java.util.Date;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static haveno.core.util.Validator.checkTradeId;
import static haveno.core.util.Validator.nonEmptyStringOf;

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

            // validate
            checkNotNull(request);
            checkTradeId(processModel.getOfferId(), request);
            checkArgument(request.getTradeAmount() > 0);
            if (trade.getAmount().compareTo(trade.getOffer().getAmount()) > 0) throw new RuntimeException("Trade amount exceeds offer amount");
            if (trade.getAmount().compareTo(trade.getOffer().getMinAmount()) < 0) throw new RuntimeException("Trade amount is less than minimum offer amount");
            if (!request.getTakerNodeAddress().equals(trade.getTaker().getNodeAddress())) throw new RuntimeException("Trade's taker node address does not match request");
            if (!request.getMakerNodeAddress().equals(trade.getMaker().getNodeAddress())) throw new RuntimeException("Trade's maker node address does not match request");
            if (!request.getOfferId().equals(offer.getId())) throw new RuntimeException("Offer id does not match request's offer id");

            // handle request as maker
            TradePeer sender;
            if (trade instanceof MakerTrade) {
                sender = trade.getTradePeer(processModel.getTempTradePeerNodeAddress());
                if (sender != trade.getTaker()) throw new RuntimeException("InitTradeRequest to maker is expected from taker");
                trade.getTaker().setPubKeyRing(request.getTakerPubKeyRing());

                // check protocol version
                if (request.getTradeProtocolVersion() != TradeProtocolVersion.MULTISIG_2_3) throw new RuntimeException("Trade protocol version is not supported"); // TODO: check if contained in supported versions

                // check trade price
                try {
                    long tradePrice = request.getTradePrice();
                    offer.verifyTradePrice(tradePrice);
                    trade.setPrice(tradePrice);
                } catch (TradePriceOutOfToleranceException e) {
                    failed(e.getMessage());
                } catch (Throwable e2) {
                    failed(e2);
                }
            }

            // handle request as arbitrator
            else if (trade instanceof ArbitratorTrade) {
                trade.getMaker().setPubKeyRing((trade.getOffer().getPubKeyRing())); // TODO: why initializing this here fields here and 
                trade.getArbitrator().setPubKeyRing(processModel.getPubKeyRing()); // TODO: why duplicating field in process model?
                if (!trade.getArbitrator().getNodeAddress().equals(request.getArbitratorNodeAddress())) throw new RuntimeException("Trade's arbitrator node address does not match request");

                // check protocol version
                if (request.getTradeProtocolVersion() != TradeProtocolVersion.MULTISIG_2_3) throw new RuntimeException("Trade protocol version is not supported"); // TODO: check consistent from maker and taker when multiple protocols supported

                // handle request from maker
                sender = trade.getTradePeer(processModel.getTempTradePeerNodeAddress());
                if (sender == trade.getMaker()) {
                    trade.getTaker().setPubKeyRing(request.getTakerPubKeyRing());

                    // check trade price
                    try {
                        long tradePrice = request.getTradePrice();
                        offer.verifyTradePrice(tradePrice);
                        trade.setPrice(tradePrice);
                    } catch (TradePriceOutOfToleranceException e) {
                        failed(e.getMessage());
                    } catch (Throwable e2) {
                        failed(e2);
                    }
                }

                // handle request from taker
                else if (sender == trade.getTaker()) {
                    if (!trade.getTaker().getPubKeyRing().equals(request.getTakerPubKeyRing())) throw new RuntimeException("Taker's pub key ring does not match request's pub key ring");
                    if (request.getTradeAmount() != trade.getAmount().longValueExact()) throw new RuntimeException("Trade amount does not match request's trade amount");
                    if (request.getTradePrice() != trade.getPrice().getValue()) throw new RuntimeException("Trade price does not match request's trade price");
                }
                
                // handle invalid sender
                else {
                    throw new RuntimeException("Sender is not trade's maker or taker");
                }
            }

            // handle request as taker
            else if (trade instanceof TakerTrade) {
                if (request.getTradeAmount() != trade.getAmount().longValueExact()) throw new RuntimeException("Trade amount does not match request's trade amount");
                if (request.getTradePrice() != trade.getPrice().getValue()) throw new RuntimeException("Trade price does not match request's trade price");
                Arbitrator arbitrator = processModel.getUser().getAcceptedArbitratorByAddress(request.getArbitratorNodeAddress());
                if (arbitrator == null) throw new RuntimeException("Arbitrator is not accepted by taker");
                trade.getArbitrator().setNodeAddress(request.getArbitratorNodeAddress());
                trade.getArbitrator().setPubKeyRing(arbitrator.getPubKeyRing());
                sender = trade.getTradePeer(processModel.getTempTradePeerNodeAddress());
                if (sender != trade.getArbitrator()) throw new RuntimeException("InitTradeRequest to taker is expected from arbitrator");
            }

            // handle invalid trade type
            else {
                throw new RuntimeException("Invalid trade type to process init trade request: " + trade.getClass().getName());
            }

            // set trading peer info
            if (trade.getMaker().getAccountId() == null) trade.getMaker().setAccountId(request.getMakerAccountId());
            else if (!trade.getMaker().getAccountId().equals(request.getMakerAccountId())) throw new RuntimeException("Maker account id is different from previous");
            if (trade.getTaker().getAccountId() == null) trade.getTaker().setAccountId(request.getTakerAccountId());
            else if (!trade.getTaker().getAccountId().equals(request.getTakerAccountId())) throw new RuntimeException("Taker account id is different from previous");
            if (trade.getMaker().getPaymentAccountId() == null) trade.getMaker().setPaymentAccountId(request.getMakerPaymentAccountId());
            else if (!trade.getMaker().getPaymentAccountId().equals(request.getMakerPaymentAccountId())) throw new RuntimeException("Maker payment account id is different from previous");
            if (trade.getTaker().getPaymentAccountId() == null) trade.getTaker().setPaymentAccountId(request.getTakerPaymentAccountId());
            else if (!trade.getTaker().getPaymentAccountId().equals(request.getTakerPaymentAccountId())) throw new RuntimeException("Taker payment account id is different from previous");
            sender.setPaymentMethodId(nonEmptyStringOf(request.getPaymentMethodId())); // TODO: move to process model?
            sender.setAccountAgeWitnessNonce(trade.getId().getBytes(Charsets.UTF_8));
            sender.setAccountAgeWitnessSignature(request.getAccountAgeWitnessSignatureOfOfferId());
            sender.setCurrentDate(request.getCurrentDate());

            // check peer's current date
            processModel.getAccountAgeWitnessService().verifyPeersCurrentDate(new Date(sender.getCurrentDate()));

            // persist trade
            trade.addInitProgressStep();
            processModel.getTradeManager().requestPersistence();
            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }
}
