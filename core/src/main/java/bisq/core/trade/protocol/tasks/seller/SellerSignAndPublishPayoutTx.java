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

package bisq.core.trade.protocol.tasks.seller;

import static com.google.common.base.Preconditions.checkNotNull;

import java.math.BigInteger;

import bisq.common.taskrunner.TaskRunner;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.offer.Offer;
import bisq.core.trade.Contract;
import bisq.core.trade.MakerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.protocol.tasks.TradeTask;
import bisq.core.util.ParsingUtils;
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
            MoneroWallet multisigWallet = walletService.getOrCreateMultisigWallet(processModel.getTrade().getId());
            String buyerSignedPayoutTxHex = processModel.getTradingPeer().getSignedPayoutTxHex();
            Contract contract = trade.getContract();
            Offer offer = checkNotNull(trade.getOffer(), "offer must not be null");
            BigInteger sellerDepositAmount = multisigWallet.getTx(trade instanceof MakerTrade ? processModel.getMakerPreparedDepositTxId() : processModel.getTakerPreparedDepositTxId()).getIncomingAmount(); 	// TODO (woodser): redundancy of processModel.getPreparedDepositTxId() vs trade.getDepositTxId() necessary or avoidable?
            BigInteger buyerDepositAmount = multisigWallet.getTx(trade instanceof MakerTrade ? processModel.getTakerPreparedDepositTxId() : processModel.getMakerPreparedDepositTxId()).getIncomingAmount();
            BigInteger tradeAmount = BigInteger.valueOf(trade.getTradeAmount().value).multiply(ParsingUtils.XMR_SATOSHI_MULTIPLIER);
            
            System.out.println("SELLER VERIFYING PAYOUT TX");
            System.out.println("Trade amount: " + trade.getTradeAmount());
            System.out.println("Buyer deposit amount: " + buyerDepositAmount);
            System.out.println("Seller deposit amount: " + sellerDepositAmount);
            
            BigInteger buyerPayoutAmount = BigInteger.valueOf(offer.getBuyerSecurityDeposit().add(trade.getTradeAmount()).value).multiply(ParsingUtils.XMR_SATOSHI_MULTIPLIER);
            System.out.println("Buyer payout amount (with multiplier): " + buyerPayoutAmount);
            BigInteger sellerPayoutAmount = BigInteger.valueOf(offer.getSellerSecurityDeposit().value).multiply(ParsingUtils.XMR_SATOSHI_MULTIPLIER);
            System.out.println("Seller payout amount (with multiplier): " + sellerPayoutAmount);
            
            // parse buyer-signed payout tx
            MoneroTxSet parsedTxSet = multisigWallet.parseTxSet(new MoneroTxSet().setMultisigTxHex(buyerSignedPayoutTxHex));
            if (parsedTxSet.getTxs().get(0).getTxSet() != parsedTxSet) System.out.println("LINKS ARE WRONG STRAIGHT FROM PARSING!!!");
            if (parsedTxSet.getTxs() == null || parsedTxSet.getTxs().size() != 1) throw new RuntimeException("Bad buyer-signed payout tx");	// TODO (woodser): nack
            MoneroTxWallet buyerSignedPayoutTx = parsedTxSet.getTxs().get(0);
            System.out.println("Parsed buyer signed tx hex:\n" + buyerSignedPayoutTx);
            
            // verify payout tx has exactly 2 destinations
            if (buyerSignedPayoutTx.getOutgoingTransfer() == null || buyerSignedPayoutTx.getOutgoingTransfer().getDestinations() == null || buyerSignedPayoutTx.getOutgoingTransfer().getDestinations().size() != 2) throw new RuntimeException("Buyer-signed payout tx does not have exactly two destinations");
            
            // get buyer and seller destinations (order not preserved)
            boolean buyerFirst = buyerSignedPayoutTx.getOutgoingTransfer().getDestinations().get(0).getAddress().equals(contract.getBuyerPayoutAddressString());
            MoneroDestination buyerPayoutDestination = buyerSignedPayoutTx.getOutgoingTransfer().getDestinations().get(buyerFirst ? 0 : 1);
            MoneroDestination sellerPayoutDestination = buyerSignedPayoutTx.getOutgoingTransfer().getDestinations().get(buyerFirst ? 1 : 0);
            
            // verify payout addresses
            if (!buyerPayoutDestination.getAddress().equals(contract.getBuyerPayoutAddressString())) throw new RuntimeException("Buyer payout address does not match contract");
            if (!sellerPayoutDestination.getAddress().equals(contract.getSellerPayoutAddressString())) throw new RuntimeException("Seller payout address does not match contract");
            
            // verify change address is multisig's primary address
            if (!buyerSignedPayoutTx.getChangeAddress().equals(multisigWallet.getPrimaryAddress())) throw new RuntimeException("Change address is not multisig wallet's primary address");
            
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
            
            // update state
            parsedTxSet.setMultisigTxHex(signedMultisigTxHex);
            if (parsedTxSet.getTxs().get(0).getTxSet() != parsedTxSet) System.out.println("LINKS ARE WRONG!!!");
	          trade.setPayoutTx(parsedTxSet.getTxs().get(0));
	          trade.setPayoutTxId(parsedTxSet.getTxs().get(0).getHash());
	          trade.setState(Trade.State.SELLER_PUBLISHED_PAYOUT_TX);
	          complete();
            
