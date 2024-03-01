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
import haveno.common.UserThread;
import haveno.core.api.NotificationListener;
import haveno.core.locale.Res;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.arbitration.ArbitrationManager;
import haveno.core.support.dispute.mediation.MediationManager;
import haveno.core.support.dispute.refund.RefundManager;
import haveno.core.trade.BuyerTrade;
import haveno.core.trade.MakerTrade;
import haveno.core.trade.SellerTrade;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.user.DontShowAgainLookup;
import haveno.core.user.Preferences;
import haveno.desktop.Navigation;
import haveno.desktop.main.MainView;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.portfolio.PortfolioView;
import haveno.desktop.main.portfolio.pendingtrades.PendingTradesView;
import haveno.desktop.main.support.SupportView;
import haveno.desktop.main.support.dispute.DisputeView;
import haveno.desktop.main.support.dispute.agent.arbitration.ArbitratorView;
import haveno.desktop.main.support.dispute.client.arbitration.ArbitrationClientView;
import haveno.desktop.main.support.dispute.client.mediation.MediationClientView;
import haveno.desktop.main.support.dispute.client.refund.RefundClientView;
import haveno.proto.grpc.NotificationMessage;
import haveno.proto.grpc.NotificationMessage.NotificationType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javafx.collections.ListChangeListener;
import javax.annotation.Nullable;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;

@Slf4j
@Singleton
public class NotificationCenter {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Static
    ///////////////////////////////////////////////////////////////////////////////////////////

    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    private final static List<Notification> notifications = new ArrayList<>();
    private Consumer<String> selectItemByTradeIdConsumer;

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

    private final Map<String, Subscription> disputeStateSubscriptionsMap = new HashMap<>();
    private final Map<String, Subscription> tradePhaseSubscriptionsMap = new HashMap<>();
    @Nullable
    private String selectedTradeId;

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

