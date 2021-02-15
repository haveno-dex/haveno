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

package bisq.core.trade.protocol.tasks.maker;

import static bisq.core.util.Validator.checkTradeId;
import static com.google.common.base.Preconditions.checkNotNull;

import bisq.common.app.Version;
import bisq.common.crypto.Sig;
import bisq.common.taskrunner.TaskRunner;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.protocol.tasks.TradeTask;
import bisq.core.user.User;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.SendDirectMessageListener;
import com.google.common.base.Charsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MakerSendsInitTradeRequest extends TradeTask {
    @SuppressWarnings({"unused"})
    public MakerSendsInitTradeRequest(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            
            System.out.println("MAKER SENDING INIT TRADE REQ TO ARBITRATOR");
            
            // verify trade
            InitTradeRequest request = (InitTradeRequest) processModel.getTradeMessage();
            checkNotNull(request);
            checkTradeId(processModel.getOfferId(), request);
            
//            // create wallet for multisig
//            // TODO (woodser): manage in common util, set path, server
//            MoneroWallet multisigWallet = MoneroWallet.createWallet(new MoneroWalletConfig()
//                    .setPassword("abctesting123")
//                    .setNetworkType(MoneroNetworkType.STAGENET));
//            
//            // prepare multisig
//            String preparedHex = multisigWallet.prepareMultisig();
//            System.out.println("Prepared multisig hex: " + preparedHex);

            // collect fields to send taker prepared multisig response  // TODO (woodser): this should happen on response from arbitrator
            XmrWalletService walletService = processModel.getProvider().getXmrWalletService();
            String offerId = processModel.getOffer().getId();
            String payoutAddress = walletService.getWallet().createSubaddress(0).getAddress();  // TODO (woodser): register TRADE_PAYOUT?
            walletService.getWallet().save();
            checkNotNull(trade.getTradeAmount(), "TradeAmount must not be null");
//            checkNotNull(trade.getTakerFeeTxId(), "TakeOfferFeeTxId must not be null"); // TODO (woodser): no taker fee tx yet if creating multisig first
            final User user = processModel.getUser();
            checkNotNull(user, "User must not be null");
            final List<NodeAddress> acceptedMediatorAddresses = user.getAcceptedMediatorAddresses();
            checkNotNull(acceptedMediatorAddresses, "acceptedMediatorAddresses must not be null");
            
            // Taker has to use offerId as nonce (he cannot manipulate that - so we avoid to have a challenge protocol for passing the nonce we want to get signed)
            // He cannot manipulate the offerId - so we avoid to have a challenge protocol for passing the nonce we want to get signed.
            final PaymentAccountPayload paymentAccountPayload = checkNotNull(processModel.getPaymentAccountPayload(trade), "processModel.getPaymentAccountPayload(trade) must not be null");
            byte[] sig = Sig.sign(processModel.getKeyRing().getSignatureKeyPair().getPrivate(), offerId.getBytes(Charsets.UTF_8));
            
            System.out.println("MAKER SENDING ARBITRTATOR SENDER NODE ADDRESS");
            System.out.println(processModel.getMyNodeAddress());
            
            // create message to initialize trade
            InitTradeRequest message = new InitTradeRequest(
                    offerId,
                    processModel.getMyNodeAddress(),
                    processModel.getPubKeyRing(),
                    trade.getTradeAmount().value,
                    trade.getTradePrice().getValue(),
                    trade.getTxFee().getValue(),
                    trade.getTakerFee().getValue(),
                    payoutAddress,
                    paymentAccountPayload,
                    processModel.getAccountId(),
                    trade.getTakerFeeTxId(),
                    UUID.randomUUID().toString(),
                    Version.getP2PMessageVersion(),
                    sig,
                    new Date().getTime(),
                    trade.getTakerNodeAddress(),
                    trade.getMakerNodeAddress(),
                    trade.getArbitratorNodeAddress());
            
            log.info("Send {} with offerId {} and uid {} to peer {}",
                    message.getClass().getSimpleName(), message.getTradeId(),
                    message.getUid(), trade.getMediatorNodeAddress());
            
            
            System.out.println("MAKER TRADE INFO");
            System.out.println("Trading peer node address: " + trade.getTradingPeerNodeAddress());
            System.out.println("Maker node address: " + trade.getMakerNodeAddress());
            System.out.println("Taker node adddress: " + trade.getTakerNodeAddress());
            System.out.println("Mediator node address: " + trade.getMediatorNodeAddress());
            System.out.println("Arbitrator node address: " + trade.getArbitratorNodeAddress());
            
            // send request to arbitrator
            processModel.getP2PService().sendEncryptedDirectMessage(
                    trade.getArbitratorNodeAddress(),
                    trade.getArbitratorPubKeyRing(),
                    message,
                    new SendDirectMessageListener() {
                        @Override
                        public void onArrived() {
                            log.info("{} arrived at arbitrator: offerId={}; uid={}", message.getClass().getSimpleName(), message.getTradeId(), message.getUid());
                            complete();
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
}
