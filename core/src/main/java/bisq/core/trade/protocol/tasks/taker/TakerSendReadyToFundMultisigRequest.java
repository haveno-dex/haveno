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
import bisq.core.trade.messages.MakerReadyToFundMultisigRequest;
import bisq.core.trade.protocol.tasks.TradeTask;
import bisq.network.p2p.SendDirectMessageListener;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class TakerSendReadyToFundMultisigRequest extends TradeTask {
    @SuppressWarnings({"unused"})
    public TakerSendReadyToFundMultisigRequest(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            // create message ask maker if ready
            MakerReadyToFundMultisigRequest request = new MakerReadyToFundMultisigRequest(
                    processModel.getOffer().getId(),
                    processModel.getMyNodeAddress(),
                    processModel.getPubKeyRing(),
                    UUID.randomUUID().toString(),
                    Version.getP2PMessageVersion());

            // send request to maker
            log.info("Send {} with offerId {} and uid {} to maker {} with pub key ring", request.getClass().getSimpleName(), request.getTradeId(), request.getUid(), trade.getMakerNodeAddress(), trade.getMakerPubKeyRing());
            processModel.getP2PService().sendEncryptedDirectMessage(
                    trade.getMakerNodeAddress(),
                    trade.getMakerPubKeyRing(),
                    request,
                    new SendDirectMessageListener() {
                        @Override
                        public void onArrived() {
                            log.info("{} arrived at maker: offerId={}; uid={}", request.getClass().getSimpleName(), request.getTradeId(), request.getUid());
                            complete();
                        }

                        @Override
                        public void onFault(String errorMessage) {
                            log.error("Sending {} failed: uid={}; peer={}; error={}", request.getClass().getSimpleName(), request.getUid(), trade.getMakerNodeAddress(), errorMessage);
                            appendToErrorMessage("Sending request failed: request=" + request + "\nerrorMessage=" + errorMessage);
                            failed();
                        }
                    }
            );
        } catch (Throwable t) {
            failed(t);
        }
    }
}
