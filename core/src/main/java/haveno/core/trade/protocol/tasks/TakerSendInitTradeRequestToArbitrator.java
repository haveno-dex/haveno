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

import haveno.common.app.Version;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.handlers.ResultHandler;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.offer.availability.DisputeAgentSelection;
import haveno.core.support.dispute.arbitration.arbitrator.Arbitrator;
import haveno.core.trade.Trade;
import haveno.core.trade.messages.InitTradeRequest;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.SendDirectMessageListener;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Set;

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

            // get least used arbitrator
            Arbitrator leastUsedArbitrator = DisputeAgentSelection.getLeastUsedArbitrator(processModel.getTradeStatisticsManager(), processModel.getArbitratorManager());
            if (leastUsedArbitrator == null) {
                failed("Could not get least used arbitrator to send " + InitTradeRequest.class.getSimpleName() + " for offer " + trade.getId());
                return;
            }

            // send request to least used arbitrators until success
            sendInitTradeRequests(leastUsedArbitrator.getNodeAddress(), new HashSet<NodeAddress>(), () -> {
                trade.addInitProgressStep();
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

                // check if trade still exists
                if (!processModel.getTradeManager().hasOpenTrade(trade)) {
                    errorMessageHandler.handleErrorMessage("Trade protocol no longer exists, tradeId=" + trade.getId());
                    return;
                }
                resultHandler.handleResult();
            }

            // if unavailable, try alternative arbitrator
            @Override
            public void onFault(String errorMessage) {
                log.warn("Arbitrator unavailable: address={}, error={}", arbitratorNodeAddress, errorMessage);
                excludedArbitrators.add(arbitratorNodeAddress);

                // check if trade still exists
                if (!processModel.getTradeManager().hasOpenTrade(trade)) {
                    errorMessageHandler.handleErrorMessage("Trade protocol no longer exists, tradeId=" + trade.getId());
                    return;
                }

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
