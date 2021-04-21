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

package bisq.core.trade.protocol.tasks;

import bisq.common.taskrunner.TaskRunner;
import bisq.core.exceptions.TradePriceOutOfToleranceException;
import bisq.core.offer.Offer;
import bisq.core.support.dispute.mediation.mediator.Mediator;
import bisq.core.trade.ArbitratorTrade;
import bisq.core.trade.MakerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.protocol.TradingPeer;
import bisq.core.user.User;
import bisq.network.p2p.NodeAddress;
import com.google.common.base.Charsets;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Coin;

import static bisq.core.util.Validator.checkTradeId;
import static bisq.core.util.Validator.nonEmptyStringOf;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public class ProcessInitTradeRequest extends TradeTask {
    @SuppressWarnings({"unused"})
    public ProcessInitTradeRequest(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            log.debug("current trade state " + trade.getState());
            InitTradeRequest request = (InitTradeRequest) processModel.getTradeMessage();
            checkNotNull(request);
            checkTradeId(processModel.getOfferId(), request);

            System.out.println("PROCESS INIT TRADE REQUEST");
            System.out.println(request);

            User user = checkNotNull(processModel.getUser(), "User must not be null");

            // handle maker trade
            TradingPeer multisigParticipant;
            if (trade instanceof MakerTrade) {

                NodeAddress arbitratorNodeAddress = checkNotNull(request.getArbitratorNodeAddress(), "payDepositRequest.getMediatorNodeAddress() must not be null");
                Mediator mediator = checkNotNull(user.getAcceptedMediatorByAddress(arbitratorNodeAddress), "user.getAcceptedMediatorByAddress(mediatorNodeAddress) must not be null"); // TODO (woodser): switch to arbitrator?

                multisigParticipant = processModel.getTaker();
                trade.setTakerNodeAddress(request.getTakerNodeAddress());
                trade.setTakerPubKeyRing(request.getPubKeyRing());
                trade.setArbitratorNodeAddress(request.getArbitratorNodeAddress());
                trade.setArbitratorPubKeyRing(mediator.getPubKeyRing());
            }

            // handle arbitrator trade
            else if (trade instanceof ArbitratorTrade) {
                // TODO (woodser): synchronize access to setting trade state in case of concurrent requests
                if (request.getSenderNodeAddress().equals(trade.getMakerNodeAddress())) {
                    multisigParticipant = processModel.getMaker();
                    if (!trade.getMakerNodeAddress().equals(request.getMakerNodeAddress()))
                        throw new RuntimeException("Init trade requests from maker and taker do not agree");  // TODO (woodser): test when maker and taker do not agree, use proper handling
                    if (trade.getMakerPubKeyRing() == null) trade.setMakerPubKeyRing(request.getPubKeyRing());
                    else if (!trade.getMakerPubKeyRing().equals(request.getPubKeyRing()))
                        throw new RuntimeException("Init trade requests from maker and taker do not agree");  // TODO (woodser): proper handling
                } else if (request.getSenderNodeAddress().equals(trade.getTakerNodeAddress())) {
                    multisigParticipant = processModel.getTaker();
                    if (!trade.getTakerNodeAddress().equals(request.getTakerNodeAddress()))
                        throw new RuntimeException("Init trade requests from maker and taker do not agree");  // TODO (woodser): proper handling
                    if (trade.getTakerPubKeyRing() == null) trade.setTakerPubKeyRing(request.getPubKeyRing());
                    else if (!trade.getTakerPubKeyRing().equals(request.getPubKeyRing()))
                        throw new RuntimeException("Init trade requests from maker and taker do not agree");  // TODO (woodser): proper handling
                } else {
                    throw new RuntimeException("Sender is not trade's maker or taker");
                }
            } else {
                throw new RuntimeException("Invalid trade type to process init trade request: " + trade.getClass().getName());
            }

            multisigParticipant.setPaymentAccountPayload(checkNotNull(request.getPaymentAccountPayload()));
            multisigParticipant.setPayoutAddressString(nonEmptyStringOf(request.getPayoutAddressString()));
            multisigParticipant.setPubKeyRing(checkNotNull(request.getPubKeyRing()));

            multisigParticipant.setAccountId(nonEmptyStringOf(request.getAccountId()));
            //trade.setTakerFeeTxId(nonEmptyStringOf(request.getTradeFeeTxId())); // TODO (woodser): no trade fee tx yet if creating multisig first

            // Taker has to sign offerId (he cannot manipulate that - so we avoid to have a challenge protocol for passing the nonce we want to get signed)
            multisigParticipant.setAccountAgeWitnessNonce(trade.getId().getBytes(Charsets.UTF_8));
            multisigParticipant.setAccountAgeWitnessSignature(request.getAccountAgeWitnessSignatureOfOfferId());
            multisigParticipant.setCurrentDate(request.getCurrentDate());

            Offer offer = checkNotNull(trade.getOffer(), "Offer must not be null");
            try {
                long takersTradePrice = request.getTradePrice();
                offer.checkTradePriceTolerance(takersTradePrice);
                trade.setTradePrice(takersTradePrice);
            } catch (TradePriceOutOfToleranceException e) {
                failed(e.getMessage());
            } catch (Throwable e2) {
                failed(e2);
            }

            checkArgument(request.getTradeAmount() > 0);
            trade.setTradeAmount(Coin.valueOf(request.getTradeAmount()));

            processModel.getTradeManager().requestPersistence();

            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }
}
