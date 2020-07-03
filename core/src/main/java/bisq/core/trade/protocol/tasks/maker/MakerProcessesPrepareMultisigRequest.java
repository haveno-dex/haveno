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

import static bisq.core.util.Validator.checkTradeId;
import static bisq.core.util.Validator.nonEmptyStringOf;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import org.bitcoinj.core.Coin;

import com.google.common.base.Charsets;

import bisq.common.taskrunner.TaskRunner;
import bisq.core.exceptions.TradePriceOutOfToleranceException;
import bisq.core.offer.Offer;
import bisq.core.support.dispute.mediation.mediator.Mediator;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.PrepareMultisigRequest;
import bisq.core.trade.protocol.TradingPeer;
import bisq.core.trade.protocol.tasks.TradeTask;
import bisq.core.user.User;
import bisq.network.p2p.NodeAddress;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MakerProcessesPrepareMultisigRequest extends TradeTask {
    @SuppressWarnings({"unused"})
    public MakerProcessesPrepareMultisigRequest(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            log.debug("current trade state " + trade.getState());
            PrepareMultisigRequest prepareMultisigRequest = (PrepareMultisigRequest) processModel.getTradeMessage();
            checkNotNull(prepareMultisigRequest);
            checkTradeId(processModel.getOfferId(), prepareMultisigRequest);

            final TradingPeer tradingPeer = processModel.getTradingPeer();
            tradingPeer.setPaymentAccountPayload(checkNotNull(prepareMultisigRequest.getPaymentAccountPayload()));

            tradingPeer.setPreparedMultisigHex(checkNotNull(prepareMultisigRequest.getPreparedMultisigHex()));
            tradingPeer.setPayoutAddressString(nonEmptyStringOf(prepareMultisigRequest.getPayoutAddressString()));
            tradingPeer.setPubKeyRing(checkNotNull(prepareMultisigRequest.getPubKeyRing()));

            tradingPeer.setAccountId(nonEmptyStringOf(prepareMultisigRequest.getAccountId()));
            trade.setTakerFeeTxId(nonEmptyStringOf(prepareMultisigRequest.getTradeFeeTxId()));

            // Taker has to sign offerId (he cannot manipulate that - so we avoid to have a challenge protocol for passing the nonce we want to get signed)
            tradingPeer.setAccountAgeWitnessNonce(trade.getId().getBytes(Charsets.UTF_8));
            tradingPeer.setAccountAgeWitnessSignature(prepareMultisigRequest.getAccountAgeWitnessSignatureOfOfferId());
            tradingPeer.setCurrentDate(prepareMultisigRequest.getCurrentDate());

            User user = checkNotNull(processModel.getUser(), "User must not be null");

            NodeAddress mediatorNodeAddress = checkNotNull(prepareMultisigRequest.getArbitratorNodeAddress(), // TODO (woodser): rename to getMediatorNodeAddress() in model?
                    "payDepositRequest.getMediatorNodeAddress() must not be null");
            trade.setMediatorNodeAddress(mediatorNodeAddress);
            Mediator mediator = checkNotNull(user.getAcceptedMediatorByAddress(mediatorNodeAddress),
                    "user.getAcceptedMediatorByAddress(mediatorNodeAddress) must not be null");
            trade.setMediatorPubKeyRing(checkNotNull(mediator.getPubKeyRing(),
                    "mediator.getPubKeyRing() must not be null"));

            Offer offer = checkNotNull(trade.getOffer(), "Offer must not be null");
            try {
                long takersTradePrice = prepareMultisigRequest.getTradePrice();
                offer.checkTradePriceTolerance(takersTradePrice);
                trade.setTradePrice(takersTradePrice);
            } catch (TradePriceOutOfToleranceException e) {
                failed(e.getMessage());
            } catch (Throwable e2) {
                failed(e2);
            }

            checkArgument(prepareMultisigRequest.getTradeAmount() > 0);
            trade.setTradeAmount(Coin.valueOf(prepareMultisigRequest.getTradeAmount()));

            trade.setTradingPeerNodeAddress(processModel.getTempTradingPeerNodeAddress());

            trade.persist();

            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }
}
