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

package haveno.desktop.main.account.register.refundagent;


import haveno.desktop.main.account.register.AgentRegistrationViewModel;

import haveno.core.btc.wallet.BtcWalletService;
import haveno.core.support.dispute.refund.refundagent.RefundAgent;
import haveno.core.support.dispute.refund.refundagent.RefundAgentManager;
import haveno.core.user.User;

import haveno.network.p2p.P2PService;

import haveno.common.crypto.KeyRing;

import com.google.inject.Inject;

import java.util.ArrayList;
import java.util.Date;

public class RefundAgentRegistrationViewModel extends AgentRegistrationViewModel<RefundAgent, RefundAgentManager> {

    @Inject
    public RefundAgentRegistrationViewModel(RefundAgentManager arbitratorManager,
                                            User user,
                                            P2PService p2PService,
                                            BtcWalletService walletService,
                                            KeyRing keyRing) {
        super(arbitratorManager, user, p2PService, walletService, keyRing);
    }

    @Override
    protected RefundAgent getDisputeAgent(String registrationSignature,
                                          String emailAddress) {
        return new RefundAgent(
                p2PService.getAddress(),
                keyRing.getPubKeyRing(),
                new ArrayList<>(languageCodes),
                new Date().getTime(),
                registrationKey.getPubKey(),
                registrationSignature,
                emailAddress,
                null,
                null
        );
    }

    @Override
    protected RefundAgent getRegisteredDisputeAgentFromUser() {
        return user.getRegisteredRefundAgent();
    }
}
