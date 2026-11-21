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

package haveno.desktop.main.offer.quicktrade;

import com.google.inject.Inject;
import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.core.account.sign.SignedWitnessService;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.monetary.Volume;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.PaymentAccountFactory;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.core.util.FormattingUtils;
import haveno.core.util.VolumeUtil;
import haveno.desktop.Navigation;
import haveno.desktop.app.StartupWizard;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.components.paymentmethods.PaymentAccountForms;
import haveno.desktop.components.paymentmethods.PaymentMethodForm;
import haveno.desktop.main.MainView;
import haveno.desktop.main.account.content.cryptoaccounts.CryptoAccountsDataModel;
import haveno.desktop.main.account.content.traditionalaccounts.TraditionalAccountsDataModel;
import haveno.desktop.main.offer.BuyOfferView;
import haveno.desktop.main.offer.OfferView;
import haveno.desktop.main.offer.OfferViewUtil;
import haveno.desktop.main.offer.SellOfferView;
import haveno.desktop.main.offer.offerbook.OfferBook;
import haveno.desktop.main.offer.offerbook.OfferBookListItem;
import haveno.desktop.main.overlays.Overlay;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.overlays.popups.PopupManager;
import haveno.desktop.util.FormBuilder;
import haveno.desktop.util.GUIUtil;
import haveno.desktop.util.Layout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javax.annotation.Nullable;

// A wizard to buy or sell XMR. It asks for the direction, the currency and the payment method, lists the offers which
// fit, adds a payment account if one is missing, then continues in the take offer or the create offer view.
public class QuickTradeWindow extends Overlay<QuickTradeWindow> {

    private static final double PAGE_WIDTH = 720;
    private static final double PAGE_HEIGHT = 410;
    private static final double SHADOW_PADDING = 30;
    // list and cell padding plus the scroll bar, which a row leaves free
    private static final double ROW_INSETS = 52;

    // A page of the wizard, built for the answers given so far each time it shows.
    private abstract class Page {
        // keeps the next button disabled, e.g. until a choice is made
        final BooleanProperty incomplete = new SimpleBooleanProperty();

        abstract Region build();

        boolean isSkipped() {
            return false;
        }

        // runs the handler once the wizard can move on
        void onNext(Runnable resultHandler) {
            resultHandler.run();
        }
    }

    private final QuickTradeModel model;
    private final Navigation navigation;
    private final Preferences preferences;
    private final User user;
    private final OfferBook offerBook;
    private final AccountAgeWitnessService accountAgeWitnessService;
    private final SignedWitnessService signedWitnessService;
    private final PaymentAccountForms paymentAccountForms;
    private final TraditionalAccountsDataModel traditionalAccountsDataModel;
    private final CryptoAccountsDataModel cryptoAccountsDataModel;

    private final List<Page> pages = List.of(new DirectionPage(), new CurrencyPage(), new PaymentMethodPage(), new OfferPage(), new AccountPage());
    private final ListChangeListener<OfferBookListItem> offerBookListener = change -> onOfferBookChanged();
    // run once the page being built is laid out, e.g. to focus a field or scroll to a selection
    private final List<Runnable> shownHandlers = new ArrayList<>();
    private StackPane rootContainer, pageSlot;
    private HBox progressDots;
    private Button backButton, nextButton;
    private int pageIndex;
    private Timer refreshTimer;
    // the offer to take, or null to create an offer
    @Nullable
    private Offer offer;
    // how much to trade in the currency, if entered
    @Nullable
    private Volume volume;

    @Inject
    public QuickTradeWindow(QuickTradeModel model,
                            Navigation navigation,
                            Preferences preferences,
                            User user,
                            OfferBook offerBook,
                            AccountAgeWitnessService accountAgeWitnessService,
                            SignedWitnessService signedWitnessService,
                            PaymentAccountForms paymentAccountForms,
                            TraditionalAccountsDataModel traditionalAccountsDataModel,
                            CryptoAccountsDataModel cryptoAccountsDataModel) {
        this.model = model;
        this.navigation = navigation;
        this.preferences = preferences;
        this.user = user;
        this.offerBook = offerBook;
        this.accountAgeWitnessService = accountAgeWitnessService;
        this.signedWitnessService = signedWitnessService;
        this.paymentAccountForms = paymentAccountForms;
        this.traditionalAccountsDataModel = traditionalAccountsDataModel;
        this.cryptoAccountsDataModel = cryptoAccountsDataModel;
    }

