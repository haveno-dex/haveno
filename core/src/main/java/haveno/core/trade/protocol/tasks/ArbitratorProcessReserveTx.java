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

import haveno.common.taskrunner.TaskRunner;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.trade.messages.InitTradeRequest;
import haveno.core.trade.protocol.TradePeer;
import lombok.extern.slf4j.Slf4j;
import monero.daemon.model.MoneroTx;

import java.math.BigInteger;

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
            TradePeer sender = trade.getTradePeer(processModel.getTempTradePeerNodeAddress());
            boolean isFromMaker = sender == trade.getMaker();
            boolean isFromBuyer = isFromMaker ? offer.getDirection() == OfferDirection.BUY : offer.getDirection() == OfferDirection.SELL;

            // TODO (woodser): if signer online, should never be called by maker?

            // process reserve tx with expected values
            BigInteger penaltyFee = HavenoUtils.multiply(isFromMaker ? offer.getAmount() : trade.getAmount(), offer.getPenaltyFeePct());
            BigInteger tradeFee = isFromMaker ? offer.getMaxMakerFee() : trade.getTakerFee();
            BigInteger sendAmount =  isFromBuyer ? BigInteger.ZERO : isFromMaker ? offer.getAmount() : trade.getAmount(); // maker reserve tx is for offer amount
            BigInteger securityDeposit = isFromMaker ? isFromBuyer ? offer.getMaxBuyerSecurityDeposit() : offer.getMaxSellerSecurityDeposit() : isFromBuyer ? trade.getBuyerSecurityDepositBeforeMiningFee() : trade.getSellerSecurityDepositBeforeMiningFee();
            MoneroTx verifiedTx;
            try {
                verifiedTx = trade.getXmrWalletService().verifyReserveTx(
                    offer.getId(),
                    penaltyFee,
                    tradeFee,
                    sendAmount,
                    securityDeposit,
                    request.getPayoutAddress(),
                    request.getReserveTxHash(),
                    request.getReserveTxHex(),
                    request.getReserveTxKey(),
                    null);
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException("Error processing reserve tx from " + (isFromMaker ? "maker " : "taker ") + processModel.getTempTradePeerNodeAddress() + ", offerId=" + offer.getId() + ": " + e.getMessage());
            }

            // save reserve tx to model
            TradePeer trader = isFromMaker ? processModel.getMaker() : processModel.getTaker();
            trader.setSecurityDeposit(securityDeposit.subtract(verifiedTx.getFee())); // subtract mining fee from security deposit
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
