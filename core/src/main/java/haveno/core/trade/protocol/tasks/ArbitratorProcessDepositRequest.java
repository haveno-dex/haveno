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
import haveno.common.util.Tuple2;
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

    private boolean depositTxsRelayed = false;

    @SuppressWarnings({"unused"})
    public ArbitratorProcessDepositRequest(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        MoneroDaemon daemon = trade.getXmrWalletService().getDaemon();
        try {
            runInterceptHook();

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
            BigInteger sendAmount =  isFromBuyer ? BigInteger.valueOf(0) : trade.getAmount();
            BigInteger securityDeposit = isFromBuyer ? trade.getBuyerSecurityDepositBeforeMiningFee() : trade.getSellerSecurityDepositBeforeMiningFee();
            String depositAddress = processModel.getMultisigAddress();

            // verify deposit tx
            Tuple2<MoneroTx, BigInteger> txResult;
            try {
                txResult = trade.getXmrWalletService().verifyTradeTx(
                    offer.getId(),
                    tradeFee,
                    sendAmount,
                    securityDeposit,
                    depositAddress,
                    trader.getDepositTxHash(),
                    request.getDepositTxHex(),
                    request.getDepositTxKey(),
                    null);
            } catch (Exception e) {
                throw new RuntimeException("Error processing deposit tx from " + (isFromTaker ? "taker " : "maker ") + trader.getNodeAddress() + ", offerId=" + offer.getId() + ": " + e.getMessage());
            }

            // set deposit info
            trader.setSecurityDeposit(txResult.second);
            trader.setDepositTxFee(txResult.first.getFee());
            trader.setDepositTxHex(request.getDepositTxHex());
            trader.setDepositTxKey(request.getDepositTxKey());
            if (request.getPaymentAccountKey() != null) trader.setPaymentAccountKey(request.getPaymentAccountKey());

            // relay deposit txs when both available
            // TODO (woodser): add small delay so tx has head start against double spend attempts?
            if (processModel.getMaker().getDepositTxHex() != null && processModel.getTaker().getDepositTxHex() != null) {

                // relay txs
                MoneroSubmitTxResult makerResult = daemon.submitTxHex(processModel.getMaker().getDepositTxHex(), true);
                MoneroSubmitTxResult takerResult = daemon.submitTxHex(processModel.getTaker().getDepositTxHex(), true);
                if (!makerResult.isGood()) throw new RuntimeException("Error submitting maker deposit tx: " + JsonUtils.serialize(makerResult));
                if (!takerResult.isGood()) throw new RuntimeException("Error submitting taker deposit tx: " + JsonUtils.serialize(takerResult));
                daemon.relayTxsByHash(Arrays.asList(processModel.getMaker().getDepositTxHash(), processModel.getTaker().getDepositTxHash()));
                depositTxsRelayed = true;

                // update trade state
                log.info("Arbitrator submitted deposit txs for trade " + trade.getId());
                trade.setState(Trade.State.ARBITRATOR_PUBLISHED_DEPOSIT_TXS);
                processModel.getTradeManager().requestPersistence();

                // create deposit response
                DepositResponse response = new DepositResponse(
                        trade.getOffer().getId(),
                        UUID.randomUUID().toString(),
                        Version.getP2PMessageVersion(),
                        new Date().getTime(),
                        null,
                        trade.getBuyer().getSecurityDeposit().longValue(),
                        trade.getSeller().getSecurityDeposit().longValue());

                // send deposit response to maker and taker
                sendDepositResponse(trade.getMaker().getNodeAddress(), trade.getMaker().getPubKeyRing(), response);
                sendDepositResponse(trade.getTaker().getNodeAddress(), trade.getTaker().getPubKeyRing(), response);
            } else {
                if (processModel.getMaker().getDepositTxHex() == null) log.info("Arbitrator waiting for deposit request from maker for trade " + trade.getId());
                if (processModel.getTaker().getDepositTxHex() == null) log.info("Arbitrator waiting for deposit request from taker for trade " + trade.getId());
            }

            complete();
            processModel.getTradeManager().requestPersistence();
        } catch (Throwable t) {

            // handle error before deposits relayed
            if (!depositTxsRelayed) {
                try {
                    daemon.flushTxPool(processModel.getMaker().getDepositTxHash(), processModel.getTaker().getDepositTxHash());
                } catch (Exception e) {
                    e.printStackTrace();
                }

                // create deposit response with error
                DepositResponse response = new DepositResponse(
                    trade.getOffer().getId(),
                    UUID.randomUUID().toString(),
                    Version.getP2PMessageVersion(),
                    new Date().getTime(),
                    t.getMessage(),
                    trade.getBuyer().getSecurityDeposit().longValue(),
                    trade.getSeller().getSecurityDeposit().longValue());

                // send deposit response to maker and taker
                sendDepositResponse(trade.getMaker().getNodeAddress(), trade.getMaker().getPubKeyRing(), response);
                sendDepositResponse(trade.getTaker().getNodeAddress(), trade.getTaker().getPubKeyRing(), response);
            }
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
