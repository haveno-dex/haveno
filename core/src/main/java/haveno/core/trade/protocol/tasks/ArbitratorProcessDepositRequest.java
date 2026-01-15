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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Slf4j
public class ArbitratorProcessDepositRequest extends TradeTask {

    private boolean depositResponsesSent;

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
            trade.getProcessModel().error = t;
            log.error("Error processing deposit request for {} {}: {}\n", trade.getClass().getSimpleName(), trade.getId(), t.getMessage(), t);
            trade.setStateIfValidTransitionTo(Trade.State.PUBLISH_DEPOSIT_TX_REQUEST_FAILED);
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
        TradePeer sender = trade.getTradePeer(processModel.getTempTradePeerNodeAddress());
        if (sender == null) throw new RuntimeException(request.getClass().getSimpleName() + " is not from maker, taker, or arbitrator");
        PubKeyRing senderPubKeyRing = sender.getPubKeyRing();

        // verify signature
        if (!HavenoUtils.isSignatureValid(senderPubKeyRing, contractAsJson, signature)) {
            throw new RuntimeException("Peer's contract signature is invalid");
        }

        // set peer's signature
        sender.setContractSignature(signature);

        // subscribe to trade state once to send responses with ack or nack
        if (!hasBothContractSignatures()) {
            trade.stateProperty().addListener((obs, oldState, newState) -> {
                if (oldState == newState) return;
                if (newState == Trade.State.PUBLISH_DEPOSIT_TX_REQUEST_FAILED) {
                    sendDepositResponsesOnce(trade.getProcessModel().error == null ? "Arbitrator failed to publish deposit txs within timeout for trade " + trade.getId() : trade.getProcessModel().error.getMessage());
                } else if (newState.ordinal() >= Trade.State.ARBITRATOR_PUBLISHED_DEPOSIT_TXS.ordinal()) {
                    sendDepositResponsesOnce(null);
                }
            });
        }

        // collect expected values
        Offer offer = trade.getOffer();
        boolean isFromTaker = sender == trade.getTaker();
        boolean isFromBuyer = sender == trade.getBuyer();
        BigInteger tradeFee = isFromTaker ? trade.getTakerFee() : trade.getMakerFee();
        BigInteger sendTradeAmount =  isFromBuyer ? BigInteger.ZERO : trade.getAmount();
        BigInteger securityDepositBeforeMiningFee = isFromBuyer ? trade.getBuyerSecurityDepositBeforeMiningFee() : trade.getSellerSecurityDepositBeforeMiningFee();
        String depositAddress = processModel.getMultisigAddress();
        sender.setSecurityDeposit(securityDepositBeforeMiningFee);

        // verify deposit tx
        boolean isFromBuyerAsTakerWithoutDeposit = isFromBuyer && isFromTaker && trade.hasBuyerAsTakerWithoutDeposit();
        if (!isFromBuyerAsTakerWithoutDeposit) {
            try {
                MoneroTx verifiedTx = trade.getXmrWalletService().verifyDepositTx(
                        offer.getId(),
                        tradeFee,
                        trade.getProcessModel().getTradeFeeAddress(),
                        sendTradeAmount,
                        securityDepositBeforeMiningFee,
                        depositAddress,
                        sender.getDepositTxHash(),
                        request.getDepositTxHex(),
                        request.getDepositTxKey(),
                        null);

                // TODO: it seems a deposit tx had 0 fee once?
                if (BigInteger.ZERO.equals(verifiedTx.getFee())) {
                    String errorMessage = "Deposit transaction from " + (isFromTaker ? "taker" : "maker") + " has 0 fee for trade " + trade.getId() + ". This should never happen.";
                    log.warn(errorMessage + "\n" + verifiedTx);
                    throw new RuntimeException(errorMessage);
                }

                // update trade state
                sender.setSecurityDeposit(securityDepositBeforeMiningFee.subtract(verifiedTx.getFee())); // subtract mining fee from security deposit
                sender.setDepositTxFee(verifiedTx.getFee());
                sender.setDepositTxHex(request.getDepositTxHex());
                sender.setDepositTxKey(request.getDepositTxKey());
            } catch (Exception e) {
                throw new RuntimeException("Error processing deposit tx from " + (isFromTaker ? "taker " : "maker ") + sender.getNodeAddress() + ", offerId=" + offer.getId() + ": " + e.getMessage());
            }
        }

