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

package bisq.core.trade.protocol.tasks;

import bisq.common.taskrunner.TaskRunner;
import bisq.core.offer.Offer;
import bisq.core.offer.OfferDirection;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.protocol.TradingPeer;
import bisq.core.util.ParsingUtils;
import java.math.BigInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * Arbitrator verifies reserve tx from maker or taker.
 * 
 * The maker reserve tx is only verified here if this arbitrator is not
 * the original offer signer and thus does not have the original reserve tx.
 */
@Slf4j
public class ArbitratorProcessReserveTx extends TradeTask {
    @SuppressWarnings({"unused"})
    public ArbitratorProcessReserveTx(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            Offer offer = trade.getOffer();
            InitTradeRequest request = (InitTradeRequest) processModel.getTradeMessage();
            boolean isFromTaker = request.getSenderNodeAddress().equals(trade.getTaker().getNodeAddress());
            boolean isFromBuyer = isFromTaker ? offer.getDirection() == OfferDirection.SELL : offer.getDirection() == OfferDirection.BUY;
            
            // TODO (woodser): if signer online, should never be called by maker
            
            // process reserve tx with expected terms
            BigInteger tradeFee = ParsingUtils.coinToAtomicUnits(isFromTaker ? trade.getTakerFee() : offer.getMakerFee());
            BigInteger depositAmount = ParsingUtils.coinToAtomicUnits(isFromBuyer ? offer.getBuyerSecurityDeposit() : offer.getAmount().add(offer.getSellerSecurityDeposit()));
            try {
                trade.getXmrWalletService().verifyTradeTx(
                    request.getPayoutAddress(),
                    depositAmount,
                    tradeFee,
                    request.getReserveTxHash(),
                    request.getReserveTxHex(),
                    request.getReserveTxKey(),
                    null,
                    true);
            } catch (Exception e) {
                throw new RuntimeException("Error processing reserve tx from " + (isFromTaker ? "taker " : "maker ") + request.getSenderNodeAddress() + ", offerId=" + offer.getId() + ": " + e.getMessage());
            }
            
            // save reserve tx to model
            TradingPeer trader = isFromTaker ? processModel.getTaker() : processModel.getMaker();
            trader.setReserveTxHash(request.getReserveTxHash());
            trader.setReserveTxHex(request.getReserveTxHex());
            trader.setReserveTxKey(request.getReserveTxKey());
            
            // persist trade
            processModel.getTradeManager().requestPersistence();
            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }
}
