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

import haveno.common.ThreadUtils;
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
import haveno.desktop.common.model.WithDataModel;
import haveno.desktop.common.view.ViewPath;
import haveno.desktop.main.MainView;
import haveno.desktop.main.overlays.notifications.Notification;
import haveno.desktop.main.overlays.notifications.NotificationCenter;
import haveno.desktop.main.overlays.notifications.NotificationManager;
import haveno.desktop.main.portfolio.PortfolioView;
import haveno.desktop.main.portfolio.pendingtrades.PendingTradesDataModel;
import haveno.desktop.main.portfolio.pendingtrades.PendingTradesListItem;
import haveno.desktop.main.portfolio.pendingtrades.PendingTradesView;
import haveno.desktop.main.portfolio.pendingtrades.PendingTradesViewModel;
import haveno.network.p2p.NodeAddress;
import haveno.proto.grpc.NotificationMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;
import javafx.scene.Scene;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
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
    class NotificationQueue {
        private final List<Runnable> timeouts = new ArrayList<>();
        private final List<Long> delays = new ArrayList<>();
        private MockedStatic<UserThread> scheduler;

        @BeforeEach
        void setUp() throws ReflectiveOperationException {
            resetQueue();
            scheduler = mockStatic(UserThread.class);
            scheduler.when(() -> UserThread.runAfter(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS))).thenAnswer(invocation -> {
                timeouts.add(invocation.getArgument(0));
                delays.add(invocation.getArgument(1));
                return mock(Timer.class);
            });
        }

        @AfterEach
        void tearDown() throws ReflectiveOperationException {
            for (Notification notification : new ArrayList<>(queue())) notification.hide();
            resetQueue();
            scheduler.close();
        }

        @Test
        void newestNotificationPreemptsAndThenRestoresOlderNotices() throws ReflectiveOperationException {
            TestNotification dispute = new TestNotification();
            TestNotification chat = new TestNotification();
            TestNotification last = new TestNotification();
            dispute.show();
            chat.autoClose().show();
            last.show();
            assertEquals(List.of(last, chat, dispute), queue());
            assertFalse(dispute.isDisplayed());
            assertFalse(chat.isDisplayed());
            assertTrue(last.isDisplayed());
            assertFalse(dispute.getIsHiddenProperty().get());
            assertFalse(chat.getIsHiddenProperty().get());
            timeouts.get(0).run();
            assertFalse(chat.getIsHiddenProperty().get());
            last.hide();
            assertTrue(chat.isDisplayed());
            assertFalse(dispute.isDisplayed());
            assertEquals(List.of(6000L, 6000L), delays);
            timeouts.get(1).run();
            assertEquals(List.of(dispute), queue());
            assertTrue(dispute.isDisplayed());
        }

        @Test
        void newMessagePromotesSuspendedChatAndInvalidatesItsOldTimeout() throws ReflectiveOperationException {
            TestNotification chat = new TestNotification();
            TestNotification dispute = new TestNotification();
            chat.autoClose().show();
            dispute.show();
            chat.message("Two unread messages");
            assertEquals(List.of(chat, dispute), queue());
            assertTrue(chat.isDisplayed());
            assertFalse(dispute.isDisplayed());
            assertEquals(2, chat.displays);
            assertEquals(List.of(6000L, 6000L), delays);
            timeouts.get(0).run();
            assertFalse(chat.getIsHiddenProperty().get());
            timeouts.get(1).run();
            assertTrue(dispute.isDisplayed());
        }

        @Test
        void newNotificationPausesCurrentTimeoutUntilItIsRestored() throws ReflectiveOperationException {
            TestNotification wallet = new TestNotification();
            TestNotification chat = new TestNotification();
            wallet.autoClose().show();
            chat.autoClose().show();
            assertEquals(List.of(6000L, 6000L), delays);
            timeouts.get(0).run();
            assertEquals(List.of(chat, wallet), queue());
            timeouts.get(1).run();
            assertEquals(List.of(wallet), queue());
            assertEquals(List.of(6000L, 6000L, 6000L), delays);
            timeouts.get(2).run();
            assertTrue(queue().isEmpty());
        }

        @Test
        void updatingVisibleChatInvalidatesAnAlreadyQueuedTimeout() {
            TestNotification chat = new TestNotification();
            chat.autoClose().show();
            chat.message("Two unread messages");
            assertEquals(List.of(6000L, 6000L), delays);
            timeouts.get(0).run();
            assertFalse(chat.getIsHiddenProperty().get());
            timeouts.get(1).run();
            assertTrue(chat.getIsHiddenProperty().get());
        }

        @Test
        void builderMessageDoesNotDisplayOrStartATimer() throws ReflectiveOperationException {
            TestNotification chat = new TestNotification();
            chat.autoClose().message("Unread message");
            assertTrue(queue().isEmpty());
            assertTrue(timeouts.isEmpty());
            assertEquals(0, chat.displays);
        }

        @Test
        void closedNotificationCanBeShownAgainWithAFreshTimeout() throws ReflectiveOperationException {
            TestNotification chat = new TestNotification();
            chat.autoClose().show();
            chat.hide();
            assertTrue(chat.getIsHiddenProperty().get());
            chat.show();
            assertEquals(List.of(chat), queue());
            assertFalse(chat.getIsHiddenProperty().get());
            assertEquals(2, chat.displays);
            assertEquals(List.of(6000L, 6000L), delays);
            timeouts.get(0).run();
            assertFalse(chat.getIsHiddenProperty().get());
            timeouts.get(1).run();
            assertTrue(queue().isEmpty());
        }

        @Test
        void managerDoesNotInsertAnAlreadyClosedOrQueuedNotification() throws ReflectiveOperationException {
            TestNotification closed = new TestNotification();
            closed.show();
            closed.hide();
            TestNotification chat = new TestNotification();
            chat.autoClose().show();
            NotificationManager.show(closed);
            NotificationManager.show(chat);
            assertEquals(List.of(chat), queue());
            assertEquals(List.of(6000L), delays);
        }

        @Test
        void startsTimeoutOnlyWhenChatIsReadyToBeSeen() {
            TestNotification chat = new TestNotification();
            chat.deferDisplay = true;
            chat.autoClose().show();
            chat.message("New message before layout finishes");
            assertTrue(timeouts.isEmpty());
            chat.finishDisplay();
            assertEquals(List.of(6000L), delays);
        }

        @Test
        void dismissingSuspendedNoticeDoesNotDisturbCurrentTimer() throws ReflectiveOperationException {
            TestNotification obsoleteWallet = new TestNotification();
            TestNotification chat = new TestNotification();
            obsoleteWallet.autoClose().show();
            chat.autoClose().show();
            obsoleteWallet.hide();
            assertEquals(List.of(chat), queue());
            assertEquals(List.of(6000L, 6000L), delays);
            assertTrue(obsoleteWallet.getIsHiddenProperty().get());
            assertEquals(1, chat.displays);
            timeouts.get(1).run();
            assertTrue(queue().isEmpty());
        }

        @Test
        void skipsSuspendedNotificationsThatAreNoLongerRelevant() throws ReflectiveOperationException {
            TestNotification first = new TestNotification();
            first.show();
            SimpleBooleanProperty unread = new SimpleBooleanProperty(true);
            TestNotification stale = new TestNotification();
            stale.onlyShowIf(unread::get).autoClose().show();
            TestNotification last = new TestNotification();
            last.show();
            unread.set(false);
            last.hide();
            assertTrue(stale.getIsHiddenProperty().get());
            assertEquals(List.of(first), queue());
            assertEquals(1, stale.displays);
            assertTrue(first.isDisplayed());
            assertEquals(List.of(6000L), delays);
        }

        @Test
        void invalidFirstNoticeDoesNotBlockTheQueue() throws ReflectiveOperationException {
            TestNotification invalid = new TestNotification();
            invalid.onlyShowIf(() -> false).show();
            TestNotification valid = new TestNotification();
            valid.show();
            assertEquals(List.of(valid), queue());
            assertTrue(invalid.getIsHiddenProperty().get());
            assertEquals(0, invalid.displays);
            assertTrue(valid.isDisplayed());
        }

        @Test
        void waitsForCurrentHideAnimationBeforeDisplayingNextNotification() throws ReflectiveOperationException {
            TestNotification closing = new TestNotification();
            closing.deferHide = true;
            closing.show();
            closing.hide();
            TestNotification next = new TestNotification();
            next.autoClose().show();
            closing.message("Update during close");
            assertEquals(List.of(next, closing), queue());
            assertEquals(0, next.displays);
            closing.finishHide.run();
            assertEquals(List.of(next), queue());
            assertEquals(1, next.displays);
            assertEquals(List.of(6000L), delays);
        }

        @Test
        void burstWaitsForSuspensionThenShowsOnlyTheNewestNotification() throws ReflectiveOperationException {
            TestNotification dispute = new TestNotification();
            dispute.deferHide = true;
            dispute.show();
            TestNotification chat = new TestNotification();
            chat.autoClose().show();
            TestNotification latest = new TestNotification();
            latest.autoClose().show();
            assertTrue(dispute.isDisplayed());
            assertEquals(0, chat.displays);
            assertEquals(0, latest.displays);
            assertTrue(timeouts.isEmpty());
            dispute.finishHide.run();
            assertFalse(dispute.isDisplayed());
            assertFalse(dispute.getIsHiddenProperty().get());
            assertFalse(chat.isDisplayed());
            assertTrue(latest.isDisplayed());
            assertEquals(List.of(latest, chat, dispute), queue());
            assertEquals(List.of(6000L), delays);
        }

        @Test
        void closingDuringSuspensionDiscardsNoticeAfterAnimation() throws ReflectiveOperationException {
            TestNotification wallet = new TestNotification();
            wallet.deferHide = true;
            wallet.autoClose().show();
            TestNotification chat = new TestNotification();
            chat.autoClose().show();
            wallet.hide();
            assertFalse(wallet.getIsHiddenProperty().get());
            assertFalse(chat.isDisplayed());
            wallet.finishHide.run();
            assertTrue(wallet.getIsHiddenProperty().get());
            assertTrue(chat.isDisplayed());
            assertEquals(List.of(chat), queue());
            timeouts.get(0).run();
            assertFalse(chat.getIsHiddenProperty().get());
        }

        @Test
        void updateDuringSuspensionRestoresTheSameChatAfterHiding() throws ReflectiveOperationException {
            TestNotification chat = new TestNotification();
            chat.deferHide = true;
            chat.autoClose().show();
            TestNotification notice = new TestNotification();
            notice.show();
            chat.message("New message during handoff");
            assertEquals(List.of(chat, notice), queue());
            assertEquals(List.of(6000L), delays);
            chat.finishHide.run();
            assertTrue(chat.isDisplayed());
            assertEquals(2, chat.displays);
            assertEquals(0, notice.displays);
            assertEquals(List.of(6000L, 6000L), delays);
            timeouts.get(0).run();
            assertFalse(chat.getIsHiddenProperty().get());
        }

        @Test
        void cancellationDuringHandoffRestoresOlderNotice() throws ReflectiveOperationException {
            TestNotification dispute = new TestNotification();
            dispute.deferHide = true;
            dispute.show();
            TestNotification obsolete = new TestNotification();
            obsolete.autoClose().show();
            obsolete.hide();
            assertTrue(obsolete.getIsHiddenProperty().get());
            dispute.finishHide.run();
            assertTrue(dispute.isDisplayed());
            assertEquals(2, dispute.displays);
            assertEquals(0, obsolete.displays);
            assertEquals(List.of(dispute), queue());
            assertTrue(timeouts.isEmpty());
        }

        @Test
        void suspendedNotificationIgnoresDisplayQueuedBeforeItsStageWasCreated() {
            List<Runnable> displays = new ArrayList<>();
            scheduler.when(() -> UserThread.execute(any(Runnable.class))).thenAnswer(invocation -> {
                displays.add(invocation.getArgument(0));
                return null;
            });
            TestNotification chat = new TestNotification();
            chat.deferDisplay = true;
            chat.autoClose().show();
            TestNotification notice = new TestNotification();
            notice.show();
            displays.get(0).run();
            assertNull(chat.stage);
            assertFalse(chat.isDisplayed());
            assertTrue(notice.isDisplayed());
            notice.hide();
            displays.get(0).run();
            assertNull(chat.stage);
            assertTrue(timeouts.isEmpty());
            chat.finishDisplay();
            assertEquals(List.of(6000L), delays);
        }

        @Test
        void showingNotificationDoesNotRestoreFocusToTheMainWindow() {
            TestNotification chat = new TestNotification();
            when(chat.ownerWindow.isFocused()).thenReturn(true);
            try (MockedStatic<Window> windows = mockStatic(Window.class)) {
                windows.when(Window::getWindows).thenReturn(FXCollections.observableArrayList(chat.ownerWindow));
                chat.show();
                verify(chat.stage).show();
                verify(chat.ownerWindow, never()).toFront();
                verify(chat.ownerWindow, never()).requestFocus();
            }
        }

        @Test
        void promotionAndRestorationPreserveFocusedDialog() {
            TestNotification dispute = new TestNotification();
            dispute.show();
            TestNotification chat = new TestNotification();
            Stage dialog = mock(Stage.class);
            when(dialog.isFocused()).thenReturn(true);
            try (MockedStatic<Window> windows = mockStatic(Window.class)) {
                windows.when(Window::getWindows).thenReturn(FXCollections.observableArrayList(dialog));
                chat.autoClose().show();
                var order = inOrder(dispute.stage, chat.stage, dialog);
                order.verify(dispute.stage).hide();
                order.verify(chat.stage).show();
                order.verify(dialog).toFront();
                order.verify(dialog).requestFocus();
                assertFalse(dispute.getIsHiddenProperty().get());
                timeouts.get(0).run();
                assertTrue(chat.getIsHiddenProperty().get());
                assertTrue(dispute.isDisplayed());
            }
        }

        @Test
        void retainsEveryNotificationThroughABurst() throws ReflectiveOperationException {
            List<TestNotification> shown = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                TestNotification notification = new TestNotification();
                notification.show();
                shown.add(notification);
            }
            Collections.reverse(shown);
            assertEquals(shown, queue());
            for (TestNotification notification : shown) {
                assertFalse(notification.getIsHiddenProperty().get());
                assertTrue(notification.isDisplayed());
                notification.hide();
            }
            assertTrue(queue().isEmpty());
        }

        @Test
        void walletExpiresWhileSuccessDialogHasFocus() {
            TestNotification wallet = new TestNotification();
            wallet.autoClose().show();
            Stage dialog = mock(Stage.class);
            when(dialog.isFocused()).thenReturn(true);
            try (MockedStatic<Window> windows = mockStatic(Window.class)) {
                windows.when(Window::getWindows).thenReturn(FXCollections.observableArrayList(wallet.stage, wallet.ownerWindow, dialog));
                timeouts.get(0).run();
                assertTrue(wallet.getIsHiddenProperty().get());
                assertEquals(List.of(6000L), delays);
                var order = inOrder(wallet.stage, dialog);
                order.verify(wallet.stage).hide();
                order.verify(dialog).toFront();
                order.verify(dialog).requestFocus();
            }
        }

        @Test
        void expiryPreservesTheDialogFocusedWhenHideAnimationFinishes() {
            TestNotification chat = new TestNotification();
            chat.deferHide = true;
            chat.autoClose().show();
            Stage firstDialog = mock(Stage.class);
            Stage nextDialog = mock(Stage.class);
            when(firstDialog.isFocused()).thenReturn(true);
            try (MockedStatic<Window> windows = mockStatic(Window.class)) {
                windows.when(Window::getWindows).thenReturn(FXCollections.observableArrayList(chat.stage, firstDialog, nextDialog));
                timeouts.get(0).run();
                when(firstDialog.isFocused()).thenReturn(false);
                when(nextDialog.isFocused()).thenReturn(true);
                chat.finishHide.run();
                assertTrue(chat.getIsHiddenProperty().get());
                verify(firstDialog, never()).requestFocus();
                var order = inOrder(chat.stage, nextDialog);
                order.verify(chat.stage).hide();
                order.verify(nextDialog).toFront();
                order.verify(nextDialog).requestFocus();
            }
        }

        @Test
        void expiryDoesNotWaitForFocusOnANonStageWindow() {
            TestNotification chat = new TestNotification();
            chat.autoClose().show();
            Window popup = mock(Window.class);
            when(popup.isFocused()).thenReturn(true);
            try (MockedStatic<Window> windows = mockStatic(Window.class)) {
                windows.when(Window::getWindows).thenReturn(FXCollections.observableArrayList(chat.stage, popup));
                timeouts.get(0).run();
                assertTrue(chat.getIsHiddenProperty().get());
                assertEquals(List.of(6000L), delays);
                verify(popup, never()).requestFocus();
            }
        }

        @Test
        void expiryDoesNotRestoreFocusToTheClosingNotification() {
            TestNotification chat = new TestNotification();
            chat.autoClose().show();
            when(chat.stage.isFocused()).thenReturn(true);
            try (MockedStatic<Window> windows = mockStatic(Window.class)) {
                windows.when(Window::getWindows).thenReturn(FXCollections.observableArrayList(chat.stage));
                timeouts.get(0).run();
                assertTrue(chat.getIsHiddenProperty().get());
                verify(chat.stage, never()).toFront();
                verify(chat.stage, never()).requestFocus();
            }
        }

        @Test
        void notificationsKeepTheUpperRightPositionAfterOwnerMoves() {
            TestNotification notification = new TestNotification();
            when(notification.ownerWindow.getX()).thenReturn(100.0);
            when(notification.ownerWindow.getY()).thenReturn(100.0);
            when(notification.ownerWindow.getWidth()).thenReturn(1020.0);
            when(notification.ownerWindow.getHeight()).thenReturn(620.0);
            notification.show();
            notification.layoutNotification();
            verify(notification.stage).setX(741.0);
            verify(notification.stage).setY(86.0);
            when(notification.ownerWindow.getX()).thenReturn(150.0);
            when(notification.ownerWindow.getY()).thenReturn(120.0);
            when(notification.ownerWindow.getWidth()).thenReturn(1200.0);
            notification.layoutNotification();
            verify(notification.stage).setX(971.0);
            verify(notification.stage).setY(106.0);
        }

        private void resetQueue() throws ReflectiveOperationException {
            queue().clear();
            var field = NotificationManager.class.getDeclaredField("displayedNotification");
            field.setAccessible(true);
            field.set(null, null);
        }

        @SuppressWarnings("unchecked")
        private List<Notification> queue() throws ReflectiveOperationException {
            var field = NotificationManager.class.getDeclaredField("notifications");
            field.setAccessible(true);
            return (List<Notification>) field.get(null);
        }
    }

    private static class TestNotification extends Notification {
        private final Stage ownerWindow = mock(Stage.class);
        private int displays;
        private boolean deferDisplay;
        private boolean deferHide;
        private Runnable finishHide;
        private boolean windowShowing;

        private TestNotification() {
            owner = mock(Pane.class);
            when(owner.getScene()).thenReturn(mock(Scene.class));
            when(owner.getScene().getWindow()).thenReturn(ownerWindow);
            when(owner.getScene().getHeight()).thenReturn(600.0);
        }

        private void finishDisplay() {
            createStage();
            finishLayout();
        }

        private void finishLayout() {
            animateDisplay();
        }

        private void createStage() {
            stage = mock(Stage.class);
            when(stage.getOwner()).thenReturn(ownerWindow);
            windowShowing = true;
            when(stage.isShowing()).thenAnswer(invocation -> windowShowing);
            doAnswer(invocation -> {
                windowShowing = false;
                return null;
            }).when(stage).hide();
            doAnswer(invocation -> {
                windowShowing = true;
                return null;
            }).when(stage).show();
            when(stage.getWidth()).thenReturn(413.0);
            setModality();
            showStage();
        }

        @Override
        protected void refitToContent() {
            layout();
        }

        private void layoutNotification() {
            layout();
        }

        @Override
        public void show(boolean showAgainChecked) {
            createContent(showAgainChecked);
            onShow();
        }

        @Override
        protected void createContent(boolean showAgainChecked) {
            gridPane = mock(GridPane.class);
        }

        @Override
        public void display() {
            displays++;
            super.display();
            if (!deferDisplay) finishDisplay();
        }

        @Override
        protected double getDuration(double duration) {
            return 1;
        }

        @Override
        protected void animateHide(Runnable onFinishedHandler) {
            if (deferHide) finishHide = () -> super.animateHide(onFinishedHandler);
            else super.animateHide(onFinishedHandler);
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
        private final Preferences preferences = mock(Preferences.class);
        private final ObservableMap<String, Boolean> seenUpdates = FXCollections.observableHashMap();
        private NotificationCenter notificationCenter;
        private Executor originalExecutor;
        private MockedStatic<ThreadUtils> backgroundTasks;
        private int nextTradeId;

        @BeforeEach
        void setUp() {
            Res.setup();
            originalExecutor = UserThread.getExecutor();
            UserThread.setExecutor(Runnable::run);
            // run subscription callbacks on the test thread so they cannot race mock setup
            backgroundTasks = mockStatic(ThreadUtils.class);
            backgroundTasks.when(() -> ThreadUtils.submitToPool(any(Runnable.class))).thenAnswer(invocation -> {
                ((Runnable) invocation.getArgument(0)).run();
                return CompletableFuture.completedFuture(null);
            });
            MediationManager mediationManager = mock(MediationManager.class);
            RefundManager refundManager = mock(RefundManager.class);
            when(preferences.getUseAnimationsProperty()).thenReturn(new SimpleBooleanProperty());
            when(preferences.getDontShowAgainMapAsObservable()).thenReturn(seenUpdates);
            when(preferences.showAgain(anyString())).thenAnswer(invocation -> !Boolean.TRUE.equals(seenUpdates.get(invocation.getArgument(0))));
            doAnswer(invocation -> {
                seenUpdates.put(invocation.getArgument(0), invocation.getArgument(1));
                return null;
            }).when(preferences).dontShowAgain(anyString(), anyBoolean());
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
            if (backgroundTasks != null) backgroundTasks.close();
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
            notificationCenter.setViewedTradeId(trade.getId());
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
        void suppressesOnlyTheOpenConversation() {
            Trade openTrade = addTrade(true);
            Trade otherTrade = addTrade(false);
            Dispute dispute = addDispute(openTrade, false);
            notificationCenter.onAllServicesAndViewsInitialized();
            notificationCenter.onChatOpened(openTrade.getChatMessages());
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                ChatMessage openMessage = message(openTrade, true);
                openTrade.getChatMessages().add(openMessage);
                sendMessage(openMessage);
                assertTrue(openMessage.isWasDisplayed());
                assertTrue(notifications.constructed().isEmpty());

                ChatMessage otherMessage = message(otherTrade, true);
                otherTrade.getChatMessages().add(otherMessage);
                sendMessage(otherMessage);
                ChatMessage supportMessage = supportMessage(dispute, false);
                dispute.getChatMessages().add(supportMessage);
                sendMessage(supportMessage);
                assertEquals(2, notifications.constructed().size());
                assertFalse(otherMessage.isWasDisplayed());
                assertFalse(supportMessage.isWasDisplayed());

                notificationCenter.onChatClosed(openTrade.getChatMessages());
                notificationCenter.onChatOpened(dispute.getChatMessages());
                ChatMessage openSupportMessage = supportMessage(dispute, false);
                dispute.getChatMessages().add(openSupportMessage);
                sendMessage(openSupportMessage);
                assertTrue(openSupportMessage.isWasDisplayed());
                assertEquals(2, notifications.constructed().size());

                ChatMessage tradeMessage = message(openTrade, true);
                openTrade.getChatMessages().add(tradeMessage);
                sendMessage(tradeMessage);
                assertFalse(tradeMessage.isWasDisplayed());
                assertEquals(3, notifications.constructed().size());
            }
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
                verify(notification).autoClose();
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
        void messageArrivingDuringDismissalGetsAFreshNotification() throws ReflectiveOperationException {
            Trade trade = addTrade(true);
            notificationCenter.onAllServicesAndViewsInitialized();
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                ChatMessage first = message(trade, true);
                trade.getChatMessages().add(first);
                sendMessage(first);
                Notification closing = notifications.constructed().get(0);
                var isClosing = Notification.class.getDeclaredMethod("isClosing");
                isClosing.setAccessible(true);
                when((Boolean) isClosing.invoke(closing)).thenReturn(true);

                ChatMessage second = message(trade, true);
                trade.getChatMessages().add(second);
                sendMessage(second);
                assertEquals(2, notifications.constructed().size());
                Notification replacement = notifications.constructed().get(1);
                verify(replacement).message(Res.get("notification.chat.messages", 2));
                verify(replacement).autoClose();
                verify(replacement).show();

                closing.getIsHiddenProperty().set(true);
                ChatMessage third = message(trade, true);
                trade.getChatMessages().add(third);
                sendMessage(third);
                assertEquals(2, notifications.constructed().size());
                verify(replacement).message(Res.get("notification.chat.messages", 3));
                assertTrue(hasUnreadChat());
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
                verify(queued).hide();
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
        void restoredTradeBadgeMatchesNotificationAfterNewMessages() throws ReflectiveOperationException {
            List<Trade> restoredTrades = List.of(addTrade(true), addTrade(false));
            for (Trade trade : restoredTrades) {
                ChatMessage outgoing = message(trade, false);
                ChatMessage displayed = message(trade, true);
                displayed.setWasDisplayed(true);
                ChatMessage system = message(trade, true);
                system.setSystemMessage(true);
                trade.getChatMessages().addAll(message(trade, true), outgoing, displayed, system);
            }
            notificationCenter.onAllServicesAndViewsInitialized();

            for (Trade trade : restoredTrades) {
                ChatMessage outgoing = trade.getChatMessages().get(1);
                PendingTradesDataModel dataModel = mock(PendingTradesDataModel.class);
                var list = PendingTradesDataModel.class.getDeclaredField("list");
                list.setAccessible(true);
                list.set(dataModel, FXCollections.observableArrayList(new PendingTradesListItem(trade, null)));
                PendingTradesViewModel viewModel = mock(PendingTradesViewModel.class);
                var delegate = WithDataModel.class.getField("dataModel");
                delegate.setAccessible(true);
                delegate.set(viewModel, dataModel);
                PendingTradesView view = new PendingTradesView(viewModel, null, navigation, notificationCenter,
                        null, null, null, preferences, false, false);
                var updateCounts = PendingTradesView.class.getDeclaredMethod("updateNewChatMessagesByTradeMap");
                updateCounts.setAccessible(true);
                var counts = PendingTradesView.class.getDeclaredField("newChatMessagesByTradeMap");
                counts.setAccessible(true);
                updateCounts.invoke(view);
                assertEquals(Map.of(trade.getId(), 1L), counts.get(view));

                try (MockedConstruction<Notification> notifications = mockNotifications()) {
                    for (long unread = 2; unread <= 3; unread++) {
                        ChatMessage incoming = message(trade, true);
                        trade.getChatMessages().add(incoming);
                        sendMessage(incoming);
                        updateCounts.invoke(view);
                        assertEquals(Map.of(trade.getId(), unread), counts.get(view));
                        assertEquals(1, notifications.constructed().size());
                        verify(notifications.constructed().get(0)).message(Res.get("notification.chat.messages", unread));
                    }
                }

                trade.getChatMessages().stream().filter(chatMessage -> chatMessage != outgoing)
                        .forEach(chatMessage -> chatMessage.setWasDisplayed(true));
                updateCounts.invoke(view);
                assertEquals(Map.of(trade.getId(), 0L), counts.get(view));
                assertFalse(outgoing.isWasDisplayed());
            }
        }

        @Test
        void arbitratorStatusPersistsWhileIncomingChatAutoCloses() {
            Trade trade = addTrade(true);
            when(trade.isArbitrator()).thenReturn(true);
            Dispute dispute = mock(Dispute.class);
            ObservableList<ChatMessage> messages = FXCollections.observableArrayList();
            when(dispute.getChatMessages()).thenReturn(messages);
            when(arbitrationManager.isAgent(dispute)).thenReturn(true);
            when(arbitrationManager.findDispute(trade.getId())).thenReturn(Optional.of(dispute));
            when(arbitrationManager.findDispute(trade.getId(), 7)).thenReturn(Optional.of(dispute));
            disputes.add(dispute);
            notificationCenter.onAllServicesAndViewsInitialized();
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                ((ObjectProperty<Trade.DisputeState>) trade.disputeStateProperty()).set(Trade.DisputeState.DISPUTE_OPENED);
                Notification status = notifications.constructed().get(0);
                verify(status).show();
                verify(status, never()).autoClose();
                ChatMessage message = new ChatMessage(SupportType.ARBITRATION, trade.getId(), 7,
                        true, "Message to arbitrator", new NodeAddress("peer:9999"));
                messages.add(message);
                sendMessage(message);
                Notification chat = notifications.constructed().get(1);
                verify(chat).autoClose();
                verify(chat).show();
                assertFalse(message.isWasDisplayed());
            }
        }

        @Test
        void coalescesSupportMessagesWithoutChangingTicketNavigation() {
            notificationCenter.onAllServicesAndViewsInitialized();
            for (boolean agent : List.of(false, true)) {
                Dispute dispute = addDispute(addTrade(true), agent);
                ObservableList<ChatMessage> messages = dispute.getChatMessages();
                ChatMessage system = supportMessage(dispute, agent);
                system.setSystemMessage(true);
                ChatMessage outgoing = supportMessage(dispute, !agent);
                ChatMessage displayed = supportMessage(dispute, agent);
                displayed.setWasDisplayed(true);
                messages.addAll(system, outgoing, displayed);
                try (MockedConstruction<Notification> notifications = mockNotifications()) {
                    sendMessage(system);
                    sendMessage(outgoing);
                    sendMessage(displayed);
                    assertTrue(notifications.constructed().isEmpty());
                    assertEquals(0, dispute.unreadMessageCount(agent, false));
                    for (int i = 0; i < 2; i++) {
                        ChatMessage message = supportMessage(dispute, agent);
                        messages.add(message);
                        sendMessage(message);
                        assertEquals(i + 1, dispute.unreadMessageCount(agent, false));
                        assertEquals(i + 2, dispute.unreadMessageCount(agent));
                    }
                    assertEquals(1, notifications.constructed().size());
                    Notification notification = notifications.constructed().get(0);
                    verify(notification).message(Res.get("notification.chat.message"));
                    verify(notification).message(Res.get("notification.chat.messages", 2));
                    verify(notification).actionButtonText(Res.get("notification.chat.goToTicket"));
                    assertFalse(hasUnreadChat());
                }
            }
        }

        @Test
        void newDisputesAndUnreadSystemUpdatesAlertWithoutCountingAsChat() {
            notificationCenter.onAllServicesAndViewsInitialized();
            for (boolean agent : List.of(false, true)) {
                Dispute dispute = addDispute(addTrade(true), agent);
                assertEquals(0, dispute.unreadMessageCount(agent, false));
                assertEquals(1, dispute.getBadgeCountProperty().get());
                dispute.setDisputeSeen(agent);
                assertEquals(0, dispute.getBadgeCountProperty().get());

                ChatMessage system = supportMessage(dispute, !agent);
                system.setSystemMessage(true);
                dispute.getChatMessages().add(system);
                assertEquals(0, dispute.unreadMessageCount(agent, false));
                assertEquals(1, dispute.unreadMessageCount(agent));
                assertEquals(1, dispute.getBadgeCountProperty().get());
                dispute.setDisputeSeen(agent);
                assertEquals(1, dispute.getBadgeCountProperty().get());

                dispute.setChatMessagesSeen(agent);
                assertTrue(system.isWasDisplayed());
                assertEquals(0, dispute.unreadMessageCount(agent));
                assertEquals(0, dispute.unreadMessageCount(agent, false));
                assertEquals(0, dispute.getBadgeCountProperty().get());
            }
        }

        @Test
        void newTradeLightsPortfolioWithoutChatAndDoesNotRepeatAtConfirmation() {
            notificationCenter.onAllServicesAndViewsInitialized();
            Trade trade = addTrade(true);
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                setPhase(trade, Trade.Phase.DEPOSITS_PUBLISHED);
                assertTrue(notificationCenter.unreadPortfolioProperty().get());
                assertFalse(hasUnreadChat());
                assertEquals(1, notifications.constructed().size());
                verify(notifications.constructed().get(0)).message(Res.get("notification.trade.accepted", Res.get("shared.seller")));
                setPhase(trade, Trade.Phase.DEPOSITS_CONFIRMED);
                assertEquals(1, notifications.constructed().size());
                assertTrue(notificationCenter.unreadPortfolioProperty().get());
            }
        }

        @Test
        void takerKeepsUnreadActivityWithoutADuplicateTradeStartedToast() {
            notificationCenter.onAllServicesAndViewsInitialized();
            Trade trade = addTrade(false);
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                setPhase(trade, Trade.Phase.DEPOSITS_PUBLISHED);
                setPhase(trade, Trade.Phase.DEPOSITS_CONFIRMED);
                assertTrue(notificationCenter.unreadPortfolioProperty().get());
                assertTrue(notifications.constructed().isEmpty());
                setPhase(trade, Trade.Phase.DEPOSITS_UNLOCKED);
                assertEquals(1, notifications.constructed().size());
                verify(notifications.constructed().get(0)).message(Res.get("notification.trade.unlocked"));
            }
        }

        @Test
        void retainsLatestMilestoneAfterRestartWithoutReplayingAnOlderToast() {
            Trade buyer = addTrade(true);
            Trade seller = addTrade(true);
            when(seller.isBuyer()).thenReturn(false);
            when(seller.isSeller()).thenReturn(true);
            setPhase(buyer, Trade.Phase.DEPOSITS_FINALIZED);
            setPhase(seller, Trade.Phase.PAYMENT_SENT);
            notificationCenter.onAllServicesAndViewsInitialized();
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                setPhase(buyer, Trade.Phase.PAYMENT_SENT);
                setPhase(seller, Trade.Phase.PAYMENT_RECEIVED);
                assertTrue(notifications.constructed().isEmpty());
                assertTrue(notificationCenter.unreadPortfolioProperty().get());
                seenUpdates.put("NotificationCenter_DEPOSITS_FINALIZED" + buyer.getId(), true);
                seenUpdates.put("NotificationCenter_PAYMENT_SENT" + seller.getId(), true);
                assertFalse(notificationCenter.unreadPortfolioProperty().get());
            }
        }

        @Test
        void advancingPastMilestoneKeepsItsCurrentNotificationUntilAcknowledged() {
            notificationCenter.onAllServicesAndViewsInitialized();
            Trade trade = addTrade(false);
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                setPhase(trade, Trade.Phase.DEPOSITS_FINALIZED);
                Notification notification = notifications.constructed().get(0);
                ArgumentCaptor<BooleanSupplier> guard = ArgumentCaptor.forClass(BooleanSupplier.class);
                verify(notification).onlyShowIf(guard.capture());
                setPhase(trade, Trade.Phase.PAYMENT_SENT);
                verify(notification, never()).hide();
                assertTrue(guard.getValue().getAsBoolean());
                assertEquals(1, notifications.constructed().size());
            }
        }

        @Test
        void sendingPaymentBeforeFinalizationDoesNotInventAConfirmationNotice() {
            Trade trade = addTrade(true);
            when(trade.getNumDepositConfirmations()).thenReturn(10L);
            setPhase(trade, Trade.Phase.DEPOSITS_UNLOCKED);
            notificationCenter.onAllServicesAndViewsInitialized();
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                setPhase(trade, Trade.Phase.PAYMENT_SENT);
                assertTrue(notifications.constructed().isEmpty());
                seenUpdates.put("NotificationCenter_DEPOSITS_UNLOCKED" + trade.getId(), true);
                assertFalse(notificationCenter.unreadPortfolioProperty().get());
            }
        }

        @Test
        void laterConfirmationCountDoesNotAnnouncePaymentReadinessAfterPayment() {
            Trade trade = addTrade(false);
            when(trade.getNumDepositConfirmations()).thenReturn(10L);
            setPhase(trade, Trade.Phase.PAYMENT_SENT);
            notificationCenter.onAllServicesAndViewsInitialized();
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                when(trade.getNumDepositConfirmations()).thenReturn((long) Trade.NUM_BLOCKS_DEPOSITS_FINALIZED);
                setPhase(trade, Trade.Phase.PAYMENT_RECEIVED);
                assertTrue(notifications.constructed().isEmpty());
                setPayout(trade, Trade.PayoutState.PAYOUT_PUBLISHED);
                assertEquals(1, notifications.constructed().size());
                verify(notifications.constructed().get(0)).message(Res.get("notification.trade.completed"));
            }
        }

        @Test
        void unknownConfirmationsAtStartupDoNotInventAFinalizedMilestone() {
            Trade trade = addTrade(true);
            setPhase(trade, Trade.Phase.PAYMENT_SENT);
            when(trade.getNumDepositConfirmations()).thenReturn(null);
            seenUpdates.put("NotificationCenter_DEPOSITS_UNLOCKED" + trade.getId(), true);
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                notificationCenter.onAllServicesAndViewsInitialized();
                setPhase(trade, Trade.Phase.PAYMENT_RECEIVED);
                assertFalse(notificationCenter.unreadPortfolioProperty().get());
                assertTrue(notifications.constructed().isEmpty());
                verify(trade, never()).isDepositsFinalized();
            }
        }

        @Test
        void tradeActionLabelReflectsWhetherOpenTradesIsAlreadyVisible() {
            notificationCenter.onAllServicesAndViewsInitialized();
            when(navigation.getCurrentPath()).thenReturn(ViewPath.to(MainView.class));
            Trade first = addTrade(true);
            Trade second = addTrade(true);
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                setPhase(first, Trade.Phase.DEPOSITS_PUBLISHED);
                Notification outside = notifications.constructed().get(0);
                verify(outside).actionButtonTextWithGoTo("portfolio.tab.pendingTrades");
                ArgumentCaptor<Runnable> action = ArgumentCaptor.forClass(Runnable.class);
                verify(outside).onAction(action.capture());
                action.getValue().run();
                verify(navigation).navigateToWithData(first, MainView.class, PortfolioView.class, PendingTradesView.class);
                viewTrade(first);
                setPhase(second, Trade.Phase.DEPOSITS_PUBLISHED);
                Notification inside = notifications.constructed().get(1);
                verify(inside).actionButtonText(Res.get("notification.trade.selectTrade"));
            }
        }

        @Test
        void restoresUnseenTradeActivityWithoutReplayingStartupToasts() {
            Trade trade = addTrade(true);
            setPhase(trade, Trade.Phase.DEPOSITS_CONFIRMED);
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                notificationCenter.onAllServicesAndViewsInitialized();
                assertTrue(notificationCenter.unreadPortfolioProperty().get());
                assertTrue(notifications.constructed().isEmpty());
            }
        }

        @Test
        void recognizesPreviouslyAcknowledgedPhaseAndLegacyCompletion() {
            Trade trade = addTrade(true);
            Trade completed = addTrade(false);
            setPhase(trade, Trade.Phase.DEPOSITS_CONFIRMED);
            setPhase(completed, Trade.Phase.PAYMENT_RECEIVED);
            setPayout(completed, Trade.PayoutState.PAYOUT_PUBLISHED);
            seenUpdates.put("NotificationCenter_DEPOSITS_PUBLISHED" + trade.getId(), true);
            seenUpdates.put("NotificationCenter_PAYMENT_RECEIVED" + completed.getId(), true);
            notificationCenter.onAllServicesAndViewsInitialized();
            assertFalse(notificationCenter.unreadPortfolioProperty().get());
        }

        @Test
        void viewingAcknowledgesOnlyThatTradeAndLeavesChatUnread() {
            Trade first = addTrade(true);
            Trade second = addTrade(false);
            setPhase(first, Trade.Phase.DEPOSITS_PUBLISHED);
            setPhase(second, Trade.Phase.DEPOSITS_PUBLISHED);
            first.getChatMessages().add(message(first, true));
            notificationCenter.onAllServicesAndViewsInitialized();
            BooleanBinding firstUnseen = notificationCenter.unseenTradeUpdateProperty(first.getId());
            BooleanBinding secondUnseen = notificationCenter.unseenTradeUpdateProperty(second.getId());
            try {
                assertTrue(firstUnseen.get());
                assertTrue(secondUnseen.get());
                viewTrade(first);
                assertFalse(firstUnseen.get());
                assertTrue(secondUnseen.get());
                viewTrade(second);
                assertFalse(secondUnseen.get());
                assertTrue(hasUnreadChat());
                assertTrue(notificationCenter.unreadPortfolioProperty().get());
                markRead(first);
                assertFalse(notificationCenter.unreadPortfolioProperty().get());
                verify(preferences, times(1)).dontShowAgain("NotificationCenter_DEPOSITS_PUBLISHED" + first.getId(), true);
            } finally {
                firstUnseen.dispose();
                secondUnseen.dispose();
            }
        }

        @Test
        void dismissalLeavesTradeUnseenAndActionNavigatesWithoutAcknowledging() {
            notificationCenter.onAllServicesAndViewsInitialized();
            Trade trade = addTrade(true);
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                setPhase(trade, Trade.Phase.DEPOSITS_PUBLISHED);
                Notification notification = notifications.constructed().get(0);
                verify(notification, never()).autoClose();
                notification.getIsHiddenProperty().set(true);
                assertTrue(notificationCenter.unreadPortfolioProperty().get());
                assertTrue(seenUpdates.isEmpty());
                ArgumentCaptor<Runnable> action = ArgumentCaptor.forClass(Runnable.class);
                verify(notification).onAction(action.capture());
                action.getValue().run();
                verify(navigation).navigateToWithData(trade, MainView.class, PortfolioView.class, PendingTradesView.class);
                assertTrue(seenUpdates.isEmpty());
                assertTrue(notificationCenter.unreadPortfolioProperty().get());
            }
        }

        @Test
        void payoutAloneNotifiesAfterPaymentNotificationWasSeen() {
            Trade trade = addTrade(true);
            when(trade.isBuyer()).thenReturn(false);
            when(trade.isSeller()).thenReturn(true);
            setPhase(trade, Trade.Phase.PAYMENT_SENT);
            seenUpdates.put("NotificationCenter_PAYMENT_SENT" + trade.getId(), true);
            notificationCenter.onAllServicesAndViewsInitialized();
            assertFalse(notificationCenter.unreadPortfolioProperty().get());
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                setPayout(trade, Trade.PayoutState.PAYOUT_PUBLISHED);
                assertTrue(notificationCenter.unreadPortfolioProperty().get());
                assertEquals(1, notifications.constructed().size());
                verify(notifications.constructed().get(0)).message(Res.get("notification.trade.completed"));
                setPayout(trade, Trade.PayoutState.PAYOUT_CONFIRMED);
                assertEquals(1, notifications.constructed().size());
                viewTrade(trade);
                assertFalse(notificationCenter.unreadPortfolioProperty().get());
                assertTrue(seenUpdates.get("NotificationCenter_PAYOUT_PUBLISHED" + trade.getId()));
            }
        }

        @Test
        void viewedTradeAcknowledgesNewMilestonesWithoutToast() {
            Trade trade = addTrade(false);
            setPhase(trade, Trade.Phase.DEPOSITS_PUBLISHED);
            notificationCenter.onAllServicesAndViewsInitialized();
            viewTrade(trade);
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                setPhase(trade, Trade.Phase.DEPOSITS_UNLOCKED);
                setPhase(trade, Trade.Phase.DEPOSITS_FINALIZED);
                assertFalse(notificationCenter.unreadPortfolioProperty().get());
                assertTrue(notifications.constructed().isEmpty());
                assertTrue(seenUpdates.get("NotificationCenter_DEPOSITS_FINALIZED" + trade.getId()));
            }
        }

        @Test
        void cachedSelectionOutsidePendingTradesDoesNotAcknowledge() {
            Trade trade = addTrade(true);
            setPhase(trade, Trade.Phase.DEPOSITS_PUBLISHED);
            notificationCenter.onAllServicesAndViewsInitialized();
            notificationCenter.setViewedTradeId(trade.getId());
            assertTrue(notificationCenter.unreadPortfolioProperty().get());
            assertTrue(seenUpdates.isEmpty());
            viewTrade(trade);
            notificationCenter.setViewedTradeId(null);
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                setPhase(trade, Trade.Phase.DEPOSITS_UNLOCKED);
                assertTrue(notificationCenter.unreadPortfolioProperty().get());
                assertEquals(1, notifications.constructed().size());
            }
        }

        @Test
        void removingTradeDiscardsItsToastDotAndSubscriptions() {
            notificationCenter.onAllServicesAndViewsInitialized();
            Trade trade = addTrade(true);
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                setPhase(trade, Trade.Phase.DEPOSITS_PUBLISHED);
                Notification notification = notifications.constructed().get(0);
                ArgumentCaptor<BooleanSupplier> guard = ArgumentCaptor.forClass(BooleanSupplier.class);
                verify(notification).onlyShowIf(guard.capture());
                assertTrue(guard.getValue().getAsBoolean());
                trades.remove(trade);
                verify(notification).hide();
                assertFalse(guard.getValue().getAsBoolean());
                assertFalse(notificationCenter.unreadPortfolioProperty().get());
                setPayout(trade, Trade.PayoutState.PAYOUT_PUBLISHED);
                assertEquals(1, notifications.constructed().size());
            }
        }

        @Test
        void newerMilestoneAndViewingInvalidateQueuedToasts() {
            notificationCenter.onAllServicesAndViewsInitialized();
            Trade trade = addTrade(true);
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                setPhase(trade, Trade.Phase.DEPOSITS_PUBLISHED);
                Notification first = notifications.constructed().get(0);
                ArgumentCaptor<BooleanSupplier> firstGuard = ArgumentCaptor.forClass(BooleanSupplier.class);
                verify(first).onlyShowIf(firstGuard.capture());
                setPhase(trade, Trade.Phase.DEPOSITS_FINALIZED);
                verify(first).hide();
                assertFalse(firstGuard.getValue().getAsBoolean());
                Notification second = notifications.constructed().get(1);
                verify(second).message(Res.get("notification.trade.finalized", Trade.NUM_BLOCKS_DEPOSITS_FINALIZED));
                ArgumentCaptor<BooleanSupplier> secondGuard = ArgumentCaptor.forClass(BooleanSupplier.class);
                verify(second).onlyShowIf(secondGuard.capture());
                assertTrue(secondGuard.getValue().getAsBoolean());
                viewTrade(trade);
                assertFalse(secondGuard.getValue().getAsBoolean());
                verify(second).hide();
            }
        }

        @Test
        void ignoresCompletedAndUnpublishedTradesAndArbitratorPaymentMilestones() {
            Trade arbitrator = addTrade(true);
            when(arbitrator.isArbitrator()).thenReturn(true);
            setPhase(arbitrator, Trade.Phase.DEPOSITS_FINALIZED);
            Trade completed = addTrade(true);
            when(completed.isCompleted()).thenReturn(true);
            setPhase(completed, Trade.Phase.PAYMENT_RECEIVED);
            setPayout(completed, Trade.PayoutState.PAYOUT_PUBLISHED);
            addTrade(true);
            notificationCenter.onAllServicesAndViewsInitialized();
            assertFalse(notificationCenter.unreadPortfolioProperty().get());
        }

        @Test
        void arbitratorCompletionFollowsPhaseChangesAndSurvivesAutomaticRemoval() {
            Trade trade = addTrade(false);
            when(trade.isArbitrator()).thenReturn(true);
            when(trade.isBuyer()).thenReturn(false);
            when(navigation.getCurrentPath()).thenReturn(ViewPath.to(MainView.class));
            notificationCenter.onAllServicesAndViewsInitialized();
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                setPhase(trade, Trade.Phase.DEPOSITS_PUBLISHED);
                setPhase(trade, Trade.Phase.DEPOSITS_UNLOCKED);
                setPhase(trade, Trade.Phase.DEPOSITS_FINALIZED);
                setPhase(trade, Trade.Phase.PAYMENT_SENT);
                setPayout(trade, Trade.PayoutState.PAYOUT_PUBLISHED);
                assertTrue(notifications.constructed().isEmpty());
                setPhase(trade, Trade.Phase.PAYMENT_RECEIVED);
                assertEquals(1, notifications.constructed().size());
                Notification notification = notifications.constructed().get(0);
                verify(notification).message(Res.get("notification.trade.completed"));
                verify(notification).show();
                verify(notification, never()).autoClose();
                assertFalse(notificationCenter.unreadPortfolioProperty().get());
                when(trade.isCompleted()).thenReturn(true);
                trades.remove(trade);
                verify(notification, never()).hide();
                ArgumentCaptor<Runnable> close = ArgumentCaptor.forClass(Runnable.class);
                verify(notification).onClose(close.capture());
                close.getValue().run();
                assertTrue(seenUpdates.get("NotificationCenter_PAYMENT_RECEIVED" + trade.getId()));
            }
        }

        @Test
        void arbitratorAlreadyCompletedBeforePhaseChangeDoesNotGainANewAlert() {
            Trade trade = addTrade(false);
            when(trade.isArbitrator()).thenReturn(true);
            when(navigation.getCurrentPath()).thenReturn(ViewPath.to(MainView.class));
            notificationCenter.onAllServicesAndViewsInitialized();
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                setPayout(trade, Trade.PayoutState.PAYOUT_PUBLISHED);
                when(trade.isCompleted()).thenReturn(true);
                setPhase(trade, Trade.Phase.PAYMENT_RECEIVED);
                trades.remove(trade);
                assertTrue(notifications.constructed().isEmpty());
            }
        }

        @Test
        void arbitratorCompletionKeepsInitialPhaseAndSeenPreferenceBehavior() {
            Trade trade = addTrade(false);
            when(trade.isArbitrator()).thenReturn(true);
            when(navigation.getCurrentPath()).thenReturn(ViewPath.to(MainView.class));
            setPhase(trade, Trade.Phase.PAYMENT_RECEIVED);
            setPayout(trade, Trade.PayoutState.PAYOUT_PUBLISHED);
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                notificationCenter.onAllServicesAndViewsInitialized();
                assertEquals(1, notifications.constructed().size());
                Notification notification = notifications.constructed().get(0);
                verify(notification).actionButtonTextWithGoTo("portfolio.tab.pendingTrades");
                ArgumentCaptor<Runnable> action = ArgumentCaptor.forClass(Runnable.class);
                verify(notification).onAction(action.capture());
                action.getValue().run();
                verify(navigation).navigateToWithData(trade, MainView.class, PortfolioView.class, PendingTradesView.class);
                assertTrue(seenUpdates.get("NotificationCenter_PAYMENT_RECEIVED" + trade.getId()));
                trades.remove(trade);
                trades.add(trade);
                assertEquals(1, notifications.constructed().size());
            }
        }

        @Test
        void preferenceResetRestoresDotsWithoutReplayingToasts() {
            Trade trade = addTrade(true);
            when(trade.isBuyer()).thenReturn(false);
            when(trade.isSeller()).thenReturn(true);
            setPhase(trade, Trade.Phase.DEPOSITS_CONFIRMED);
            seenUpdates.put("NotificationCenter_DEPOSITS_PUBLISHED" + trade.getId(), true);
            notificationCenter.onAllServicesAndViewsInitialized();
            assertFalse(notificationCenter.unreadPortfolioProperty().get());
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                seenUpdates.clear();
                assertTrue(notificationCenter.unreadPortfolioProperty().get());
                assertTrue(notifications.constructed().isEmpty());
                setPhase(trade, Trade.Phase.DEPOSITS_UNLOCKED);
                setPhase(trade, Trade.Phase.DEPOSITS_FINALIZED);
                assertTrue(notifications.constructed().isEmpty());
                setPhase(trade, Trade.Phase.PAYMENT_SENT);
                assertEquals(1, notifications.constructed().size());
                verify(notifications.constructed().get(0)).message(Res.get("notification.trade.paymentSent"));
            }
        }

        @Test
        void queuedPreferenceResetDoesNotSuppressANewerMilestone() {
            Trade trade = addTrade(true);
            setPhase(trade, Trade.Phase.DEPOSITS_CONFIRMED);
            seenUpdates.put("NotificationCenter_DEPOSITS_PUBLISHED" + trade.getId(), true);
            notificationCenter.onAllServicesAndViewsInitialized();
            List<Runnable> updates = new ArrayList<>();
            UserThread.setExecutor(updates::add);
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                seenUpdates.clear();
                setPhase(trade, Trade.Phase.DEPOSITS_UNLOCKED);
                drainUpdates(updates);
                assertTrue(notificationCenter.unreadPortfolioProperty().get());
                assertEquals(1, notifications.constructed().size());
                verify(notifications.constructed().get(0)).message(Res.get("notification.trade.unlocked"));
            }
        }

        @Test
        void queuedTradeUpdatesUseLatestStateAndIgnoreRemovedTrades() {
            notificationCenter.onAllServicesAndViewsInitialized();
            Trade trade = addTrade(false);
            List<Runnable> updates = new ArrayList<>();
            UserThread.setExecutor(updates::add);
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                setPhase(trade, Trade.Phase.DEPOSITS_UNLOCKED);
                setPhase(trade, Trade.Phase.DEPOSITS_FINALIZED);
                assertFalse(notificationCenter.unreadPortfolioProperty().get());
                drainUpdates(updates);
                assertEquals(1, notifications.constructed().size());
                verify(notifications.constructed().get(0)).message(Res.get("notification.trade.finalized", Trade.NUM_BLOCKS_DEPOSITS_FINALIZED));
                setPayout(trade, Trade.PayoutState.PAYOUT_PUBLISHED);
                trades.remove(trade);
                drainUpdates(updates);
                assertEquals(1, notifications.constructed().size());
                assertFalse(notificationCenter.unreadPortfolioProperty().get());
            }
        }

        @Test
        void startupSuppressionSurvivesQueuedCallbacksAndNextConfirmation() {
            Trade trade = addTrade(true);
            setPhase(trade, Trade.Phase.DEPOSITS_PUBLISHED);
            List<Runnable> updates = new ArrayList<>();
            UserThread.setExecutor(updates::add);
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                notificationCenter.onAllServicesAndViewsInitialized();
                drainUpdates(updates);
                assertTrue(notificationCenter.unreadPortfolioProperty().get());
                setPhase(trade, Trade.Phase.DEPOSITS_CONFIRMED);
                drainUpdates(updates);
                assertTrue(notifications.constructed().isEmpty());
                setPhase(trade, Trade.Phase.DEPOSITS_FINALIZED);
                drainUpdates(updates);
                assertEquals(1, notifications.constructed().size());
            }
        }

        @Test
        void sellerReceivesPaymentMilestoneWhileChatRemainsSeparate() {
            Trade trade = addTrade(false);
            when(trade.isBuyer()).thenReturn(false);
            when(trade.isSeller()).thenReturn(true);
            setPhase(trade, Trade.Phase.DEPOSITS_FINALIZED);
            seenUpdates.put("NotificationCenter_DEPOSITS_PUBLISHED" + trade.getId(), true);
            notificationCenter.onAllServicesAndViewsInitialized();
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                setPhase(trade, Trade.Phase.PAYMENT_SENT);
                assertTrue(notificationCenter.unreadPortfolioProperty().get());
                assertFalse(hasUnreadChat());
                assertEquals(1, notifications.constructed().size());
                verify(notifications.constructed().get(0)).message(Res.get("notification.trade.paymentSent"));
            }
        }

        @Test
        void restoringAnExistingTradeKeepsItsDotWithoutReplayingTheOldMilestone() {
            Trade trade = addTrade(true);
            trades.remove(trade);
            setPhase(trade, Trade.Phase.DEPOSITS_PUBLISHED);
            notificationCenter.onAllServicesAndViewsInitialized();
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                trades.add(trade);
                assertTrue(notificationCenter.unreadPortfolioProperty().get());
                setPhase(trade, Trade.Phase.DEPOSITS_CONFIRMED);
                assertTrue(notifications.constructed().isEmpty());
                setPhase(trade, Trade.Phase.DEPOSITS_FINALIZED);
                assertEquals(1, notifications.constructed().size());
            }
        }

        @Test
        void makerAcceptanceSurvivesDepositsConfirmingBeforeQueuedUpdatesRun() {
            notificationCenter.onAllServicesAndViewsInitialized();
            Trade trade = addTrade(true);
            List<Runnable> updates = new ArrayList<>();
            UserThread.setExecutor(updates::add);
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                setPhase(trade, Trade.Phase.DEPOSITS_PUBLISHED);
                setPhase(trade, Trade.Phase.DEPOSITS_CONFIRMED);
                drainUpdates(updates);
                assertEquals(1, notifications.constructed().size());
                verify(notifications.constructed().get(0)).message(Res.get("notification.trade.accepted", Res.get("shared.seller")));
            }
        }

        @Test
        void newTradeStillNotifiesIfItAdvancesBeforeSubscriptionsAttach() {
            notificationCenter.onAllServicesAndViewsInitialized();
            List<Runnable> updates = new ArrayList<>();
            UserThread.setExecutor(updates::add);
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                Trade trade = addTrade(true);
                setPhase(trade, Trade.Phase.DEPOSITS_PUBLISHED);
                drainUpdates(updates);
                assertTrue(notificationCenter.unreadPortfolioProperty().get());
                assertEquals(1, notifications.constructed().size());
                verify(notifications.constructed().get(0)).message(Res.get("notification.trade.accepted", Res.get("shared.seller")));
            }
        }

        @Test
        void startupRestoreDoesNotSuppressAMilestoneReachedBeforeItRuns() {
            Trade trade = addTrade(true);
            setPhase(trade, Trade.Phase.DEPOSITS_CONFIRMED);
            List<Runnable> updates = new ArrayList<>();
            UserThread.setExecutor(updates::add);
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                notificationCenter.onAllServicesAndViewsInitialized();
                // the trade state advances before its queued phase update and the restore refresh run
                when(trade.getPhase()).thenReturn(Trade.Phase.DEPOSITS_UNLOCKED);
                drainUpdates(updates);
                assertTrue(notifications.constructed().isEmpty());
                setPhase(trade, Trade.Phase.DEPOSITS_UNLOCKED);
                drainUpdates(updates);
                assertEquals(1, notifications.constructed().size());
                verify(notifications.constructed().get(0)).message(Res.get("notification.trade.unlocked"));
            }
        }

        @Test
        void startupRestoreDoesNotReplayPublishedPayoutAfterPhaseAdvances() {
            Trade trade = addTrade(true);
            setPhase(trade, Trade.Phase.PAYMENT_SENT);
            setPayout(trade, Trade.PayoutState.PAYOUT_PUBLISHED);
            List<Runnable> updates = new ArrayList<>();
            UserThread.setExecutor(updates::add);
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                notificationCenter.onAllServicesAndViewsInitialized();
                // the phase advances before its property update, but the payout was already published
                when(trade.getPhase()).thenReturn(Trade.Phase.PAYMENT_RECEIVED);
                drainUpdates(updates);
                assertTrue(notificationCenter.unreadPortfolioProperty().get());
                assertTrue(notifications.constructed().isEmpty());
                setPhase(trade, Trade.Phase.PAYMENT_RECEIVED);
                drainUpdates(updates);
                assertTrue(notifications.constructed().isEmpty());
                setPayout(trade, Trade.PayoutState.PAYOUT_CONFIRMED);
                drainUpdates(updates);
                assertTrue(notificationCenter.unreadPortfolioProperty().get());
                assertTrue(notifications.constructed().isEmpty());
            }
        }

        @Test
        void startupRestoreDoesNotSuppressAPayoutPublishedBeforeItRuns() {
            Trade trade = addTrade(true);
            setPhase(trade, Trade.Phase.PAYMENT_SENT);
            List<Runnable> updates = new ArrayList<>();
            UserThread.setExecutor(updates::add);
            try (MockedConstruction<Notification> notifications = mockNotifications()) {
                notificationCenter.onAllServicesAndViewsInitialized();
                // the payout advances before its queued property update and the restore refresh run
                when(trade.isPayoutPublished()).thenReturn(true);
                drainUpdates(updates);
                assertTrue(notifications.constructed().isEmpty());
                setPayout(trade, Trade.PayoutState.PAYOUT_PUBLISHED);
                drainUpdates(updates);
                assertEquals(1, notifications.constructed().size());
                verify(notifications.constructed().get(0)).message(Res.get("notification.trade.completed"));
                setPayout(trade, Trade.PayoutState.PAYOUT_CONFIRMED);
                drainUpdates(updates);
                assertEquals(1, notifications.constructed().size());
            }
        }

        private void drainUpdates(List<Runnable> updates) {
            while (!updates.isEmpty()) updates.remove(0).run();
        }

        private void setPhase(Trade trade, Trade.Phase phase) {
            ((ObjectProperty<Trade.Phase>) trade.statePhaseProperty()).set(phase);
        }

        private void setPayout(Trade trade, Trade.PayoutState payout) {
            ((ObjectProperty<Trade.PayoutState>) trade.payoutStateProperty()).set(payout);
        }

        private void viewTrade(Trade trade) {
            when(navigation.getCurrentPath()).thenReturn(ViewPath.to(MainView.class, PortfolioView.class, PendingTradesView.class));
            notificationCenter.setViewedTradeId(trade.getId());
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
            SimpleObjectProperty<Trade.Phase> phase = new SimpleObjectProperty<>(Trade.Phase.INIT);
            SimpleObjectProperty<Trade.PayoutState> payout = new SimpleObjectProperty<>(Trade.PayoutState.PAYOUT_UNPUBLISHED);
            when(trade.statePhaseProperty()).thenReturn(phase);
            when(trade.payoutStateProperty()).thenReturn(payout);
            when(trade.getPhase()).thenAnswer(invocation -> phase.get());
            when(trade.isDepositsPublished()).thenAnswer(invocation -> phase.get().ordinal() >= Trade.Phase.DEPOSITS_PUBLISHED.ordinal());
            when(trade.getNumDepositConfirmations()).thenAnswer(invocation ->
                    phase.get().ordinal() >= Trade.Phase.DEPOSITS_FINALIZED.ordinal() ? (long) Trade.NUM_BLOCKS_DEPOSITS_FINALIZED : 0L);
            when(trade.isPayoutPublished()).thenAnswer(invocation -> payout.get().ordinal() >= Trade.PayoutState.PAYOUT_PUBLISHED.ordinal());
            when(trade.isBuyer()).thenReturn(true);
            when(trade.disputeStateProperty()).thenReturn(new SimpleObjectProperty<>(Trade.DisputeState.NO_DISPUTE));
            when(tradeManager.getOpenTrade(trade.getId())).thenReturn(Optional.of(trade));
            trades.add(trade);
            return trade;
        }

        private ChatMessage message(Trade trade, boolean incoming) {
            return new ChatMessage(SupportType.TRADE, trade.getId(), 0,
                    incoming ? trade.isMaker() : !trade.isMaker(), "Peer message", new NodeAddress("peer:9999"));
        }

        private Dispute addDispute(Trade trade, boolean agent) {
            Dispute dispute = new Dispute(0L, trade.getId(), 0, true, true, true, null, 0L, 0L,
                    null, null, null, null, "", null, null, null, null, null, false, SupportType.ARBITRATION);
            when(arbitrationManager.isAgent(dispute)).thenReturn(agent);
            when(arbitrationManager.findDispute(trade.getId(), 0)).thenReturn(Optional.of(dispute));
            disputes.add(dispute);
            return dispute;
        }

        private ChatMessage supportMessage(Dispute dispute, boolean senderIsTrader) {
            return new ChatMessage(SupportType.ARBITRATION, dispute.getTradeId(), dispute.getTraderId(),
                    senderIsTrader, "Private support message", new NodeAddress("peer:9999"));
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
