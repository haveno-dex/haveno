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

package bisq.core.trade.protocol.tasks.buyer_as_taker;

import bisq.core.trade.Trade;
import bisq.core.trade.protocol.tasks.TradeTask;

import bisq.common.taskrunner.TaskRunner;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BuyerAsTakerSignsDepositTx extends TradeTask {

    public BuyerAsTakerSignsDepositTx(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            throw new RuntimeException("BuyerAsTakerSignsDepositTx not implemented for xmr");

           /* log.debug("\n\n------------------------------------------------------------\n"
                    + "Contract as json\n"
                    + trade.getContractAsJson()
                    + "\n------------------------------------------------------------\n");*/


//            byte[] contractHash = Hash.getSha256Hash(checkNotNull(trade.getContractAsJson()));
//            trade.setContractHash(contractHash);
//            List<RawTransactionInput> buyerInputs = checkNotNull(processModel.getRawTransactionInputs(), "buyerInputs must not be null");
//            BtcWalletService walletService = processModel.getBtcWalletService();
//            String id = processModel.getOffer().getId();
//
//            Optional<AddressEntry> addressEntryOptional = walletService.getAddressEntry(id, AddressEntry.Context.MULTI_SIG);
//            checkArgument(addressEntryOptional.isPresent(), "addressEntryOptional must be present");
//            AddressEntry buyerMultiSigAddressEntry = addressEntryOptional.get();
//            Coin buyerInput = Coin.valueOf(buyerInputs.stream().mapToLong(input -> input.value).sum());
//
//            buyerMultiSigAddressEntry.setCoinLockedInMultiSig(buyerInput.subtract(trade.getTxFee().multiply(2)));
//            walletService.saveAddressEntryList();
//
//            TradingPeer tradingPeer = trade.getTradingPeer();
//            byte[] buyerMultiSigPubKey = processModel.getMyMultiSigPubKey();
//            checkArgument(Arrays.equals(buyerMultiSigPubKey, buyerMultiSigAddressEntry.getPubKey()),
//                    "buyerMultiSigPubKey from AddressEntry must match the one from the trade data. trade id =" + id);
//
//            List<RawTransactionInput> sellerInputs = checkNotNull(tradingPeer.getRawTransactionInputs());
//            byte[] sellerMultiSigPubKey = tradingPeer.getMultiSigPubKey();
//            Transaction depositTx = processModel.getTradeWalletService().takerSignsDepositTx(
//                    false,
//                    contractHash,
//                    processModel.getPreparedDepositTx(),
//                    buyerInputs,
//                    sellerInputs,
//                    buyerMultiSigPubKey,
//                    sellerMultiSigPubKey);
//            processModel.setDepositTx(depositTx);
//
//            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }
}
