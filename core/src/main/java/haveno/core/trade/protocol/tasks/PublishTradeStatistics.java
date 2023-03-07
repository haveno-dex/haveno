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
import haveno.core.offer.OfferPayload;
import haveno.core.trade.Trade;
import haveno.core.trade.statistics.TradeStatistics2;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.network.NetworkNode;
import haveno.network.p2p.network.TorNetworkNode;
import java.util.HashMap;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public class PublishTradeStatistics extends TradeTask {
    public PublishTradeStatistics(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            checkNotNull(trade.getMakerDepositTx());
            checkNotNull(trade.getTakerDepositTx());

            Map<String, String> extraDataMap = new HashMap<>();
            if (processModel.getReferralIdService().getOptionalReferralId().isPresent()) {
                extraDataMap.put(OfferPayload.REFERRAL_ID, processModel.getReferralIdService().getOptionalReferralId().get());
            }

            NodeAddress mediatorNodeAddress = checkNotNull(trade.getArbitrator().getNodeAddress());
            // The first 4 chars are sufficient to identify a mediator.
            // For testing with regtest/localhost we use the full address as its localhost and would result in
            // same values for multiple mediators.
            NetworkNode networkNode = model.getProcessModel().getP2PService().getNetworkNode();
            String address = networkNode instanceof TorNetworkNode ?
                    mediatorNodeAddress.getFullAddress().substring(0, 4) :
                    mediatorNodeAddress.getFullAddress();
            extraDataMap.put(TradeStatistics2.ARBITRATOR_ADDRESS, address);

            Offer offer = checkNotNull(trade.getOffer());
            TradeStatistics2 tradeStatistics = new TradeStatistics2(offer.getOfferPayload(),
                trade.getPrice(),
                trade.getAmount(),
                trade.getDate(),
                trade.getMaker().getDepositTxHash(),
                trade.getTaker().getDepositTxHash(),
                extraDataMap);
            processModel.getP2PService().addPersistableNetworkPayload(tradeStatistics, true);

            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }
}
