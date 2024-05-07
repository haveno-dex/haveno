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


import common.utils.JsonUtils;
import haveno.common.app.Version;
import haveno.common.crypto.PubKeyRing;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.offer.Offer;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.trade.messages.DepositRequest;
import haveno.core.trade.messages.DepositResponse;
import haveno.core.trade.protocol.TradePeer;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.SendDirectMessageListener;
import lombok.extern.slf4j.Slf4j;
import monero.daemon.MoneroDaemon;
import monero.daemon.model.MoneroSubmitTxResult;
import monero.daemon.model.MoneroTx;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Date;
import java.util.UUID;

@Slf4j
public class ArbitratorProcessDepositRequest extends TradeTask {

    private Throwable error;

    @SuppressWarnings({"unused"})
    public ArbitratorProcessDepositRequest(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            // check if trade is failed
            if (trade.getState() == Trade.State.PUBLISH_DEPOSIT_TX_REQUEST_FAILED) throw new RuntimeException("Cannot process deposit request because trade is already failed, tradeId=" + trade.getId());

            // update trade state
            trade.setStateIfValidTransitionTo(Trade.State.SAW_ARRIVED_PUBLISH_DEPOSIT_TX_REQUEST);
            processModel.getTradeManager().requestPersistence();

            // process request
            processDepositRequest();
            complete();
        } catch (Throwable t) {
            this.error = t;
            t.printStackTrace();
            trade.setState(Trade.State.PUBLISH_DEPOSIT_TX_REQUEST_FAILED);
            failed(t);
        }
        processModel.getTradeManager().requestPersistence();
    }

    private void processDepositRequest() {

        // get contract and signature
        String contractAsJson = trade.getContractAsJson();
        DepositRequest request = (DepositRequest) processModel.getTradeMessage(); // TODO (woodser): verify response
        byte[] signature = request.getContractSignature();

        // get trader info
        TradePeer trader = trade.getTradePeer(processModel.getTempTradePeerNodeAddress());
        if (trader == null) throw new RuntimeException(request.getClass().getSimpleName() + " is not from maker, taker, or arbitrator");
        PubKeyRing peerPubKeyRing = trader.getPubKeyRing();

        // verify signature
        if (!HavenoUtils.isSignatureValid(peerPubKeyRing, contractAsJson, signature)) {
            throw new RuntimeException("Peer's contract signature is invalid");
        }

        // set peer's signature
        trader.setContractSignature(signature);

        // collect expected values
        Offer offer = trade.getOffer();
        boolean isFromTaker = trader == trade.getTaker();
        boolean isFromBuyer = trader == trade.getBuyer();
        BigInteger tradeFee = isFromTaker ? trade.getTakerFee() : trade.getMakerFee();
        BigInteger sendTradeAmount =  isFromBuyer ? BigInteger.ZERO : trade.getAmount();
        BigInteger securityDeposit = isFromBuyer ? trade.getBuyerSecurityDepositBeforeMiningFee() : trade.getSellerSecurityDepositBeforeMiningFee();
        String depositAddress = processModel.getMultisigAddress();

        // verify deposit tx
        MoneroTx verifiedTx;
        try {
            verifiedTx = trade.getXmrWalletService().verifyDepositTx(
                    offer.getId(),
                    tradeFee,
                    trade.getProcessModel().getTradeFeeAddress(),
                    sendTradeAmount,
                    securityDeposit,
                    depositAddress,
                    trader.getDepositTxHash(),
                    request.getDepositTxHex(),
                    request.getDepositTxKey(),
                    null);
        } catch (Exception e) {
            throw new RuntimeException("Error processing deposit tx from " + (isFromTaker ? "taker " : "maker ") + trader.getNodeAddress() + ", offerId=" + offer.getId() + ": " + e.getMessage());
        }

        // update trade state
        trader.setSecurityDeposit(securityDeposit.subtract(verifiedTx.getFee())); // subtract mining fee from security deposit
        trader.setDepositTxFee(verifiedTx.getFee());
        trader.setDepositTxHex(request.getDepositTxHex());
        trader.setDepositTxKey(request.getDepositTxKey());
        if (request.getPaymentAccountKey() != null) trader.setPaymentAccountKey(request.getPaymentAccountKey());
        processModel.getTradeManager().requestPersistence();

        // relay deposit txs when both available
        MoneroDaemon daemon = trade.getXmrWalletService().getDaemon();
        if (processModel.getMaker().getDepositTxHex() != null && processModel.getTaker().getDepositTxHex() != null) {

            // check timeout and extend just before relaying
            if (isTimedOut()) throw new RuntimeException("Trade protocol has timed out before relaying deposit txs for {} {}" + trade.getClass().getSimpleName() + " " + trade.getShortId());
            trade.addInitProgressStep();

            try {

                // submit txs to pool but do not relay
                MoneroSubmitTxResult makerResult = daemon.submitTxHex(processModel.getMaker().getDepositTxHex(), true);
                if (!makerResult.isGood()) throw new RuntimeException("Error submitting maker deposit tx: " + JsonUtils.serialize(makerResult));
                MoneroSubmitTxResult takerResult = daemon.submitTxHex(processModel.getTaker().getDepositTxHex(), true);
                if (!takerResult.isGood()) throw new RuntimeException("Error submitting taker deposit tx: " + JsonUtils.serialize(takerResult));

                // relay txs
                daemon.relayTxsByHash(Arrays.asList(processModel.getMaker().getDepositTxHash(), processModel.getTaker().getDepositTxHash()));

                // update trade state
                log.info("Arbitrator published deposit txs for trade " + trade.getId());
                trade.setState(Trade.State.ARBITRATOR_PUBLISHED_DEPOSIT_TXS);
            } catch (Exception e) {

                // flush txs from pool
                try {
                    daemon.flushTxPool(processModel.getMaker().getDepositTxHash());
                } catch (Exception e2) {
                    e2.printStackTrace();
                }
                try {
                    daemon.flushTxPool(processModel.getTaker().getDepositTxHash());
                } catch (Exception e2) {
                    e2.printStackTrace();
                }
                throw e;
            }
        } else {

            // subscribe to trade state once to send responses with ack or nack
            trade.stateProperty().addListener((obs, oldState, newState) -> {
                if (newState == Trade.State.PUBLISH_DEPOSIT_TX_REQUEST_FAILED) {
                    sendDepositResponses(error == null ? "Arbitrator failed to publish deposit txs within timeout for trade " + trade.getId() : error.getMessage());
                } else if (newState == Trade.State.ARBITRATOR_PUBLISHED_DEPOSIT_TXS) {
                    sendDepositResponses(null);
                }
            });

            if (processModel.getMaker().getDepositTxHex() == null) log.info("Arbitrator waiting for deposit request from maker for trade " + trade.getId());
            if (processModel.getTaker().getDepositTxHex() == null) log.info("Arbitrator waiting for deposit request from taker for trade " + trade.getId());
        }
    }

    private boolean isTimedOut() {
        return !processModel.getTradeManager().hasOpenTrade(trade);
    }

    private void sendDepositResponses(String errorMessage) {
                
        // create deposit response
        DepositResponse response = new DepositResponse(
                trade.getOffer().getId(),
                UUID.randomUUID().toString(),
                Version.getP2PMessageVersion(),
                new Date().getTime(),
                errorMessage,
                trade.getBuyer().getSecurityDeposit().longValue(),
                trade.getSeller().getSecurityDeposit().longValue());

        // send deposit response to maker and taker
        sendDepositResponse(trade.getMaker().getNodeAddress(), trade.getMaker().getPubKeyRing(), response);
        sendDepositResponse(trade.getTaker().getNodeAddress(), trade.getTaker().getPubKeyRing(), response);
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
            }
        });
    }
}
