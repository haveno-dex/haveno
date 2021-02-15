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

import bisq.common.app.Version;
import bisq.common.taskrunner.TaskRunner;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.InitMultisigMessage;
import bisq.core.trade.protocol.tasks.TradeTask;
import bisq.network.p2p.SendDirectMessageListener;
import java.util.Date;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.MoneroWallet;

@Slf4j
public class TakerSendInitMultisigMessages extends TradeTask {
  
    private boolean makerAck;
    private boolean arbitratorAck;

    @SuppressWarnings({"unused"})
    public TakerSendInitMultisigMessages(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            
            // create wallet for multisig
            // TODO (woodser): assert that wallet does not already exist
            MoneroWallet multisigWallet = processModel.getProvider().getXmrWalletService().getOrCreateMultisigWallet(processModel.getTrade().getId());
            
            // prepare multisig
            String preparedHex = multisigWallet.prepareMultisig();
            processModel.setPreparedMultisigHex(preparedHex);
            System.out.println("Prepared multisig hex: " + preparedHex);
            
            // create message to initialize trade
            InitMultisigMessage message = new InitMultisigMessage(
                    processModel.getOffer().getId(),
                    processModel.getMyNodeAddress(),
                    processModel.getPubKeyRing(),
                    UUID.randomUUID().toString(),
                    Version.getP2PMessageVersion(),
                    new Date().getTime(),
                    preparedHex,
                    null);
            
            // send request to arbitrator
            log.info("Send {} with offerId {} and uid {} to arbitrator {} with pub key ring", message.getClass().getSimpleName(), message.getTradeId(), message.getUid(), trade.getArbitratorNodeAddress(), trade.getArbitratorPubKeyRing());
            processModel.getP2PService().sendEncryptedDirectMessage(
                    trade.getArbitratorNodeAddress(),
                    trade.getArbitratorPubKeyRing(),
                    message,
                    new SendDirectMessageListener() {
                        @Override
                        public void onArrived() {
                            log.info("{} arrived at arbitrator: offerId={}; uid={}", message.getClass().getSimpleName(), message.getTradeId(), message.getUid());
                            arbitratorAck = true;
                            checkComplete();
                        }
                        @Override
                        public void onFault(String errorMessage) {
                            log.error("Sending {} failed: uid={}; peer={}; error={}", message.getClass().getSimpleName(), message.getUid(), trade.getArbitratorNodeAddress(), errorMessage);
                            appendToErrorMessage("Sending message failed: message=" + message + "\nerrorMessage=" + errorMessage);
                            failed();
                        }
                    }
            );
            
            // send request to maker
            log.info("Send {} with offerId {} and uid {} to peer {}", message.getClass().getSimpleName(), message.getTradeId(), message.getUid(), trade.getMakerNodeAddress());
            processModel.getP2PService().sendEncryptedDirectMessage(
                    trade.getMakerNodeAddress(),
                    trade.getMakerPubKeyRing(),
                    message,
                    new SendDirectMessageListener() {
                        @Override
                        public void onArrived() {
                            log.info("{} arrived at peer: offerId={}; uid={}", message.getClass().getSimpleName(), message.getTradeId(), message.getUid());
                            makerAck = true;
                            checkComplete();
                        }
                        @Override
                        public void onFault(String errorMessage) {
                            log.error("Sending {} failed: uid={}; peer={}; error={}", message.getClass().getSimpleName(), message.getUid(), trade.getMakerNodeAddress(), errorMessage);
                            appendToErrorMessage("Sending message failed: message=" + message + "\nerrorMessage=" + errorMessage);
                            failed();
                        }
                    }
            );
        } catch (Throwable t) {
          failed(t);
        }
    }
    
    private void checkComplete() {
      if (makerAck && arbitratorAck) complete();
    }
}
