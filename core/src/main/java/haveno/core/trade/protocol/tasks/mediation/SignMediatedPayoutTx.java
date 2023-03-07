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

package haveno.core.trade.protocol.tasks.mediation;

import haveno.common.taskrunner.TaskRunner;
import haveno.core.trade.Trade;
import haveno.core.trade.protocol.tasks.TradeTask;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SignMediatedPayoutTx extends TradeTask {

    public SignMediatedPayoutTx(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            throw new RuntimeException("SignMediatedPayoutTx not implemented for xmr");

//            TradePeer tradePeer = trade.getTradePeer();
//            if (processModel.getMediatedPayoutTxSignature() != null) {
//                log.warn("processModel.getTxSignatureFromMediation is already set");
//            }
//
//            String tradeId = trade.getId();
//            BtcWalletService walletService = processModel.getBtcWalletService();
//            Transaction depositTx = checkNotNull(trade.getDepositTx(), "trade.getDepositTx() must not be null");
//            Offer offer = checkNotNull(trade.getOffer(), "offer must not be null");
//            Coin tradeAmount = checkNotNull(trade.getTradeAmount(), "tradeAmount must not be null");
//            Contract contract = checkNotNull(trade.getContract(), "contract must not be null");
//
//            Coin totalPayoutAmount = offer.getBuyerSecurityDeposit().add(tradeAmount).add(offer.getSellerSecurityDeposit());
//            Coin buyerPayoutAmount = Coin.valueOf(processModel.getBuyerPayoutAmountFromMediation());
//            Coin sellerPayoutAmount = Coin.valueOf(processModel.getSellerPayoutAmountFromMediation());
//
//            checkArgument(totalPayoutAmount.equals(buyerPayoutAmount.add(sellerPayoutAmount)),
//                    "Payout amount does not match buyerPayoutAmount=" + buyerPayoutAmount.toFriendlyString() +
//                            "; sellerPayoutAmount=" + sellerPayoutAmount);
//
//            boolean isMyRoleBuyer = contract.isMyRoleBuyer(processModel.getPubKeyRing());
//
//            String myPayoutAddressString = walletService.getOrCreateAddressEntry(tradeId, AddressEntry.Context.TRADE_PAYOUT).getAddressString();
//            String peersPayoutAddressString = tradePeer.getPayoutAddressString();
//            String buyerPayoutAddressString = isMyRoleBuyer ? myPayoutAddressString : peersPayoutAddressString;
//            String sellerPayoutAddressString = isMyRoleBuyer ? peersPayoutAddressString : myPayoutAddressString;
//
//            byte[] myMultiSigPubKey = processModel.getMyMultiSigPubKey();
//            byte[] peersMultiSigPubKey = tradePeer.getMultiSigPubKey();
//            byte[] buyerMultiSigPubKey = isMyRoleBuyer ? myMultiSigPubKey : peersMultiSigPubKey;
//            byte[] sellerMultiSigPubKey = isMyRoleBuyer ? peersMultiSigPubKey : myMultiSigPubKey;
//
//            DeterministicKey myMultiSigKeyPair = walletService.getMultiSigKeyPair(tradeId, myMultiSigPubKey);
//
//            checkArgument(Arrays.equals(myMultiSigPubKey,
//                    walletService.getOrCreateAddressEntry(tradeId, AddressEntry.Context.MULTI_SIG).getPubKey()),
//                    "myMultiSigPubKey from AddressEntry must match the one from the trade data. trade id =" + tradeId);
//
//            byte[] mediatedPayoutTxSignature = processModel.getTradeWalletService().signMediatedPayoutTx(
//                    depositTx,
//                    buyerPayoutAmount,
//                    sellerPayoutAmount,
//                    buyerPayoutAddressString,
//                    sellerPayoutAddressString,
//                    myMultiSigKeyPair,
//                    buyerMultiSigPubKey,
//                    sellerMultiSigPubKey);
//            processModel.setMediatedPayoutTxSignature(mediatedPayoutTxSignature);
//
//            processModel.getTradeManager().requestPersistence();
//
//            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }
}

