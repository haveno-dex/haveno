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


import bisq.common.app.Version;
import bisq.common.crypto.Sig;
import bisq.common.taskrunner.TaskRunner;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.protocol.TradeListener;
import bisq.network.p2p.AckMessage;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.SendDirectMessageListener;
import com.google.common.base.Charsets;
import java.util.Date;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * Arbitrator sends InitTradeRequest to maker after receiving InitTradeRequest
 * from taker and verifying taker reserve tx.
 */
@Slf4j
public class ArbitratorSendsInitTradeRequestToMakerIfFromTaker extends TradeTask {
    @SuppressWarnings({"unused"})
    public ArbitratorSendsInitTradeRequestToMakerIfFromTaker(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            
            // collect fields for request
            String offerId = processModel.getOffer().getId();
            InitTradeRequest request = (InitTradeRequest) processModel.getTradeMessage();
            
            // arbitrator signs offer id as nonce to avoid challenge protocol
            byte[] sig = Sig.sign(processModel.getKeyRing().getSignatureKeyPair().getPrivate(), offerId.getBytes(Charsets.UTF_8));
            
            // save pub keys
            processModel.getArbitrator().setPubKeyRing(processModel.getPubKeyRing()); // TODO (woodser): why duplicating field in process model
            trade.setArbitratorPubKeyRing(processModel.getPubKeyRing());
            trade.setMakerPubKeyRing(trade.getOffer().getPubKeyRing());
            trade.setTakerPubKeyRing(request.getPubKeyRing());
            
            // create request to initialize trade with maker
            InitTradeRequest makerRequest = new InitTradeRequest(
                    offerId,
                    request.getSenderNodeAddress(),
                    request.getPubKeyRing(),
                    trade.getTradeAmount().value,
                    trade.getTradePrice().getValue(),
                    trade.getTakerFee().getValue(),
                    request.getAccountId(),
                    request.getPaymentAccountId(),
                    request.getPaymentMethodId(),
                    UUID.randomUUID().toString(),
                    Version.getP2PMessageVersion(),
                    sig,
                    new Date().getTime(),
                    trade.getMakerNodeAddress(),
                    trade.getTakerNodeAddress(),
                    trade.getArbitratorNodeAddress(),
                    null,
                    null, // do not include taker's reserve tx
                    null,
                    null,
                    null);
            
            // listen for maker to ack InitTradeRequest
            TradeListener listener = new TradeListener() {
                @Override
                public void onAckMessage(AckMessage ackMessage, NodeAddress sender) {
                    if (sender.equals(trade.getMakerNodeAddress()) && ackMessage.getSourceMsgClassName().equals(InitTradeRequest.class.getSimpleName())) {
                        trade.removeListener(this);
                        if (ackMessage.isSuccess()) complete();
                        else failed("Received unsuccessful ack for InitTradeRequest from maker"); // TODO (woodser): maker should not do this, penalize them by broadcasting reserve tx?
                    }
                }
            };
            trade.addListener(listener);

            // send request to maker
            log.info("Send {} with offerId {} and uid {} to maker {} with pub key ring", makerRequest.getClass().getSimpleName(), makerRequest.getTradeId(), makerRequest.getUid(), trade.getMakerNodeAddress(), trade.getMakerPubKeyRing());
            processModel.getP2PService().sendEncryptedDirectMessage(
                    trade.getMakerNodeAddress(), // TODO (woodser): maker's address might be different from original owner address if they disconnect and reconnect, need to validate and update address when requests received
                    trade.getMakerPubKeyRing(),
                    makerRequest,
                    new SendDirectMessageListener() {
                        @Override
                        public void onArrived() {
                            log.info("{} arrived at maker: offerId={}; uid={}", makerRequest.getClass().getSimpleName(), makerRequest.getTradeId(), makerRequest.getUid());
                        }
                        @Override
                        public void onFault(String errorMessage) {
                            log.error("Sending {} failed: uid={}; peer={}; error={}", makerRequest.getClass().getSimpleName(), makerRequest.getUid(), trade.getArbitratorNodeAddress(), errorMessage);
                            appendToErrorMessage("Sending message failed: message=" + makerRequest + "\nerrorMessage=" + errorMessage);
                            trade.removeListener(listener);
                            failed();
                        }
                    }
            );
        } catch (Throwable t) {
            failed(t);
        }
    }
}
