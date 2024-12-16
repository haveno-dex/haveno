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

package haveno.desktop.main.account.register.mediator;


import com.google.inject.Inject;
import com.google.inject.name.Named;
import haveno.common.config.Config;
import haveno.core.locale.Res;
import haveno.core.support.dispute.mediation.mediator.Mediator;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.main.account.register.AgentRegistrationView;

@FxmlView
public class MediatorRegistrationView extends AgentRegistrationView<Mediator, MediatorRegistrationViewModel> {

    @Inject
    public MediatorRegistrationView(MediatorRegistrationViewModel model,
                                    @Named(Config.USE_DEV_PRIVILEGE_KEYS) boolean useDevPrivilegeKeys) {
        super(model, useDevPrivilegeKeys);
    }

    @Override
    protected String getRole() {
        return Res.get("shared.mediator");
    }
}
