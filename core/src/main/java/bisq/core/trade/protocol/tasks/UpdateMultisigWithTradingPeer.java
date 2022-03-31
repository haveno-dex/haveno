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

import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.TradeMessage;
import bisq.core.trade.messages.UpdateMultisigRequest;
import bisq.core.trade.messages.UpdateMultisigResponse;
import bisq.core.trade.protocol.TradeListener;

import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.SendDirectMessageListener;

import bisq.common.app.Version;
import bisq.common.taskrunner.TaskRunner;

import java.util.Arrays;
import java.util.Date;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;



import monero.wallet.MoneroWallet;

@Slf4j
public class UpdateMultisigWithTradingPeer extends TradeTask {

  private TradeListener updateMultisigResponseListener;

    @SuppressWarnings({"unused"})
    public UpdateMultisigWithTradingPeer(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            // fetch relevant trade info
            XmrWalletService walletService = processModel.getProvider().getXmrWalletService();
            MoneroWallet multisigWallet = walletService.getMultisigWallet(trade.getId()); // closed in BuyerCreateAndSignPayoutTx

            // skip if multisig wallet does not need updated
            if (!multisigWallet.isMultisigImportNeeded()) {
              log.warn("Multisig wallet does not need updated, this should not happen");
              failed();
              return;
            }

            // register listener to receive updated multisig response
            updateMultisigResponseListener = new TradeListener() {
              @Override
              public void onVerifiedTradeMessage(TradeMessage message, NodeAddress sender) {
                if (!(message instanceof UpdateMultisigResponse)) return;
                UpdateMultisigResponse response = (UpdateMultisigResponse) message;
                multisigWallet.importMultisigHex(Arrays.asList(response.getUpdatedMultisigHex()));
                multisigWallet.sync();
                trade.removeListener(updateMultisigResponseListener);
                complete();
              }
            };
            trade.addListener(updateMultisigResponseListener);

            // get updated multisig hex
            multisigWallet.sync();
            String updatedMultisigHex = multisigWallet.getMultisigHex();

            // message trading peer with updated multisig hex
            UpdateMultisigRequest message = new UpdateMultisigRequest(
                    processModel.getOffer().getId(),
                    processModel.getMyNodeAddress(),
                    processModel.getPubKeyRing(),
                    UUID.randomUUID().toString(),
                    Version.getP2PMessageVersion(),
                    new Date().getTime(),
                    updatedMultisigHex);

            System.out.println("Sending message: " + message);

            // TODO (woodser): trade.getTradingPeerNodeAddress() and/or trade.getTradingPeerPubKeyRing() are null on restart of application, so cannot send payment to complete trade
            log.info("Send {} with offerId {} and uid {} to peer {}", message.getClass().getSimpleName(), message.getTradeId(), message.getUid(), trade.getTradingPeerNodeAddress());
            processModel.getP2PService().sendEncryptedDirectMessage(trade.getTradingPeerNodeAddress(), trade.getTradingPeerPubKeyRing(), message, new SendDirectMessageListener() {
              @Override
              public void onArrived() {
                  log.info("{} arrived at trading peer: offerId={}; uid={}", message.getClass().getSimpleName(), message.getTradeId(), message.getUid());
              }
              @Override
              public void onFault(String errorMessage) {
                  log.error("Sending {} failed: uid={}; peer={}; error={}", message.getClass().getSimpleName(), message.getUid(), trade.getArbitratorNodeAddress(), errorMessage);
                  appendToErrorMessage("Sending message failed: message=" + message + "\nerrorMessage=" + errorMessage);
                  failed();
              }
            });
        } catch (Throwable t) {
            failed(t);
        }
    }
}
