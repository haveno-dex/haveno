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

import static com.google.common.base.Preconditions.checkNotNull;

import bisq.common.app.Version;
import bisq.common.crypto.Sig;
import bisq.common.taskrunner.TaskRunner;
import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.InitMultisigRequest;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.network.p2p.SendDirectMessageListener;
import com.google.common.base.Charsets;
import java.util.Date;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.MoneroWallet;

/**
 * Arbitrator sends InitMultisigRequest to maker and taker if both reserve txs received.
 */
@Slf4j
public class ArbitratorSendsInitMultisigRequestsIfFundsReserved extends TradeTask {
    
    private boolean takerAck;
    private boolean makerAck;
    
    @SuppressWarnings({"unused"})
    public ArbitratorSendsInitMultisigRequestsIfFundsReserved(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            
            // skip if arbitrator does not have maker reserve tx
            if (processModel.getMaker().getReserveTxHash() == null) {
                log.info("Arbitrator does not have maker reserve tx for offerId {}, waiting to receive before initializing multisig wallet", processModel.getOffer().getId());
                complete();
                return;
            }
            
            // create wallet for multisig
            MoneroWallet multisigWallet = processModel.getXmrWalletService().createMultisigWallet(trade.getId());
            
            // prepare multisig
            String preparedHex = multisigWallet.prepareMultisig();
            processModel.setPreparedMultisigHex(preparedHex);

            // create message to initialize multisig
            InitMultisigRequest request = new InitMultisigRequest(
                    processModel.getOffer().getId(),
                    processModel.getMyNodeAddress(),
                    processModel.getPubKeyRing(),
                    UUID.randomUUID().toString(),
                    Version.getP2PMessageVersion(),
                    new Date().getTime(),
                    preparedHex,
                    null);

            // send request to maker
            log.info("Send {} with offerId {} and uid {} to maker {}", request.getClass().getSimpleName(), request.getTradeId(), request.getUid(), trade.getMakerNodeAddress());
            processModel.getP2PService().sendEncryptedDirectMessage(
                    trade.getMakerNodeAddress(),
                    trade.getMakerPubKeyRing(),
                    request,
                    new SendDirectMessageListener() {
                        @Override
                        public void onArrived() {
                            log.info("{} arrived at arbitrator: offerId={}; uid={}", request.getClass().getSimpleName(), request.getTradeId(), request.getUid());
                            makerAck = true;
                            checkComplete();
                        }
                        @Override
                        public void onFault(String errorMessage) {
                            log.error("Sending {} failed: uid={}; peer={}; error={}", request.getClass().getSimpleName(), request.getUid(), trade.getMakerNodeAddress(), errorMessage);
                            appendToErrorMessage("Sending message failed: message=" + request + "\nerrorMessage=" + errorMessage);
                            failed();
                        }
                    }
            );

            // send request to taker
            log.info("Send {} with offerId {} and uid {} to taker {}", request.getClass().getSimpleName(), request.getTradeId(), request.getUid(), trade.getTakerNodeAddress());
            processModel.getP2PService().sendEncryptedDirectMessage(
                    trade.getTakerNodeAddress(),
                    trade.getTakerPubKeyRing(),
                    request,
                    new SendDirectMessageListener() {
                        @Override
                        public void onArrived() {
                            log.info("{} arrived at peer: offerId={}; uid={}", request.getClass().getSimpleName(), request.getTradeId(), request.getUid());
                            takerAck = true;
                            checkComplete();
                        }
                        @Override
                        public void onFault(String errorMessage) {
                            log.error("Sending {} failed: uid={}; peer={}; error={}", request.getClass().getSimpleName(), request.getUid(), trade.getTakerNodeAddress(), errorMessage);
                            appendToErrorMessage("Sending message failed: message=" + request + "\nerrorMessage=" + errorMessage);
                            failed();
                        }
                    }
            );
        } catch (Throwable t) {
            failed(t);
        }
    }
    
    private void checkComplete() {
        if (makerAck && takerAck) complete();
    }
}
