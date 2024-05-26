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
import haveno.common.taskrunner.TaskRunner;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.trade.messages.InitMultisigRequest;
import haveno.core.trade.messages.InitTradeRequest;
import haveno.core.trade.protocol.TradePeer;
import haveno.network.p2p.SendDirectMessageListener;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.MoneroWallet;

import java.util.Date;
import java.util.UUID;

/**
 * Arbitrator sends InitTradeRequest to maker after receiving InitTradeRequest
 * from taker and verifying taker reserve tx.
 *
 * Arbitrator sends InitMultisigRequests after the maker acks.
 */
@Slf4j
public class ArbitratorSendInitTradeOrMultisigRequests extends TradeTask {

    @SuppressWarnings({"unused"})
    public ArbitratorSendInitTradeOrMultisigRequests(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            InitTradeRequest request = (InitTradeRequest) processModel.getTradeMessage();
            TradePeer sender = trade.getTradePeer(processModel.getTempTradePeerNodeAddress());

            // handle request from maker
            if (sender == trade.getMaker()) {

                // create request to taker
                InitTradeRequest takerRequest = new InitTradeRequest(
                        request.getTradeProtocolVersion(),
                        processModel.getOfferId(),
                        trade.getAmount().longValueExact(),
                        trade.getPrice().getValue(),
                        request.getPaymentMethodId(),
                        request.getMakerAccountId(),
                        request.getTakerAccountId(),
                        request.getMakerPaymentAccountId(),
                        request.getTakerPaymentAccountId(),
                        request.getTakerPubKeyRing(),
                        UUID.randomUUID().toString(),
                        Version.getP2PMessageVersion(),
                        request.getAccountAgeWitnessSignatureOfOfferId(),
                        new Date().getTime(),
                        trade.getMaker().getNodeAddress(),
                        trade.getTaker().getNodeAddress(),
                        trade.getArbitrator().getNodeAddress(),
                        null,
                        null,
                        null,
                        null);

                // send request to taker
                log.info("Send {} with offerId {} and uid {} to taker {}", takerRequest.getClass().getSimpleName(), takerRequest.getOfferId(), takerRequest.getUid(), trade.getTaker().getNodeAddress());
                processModel.getP2PService().sendEncryptedDirectMessage(
                        trade.getTaker().getNodeAddress(), // TODO (woodser): maker's address might be different from original owner address if they disconnect and reconnect, need to validate and update address when requests received
                        trade.getTaker().getPubKeyRing(),
                        takerRequest,
                        new SendDirectMessageListener() {
                            @Override
                            public void onArrived() {
                                log.info("{} arrived at taker: offerId={}; uid={}", takerRequest.getClass().getSimpleName(), takerRequest.getOfferId(), takerRequest.getUid());
                                complete();
                            }
                            @Override
                            public void onFault(String errorMessage) {
                                log.error("Sending {} failed: uid={}; peer={}; error={}", takerRequest.getClass().getSimpleName(), takerRequest.getUid(), trade.getTaker().getNodeAddress(), errorMessage);
                                appendToErrorMessage("Sending message failed: message=" + takerRequest + "\nerrorMessage=" + errorMessage);
                                failed();
                            }
                        }
                );
            }

            // handle request from taker
            else if (sender == trade.getTaker()) {
                sendInitMultisigRequests();
                complete(); // TODO: wait for InitMultisigRequest arrivals?
            } else {
                throw new RuntimeException("Request is not from maker or taker");
            }
        } catch (Throwable t) {
            failed(t);
        }
    }

    private void sendInitMultisigRequests() {

        // ensure arbitrator has reserve txs
        if (processModel.getMaker().getReserveTxHash() == null) throw new RuntimeException("Arbitrator does not have maker's reserve tx after initializing trade");
        if (processModel.getTaker().getReserveTxHash() == null) throw new RuntimeException("Arbitrator does not have taker's reserve tx after initializing trade");

        // create wallet for multisig
        MoneroWallet multisigWallet = trade.createWallet();

        // prepare multisig
        String preparedHex = multisigWallet.prepareMultisig();
        trade.getSelf().setPreparedMultisigHex(preparedHex);

        // set trade fee address
        String address = HavenoUtils.ARBITRATOR_ASSIGNS_TRADE_FEE_ADDRESS ? trade.getXmrWalletService().getBaseAddressEntry().getAddressString() : HavenoUtils.getGlobalTradeFeeAddress();
        if (trade.getProcessModel().getTradeFeeAddress() == null) {
            trade.getProcessModel().setTradeFeeAddress(address);
        }

        // create message to initialize multisig
        InitMultisigRequest initMultisigRequest = new InitMultisigRequest(
                processModel.getOffer().getId(),
                UUID.randomUUID().toString(),
                Version.getP2PMessageVersion(),
                new Date().getTime(),
                preparedHex,
                null,
                null,
                trade.getProcessModel().getTradeFeeAddress());

        // send request to maker
        log.info("Send {} with offerId {} and uid {} to maker {}", initMultisigRequest.getClass().getSimpleName(), initMultisigRequest.getOfferId(), initMultisigRequest.getUid(), trade.getMaker().getNodeAddress());
        processModel.getP2PService().sendEncryptedDirectMessage(
                trade.getMaker().getNodeAddress(),
                trade.getMaker().getPubKeyRing(),
                initMultisigRequest,
                new SendDirectMessageListener() {
                    @Override
                    public void onArrived() {
                        log.info("{} arrived at maker: offerId={}; uid={}", initMultisigRequest.getClass().getSimpleName(), initMultisigRequest.getOfferId(), initMultisigRequest.getUid());
                    }
                    @Override
                    public void onFault(String errorMessage) {
                        log.error("Sending {} failed: uid={}; peer={}; error={}", initMultisigRequest.getClass().getSimpleName(), initMultisigRequest.getUid(), trade.getMaker().getNodeAddress(), errorMessage);
                    }
                }
        );

        // send request to taker
        log.info("Send {} with offerId {} and uid {} to taker {}", initMultisigRequest.getClass().getSimpleName(), initMultisigRequest.getOfferId(), initMultisigRequest.getUid(), trade.getTaker().getNodeAddress());
        processModel.getP2PService().sendEncryptedDirectMessage(
                trade.getTaker().getNodeAddress(),
                trade.getTaker().getPubKeyRing(),
                initMultisigRequest,
                new SendDirectMessageListener() {
                    @Override
                    public void onArrived() {
                        log.info("{} arrived at taker: offerId={}; uid={}", initMultisigRequest.getClass().getSimpleName(), initMultisigRequest.getOfferId(), initMultisigRequest.getUid());
                    }
                    @Override
                    public void onFault(String errorMessage) {
                        log.error("Sending {} failed: uid={}; peer={}; error={}", initMultisigRequest.getClass().getSimpleName(), initMultisigRequest.getUid(), trade.getTaker().getNodeAddress(), errorMessage);
                    }
                }
        );
    }
}
