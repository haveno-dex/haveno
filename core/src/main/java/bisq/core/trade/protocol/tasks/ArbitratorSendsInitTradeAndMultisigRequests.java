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


import bisq.common.app.Version;
import bisq.common.crypto.Sig;
import bisq.common.taskrunner.TaskRunner;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.InitMultisigRequest;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.protocol.TradeListener;
import bisq.network.p2p.AckMessage;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.P2PService;
import bisq.network.p2p.SendDirectMessageListener;
import com.google.common.base.Charsets;
import java.util.Date;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.MoneroWallet;

/**
 * Arbitrator sends InitTradeRequest to maker after receiving InitTradeRequest
 * from taker and verifying taker reserve tx.
 * 
 * Arbitrator sends InitMultisigRequests after the maker acks.
 */
@Slf4j
public class ArbitratorSendsInitTradeAndMultisigRequests extends TradeTask {
    
    @SuppressWarnings({"unused"})
    public ArbitratorSendsInitTradeAndMultisigRequests(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            
            // skip if request not from taker
            InitTradeRequest request = (InitTradeRequest) processModel.getTradeMessage();
            if (!request.getSenderNodeAddress().equals(trade.getTakerNodeAddress())) {
                complete();
                return;
            }
            
            // arbitrator signs offer id as nonce to avoid challenge protocol
            byte[] sig = Sig.sign(processModel.getKeyRing().getSignatureKeyPair().getPrivate(), processModel.getOfferId().getBytes(Charsets.UTF_8));
            
            // save pub keys
            processModel.getArbitrator().setPubKeyRing(processModel.getPubKeyRing()); // TODO (woodser): why duplicating field in process model
            trade.setArbitratorPubKeyRing(processModel.getPubKeyRing());
            trade.setMakerPubKeyRing(trade.getOffer().getPubKeyRing());
            trade.setTakerPubKeyRing(request.getPubKeyRing());
            
            // create request to initialize trade with maker
            InitTradeRequest makerRequest = new InitTradeRequest(
                    processModel.getOfferId(),
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
            
            // send init multisig requests on ack // TODO (woodser): only send InitMultisigRequests if arbitrator has maker reserve tx, else wait for that
            TradeListener listener = new TradeListener() {
                @Override
                public void onAckMessage(AckMessage ackMessage, NodeAddress sender) {
                    if (sender.equals(trade.getMakerNodeAddress()) &&
                            ackMessage.getSourceMsgClassName().equals(InitTradeRequest.class.getSimpleName()) &&
                            ackMessage.getSourceUid().equals(makerRequest.getUid())) {
                        trade.removeListener(this);
                        if (ackMessage.isSuccess()) sendInitMultisigRequests();
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
                            complete();
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
    
    private void sendInitMultisigRequests() {
        
        // ensure arbitrator has maker's reserve tx
        if (processModel.getMaker().getReserveTxHash() == null) {
            log.warn("Arbitrator {} does not have maker's reserve tx after initializing trade", P2PService.getMyNodeAddress());
            failed();
            return;
        }
        
        // create wallet for multisig
        MoneroWallet multisigWallet = processModel.getXmrWalletService().createMultisigWallet(trade.getId());
        
        // prepare multisig
        String preparedHex = multisigWallet.prepareMultisig();
        processModel.setPreparedMultisigHex(preparedHex);

        // create message to initialize multisig
        InitMultisigRequest initMultisigRequest = new InitMultisigRequest(
                processModel.getOffer().getId(),
                processModel.getMyNodeAddress(),
                processModel.getPubKeyRing(),
                UUID.randomUUID().toString(),
                Version.getP2PMessageVersion(),
                new Date().getTime(),
                preparedHex,
                null);

        // send request to maker
        log.info("Send {} with offerId {} and uid {} to maker {}", initMultisigRequest.getClass().getSimpleName(), initMultisigRequest.getTradeId(), initMultisigRequest.getUid(), trade.getMakerNodeAddress());
        processModel.getP2PService().sendEncryptedDirectMessage(
                trade.getMakerNodeAddress(),
                trade.getMakerPubKeyRing(),
                initMultisigRequest,
                new SendDirectMessageListener() {
                    @Override
                    public void onArrived() {
                        log.info("{} arrived at arbitrator: offerId={}; uid={}", initMultisigRequest.getClass().getSimpleName(), initMultisigRequest.getTradeId(), initMultisigRequest.getUid());
                    }
                    @Override
                    public void onFault(String errorMessage) {
                        log.error("Sending {} failed: uid={}; peer={}; error={}", initMultisigRequest.getClass().getSimpleName(), initMultisigRequest.getUid(), trade.getMakerNodeAddress(), errorMessage);
                        appendToErrorMessage("Sending message failed: message=" + initMultisigRequest + "\nerrorMessage=" + errorMessage);
                        failed();
                    }
                }
        );

        // send request to taker
        log.info("Send {} with offerId {} and uid {} to taker {}", initMultisigRequest.getClass().getSimpleName(), initMultisigRequest.getTradeId(), initMultisigRequest.getUid(), trade.getTakerNodeAddress());
        processModel.getP2PService().sendEncryptedDirectMessage(
                trade.getTakerNodeAddress(),
                trade.getTakerPubKeyRing(),
                initMultisigRequest,
                new SendDirectMessageListener() {
                    @Override
                    public void onArrived() {
                        log.info("{} arrived at peer: offerId={}; uid={}", initMultisigRequest.getClass().getSimpleName(), initMultisigRequest.getTradeId(), initMultisigRequest.getUid());
                    }
                    @Override
                    public void onFault(String errorMessage) {
                        log.error("Sending {} failed: uid={}; peer={}; error={}", initMultisigRequest.getClass().getSimpleName(), initMultisigRequest.getUid(), trade.getTakerNodeAddress(), errorMessage);
                        appendToErrorMessage("Sending message failed: message=" + initMultisigRequest + "\nerrorMessage=" + errorMessage);
                        failed();
                    }
                }
        );
    }
}
