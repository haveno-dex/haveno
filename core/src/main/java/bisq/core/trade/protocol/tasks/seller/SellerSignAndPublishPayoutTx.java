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

package bisq.core.trade.protocol.tasks.seller;

import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.trade.Contract;
import bisq.core.trade.MakerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.protocol.tasks.TradeTask;
import bisq.core.util.ParsingUtils;

import bisq.common.taskrunner.TaskRunner;

import java.math.BigInteger;

import lombok.extern.slf4j.Slf4j;

import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroDestination;
import monero.wallet.model.MoneroMultisigSignResult;
import monero.wallet.model.MoneroTxSet;
import monero.wallet.model.MoneroTxWallet;

@Slf4j
public class SellerSignAndPublishPayoutTx extends TradeTask {

    @SuppressWarnings({"unused"})
    public SellerSignAndPublishPayoutTx(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            // gather relevant trade info
            XmrWalletService walletService = processModel.getProvider().getXmrWalletService();
            MoneroWallet multisigWallet = walletService.getMultisigWallet(trade.getId());
            String buyerSignedPayoutTxHex = trade.getTradingPeer().getSignedPayoutTxHex();
            Contract contract = trade.getContract();
            BigInteger sellerDepositAmount = multisigWallet.getTx(trade instanceof MakerTrade ? processModel.getMaker().getDepositTxHash() : processModel.getTaker().getDepositTxHash()).getIncomingAmount(); 	// TODO (woodser): redundancy of processModel.getPreparedDepositTxId() vs trade.getDepositTxId() necessary or avoidable?
            BigInteger buyerDepositAmount = multisigWallet.getTx(trade instanceof MakerTrade ? processModel.getTaker().getDepositTxHash() : processModel.getMaker().getDepositTxHash()).getIncomingAmount();
            BigInteger tradeAmount = ParsingUtils.coinToAtomicUnits(trade.getTradeAmount());

            // parse buyer-signed payout tx
            MoneroTxSet parsedTxSet = multisigWallet.describeTxSet(new MoneroTxSet().setMultisigTxHex(buyerSignedPayoutTxHex));
            if (parsedTxSet.getTxs() == null || parsedTxSet.getTxs().size() != 1) throw new RuntimeException("Bad buyer-signed payout tx");	// TODO (woodser): test nack
            MoneroTxWallet buyerSignedPayoutTx = parsedTxSet.getTxs().get(0);

            // verify payout tx has exactly 2 destinations
            log.info("Seller verifying buyer-signed payout tx");
            if (buyerSignedPayoutTx.getOutgoingTransfer() == null || buyerSignedPayoutTx.getOutgoingTransfer().getDestinations() == null || buyerSignedPayoutTx.getOutgoingTransfer().getDestinations().size() != 2) throw new RuntimeException("Buyer-signed payout tx does not have exactly two destinations");

            // get buyer and seller destinations (order not preserved)
            boolean buyerFirst = buyerSignedPayoutTx.getOutgoingTransfer().getDestinations().get(0).getAddress().equals(contract.getBuyerPayoutAddressString());
            MoneroDestination buyerPayoutDestination = buyerSignedPayoutTx.getOutgoingTransfer().getDestinations().get(buyerFirst ? 0 : 1);
            MoneroDestination sellerPayoutDestination = buyerSignedPayoutTx.getOutgoingTransfer().getDestinations().get(buyerFirst ? 1 : 0);

            // verify payout addresses
            if (!buyerPayoutDestination.getAddress().equals(contract.getBuyerPayoutAddressString())) throw new RuntimeException("Buyer payout address does not match contract");
            if (!sellerPayoutDestination.getAddress().equals(contract.getSellerPayoutAddressString())) throw new RuntimeException("Seller payout address does not match contract");

            // verify change address is multisig's primary address
            if (!buyerSignedPayoutTx.getChangeAmount().equals(BigInteger.ZERO) && !buyerSignedPayoutTx.getChangeAddress().equals(multisigWallet.getPrimaryAddress())) throw new RuntimeException("Change address is not multisig wallet's primary address");

            // verify sum of outputs = destination amounts + change amount
            if (!buyerSignedPayoutTx.getOutputSum().equals(buyerPayoutDestination.getAmount().add(sellerPayoutDestination.getAmount()).add(buyerSignedPayoutTx.getChangeAmount()))) throw new RuntimeException("Sum of outputs != destination amounts + change amount");

            // verify buyer destination amount is deposit amount + trade amount - 1/2 tx costs
            BigInteger txCost = buyerSignedPayoutTx.getFee().add(buyerSignedPayoutTx.getChangeAmount());
            BigInteger expectedBuyerPayout = buyerDepositAmount.add(tradeAmount).subtract(txCost.divide(BigInteger.valueOf(2)));
            if (!buyerPayoutDestination.getAmount().equals(expectedBuyerPayout)) throw new RuntimeException("Buyer destination amount is not deposit amount + trade amount - 1/2 tx costs, " + buyerPayoutDestination.getAmount() + " vs " + expectedBuyerPayout);

            // verify seller destination amount is deposit amount - trade amount - 1/2 tx costs
            BigInteger expectedSellerPayout = sellerDepositAmount.subtract(tradeAmount).subtract(txCost.divide(BigInteger.valueOf(2)));
            if (!sellerPayoutDestination.getAmount().equals(expectedSellerPayout)) throw new RuntimeException("Seller destination amount is not deposit amount - trade amount - 1/2 tx costs, " + sellerPayoutDestination.getAmount() + " vs " + expectedSellerPayout);

            // TODO (woodser): verify fee is reasonable (e.g. within 2x of fee estimate tx)

            // sign buyer-signed payout tx
            MoneroMultisigSignResult result = multisigWallet.signMultisigTxHex(buyerSignedPayoutTxHex);
            if (result.getSignedMultisigTxHex() == null) throw new RuntimeException("Error signing buyer-signed payout tx");
            String signedMultisigTxHex = result.getSignedMultisigTxHex();

            // submit fully signed payout tx to the network
            multisigWallet.submitMultisigTxHex(signedMultisigTxHex);
            
            // close multisig wallet
            walletService.closeMultisigWallet(trade.getId());

            // update trade state
            parsedTxSet.setMultisigTxHex(signedMultisigTxHex); // TODO (woodser): better place to store this?
            trade.setPayoutTx(parsedTxSet.getTxs().get(0));
            trade.setPayoutTxId(parsedTxSet.getTxs().get(0).getHash());
            trade.setState(Trade.State.SELLER_PUBLISHED_PAYOUT_TX);
            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }
}