        // update trade state
        if (request.getPaymentAccountKey() != null) sender.setPaymentAccountKey(request.getPaymentAccountKey());
        processModel.getTradeManager().requestPersistence();

        // relay deposit txs when both requests received
        MoneroDaemon monerod = trade.getXmrWalletService().getMonerod();
        if (hasBothContractSignatures()) {

            // check timeout and extend just before relaying
            if (isTimedOut()) throw new RuntimeException("Trade protocol has timed out before relaying deposit txs for {} {}" + trade.getClass().getSimpleName() + " " + trade.getShortId());
            trade.addInitProgressStep();

            // relay deposit txs
            boolean depositTxsRelayed = false;
            List<String> txHashes = new ArrayList<>();
            try {

                // submit maker tx to pool but do not relay
                MoneroSubmitTxResult makerResult = monerod.submitTxHex(processModel.getMaker().getDepositTxHex(), true);
                if (!makerResult.isGood()) throw new RuntimeException("Error submitting maker deposit tx: " + JsonUtils.serialize(makerResult));
                txHashes.add(processModel.getMaker().getDepositTxHash());

                // submit taker tx to pool but do not relay
                if (!trade.hasBuyerAsTakerWithoutDeposit()) {
                    MoneroSubmitTxResult takerResult = monerod.submitTxHex(processModel.getTaker().getDepositTxHex(), true);
                    if (!takerResult.isGood()) throw new RuntimeException("Error submitting taker deposit tx: " + JsonUtils.serialize(takerResult));
                    txHashes.add(processModel.getTaker().getDepositTxHash());
                }

                // relay txs
                try {
                    monerod.relayTxsByHash(txHashes); // call will error if txs are already confirmed, but they're still relayed
                } catch (Exception e) {
                    log.warn("Error relaying deposit txs for trade {}. They could already be confirmed. Error={}", trade.getId(), e.getMessage());
                }
                depositTxsRelayed = true;

                // update trade state
                log.info("Arbitrator published deposit txs for trade " + trade.getId());
                trade.setStateIfValidTransitionTo(Trade.State.ARBITRATOR_PUBLISHED_DEPOSIT_TXS);
            } catch (Exception e) {
                log.warn("Arbitrator error publishing deposit txs for trade {} {}: {}\n", trade.getClass().getSimpleName(), trade.getShortId(), e.getMessage(), e);
                if (!depositTxsRelayed) {

                    // flush txs from pool
                    try {
                        monerod.flushTxPool(txHashes);
                    } catch (Exception e2) {
                        log.warn("Error flushing deposit txs from pool for trade {}: {}\n", trade.getId(), e2.getMessage(), e2);
                    }
                }
                throw e;
            }
        } else {
            if (processModel.getMaker().getDepositTxHex() == null) log.info("Arbitrator waiting for deposit request from maker for trade " + trade.getId());
            if (processModel.getTaker().getDepositTxHex() == null && !trade.hasBuyerAsTakerWithoutDeposit()) log.info("Arbitrator waiting for deposit request from taker for trade " + trade.getId());
        }
    }

    private boolean hasBothContractSignatures() {
        return processModel.getMaker().getContractSignature() != null && processModel.getTaker().getContractSignature() != null;
    }

    private boolean isTimedOut() {
        return !processModel.getTradeManager().hasOpenTrade(trade);
    }

    private synchronized void sendDepositResponsesOnce(String errorMessage) {

        // skip if sent
        if (depositResponsesSent) return;
        depositResponsesSent = true;

        // log error
        if (errorMessage != null) {
            log.warn("Sending deposit responses for tradeId={}, error={}", trade.getId(), errorMessage);
        }

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
        log.info("Sending deposit response to trader={}; offerId={}, error={}", nodeAddress, trade.getId(), response.getErrorMessage());
        processModel.getP2PService().sendEncryptedDirectMessage(nodeAddress, pubKeyRing, response, new SendDirectMessageListener() {
            @Override
            public void onArrived() {
                log.info("{} arrived: trading peer={}; offerId={}; uid={}", response.getClass().getSimpleName(), nodeAddress, trade.getId(), trade.getUid());
            }
            @Override
            public void onFault(String errorMessage) {
                log.error("Sending {} failed: uid={}; peer={}; error={}", response.getClass().getSimpleName(), nodeAddress, trade.getId(), errorMessage);
                appendToErrorMessage("Sending message failed: message=" + response + "\nerrorMessage=" + errorMessage);
            }
        });
    }
}
