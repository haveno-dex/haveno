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
import bisq.common.taskrunner.TaskRunner;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.PaymentAccountKeyResponse;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.SendDirectMessageListener;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ArbitratorProcessPaymentAccountKeyRequest extends TradeTask {

    @SuppressWarnings({"unused"})
    public ArbitratorProcessPaymentAccountKeyRequest(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
          runInterceptHook();

          // ensure deposit txs confirmed
          trade.listenForDepositTxs();
          if (trade.getPhase().ordinal() < Trade.Phase.DEPOSITS_CONFIRMED.ordinal()) {
              throw new RuntimeException("Arbitrator refusing payment account key request for trade " + trade.getId() + " because the deposit txs have not confirmed");
          }

          // create response for buyer with key to decrypt seller's payment account payload
          PaymentAccountKeyResponse response = new PaymentAccountKeyResponse(
                  trade.getId(),
                  processModel.getMyNodeAddress(),
                  processModel.getPubKeyRing(),
                  UUID.randomUUID().toString(),
                  Version.getP2PMessageVersion(),
                  trade.getSeller().getPaymentAccountKey(),
                  null
          );

          // send response to buyer
          boolean isMakerBuyer = trade.getOffer().isBuyOffer();
          NodeAddress buyerAddress = isMakerBuyer ? trade.getMakerNodeAddress() : trade.getTakerNodeAddress(); // TODO: trade.getBuyer().getNodeAddress()
          PubKeyRing buyerPubKeyRing = isMakerBuyer ? trade.getMakerPubKeyRing() : trade.getTakerPubKeyRing();
          log.info("Arbitrator sending PaymentAccountKeyResponse to buyer={}; offerId={}", buyerAddress, trade.getId());
          processModel.getP2PService().sendEncryptedDirectMessage(buyerAddress, buyerPubKeyRing, response, new SendDirectMessageListener() {
              @Override
              public void onArrived() {
                  log.info("{} arrived: trading peer={}; offerId={}; uid={}", response.getClass().getSimpleName(), buyerAddress, trade.getId());
                  complete();
              }
              @Override
              public void onFault(String errorMessage) {
                  log.error("Sending {} failed: uid={}; peer={}; error={}", response.getClass().getSimpleName(), buyerAddress, trade.getId(), errorMessage);
                  appendToErrorMessage("Sending message failed: message=" + response + "\nerrorMessage=" + errorMessage);
                  failed();
              }
          });
        } catch (Throwable t) {
          failed(t);
        }
    }
}
