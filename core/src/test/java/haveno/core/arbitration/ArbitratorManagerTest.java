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

package haveno.core.arbitration;

import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.core.filter.FilterManager;
import haveno.core.support.dispute.arbitration.arbitrator.Arbitrator;
import haveno.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import haveno.core.support.dispute.arbitration.arbitrator.ArbitratorService;
import haveno.core.user.User;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.P2PServiceListener;
import javafx.beans.property.SimpleObjectProperty;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsIterableContainingInAnyOrder.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ArbitratorManagerTest {

    @Test
    public void testRepublishesAfterSlowHiddenServiceStartup() {
        User user = mock(User.class);
        ArbitratorService arbitratorService = mock(ArbitratorService.class);
        P2PService p2PService = mock(P2PService.class);
        FilterManager filterManager = mock(FilterManager.class);
        Arbitrator arbitrator = new Arbitrator(new NodeAddress("arbitrator:9999"), null,
                List.of("en"), 0L, null, "", null, null, null);
        when(user.getRegisteredArbitrator()).thenReturn(arbitrator);
        when(arbitratorService.getP2PService()).thenReturn(p2PService);
        when(p2PService.isBootstrapped()).thenReturn(true);
        when(filterManager.filterProperty()).thenReturn(new SimpleObjectProperty<>());
        AtomicReference<Runnable> startupRepublish = new AtomicReference<>();
        Timer periodicTimer = mock(Timer.class);

        try (MockedStatic<UserThread> userThread = mockStatic(UserThread.class)) {
            userThread.when(() -> UserThread.runPeriodically(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS)))
                    .thenReturn(periodicTimer);
            userThread.when(() -> UserThread.runAfter(any(Runnable.class), eq(60L)))
                    .thenAnswer(invocation -> {
                        startupRepublish.set(invocation.getArgument(0));
                        return mock(Timer.class);
                    });
            ArbitratorManager manager = new ArbitratorManager(null, arbitratorService, user, filterManager);
            manager.onAllServicesInitialized();

            // Both startup broadcasts can arrive before the seed knows our onion address.
            startupRepublish.get().run();
            verify(arbitratorService, times(2)).addDisputeAgent(eq(arbitrator), any(), any());

            ArgumentCaptor<P2PServiceListener> listener = ArgumentCaptor.forClass(P2PServiceListener.class);
            verify(p2PService).addP2PServiceListener(listener.capture());
            listener.getValue().onUpdatedDataReceived();
            verify(arbitratorService, times(3)).addDisputeAgent(eq(arbitrator), any(), any());

            when(user.getRegisteredArbitrator()).thenReturn(null);
            listener.getValue().onUpdatedDataReceived();
            verify(arbitratorService, times(3)).addDisputeAgent(eq(arbitrator), any(), any());

            manager.shutDown();
            verify(p2PService).removeP2PServiceListener(listener.getValue());
            verify(periodicTimer).stop();
        }
    }

    @Test
    public void testWaitsForBootstrapBeforeRepublishing() {
        User user = mock(User.class);
        ArbitratorService arbitratorService = mock(ArbitratorService.class);
        P2PService p2PService = mock(P2PService.class);
        FilterManager filterManager = mock(FilterManager.class);
        Arbitrator arbitrator = new Arbitrator(new NodeAddress("arbitrator:9999"), null,
                List.of("en"), 0L, null, "", null, null, null);
        when(user.getRegisteredArbitrator()).thenReturn(arbitrator);
        when(arbitratorService.getP2PService()).thenReturn(p2PService);
        when(filterManager.filterProperty()).thenReturn(new SimpleObjectProperty<>());

        try (MockedStatic<UserThread> userThread = mockStatic(UserThread.class)) {
            userThread.when(() -> UserThread.runPeriodically(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS)))
                    .thenReturn(mock(Timer.class));
            ArbitratorManager manager = new ArbitratorManager(null, arbitratorService, user, filterManager);
            manager.onAllServicesInitialized();
            ArgumentCaptor<P2PServiceListener> listener = ArgumentCaptor.forClass(P2PServiceListener.class);
            verify(p2PService).addP2PServiceListener(listener.capture());
            listener.getValue().onUpdatedDataReceived();
            verify(arbitratorService, never()).addDisputeAgent(any(), any(), any());

            when(p2PService.isBootstrapped()).thenReturn(true);
            listener.getValue().onDataReceived();
            verify(arbitratorService).addDisputeAgent(eq(arbitrator), any(), any());
            manager.shutDown();
        }
    }

    @Test
    public void testIsArbitratorAvailableForLanguage() {
        User user = mock(User.class);
        ArbitratorService arbitratorService = mock(ArbitratorService.class);

        ArbitratorManager manager = new ArbitratorManager(null, arbitratorService, user, null);

        ArrayList<String> languagesOne = new ArrayList<String>() {{
            add("en");
            add("de");
        }};

        ArrayList<String> languagesTwo = new ArrayList<String>() {{
            add("en");
            add("es");
        }};

        Arbitrator one = new Arbitrator(new NodeAddress("arbitrator:1"), null,
                languagesOne, 0L, null, "", null,
                null, null);

        Arbitrator two = new Arbitrator(new NodeAddress("arbitrator:2"), null,
                languagesTwo, 0L, null, "", null,
                null, null);

        manager.addDisputeAgent(one, () -> {
        }, errorMessage -> {
        });
        manager.addDisputeAgent(two, () -> {
        }, errorMessage -> {
        });

        assertTrue(manager.isAgentAvailableForLanguage("en"));
        assertFalse(manager.isAgentAvailableForLanguage("th"));
    }

    @Test
    public void testGetArbitratorLanguages() {
        User user = mock(User.class);
        ArbitratorService arbitratorService = mock(ArbitratorService.class);

        ArbitratorManager manager = new ArbitratorManager(null, arbitratorService, user, null);

        ArrayList<String> languagesOne = new ArrayList<String>() {{
            add("en");
            add("de");
        }};

        ArrayList<String> languagesTwo = new ArrayList<String>() {{
            add("en");
            add("es");
        }};

        Arbitrator one = new Arbitrator(new NodeAddress("arbitrator:1"), null,
                languagesOne, 0L, null, "", null,
                null, null);

        Arbitrator two = new Arbitrator(new NodeAddress("arbitrator:2"), null,
                languagesTwo, 0L, null, "", null,
                null, null);

        ArrayList<NodeAddress> nodeAddresses = new ArrayList<NodeAddress>() {{
            add(two.getNodeAddress());
        }};

        manager.addDisputeAgent(one, () -> {
        }, errorMessage -> {
        });
        manager.addDisputeAgent(two, () -> {
        }, errorMessage -> {
        });

        assertThat(manager.getDisputeAgentLanguages(nodeAddresses), containsInAnyOrder("en", "es"));
        assertThat(manager.getDisputeAgentLanguages(nodeAddresses), not(containsInAnyOrder("de")));
    }

}
