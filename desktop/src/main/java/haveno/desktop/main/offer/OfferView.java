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
import haveno.desktop.main.offer.offerbook.FiatOfferBookView;
import haveno.desktop.main.offer.offerbook.OfferBookView;
import haveno.desktop.main.offer.offerbook.CryptoOfferBookView;
import haveno.desktop.main.offer.offerbook.OtherOfferBookView;
import haveno.desktop.main.offer.takeoffer.TakeOfferView;
import haveno.desktop.util.GUIUtil;
import haveno.network.p2p.P2PService;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.Optional;

public abstract class OfferView extends ActivatableView<TabPane, Void> {

    private OfferBookView<?, ?> fiatOfferBookView, cryptoOfferBookView, otherOfferBookView;

    private Tab labelTab, fiatOfferBookTab, cryptoOfferBookTab, otherOfferBookTab;

    private final ViewLoader viewLoader;
    private final Navigation navigation;
    private final Preferences preferences;
    private final User user;
    private final P2PService p2PService;
    private final OfferDirection direction;

    private Offer offer;
    private TradeCurrency tradeCurrency;
    private Navigation.Listener navigationListener;
    private ChangeListener<Tab> tabChangeListener;
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
        navigationListener = (viewPath, data) -> {
            UserThread.execute(() -> {
                if (viewPath.size() == 3 && viewPath.indexOf(this.getClass()) == 1) {
                    loadView(viewPath.tip(), null, data);
                } else if (viewPath.size() == 4 && viewPath.indexOf(this.getClass()) == 1) {
                    loadView(viewPath.get(2), viewPath.tip(), data);
                }
            });
        };
        tabChangeListener = (observableValue, oldValue, newValue) -> {
            UserThread.execute(() -> {
                if (newValue != null) {
                    if (newValue.equals(fiatOfferBookTab)) {
                        if (fiatOfferBookView != null) {
                            fiatOfferBookView.onTabSelected(true);
                        } else {
                            loadView(FiatOfferBookView.class, null, null);
                        }
                    } else if (newValue.equals(cryptoOfferBookTab)) {
                        if (cryptoOfferBookView != null) {
                            cryptoOfferBookView.onTabSelected(true);
                        } else {
                            loadView(CryptoOfferBookView.class, null, null);
                        }
                    } else if (newValue.equals(otherOfferBookTab)) {
                        if (otherOfferBookView != null) {
                            otherOfferBookView.onTabSelected(true);
                        } else {
                            loadView(OtherOfferBookView.class, null, null);
                        }
                    }
                }
                if (oldValue != null) {
                    if (oldValue.equals(fiatOfferBookTab) && fiatOfferBookView != null) {
                        fiatOfferBookView.onTabSelected(false);
                    } else if (oldValue.equals(cryptoOfferBookTab) && cryptoOfferBookView != null) {
                        cryptoOfferBookView.onTabSelected(false);
                    } else if (oldValue.equals(otherOfferBookTab) && otherOfferBookView != null) {
                        otherOfferBookView.onTabSelected(false);
                    }
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
                Optional<TradeCurrency> optionalTradeCurrency = CurrencyUtil.getTradeCurrency(offer.getCurrencyCode());
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

        root.getSelectionModel().selectedItemProperty().addListener(tabChangeListener);
        navigation.addListener(navigationListener);
        if (fiatOfferBookView == null) {
            navigation.navigateTo(MainView.class, this.getClass(), FiatOfferBookView.class);
        }
    }

    @Override
    protected void deactivate() {
        navigation.removeListener(navigationListener);
        root.getSelectionModel().selectedItemProperty().removeListener(tabChangeListener);
    }

    protected abstract String getOfferLabel();

    private void loadView(Class<? extends View> viewClass,
                          Class<? extends View> childViewClass,
                          @Nullable Object data) {
        TabPane tabPane = root;
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);

        if (OfferBookView.class.isAssignableFrom(viewClass)) {

            if (viewClass == FiatOfferBookView.class && fiatOfferBookTab != null && fiatOfferBookView != null) {
                if (childViewClass == null) {
                    fiatOfferBookTab.setContent(fiatOfferBookView.getRoot());
                } else if (childViewClass == TakeOfferView.class) {
                    loadTakeViewClass(viewClass, childViewClass, fiatOfferBookTab);
                } else {
                    loadCreateViewClass(fiatOfferBookView, viewClass, childViewClass, fiatOfferBookTab, (PaymentMethod) data);
                }
                tabPane.getSelectionModel().select(fiatOfferBookTab);
            } else if (viewClass == CryptoOfferBookView.class && cryptoOfferBookTab != null && cryptoOfferBookView != null) {
                if (childViewClass == null) {
                    cryptoOfferBookTab.setContent(cryptoOfferBookView.getRoot());
                } else if (childViewClass == TakeOfferView.class) {
                    loadTakeViewClass(viewClass, childViewClass, cryptoOfferBookTab);
                } else {
                    // add sanity check in case of app restart
                    if (CurrencyUtil.isTraditionalCurrency(tradeCurrency.getCode())) {
                        Optional<TradeCurrency> tradeCurrencyOptional = (this.direction == OfferDirection.SELL) ?
                                CurrencyUtil.getTradeCurrency(preferences.getSellScreenCryptoCurrencyCode()) :
                                CurrencyUtil.getTradeCurrency(preferences.getBuyScreenCryptoCurrencyCode());
                        tradeCurrency = tradeCurrencyOptional.isEmpty() ? OfferViewUtil.getAnyOfMainCryptoCurrencies() : tradeCurrencyOptional.get();
                    }
                    loadCreateViewClass(cryptoOfferBookView, viewClass, childViewClass, cryptoOfferBookTab, (PaymentMethod) data);
                }
                tabPane.getSelectionModel().select(cryptoOfferBookTab);
            } else if (viewClass == OtherOfferBookView.class && otherOfferBookTab != null && otherOfferBookView != null) {
                if (childViewClass == null) {
                    otherOfferBookTab.setContent(otherOfferBookView.getRoot());
                } else if (childViewClass == TakeOfferView.class) {
                    loadTakeViewClass(viewClass, childViewClass, otherOfferBookTab);
                } else {
                    loadCreateViewClass(otherOfferBookView, viewClass, childViewClass, otherOfferBookTab, (PaymentMethod) data);
                }
                tabPane.getSelectionModel().select(otherOfferBookTab);
            } else {
                if (fiatOfferBookTab == null) {

                    // add preceding label tab
                    labelTab = new Tab();
                    labelTab.setDisable(true);
                    labelTab.setContent(new Label());
                    labelTab.setClosable(false);
                    Label offerLabel = new Label(getOfferLabel()); // use overlay for label for custom formatting
                    offerLabel.getStyleClass().add("titled-group-bg-label");
                    offerLabel.setStyle("-fx-font-size: 1.3em;");
                    labelTab.setGraphic(offerLabel);

                    fiatOfferBookTab = new Tab(Res.get("shared.fiat"));
                    fiatOfferBookTab.setClosable(false);
                    cryptoOfferBookTab = new Tab(Res.get("shared.crypto"));
                    cryptoOfferBookTab.setClosable(false);
                    otherOfferBookTab = new Tab(Res.get("shared.other"));
                    otherOfferBookTab.setClosable(false);
                    tabPane.getTabs().addAll(labelTab, fiatOfferBookTab, cryptoOfferBookTab, otherOfferBookTab);
                }
                if (viewClass == FiatOfferBookView.class) {
                    fiatOfferBookView = (FiatOfferBookView) viewLoader.load(FiatOfferBookView.class);
                    fiatOfferBookView.setOfferActionHandler(offerActionHandler);
                    fiatOfferBookView.setDirection(direction);
                    fiatOfferBookView.onTabSelected(true);
                    tabPane.getSelectionModel().select(fiatOfferBookTab);
                    fiatOfferBookTab.setContent(fiatOfferBookView.getRoot());
                } else if (viewClass == CryptoOfferBookView.class) {
                    cryptoOfferBookView = (CryptoOfferBookView) viewLoader.load(CryptoOfferBookView.class);
                    cryptoOfferBookView.setOfferActionHandler(offerActionHandler);
                    cryptoOfferBookView.setDirection(direction);
                    cryptoOfferBookView.onTabSelected(true);
                    tabPane.getSelectionModel().select(cryptoOfferBookTab);
                    cryptoOfferBookTab.setContent(cryptoOfferBookView.getRoot());
                } else if (viewClass == OtherOfferBookView.class) {
                    otherOfferBookView = (OtherOfferBookView) viewLoader.load(OtherOfferBookView.class);
                    otherOfferBookView.setOfferActionHandler(offerActionHandler);
                    otherOfferBookView.setDirection(direction);
                    otherOfferBookView.onTabSelected(true);
                    tabPane.getSelectionModel().select(otherOfferBookTab);
                    otherOfferBookTab.setContent(otherOfferBookView.getRoot());
                }
            }
        }
    }

    private void loadCreateViewClass(OfferBookView<?, ?> offerBookView,
                                     Class<? extends View> viewClass,
                                     Class<? extends View> childViewClass,
                                     Tab marketOfferBookTab,
                                     @Nullable PaymentMethod paymentMethod) {
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
            //reset tab
            navigation.navigateTo(MainView.class, this.getClass(), viewClass);
        });

        // close handler from close on create offer action
        marketOfferBookTab.setContent(view.getRoot());
    }

