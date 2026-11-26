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

package haveno.desktop.main.offer;

import haveno.common.UserThread;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.desktop.Navigation;
import haveno.desktop.common.view.ActivatableView;
import haveno.desktop.common.view.View;
import haveno.desktop.common.view.ViewLoader;
import haveno.desktop.main.MainView;
import haveno.desktop.main.offer.createoffer.CreateOfferView;
import haveno.desktop.main.offer.offerbook.OfferBookView;
import haveno.desktop.main.offer.takeoffer.TakeOfferView;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.util.GUIUtil;
import haveno.network.p2p.P2PService;
import javafx.beans.value.ObservableNumberValue;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import javax.annotation.Nullable;
import java.util.Optional;

public abstract class OfferView extends ActivatableView<VBox, Void> {

    @FXML
    private HBox header;
    @FXML
    private Label offerLabel;
    @FXML
    private StackPane headerControls, content;
    @FXML
    private Region navigationSpacer;

    private OfferBookView offerBookView;
    private View offerView;

    private final ViewLoader viewLoader;
    private final Navigation navigation;
    private final Preferences preferences;
    private final User user;
    private final P2PService p2PService;
    private final OfferDirection direction;

    private Offer offer;
    private TradeCurrency tradeCurrency;
    private Navigation.Listener navigationListener;
    private OfferView.OfferActionHandler offerActionHandler;

    protected OfferView(ViewLoader viewLoader,
                        Navigation navigation,
                        Preferences preferences,
                        User user,
                        P2PService p2PService,
                        OfferDirection direction) {
        this.viewLoader = viewLoader;
        this.navigation = navigation;
        this.preferences = preferences;
        this.user = user;
        this.p2PService = p2PService;
        this.direction = direction;
    }

    @Override
    protected void initialize() {
        offerLabel.setText(getOfferLabel());

        // clip a view which exceeds the available space
        Rectangle contentClip = new Rectangle();
        contentClip.widthProperty().bind(content.widthProperty());
        contentClip.heightProperty().bind(content.heightProperty());
        content.setClip(contentClip);

        navigationListener = (viewPath, data) -> {
            UserThread.execute(() -> {
                if (viewPath.indexOf(this.getClass()) != 1 || viewPath.size() < 3 || viewPath.get(2) != OfferBookView.class) return;
                if (viewPath.size() == 3) {
                    loadView(null);
                    if (data instanceof Offer offer) offerBookView.showOffer(offer);
                } else if (viewPath.size() == 4) {
                    loadView(viewPath.tip());
                }
            });
        };

        offerActionHandler = new OfferActionHandler() {
            @Override
            public void onCreateOffer(TradeCurrency tradeCurrency, PaymentMethod paymentMethod) {
                if (canCreateOrTakeOffer(tradeCurrency)) {
                    showCreateOffer(tradeCurrency, paymentMethod);
                }
            }

            @Override
            public void onTakeOffer(Offer offer) {
                Optional<TradeCurrency> optionalTradeCurrency = CurrencyUtil.getTradeCurrency(offer.getCounterCurrencyCode());
                if (optionalTradeCurrency.isPresent() && canCreateOrTakeOffer(optionalTradeCurrency.get())) {
                    showTakeOffer(offer);
                }
            }
        };
    }

    @Override
    protected void activate() {
        Optional<TradeCurrency> tradeCurrencyOptional = (this.direction == OfferDirection.SELL) ?
                CurrencyUtil.getTradeCurrency(preferences.getSellScreenCurrencyCode()) :
                CurrencyUtil.getTradeCurrency(preferences.getBuyScreenCurrencyCode());
        tradeCurrency = tradeCurrencyOptional.orElseGet(GlobalSettings::getDefaultTradeCurrency);

        navigation.addListener(navigationListener);
        if (offerBookView == null) {
            navigation.navigateTo(MainView.class, this.getClass(), OfferBookView.class);
        }
    }

    @Override
    protected void deactivate() {
        navigation.removeListener(navigationListener);
    }