    // starts from a currency and its payment method if given, which the user can still change
    public void show(OfferDirection direction, @Nullable TradeCurrency currency, @Nullable PaymentMethod paymentMethod) {
        model.direction = direction;
        model.currency = currency;
        model.paymentMethod = currency == null || CurrencyUtil.isCryptoCurrency(currency.getCode()) ? null : paymentMethod;
        model.refreshOffers();
        offer = null;
        volume = null;
        show();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Window
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void createGridPane() {
        pageSlot = new StackPane();
        pageSlot.setAlignment(Pos.TOP_CENTER);
        pageSlot.setMinSize(PAGE_WIDTH, PAGE_HEIGHT);
        pageSlot.setMaxSize(PAGE_WIDTH, PAGE_HEIGHT);

        backButton = new AutoTooltipButton("< " + Res.get("tacWindow.legal.back"));
        backButton.getStyleClass().addAll("tac-agreement-secondary-button", "tac-agreement-back-button");
        backButton.setMinWidth(110);
        backButton.setOnAction(event -> showPage(previousIndex(), false));
        Button cancelButton = new AutoTooltipButton(Res.get("shared.cancel"));
        cancelButton.getStyleClass().addAll("tac-agreement-secondary-button", "tac-agreement-back-button");
        cancelButton.setMinWidth(110);
        cancelButton.setOnAction(event -> hide());
        nextButton = new AutoTooltipButton();
        nextButton.getStyleClass().addAll("action-button", "tac-agreement-action-button");
        nextButton.setMinWidth(190);
        nextButton.setDefaultButton(true);
        nextButton.setOnAction(event -> onNext());

        // back at the left, cancel and next at the right, like the startup wizard
        HBox actionButtons = new HBox(12, cancelButton, nextButton);
        actionButtons.setMaxWidth(Region.USE_PREF_SIZE);
        StackPane footer = new StackPane(backButton, actionButtons);
        StackPane.setAlignment(backButton, Pos.CENTER_LEFT);
        StackPane.setAlignment(actionButtons, Pos.CENTER_RIGHT);
        Region footerSeparator = new Region();
        footerSeparator.getStyleClass().add("tac-agreement-footer-separator");

        gridPane = new GridPane();
        gridPane.setVgap(12);
        gridPane.getStyleClass().add("wizard-card");
        gridPane.setMaxWidth(Region.USE_PREF_SIZE);
        gridPane.addColumn(0, pageSlot, footerSeparator, footer);

        progressDots = new HBox(7);
        progressDots.setAlignment(Pos.CENTER);

        VBox column = new VBox(12, gridPane, progressDots);
        column.setAlignment(Pos.TOP_CENTER);
        rootContainer = new StackPane(column);
        rootContainer.getStyleClass().add("quick-trade-root");
        rootContainer.setPadding(new Insets(SHADOW_PADDING));
        // popups open above the wizard without blocking it, so it waits for them
        rootContainer.disableProperty().bind(PopupManager.hasPendingPopupsProperty());

        showPage(0, true);
    }

    @Override
    protected Region getRootContainer() {
        return rootContainer;
    }

    @Override
    protected void addHeadLine() {
    }

    @Override
    protected void addMessage() {
    }

    @Override
    protected void addButtons() {
    }

    @Override
    protected void applyStyles() {
    }

    @Override
    protected void setupKeyHandler(Scene scene) {
        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                event.consume();
                hide();
            }
        });
    }

    @Override
    protected void onShow() {
        offerBook.getOfferBookListItems().addListener(offerBookListener);
        display();
        UserThread.execute(this::runShownHandlers); // after the window is shown
    }

    @Override
    protected void cleanup() {
        offerBook.getOfferBookListItems().removeListener(offerBookListener);
        if (refreshTimer != null) refreshTimer.stop();
        refreshTimer = null;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Navigation
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void showPage(int index, boolean forward) {
        boolean animate = pageSlot.getScene() != null;
        pageIndex = index;
        Page page = pages.get(index);
        Region content = page.build();
        pageSlot.getChildren().setAll(content);
        pageSlot.applyCss();
        pageSlot.layout();
        if (animate) {
            runShownHandlers();
            StartupWizard.playStepTransition(content, forward);
        }
        backButton.setVisible(previousIndex() >= 0);
        nextButton.disableProperty().bind(page.incomplete);
        refreshNavigation();
    }

    // focus and scroll positions apply once the page is in the window
    private void runShownHandlers() {
        shownHandlers.forEach(Runnable::run);
        shownHandlers.clear();
    }

    // the next button names what the last page leads to, with one dot per page on the way there
    private void refreshNavigation() {
        boolean isLast = nextIndex() < 0;
        nextButton.setText(pages.get(pageIndex) instanceof AccountPage ? Res.get("shared.saveNewAccount") :
                !isLast ? Res.get("startupWizard.next") :
                offer != null ? Res.get("offerbook.takeOffer") : getCreateOfferText());
        progressDots.getChildren().clear();
        for (Page page : pages) {
            if (page.isSkipped()) continue;
            Region dot = new Region();
            dot.getStyleClass().add("wizard-progress-dot");
            if (page == pages.get(pageIndex)) dot.getStyleClass().add("wizard-progress-dot-active");
            progressDots.getChildren().add(dot);
        }
    }

    private void onNext() {
        pages.get(pageIndex).onNext(() -> {
            if (nextIndex() >= 0) showPage(nextIndex(), true);
            else onComplete();
        });
    }

    private int nextIndex() {
        for (int i = pageIndex + 1; i < pages.size(); i++) {
            if (!pages.get(i).isSkipped()) return i;
        }
        return -1;
    }

    private int previousIndex() {
        for (int i = pageIndex - 1; i >= 0; i--) {
            if (!pages.get(i).isSkipped()) return i;
        }
        return -1;
    }

    // continue in the offer view of the direction, which takes the offer or creates one
    private void onComplete() {
        hide();
        Volume takeVolume = offer != null && volume != null && model.isInRange(offer, volume) ? volume : null;
        navigation.navigateToWithData(new OfferView.TradeRequest(offer, model.currency, model.paymentMethod, takeVolume),
                MainView.class,
                model.direction == OfferDirection.BUY ? BuyOfferView.class : SellOfferView.class,
                OfferViewUtil.getOfferBookViewClass(model.currency.getCode()));
    }

    // offers come and go while the wizard shows, so keep the offer page current
    private void onOfferBookChanged() {
        if (refreshTimer != null || !(pages.get(pageIndex) instanceof OfferPage)) return;
        refreshTimer = UserThread.runAfter(() -> {
            refreshTimer = null;
            if (pages.get(pageIndex) instanceof OfferPage page) page.refresh();
        }, 500, TimeUnit.MILLISECONDS);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Pages
    ///////////////////////////////////////////////////////////////////////////////////////////

    private class DirectionPage extends Page {
        @Override
        Region build() {
            StartupWizard.ChoiceCard buyCard = new StartupWizard.ChoiceCard(MaterialDesignIcon.ARROW_DOWN_BOLD_CIRCLE_OUTLINE,
                    Res.get("mainView.menu.buyXmr"), null, Res.get("quickTrade.direction.buy"));
            StartupWizard.ChoiceCard sellCard = new StartupWizard.ChoiceCard(MaterialDesignIcon.ARROW_UP_BOLD_CIRCLE_OUTLINE,
                    Res.get("mainView.menu.sellXmr"), null, Res.get("quickTrade.direction.sell"));
            StartupWizard.ChoiceCard.group(() -> {
                model.direction = buyCard.isSelected() ? OfferDirection.BUY : OfferDirection.SELL;
                model.refreshOffers();
            }, buyCard, sellCard);
            StartupWizard.ChoiceCard selectedCard = model.direction == OfferDirection.BUY ? buyCard : sellCard;
            selectedCard.setSelected(true);
            shownHandlers.add(selectedCard::requestFocus);

            HBox cards = new HBox(16, buyCard, sellCard);
            for (StartupWizard.ChoiceCard card : List.of(buyCard, sellCard)) {
                card.setPrefWidth((PAGE_WIDTH - cards.getSpacing()) / 2);
                card.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
                    if (event.getClickCount() == 2) nextButton.fire();
                });
            }
            VBox.setMargin(cards, new Insets(30, 0, 0, 0));
            return createPage(MaterialDesignIcon.FLASH, Res.get("quickTrade.title"), Res.get("quickTrade.direction.subtitle"), cards);
        }
    }

    private class CurrencyPage extends Page {
        @Override
        Region build() {
            List<TradeCurrency> currencies = model.getCurrencies();
            FilteredList<TradeCurrency> filtered = new FilteredList<>(FXCollections.observableArrayList(currencies));
            ListView<TradeCurrency> list = createList(filtered, currency -> createRow(
                    GUIUtil.getCurrencyGraphic(currency.getCode(), 22), new Label(currency.getName() + " (" + currency.getCode() + ")"), null,
                    model.getNumOffers(currency)));
            list.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, currency) -> {
                if (currency == null) return;
                if (!currency.equals(model.currency)) {
                    model.paymentMethod = null;
                    volume = null;
                }
                model.currency = currency;
            });
            incomplete.bind(list.getSelectionModel().selectedItemProperty().isNull());

            // a currency chosen before, else the user's own if it has offers, else the one with the most offers
            TradeCurrency preselected = model.currency != null ? model.currency : CurrencyUtil.getDefaultTradeCurrency();
            if (model.currency == null && model.getNumOffers(preselected) == 0 && model.getNumOffers(currencies.get(0)) > 0) preselected = currencies.get(0);
            select(list, preselected);

            TextField searchField = new TextField();
            searchField.setPromptText(Res.get("startupWizard.currency.search"));
            searchField.getStyleClass().add("login-password-field");
            searchField.textProperty().addListener((observable, oldValue, text) -> {
                String filter = text.trim().toLowerCase();
                filtered.setPredicate(currency -> currency.getName().toLowerCase().contains(filter) || currency.getCode().toLowerCase().contains(filter));
                list.getSelectionModel().selectFirst();
            });
            moveSelectionWithArrowKeys(searchField, list);
            shownHandlers.add(searchField::requestFocus);

            return createPage(MaterialDesignIcon.EARTH,
                    Res.get(model.direction == OfferDirection.BUY ? "quickTrade.currency.headline.buy" : "quickTrade.currency.headline.sell"),
                    Res.get("quickTrade.currency.subtitle"),
                    createField(searchField, StartupWizard.createIcon(MaterialDesignIcon.MAGNIFY, "1.1em", "wizard-search-icon")), list);
        }
    }

    private class PaymentMethodPage extends Page {
        @Override
        boolean isSkipped() {
            return model.currency != null && CurrencyUtil.isCryptoCurrency(model.currency.getCode());
        }

        @Override
        Region build() {
            List<PaymentMethod> paymentMethods = model.getPaymentMethods();
            ListView<PaymentMethod> list = createList(FXCollections.observableArrayList(paymentMethods), paymentMethod -> createRow(
                    null, new Label(Res.get(paymentMethod.getId())), model.hasAccount(paymentMethod) ? Res.get("quickTrade.paymentMethod.account") : null,
                    model.getNumOffers(paymentMethod)));
            list.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, paymentMethod) -> model.paymentMethod = paymentMethod);
            incomplete.bind(list.getSelectionModel().selectedItemProperty().isNull());

            // a method chosen before, else one of the user's with offers, else any with offers, else one of the user's
            PaymentMethod preselected = paymentMethods.contains(model.paymentMethod) ? model.paymentMethod : null;
            for (boolean hasAccount : new boolean[]{true, false}) {
                for (PaymentMethod paymentMethod : paymentMethods) {
                    if (preselected == null && model.hasAccount(paymentMethod) == hasAccount && model.getNumOffers(paymentMethod) > 0) preselected = paymentMethod;
                }
            }
            if (preselected == null) preselected = paymentMethods.stream().filter(model::hasAccount).findFirst().orElse(null);
            select(list, preselected);
            shownHandlers.add(list::requestFocus);

            return createPage(MaterialDesignIcon.BANK,
                    Res.get(model.direction == OfferDirection.BUY ? "quickTrade.paymentMethod.headline.buy" : "quickTrade.paymentMethod.headline.sell"),
                    Res.get("quickTrade.paymentMethod.subtitle", getSummary()), list);
        }
    }

    private class OfferPage extends Page {
        private ListView<Offer> list;
        private Hyperlink createOfferLink;

        @Override
        Region build() {
            list = createList(FXCollections.observableArrayList(), this::createOfferRow);
            list.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, selected) -> {
                offer = selected;
                refreshNavigation();
            });
            Label placeholder = new AutoTooltipLabel(Res.get("quickTrade.offers.none"));
            placeholder.getStyleClass().add("startup-wizard-footer-label");
            list.setPlaceholder(placeholder);

            String currencyCode = model.currency.getCode();
            TextField amountField = new TextField(volume == null ? "" : VolumeUtil.formatVolume(volume));
            amountField.setPromptText(Res.get("quickTrade.offers.amount", currencyCode));
            amountField.getStyleClass().add("login-password-field");
            amountField.textProperty().addListener((observable, oldValue, text) -> {
                try {
                    volume = text.isBlank() ? null : Volume.parse(text, currencyCode);
                    if (volume != null && volume.getValue() <= 0) volume = null;
                } catch (Exception e) {
                    volume = null;
                }
                refresh();
            });
            moveSelectionWithArrowKeys(amountField, list);
            Label currency = new Label(currencyCode);
            currency.getStyleClass().add("quick-trade-detail");

            // creating an offer stays one click away while offers exist
            createOfferLink = new Hyperlink(Res.get("quickTrade.offers.create"));
            createOfferLink.getStyleClass().add("quick-trade-link");
            createOfferLink.setOnAction(event -> {
                list.getSelectionModel().clearSelection();
                nextButton.fire();
            });
            HBox createOfferBox = new HBox(createOfferLink);
            createOfferBox.setAlignment(Pos.CENTER_RIGHT);
            createOfferBox.managedProperty().bind(createOfferLink.visibleProperty());

            // the amount only narrows down offers, so it shows while there are some
            Region amountBox = createField(amountField, currency);
            amountBox.visibleProperty().bind(createOfferLink.visibleProperty());
            amountBox.managedProperty().bind(amountBox.visibleProperty());

            refresh();
            shownHandlers.add(list::requestFocus);
            return createPage(MaterialDesignIcon.TAG_OUTLINE, Res.get("quickTrade.offers.headline"),
                    Res.get(model.direction == OfferDirection.BUY ? "quickTrade.offers.subtitle.buy" : "quickTrade.offers.subtitle.sell", getSummary()),
                    amountBox, list, createOfferBox);
        }

        // offers which trade the entered amount come first, each group in price order
        void refresh() {
            Offer selected = offer;
            model.refreshOffers();
            List<Offer> offers = model.getOffers();
            if (volume != null) offers.sort((offer1, offer2) -> Boolean.compare(model.isInRange(offer2, volume), model.isInRange(offer1, volume)));
            list.getItems().setAll(offers);
            // keep the selected offer while it trades the amount, else move to the best one
            int index = selected == null || volume != null && !model.isInRange(selected, volume) ? -1 :
                    offers.stream().map(Offer::getId).toList().indexOf(selected.getId());
            list.getSelectionModel().select(Math.max(index, 0));
            createOfferLink.setVisible(!offers.isEmpty());
        }

        private Node createOfferRow(Offer offer) {
            String currencyCode = offer.getCounterCurrencyCode();
            boolean isBuy = model.direction == OfferDirection.BUY;
            boolean inRange = volume == null || model.isInRange(offer, volume);

            Label price = new Label(FormattingUtils.formatPrice(offer.getPrice()) + " " + currencyCode);
            price.getStyleClass().add("quick-trade-value");
            HBox priceBox = new HBox(8, price);
            priceBox.setAlignment(Pos.CENTER_LEFT);
            if (offer == list.getItems().get(0) && inRange) {
                Label badge = new Label(Res.get("quickTrade.offers.bestPrice"));
                badge.getStyleClass().add("wizard-choice-card-badge");
                priceBox.getChildren().add(badge);
            }
            // a price below the market is in a buyer's favor, one above in a seller's
            double aboveMarket = model.getPriceAboveMarket(offer).orElse(Double.NaN);
            boolean atMarket = Math.abs(aboveMarket) < 0.00005;
            Label market = new Label(Double.isNaN(aboveMarket) ? "" : atMarket ? Res.get("quickTrade.offers.atMarket") :
                    Res.get(aboveMarket > 0 ? "quickTrade.offers.aboveMarket" : "quickTrade.offers.belowMarket",
                            FormattingUtils.formatPercentagePrice(Math.abs(aboveMarket))));
            market.getStyleClass().add(!atMarket && aboveMarket < 0 == isBuy ? "quick-trade-in-favor" : "quick-trade-detail");

            Label amount, amountDetail;
            if (volume != null && inRange) {
                amount = new Label(HavenoUtils.formatXmr(model.getAmount(offer, volume), true));
                amountDetail = new Label(Res.get(isBuy ? "quickTrade.offers.receive" : "quickTrade.offers.send"));
            } else {
                amount = new Label(getRange(HavenoUtils.formatXmr(offer.getMinAmount()), HavenoUtils.formatXmr(model.getMaxAmount(offer))) + " XMR");
                amountDetail = new Label(getRange(VolumeUtil.formatVolume(offer.getMinVolume()),
                        VolumeUtil.formatVolume(offer.getVolumeByAmount(model.getMaxAmount(offer), offer.getMinAmount(), offer.getAmount()))) + " " + currencyCode);
            }
            amount.getStyleClass().add("quick-trade-value");
            amountDetail.getStyleClass().add("quick-trade-detail");

            Label paymentMethod = new Label(OfferViewUtil.getPaymentMethod(offer));
            paymentMethod.setTooltip(new Tooltip(OfferViewUtil.getPaymentMethodToolTip(offer)));
            double depositPct = isBuy ? offer.getBuyerSecurityDepositPct() : offer.getSellerSecurityDepositPct();
            Label deposit = new Label(Res.get("quickTrade.offers.deposit", FormattingUtils.formatToRoundedPercentWithSymbol(depositPct)));
            deposit.getStyleClass().add("quick-trade-detail");

            // the offer book's item caches the age of the maker's account
            OfferBookListItem.WitnessAgeData witnessAgeData = offerBook.getOfferBookListItems().stream()
                    .filter(item -> item.getOffer() == offer)
                    .findAny()
                    .orElseGet(() -> new OfferBookListItem(offer))
                    .getWitnessAgeData(accountAgeWitnessService, signedWitnessService);
            Text accountIcon = FormBuilder.getIcon(witnessAgeData.getIcon(), "1.1em");
            accountIcon.getStyleClass().add("quick-trade-icon");
            Label account = new Label(witnessAgeData.getDisplayString(), accountIcon);
            account.getStyleClass().add("quick-trade-detail");
            account.setTooltip(new Tooltip(witnessAgeData.getInfo()));
            account.setMinWidth(Region.USE_PREF_SIZE);
            // crypto offers have no account age to show
            account.setVisible(witnessAgeData.getType() != OfferBookListItem.WitnessAgeData.TYPE_CRYPTOS);
            account.setManaged(account.isVisible());

            // the payment method takes the width which the fixed columns and the account leave
            VBox terms = new VBox(1, paymentMethod, deposit);
            terms.setMinWidth(0);
            HBox.setHgrow(terms, Priority.ALWAYS);
            HBox row = new HBox(createColumn(210, priceBox, market), createColumn(190, amount, amountDetail), terms, account);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setOpacity(inRange ? 1 : 0.45);
            return row;
        }

        private VBox createColumn(double width, Node... lines) {
            VBox column = new VBox(1, lines);
            column.setMinWidth(width);
            column.setMaxWidth(width);
            return column;
        }

        private String getRange(String min, String max) {
            return min.equals(max) ? min : min + " - " + max;
        }
    }

    private class AccountPage extends Page {
        private PaymentMethodForm form;

        @Override
        boolean isSkipped() {
            // unknown before an offer is chosen on the page before this one
            if (pageIndex < pages.indexOf(this) - 1) return true;
            return offer != null ? model.canTakeOffer(offer) : model.getAccountForNewOffer() != null;
        }

        @Override
        Region build() {
            PaymentAccount account = PaymentAccountFactory.getPaymentAccount(model.getAccountPaymentMethod(offer));
            account.init();
            // an account whose method does not set its currency starts with the chosen one
            if (account.getTradeCurrencies().isEmpty()) account.setSingleTradeCurrency(model.currency);
            if (account.getMessageForAccountCreation() != null) {
                new Popup().information(Res.get(account.getMessageForAccountCreation())).width(900).closeButtonText(Res.get("shared.iUnderstand")).show();
            }

            GridPane grid = new GridPane();
            grid.setHgap(5);
            grid.setVgap(5);
            grid.setPadding(new Insets(0, 14, 14, 14));
            ColumnConstraints column = new ColumnConstraints();
            column.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(column);
            form = paymentAccountForms.getForm(account, grid, -1);
            form.addFormForAddAccount();
            incomplete.bind(form.allInputsValidProperty().not());

            // the first row starts at the top of the panel, without the distance some forms keep to a group title
            for (Node node : grid.getChildren()) {
                Insets margin = GridPane.getMargin(node);
                if (margin == null || !Integer.valueOf(0).equals(GridPane.getRowIndex(node))) continue;
                GridPane.setMargin(node, new Insets(Math.min(margin.getTop(), Layout.FLOATING_LABEL_DISTANCE), margin.getRight(), margin.getBottom(), margin.getLeft()));
            }

            // typing starts in the first text field, as a currency selector would open its list once focused
            grid.getChildren().stream().filter(node -> node instanceof TextInputControl input && input.isEditable()).findFirst()
                    .ifPresent(input -> shownHandlers.add(input::requestFocus));

            ScrollPane scrollPane = new ScrollPane(grid);
            scrollPane.setFitToWidth(true);
            scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scrollPane.getStyleClass().add("quick-trade-form");
            VBox.setVgrow(scrollPane, Priority.ALWAYS);

            return createPage(MaterialDesignIcon.ACCOUNT_CARD_DETAILS,
                    Res.get("quickTrade.account.headline", account.getPaymentMethod().isBlockchain() ? model.currency.getName() : Res.get(account.getPaymentMethod().getId())),
                    Res.get(offer != null ? "quickTrade.account.subtitle.take" : "quickTrade.account.subtitle.create"), scrollPane);
        }

        // save the account once its info is confirmed, then move on if it fits the offer
        @Override
        void onNext(Runnable resultHandler) {
            PaymentAccount account = form.getPaymentAccount();
            paymentAccountForms.confirmNewAccount(account, () -> {
                if (user.getPaymentAccounts() != null && user.getPaymentAccounts().stream().anyMatch(other -> account.getAccountName().equals(other.getAccountName()))) {
                    new Popup().warning(Res.get("shared.accountNameAlreadyUsed")).show();
                    return;
                }
                if (account.getPaymentMethod().isBlockchain()) cryptoAccountsDataModel.onSaveNewAccount(account);
                else traditionalAccountsDataModel.onSaveNewAccount(account);
                if (offer == null) {
                    preferences.setSelectedPaymentAccountForCreateOffer(account);
                } else if (model.canTakeOffer(offer)) {
                    preferences.setTakeOfferSelectedPaymentAccountId(account.getId());
                } else {
                    new Popup().warning(Res.get("quickTrade.account.mismatch")).show();
                    model.refreshOffers();
                    showPage(previousIndex(), false);
                    return;
                }
                resultHandler.run();
            });
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Utils
    ///////////////////////////////////////////////////////////////////////////////////////////

    private VBox createPage(MaterialDesignIcon icon, String title, String subtitle, Node... content) {
        VBox page = new VBox(12, StartupWizard.createHeaderSection(icon, title, subtitle));
        page.getChildren().addAll(content);
        return page;
    }

    // a list of choices in the wizard's style, where a double click moves on like the next button
    private <T> ListView<T> createList(ObservableList<T> items, Function<T, Node> rowFactory) {
        ListView<T> list = new ListView<>(items);
        list.getStyleClass().add("wizard-list");
        list.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                Node row = empty || item == null ? null : rowFactory.apply(item);
                if (row instanceof Region region) region.prefWidthProperty().bind(listView.widthProperty().subtract(ROW_INSETS));
                setGraphic(row);
            }
        });
        list.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && list.getSelectionModel().getSelectedItem() != null && !nextButton.isDisabled()) nextButton.fire();
        });
        // the list would keep Enter from the default button and Escape from the window
        list.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) nextButton.fire();
            else if (event.getCode() == KeyCode.ESCAPE) hide();
            else return;
            event.consume();
        });
        VBox.setVgrow(list, Priority.ALWAYS);
        return list;
    }

    // a list row: an icon, a label, a badge and the number of offers at the right, where given
    private HBox createRow(@Nullable Node icon, Label label, @Nullable String badgeText, int numOffers) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        if (icon != null) row.getChildren().add(icon);
        row.getChildren().add(label);
        if (badgeText != null) {
            Label badge = new Label(badgeText);
            badge.getStyleClass().add("wizard-choice-card-badge");
            row.getChildren().add(badge);
        }
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label offers = new Label(numOffers == 0 ? "" : numOffers + " " + Res.get(numOffers == 1 ? "shared.oneOffer" : "shared.multipleOffers"));
        offers.getStyleClass().add("quick-trade-detail");
        row.getChildren().addAll(spacer, offers);
        return row;
    }

    // a text field with a node at its right end, like the search field of the startup wizard
    private StackPane createField(TextField field, Node trailing) {
        trailing.setMouseTransparent(true);
        StackPane box = new StackPane(field, trailing);
        StackPane.setAlignment(trailing, Pos.CENTER_RIGHT);
        StackPane.setMargin(trailing, new Insets(0, 12, 0, 0));
        return box;
    }

    private <T> void select(ListView<T> list, @Nullable T item) {
        if (item == null) return;
        list.getSelectionModel().select(item);
        shownHandlers.add(() -> list.scrollTo(Math.max(0, list.getSelectionModel().getSelectedIndex() - 2)));
    }

    // let the arrow keys move the list selection while typing in the field
    private void moveSelectionWithArrowKeys(TextField field, ListView<?> list) {
        field.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            int delta = event.getCode() == KeyCode.DOWN ? 1 : event.getCode() == KeyCode.UP ? -1 : 0;
            if (delta == 0) return;
            int index = Math.min(Math.max(list.getSelectionModel().getSelectedIndex() + delta, 0), list.getItems().size() - 1);
            list.getSelectionModel().select(index);
            list.scrollTo(Math.max(0, index - 2));
            event.consume();
        });
    }

    // the answers so far as a phrase, e.g. "Buying XMR with Euro via SEPA"
    private String getSummary() {
        String summary = Res.get(model.direction == OfferDirection.BUY ? "quickTrade.summary.buy" : "quickTrade.summary.sell", model.currency.getName());
        return model.paymentMethod == null || pages.get(pageIndex) instanceof PaymentMethodPage ? summary :
                Res.get("quickTrade.summary.paymentMethod", summary, Res.get(model.paymentMethod.getId()));
    }

    private String getCreateOfferText() {
        return Res.get("offerbook.createNewOffer", Res.get(model.direction == OfferDirection.BUY ? "shared.buy" : "shared.sell"), Res.getBaseCurrencyCode());
    }
}
