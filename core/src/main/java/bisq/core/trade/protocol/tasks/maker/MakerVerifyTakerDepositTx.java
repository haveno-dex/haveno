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

import static com.google.common.base.Preconditions.checkNotNull;

import bisq.common.taskrunner.TaskRunner;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.DepositTxMessage;
import bisq.core.trade.protocol.tasks.TradeTask;
import bisq.core.util.Validator;
import common.utils.GenUtils;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroOutputWallet;
import monero.wallet.model.MoneroTxWallet;
import monero.wallet.model.MoneroWalletListener;

@Slf4j
public class MakerVerifyTakerDepositTx extends TradeTask {

    public MakerVerifyTakerDepositTx(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            
            // get message
            DepositTxMessage message = (DepositTxMessage) processModel.getTradeMessage();
            Validator.checkTradeId(processModel.getOfferId(), message);
            checkNotNull(message);
            trade.setTradingPeerNodeAddress(processModel.getTempTradingPeerNodeAddress());
            if (trade.getMakerDepositTxId() != null) throw new RuntimeException("Maker deposit tx already set for trade " + trade.getId() +  ", this should not happen"); // TODO: ignore and nack bad requests to not show on client
            
            // verify deposit tx id
            if (message.getDepositTxId() == null) throw new RuntimeException("Taker must provide deposit tx id");
            
            // get multisig wallet
            XmrWalletService walletService = processModel.getProvider().getXmrWalletService();
            MoneroWallet multisigWallet = walletService.getOrCreateMultisigWallet(processModel.getTrade().getId());            
            
            // wait until wallet sees taker deposit tx
            MoneroTxWallet takerDepositTx = null;
            while (takerDepositTx == null) {
                try {
                    takerDepositTx = multisigWallet.getTx(message.getDepositTxId());
                } catch (Exception e) {
                    
                }
                GenUtils.waitFor(1000); // TODO (woodser): better way to wait for notification, use listener
            }
            
            // TODO (woodser): verify taker deposit tx

            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }
}