//            checkNotNull(trade.getTradeAmount(), "trade.getTradeAmount() must not be null");
//
//            Offer offer = trade.getOffer();
//            TradingPeer tradingPeer = processModel.getTradingPeer();
//            BtcWalletService walletService = processModel.getBtcWalletService();
//            String id = processModel.getOffer().getId();
//
//            final byte[] buyerSignature = tradingPeer.getSignature();
//
//            Coin buyerPayoutAmount = checkNotNull(offer.getBuyerSecurityDeposit()).add(trade.getTradeAmount());
//            Coin sellerPayoutAmount = offer.getSellerSecurityDeposit();
//
//            final String buyerPayoutAddressString = tradingPeer.getPayoutAddressString();
//            String sellerPayoutAddressString = walletService.getOrCreateAddressEntry(id,
//                    AddressEntry.Context.TRADE_PAYOUT).getAddressString();
//
//            final byte[] buyerMultiSigPubKey = tradingPeer.getMultiSigPubKey();
//            byte[] sellerMultiSigPubKey = processModel.getMyMultiSigPubKey();
//
//            Optional<AddressEntry> multiSigAddressEntryOptional = walletService.getAddressEntry(id,
//                    AddressEntry.Context.MULTI_SIG);
//            if (!multiSigAddressEntryOptional.isPresent() || !Arrays.equals(sellerMultiSigPubKey,
//                    multiSigAddressEntryOptional.get().getPubKey())) {
//                // In some error edge cases it can be that the address entry is not marked (or was unmarked).
//                // We do not want to fail in that case and only report a warning.
//                // One case where that helped to avoid a failed payout attempt was when the taker had a power failure
//                // at the moment when the offer was taken. This caused first to not see step 1 in the trade process
//                // (all greyed out) but after the deposit tx was confirmed the trade process was on step 2 and
//                // everything looked ok. At the payout multiSigAddressEntryOptional was not present and payout
//                // could not be done. By changing the previous behaviour from fail if multiSigAddressEntryOptional
//                // is not present to only log a warning the payout worked.
//                log.warn("sellerMultiSigPubKey from AddressEntry does not match the one from the trade data. " +
//                        "Trade id ={}, multiSigAddressEntryOptional={}", id, multiSigAddressEntryOptional);
//            }
//
//            DeterministicKey multiSigKeyPair = walletService.getMultiSigKeyPair(id, sellerMultiSigPubKey);
//
//            Transaction transaction = processModel.getTradeWalletService().sellerSignsAndFinalizesPayoutTx(
//                    checkNotNull(trade.getDepositTx()),
//                    buyerSignature,
//                    buyerPayoutAmount,
//                    sellerPayoutAmount,
//                    buyerPayoutAddressString,
//                    sellerPayoutAddressString,
//                    multiSigKeyPair,
//                    buyerMultiSigPubKey,
//                    sellerMultiSigPubKey
//            );
//
//            trade.setPayoutTx(transaction);
//
//            walletService.swapTradeEntryToAvailableEntry(id, AddressEntry.Context.MULTI_SIG);
//
//            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }
}
