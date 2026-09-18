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

package haveno.desktop.main.overlays.notifications;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import haveno.common.ThreadUtils;
import haveno.common.UserThread;
import haveno.common.util.Utilities;
import haveno.core.api.NotificationListener;
import haveno.core.locale.Res;
import haveno.core.support.SupportType;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.DisputeList;
import haveno.core.support.dispute.DisputeManager;
import haveno.core.support.dispute.arbitration.ArbitrationManager;
import haveno.core.support.dispute.mediation.MediationManager;
import haveno.core.support.dispute.refund.RefundManager;
import haveno.core.support.messages.ChatMessage;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.user.Preferences;
import haveno.desktop.Navigation;
import haveno.desktop.main.MainView;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.portfolio.PortfolioView;
import haveno.desktop.main.portfolio.pendingtrades.PendingTradesView;
import haveno.desktop.main.support.SupportView;
import haveno.desktop.main.support.dispute.DisputeView;
import haveno.desktop.main.support.dispute.agent.arbitration.ArbitratorView;
import haveno.desktop.main.support.dispute.agent.mediation.MediatorView;
import haveno.desktop.main.support.dispute.agent.refund.RefundAgentView;
import haveno.desktop.main.support.dispute.client.arbitration.ArbitrationClientView;
import haveno.desktop.main.support.dispute.client.mediation.MediationClientView;
import haveno.desktop.main.support.dispute.client.refund.RefundClientView;
import haveno.proto.grpc.NotificationMessage;
import haveno.proto.grpc.NotificationMessage.NotificationType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.MapChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;
import javafx.scene.Scene;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.stage.Window;
import javax.annotation.Nullable;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;

@Slf4j
@Singleton
public class NotificationCenter {

    private static final long AMBIGUOUS_PAYMENT_NOTIFICATION_THROTTLE_NANOS = TimeUnit.MINUTES.toNanos(10);

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Static
    ///////////////////////////////////////////////////////////////////////////////////////////

    private static final String NOTIFICATION_KEY_PREFIX = "NotificationCenter_";

    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    private final static List<Notification> notifications = new ArrayList<>();

    static void add(Notification notification) {
        notifications.add(notification);
    }

    static boolean useAnimations;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Instance fields
    ///////////////////////////////////////////////////////////////////////////////////////////

    private final TradeManager tradeManager;
    private final ArbitrationManager arbitrationManager;
    private final MediationManager mediationManager;
    private final RefundManager refundManager;
    private final Navigation navigation;
    private final Preferences preferences;

    private final Map<String, Subscription> disputeStateSubscriptionsMap = new HashMap<>();
    private final Map<String, Subscription> tradePhaseSubscriptionsMap = new HashMap<>();
    private final Map<String, Long> ambiguousPaymentNotificationTimesNanos = new HashMap<>();
    private final Map<String, Notification> ambiguousPaymentNotifications = new HashMap<>();
    private final Map<String, Subscription> tradePayoutSubscriptionsMap = new HashMap<>();
    private final ObservableMap<String, String> unseenTradeUpdates = FXCollections.observableHashMap();
    private final Map<String, Notification> tradeNotifications = new HashMap<>();
    private final Set<String> notifiedTradeUpdates = new HashSet<>();
    private final ReadOnlyBooleanWrapper unreadPortfolio = new ReadOnlyBooleanWrapper();
    private final Set<ObservableList<ChatMessage>> focusedChats = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ObservableList<ChatMessage>> observedChats = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<ObservableList<ChatMessage>, Notification> chatNotifications = new IdentityHashMap<>();
    private final Set<String> notifiedChatMessages = new HashSet<>();
    private final ListChangeListener<ChatMessage> chatMessagesListener = change -> UserThread.execute(this::refreshChatState);
    private final ReadOnlyBooleanWrapper unreadTradeChat = new ReadOnlyBooleanWrapper();
    private Window mainWindow;
    @Nullable
    private String viewedTradeId;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, initialisation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public NotificationCenter(TradeManager tradeManager,
                              ArbitrationManager arbitrationManager,
                              MediationManager mediationManager,
                              RefundManager refundManager,
                              Preferences preferences,
                              Navigation navigation) {
        this.tradeManager = tradeManager;
        this.arbitrationManager = arbitrationManager;
        this.mediationManager = mediationManager;
        this.refundManager = refundManager;
        this.navigation = navigation;
        this.preferences = preferences;
        unreadPortfolio.bind(unreadTradeChat.or(Bindings.isNotEmpty(unseenTradeUpdates)));

        EasyBind.subscribe(preferences.getUseAnimationsProperty(), useAnimations -> NotificationCenter.useAnimations = useAnimations);
    }

