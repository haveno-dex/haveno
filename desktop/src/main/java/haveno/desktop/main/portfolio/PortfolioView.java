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

package haveno.desktop.main.portfolio;

import com.google.inject.Inject;
import haveno.common.UserThread;
import haveno.core.locale.Res;
import haveno.core.offer.OfferPayload;
import haveno.core.offer.OpenOffer;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.trade.failed.FailedTradesManager;
import haveno.desktop.Navigation;
import haveno.desktop.util.Accessibility;
import haveno.desktop.common.view.ActivatableView;
import haveno.desktop.common.view.CachingViewLoader;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.common.view.View;
import haveno.desktop.main.MainView;
import haveno.desktop.main.overlays.notifications.NotificationCenter;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.portfolio.cloneoffer.CloneOfferView;
import haveno.desktop.main.portfolio.closedtrades.ClosedTradesView;
import haveno.desktop.main.portfolio.duplicateoffer.DuplicateOfferView;
import haveno.desktop.main.portfolio.editoffer.EditOfferView;
import haveno.desktop.main.portfolio.failedtrades.FailedTradesView;
import haveno.desktop.main.portfolio.openoffer.OpenOffersView;
import haveno.desktop.main.portfolio.pendingtrades.PendingTradesView;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javafx.beans.binding.Bindings;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableNumberValue;
import javafx.collections.ListChangeListener;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javax.annotation.Nullable;

@FxmlView
public class PortfolioView extends ActivatableView<VBox, Void> {

    @FXML
    TabPane tabPane;
    @FXML
    StackPane headerControls, content;
    @FXML
    Region navigationSpacer;
    @FXML
    Separator headerSeparator;
    @FXML
    Tab openOffersTab, pendingTradesTab, closedTradesTab;
    private Tab editOpenOfferTab, duplicateOfferTab, cloneOpenOfferTab;
    private final Tab failedTradesTab = new Tab(Res.get("portfolio.tab.failed"));
    private Tab currentTab;
    private Navigation.Listener navigationListener;
    private ChangeListener<Tab> tabChangeListener;
    private ListChangeListener<Tab> tabListChangeListener;

    private final CachingViewLoader viewLoader;
    private final Navigation navigation;
    private final FailedTradesManager failedTradesManager;
    private final TradeManager tradeManager;
    private final LongProperty numOpenTrades = new SimpleLongProperty();
    private final Set<Trade> observedTrades = new HashSet<>();
    private final ListChangeListener<Trade> tradesListChangeListener = change -> UserThread.execute(this::updateOpenTradeCount);
    private final ChangeListener<Trade.State> tradeStateChangeListener = (observable, oldValue, newValue) ->
            UserThread.execute(this::updateOpenTradeCount);
    private boolean active;
    private final NotificationCenter notificationCenter;
    private EditOfferView editOfferView;
    private ReadOnlyBooleanProperty editOfferCanceling;
    private DuplicateOfferView duplicateOfferView;
    private CloneOfferView cloneOfferView;
    private boolean editOpenOfferViewOpen, cloneOpenOfferViewOpen;
    private OpenOffer openOffer;
    private OpenOffersView openOffersView;
    private boolean tabListChangeListenerAdded = false;

    @Inject
    public PortfolioView(CachingViewLoader viewLoader, Navigation navigation, FailedTradesManager failedTradesManager,
                         NotificationCenter notificationCenter, TradeManager tradeManager) {
        this.viewLoader = viewLoader;
        this.navigation = navigation;
        this.failedTradesManager = failedTradesManager;
        this.notificationCenter = notificationCenter;
        this.tradeManager = tradeManager;
    }

