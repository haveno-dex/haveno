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

import bisq.core.trade.Trade;
import bisq.core.trade.messages.UpdateMultisigRequest;
import bisq.core.trade.messages.UpdateMultisigResponse;

import bisq.network.p2p.SendDirectMessageListener;

import bisq.common.app.Version;
import bisq.common.taskrunner.TaskRunner;

import java.util.Arrays;
import java.util.Date;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

import static bisq.core.util.Validator.checkTradeId;
import static com.google.common.base.Preconditions.checkNotNull;



import monero.wallet.MoneroWallet;

@Slf4j
public class ProcessUpdateMultisigRequest extends TradeTask {

    @SuppressWarnings({"unused"})
    public ProcessUpdateMultisigRequest(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
          runInterceptHook();
          log.debug("current trade state " + trade.getState());
          UpdateMultisigRequest request = (UpdateMultisigRequest) processModel.getTradeMessage();
          checkNotNull(request);
          checkTradeId(processModel.getOfferId(), request);
          MoneroWallet multisigWallet = processModel.getProvider().getXmrWalletService().getMultisigWallet(trade.getId());

          System.out.println("PROCESS UPDATE MULTISIG REQUEST");
          System.out.println(request);

          // check if multisig wallet needs updated
          if (!multisigWallet.isMultisigImportNeeded()) {
            log.warn("Multisig wallet does not need updated, so request is unexpected");
            failed(); // TODO (woodser): ignore instead fail
            return;
          }

          // get updated multisig hex
          multisigWallet.sync();
          String updatedMultisigHex = multisigWallet.getMultisigHex();

          // import the multisig hex
          int numOutputsSigned = multisigWallet.importMultisigHex(request.getUpdatedMultisigHex());
          System.out.println("Num outputs signed by imported multisig hex: " + numOutputsSigned);

          // close multisig wallet
          processModel.getProvider().getXmrWalletService().closeMultisigWallet(trade.getId());

          // respond with updated multisig hex
          UpdateMultisigResponse response = new UpdateMultisigResponse(
                  processModel.getOffer().getId(),
                  processModel.getMyNodeAddress(),
                  processModel.getPubKeyRing(),
                  UUID.randomUUID().toString(),
                  Version.getP2PMessageVersion(),
                  new Date().getTime(),
                  updatedMultisigHex);

          log.info("Send {} with offerId {} and uid {} to peer {}", response.getClass().getSimpleName(), response.getTradeId(), response.getUid(), trade.getTradingPeerNodeAddress());
          processModel.getP2PService().sendEncryptedDirectMessage(trade.getTradingPeerNodeAddress(), trade.getTradingPeerPubKeyRing(), response, new SendDirectMessageListener() {
            @Override
            public void onArrived() {
                log.info("{} arrived at trading peer: offerId={}; uid={}", response.getClass().getSimpleName(), response.getTradeId(), response.getUid());
                complete();
            }
            @Override
            public void onFault(String errorMessage) {
                log.error("Sending {} failed: uid={}; peer={}; error={}", response.getClass().getSimpleName(), response.getUid(), trade.getArbitratorNodeAddress(), errorMessage);
                appendToErrorMessage("Sending response failed: response=" + response + "\nerrorMessage=" + errorMessage);
                failed();
            }
          });
        } catch (Throwable t) {
            failed(t);
        }
    }
}
