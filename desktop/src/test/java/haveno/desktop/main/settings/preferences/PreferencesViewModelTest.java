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

package haveno.desktop.main.settings.preferences;

import haveno.core.support.dispute.arbitration.arbitrator.Arbitrator;
import haveno.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import haveno.core.support.dispute.mediation.mediator.Mediator;
import haveno.core.support.dispute.mediation.mediator.MediatorManager;
import haveno.core.user.Preferences;
import haveno.desktop.maker.PreferenceMakers;
import haveno.network.p2p.NodeAddress;
import javafx.collections.FXCollections;
import javafx.collections.ObservableMap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PreferencesViewModelTest {

    @Test
    public void getArbitrationLanguages() {

        ArbitratorManager arbitratorAgentManager = mock(ArbitratorManager.class);

        final ObservableMap<NodeAddress, Arbitrator> arbitrators = FXCollections.observableHashMap();

        ArrayList<String> languagesOne = new ArrayList<>() {{
            add("en");
            add("de");
        }};

        ArrayList<String> languagesTwo = new ArrayList<>() {{
            add("en");
            add("es");
        }};

        Arbitrator one = new Arbitrator(new NodeAddress("refundAgent:1"), null, languagesOne, 0L,
                null, null, null, null, null);

        Arbitrator two = new Arbitrator(new NodeAddress("refundAgent:2"), null, languagesTwo, 0L,
                null, null, null, null, null);

        arbitrators.put(one.getNodeAddress(), one);
        arbitrators.put(two.getNodeAddress(), two);

        Preferences preferences = PreferenceMakers.empty;

        when(arbitratorAgentManager.getObservableMap()).thenReturn(arbitrators);

        PreferencesViewModel model = new PreferencesViewModel(preferences, arbitratorAgentManager, null);

        assertEquals("English, Deutsch, español", model.getArbitrationLanguages());
    }

    @Test
    public void getMediationLanguages() {

        MediatorManager mediationManager = mock(MediatorManager.class);

        final ObservableMap<NodeAddress, Mediator> mnediators = FXCollections.observableHashMap();

        ArrayList<String> languagesOne = new ArrayList<>() {{
            add("en");
            add("de");
        }};

        ArrayList<String> languagesTwo = new ArrayList<>() {{
            add("en");
            add("es");
        }};

        Mediator one = new Mediator(new NodeAddress("refundAgent:1"), null, languagesOne, 0L,
                null, null, null, null, null);

        Mediator two = new Mediator(new NodeAddress("refundAgent:2"), null, languagesTwo, 0L,
                null, null, null, null, null);

        mnediators.put(one.getNodeAddress(), one);
        mnediators.put(two.getNodeAddress(), two);

        Preferences preferences = PreferenceMakers.empty;

        when(mediationManager.getObservableMap()).thenReturn(mnediators);

        PreferencesViewModel model = new PreferencesViewModel(preferences, null, mediationManager);

        assertEquals("English, Deutsch, español", model.getMediationLanguages());
    }

    @Test
    public void needsSupportLanguageWarning_forNotSupportedLanguageInArbitration() {

        MediatorManager mediationManager = mock(MediatorManager.class);
        ArbitratorManager arbitratorManager = mock(ArbitratorManager.class);

        Preferences preferences = PreferenceMakers.empty;

        when(arbitratorManager.isAgentAvailableForLanguage(preferences.getUserLanguage())).thenReturn(false);

        PreferencesViewModel model = new PreferencesViewModel(preferences, arbitratorManager, mediationManager);

        assertTrue(model.needsSupportLanguageWarning());
    }

    @Test
    public void needsSupportLanguageWarning_forNotSupportedLanguageInMediation() {

        MediatorManager mediationManager = mock(MediatorManager.class);
        ArbitratorManager arbitratorManager = mock(ArbitratorManager.class);

        Preferences preferences = PreferenceMakers.empty;

        when(arbitratorManager.isAgentAvailableForLanguage(preferences.getUserLanguage())).thenReturn(true);
        when(mediationManager.isAgentAvailableForLanguage(preferences.getUserLanguage())).thenReturn(false);

        PreferencesViewModel model = new PreferencesViewModel(preferences, arbitratorManager, mediationManager);

        assertTrue(model.needsSupportLanguageWarning());
    }
}
