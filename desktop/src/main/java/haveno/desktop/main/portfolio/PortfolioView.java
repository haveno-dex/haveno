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
import haveno.core.trade.failed.FailedTradesManager;
import haveno.desktop.Navigation;
import haveno.desktop.common.view.ActivatableView;
import haveno.desktop.common.view.CachingViewLoader;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.common.view.View;
import haveno.desktop.main.MainView;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.portfolio.cloneoffer.CloneOfferView;
import haveno.desktop.main.portfolio.closedtrades.ClosedTradesView;
import haveno.desktop.main.portfolio.duplicateoffer.DuplicateOfferView;
import haveno.desktop.main.portfolio.editoffer.EditOfferView;
import haveno.desktop.main.portfolio.failedtrades.FailedTradesView;
import haveno.desktop.main.portfolio.openoffer.OpenOffersView;
import haveno.desktop.main.portfolio.pendingtrades.PendingTradesView;
import java.util.List;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javax.annotation.Nullable;

@FxmlView
public class PortfolioView extends ActivatableView<TabPane, Void> {

    @FXML
    Tab openOffersTab, pendingTradesTab, closedTradesTab;
    private Tab editOpenOfferTab, duplicateOfferTab, cloneOpenOfferTab;
    private final Tab failedTradesTab = new Tab(Res.get("portfolio.tab.failed").toUpperCase());
    private Tab currentTab;
    private Navigation.Listener navigationListener;
    private ChangeListener<Tab> tabChangeListener;
    private ListChangeListener<Tab> tabListChangeListener;

    private final CachingViewLoader viewLoader;
    private final Navigation navigation;
    private final FailedTradesManager failedTradesManager;
    private EditOfferView editOfferView;
    private DuplicateOfferView duplicateOfferView;
    private CloneOfferView cloneOfferView;
    private boolean editOpenOfferViewOpen, cloneOpenOfferViewOpen;
    private OpenOffer openOffer;
    private OpenOffersView openOffersView;

    @Inject
    public PortfolioView(CachingViewLoader viewLoader, Navigation navigation, FailedTradesManager failedTradesManager) {
        this.viewLoader = viewLoader;
        this.navigation = navigation;
        this.failedTradesManager = failedTradesManager;
    }

    @Override
    public void initialize() {
        root.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        failedTradesTab.setClosable(false);

        openOffersTab.setText(Res.get("portfolio.tab.openOffers"));
        pendingTradesTab.setText(Res.get("portfolio.tab.pendingTrades"));
        closedTradesTab.setText(Res.get("portfolio.tab.history"));

        navigationListener = (viewPath, data) -> {
            if (viewPath.size() == 3 && viewPath.indexOf(PortfolioView.class) == 1)
                loadView(viewPath.tip(), data);
        };

        tabChangeListener = (ov, oldValue, newValue) -> {
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

            if (oldValue != null && oldValue == editOpenOfferTab)
                editOfferView.onTabSelected(false);
            if (oldValue != null && oldValue == duplicateOfferTab)
                duplicateOfferView.onTabSelected(false);
            if (oldValue != null && oldValue == cloneOpenOfferTab)
                cloneOfferView.onTabSelected(false);

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

    private void onEditOpenOfferRemoved() {
        editOpenOfferViewOpen = false;
        if (editOfferView != null) {
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
        failedTradesManager.getObservableList().addListener((ListChangeListener<Trade>) c -> {
            UserThread.execute(() -> {
                if (failedTradesManager.getObservableList().size() > 0 && root.getTabs().size() == 3)
                    root.getTabs().add(failedTradesTab);
            });
        });
        if (failedTradesManager.getObservableList().size() > 0 && root.getTabs().size() == 3)
            root.getTabs().add(failedTradesTab);

        root.getSelectionModel().selectedItemProperty().addListener(tabChangeListener);
        root.getTabs().addListener(tabListChangeListener);
        navigation.addListener(navigationListener);

        if (root.getSelectionModel().getSelectedItem() == openOffersTab)
            navigation.navigateTo(MainView.class, PortfolioView.class, OpenOffersView.class);
        else if (root.getSelectionModel().getSelectedItem() == pendingTradesTab)
            navigation.navigateTo(MainView.class, PortfolioView.class, PendingTradesView.class);
        else if (root.getSelectionModel().getSelectedItem() == closedTradesTab)
            navigation.navigateTo(MainView.class, PortfolioView.class, ClosedTradesView.class);
        else if (root.getSelectionModel().getSelectedItem() == failedTradesTab)
            navigation.navigateTo(MainView.class, PortfolioView.class, FailedTradesView.class);
        else if (root.getSelectionModel().getSelectedItem() == editOpenOfferTab) {
            navigation.navigateTo(MainView.class, PortfolioView.class, EditOfferView.class);
            if (editOfferView != null) editOfferView.onTabSelected(true);
        } else if (root.getSelectionModel().getSelectedItem() == duplicateOfferTab) {
            navigation.navigateTo(MainView.class, PortfolioView.class, DuplicateOfferView.class);
            if (duplicateOfferView != null) duplicateOfferView.onTabSelected(true);
        } else if (root.getSelectionModel().getSelectedItem() == cloneOpenOfferTab) {
            navigation.navigateTo(MainView.class, PortfolioView.class, CloneOfferView.class);
            if (cloneOfferView != null) cloneOfferView.onTabSelected(true);
        }
    }

    @Override
    protected void deactivate() {
        root.getSelectionModel().selectedItemProperty().removeListener(tabChangeListener);
        root.getTabs().removeListener(tabListChangeListener);
        navigation.removeListener(navigationListener);
        currentTab = null;
    }

    private void loadView(Class<? extends View> viewClass, @Nullable Object data) {

        // nullify current tab to trigger activate/deactivate
        if (currentTab != null) currentTab.setContent(null);

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
                    editOpenOfferTab = new Tab(Res.get("portfolio.tab.editOpenOffer").toUpperCase());
                    editOfferView.setCloseHandler(() -> {
                        UserThread.execute(() -> root.getTabs().remove(editOpenOfferTab));
                    });
                    root.getTabs().add(editOpenOfferTab);
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
                duplicateOfferTab = new Tab(Res.get("portfolio.tab.duplicateOffer").toUpperCase());
                duplicateOfferView.setCloseHandler(() -> {
                    UserThread.execute(() -> root.getTabs().remove(duplicateOfferTab));
                });
                root.getTabs().add(duplicateOfferTab);
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
                    cloneOpenOfferTab = new Tab(Res.get("portfolio.tab.cloneOpenOffer").toUpperCase());
                    cloneOfferView.setCloseHandler(() -> {
                        root.getTabs().remove(cloneOpenOfferTab);
                    });
                    root.getTabs().add(cloneOpenOfferTab);
                }
                if (currentTab != cloneOpenOfferTab)
                    cloneOfferView.onTabSelected(true);

                currentTab = cloneOpenOfferTab;
            } else {
                view = viewLoader.load(OpenOffersView.class);
                selectOpenOffersView((OpenOffersView) view);
            }
        }

        currentTab.setContent(view.getRoot());
        root.getSelectionModel().select(currentTab);
    }

    private void selectOpenOffersView(OpenOffersView view) {
        openOffersView = view;
        currentTab = openOffersTab;

        EditOpenOfferHandler editOpenOfferHandler = openOffer -> {
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