    public void onAllServicesAndViewsInitialized() {
        tradeManager.addAmbiguousPaymentRejectedListener(this::onAmbiguousPaymentRejected);

        Scene scene = MainView.getRootContainer().getScene();
        mainWindow = scene.getWindow();
        // acknowledge content interaction after selection and focus settle, not title-bar drags
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (!Notification.isNotificationTarget(event.getTarget())) UserThread.execute(() -> setViewedTradeId(viewedTradeId));
        });
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!Notification.isNotificationTarget(event.getTarget())) UserThread.execute(() -> setViewedTradeId(viewedTradeId));
        });
        tradeManager.getObservableList().addListener((ListChangeListener<Trade>) change ->
                UserThread.execute(this::refreshChatState));
        for (DisputeManager<? extends DisputeList<Dispute>> manager : getDisputeManagers()) {
            manager.getDisputesAsObservableList().addListener((ListChangeListener<Dispute>) change ->
                    UserThread.execute(this::refreshChatState));
        }
        refreshChatState();

        tradeManager.getObservableList().addListener((ListChangeListener<Trade>) change -> {
            while (change.next()) {
                List<Trade> removed = new ArrayList<>(change.getRemoved());
                List<Trade> added = new ArrayList<>(change.getAddedSubList());
                // capture new trades before queued subscription setup can observe a later phase
                List<Trade> newTrades = added.stream().filter(trade -> !trade.isDepositsPublished()).toList();
                UserThread.execute(() -> {
                    removed.forEach(this::removeTradeSubscriptions);
                    added.forEach(trade -> addTradeSubscriptions(trade, newTrades.contains(trade)));
                });
            }
        });
        snapshot(tradeManager.getObservableList()).forEach(trade -> addTradeSubscriptions(trade, false));
        preferences.getDontShowAgainMapAsObservable().addListener((MapChangeListener<String, Boolean>) change -> {
            String key = change.getKey();
            if (key.startsWith(NOTIFICATION_KEY_PREFIX)) {
                boolean wasRemoved = change.wasRemoved();
                UserThread.execute(() -> {
                    // restore the cleared event's dot without suppressing newer milestones
                    if (wasRemoved) notifiedTradeUpdates.add(key);
                    snapshot(tradeManager.getObservableList()).forEach(trade -> refreshTradeNotification(trade, null));
                });
            }
        });

        // show popups for chat and error notifications
        tradeManager.getNotificationService().addListener(new NotificationListener() {
            @Override
            public void onMessage(@NonNull NotificationMessage message) {
                UserThread.execute(() -> {
                    if (message.getType() == NotificationType.ERROR) {
                        new Popup().warning(message.getMessage()).show();
                    } else if (message.getType() == NotificationType.CHAT_MESSAGE) {
                        onChatMessage(message.getChatMessage());
                    }
                });
            }
        });
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Setter/Getter
    ///////////////////////////////////////////////////////////////////////////////////////////

    public ReadOnlyBooleanProperty unreadTradeChatProperty() {
        return unreadTradeChat.getReadOnlyProperty();
    }

    public ReadOnlyBooleanProperty unreadPortfolioProperty() {
        return unreadPortfolio.getReadOnlyProperty();
    }

    public BooleanBinding unseenTradeUpdateProperty(String tradeId) {
        return Bindings.createBooleanBinding(() -> unseenTradeUpdates.containsKey(tradeId), unseenTradeUpdates);
    }

    public void setViewedTradeId(@Nullable String viewedTradeId) {
        this.viewedTradeId = viewedTradeId;
        if (viewedTradeId != null) {
            snapshot(tradeManager.getObservableList()).stream()
                    .filter(trade -> trade.getId().equals(viewedTradeId))
                    .findFirst().ifPresent(trade -> refreshTradeNotification(trade, null));
        }
    }

    public void onChatFocusChanged(ObservableList<ChatMessage> messages, boolean focused, Runnable persist) {
        if (focused) {
            focusedChats.add(messages);
            List<ChatMessage> unread = snapshot(messages).stream().filter(message -> !message.isWasDisplayed()).toList();
            if (!unread.isEmpty()) {
                unread.forEach(message -> message.setWasDisplayed(true));
                persist.run();
            }
            dismissChatNotification(messages);
        } else {
            focusedChats.remove(messages);
        }
        refreshChatState();
    }

    public boolean isChatFocused(ObservableList<ChatMessage> messages) {
        return focusedChats.contains(messages);
    }

    private void navigateToTrade(Trade trade) {
        navigation.navigateToWithData(new PendingTradesView.OpenChatRequest(trade),
                MainView.class, PortfolioView.class, PendingTradesView.class);
    }

    private void navigateToDispute(Dispute dispute, DisputeManager<? extends DisputeList<Dispute>> manager) {
        boolean agent = manager.isAgent(dispute);
        Class<? extends DisputeView> viewClass = manager == arbitrationManager ?
                (agent ? ArbitratorView.class : ArbitrationClientView.class) : manager == mediationManager ?
                (agent ? MediatorView.class : MediationClientView.class) :
                (agent ? RefundAgentView.class : RefundClientView.class);
        navigation.navigateToWithData(dispute, MainView.class, SupportView.class, viewClass);
    }

    private List<DisputeManager<? extends DisputeList<Dispute>>> getDisputeManagers() {
        return List.of(arbitrationManager, mediationManager, refundManager);
    }

    private static <T> List<T> snapshot(ObservableList<T> list) {
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    private static boolean isUnreadChat(ChatMessage message, boolean senderFlag) {
        return !message.isWasDisplayed() && !message.isSystemMessage() && message.isSenderIsTrader() == senderFlag;
    }

    private void refreshChatState() {
        Set<ObservableList<ChatMessage>> currentChats = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean hasUnreadTradeChat = false;
        for (Trade trade : snapshot(tradeManager.getObservableList())) {
            ObservableList<ChatMessage> messages = trade.getChatMessages();
            observeChat(messages, currentChats);
            if (!trade.isArbitrator() && !isChatFocused(messages) &&
                    snapshot(messages).stream().anyMatch(message -> isUnreadChat(message, trade.isMaker()))) {
                hasUnreadTradeChat = true;
            }
        }
        for (DisputeManager<? extends DisputeList<Dispute>> manager : getDisputeManagers()) {
            for (Dispute dispute : snapshot(manager.getDisputesAsObservableList())) {
                observeChat(dispute.getChatMessages(), currentChats);
                boolean agent = manager.isAgent(dispute);
                dispute.refreshAlertLevel(agent);
            }
        }
        observedChats.removeIf(messages -> {
            if (currentChats.contains(messages)) return false;
            synchronized (messages) {
                messages.removeListener(chatMessagesListener);
            }
            return true;
        });
        new ArrayList<>(chatNotifications.keySet()).stream()
                .filter(messages -> !currentChats.contains(messages))
                .forEach(this::dismissChatNotification);
        unreadTradeChat.set(hasUnreadTradeChat);
    }

    private void dismissChatNotification(ObservableList<ChatMessage> messages) {
        Notification notification = chatNotifications.remove(messages);
        if (notification != null) notification.hide();
    }

    private void observeChat(ObservableList<ChatMessage> messages, Set<ObservableList<ChatMessage>> currentChats) {
        currentChats.add(messages);
        if (observedChats.add(messages)) {
            synchronized (messages) {
                messages.addListener(chatMessagesListener);
            }
        }
    }

    private void onChatMessage(protobuf.ChatMessage incoming) {
        if (incoming.getType() == protobuf.SupportType.TRADE) {
            tradeManager.getOpenTrade(incoming.getTradeId()).ifPresent(trade -> {
                if (!trade.isArbitrator()) {
                    snapshot(trade.getChatMessages()).stream()
                            .filter(message -> message.getUid().equals(incoming.getUid()))
                            .filter(message -> isUnreadChat(message, trade.isMaker()))
                            .findFirst().ifPresent(message -> notifyChatMessage(message, trade.getChatMessages(), trade.isMaker(),
                                    tradeManager::requestPersistence, () -> navigateToTrade(trade)));
                }
            });
        } else {
            DisputeManager<? extends DisputeList<Dispute>> manager = switch (incoming.getType()) {
                case ARBITRATION -> arbitrationManager;
                case MEDIATION -> mediationManager;
                case REFUND -> refundManager;
                default -> null;
            };
            if (manager != null) {
                manager.findDispute(incoming.getTradeId(), incoming.getTraderId()).ifPresent(dispute ->
                        snapshot(dispute.getChatMessages()).stream()
                                .filter(message -> message.getUid().equals(incoming.getUid()))
                                .filter(message -> isUnreadChat(message, manager.isAgent(dispute)))
                                .findFirst().ifPresent(message -> notifyChatMessage(message, dispute.getChatMessages(), manager.isAgent(dispute),
                                        manager::requestPersistence, () -> navigateToDispute(dispute, manager))));
            }
        }
        refreshChatState();
    }

    private void notifyChatMessage(ChatMessage message, ObservableList<ChatMessage> messages, boolean senderFlag,
                                   Runnable persist, Runnable navigate) {
        if (isChatFocused(messages)) {
            message.setWasDisplayed(true);
            persist.run();
            return;
        }
        String key = message.getSupportType() + ":" + message.getTradeId() + ":" + message.getTraderId() + ":" + message.getUid();
        if (!notifiedChatMessages.add(key)) return;
        long unread = snapshot(messages).stream().filter(chatMessage -> isUnreadChat(chatMessage, senderFlag)).count();
        String text = unread == 1 ? Res.get("notification.chat.message") : Res.get("notification.chat.messages", unread);
        Notification existing = chatNotifications.get(messages);
        if (existing != null && !existing.isClosing()) {
            existing.message(text);
            return;
        }

        boolean support = message.getSupportType() != SupportType.TRADE;
        Notification notification = new Notification();
        notification
                .autoClose()
                .headLine(Res.get(support ? "notification.chat.support" : "notification.chat.trade", Utilities.getShortId(message.getTradeId())))
                .message(text)
                .actionButtonText(Res.get(support ? "notification.chat.goToTicket" : "notification.chat.openChat"))
                .onAction(navigate)
                .onlyShowIf(() -> chatNotifications.get(messages) == notification && observedChats.contains(messages) &&
                        !isChatFocused(messages) && snapshot(messages).stream().anyMatch(chatMessage -> isUnreadChat(chatMessage, senderFlag)));
        chatNotifications.put(messages, notification);
        notification.getIsHiddenProperty().addListener((observable, oldValue, hidden) -> {
            if (hidden) chatNotifications.remove(messages, notification);
        });
        notification.show();
        if (!notification.isHasBeenDisplayed()) chatNotifications.remove(messages, notification);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void addTradeSubscriptions(Trade trade, boolean notifyInitialState) {
        String tradeId = trade.getId();
        if (disputeStateSubscriptionsMap.containsKey(tradeId)) return;
        disputeStateSubscriptionsMap.put(tradeId, EasyBind.subscribe(trade.disputeStateProperty(),
                state -> ThreadUtils.submitToPool(() -> onDisputeStateChanged(trade, state))));
        if (trade.isArbitrator()) {
            tradePhaseSubscriptionsMap.put(tradeId, EasyBind.subscribe(trade.statePhaseProperty(),
                    phase -> onArbitratorTradePhaseChanged(trade, phase)));
            return;
        }
        ChangeListener<Trade.Phase> phaseListener = (observable, oldValue, newValue) -> onTradeStateChanged(trade, oldValue);
        trade.statePhaseProperty().addListener(phaseListener);
        tradePhaseSubscriptionsMap.put(tradeId, () -> trade.statePhaseProperty().removeListener(phaseListener));
        ChangeListener<Trade.PayoutState> payoutListener = (observable, oldValue, newValue) -> onTradeStateChanged(trade, trade.getPhase());
        trade.payoutStateProperty().addListener(payoutListener);
        tradePayoutSubscriptionsMap.put(tradeId, () -> trade.payoutStateProperty().removeListener(payoutListener));
        onTradeStateChanged(trade, notifyInitialState ? Trade.Phase.INIT : null);
    }

    private void removeTradeSubscriptions(Trade trade) {
        String tradeId = trade.getId();
        Subscription disputeSubscription = disputeStateSubscriptionsMap.remove(tradeId);
        if (disputeSubscription != null) disputeSubscription.unsubscribe();
        Subscription phaseSubscription = tradePhaseSubscriptionsMap.remove(tradeId);
        if (phaseSubscription != null) phaseSubscription.unsubscribe();
        Subscription payoutSubscription = tradePayoutSubscriptionsMap.remove(tradeId);
        if (payoutSubscription != null) payoutSubscription.unsubscribe();
        unseenTradeUpdates.remove(tradeId);
        dismissTradeNotification(tradeId);
    }

    private boolean isTradeVisible(Trade trade) {
        return mainWindow != null && mainWindow.isFocused() && trade.getId().equals(viewedTradeId) && navigation.getCurrentPath() != null &&
                navigation.getCurrentPath().contains(PendingTradesView.class);
    }

    private String tradeNotificationKey(Trade trade, String event) {
        return NOTIFICATION_KEY_PREFIX + event + trade.getId();
    }

    private void markTradeUpdatesSeen(Trade trade) {
        for (Trade.Phase phase : List.of(Trade.Phase.DEPOSITS_PUBLISHED, Trade.Phase.DEPOSITS_UNLOCKED,
                Trade.Phase.DEPOSITS_FINALIZED, Trade.Phase.PAYMENT_SENT)) {
            if (trade.getPhase().ordinal() >= phase.ordinal()) {
                String key = tradeNotificationKey(trade, phase.name());
                if (preferences.showAgain(key)) preferences.dontShowAgain(key, true);
            }
        }
        if (trade.isPayoutPublished()) {
            String key = tradeNotificationKey(trade, Trade.PayoutState.PAYOUT_PUBLISHED.name());
            if (preferences.showAgain(key)) preferences.dontShowAgain(key, true);
        }
        unseenTradeUpdates.remove(trade.getId());
        dismissTradeNotification(trade.getId());
    }

    private void dismissTradeNotification(String tradeId) {
        Notification notification = tradeNotifications.remove(tradeId);
        if (notification != null) notification.hide();
    }

    // previousPhase is the phase before a live transition, or null to refresh the dot without a popup
    private void onTradeStateChanged(Trade trade, @Nullable Trade.Phase previousPhase) {
        Trade.Phase phase = trade.getPhase();
        boolean payoutPublished = trade.isPayoutPublished();
        UserThread.execute(() -> {
            refreshTradeNotification(trade, previousPhase);
            // restored activity keeps its dot without replaying its popup
            // phase changes cannot create a new milestone after payout publication
            String key = unseenTradeUpdates.get(trade.getId());
            if (previousPhase == null && key != null && trade.isPayoutPublished() == payoutPublished &&
                    (payoutPublished || trade.getPhase() == phase))
                notifiedTradeUpdates.add(key);
        });
    }

    private void onArbitratorTradePhaseChanged(Trade trade, Trade.Phase phase) {
        if (!trade.isPayoutPublished() || trade.isCompleted()) return;
        String key = tradeNotificationKey(trade, phase.name());
        UserThread.execute(() -> {
            if (!preferences.showAgain(key)) return;
            boolean navigateToTrades = navigation.getCurrentPath() != null &&
                    !navigation.getCurrentPath().contains(PendingTradesView.class);
            if (!navigateToTrades && (viewedTradeId == null || viewedTradeId.equals(trade.getId()))) return;

            // phase-triggered arbitrator notices survive automatic removal from open trades
            Notification notification = new Notification().tradeHeadLine(trade.getShortId())
                    .message(Res.get("notification.trade.completed"))
                    .onAction(() -> {
                        preferences.dontShowAgain(key, true);
                        navigation.navigateToWithData(trade, MainView.class, PortfolioView.class, PendingTradesView.class);
                    })
                    .onClose(() -> preferences.dontShowAgain(key, true));
            if (navigateToTrades) notification.actionButtonTextWithGoTo("portfolio.tab.pendingTrades");
            else notification.actionButtonText(Res.get("notification.trade.selectTrade"));
            notification.show();
        });
    }

    private void refreshTradeNotification(Trade trade, @Nullable Trade.Phase previousPhase) {
        if (!snapshot(tradeManager.getObservableList()).contains(trade)) return;
        String tradeId = trade.getId();
        if (trade.isArbitrator() || trade.isCompleted() || !trade.isDepositsPublished()) {
            unseenTradeUpdates.remove(tradeId);
            dismissTradeNotification(tradeId);
            return;
        }
        if (isTradeVisible(trade)) {
            markTradeUpdatesSeen(trade);
            return;
        }

        Trade.Phase phase = trade.getPhase();
        // payment can precede finalization, and restored trades may not have confirmation counts yet
        Long depositConfirmations = trade.isBuyer() && phase.ordinal() > Trade.Phase.DEPOSITS_FINALIZED.ordinal() ?
                trade.getNumDepositConfirmations() : null;
        boolean depositsFinalized = phase == Trade.Phase.DEPOSITS_FINALIZED ||
                (depositConfirmations != null && depositConfirmations >= Trade.NUM_BLOCKS_DEPOSITS_FINALIZED);
        String event = Trade.Phase.DEPOSITS_PUBLISHED.name();
        String message = trade.isMaker() ? Res.get("notification.trade.accepted",
                trade.isBuyer() ? Res.get("shared.seller") : Res.get("shared.buyer")) : null;
        if (trade.isPayoutPublished()) {
            event = Trade.PayoutState.PAYOUT_PUBLISHED.name();
            message = Res.get("notification.trade.completed");
        } else if (trade.isBuyer() && depositsFinalized) {
            event = Trade.Phase.DEPOSITS_FINALIZED.name();
            message = Res.get("notification.trade.finalized", Trade.NUM_BLOCKS_DEPOSITS_FINALIZED);
        } else if (trade.isBuyer() && phase.ordinal() >= Trade.Phase.DEPOSITS_UNLOCKED.ordinal()) {
            event = Trade.Phase.DEPOSITS_UNLOCKED.name();
            message = Res.get("notification.trade.unlocked");
        } else if (trade.isSeller() && phase.ordinal() >= Trade.Phase.PAYMENT_SENT.ordinal()) {
            event = Trade.Phase.PAYMENT_SENT.name();
            message = Res.get("notification.trade.paymentSent");
        }

        String key = tradeNotificationKey(trade, event);
        if (!key.equals(unseenTradeUpdates.get(tradeId))) dismissTradeNotification(tradeId);
        // older versions recorded completion against the payment-received phase
        boolean legacyCompletionSeen = trade.isPayoutPublished() &&
                !preferences.showAgain(tradeNotificationKey(trade, Trade.Phase.PAYMENT_RECEIVED.name()));
        if (!preferences.showAgain(key) || legacyCompletionSeen) {
            unseenTradeUpdates.remove(tradeId);
            dismissTradeNotification(tradeId);
            return;
        }
        unseenTradeUpdates.put(tradeId, key);
        if (message == null || previousPhase == null) return;
        if (!trade.isPayoutPublished() && !event.equals(phase.name())) {
            // a milestone crossed live still notifies when the later phases carry no notice of their own
            boolean derived = event.equals(Trade.Phase.DEPOSITS_FINALIZED.name()); // inferred from confirmations
            if (derived || Trade.Phase.valueOf(event).ordinal() <= previousPhase.ordinal()) return;
        }
        if (!notifiedTradeUpdates.add(key)) return;

        Notification notification = new Notification();
        notification.useAnimation(false)
                .tradeHeadLine(trade.getShortId())
                .message(message)
                .onAction(() -> {
                    navigation.navigateToWithData(trade, MainView.class, PortfolioView.class, PendingTradesView.class);
                    markTradeUpdatesSeen(trade);
                })
                .onlyShowIf(() -> tradeNotifications.get(tradeId) == notification &&
                        key.equals(unseenTradeUpdates.get(tradeId)));
        if (navigation.getCurrentPath() != null && !navigation.getCurrentPath().contains(PendingTradesView.class))
            notification.actionButtonTextWithGoTo("portfolio.tab.pendingTrades");
        else notification.actionButtonText(Res.get("notification.trade.selectTrade"));
        tradeNotifications.put(tradeId, notification);
        notification.getIsHiddenProperty().addListener((observable, oldValue, hidden) -> {
            if (hidden) tradeNotifications.remove(tradeId, notification);
        });
        notification.show();
        if (!notification.isHasBeenDisplayed()) tradeNotifications.remove(tradeId, notification);
    }

    private void onAmbiguousPaymentRejected(String offerId) {
        UserThread.execute(() -> {
            if (ambiguousPaymentNotifications.containsKey(offerId)) return;
            long now = System.nanoTime();
            ambiguousPaymentNotificationTimesNanos.entrySet().removeIf(entry ->
                    now - entry.getValue() >= AMBIGUOUS_PAYMENT_NOTIFICATION_THROTTLE_NANOS);
            if (ambiguousPaymentNotificationTimesNanos.containsKey(offerId)) return;
            ambiguousPaymentNotificationTimesNanos.put(offerId, now);

            Notification notification = new Notification()
                    .headLine(Res.get("notification.offer.takeRejected.headline"))
                    .notification(Res.get("notification.offer.takeRejected.msg", Utilities.getShortId(offerId)));
            ambiguousPaymentNotifications.put(offerId, notification);
            notification.getIsHiddenProperty().addListener((observable, oldValue, hidden) -> {
                if (hidden) ambiguousPaymentNotifications.remove(offerId, notification);
            });
            notification.show();
            if (!notification.isHasBeenDisplayed()) ambiguousPaymentNotifications.remove(offerId, notification);
        });
    }

    private void onDisputeStateChanged(Trade trade, Trade.DisputeState disputeState) {
        String message = null;
        if (arbitrationManager.findDispute(trade.getId()).isPresent()) {
            Dispute dispute = arbitrationManager.findDispute(trade.getId()).get();
            String disputeOrTicket = dispute.isSupportTicket() ?
                    Res.get("shared.supportTicket") :
                    Res.get("shared.dispute");
            switch (disputeState) {
                case NO_DISPUTE:
                    break;
                case DISPUTE_OPENED:
                    // notify if arbitrator or dispute opener (arbitrator's disputes are in context of each trader, so isOpener() doesn't apply)
                    if (trade.isArbitrator() || !dispute.isOpener()) message = Res.get("notification.trade.peerOpenedDispute", disputeOrTicket);
                    break;
                case DISPUTE_CLOSED:
                    // skip notifying arbitrator
                    if (!trade.isArbitrator()) message = Res.get("notification.trade.disputeClosed", disputeOrTicket);
                    break;
                default:
                    break;
            }
            if (message != null) {
                goToSupport(trade, message, trade.isArbitrator() ? ArbitratorView.class : ArbitrationClientView.class);
            }
        } else if (refundManager.findDispute(trade.getId()).isPresent()) {
            Dispute dispute = refundManager.findDispute(trade.getId()).get();
            String disputeOrTicket = dispute.isSupportTicket() ?
                    Res.get("shared.supportTicket") :
                    Res.get("shared.dispute");
            switch (disputeState) {
                case NO_DISPUTE:
                    break;
                case REFUND_REQUESTED:
                    break;
                case REFUND_REQUEST_STARTED_BY_PEER:
                    message = Res.get("notification.trade.peerOpenedDispute", disputeOrTicket);
                    break;
                case REFUND_REQUEST_CLOSED:
                    message = Res.get("notification.trade.disputeClosed", disputeOrTicket);
                    break;
                default:
//                    if (DevEnv.isDevMode()) {
//                        log.error("refundManager must not contain mediation or arbitration disputes. disputeState={}", disputeState);
//                        throw new RuntimeException("arbitrationDisputeManager must not contain mediation disputes");
//                    }
                    break;
            }
            if (message != null) {
                goToSupport(trade, message, RefundClientView.class);
            }
        } else if (mediationManager.findDispute(trade.getId()).isPresent()) {
            Dispute dispute = mediationManager.findDispute(trade.getId()).get();
            String disputeOrTicket = dispute.isSupportTicket() ?
                    Res.get("shared.supportTicket") :
                    Res.get("shared.mediationCase");
            switch (disputeState) {
                // TODO
                case MEDIATION_REQUESTED:
                    break;
                case MEDIATION_STARTED_BY_PEER:
                    message = Res.get("notification.trade.peerOpenedDispute", disputeOrTicket);
                    break;
                case MEDIATION_CLOSED:
                    message = Res.get("notification.trade.disputeClosed", disputeOrTicket);
                    break;
                default:
//                    if (DevEnv.isDevMode()) {
//                        log.error("mediationDisputeManager must not contain arbitration or refund disputes. disputeState={}", disputeState);
//                        throw new RuntimeException("mediationDisputeManager must not contain arbitration disputes");
//                    }
                    break;
            }
            if (message != null) {
                goToSupport(trade, message, MediationClientView.class);
            }
        }
    }

    private void goToSupport(Trade trade, String message, Class<? extends DisputeView> viewClass) {
        UserThread.execute(() -> {
            Notification notification = new Notification()
                    .disputeHeadLine(trade.getShortId()).message(message);
            if (navigation.getCurrentPath() != null && !navigation.getCurrentPath().contains(viewClass)) {
                notification.actionButtonTextWithGoTo("mainView.menu.support")
                        .onAction(() -> navigation.navigateTo(MainView.class, SupportView.class, viewClass))
                        .show();
            } else {
                notification.show();
            }
        });
    }
}
