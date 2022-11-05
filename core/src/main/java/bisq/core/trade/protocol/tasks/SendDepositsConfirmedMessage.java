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

import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.DepositsConfirmedMessage;
import bisq.core.trade.messages.TradeMailboxMessage;
import bisq.network.p2p.NodeAddress;
import bisq.common.crypto.PubKeyRing;
import bisq.common.taskrunner.TaskRunner;

import lombok.extern.slf4j.Slf4j;
import monero.wallet.MoneroWallet;

/**
 * Send message on first confirmation to decrypt peer payment account and update multisig hex.
 */
@Slf4j
public abstract class SendDepositsConfirmedMessage extends SendMailboxMessageTask {
    private DepositsConfirmedMessage message;

    public SendDepositsConfirmedMessage(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            super.run();
        } catch (Throwable t) {
            failed(t);
        }
    }

    @Override
    protected abstract NodeAddress getReceiverNodeAddress();

    @Override
    protected abstract PubKeyRing getReceiverPubKeyRing();

    @Override
    protected TradeMailboxMessage getTradeMailboxMessage(String tradeId) {
        if (message == null) {

            // export multisig hex once
            if (trade.getSelf().getUpdatedMultisigHex() == null) {
                XmrWalletService walletService = processModel.getProvider().getXmrWalletService();
                MoneroWallet multisigWallet = walletService.getMultisigWallet(tradeId);
                trade.getSelf().setUpdatedMultisigHex(multisigWallet.exportMultisigHex());
                processModel.getTradeManager().requestPersistence();
            }

            // We do not use a real unique ID here as we want to be able to re-send the exact same message in case the
            // peer does not respond with an ACK msg in a certain time interval. To avoid that we get dangling mailbox
            // messages where only the one which gets processed by the peer would be removed we use the same uid. All
            // other data stays the same when we re-send the message at any time later.
            String deterministicId = tradeId + processModel.getMyNodeAddress().getFullAddress();
            message = new DepositsConfirmedMessage(
                    trade.getOffer().getId(),
                    processModel.getMyNodeAddress(),
                    processModel.getPubKeyRing(),
                    deterministicId,
                    getReceiverNodeAddress().equals(trade.getBuyer().getNodeAddress()) ? trade.getSeller().getPaymentAccountKey() : null, // buyer receives seller's payment account decryption key
                    trade.getSelf().getUpdatedMultisigHex());
        }
        return message;
    }

    @Override
    protected void setStateSent() {
        // no additional handling
    }

    @Override
    protected void setStateArrived() {
        // no additional handling
    }

    @Override
    protected void setStateStoredInMailbox() {
        // no additional handling
    }

    @Override
    protected void setStateFault() {
        // no additional handling
    }
}
