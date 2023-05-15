/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
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
import haveno.desktop.main.offer.offerbook.BtcOfferBookView;
import haveno.desktop.main.offer.offerbook.OfferBookView;
import haveno.desktop.main.offer.offerbook.OtherOfferBookView;
import haveno.desktop.main.offer.offerbook.TopCryptoOfferBookView;
import haveno.desktop.main.offer.takeoffer.TakeOfferView;
import haveno.desktop.util.GUIUtil;
import haveno.network.p2p.P2PService;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.Optional;

public abstract class OfferView extends ActivatableView<TabPane, Void> {

    private OfferBookView<?, ?> btcOfferBookView, topCryptoOfferBookView, otherOfferBookView;

    private Tab btcOfferBookTab, topCryptoOfferBookTab, otherOfferBookTab;

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
                    if (newValue.equals(btcOfferBookTab)) {
                        if (btcOfferBookView != null) {
                            btcOfferBookView.onTabSelected(true);
                        } else {
                            loadView(BtcOfferBookView.class, null, null);
                        }
                    } else if (newValue.equals(topCryptoOfferBookTab)) {
                        if (topCryptoOfferBookView != null) {
                            topCryptoOfferBookView.onTabSelected(true);
                        } else {
                            loadView(TopCryptoOfferBookView.class, null, null);
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
                    if (oldValue.equals(btcOfferBookTab) && btcOfferBookView != null) {
                        btcOfferBookView.onTabSelected(false);
                    } else if (oldValue.equals(topCryptoOfferBookTab) && topCryptoOfferBookView != null) {
                        topCryptoOfferBookView.onTabSelected(false);
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
        if (btcOfferBookView == null) {
            navigation.navigateTo(MainView.class, this.getClass(), BtcOfferBookView.class);
        }

        GUIUtil.updateTopCrypto(preferences);

        if (topCryptoOfferBookTab != null) {
            topCryptoOfferBookTab.setText(GUIUtil.TOP_CRYPTO.getCode());
        }
    }

    @Override
    protected void deactivate() {
        navigation.removeListener(navigationListener);
        root.getSelectionModel().selectedItemProperty().removeListener(tabChangeListener);
    }

    private void loadView(Class<? extends View> viewClass,
                          Class<? extends View> childViewClass,
                          @Nullable Object data) {
        TabPane tabPane = root;
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);

        if (OfferBookView.class.isAssignableFrom(viewClass)) {

            if (viewClass == BtcOfferBookView.class && btcOfferBookTab != null && btcOfferBookView != null) {
                if (childViewClass == null) {
                    btcOfferBookTab.setContent(btcOfferBookView.getRoot());
                } else if (childViewClass == TakeOfferView.class) {
                    loadTakeViewClass(viewClass, childViewClass, btcOfferBookTab);
                } else {
                    loadCreateViewClass(btcOfferBookView, viewClass, childViewClass, btcOfferBookTab, (PaymentMethod) data);
                }
                tabPane.getSelectionModel().select(btcOfferBookTab);
            } else if (viewClass == TopCryptoOfferBookView.class && topCryptoOfferBookTab != null && topCryptoOfferBookView != null) {
                if (childViewClass == null) {
                    topCryptoOfferBookTab.setContent(topCryptoOfferBookView.getRoot());
                } else if (childViewClass == TakeOfferView.class) {
                    loadTakeViewClass(viewClass, childViewClass, topCryptoOfferBookTab);
                } else {
                    tradeCurrency = GUIUtil.TOP_CRYPTO;
                    loadCreateViewClass(topCryptoOfferBookView, viewClass, childViewClass, topCryptoOfferBookTab, (PaymentMethod) data);
                }
                tabPane.getSelectionModel().select(topCryptoOfferBookTab);
            } else if (viewClass == OtherOfferBookView.class && otherOfferBookTab != null && otherOfferBookView != null) {
                if (childViewClass == null) {
                    otherOfferBookTab.setContent(otherOfferBookView.getRoot());
                } else if (childViewClass == TakeOfferView.class) {
                    loadTakeViewClass(viewClass, childViewClass, otherOfferBookTab);
                } else {
                    //add sanity check in case of app restart
                    if (CurrencyUtil.isTraditionalCurrency(tradeCurrency.getCode())) {
                        Optional<TradeCurrency> tradeCurrencyOptional = (this.direction == OfferDirection.SELL) ?
                                CurrencyUtil.getTradeCurrency(preferences.getSellScreenCryptoCurrencyCode()) :
                                CurrencyUtil.getTradeCurrency(preferences.getBuyScreenCryptoCurrencyCode());
                        tradeCurrency = tradeCurrencyOptional.isEmpty() ? OfferViewUtil.getAnyOfMainCryptoCurrencies() : tradeCurrencyOptional.get();
                    }
                    loadCreateViewClass(otherOfferBookView, viewClass, childViewClass, otherOfferBookTab, (PaymentMethod) data);
                }
                tabPane.getSelectionModel().select(otherOfferBookTab);
            } else {
                if (btcOfferBookTab == null) {
                    btcOfferBookTab = new Tab(Res.getBaseCurrencyName().toUpperCase());
                    btcOfferBookTab.setClosable(false);
                    topCryptoOfferBookTab = new Tab(GUIUtil.TOP_CRYPTO.getCode());
                    topCryptoOfferBookTab.setClosable(false);
                    otherOfferBookTab = new Tab(Res.get("shared.other").toUpperCase());
                    otherOfferBookTab.setClosable(false);

                    tabPane.getTabs().addAll(btcOfferBookTab, topCryptoOfferBookTab, otherOfferBookTab);
                }
                if (viewClass == BtcOfferBookView.class) {
                    btcOfferBookView = (BtcOfferBookView) viewLoader.load(BtcOfferBookView.class);
                    btcOfferBookView.setOfferActionHandler(offerActionHandler);
                    btcOfferBookView.setDirection(direction);
                    btcOfferBookView.onTabSelected(true);
                    tabPane.getSelectionModel().select(btcOfferBookTab);
                    btcOfferBookTab.setContent(btcOfferBookView.getRoot());
                } else if (viewClass == TopCryptoOfferBookView.class) {
                    topCryptoOfferBookView = (TopCryptoOfferBookView) viewLoader.load(TopCryptoOfferBookView.class);
                    topCryptoOfferBookView.setOfferActionHandler(offerActionHandler);
                    topCryptoOfferBookView.setDirection(direction);
                    topCryptoOfferBookView.onTabSelected(true);
                    tabPane.getSelectionModel().select(topCryptoOfferBookTab);
                    topCryptoOfferBookTab.setContent(topCryptoOfferBookView.getRoot());
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
        if (CurrencyUtil.isTraditionalCurrency(currencyCode)) {
            offerBookViewClass = BtcOfferBookView.class;
        } else if (currencyCode.equals(GUIUtil.TOP_CRYPTO.getCode())) {
            offerBookViewClass = TopCryptoOfferBookView.class;
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
