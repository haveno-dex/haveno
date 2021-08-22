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

import bisq.core.trade.ArbitratorTrade;
import bisq.core.trade.MakerTrade;
import bisq.core.trade.TakerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.InitMultisigRequest;
import bisq.core.trade.protocol.TradingPeer;

import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.SendDirectMessageListener;

import bisq.common.app.Version;
import bisq.common.crypto.PubKeyRing;
import bisq.common.taskrunner.TaskRunner;

import java.util.Arrays;
import java.util.Date;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

import static bisq.core.util.Validator.checkTradeId;
import static com.google.common.base.Preconditions.checkNotNull;



import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroMultisigInitResult;

@Slf4j
public class ProcessInitMultisigRequest extends TradeTask {

    private boolean ack1 = false;
    private boolean ack2 = false;
    private static Object lock = new Object();
    MoneroWallet multisigWallet;

    @SuppressWarnings({"unused"})
    public ProcessInitMultisigRequest(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
          runInterceptHook();
          log.debug("current trade state " + trade.getState());
          InitMultisigRequest request = (InitMultisigRequest) processModel.getTradeMessage();
          checkNotNull(request);
          checkTradeId(processModel.getOfferId(), request);

          System.out.println("PROCESS MULTISIG MESSAGE");
          System.out.println(request);
//          System.out.println("PROCESS MULTISIG MESSAGE TRADE");
//          System.out.println(trade);

          // TODO (woodser): verify request including sender's signature in previous pipeline task
          // TODO (woodser): run in separate thread to not block UI thread?
          // TODO (woodser): validate message has expected sender in previous step

          // synchronize access to wallet
          synchronized (lock) {

            // get peer multisig participant
            TradingPeer multisigParticipant;
            if (request.getSenderNodeAddress().equals(trade.getMakerNodeAddress())) multisigParticipant = processModel.getMaker();
            else if (request.getSenderNodeAddress().equals(trade.getTakerNodeAddress())) multisigParticipant = processModel.getTaker();
            else if (request.getSenderNodeAddress().equals(trade.getArbitratorNodeAddress())) multisigParticipant = processModel.getArbitrator();
            else throw new RuntimeException("Invalid sender to process init trade message: " + trade.getClass().getName());

            // reconcile peer's established multisig hex with message
            if (multisigParticipant.getPreparedMultisigHex() == null) multisigParticipant.setPreparedMultisigHex(request.getPreparedMultisigHex());
            else if (!multisigParticipant.getPreparedMultisigHex().equals(request.getPreparedMultisigHex())) throw new RuntimeException("Message's prepared multisig differs from previous messages, previous: " + multisigParticipant.getPreparedMultisigHex() + ", message: " + request.getPreparedMultisigHex());
            if (multisigParticipant.getMadeMultisigHex() == null) multisigParticipant.setMadeMultisigHex(request.getMadeMultisigHex());
            else if (!multisigParticipant.getMadeMultisigHex().equals(request.getMadeMultisigHex())) throw new RuntimeException("Message's made multisig differs from previous messages");
            
            // prepare multisig if applicable
            boolean updateParticipants = false;
            if (processModel.getPreparedMultisigHex() == null) {
              System.out.println("Preparing multisig wallet!");
              multisigWallet = processModel.getProvider().getXmrWalletService().createMultisigWallet(trade.getId());
              processModel.setPreparedMultisigHex(multisigWallet.prepareMultisig());
              updateParticipants = true;
            } else {
              multisigWallet = processModel.getProvider().getXmrWalletService().getMultisigWallet(trade.getId());
            }

            // make multisig if applicable
            TradingPeer[] peers = getMultisigPeers();
            if (processModel.getMadeMultisigHex() == null && peers[0].getPreparedMultisigHex() != null && peers[1].getPreparedMultisigHex() != null) {
              System.out.println("Making multisig wallet!");
              MoneroMultisigInitResult result = multisigWallet.makeMultisig(Arrays.asList(peers[0].getPreparedMultisigHex(), peers[1].getPreparedMultisigHex()), 2, "abctesting123"); // TODO (woodser): move this to config
              processModel.setMadeMultisigHex(result.getMultisigHex());
              updateParticipants = true;
            }

            // exchange multisig keys if applicable
            if (!processModel.isMultisigSetupComplete() && peers[0].getMadeMultisigHex() != null && peers[1].getMadeMultisigHex() != null) {
              System.out.println("Exchanging multisig wallet!");
              multisigWallet.exchangeMultisigKeys(Arrays.asList(peers[0].getMadeMultisigHex(), peers[1].getMadeMultisigHex()), "abctesting123");  // TODO (woodser): move this to config
              processModel.setMultisigSetupComplete(true);
            }

            // update multisig participants if new state to communicate
            if (updateParticipants) {

              // get destination addresses and pub key rings  // TODO: better way, use getMultisigPeers()
              NodeAddress peer1Address;
              PubKeyRing peer1PubKeyRing;
              NodeAddress peer2Address;
              PubKeyRing peer2PubKeyRing;
              if (trade instanceof ArbitratorTrade) {
                peer1Address = trade.getTakerNodeAddress();
                peer1PubKeyRing = trade.getTakerPubKeyRing();
                peer2Address = trade.getMakerNodeAddress();
                peer2PubKeyRing = trade.getMakerPubKeyRing();
              } else if (trade instanceof MakerTrade) {
                peer1Address = trade.getTakerNodeAddress();
                peer1PubKeyRing = trade.getTakerPubKeyRing();
                peer2Address = trade.getArbitratorNodeAddress();
                peer2PubKeyRing = trade.getArbitratorPubKeyRing();
              } else {
                peer1Address = trade.getMakerNodeAddress();
                peer1PubKeyRing = trade.getMakerPubKeyRing();
                peer2Address = trade.getArbitratorNodeAddress();
                peer2PubKeyRing = trade.getArbitratorPubKeyRing();
              }

              if (peer1Address == null) throw new RuntimeException("Peer1 address is null");
              if (peer1PubKeyRing == null) throw new RuntimeException("Peer1 pub key ring is null");
              if (peer2Address == null) throw new RuntimeException("Peer2 address is null");
              if (peer2PubKeyRing == null) throw new RuntimeException("Peer2 pub key ring null");

              // send to peer 1
              sendInitMultisigRequest(peer1Address, peer1PubKeyRing, new SendDirectMessageListener() {
                @Override
                public void onArrived() {
                    log.info("{} arrived: peer={}; offerId={}; uid={}", request.getClass().getSimpleName(), peer1Address, request.getTradeId(), request.getUid());
                    ack1 = true;
                    if (ack1 && ack2) completeAux();
                }
                @Override
                public void onFault(String errorMessage) {
                    log.error("Sending {} failed: uid={}; peer={}; error={}", request.getClass().getSimpleName(), request.getUid(), peer1Address, errorMessage);
                    appendToErrorMessage("Sending message failed: message=" + request + "\nerrorMessage=" + errorMessage);
                    failed();
                }
              });

              // send to peer 2
              sendInitMultisigRequest(peer2Address, peer2PubKeyRing, new SendDirectMessageListener() {
                @Override
                public void onArrived() {
                    log.info("{} arrived: peer={}; offerId={}; uid={}", request.getClass().getSimpleName(), peer2Address, request.getTradeId(), request.getUid());
                    ack2 = true;
                    if (ack1 && ack2) completeAux();
                }
                @Override
                public void onFault(String errorMessage) {
                    log.error("Sending {} failed: uid={}; peer={}; error={}", request.getClass().getSimpleName(), request.getUid(), peer2Address, errorMessage);
                    appendToErrorMessage("Sending message failed: message=" + request + "\nerrorMessage=" + errorMessage);
                    failed();
                }
              });
            } else {
              completeAux();
            }
          }
        } catch (Throwable t) {
          failed(t);
        }
    }

    private TradingPeer[] getMultisigPeers() {
      TradingPeer[] peers = new TradingPeer[2];
      if (trade instanceof TakerTrade) {
        peers[0] = processModel.getArbitrator();
        peers[1] = processModel.getMaker();
      } else if (trade instanceof MakerTrade) {
        peers[1] = processModel.getTaker();
        peers[0] = processModel.getArbitrator();
      } else {
        peers[0] = processModel.getTaker();
        peers[1] = processModel.getMaker();
      }
      return peers;
    }

    private void sendInitMultisigRequest(NodeAddress recipient, PubKeyRing pubKeyRing, SendDirectMessageListener listener) {

      // create multisig message with current multisig hex
      InitMultisigRequest request = new InitMultisigRequest(
              processModel.getOffer().getId(),
              processModel.getMyNodeAddress(),
              processModel.getPubKeyRing(),
              UUID.randomUUID().toString(),
              Version.getP2PMessageVersion(),
              new Date().getTime(),
              processModel.getPreparedMultisigHex(),
              processModel.getMadeMultisigHex());

      log.info("Send {} with offerId {} and uid {} to peer {}", request.getClass().getSimpleName(), request.getTradeId(), request.getUid(), recipient);
      processModel.getP2PService().sendEncryptedDirectMessage(recipient, pubKeyRing, request, listener);
    }

    private void completeAux() {
      multisigWallet.save();
      complete();
    }
}
