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

package bisq.core.trade.protocol.tasks.buyer_as_taker;

import bisq.core.trade.Trade;
import bisq.core.trade.protocol.tasks.TradeTask;

import bisq.common.taskrunner.TaskRunner;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BuyerAsTakerSendsDepositTxMessage extends TradeTask {
    public BuyerAsTakerSendsDepositTxMessage(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            throw new RuntimeException("BuyerAsTakerSendsDepositTxMessage not implemented for xmr");
//            if (processModel.getDepositTx() != null) {
//                DepositTxMessage message = new DepositTxMessage(UUID.randomUUID().toString(),
//                        processModel.getOfferId(),
//                        processModel.getMyNodeAddress(),
//                        processModel.getDepositTx().bitcoinSerialize());
//
//                NodeAddress peersNodeAddress = trade.getTradingPeerNodeAddress();
//                log.info("Send {} to peer {}. tradeId={}, uid={}",
//                        message.getClass().getSimpleName(), peersNodeAddress, message.getTradeId(), message.getUid());
//                processModel.getP2PService().sendEncryptedDirectMessage(
//                        peersNodeAddress,
//                        trade.getTradingPeer().getPubKeyRing(),
//                        message,
//                        new SendDirectMessageListener() {
//                            @Override
//                            public void onArrived() {
//                                log.info("{} arrived at peer {}. tradeId={}, uid={}",
//                                        message.getClass().getSimpleName(), peersNodeAddress, message.getTradeId(), message.getUid());
//                                complete();
//                            }
//
//                            @Override
//                            public void onFault(String errorMessage) {
//                                log.error("{} failed: Peer {}. tradeId={}, uid={}, errorMessage={}",
//                                        message.getClass().getSimpleName(), peersNodeAddress, message.getTradeId(), message.getUid(), errorMessage);
//
//                                appendToErrorMessage("Sending message failed: message=" + message + "\nerrorMessage=" + errorMessage);
//                                failed();
//                            }
//                        }
//                );
//            } else {
//                log.error("processModel.getDepositTx() = " + processModel.getDepositTx());
//                failed("DepositTx is null");
//            }
        } catch (Throwable t) {
            failed(t);
        }
    }
}
