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

package haveno.desktop.main.settings.preferences;


import com.google.inject.Inject;
import haveno.core.locale.LanguageUtil;
import haveno.core.support.dispute.mediation.mediator.MediatorManager;
import haveno.core.support.dispute.refund.refundagent.RefundAgentManager;
import haveno.core.user.Preferences;
import haveno.desktop.common.model.ActivatableViewModel;

import java.util.stream.Collectors;

public class PreferencesViewModel extends ActivatableViewModel {

    private final RefundAgentManager refundAgentManager;
    private final MediatorManager mediationManager;
    private final Preferences preferences;

    @Inject
    public PreferencesViewModel(Preferences preferences,
                                RefundAgentManager refundAgentManager,
                                MediatorManager mediationManager) {
        this.preferences = preferences;
        this.refundAgentManager = refundAgentManager;
        this.mediationManager = mediationManager;
    }

    boolean needsSupportLanguageWarning() {
        return !refundAgentManager.isAgentAvailableForLanguage(preferences.getUserLanguage()) ||
                !mediationManager.isAgentAvailableForLanguage(preferences.getUserLanguage());
    }

    String getArbitrationLanguages() {
        return refundAgentManager.getObservableMap().values().stream()
                .flatMap(arbitrator -> arbitrator.getLanguageCodes().stream())
                .distinct()
                .map(LanguageUtil::getDisplayName)
                .collect(Collectors.joining(", "));
    }

    public String getMediationLanguages() {
        return mediationManager.getObservableMap().values().stream()
                .flatMap(mediator -> mediator.getLanguageCodes().stream())
                .distinct()
                .map(LanguageUtil::getDisplayName)
                .collect(Collectors.joining(", "));
    }
}
