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

package haveno.desktop.main.account.register.refundagent;


import com.google.inject.Inject;
import haveno.common.crypto.KeyRing;
import haveno.core.support.dispute.refund.refundagent.RefundAgent;
import haveno.core.support.dispute.refund.refundagent.RefundAgentManager;
import haveno.core.user.User;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.main.account.register.AgentRegistrationViewModel;
import haveno.network.p2p.P2PService;

import java.util.ArrayList;
import java.util.Date;

public class RefundAgentRegistrationViewModel extends AgentRegistrationViewModel<RefundAgent, RefundAgentManager> {

    @Inject
    public RefundAgentRegistrationViewModel(RefundAgentManager arbitratorManager,
                                            User user,
                                            P2PService p2PService,
                                            XmrWalletService xmrWalletService,
                                            KeyRing keyRing) {
        super(arbitratorManager, user, p2PService, xmrWalletService, keyRing);
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
