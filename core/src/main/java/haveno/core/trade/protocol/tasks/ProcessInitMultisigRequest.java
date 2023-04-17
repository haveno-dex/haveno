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

package haveno.core.trade.protocol.tasks;

import haveno.common.app.Version;
import haveno.common.crypto.PubKeyRing;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.trade.ArbitratorTrade;
import haveno.core.trade.MakerTrade;
import haveno.core.trade.TakerTrade;
import haveno.core.trade.Trade;
import haveno.core.trade.messages.InitMultisigRequest;
import haveno.core.trade.protocol.TradePeer;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.SendDirectMessageListener;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroMultisigInitResult;

import java.util.Arrays;
import java.util.Date;
import java.util.UUID;

import static com.google.common.base.Preconditions.checkNotNull;
import static haveno.core.util.Validator.checkTradeId;

@Slf4j
public class ProcessInitMultisigRequest extends TradeTask {

    private boolean ack1 = false;
    private boolean ack2 = false;
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
          XmrWalletService xmrWalletService = processModel.getProvider().getXmrWalletService();

          // get peer multisig participant
          TradePeer multisigParticipant = trade.getTradePeer(processModel.getTempTradePeerNodeAddress());

          // reconcile peer's established multisig hex with message
          if (multisigParticipant.getPreparedMultisigHex() == null) multisigParticipant.setPreparedMultisigHex(request.getPreparedMultisigHex());
          else if (request.getPreparedMultisigHex() != null && !multisigParticipant.getPreparedMultisigHex().equals(request.getPreparedMultisigHex())) throw new RuntimeException("Message's prepared multisig differs from previous messages, previous: " + multisigParticipant.getPreparedMultisigHex() + ", message: " + request.getPreparedMultisigHex());
          if (multisigParticipant.getMadeMultisigHex() == null) multisigParticipant.setMadeMultisigHex(request.getMadeMultisigHex());
          else if (request.getMadeMultisigHex() != null && !multisigParticipant.getMadeMultisigHex().equals(request.getMadeMultisigHex())) throw new RuntimeException("Message's made multisig differs from previous messages: " + request.getMadeMultisigHex() + " versus " + multisigParticipant.getMadeMultisigHex());
          if (multisigParticipant.getExchangedMultisigHex() == null) multisigParticipant.setExchangedMultisigHex(request.getExchangedMultisigHex());
          else if (request.getExchangedMultisigHex() != null && !multisigParticipant.getExchangedMultisigHex().equals(request.getExchangedMultisigHex())) throw new RuntimeException("Message's exchanged multisig differs from previous messages: " + request.getExchangedMultisigHex() + " versus " + multisigParticipant.getExchangedMultisigHex());

          // prepare multisig if applicable
          boolean updateParticipants = false;
          if (trade.getSelf().getPreparedMultisigHex() == null) {
            log.info("Preparing multisig wallet for {} {}", trade.getClass().getSimpleName(), trade.getId());
            multisigWallet = trade.createWallet();
            trade.getSelf().setPreparedMultisigHex(multisigWallet.prepareMultisig());
            trade.setStateIfValidTransitionTo(Trade.State.MULTISIG_PREPARED);
            updateParticipants = true;
          } else if (processModel.getMultisigAddress() == null) {
            multisigWallet = trade.getWallet();
          }

          // make multisig if applicable
          TradePeer[] peers = getMultisigPeers();
          if (trade.getSelf().getMadeMultisigHex() == null && peers[0].getPreparedMultisigHex() != null && peers[1].getPreparedMultisigHex() != null) {
            log.info("Making multisig wallet for {} {}", trade.getClass().getSimpleName(), trade.getId());
            String multisigHex = multisigWallet.makeMultisig(Arrays.asList(peers[0].getPreparedMultisigHex(), peers[1].getPreparedMultisigHex()), 2, xmrWalletService.getWalletPassword()); // TODO (woodser): xmrWalletService.makeMultisig(tradeId, multisigHexes, threshold)?
            trade.getSelf().setMadeMultisigHex(multisigHex);
            trade.setStateIfValidTransitionTo(Trade.State.MULTISIG_MADE);
            updateParticipants = true;
          }

