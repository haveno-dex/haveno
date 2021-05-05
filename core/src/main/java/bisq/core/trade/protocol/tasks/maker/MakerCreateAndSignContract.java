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

import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.trade.BuyerAsMakerTrade;
import bisq.core.trade.Contract;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.DepositTxMessage;
import bisq.core.trade.protocol.TradingPeer;
import bisq.core.trade.protocol.tasks.TradeTask;

import bisq.network.p2p.NodeAddress;

import bisq.common.crypto.Hash;
import bisq.common.crypto.Sig;
import bisq.common.taskrunner.TaskRunner;
import bisq.common.util.Utilities;

import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public class MakerCreateAndSignContract extends TradeTask {
    public MakerCreateAndSignContract(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            DepositTxMessage message = (DepositTxMessage) processModel.getTradeMessage();
            trade.setTakerFeeTxId(message.getTradeFeeTxId()); // TODO (woodser): must verify trade fee tx. set up contract before taker deposits?
            //String takerFeeTxId = checkNotNull(processModel.getTakeOfferFeeTxId());

            TradingPeer taker = processModel.getTradingPeer();
            boolean isBuyerMakerAndSellerTaker = trade instanceof BuyerAsMakerTrade;
            NodeAddress buyerNodeAddress = isBuyerMakerAndSellerTaker ? processModel.getMyNodeAddress() : processModel.getTempTradingPeerNodeAddress();
            NodeAddress sellerNodeAddress = isBuyerMakerAndSellerTaker ? processModel.getTempTradingPeerNodeAddress() : processModel.getMyNodeAddress();
            XmrWalletService walletService = processModel.getProvider().getXmrWalletService();
            String id = processModel.getOffer().getId();

            // get maker payout address
            XmrAddressEntry makerPayoutEntry = walletService.getOrCreateAddressEntry(id, XmrAddressEntry.Context.TRADE_PAYOUT);
            checkNotNull(taker.getPayoutAddressString(), "taker.getPayoutAddressString()");

//            AddressEntry makerAddressEntry = walletService.getOrCreateAddressEntry(id, AddressEntry.Context.MULTI_SIG);
//            byte[] makerMultiSigPubKey = makerAddressEntry.getPubKey();

            Contract contract = new Contract(
                    processModel.getOffer().getOfferPayload(),
                    checkNotNull(trade.getTradeAmount()).value,
                    trade.getTradePrice().getValue(),
                    //takerFeeTxId, // TODO (woodser): include taker fee tx id?
                    buyerNodeAddress,
                    sellerNodeAddress,
                    trade.getArbitratorNodeAddress(),
                    isBuyerMakerAndSellerTaker,
                    processModel.getAccountId(),
                    checkNotNull(taker.getAccountId()),
                    checkNotNull(processModel.getPaymentAccountPayload(trade)),
                    checkNotNull(taker.getPaymentAccountPayload()),
                    processModel.getPubKeyRing(),
                    checkNotNull(taker.getPubKeyRing()),
                    makerPayoutEntry.getAddressString(),
                    checkNotNull(taker.getPayoutAddressString()),
                    trade.getLockTime()
            );
            String contractAsJson = Utilities.objectToJson(contract);
            String signature = Sig.sign(processModel.getKeyRing().getSignatureKeyPair().getPrivate(), contractAsJson);

            trade.setContract(contract);
            trade.setContractAsJson(contractAsJson);
            trade.setMakerContractSignature(signature);

            byte[] contractHash = Hash.getSha256Hash(checkNotNull(trade.getContractAsJson()));
            trade.setContractHash(contractHash);


            processModel.getTradeManager().requestPersistence();

            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }
}