    private void loadTakeViewClass(Class<? extends View> viewClass,
                                   Class<? extends View> childViewClass,
                                   Tab marketOfferBookTab) {

        if (offer == null) {
            return;
        }

        View view = viewLoader.load(childViewClass);
        // CreateOffer and TakeOffer must not be cached by ViewLoader as we cannot use a view multiple times
        // in different graphs
        ((InitializableViewWithTakeOfferData) view).initWithData(offer);
        ((SelectableView) view).onTabSelected(true);

        // close handler from close on take offer action
        ((ClosableView) view).setCloseHandler(() -> {
            ((SelectableView) view).onTabSelected(false);
            navigation.navigateTo(MainView.class, this.getClass(), viewClass);
        });
        marketOfferBookTab.setContent(view.getRoot());
    }

    protected boolean canCreateOrTakeOffer(TradeCurrency tradeCurrency) {
        return GUIUtil.isBootstrappedOrShowPopup(p2PService) &&
                GUIUtil.canCreateOrTakeOfferOrShowPopup(user, navigation);
    }

    private void showTakeOffer(Offer offer) {
        this.offer = offer;

        Class<? extends OfferBookView<?, ?>> offerBookViewClass = getOfferBookViewClassFor(offer.getCurrencyCode());
        navigation.navigateTo(MainView.class, this.getClass(), offerBookViewClass, TakeOfferView.class);
    }

    private void showCreateOffer(TradeCurrency tradeCurrency, PaymentMethod paymentMethod) {
        this.tradeCurrency = tradeCurrency;

        Class<? extends OfferBookView<?, ?>> offerBookViewClass = getOfferBookViewClassFor(tradeCurrency.getCode());
        navigation.navigateToWithData(paymentMethod, MainView.class, this.getClass(), offerBookViewClass, CreateOfferView.class);
    }

    @NotNull
    private Class<? extends OfferBookView<?, ?>> getOfferBookViewClassFor(String currencyCode) {
        Class<? extends OfferBookView<?, ?>> offerBookViewClass;
        if (CurrencyUtil.isFiatCurrency(currencyCode)) {
            offerBookViewClass = FiatOfferBookView.class;
        } else if (CurrencyUtil.isCryptoCurrency(currencyCode)) {
            offerBookViewClass = CryptoOfferBookView.class;
        } else {
            offerBookViewClass = OtherOfferBookView.class;
        }
        return offerBookViewClass;
    }

    public interface OfferActionHandler {
        void onCreateOffer(TradeCurrency tradeCurrency, PaymentMethod paymentMethod);

        void onTakeOffer(Offer offer);
    }

    public interface CloseHandler {
        void close();
    }
}