          // import made multisig keys if applicable
          if (trade.getSelf().getExchangedMultisigHex() == null && peers[0].getMadeMultisigHex() != null && peers[1].getMadeMultisigHex() != null) {
            log.info("Importing made multisig hex for {} {}", trade.getClass().getSimpleName(), trade.getId());
            MoneroMultisigInitResult result = multisigWallet.exchangeMultisigKeys(Arrays.asList(peers[0].getMadeMultisigHex(), peers[1].getMadeMultisigHex()), xmrWalletService.getWalletPassword());
            trade.getSelf().setExchangedMultisigHex(result.getMultisigHex());
            trade.setStateIfValidTransitionTo(Trade.State.MULTISIG_EXCHANGED);
            updateParticipants = true;
          }

          // import exchanged multisig keys if applicable
          if (processModel.getMultisigAddress() == null && peers[0].getExchangedMultisigHex() != null && peers[1].getExchangedMultisigHex() != null) {
            log.info("Importing exchanged multisig hex for trade {}", trade.getId());
            MoneroMultisigInitResult result = multisigWallet.exchangeMultisigKeys(Arrays.asList(peers[0].getExchangedMultisigHex(), peers[1].getExchangedMultisigHex()), xmrWalletService.getWalletPassword());
            processModel.setMultisigAddress(result.getAddress());
            trade.saveWallet(); // save multisig wallet on completion
            trade.setStateIfValidTransitionTo(Trade.State.MULTISIG_COMPLETED);
            trade.updateWalletRefreshPeriod(); // starts syncing
          }

          // update multisig participants if new state to communicate
          if (updateParticipants) {

            // get destination addresses and pub key rings  // TODO: better way, use getMultisigPeers()
            NodeAddress peer1Address;
            PubKeyRing peer1PubKeyRing;
            NodeAddress peer2Address;
            PubKeyRing peer2PubKeyRing;
            if (trade instanceof ArbitratorTrade) {
              peer1Address = trade.getTaker().getNodeAddress();
              peer1PubKeyRing = trade.getTaker().getPubKeyRing();
              peer2Address = trade.getMaker().getNodeAddress();
              peer2PubKeyRing = trade.getMaker().getPubKeyRing();
            } else if (trade instanceof MakerTrade) {
              peer1Address = trade.getTaker().getNodeAddress();
              peer1PubKeyRing = trade.getTaker().getPubKeyRing();
              peer2Address = trade.getArbitrator().getNodeAddress();
              peer2PubKeyRing = trade.getArbitrator().getPubKeyRing();
            } else {
              peer1Address = trade.getMaker().getNodeAddress();
              peer1PubKeyRing = trade.getMaker().getPubKeyRing();
              peer2Address = trade.getArbitrator().getNodeAddress();
              peer2PubKeyRing = trade.getArbitrator().getPubKeyRing();
            }

            if (peer1Address == null) throw new RuntimeException("Peer1 address is null");
            if (peer1PubKeyRing == null) throw new RuntimeException("Peer1 pub key ring is null");
            if (peer2Address == null) throw new RuntimeException("Peer2 address is null");
            if (peer2PubKeyRing == null) throw new RuntimeException("Peer2 pub key ring null");

            log.info("{} {} sending InitMultisigRequests", trade.getClass().getSimpleName(), trade.getId());

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
      } catch (Throwable t) {
        failed(t);
      }
    }

    private TradePeer[] getMultisigPeers() {
      TradePeer[] peers = new TradePeer[2];
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
                UUID.randomUUID().toString(),
                Version.getP2PMessageVersion(),
                new Date().getTime(),
                trade.getSelf().getPreparedMultisigHex(),
                trade.getSelf().getMadeMultisigHex(),
                trade.getSelf().getExchangedMultisigHex());

        log.info("Send {} with offerId {} and uid {} to peer {}", request.getClass().getSimpleName(), request.getTradeId(), request.getUid(), recipient);
        processModel.getP2PService().sendEncryptedDirectMessage(recipient, pubKeyRing, request, listener);
    }

    private void completeAux() {
        complete();
    }
}
