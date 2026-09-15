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

package haveno.desktop.main.overlays;

import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.core.api.CoreNotificationService;
import haveno.core.locale.Res;
import haveno.core.support.SupportType;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.arbitration.ArbitrationManager;
import haveno.core.support.dispute.mediation.MediationManager;
import haveno.core.support.dispute.refund.RefundManager;
import haveno.core.support.messages.ChatMessage;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.user.Preferences;
import haveno.desktop.Navigation;
import haveno.desktop.main.MainView;
import haveno.desktop.main.overlays.notifications.Notification;
import haveno.desktop.main.overlays.notifications.NotificationCenter;
import haveno.desktop.main.portfolio.PortfolioView;
import haveno.desktop.main.portfolio.pendingtrades.PendingTradesView;
import haveno.network.p2p.NodeAddress;
import haveno.proto.grpc.NotificationMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class OverlayTest {

    @Test
    public void typeSafeCreation() {
        new A();
        new C();
        new D<>();
    }

    @Test
    public void typeUnsafeCreation() {
        assertThrows(RuntimeException.class, () -> new B());
    }

    @Nested
    class NotificationTimeouts {
        private final List<Runnable> callbacks = new ArrayList<>();
        private final List<Long> delays = new ArrayList<>();
        private final Timer timer = mock(Timer.class);
        private MockedStatic<UserThread> scheduler;

        @BeforeEach
        void setUp() {
            scheduler = mockStatic(UserThread.class);
            scheduler.when(() -> UserThread.runAfter(any(Runnable.class), anyLong())).thenAnswer(invocation -> {
                callbacks.add(invocation.getArgument(0));
                delays.add(invocation.getArgument(1));
                return timer;
            });
        }

        @AfterEach
        void tearDown() {
            scheduler.close();
        }

        @Test
        void waitsForOtherWindowFocusWithoutInterruptingIt() throws ReflectiveOperationException {
            TimeoutNotification notification = new TimeoutNotification();
            Stage toast = mock(Stage.class);
            Window owner = mock(Window.class);
            Window chat = mock(Window.class);
            notification.stage = toast;
            when(toast.isShowing()).thenReturn(true);
            when(toast.getOwner()).thenReturn(owner);
            when(chat.isFocused()).thenReturn(true);
            try (MockedStatic<Window> windows = mockStatic(Window.class)) {
                windows.when(Window::getWindows).thenReturn(FXCollections.observableArrayList(owner, toast, chat));
                notification.autoClose(8);
                notification.startTimer();
                callbacks.get(0).run();
                assertEquals(0, notification.closes);
                assertEquals(List.of(8L, 1L), delays);
                verify(chat, never()).requestFocus();
                when(chat.isFocused()).thenReturn(false);
                when(owner.isFocused()).thenReturn(true);
                callbacks.get(1).run();
                assertEquals(1, notification.closes);
            }
        }

        @Test
        void ignoresPopupFocusMirroredFromToastOrOwner() throws ReflectiveOperationException {
            for (boolean toastFocused : List.of(false, true)) {
                TimeoutNotification notification = new TimeoutNotification();
                Stage toast = mock(Stage.class);
                Window owner = mock(Window.class);
                Window popup = mock(Window.class);
                notification.stage = toast;
                when(toast.isShowing()).thenReturn(true);
                when(toast.getOwner()).thenReturn(owner);
                when(toast.isFocused()).thenReturn(toastFocused);
                when(owner.isFocused()).thenReturn(!toastFocused);
                when(popup.isFocused()).thenReturn(true);
                try (MockedStatic<Window> windows = mockStatic(Window.class)) {
                    windows.when(Window::getWindows).thenReturn(FXCollections.observableArrayList(owner, toast, popup));
                    notification.autoClose(8);
                    notification.startTimer();
                    callbacks.get(callbacks.size() - 1).run();
                    assertEquals(1, notification.closes);
                }
            }
        }

        @Test
        void updatingMessageInvalidatesAnAlreadyQueuedTimeout() throws ReflectiveOperationException {
            TimeoutNotification notification = new TimeoutNotification();
            notification.autoClose(8);
            notification.startTimer();
            notification.message("Two unread messages");
            verify(timer).stop();
            assertEquals(List.of(8L, 8L), delays);
            callbacks.get(0).run();
            assertEquals(0, notification.closes);
            callbacks.get(1).run();
            assertEquals(1, notification.closes);
        }

        @Test
        void retainsDefaultDelayAndClosesWithoutAnOwner() throws ReflectiveOperationException {
            TimeoutNotification notification = new TimeoutNotification();
            assertThrows(IllegalArgumentException.class, () -> notification.autoClose(0));
            notification.autoClose();
            notification.startTimer();
            assertEquals(List.of(6L), delays);
            callbacks.get(0).run();
            assertEquals(1, notification.closes);
        }
    }

    private static class TimeoutNotification extends Notification {
        private int closes;

        private void startTimer() throws ReflectiveOperationException {
            var method = Notification.class.getDeclaredMethod("startAutoCloseTimer");
            method.setAccessible(true);
            method.invoke(this);
        }

        @Override
        protected void doClose() {
            closes++;
        }
    }

    @Nested
    class UnreadTradeChat {
        private final ObservableList<Trade> trades = FXCollections.observableArrayList();
        private final ObservableList<Dispute> disputes = FXCollections.observableArrayList();
        private final TradeManager tradeManager = mock(TradeManager.class);
        private final CoreNotificationService notificationService = new CoreNotificationService();
        private final Navigation navigation = mock(Navigation.class);
        private final ArbitrationManager arbitrationManager = mock(ArbitrationManager.class);
        private NotificationCenter notificationCenter;
        private Executor originalExecutor;
        private int nextTradeId;

        @BeforeEach
        void setUp() {
            Res.setup();
            originalExecutor = UserThread.getExecutor();
            UserThread.setExecutor(Runnable::run);
            MediationManager mediationManager = mock(MediationManager.class);
            RefundManager refundManager = mock(RefundManager.class);
            Preferences preferences = mock(Preferences.class);
            when(preferences.getUseAnimationsProperty()).thenReturn(new SimpleBooleanProperty());
            when(tradeManager.getObservableList()).thenReturn(trades);
            when(tradeManager.getNotificationService()).thenReturn(notificationService);
            when(arbitrationManager.getDisputesAsObservableList()).thenReturn(disputes);
            when(mediationManager.getDisputesAsObservableList()).thenReturn(FXCollections.observableArrayList());
            when(refundManager.getDisputesAsObservableList()).thenReturn(FXCollections.observableArrayList());
            notificationCenter = new NotificationCenter(tradeManager, arbitrationManager, mediationManager,
                    refundManager, preferences, navigation);
        }

        @AfterEach
        void tearDown() {
            UserThread.setExecutor(originalExecutor);
        }

        @Test
        void restoresUnreadMessagesForMakerAndTakerUntilBothChatsAreRead() {
            Trade maker = addTrade(true);
            Trade taker = addTrade(false);
            maker.getChatMessages().add(message(maker, true));
            taker.getChatMessages().add(message(taker, true));
            notificationCenter.onAllServicesAndViewsInitialized();
            assertTrue(hasUnreadChat());

            markRead(maker);
            assertTrue(hasUnreadChat());
            markRead(taker);
            assertFalse(hasUnreadChat());
        }

        @Test
        void ignoresOutgoingDisplayedSystemAndArbitratorMessages() {
            for (boolean maker : List.of(true, false)) {
                Trade trade = addTrade(maker);
                ChatMessage displayed = message(trade, true);
                displayed.setWasDisplayed(true);
                ChatMessage system = message(trade, true);
                system.setSystemMessage(true);
                trade.getChatMessages().addAll(message(trade, false), displayed, system);
            }
            Trade arbitrator = addTrade(true);
            when(arbitrator.isArbitrator()).thenReturn(true);
            arbitrator.getChatMessages().add(message(arbitrator, true));
            notificationCenter.onAllServicesAndViewsInitialized();
            assertFalse(hasUnreadChat());
        }

        @Test
        void observesNewTradesAndClearsWhenUnreadTradeIsRemoved() {
            notificationCenter.onAllServicesAndViewsInitialized();
            assertFalse(hasUnreadChat());
            Trade trade = addTrade(true);
            trade.getChatMessages().add(message(trade, true));
            assertTrue(hasUnreadChat());
            trades.remove(trade);
            assertFalse(hasUnreadChat());
            trades.add(trade);
            assertTrue(hasUnreadChat());
            trade.getChatMessages().clear();
            assertFalse(hasUnreadChat());
        }

        @Test
        void keepsSupportAndTradeStatusNotificationsSeparate() {
            Trade trade = addTrade(true);
            Dispute dispute = mock(Dispute.class);
            ChatMessage supportMessage = new ChatMessage(SupportType.ARBITRATION, trade.getId(), 0,
                    false, "Support message", new NodeAddress("peer:9999"));
            when(dispute.getChatMessages()).thenReturn(FXCollections.observableArrayList(supportMessage));
            disputes.add(dispute);
            notificationCenter.onAllServicesAndViewsInitialized();
            notificationService.sendNotification(NotificationMessage.newBuilder()
                    .setType(NotificationMessage.NotificationType.TRADE_UPDATE).build());
            assertFalse(hasUnreadChat());
            verify(dispute).refreshAlertLevel(false);
        }

        @Test
        void doesNotMarkMessagesReadWhenTradeIsOnlySelected() {
            Trade trade = addTrade(true);
            ChatMessage message = message(trade, true);
            trade.getChatMessages().add(message);
            notificationCenter.onAllServicesAndViewsInitialized();
            notificationCenter.setSelectedTradeId(trade.getId());
            assertTrue(hasUnreadChat());
            assertFalse(message.isWasDisplayed());
        }

        @Test
        void suppressesMessagesInOpenChatAndPersistsTheirReadState() {
            Trade trade = addTrade(false);
            notificationCenter.onAllServicesAndViewsInitialized();
            notificationCenter.onChatOpened(trade.getChatMessages());
            ChatMessage message = message(trade, true);
            trade.getChatMessages().add(message);
            assertFalse(hasUnreadChat());
            notificationService.sendNotification(NotificationMessage.newBuilder()
                    .setType(NotificationMessage.NotificationType.CHAT_MESSAGE)
                    .setChatMessage(protobuf.ChatMessage.newBuilder().setType(protobuf.SupportType.TRADE)
                            .setTradeId(trade.getId()).setUid(message.getUid())).build());
            assertTrue(message.isWasDisplayed());
            verify(tradeManager).requestPersistence();
            notificationCenter.onChatClosed(trade.getChatMessages());
            assertFalse(hasUnreadChat());
            trade.getChatMessages().add(message(trade, true));
            assertTrue(hasUnreadChat());
        }

        @Test
        void dispatchesUnreadUpdatesThroughUserThread() {
            Trade trade = addTrade(true);
            notificationCenter.onAllServicesAndViewsInitialized();
            List<Runnable> updates = new ArrayList<>();
            UserThread.setExecutor(updates::add);
            trade.getChatMessages().add(message(trade, true));
            assertFalse(hasUnreadChat());
            assertFalse(updates.isEmpty());
            updates.forEach(Runnable::run);
            assertTrue(hasUnreadChat());
        }

        @Test
        void coalescesUniqueMessagesAndRetainsUnreadStateWhenDismissed() {
            Trade trade = addTrade(true);
            notificationCenter.onAllServicesAndViewsInitialized();
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                ChatMessage first = message(trade, true);
                trade.getChatMessages().add(first);
                sendMessage(first);
                Notification notification = notifications.constructed().get(0);
                verify(notification).autoClose(8);
                verify(notification).message(Res.get("notification.chat.message"));
                ChatMessage second = message(trade, true);
                trade.getChatMessages().add(second);
                sendMessage(second);
                sendMessage(second);
                assertEquals(1, notifications.constructed().size());
                verify(notification, times(1)).message(Res.get("notification.chat.messages", 2));
                notification.getIsHiddenProperty().set(true);
                assertTrue(hasUnreadChat());
                assertFalse(first.isWasDisplayed());
                assertFalse(second.isWasDisplayed());
                ChatMessage third = message(trade, true);
                trade.getChatMessages().add(third);
                sendMessage(third);
                assertEquals(2, notifications.constructed().size());
                verify(notifications.constructed().get(1)).message(Res.get("notification.chat.messages", 3));
            }
        }

        @Test
        void keepsToastsSeparateAndNavigatesDirectlyToRequestedChat() {
            Trade first = addTrade(true);
            Trade second = addTrade(false);
            notificationCenter.onAllServicesAndViewsInitialized();
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                for (Trade trade : List.of(first, second)) {
                    ChatMessage message = message(trade, true);
                    trade.getChatMessages().add(message);
                    sendMessage(message);
                }
                assertEquals(2, notifications.constructed().size());
                Notification notification = notifications.constructed().get(1);
                verify(notification).actionButtonText(Res.get("notification.chat.openChat"));
                ArgumentCaptor<Runnable> action = ArgumentCaptor.forClass(Runnable.class);
                verify(notification).onAction(action.capture());
                action.getValue().run();
                verify(navigation).navigateToWithData(new PendingTradesView.OpenChatRequest(second),
                        MainView.class, PortfolioView.class, PendingTradesView.class);
                assertTrue(hasUnreadChat());
                assertFalse(second.getChatMessages().get(0).isWasDisplayed());
            }
        }

        @Test
        void invalidatesQueuedToastEvenIfNewMessagesArriveAfterChatCloses() {
            Trade trade = addTrade(true);
            notificationCenter.onAllServicesAndViewsInitialized();
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                ChatMessage first = message(trade, true);
                trade.getChatMessages().add(first);
                sendMessage(first);
                Notification queued = notifications.constructed().get(0);
                when(queued.isDisplayed()).thenReturn(false);
                ArgumentCaptor<BooleanSupplier> guard = ArgumentCaptor.forClass(BooleanSupplier.class);
                verify(queued).onlyShowIf(guard.capture());
                assertTrue(guard.getValue().getAsBoolean());
                first.setWasDisplayed(true);
                notificationCenter.onChatOpened(trade.getChatMessages());
                verify(queued, never()).hide();
                notificationCenter.onChatClosed(trade.getChatMessages());
                ChatMessage second = message(trade, true);
                trade.getChatMessages().add(second);
                sendMessage(second);
                assertFalse(guard.getValue().getAsBoolean());
                Notification current = notifications.constructed().get(1);
                ArgumentCaptor<BooleanSupplier> currentGuard = ArgumentCaptor.forClass(BooleanSupplier.class);
                verify(current).onlyShowIf(currentGuard.capture());
                queued.getIsHiddenProperty().set(true);
                assertTrue(currentGuard.getValue().getAsBoolean());
                notificationCenter.onChatOpened(trade.getChatMessages());
                verify(current).hide();
                assertFalse(currentGuard.getValue().getAsBoolean());
            }
        }

        @Test
        void removingTradeDismissesItsToastWithoutMarkingItRead() {
            Trade trade = addTrade(true);
            notificationCenter.onAllServicesAndViewsInitialized();
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                ChatMessage message = message(trade, true);
                trade.getChatMessages().add(message);
                sendMessage(message);
                Notification notification = notifications.constructed().get(0);
                ArgumentCaptor<BooleanSupplier> guard = ArgumentCaptor.forClass(BooleanSupplier.class);
                verify(notification).onlyShowIf(guard.capture());
                trades.remove(trade);
                verify(notification).hide();
                assertFalse(guard.getValue().getAsBoolean());
                assertFalse(message.isWasDisplayed());
            }
        }

        @Test
        void countsExistingUnreadMessagesButIgnoresOutgoingAndSystemMessages() {
            Trade trade = addTrade(true);
            ChatMessage first = message(trade, true);
            ChatMessage second = message(trade, true);
            ChatMessage outgoing = message(trade, false);
            ChatMessage system = message(trade, true);
            system.setSystemMessage(true);
            trade.getChatMessages().addAll(first, second, outgoing, system);
            notificationCenter.onAllServicesAndViewsInitialized();
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                sendMessage(outgoing);
                sendMessage(system);
                assertTrue(notifications.constructed().isEmpty());
                sendMessage(second);
                verify(notifications.constructed().get(0)).message(Res.get("notification.chat.messages", 2));
            }
        }

        @Test
        void coalescesSupportMessagesWithoutChangingTicketNavigation() {
            Trade trade = addTrade(true);
            Dispute dispute = mock(Dispute.class);
            ObservableList<ChatMessage> messages = FXCollections.observableArrayList();
            when(dispute.getChatMessages()).thenReturn(messages);
            when(arbitrationManager.findDispute(trade.getId(), 0)).thenReturn(Optional.of(dispute));
            disputes.add(dispute);
            notificationCenter.onAllServicesAndViewsInitialized();
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                for (int i = 0; i < 2; i++) {
                    ChatMessage message = new ChatMessage(SupportType.ARBITRATION, trade.getId(), 0,
                            false, "Private support message", new NodeAddress("peer:9999"));
                    messages.add(message);
                    sendMessage(message);
                }
                assertEquals(1, notifications.constructed().size());
                Notification notification = notifications.constructed().get(0);
                verify(notification).message(Res.get("notification.chat.messages", 2));
                verify(notification).actionButtonText(Res.get("notification.chat.goToTicket"));
                assertFalse(hasUnreadChat());
            }
        }

        private MockedConstruction<Notification> mockNotifications() {
            return mockConstruction(Notification.class, withSettings().defaultAnswer(RETURNS_SELF), (notification, context) -> {
                when(notification.getIsHiddenProperty()).thenReturn(new SimpleBooleanProperty());
                when(notification.isHasBeenDisplayed()).thenReturn(true);
                when(notification.isDisplayed()).thenReturn(true);
            });
        }

        private void sendMessage(ChatMessage message) {
            notificationService.sendNotification(NotificationMessage.newBuilder()
                    .setType(NotificationMessage.NotificationType.CHAT_MESSAGE)
                    .setChatMessage(protobuf.ChatMessage.newBuilder().setType(protobuf.SupportType.valueOf(message.getSupportType().name()))
                            .setTradeId(message.getTradeId()).setTraderId(message.getTraderId()).setUid(message.getUid())).build());
        }

        private Trade addTrade(boolean maker) {
            Trade trade = mock(Trade.class);
            when(trade.getId()).thenReturn("trade-" + nextTradeId++);
            when(trade.isMaker()).thenReturn(maker);
            when(trade.getChatMessages()).thenReturn(FXCollections.observableArrayList());
            when(trade.statePhaseProperty()).thenReturn(new SimpleObjectProperty<>(Trade.Phase.INIT));
            when(trade.disputeStateProperty()).thenReturn(new SimpleObjectProperty<>(Trade.DisputeState.NO_DISPUTE));
            when(tradeManager.getOpenTrade(trade.getId())).thenReturn(Optional.of(trade));
            trades.add(trade);
            return trade;
        }

        private ChatMessage message(Trade trade, boolean incoming) {
            return new ChatMessage(SupportType.TRADE, trade.getId(), 0,
                    incoming ? trade.isMaker() : !trade.isMaker(), "Peer message", new NodeAddress("peer:9999"));
        }

        private void markRead(Trade trade) {
            trade.getChatMessages().forEach(message -> message.setWasDisplayed(true));
            notificationCenter.onChatOpened(trade.getChatMessages());
            notificationCenter.onChatClosed(trade.getChatMessages());
        }

        private boolean hasUnreadChat() {
            return notificationCenter.unreadTradeChatProperty().get();
        }
    }

    private static class A extends Overlay<A> {
    }

    private static class B extends Overlay<A> {
    }

    private static class C extends TabbedOverlay<C> {
    }

    private static class D<T> extends Overlay<D<T>> {
    }
}
