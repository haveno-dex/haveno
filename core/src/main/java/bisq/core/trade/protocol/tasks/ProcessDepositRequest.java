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
import bisq.core.offer.Offer;
import bisq.core.offer.OfferPayload;
import bisq.core.trade.Trade;
import bisq.core.trade.TradeUtils;
import bisq.core.trade.messages.DepositRequest;
import bisq.core.trade.messages.DepositResponse;
import bisq.core.trade.protocol.TradingPeer;
import bisq.core.util.ParsingUtils;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.SendDirectMessageListener;
import java.math.BigInteger;
import java.util.Date;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import monero.daemon.MoneroDaemon;
import monero.wallet.MoneroWallet;

@Slf4j
public class ProcessDepositRequest extends TradeTask {
    
    @SuppressWarnings({"unused"})
    public ProcessDepositRequest(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
          runInterceptHook();
          
          // get contract and signature
          String contractAsJson = trade.getContractAsJson();
          DepositRequest request = (DepositRequest) processModel.getTradeMessage(); // TODO (woodser): verify response
          String signature = request.getContractSignature();

          // get peer info
          // TODO (woodser): make these utilities / refactor model
          // TODO (woodser): verify request
          PubKeyRing peerPubKeyRing;
          TradingPeer peer = trade.getTradingPeer(request.getSenderNodeAddress());
          if (peer == processModel.getArbitrator()) peerPubKeyRing = trade.getArbitratorPubKeyRing();
          else if (peer == processModel.getMaker()) peerPubKeyRing = trade.getMakerPubKeyRing();
          else if (peer == processModel.getTaker()) peerPubKeyRing = trade.getTakerPubKeyRing();
          else throw new RuntimeException(request.getClass().getSimpleName() + " is not from maker, taker, or arbitrator");

          // verify signature
          if (!Sig.verify(peerPubKeyRing.getSignaturePubKey(), contractAsJson, signature)) throw new RuntimeException("Peer's contract signature is invalid");
          
          // set peer's signature
          peer.setContractSignature(signature);
          
          // collect expected values of deposit tx
          Offer offer = trade.getOffer();
          boolean isFromTaker = request.getSenderNodeAddress().equals(trade.getTakerNodeAddress());
          boolean isFromBuyer = isFromTaker ? offer.getDirection() == OfferPayload.Direction.SELL : offer.getDirection() == OfferPayload.Direction.BUY;
          BigInteger depositAmount = ParsingUtils.coinToAtomicUnits(isFromBuyer ? offer.getBuyerSecurityDeposit() : offer.getAmount().add(offer.getSellerSecurityDeposit()));
          MoneroWallet multisigWallet = processModel.getProvider().getXmrWalletService().getMultisigWallet(trade.getId()); // TODO (woodser): only get, do not create
          String depositAddress = multisigWallet.getPrimaryAddress();
          BigInteger tradeFee;
          TradingPeer trader = trade.getTradingPeer(request.getSenderNodeAddress());
          if (trader == processModel.getMaker()) tradeFee = ParsingUtils.coinToAtomicUnits(trade.getOffer().getMakerFee());
          else if (trader == processModel.getTaker()) tradeFee = ParsingUtils.coinToAtomicUnits(trade.getTakerFee());
          else throw new RuntimeException("DepositRequest is not from maker or taker");
          
          // flush reserve tx from pool
          MoneroDaemon daemon = trade.getXmrWalletService().getDaemon();
          daemon.flushTxPool(trader.getReserveTxHash());
          
          // process and verify deposit tx which submits to the pool
          TradeUtils.processTradeTx(
                  daemon,
                  trade.getXmrWalletService().getWallet(),
                  depositAddress,
                  depositAmount,
                  tradeFee,
                  trader.getDepositTxHash(),
                  request.getDepositTxHex(),
                  request.getDepositTxKey(),
                  false);
          
          // sychronize to send only one response
          synchronized(processModel) {
              
              // set deposit info
              trader.setDepositTxHex(request.getDepositTxHex());
              trader.setDepositTxKey(request.getDepositTxKey());
              
              // relay deposit txs when both available
              // TODO (woodser): add small delay so tx has head start against double spend attempts?
              if (processModel.getMaker().getDepositTxHex() != null && processModel.getTaker().getDepositTxHex() != null) {
                  
                  // relay txs
                  daemon.relayTxByHash(processModel.getMaker().getDepositTxHash());
                  daemon.relayTxByHash(processModel.getTaker().getDepositTxHash());
                  
                  // create deposit response
                  DepositResponse response = new DepositResponse(
                          trade.getOffer().getId(),
                          processModel.getMyNodeAddress(),
                          processModel.getPubKeyRing(),
                          UUID.randomUUID().toString(),
                          Version.getP2PMessageVersion(),
                          new Date().getTime());
                  
                  // send deposit response to maker and taker
                  sendDepositResponse(trade.getMakerNodeAddress(), trade.getMakerPubKeyRing(), response);
                  sendDepositResponse(trade.getTakerNodeAddress(), trade.getTakerPubKeyRing(), response);
              }
          }
          
          // TODO (woodser): request persistence?
          complete();
        } catch (Throwable t) {
          failed(t);
        }
    }
    
    private void sendDepositResponse(NodeAddress nodeAddress, PubKeyRing pubKeyRing, DepositResponse response) {
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
