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

import bisq.core.offer.availability.DisputeAgentSelection;
import bisq.core.support.dispute.arbitration.arbitrator.Arbitrator;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.SendDirectMessageListener;
import java.util.HashSet;
import java.util.Set;
import bisq.common.app.Version;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.ResultHandler;
import bisq.common.taskrunner.TaskRunner;


import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TakerSendInitTradeRequestToArbitrator extends TradeTask {

    @SuppressWarnings({"unused"})
    public TakerSendInitTradeRequestToArbitrator(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            // send request to signing arbitrator then least used arbitrators until success
            sendInitTradeRequests(trade.getOffer().getOfferPayload().getArbitratorSigner(), new HashSet<NodeAddress>(), () -> {
                complete();
            }, (errorMessage) -> {
                log.warn("Cannot initialize trade with arbitrators: " + errorMessage);
                failed(errorMessage);
            });
        } catch (Throwable t) {
          failed(t);
        }
    }

    private void sendInitTradeRequests(NodeAddress arbitratorNodeAddress, Set<NodeAddress> excludedArbitrators, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        sendInitTradeRequest(arbitratorNodeAddress, new SendDirectMessageListener() {
            @Override
            public void onArrived() {
                log.info("{} arrived at arbitrator: offerId={}", InitTradeRequest.class.getSimpleName(), trade.getId());
                resultHandler.handleResult();
            }

            // if unavailable, try alternative arbitrator
            @Override
            public void onFault(String errorMessage) {
                log.warn("Arbitrator {} unavailable: {}", arbitratorNodeAddress, errorMessage);
                excludedArbitrators.add(arbitratorNodeAddress);
                Arbitrator altArbitrator = DisputeAgentSelection.getLeastUsedArbitrator(processModel.getTradeStatisticsManager(), processModel.getArbitratorManager(), excludedArbitrators);
                if (altArbitrator == null) {
                    errorMessageHandler.handleErrorMessage("Cannot take offer because no arbitrators are available");
                    return;
                }
                log.info("Using alternative arbitrator {}", altArbitrator.getNodeAddress());
                sendInitTradeRequests(altArbitrator.getNodeAddress(), excludedArbitrators, resultHandler, errorMessageHandler);
            }
        });
    }

    private void sendInitTradeRequest(NodeAddress arbitratorNodeAddress, SendDirectMessageListener listener) {
        
        // get registered arbitrator
        Arbitrator arbitrator = processModel.getUser().getAcceptedArbitratorByAddress(arbitratorNodeAddress);
        if (arbitrator == null) throw new RuntimeException("Node address " + arbitratorNodeAddress + " is not a registered arbitrator");

        // set pub keys
        processModel.getArbitrator().setPubKeyRing(arbitrator.getPubKeyRing());
        trade.getArbitrator().setNodeAddress(arbitratorNodeAddress);
        trade.getArbitrator().setPubKeyRing(processModel.getArbitrator().getPubKeyRing());

        // create request to arbitrator
        InitTradeRequest makerRequest = (InitTradeRequest) processModel.getTradeMessage(); // taker's InitTradeRequest to maker
        InitTradeRequest arbitratorRequest = new InitTradeRequest(
                makerRequest.getTradeId(),
                makerRequest.getSenderNodeAddress(),
                makerRequest.getPubKeyRing(),
                makerRequest.getTradeAmount(),
                makerRequest.getTradePrice(),
                makerRequest.getTradeFee(),
                makerRequest.getAccountId(),
                makerRequest.getPaymentAccountId(),
                makerRequest.getPaymentMethodId(),
                makerRequest.getUid(),
                Version.getP2PMessageVersion(),
                makerRequest.getAccountAgeWitnessSignatureOfOfferId(),
                makerRequest.getCurrentDate(),
                makerRequest.getMakerNodeAddress(),
                makerRequest.getTakerNodeAddress(),
                trade.getArbitrator().getNodeAddress(),
                processModel.getReserveTx().getHash(),
                processModel.getReserveTx().getFullHex(),
                processModel.getReserveTx().getKey(),
                makerRequest.getPayoutAddress(),
                processModel.getMakerSignature());

        // send request to arbitrator
        log.info("Sending {} with offerId {} and uid {} to arbitrator {}", arbitratorRequest.getClass().getSimpleName(), arbitratorRequest.getTradeId(), arbitratorRequest.getUid(), trade.getArbitrator().getNodeAddress());
        processModel.getP2PService().sendEncryptedDirectMessage(
                arbitratorNodeAddress,
                arbitrator.getPubKeyRing(),
                arbitratorRequest,
                listener
        );
    }
}