    @Override
    public void initialize() {
        Accessibility.fixTabs(tabPane);
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        failedTradesTab.setClosable(false);

        openOffersTab.setText(Res.get("portfolio.tab.openOffers"));
        pendingTradesTab.setText(Res.get("portfolio.tab.pendingTrades"));
        closedTradesTab.setText(Res.get("portfolio.tab.history"));
        setupOpenTradeIndicator();
        // preserve the tab content's clipping when a view exceeds the available space
        Rectangle contentClip = new Rectangle();
        contentClip.widthProperty().bind(content.widthProperty());
        contentClip.heightProperty().bind(content.heightProperty());
        content.setClip(contentClip);
        // retain tab shortcuts from controls hosted outside the tab pane
        EventHandler<KeyEvent> tabNavigationHandler = event -> {
            if (event.isControlDown() && !event.isAltDown() && !event.isMetaDown() &&
                    (event.getCode() == KeyCode.TAB || (!event.isShiftDown() &&
                            (event.getCode() == KeyCode.PAGE_UP || event.getCode() == KeyCode.PAGE_DOWN)))) {
                tabPane.fireEvent(event.copyFor(tabPane, tabPane));
                event.consume();
            }
        };
        content.addEventHandler(KeyEvent.KEY_PRESSED, tabNavigationHandler);
        headerControls.addEventHandler(KeyEvent.KEY_PRESSED, tabNavigationHandler);
        headerControls.setVisible(false);
        headerControls.managedProperty().bind(headerControls.visibleProperty());
        headerSeparator.visibleProperty().bind(headerControls.visibleProperty());
        headerSeparator.managedProperty().bind(headerControls.visibleProperty());

        navigationListener = (viewPath, data) -> {
            if (viewPath.size() == 3 && viewPath.indexOf(PortfolioView.class) == 1)
                loadView(viewPath.tip(), data);
        };

        tabChangeListener = (ov, oldValue, newValue) -> {
            if (oldValue != null && oldValue == editOpenOfferTab && editOfferView != null)
                editOfferView.onTabSelected(false);
            if (oldValue != null && oldValue == duplicateOfferTab)
                duplicateOfferView.onTabSelected(false);
            if (oldValue != null && oldValue == cloneOpenOfferTab)
                cloneOfferView.onTabSelected(false);

            // let the removal listener return directly to open offers without loading the neighboring tab
            if (oldValue != null && oldValue == editOpenOfferTab && !tabPane.getTabs().contains(oldValue)) return;
            // navigation has already loaded the tab before selecting it
            if (newValue == currentTab) return;

            if (newValue == openOffersTab)
                navigation.navigateTo(MainView.class, PortfolioView.class, OpenOffersView.class);
            else if (newValue == pendingTradesTab)
                navigation.navigateTo(MainView.class, PortfolioView.class, PendingTradesView.class);
            else if (newValue == closedTradesTab)
                navigation.navigateTo(MainView.class, PortfolioView.class, ClosedTradesView.class);
            else if (newValue == failedTradesTab)
                navigation.navigateTo(MainView.class, PortfolioView.class, FailedTradesView.class);
            else if (newValue == editOpenOfferTab)
                navigation.navigateTo(MainView.class, PortfolioView.class, EditOfferView.class);
            else if (newValue == duplicateOfferTab) {
                navigation.navigateTo(MainView.class, PortfolioView.class, DuplicateOfferView.class);
            } else if (newValue == cloneOpenOfferTab) {
                navigation.navigateTo(MainView.class, PortfolioView.class, CloneOfferView.class);
            }

        };

        tabListChangeListener = change -> {
            change.next();
            List<? extends Tab> removedTabs = change.getRemoved();
            if (removedTabs.size() == 1 && removedTabs.get(0).equals(editOpenOfferTab))
                onEditOpenOfferRemoved();
            if (removedTabs.size() == 1 && removedTabs.get(0).equals(duplicateOfferTab))
                onDuplicateOfferRemoved();
            if (removedTabs.size() == 1 && removedTabs.get(0).equals(cloneOpenOfferTab))
                onCloneOpenOfferRemoved();
        };
    }

    public void reserveNavigationWidth(ObservableNumberValue width) {
        navigationSpacer.prefWidthProperty().bind(width);
    }

