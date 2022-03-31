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

package bisq.core.trade.protocol.tasks.taker;

import bisq.core.support.dispute.mediation.mediator.Mediator;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.protocol.tasks.TradeTask;
import bisq.network.p2p.NodeAddress;
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
            
            // send request to arbitrator
            sendInitTradeRequest(trade.getOffer().getOfferPayload().getArbitratorSigner(), new SendDirectMessageListener() {
                @Override
                public void onArrived() {
                    log.info("{} arrived at arbitrator: offerId={}", InitTradeRequest.class.getSimpleName(), trade.getId());
                }
                
                // send request to backup arbitrator if signer unavailable
                @Override
                public void onFault(String errorMessage) {
                    log.info("Sending {} to signing arbitrator {} failed, using backup arbitrator {}. error={}", InitTradeRequest.class.getSimpleName(), trade.getOffer().getOfferPayload().getArbitratorSigner(), processModel.getBackupArbitrator(), errorMessage);
                    if (processModel.getBackupArbitrator() == null) {
                        log.warn("Cannot take offer because signing arbitrator is offline and backup arbitrator is null");
                        failed();
                        return;
                    }
                    sendInitTradeRequest(processModel.getBackupArbitrator(), new SendDirectMessageListener() {
                        @Override
                        public void onArrived() {
                            log.info("{} arrived at backup arbitrator: offerId={}", InitTradeRequest.class.getSimpleName(), trade.getId());
                        }
                        @Override
                        public void onFault(String errorMessage) { // TODO (woodser): distinguish nack from offline
                            log.warn("Cannot take offer because arbitrators are unavailable: error={}.", InitTradeRequest.class.getSimpleName(), errorMessage);
                            failed();
                        }
                    });
                }
            });
            complete(); // TODO (woodser): onArrived() doesn't get called if arbitrator rejects concurrent requests. always complete before onArrived()?
        } catch (Throwable t) {
          failed(t);
        }
    }
    
    private void sendInitTradeRequest(NodeAddress arbitratorNodeAddress, SendDirectMessageListener listener) {
        
        // get registered arbitrator
        Mediator arbitrator = processModel.getUser().getAcceptedMediatorByAddress(arbitratorNodeAddress);
        if (arbitrator == null) throw new RuntimeException("Node address " + arbitratorNodeAddress + " is not a registered arbitrator");
        
        // set pub keys
        processModel.getArbitrator().setPubKeyRing(arbitrator.getPubKeyRing());
        trade.setArbitratorNodeAddress(arbitratorNodeAddress);
        trade.setArbitratorPubKeyRing(processModel.getArbitrator().getPubKeyRing());
        
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
                trade.getArbitratorNodeAddress(),
                processModel.getReserveTx().getHash(),
                processModel.getReserveTx().getFullHex(),
                processModel.getReserveTx().getKey(),
                makerRequest.getPayoutAddress(),
                processModel.getMakerSignature());

        // send request to arbitrator
        log.info("Sending {} with offerId {} and uid {} to arbitrator {} with pub key ring {}", arbitratorRequest.getClass().getSimpleName(), arbitratorRequest.getTradeId(), arbitratorRequest.getUid(), trade.getArbitratorNodeAddress(), trade.getArbitratorPubKeyRing());
        processModel.getP2PService().sendEncryptedDirectMessage(
                arbitratorNodeAddress,
                arbitrator.getPubKeyRing(),
                arbitratorRequest,
                listener
        );
    }
}
