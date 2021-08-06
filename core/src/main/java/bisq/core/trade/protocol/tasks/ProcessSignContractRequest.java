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
import bisq.common.crypto.PubKeyRing;
import bisq.common.crypto.Sig;
import bisq.common.taskrunner.TaskRunner;
import bisq.common.util.Utilities;
import bisq.core.trade.ArbitratorTrade;
import bisq.core.trade.Contract;
import bisq.core.trade.Trade;
import bisq.core.trade.TradeUtils;
import bisq.core.trade.messages.SignContractRequest;
import bisq.core.trade.messages.SignContractResponse;
import bisq.core.trade.protocol.TradingPeer;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.SendDirectMessageListener;
import java.util.Date;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProcessSignContractRequest extends TradeTask {
    
    private boolean ack1 = false;
    private boolean ack2 = false;

    @SuppressWarnings({"unused"})
    public ProcessSignContractRequest(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
          runInterceptHook();
          
          // extract fields from request
          // TODO (woodser): verify request and from maker or taker
          SignContractRequest request = (SignContractRequest) processModel.getTradeMessage();
          TradingPeer trader = trade.getTradingPeer(request.getSenderNodeAddress());
          trader.setDepositTxHash(request.getDepositTxHash());
          trader.setAccountId(request.getAccountId());
          trader.setPaymentAccountPayloadHash(request.getPaymentAccountPayloadHash());
          trader.setPayoutAddressString(request.getPayoutAddress());
          
          // return contract signature when ready
          // TODO (woodser): synchronize contract creation; both requests received at the same time
          // TODO (woodser): remove makerDepositTxId and takerDepositTxId from Trade
          if (processModel.getMaker().getDepositTxHash() != null && processModel.getTaker().getDepositTxHash() != null) { // TODO (woodser): synchronize on process model before setting hash so response only sent once
              
              // create and sign contract
              Contract contract = TradeUtils.createContract(trade);
              String contractAsJson = Utilities.objectToJson(contract);
              String signature = Sig.sign(processModel.getKeyRing().getSignatureKeyPair().getPrivate(), contractAsJson);
              
              // save contract and signature
              trade.setContract(contract);
              trade.setContractAsJson(contractAsJson);
              trade.getSelf().setContractSignature(signature);
              
              // create response with contract signature
              SignContractResponse response = new SignContractResponse(
                      trade.getOffer().getId(),
                      processModel.getMyNodeAddress(),
                      processModel.getPubKeyRing(),
                      UUID.randomUUID().toString(),
                      Version.getP2PMessageVersion(),
                      new Date().getTime(),
                      signature);
              
              // get response recipients. only arbitrator sends response to both peers
              NodeAddress recipient1 = trade instanceof ArbitratorTrade ? trade.getMakerNodeAddress() : trade.getTradingPeerNodeAddress();
              PubKeyRing recipient1PubKey = trade instanceof ArbitratorTrade ? trade.getMakerPubKeyRing() : trade.getTradingPeerPubKeyRing();
              NodeAddress recipient2 = trade instanceof ArbitratorTrade ? trade.getTakerNodeAddress() : null;
              PubKeyRing recipient2PubKey = trade instanceof ArbitratorTrade ? trade.getTakerPubKeyRing() : null;
              
              // send response to recipient 1
              processModel.getP2PService().sendEncryptedDirectMessage(recipient1, recipient1PubKey, response, new SendDirectMessageListener() {
                  @Override
                  public void onArrived() {
                      log.info("{} arrived: trading peer={}; offerId={}; uid={}", response.getClass().getSimpleName(), recipient1, trade.getId());
                      ack1 = true;
                      if (ack1 && (recipient2 == null || ack2)) complete();
                  }
                  @Override
                  public void onFault(String errorMessage) {
                      log.error("Sending {} failed: uid={}; peer={}; error={}", response.getClass().getSimpleName(), recipient1, trade.getId(), errorMessage);
                      appendToErrorMessage("Sending message failed: message=" + response + "\nerrorMessage=" + errorMessage);
                      failed();
                  }
              });
              
              // send response to recipient 2 if applicable
              if (recipient2 != null) {
                  processModel.getP2PService().sendEncryptedDirectMessage(recipient2, recipient2PubKey, response, new SendDirectMessageListener() {
                      @Override
                      public void onArrived() {
                          log.info("{} arrived: trading peer={}; offerId={}; uid={}", response.getClass().getSimpleName(), recipient2, trade.getId());
                          ack2 = true;
                          if (ack1 && ack2) complete();
                      }
                      @Override
                      public void onFault(String errorMessage) {
                          log.error("Sending {} failed: uid={}; peer={}; error={}", response.getClass().getSimpleName(), recipient2, trade.getId(), errorMessage);
                          appendToErrorMessage("Sending message failed: message=" + response + "\nerrorMessage=" + errorMessage);
                          failed();
                      }
                  });
              }
          }
        } catch (Throwable t) {
          failed(t);
        }
    }
}
