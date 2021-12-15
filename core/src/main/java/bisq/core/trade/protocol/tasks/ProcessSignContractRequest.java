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
import bisq.common.util.Utilities;
import bisq.core.trade.ArbitratorTrade;
import bisq.core.trade.Contract;
import bisq.core.trade.Trade;
import bisq.core.trade.TradeUtils;
import bisq.core.trade.messages.SignContractRequest;
import bisq.core.trade.messages.SignContractResponse;
import bisq.core.trade.protocol.TradeListener;
import bisq.core.trade.protocol.TradingPeer;
import bisq.network.p2p.AckMessage;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.SendDirectMessageListener;
import java.util.Date;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProcessSignContractRequest extends TradeTask {
    
    private boolean ack1 = false;
    private boolean ack2 = false;
    private boolean failed = false;

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
          
          // sign contract only when both deposit txs hashes known
          // TODO (woodser): synchronize contract creation; both requests received at the same time
          // TODO (woodser): remove makerDepositTxId and takerDepositTxId from Trade
          if (processModel.getMaker().getDepositTxHash() == null || processModel.getTaker().getDepositTxHash() == null) {
              complete();
              return;
          }
          
          // create and sign contract
          Contract contract = TradeUtils.createContract(trade);
          String contractAsJson = Utilities.objectToJson(contract);
          String signature = Sig.sign(processModel.getKeyRing().getSignatureKeyPair().getPrivate(), contractAsJson);
          
          // save contract and signature
          trade.setContract(contract);
          trade.setContractAsJson(contractAsJson);
          trade.getSelf().setContractSignature(signature);
          
          // get response recipients. only arbitrator sends response to both peers
          NodeAddress recipient1 = trade instanceof ArbitratorTrade ? trade.getMakerNodeAddress() : trade.getTradingPeerNodeAddress();
          PubKeyRing recipient1PubKey = trade instanceof ArbitratorTrade ? trade.getMakerPubKeyRing() : trade.getTradingPeerPubKeyRing();
          NodeAddress recipient2 = trade instanceof ArbitratorTrade ? trade.getTakerNodeAddress() : null;
          PubKeyRing recipient2PubKey = trade instanceof ArbitratorTrade ? trade.getTakerPubKeyRing() : null;
          
          // complete on successful ack messages
          TradeListener ackListener = new TradeListener() {
              @Override
              public void onAckMessage(AckMessage ackMessage, NodeAddress sender) {
                  if (!ackMessage.getSourceMsgClassName().equals(SignContractResponse.class.getSimpleName())) return;
                  if (ackMessage.isSuccess()) {
                     if (sender.equals(trade.getTradingPeerNodeAddress())) ack1 = true;
                     if (sender.equals(trade.getArbitratorNodeAddress())) ack2 = true;
                     if (trade instanceof ArbitratorTrade ? ack1 && ack2 : ack1) { // only arbitrator sends response to both peers
                         trade.removeListener(this);
                         complete();
                     }
                  } else {
                      if (!failed) {
                          failed = true;
                          failed(ackMessage.getErrorMessage()); // TODO: (woodser): only fail once? build into task?
                      }
                  }
              }
          };
          trade.addListener(ackListener);
          
          // send contract signature response(s)
          if (recipient1 != null) sendSignContractResponse(recipient1, recipient1PubKey, signature);
          if (recipient2 != null) sendSignContractResponse(recipient2, recipient2PubKey, signature);
        } catch (Throwable t) {
          failed(t);
        }
    }
    
    private void sendSignContractResponse(NodeAddress nodeAddress, PubKeyRing pubKeyRing, String contractSignature) {
        
        // create response with contract signature
        SignContractResponse response = new SignContractResponse(
                trade.getOffer().getId(),
                processModel.getMyNodeAddress(),
                processModel.getPubKeyRing(),
                UUID.randomUUID().toString(),
                Version.getP2PMessageVersion(),
                new Date().getTime(),
                contractSignature);
        
        // send request
        processModel.getP2PService().sendEncryptedDirectMessage(nodeAddress, pubKeyRing, response, new SendDirectMessageListener() {
            @Override
            public void onArrived() {
                log.info("{} arrived: trading peer={}; offerId={}; uid={}", response.getClass().getSimpleName(), nodeAddress, trade.getId());
            }
            @Override
            public void onFault(String errorMessage) {
                log.error("Sending {} failed: uid={}; peer={}; error={}", response.getClass().getSimpleName(), nodeAddress, trade.getId(), errorMessage);
                appendToErrorMessage("Sending message failed: message=" + response + "\nerrorMessage=" + errorMessage);
                failed();
            }
        });
    }
}