    protected abstract String getOfferLabel();

    public void reserveNavigationWidth(ObservableNumberValue width) {
        navigationSpacer.prefWidthProperty().bind(width);
    }

    private void loadView(@Nullable Class<? extends View> childViewClass) {
        if (offerBookView == null) {
            offerBookView = (OfferBookView) viewLoader.load(OfferBookView.class);
            offerBookView.setOfferActionHandler(offerActionHandler);
            offerBookView.setDirection(direction);
            offerBookView.onTabSelected(true);
            headerControls.getChildren().setAll(offerBookView.getHeaderControls());
            showView(offerBookView);
        } else if (childViewClass == null) {
            if (offerView != null) { // TODO: do not close existing offer view if navigating from same market view currency?
                ((ClosableView) offerView).close();
                offerView = null;
            }
            showView(offerBookView);
        } else if (childViewClass == TakeOfferView.class) {
            loadTakeViewClass(childViewClass);
        } else {
            loadCreateViewClass(childViewClass);
        }
    }

    // the heading and its selectors apply to the offer book only, and keep their space otherwise
    private void showView(View view) {
        header.setVisible(view == offerBookView);
        if (!content.getChildren().contains(view.getRoot())) content.getChildren().setAll(view.getRoot());
    }

    private void loadCreateViewClass(Class<? extends View> childViewClass) {
        if (tradeCurrency == null) {
            return;
        }

        View view;
        // CreateOffer and TakeOffer must not be cached by ViewLoader as we cannot use a view multiple times
        // in different graphs
        view = viewLoader.load(childViewClass);

        ((CreateOfferView) view).initWithData(direction, tradeCurrency, offerActionHandler);

        ((SelectableView) view).onTabSelected(true);

        ((ClosableView) view).setCloseHandler(() -> {
            offerBookView.enableCreateOfferButton();
            ((SelectableView) view).onTabSelected(false);
            navigation.navigateTo(MainView.class, this.getClass(), OfferBookView.class);
        });

        offerView = view;
        showView(view);
    }

    private void loadTakeViewClass(Class<? extends View> childViewClass) {

        if (offer == null) {
            return;
        }

        if (offer.getPrice() == null) {
            navigation.navigateTo(MainView.class, this.getClass(), OfferBookView.class);
            new Popup().warning(Res.get("takeOffer.noPriceFeedAvailable")).show();
            return;
        }

        View view = viewLoader.load(childViewClass);
        // CreateOffer and TakeOffer must not be cached by ViewLoader as we cannot use a view multiple times
        // in different graphs
        ((InitializableViewWithTakeOfferData) view).initWithData(offer);
        ((SelectableView) view).onTabSelected(true);

        ((ClosableView) view).setCloseHandler(() -> {
            ((SelectableView) view).onTabSelected(false);
            navigation.navigateTo(MainView.class, this.getClass(), OfferBookView.class);
        });

        offerView = view;
        showView(view);
    }

    protected boolean canCreateOrTakeOffer(TradeCurrency tradeCurrency) {
        return GUIUtil.isBootstrappedOrShowPopup(p2PService) &&
                GUIUtil.canCreateOrTakeOfferOrShowPopup(user, navigation);
    }

    private void showTakeOffer(Offer offer) {
        this.offer = offer;

        navigation.navigateTo(MainView.class, this.getClass(), OfferBookView.class, TakeOfferView.class);
    }

    private void showCreateOffer(TradeCurrency tradeCurrency, PaymentMethod paymentMethod) {
        this.tradeCurrency = tradeCurrency;

        navigation.navigateToWithData(paymentMethod, MainView.class, this.getClass(), OfferBookView.class, CreateOfferView.class);
    }

    public interface OfferActionHandler {
        void onCreateOffer(TradeCurrency tradeCurrency, PaymentMethod paymentMethod);

        void onTakeOffer(Offer offer);
    }

    public interface CloseHandler {
        void close();
    }
}