        EasyBind.subscribe(preferences.getUseAnimationsProperty(), useAnimations -> NotificationCenter.useAnimations = useAnimations);
    }

    public void onAllServicesAndViewsInitialized() {
        tradeManager.getObservableList().addListener((ListChangeListener<Trade>) change -> {
            change.next();
            if (change.wasRemoved()) {
                change.getRemoved().forEach(trade -> {
                    String tradeId = trade.getId();
                    if (disputeStateSubscriptionsMap.containsKey(tradeId)) {
                        disputeStateSubscriptionsMap.get(tradeId).unsubscribe();
                        disputeStateSubscriptionsMap.remove(tradeId);
                    }

                    if (tradePhaseSubscriptionsMap.containsKey(tradeId)) {
                        tradePhaseSubscriptionsMap.get(tradeId).unsubscribe();
                        tradePhaseSubscriptionsMap.remove(tradeId);
                    }
                });
            }
            if (change.wasAdded()) {
                change.getAddedSubList().forEach(trade -> {
                    String tradeId = trade.getId();
                    if (disputeStateSubscriptionsMap.containsKey(tradeId)) {
                        log.debug("We have already an entry in disputeStateSubscriptionsMap.");
                    } else {
                        Subscription disputeStateSubscription = EasyBind.subscribe(trade.disputeStateProperty(),
                                disputeState -> onDisputeStateChanged(trade, disputeState));
                        disputeStateSubscriptionsMap.put(tradeId, disputeStateSubscription);
                    }

                    if (tradePhaseSubscriptionsMap.containsKey(tradeId)) {
                        log.debug("We have already an entry in tradePhaseSubscriptionsMap.");
                    } else {
                        Subscription tradePhaseSubscription = EasyBind.subscribe(trade.statePhaseProperty(),
                                phase -> onTradePhaseChanged(trade, phase));
                        tradePhaseSubscriptionsMap.put(tradeId, tradePhaseSubscription);
                    }
                });
            }
        });

        tradeManager.getObservableList().forEach(trade -> {
                    String tradeId = trade.getId();
                    Subscription disputeStateSubscription = EasyBind.subscribe(trade.disputeStateProperty(),
                            disputeState -> onDisputeStateChanged(trade, disputeState));
                    disputeStateSubscriptionsMap.put(tradeId, disputeStateSubscription);

                    Subscription tradePhaseSubscription = EasyBind.subscribe(trade.statePhaseProperty(),
                            phase -> onTradePhaseChanged(trade, phase));
                    tradePhaseSubscriptionsMap.put(tradeId, tradePhaseSubscription);
                }
        );

        // show popup for error notifications
        tradeManager.getNotificationService().addListener(new NotificationListener() {
            @Override
            public void onMessage(@NonNull NotificationMessage message) {
                if (message.getType() == NotificationType.ERROR) {
                    new Popup().warning(message.getMessage()).show();
                }
            }
        });
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Setter/Getter
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Nullable
    public String getSelectedTradeId() {
        return selectedTradeId;
    }

    public void setSelectedTradeId(@Nullable String selectedTradeId) {
        this.selectedTradeId = selectedTradeId;
    }

    public void setSelectItemByTradeIdConsumer(Consumer<String> selectItemByTradeIdConsumer) {
        this.selectItemByTradeIdConsumer = selectItemByTradeIdConsumer;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void onTradePhaseChanged(Trade trade, Trade.Phase phase) {
        String message = null;
        if (trade.isPayoutPublished() && !trade.isCompleted()) {
            message = Res.get("notification.trade.completed");
        } else {
            if (trade instanceof MakerTrade &&
                    phase.ordinal() == Trade.Phase.DEPOSITS_PUBLISHED.ordinal()) {
                final String role = trade instanceof BuyerTrade ? Res.get("shared.seller") : Res.get("shared.buyer");
                message = Res.get("notification.trade.accepted", role);
            }

            if (trade instanceof BuyerTrade && phase.ordinal() == Trade.Phase.DEPOSITS_UNLOCKED.ordinal())
                message = Res.get("notification.trade.unlocked");
            else if (trade instanceof SellerTrade && phase.ordinal() == Trade.Phase.PAYMENT_SENT.ordinal())
                message = Res.get("notification.trade.paymentSent");
        }

        if (message != null) {
            String key = "NotificationCenter_" + phase.name() + trade.getId();
            if (DontShowAgainLookup.showAgain(key)) {
                Notification notification = new Notification().tradeHeadLine(trade.getShortId()).message(message);
                if (navigation.getCurrentPath() != null && !navigation.getCurrentPath().contains(PendingTradesView.class)) {
                    notification.actionButtonTextWithGoTo("navigation.portfolio.pending")
                            .onAction(() -> {
                                DontShowAgainLookup.dontShowAgain(key, true);
                                navigation.navigateTo(MainView.class, PortfolioView.class, PendingTradesView.class);
                                if (selectItemByTradeIdConsumer != null)
                                    UserThread.runAfter(() -> selectItemByTradeIdConsumer.accept(trade.getId()), 1);
                            })
                            .onClose(() -> DontShowAgainLookup.dontShowAgain(key, true))
                            .show();
                } else if (selectedTradeId != null && !trade.getId().equals(selectedTradeId)) {
                    notification.actionButtonText(Res.get("notification.trade.selectTrade"))
                            .onAction(() -> {
                                DontShowAgainLookup.dontShowAgain(key, true);
                                if (selectItemByTradeIdConsumer != null)
                                    selectItemByTradeIdConsumer.accept(trade.getId());
                            })
                            .onClose(() -> DontShowAgainLookup.dontShowAgain(key, true))
                            .show();
                }
            }
        }
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
        }else if (refundManager.findDispute(trade.getId()).isPresent()) {
            String disputeOrTicket = refundManager.findDispute(trade.getId()).get().isSupportTicket() ?
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
            String disputeOrTicket = mediationManager.findDispute(trade.getId()).get().isSupportTicket() ?
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
        Notification notification = new Notification().disputeHeadLine(trade.getShortId()).message(message);
        if (navigation.getCurrentPath() != null && !navigation.getCurrentPath().contains(viewClass)) {
            notification.actionButtonTextWithGoTo("navigation.support")
                    .onAction(() -> navigation.navigateTo(MainView.class, SupportView.class, viewClass))
                    .show();
        } else {
            notification.show();
        }
    }
}