    private void setupOpenTradeIndicator() {
        Label count = new Label();
        count.getStyleClass().add("pending-trades-count");
        count.textProperty().bind(numOpenTrades.asString());
        StackPane countContainer = new StackPane(count);
        countContainer.setPadding(new Insets(0, 0, 0, 8));
        countContainer.setMouseTransparent(true);

        Circle countDot = new Circle(4);
        countDot.getStyleClass().add("tab-unread-dot");
        countDot.setManaged(false);
        countDot.visibleProperty().bind(notificationCenter.unreadPortfolioProperty());
        countDot.centerXProperty().bind(count.layoutXProperty().add(count.widthProperty()));
        countDot.centerYProperty().bind(count.layoutYProperty());
        countContainer.getChildren().add(countDot);

        Circle dot = new Circle(4);
        dot.getStyleClass().add("tab-unread-dot");
        dot.setManaged(false);
        dot.visibleProperty().bind(notificationCenter.unreadPortfolioProperty());

        // use the existing right padding without changing the tab's width
        StackPane indicator = new StackPane(dot) {
            @Override
            protected void layoutChildren() {
                super.layoutChildren();
                Node label = getParent() == null ? null : getParent().lookup(".text");
                if (label != null) {
                    dot.centerXProperty().bind(Bindings.createDoubleBinding(
                            () -> parentToLocal(label.localToParent(label.getLayoutBounds())).getMaxX() + 8,
                            label.layoutBoundsProperty(), label.localToParentTransformProperty(), localToParentTransformProperty()));
                    dot.centerYProperty().bind(Bindings.createDoubleBinding(
                            () -> parentToLocal(label.localToParent(label.getLayoutBounds())).getMinY(),
                            label.layoutBoundsProperty(), label.localToParentTransformProperty(), localToParentTransformProperty()));
                }
            }
        };
        indicator.setMinSize(0, 0);
        indicator.setPrefSize(0, 0);
        indicator.setMaxSize(0, 0);
        indicator.setMouseTransparent(true);
        pendingTradesTab.graphicProperty().bind(Bindings.when(numOpenTrades.greaterThan(0))
                .then(countContainer).otherwise(indicator));
        pendingTradesTab.getStyleClass().add("unread-trade-chat-tab");

        Tooltip tooltip = new Tooltip(Res.get("notification.trade.unreadUpdates"));
        Runnable updateHelp = () -> {
            boolean unread = notificationCenter.unreadPortfolioProperty().get();
            pendingTradesTab.setTooltip(unread ? tooltip : null);
            Node header = tabPane.lookup(".unread-trade-chat-tab");
            String help = numOpenTrades.get() > 0 ? pendingTradesTab.getText() + ": " + numOpenTrades.get() : null;
            if (unread) help = help == null ? tooltip.getText() : help + ". " + tooltip.getText();
            if (header != null) header.setAccessibleHelp(help);
        };
        tabPane.skinProperty().addListener((observable, oldValue, newValue) -> UserThread.execute(updateHelp));
        notificationCenter.unreadPortfolioProperty().addListener((observable, oldValue, newValue) -> updateHelp.run());
        numOpenTrades.addListener((observable, oldValue, newValue) -> updateHelp.run());
        updateHelp.run();
    }

    private void updateOpenTradeCount() {
        if (!active) return;
        synchronized (tradeManager.getObservableList()) {
            observedTrades.removeIf(trade -> {
                if (tradeManager.getObservableList().contains(trade)) return false;
                trade.stateProperty().removeListener(tradeStateChangeListener);
                return true;
            });
            for (Trade trade : tradeManager.getObservableList()) {
                if (observedTrades.add(trade)) trade.stateProperty().addListener(tradeStateChangeListener);
            }
            // match the unfiltered table even while another portfolio tab is selected
            numOpenTrades.set(tradeManager.getObservableList().stream().filter(Trade::isDepositsPublished).count());
        }
    }

    private void onEditOpenOfferRemoved() {
        editOpenOfferViewOpen = false;
        if (editOfferView != null) {
            editOfferCanceling = editOfferView.cancelingProperty();
            editOfferView.onClose();
            editOfferView = null;
        }

        navigation.navigateTo(MainView.class, this.getClass(), OpenOffersView.class);
    }

    private void onDuplicateOfferRemoved() {
        if (duplicateOfferView != null) {
            duplicateOfferView.onClose();
            duplicateOfferView = null;
        }

        navigation.navigateTo(MainView.class, this.getClass(), OpenOffersView.class);
    }

    private void onCloneOpenOfferRemoved() {
        cloneOpenOfferViewOpen = false;
        if (cloneOfferView != null) {
            cloneOfferView.onClose();
            cloneOfferView = null;
        }

        navigation.navigateTo(MainView.class, this.getClass(), OpenOffersView.class);
    }

