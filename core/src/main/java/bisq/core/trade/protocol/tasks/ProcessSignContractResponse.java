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
import bisq.common.crypto.PubKeyRing;
import bisq.common.crypto.Sig;
import bisq.common.taskrunner.TaskRunner;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.DepositRequest;
import bisq.core.trade.messages.SignContractResponse;
import bisq.core.trade.protocol.TradingPeer;
import bisq.network.p2p.SendDirectMessageListener;
import java.util.Date;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProcessSignContractResponse extends TradeTask {
    
    @SuppressWarnings({"unused"})
    public ProcessSignContractResponse(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
          runInterceptHook();

          // get contract and signature
          String contractAsJson = trade.getContractAsJson();
          SignContractResponse response = (SignContractResponse) processModel.getTradeMessage(); // TODO (woodser): verify response
          String signature = response.getContractSignature();

          // get peer info
          // TODO (woodser): make these utilities / refactor model
          PubKeyRing peerPubKeyRing;
          TradingPeer peer = trade.getTradingPeer(response.getSenderNodeAddress());
          if (peer == processModel.getArbitrator()) peerPubKeyRing = trade.getArbitratorPubKeyRing();
          else if (peer == processModel.getMaker()) peerPubKeyRing = trade.getMakerPubKeyRing();
          else if (peer == processModel.getTaker()) peerPubKeyRing = trade.getTakerPubKeyRing();
          else throw new RuntimeException(response.getClass().getSimpleName() + " is not from maker, taker, or arbitrator");

          // verify signature
          // TODO (woodser): transfer contract for convenient comparison?
          if (!Sig.verify(peerPubKeyRing.getSignaturePubKey(), contractAsJson, signature)) throw new RuntimeException("Peer's contract signature is invalid");

          // set peer's signature
          peer.setContractSignature(signature);

          // send deposit request when all contract signatures received
          if (processModel.getArbitrator().getContractSignature() != null && processModel.getMaker().getContractSignature() != null && processModel.getTaker().getContractSignature() != null) {

              // start listening for deposit txs
              trade.listenForDepositTxs();

              // create request for arbitrator to deposit funds to multisig
              DepositRequest request = new DepositRequest(
                      trade.getOffer().getId(),
                      processModel.getMyNodeAddress(),
                      processModel.getPubKeyRing(),
                      UUID.randomUUID().toString(),
                      Version.getP2PMessageVersion(),
                      new Date().getTime(),
                      trade.getSelf().getContractSignature(),
                      processModel.getDepositTxXmr().getFullHex(),
                      processModel.getDepositTxXmr().getKey());

              // send request to arbitrator
              processModel.getP2PService().sendEncryptedDirectMessage(trade.getArbitratorNodeAddress(), trade.getArbitratorPubKeyRing(), request, new SendDirectMessageListener() {
                  @Override
                  public void onArrived() {
                      log.info("{} arrived: trading peer={}; offerId={}; uid={}", request.getClass().getSimpleName(), trade.getArbitratorNodeAddress(), trade.getId());
                      processModel.getTradeManager().requestPersistence();
                      complete();
                  }
                  @Override
                  public void onFault(String errorMessage) {
                      log.error("Sending {} failed: uid={}; peer={}; error={}", request.getClass().getSimpleName(), trade.getArbitratorNodeAddress(), trade.getId(), errorMessage);
                      appendToErrorMessage("Sending message failed: message=" + request + "\nerrorMessage=" + errorMessage);
                      failed();
                  }
              });
          } else {
              complete(); // does not yet have needed signatures
          }
        } catch (Throwable t) {
          failed(t);
        }
    }
}
