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

package bisq.core.trade.protocol.tasks.taker;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.google.common.base.Charsets;

import bisq.common.app.Version;
import bisq.common.crypto.Sig;
import bisq.common.taskrunner.TaskRunner;
import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.PrepareMultisigRequest;
import bisq.core.trade.protocol.tasks.TradeTask;
import bisq.core.user.User;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.SendDirectMessageListener;
import lombok.extern.slf4j.Slf4j;
import monero.daemon.model.MoneroNetworkType;
import monero.wallet.MoneroWalletJni;
import monero.wallet.model.MoneroWalletConfig;

@Slf4j
public class TakerSendsPrepareMultisigRequests extends TradeTask {
  
    private boolean peerAck;
    private boolean arbitratorAck;

    @SuppressWarnings({"unused"})
    public TakerSendsPrepareMultisigRequests(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            
            // create wallet for multisig
            // TODO (woodser): manage in common util, set path, server
            MoneroWalletJni multisigWallet = MoneroWalletJni.createWallet(new MoneroWalletConfig()
                    .setPassword("abctesting123")
                    .setNetworkType(MoneroNetworkType.STAGENET));
            
            // prepare multisig
            String preparedHex = multisigWallet.prepareMultisig();
            System.out.println("Prepared multisig hex: " + preparedHex);
            
            // collect fields for requests
            XmrWalletService walletService = processModel.getXmrWalletService();
            String offerId = processModel.getOffer().getId();
            String takerPayoutAddress = walletService.getOrCreateAddressEntry(offerId, XmrAddressEntry.Context.TRADE_PAYOUT).getAddressString();
            checkNotNull(trade.getTradeAmount(), "TradeAmount must not be null");
            checkNotNull(trade.getTakerFeeTxId(), "TakeOfferFeeTxId must not be null");
            final User user = processModel.getUser();
            checkNotNull(user, "User must not be null");
            
            // must have mediator address // TODO (woodser): using mediator instead of arbitrator because it's initially assigned, keep or replace with arbitrator role? 
            final List<NodeAddress> acceptedMediatorAddresses = user.getAcceptedMediatorAddresses();
            checkNotNull(acceptedMediatorAddresses, "acceptedMediatorAddresses must not be null");
            
            // Taker has to use offerId as nonce (he cannot manipulate that - so we avoid to have a challenge protocol for passing the nonce we want to get signed)
            // He cannot manipulate the offerId - so we avoid to have a challenge protocol for passing the nonce we want to get signed.
            final PaymentAccountPayload paymentAccountPayload = checkNotNull(processModel.getPaymentAccountPayload(trade), "processModel.getPaymentAccountPayload(trade) must not be null");
            byte[] sig = Sig.sign(processModel.getKeyRing().getSignatureKeyPair().getPrivate(), offerId.getBytes(Charsets.UTF_8));
            
            // create message to request preparing multisig
            PrepareMultisigRequest message = new PrepareMultisigRequest(
                    offerId,
                    processModel.getMyNodeAddress(),
                    trade.getTradeAmount().value,
                    trade.getTradePrice().getValue(),
                    trade.getTxFee().getValue(),
                    trade.getTakerFee().getValue(),
                    preparedHex,
                    takerPayoutAddress,
                    processModel.getPubKeyRing(),
                    paymentAccountPayload,
                    processModel.getAccountId(),
                    trade.getTakerFeeTxId(),
                    acceptedMediatorAddresses == null ? new ArrayList<>() : new ArrayList<>(acceptedMediatorAddresses),
                    trade.getMediatorNodeAddress(),
                    UUID.randomUUID().toString(),
                    Version.getP2PMessageVersion(),
                    sig,
                    new Date().getTime());
            
            log.info("Send {} with offerId {} and uid {} to peer {}",
                    message.getClass().getSimpleName(), message.getTradeId(),
                    message.getUid(), trade.getTradingPeerNodeAddress());
            
//            // TODO: send request to arbitrator
//            processModel.getP2PService().sendEncryptedDirectMessage(
//                    trade.getArbitratorNodeAddress(),
//                    trade.getArbitratorPubKeyRing(),
//                    message,
//                    new SendDirectMessageListener() {
//                        @Override
//                        public void onArrived() {
//                            log.info("{} arrived at arbitrator: offerId={}; uid={}", message.getClass().getSimpleName(), message.getTradeId(), message.getUid());
//                            arbitratorAck = true;
//                            checkComplete();
//                        }
//                        @Override
//                        public void onFault(String errorMessage) {
//                            log.error("Sending {} failed: uid={}; peer={}; error={}", message.getClass().getSimpleName(), message.getUid(), trade.getTradingPeerNodeAddress(), errorMessage);
//                            appendToErrorMessage("Sending message failed: message=" + message + "\nerrorMessage=" + errorMessage);
//                            failed();
//                        }
//                    }
//            );
            
            // send request to peer
            processModel.getP2PService().sendEncryptedDirectMessage(
                    trade.getTradingPeerNodeAddress(),
                    processModel.getTradingPeer().getPubKeyRing(),
                    message,
                    new SendDirectMessageListener() {
                        @Override
                        public void onArrived() {
                            log.info("{} arrived at peer: offerId={}; uid={}", message.getClass().getSimpleName(), message.getTradeId(), message.getUid());
                            peerAck = true;
                            checkComplete();
                        }
                        @Override
                        public void onFault(String errorMessage) {
                            log.error("Sending {} failed: uid={}; peer={}; error={}", message.getClass().getSimpleName(), message.getUid(), trade.getTradingPeerNodeAddress(), errorMessage);
                            appendToErrorMessage("Sending message failed: message=" + message + "\nerrorMessage=" + errorMessage);
                            failed();
                        }
                    }
            );
        } catch (Throwable t) {
          failed(t);
        }
    }
    
    private void checkComplete() {
      if (peerAck && arbitratorAck) complete();
    }
}