    @Override
    protected void activate() {
        active = true;
        tradeManager.getObservableList().addListener(tradesListChangeListener);
        updateOpenTradeCount();
        failedTradesManager.getObservableList().addListener((ListChangeListener<Trade>) c -> {
            UserThread.execute(() -> {
                if (failedTradesManager.getObservableList().size() > 0 && tabPane.getTabs().size() == 3)
                    tabPane.getTabs().add(failedTradesTab);
            });
        });
        if (failedTradesManager.getObservableList().size() > 0 && tabPane.getTabs().size() == 3)
            tabPane.getTabs().add(failedTradesTab);

        tabPane.getSelectionModel().selectedItemProperty().addListener(tabChangeListener);
        if (!tabListChangeListenerAdded) {
            tabPane.getTabs().addListener(tabListChangeListener);
            tabListChangeListenerAdded = true; // add listener only once
        }
        navigation.addListener(navigationListener);

        if (tabPane.getSelectionModel().getSelectedItem() == openOffersTab)
            navigation.navigateTo(MainView.class, PortfolioView.class, OpenOffersView.class);
        else if (tabPane.getSelectionModel().getSelectedItem() == pendingTradesTab)
            navigation.navigateTo(MainView.class, PortfolioView.class, PendingTradesView.class);
        else if (tabPane.getSelectionModel().getSelectedItem() == closedTradesTab)
            navigation.navigateTo(MainView.class, PortfolioView.class, ClosedTradesView.class);
        else if (tabPane.getSelectionModel().getSelectedItem() == failedTradesTab)
            navigation.navigateTo(MainView.class, PortfolioView.class, FailedTradesView.class);
        else if (tabPane.getSelectionModel().getSelectedItem() == editOpenOfferTab) {
            navigation.navigateTo(MainView.class, PortfolioView.class, EditOfferView.class);
            if (editOfferView != null) editOfferView.onTabSelected(true);
        } else if (tabPane.getSelectionModel().getSelectedItem() == duplicateOfferTab) {
            navigation.navigateTo(MainView.class, PortfolioView.class, DuplicateOfferView.class);
            if (duplicateOfferView != null) duplicateOfferView.onTabSelected(true);
        } else if (tabPane.getSelectionModel().getSelectedItem() == cloneOpenOfferTab) {
            navigation.navigateTo(MainView.class, PortfolioView.class, CloneOfferView.class);
            if (cloneOfferView != null) cloneOfferView.onTabSelected(true);
        }
    }

    @Override
    protected void deactivate() {
        active = false;
        tradeManager.getObservableList().removeListener(tradesListChangeListener);
        observedTrades.forEach(trade -> trade.stateProperty().removeListener(tradeStateChangeListener));
        observedTrades.clear();
        tabPane.getSelectionModel().selectedItemProperty().removeListener(tabChangeListener);
        navigation.removeListener(navigationListener);
        currentTab = null;
    }

