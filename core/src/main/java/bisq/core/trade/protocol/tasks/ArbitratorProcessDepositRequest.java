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
import bisq.core.offer.Offer;
import bisq.core.offer.OfferDirection;
import bisq.core.trade.Trade;
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

@Slf4j
public class ArbitratorProcessDepositRequest extends TradeTask {

    @SuppressWarnings({"unused"})
    public ArbitratorProcessDepositRequest(TaskRunner taskHandler, Trade trade) {
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
          TradingPeer peer = trade.getTradingPeer(request.getSenderNodeAddress());
          if (peer == null) throw new RuntimeException(request.getClass().getSimpleName() + " is not from maker, taker, or arbitrator");
          PubKeyRing peerPubKeyRing = peer.getPubKeyRing();

          // verify signature
          if (!Sig.verify(peerPubKeyRing.getSignaturePubKey(), contractAsJson, signature)) throw new RuntimeException("Peer's contract signature is invalid");

          // set peer's signature
          peer.setContractSignature(signature);

          // collect expected values of deposit tx
          Offer offer = trade.getOffer();
          boolean isFromTaker = request.getSenderNodeAddress().equals(trade.getTaker().getNodeAddress());
          boolean isFromBuyer = isFromTaker ? offer.getDirection() == OfferDirection.SELL : offer.getDirection() == OfferDirection.BUY;
          BigInteger depositAmount = ParsingUtils.coinToAtomicUnits(isFromBuyer ? offer.getBuyerSecurityDeposit() : offer.getAmount().add(offer.getSellerSecurityDeposit()));
          String depositAddress = processModel.getMultisigAddress();
          BigInteger tradeFee;
          TradingPeer trader = trade.getTradingPeer(request.getSenderNodeAddress());
          if (trader == processModel.getMaker()) tradeFee = ParsingUtils.coinToAtomicUnits(trade.getOffer().getMakerFee());
          else if (trader == processModel.getTaker()) tradeFee = ParsingUtils.coinToAtomicUnits(trade.getTakerFee());
          else throw new RuntimeException("DepositRequest is not from maker or taker");

          // verify deposit tx
          trade.getXmrWalletService().verifyTradeTx(depositAddress,
                  depositAmount,
                  tradeFee,
                  trader.getDepositTxHash(),
                  request.getDepositTxHex(),
                  request.getDepositTxKey(),
                  null,
                  false);

          // set deposit info
          trader.setDepositTxHex(request.getDepositTxHex());
          trader.setDepositTxKey(request.getDepositTxKey());
          if (request.getPaymentAccountKey() != null) trader.setPaymentAccountKey(request.getPaymentAccountKey());

          // relay deposit txs when both available
          // TODO (woodser): add small delay so tx has head start against double spend attempts?
          if (processModel.getMaker().getDepositTxHex() != null && processModel.getTaker().getDepositTxHex() != null) {

              // relay txs
              MoneroDaemon daemon = trade.getXmrWalletService().getDaemon();
              daemon.submitTxHex(processModel.getMaker().getDepositTxHex()); // TODO (woodser): check that result is good. will need to release funds if one is submitted
              daemon.submitTxHex(processModel.getTaker().getDepositTxHex());
              
              // update trade state
              log.info("Arbitrator submitted deposit txs for trade " + trade.getId());
              trade.setState(Trade.State.ARBITRATOR_PUBLISHED_DEPOSIT_TXS);
              
              // create deposit response
              DepositResponse response = new DepositResponse(
                      trade.getOffer().getId(),
                      processModel.getMyNodeAddress(),
                      processModel.getPubKeyRing(),
                      UUID.randomUUID().toString(),
                      Version.getP2PMessageVersion(),
                      new Date().getTime());
              
              // send deposit response to maker and taker
              sendDepositResponse(trade.getMaker().getNodeAddress(), trade.getMaker().getPubKeyRing(), response);
              sendDepositResponse(trade.getTaker().getNodeAddress(), trade.getTaker().getPubKeyRing(), response);
          } else {
              if (processModel.getMaker().getDepositTxHex() == null) log.info("Arbitrator waiting for deposit request from maker for trade " + trade.getId());
              if (processModel.getTaker().getDepositTxHex() == null) log.info("Arbitrator waiting for deposit request from taker for trade " + trade.getId());
          }

          // TODO (woodser): request persistence?
          complete();
        } catch (Throwable t) {
          failed(t);
        }
    }

    private void sendDepositResponse(NodeAddress nodeAddress, PubKeyRing pubKeyRing, DepositResponse response) {
        log.info("Sending deposit response to trader={}; offerId={}", nodeAddress, trade.getId());
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
