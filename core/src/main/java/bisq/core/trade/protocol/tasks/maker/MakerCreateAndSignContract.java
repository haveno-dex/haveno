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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Preconditions;

import bisq.common.crypto.Sig;
import bisq.common.taskrunner.TaskRunner;
import bisq.common.util.Utilities;
import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.trade.BuyerAsMakerTrade;
import bisq.core.trade.Contract;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.DepositTxMessage;
import bisq.core.trade.protocol.TradingPeer;
import bisq.core.trade.protocol.tasks.TradeTask;
import bisq.network.p2p.NodeAddress;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MakerCreateAndSignContract extends TradeTask {
    @SuppressWarnings({"unused"})
    public MakerCreateAndSignContract(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            
            DepositTxMessage message = (DepositTxMessage) processModel.getTradeMessage();
            trade.setTakerFeeTxId(message.getTradeFeeTxId()); // TODO (woodser): must verify trade fee tx
            Preconditions.checkNotNull(trade.getTakerFeeTxId(), "trade.getTakeOfferFeeTxId() must not be null");
            
            TradingPeer taker = processModel.getTradingPeer();
            PaymentAccountPayload makerPaymentAccountPayload = processModel.getPaymentAccountPayload(trade);
            checkNotNull(makerPaymentAccountPayload, "makerPaymentAccountPayload must not be null");
            PaymentAccountPayload takerPaymentAccountPayload = checkNotNull(taker.getPaymentAccountPayload());
            boolean isBuyerMakerAndSellerTaker = trade instanceof BuyerAsMakerTrade;

            NodeAddress buyerNodeAddress = isBuyerMakerAndSellerTaker ? processModel.getMyNodeAddress() : processModel.getTempTradingPeerNodeAddress();
            NodeAddress sellerNodeAddress = isBuyerMakerAndSellerTaker ? processModel.getTempTradingPeerNodeAddress() : processModel.getMyNodeAddress();
            XmrWalletService walletService = processModel.getXmrWalletService();
            String id = processModel.getOffer().getId();
            
            // get maker payout address
            XmrAddressEntry makerPayoutEntry = walletService.getOrCreateAddressEntry(id, XmrAddressEntry.Context.TRADE_PAYOUT);
            checkNotNull(taker.getPayoutAddressString(), "taker.getPayoutAddressString()");

//            checkArgument(!walletService.getAddressEntry(id, XmrAddressEntry.Context.MULTI_SIG).isPresent(), "addressEntry must not be set here.");
//            XmrAddressEntry makerAddressEntry = walletService.getOrCreateAddressEntry(id, XmrAddressEntry.Context.MULTI_SIG);
//            byte[] makerMultiSigPubKey = makerAddressEntry.getPubKey();

            checkNotNull(trade.getTradeAmount(), "trade.getTradeAmount() must not be null");
            Contract contract = new Contract(
                    processModel.getOffer().getOfferPayload(),
                    trade.getTradeAmount().value,
                    trade.getTradePrice().getValue(),
                    //trade.getTakerFeeTxId(),
                    buyerNodeAddress,
                    sellerNodeAddress,
                    trade.getArbitratorNodeAddress(),
                    isBuyerMakerAndSellerTaker,
                    processModel.getAccountId(),
                    taker.getAccountId(),
                    makerPaymentAccountPayload,
                    takerPaymentAccountPayload,
                    processModel.getPubKeyRing(),
                    taker.getPubKeyRing(),
                    makerPayoutEntry.getAddressString(),
                    taker.getPayoutAddressString(),
                    trade.getLockTime()
            );
            String contractAsJson = Utilities.objectToJson(contract);
            String signature = Sig.sign(processModel.getKeyRing().getSignatureKeyPair().getPrivate(), contractAsJson);

            trade.setContract(contract);
            trade.setContractAsJson(contractAsJson);
            trade.setMakerContractSignature(signature);

            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }
}