    private void loadView(Class<? extends View> viewClass, @Nullable Object data) {
        if (viewClass == EditOfferView.class && editOfferCanceling != null && editOfferCanceling.get()) {
            navigation.navigateTo(MainView.class, PortfolioView.class, OpenOffersView.class);
            return;
        }

        // keep pending trade details attached when a notification targets the active tab
        if (currentTab != null && (viewClass != PendingTradesView.class || currentTab != pendingTradesTab))
            content.getChildren().clear();

        View view = viewLoader.load(viewClass);

        if (view instanceof OpenOffersView) {
            selectOpenOffersView((OpenOffersView) view);
        } else if (view instanceof PendingTradesView) {
            currentTab = pendingTradesTab;
        } else if (view instanceof ClosedTradesView) {
            currentTab = closedTradesTab;
        } else if (view instanceof FailedTradesView) {
            currentTab = failedTradesTab;
        } else if (view instanceof EditOfferView) {
            if (data instanceof OpenOffer) {
                openOffer = (OpenOffer) data;
            }
            if (openOffer != null) {
                if (editOfferView == null) {
                    editOfferView = (EditOfferView) view;
                    editOfferView.applyOpenOffer(openOffer);
                    editOpenOfferTab = new Tab(Res.get("portfolio.tab.editOpenOffer"));
                    editOfferView.setCloseHandler(() -> {
                        UserThread.execute(() -> tabPane.getTabs().remove(editOpenOfferTab));
                    });
                    tabPane.getTabs().add(editOpenOfferTab);
                }
                if (currentTab != editOpenOfferTab)
                    editOfferView.onTabSelected(true);

                currentTab = editOpenOfferTab;
            } else {
                view = viewLoader.load(OpenOffersView.class);
                selectOpenOffersView((OpenOffersView) view);
            }
        } else if (view instanceof DuplicateOfferView) {
            if (duplicateOfferView == null && data instanceof OfferPayload && data != null) {
                viewLoader.removeFromCache(viewClass);  // remove cached dialog
                view = viewLoader.load(viewClass);      // and load a fresh one
                duplicateOfferView = (DuplicateOfferView) view;
                duplicateOfferView.initWithData((OfferPayload) data);
                duplicateOfferTab = new Tab(Res.get("portfolio.tab.duplicateOffer"));
                duplicateOfferView.setCloseHandler(() -> {
                    UserThread.execute(() -> tabPane.getTabs().remove(duplicateOfferTab));
                });
                tabPane.getTabs().add(duplicateOfferTab);
            }
            if (duplicateOfferView != null) {
                if (currentTab != duplicateOfferTab)
                    duplicateOfferView.onTabSelected(true);
                currentTab = duplicateOfferTab;
            } else {
                view = viewLoader.load(OpenOffersView.class);
                selectOpenOffersView((OpenOffersView) view);
            }
        } else if (view instanceof CloneOfferView) {
            if (data instanceof OpenOffer) {
                openOffer = (OpenOffer) data;
            }
            if (openOffer != null) {
                if (cloneOfferView == null) {
                    cloneOfferView = (CloneOfferView) view;
                    cloneOfferView.applyOpenOffer(openOffer);
                    cloneOpenOfferTab = new Tab(Res.get("portfolio.tab.cloneOpenOffer"));
                    cloneOfferView.setCloseHandler(() -> {
                        tabPane.getTabs().remove(cloneOpenOfferTab);
                    });
                    tabPane.getTabs().add(cloneOpenOfferTab);
                }
                if (currentTab != cloneOpenOfferTab)
                    cloneOfferView.onTabSelected(true);

                currentTab = cloneOpenOfferTab;
            } else {
                view = viewLoader.load(OpenOffersView.class);
                selectOpenOffersView((OpenOffersView) view);
            }
        }

        Region controls = null;
        if (view instanceof OpenOffersView openOffers) controls = openOffers.getHeaderControls();
        else if (view instanceof PendingTradesView pendingTrades) controls = pendingTrades.getHeaderControls();
        else if (view instanceof ClosedTradesView closedTrades) controls = closedTrades.getHeaderControls();
        else if (view instanceof FailedTradesView failedTrades) controls = failedTrades.getHeaderControls();
        if (controls == null) headerControls.getChildren().clear();
        else if (!headerControls.getChildren().contains(controls)) headerControls.getChildren().setAll(controls);
        headerControls.setVisible(controls != null);

        Node viewRoot = view.getRoot();
        // retain the scene attachment when a notification targets the currently visible trade
        if (!content.getChildren().contains(viewRoot)) content.getChildren().setAll(viewRoot);
        tabPane.getSelectionModel().select(currentTab);
        if (view instanceof PendingTradesView pendingTradesView) {
            if (data instanceof PendingTradesView.OpenChatRequest request) pendingTradesView.selectTrade(request.trade(), true);
            else if (data instanceof Trade trade) pendingTradesView.selectTrade(trade);
        }
    }

    private void selectOpenOffersView(OpenOffersView view) {
        openOffersView = view;
        currentTab = openOffersTab;
        // keep the cancellation guard on the cached offer view
        if (editOfferCanceling != null) view.getRoot().disableProperty().bind(editOfferCanceling);

        EditOpenOfferHandler editOpenOfferHandler = openOffer -> {
            if (editOfferCanceling != null && editOfferCanceling.get()) return;
            if (!editOpenOfferViewOpen) {
                editOpenOfferViewOpen = true;
                PortfolioView.this.openOffer = openOffer;
                navigation.navigateTo(MainView.class, PortfolioView.this.getClass(), EditOfferView.class);
            } else {
                new Popup().warning(Res.get("editOffer.openTabWarning")).show();
            }
        };
        openOffersView.setEditOpenOfferHandler(editOpenOfferHandler);

        CloneOpenOfferHandler cloneOpenOfferHandler = openOffer -> {
            if (!cloneOpenOfferViewOpen) {
                cloneOpenOfferViewOpen = true;
                PortfolioView.this.openOffer = openOffer;
                navigation.navigateTo(MainView.class, PortfolioView.this.getClass(), CloneOfferView.class);
            } else {
                new Popup().warning(Res.get("cloneOffer.openTabWarning")).show();
            }
        };
        openOffersView.setCloneOpenOfferHandler(cloneOpenOfferHandler);
    }

    public interface EditOpenOfferHandler {
        void onEditOpenOffer(OpenOffer openOffer);
    }

    public interface CloneOpenOfferHandler {
        void onCloneOpenOffer(OpenOffer openOffer);
    }
}
