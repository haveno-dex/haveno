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

package bisq.core.trade.protocol.tasks.taker;

import bisq.core.support.dispute.mediation.mediator.Mediator;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.protocol.tasks.TradeTask;

import bisq.network.p2p.SendDirectMessageListener;

import bisq.common.app.Version;
import bisq.common.taskrunner.TaskRunner;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TakerSendsInitTradeRequestToArbitrator extends TradeTask {

    @SuppressWarnings({"unused"})
    public TakerSendsInitTradeRequestToArbitrator(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            
            // get primary arbitrator
            Mediator arbitrator = processModel.getUser().getAcceptedMediatorByAddress(trade.getArbitratorNodeAddress());
            if (arbitrator == null) throw new RuntimeException("Cannot get arbitrator instance from node address"); // TODO (woodser): null if arbitrator goes offline or never seen?
            
            // save pub keys
            processModel.getArbitrator().setPubKeyRing(arbitrator.getPubKeyRing());
            trade.setArbitratorPubKeyRing(processModel.getArbitrator().getPubKeyRing());
            trade.setMakerPubKeyRing(trade.getTradingPeer().getPubKeyRing());
            
            // send trade request to arbitrator
            InitTradeRequest makerRequest = (InitTradeRequest) processModel.getTradeMessage();
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
                    trade.getArbitratorNodeAddress(),
                    processModel.getReserveTx().getHash(),
                    processModel.getReserveTx().getFullHex(),
                    processModel.getReserveTx().getKey(),
                    makerRequest.getPayoutAddress(),
                    processModel.getMakerSignature());

            // send request to arbitrator
            System.out.println("SENDING INIT TRADE REQUEST TO ARBITRATOR!");
            log.info("Send {} with offerId {} and uid {} to arbitrator {} with pub key ring", arbitratorRequest.getClass().getSimpleName(), arbitratorRequest.getTradeId(), arbitratorRequest.getUid(), trade.getArbitratorNodeAddress(), trade.getArbitratorPubKeyRing());
            processModel.getP2PService().sendEncryptedDirectMessage(
                    trade.getArbitratorNodeAddress(),
                    trade.getArbitratorPubKeyRing(),
                    arbitratorRequest,
                    new SendDirectMessageListener() {
                        @Override
                        public void onArrived() {
                            log.info("{} arrived at arbitrator: offerId={}; uid={}", arbitratorRequest.getClass().getSimpleName(), arbitratorRequest.getTradeId(), arbitratorRequest.getUid());
                        }
                        @Override // TODO (woodser): handle case where primary arbitrator is unavailable so use backup arbitrator, distinguish offline from bad ack
                        public void onFault(String errorMessage) {
                            log.error("Sending {} failed: uid={}; peer={}; error={}", arbitratorRequest.getClass().getSimpleName(), arbitratorRequest.getUid(), trade.getArbitratorNodeAddress(), errorMessage);
                            appendToErrorMessage("Sending message failed: message=" + arbitratorRequest + "\nerrorMessage=" + errorMessage);
                            failed();
                        }
                    }
            );
        } catch (Throwable t) {
          failed(t);
        }
    }
}
