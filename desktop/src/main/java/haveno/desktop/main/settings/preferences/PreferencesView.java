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

package haveno.desktop.main.settings.preferences;

import static com.google.common.base.Preconditions.checkArgument;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import haveno.common.UserThread;
import haveno.common.app.DevEnv;
import haveno.common.config.Config;
import haveno.common.util.Tuple2;
import haveno.common.util.Tuple3;
import haveno.common.util.Utilities;
import haveno.core.filter.Filter;
import haveno.core.filter.FilterManager;
import haveno.core.locale.Country;
import haveno.core.locale.CountryUtil;
import haveno.core.locale.CryptoCurrency;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.LanguageUtil;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.validation.XmrValidator;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.core.util.FormattingUtils;
import haveno.core.util.ParsingUtils;
import haveno.core.util.validation.IntegerValidator;
import haveno.core.util.validation.RegexValidator;
import haveno.core.util.validation.RegexValidatorFactory;
import haveno.core.xmr.wallet.Restrictions;
import haveno.desktop.common.view.ActivatableViewAndModel;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.components.InputTextField;
import haveno.desktop.components.PasswordTextField;
import haveno.desktop.components.TitledGroupBg;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.overlays.windows.EditCustomExplorerWindow;
import static haveno.desktop.util.FormBuilder.addButton;
import static haveno.desktop.util.FormBuilder.addComboBox;
import static haveno.desktop.util.FormBuilder.addInputTextField;
import static haveno.desktop.util.FormBuilder.addSlideToggleButton;
import static haveno.desktop.util.FormBuilder.addTextFieldWithEditButton;
import static haveno.desktop.util.FormBuilder.addTitledGroupBg;
import static haveno.desktop.util.FormBuilder.addTopLabelListView;
import haveno.desktop.util.GUIUtil;
import haveno.desktop.util.ImageUtil;
import haveno.desktop.util.Layout;
import java.io.File;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.VPos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.ImageView;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Callback;
import javafx.util.StringConverter;
import org.apache.commons.lang3.StringUtils;

@FxmlView
public class PreferencesView extends ActivatableViewAndModel<GridPane, PreferencesViewModel> {
    private final User user;
    private TextField xmrExplorerTextField;
    private ComboBox<String> userLanguageComboBox;
    private ComboBox<Country> userCountryComboBox;
    private ComboBox<TradeCurrency> preferredTradeCurrencyComboBox;

    private ToggleButton showOwnOffersInOfferBook, useAnimations, useDarkMode, sortMarketCurrenciesNumerically,
            avoidStandbyMode, useSoundForNotifications, useCustomFee, autoConfirmXmrToggle, hideNonAccountPaymentMethodsToggle, denyApiTakerToggle,
            notifyOnPreReleaseToggle;
    private int gridRow = 0;
    private int displayCurrenciesGridRowIndex = 0;
    private InputTextField ignoreTradersListInputTextField, ignoreDustThresholdInputTextField,
            autoConfRequiredConfirmationsTf, autoConfServiceAddressTf, autoConfTradeLimitTf, /*referralIdInputTextField,*/
            rpcUserTextField, blockNotifyPortTextField;
    private PasswordTextField rpcPwTextField;

    private ChangeListener<Boolean> autoConfServiceAddressFocusOutListener, autoConfRequiredConfirmationsFocusOutListener;
    private final Preferences preferences;
    //private final ReferralIdService referralIdService;
    private final FilterManager filterManager;
    private final File storageDir;

    private ListView<TraditionalCurrency> traditionalCurrenciesListView;
    private ComboBox<TraditionalCurrency> traditionalCurrenciesComboBox;
    private ListView<CryptoCurrency> cryptoCurrenciesListView;
    private ComboBox<CryptoCurrency> cryptoCurrenciesComboBox;
    private Button resetDontShowAgainButton, editCustomBtcExplorer;
    private ObservableList<String> languageCodes;
    private ObservableList<Country> countries;
    private ObservableList<TraditionalCurrency> traditionalCurrencies;
    private ObservableList<TraditionalCurrency> allTraditionalCurrencies;
    private ObservableList<CryptoCurrency> cryptoCurrencies;
    private ObservableList<CryptoCurrency> allCryptoCurrencies;
    private ObservableList<TradeCurrency> tradeCurrencies;
    private InputTextField deviationInputTextField;
    private ChangeListener<String> deviationListener, ignoreTradersListListener, ignoreDustThresholdListener,
            rpcUserListener, rpcPwListener, blockNotifyPortListener,
            autoConfTradeLimitListener, autoConfServiceAddressListener;
    private ChangeListener<Boolean> deviationFocusedListener;
    private final boolean displayStandbyModeFeature;
    private ChangeListener<Filter> filterChangeListener;

    private boolean hideXmrAutoConf = true; // TODO: remove xmr auto conf or use as a model for other blockchains


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, initialisation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public PreferencesView(PreferencesViewModel model,
                           Preferences preferences,
                           FilterManager filterManager,
                           Config config,
                           User user,
                           @Named(Config.STORAGE_DIR) File storageDir) {
        super(model);
        this.user = user;
        this.preferences = preferences;
        this.filterManager = filterManager;
        this.storageDir = storageDir;
        this.displayStandbyModeFeature = Utilities.isLinux() || Utilities.isOSX() || Utilities.isWindows();
    }

    @Override
    public void initialize() {
        languageCodes = FXCollections.observableArrayList(LanguageUtil.getUserLanguageCodes());
        countries = FXCollections.observableArrayList(CountryUtil.getAllCountries());
        traditionalCurrencies = preferences.getTraditionalCurrenciesAsObservable();
        cryptoCurrencies = preferences.getCryptoCurrenciesAsObservable();
        tradeCurrencies = preferences.getTradeCurrenciesAsObservable();

        allTraditionalCurrencies = FXCollections.observableArrayList(CurrencyUtil.getAllSortedTraditionalCurrencies());
        allTraditionalCurrencies.removeAll(traditionalCurrencies);

        initializeGeneralOptions();
        initializeDisplayOptions();
        initializeSeparator();
        initializeAutoConfirmOptions();
        initializeDisplayCurrencies();
    }


    @Override
    protected void activate() {
        // We want to have it updated in case an asset got removed
        allCryptoCurrencies = FXCollections.observableArrayList(CurrencyUtil.getActiveSortedCryptoCurrencies(filterManager));
        allCryptoCurrencies.removeAll(cryptoCurrencies);

        activateGeneralOptions();
        activateDisplayCurrencies();
        activateDisplayPreferences();
        activateAutoConfirmPreferences();
    }

    @Override
    protected void deactivate() {
        deactivateGeneralOptions();
        deactivateDisplayCurrencies();
        deactivateDisplayPreferences();
        deactivateAutoConfirmPreferences();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Initialize
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void initializeGeneralOptions() {
        int titledGroupBgRowSpan = displayStandbyModeFeature ? 8 : 7;
        TitledGroupBg titledGroupBg = addTitledGroupBg(root, gridRow, titledGroupBgRowSpan, Res.get("setting.preferences.general"));
        GridPane.setColumnSpan(titledGroupBg, 1);

        userLanguageComboBox = addComboBox(root, gridRow,
                Res.get("shared.language"), Layout.FIRST_ROW_DISTANCE);
        userCountryComboBox = addComboBox(root, ++gridRow,
                Res.get("shared.country"));
        userCountryComboBox.setButtonCell(GUIUtil.getComboBoxButtonCell(Res.get("shared.country"), userCountryComboBox,
                false));

        Tuple2<TextField, Button> xmrExp = addTextFieldWithEditButton(root, ++gridRow, Res.get("setting.preferences.explorer"));
        xmrExplorerTextField = xmrExp.first;
        editCustomBtcExplorer = xmrExp.second;

        // deviation
        deviationInputTextField = addInputTextField(root, ++gridRow,
                Res.get("setting.preferences.deviation"));
        deviationListener = (observable, oldValue, newValue) -> {
            try {
                double value = ParsingUtils.parsePercentStringToDouble(newValue);
                final double maxDeviation = 0.5;
                if (value <= maxDeviation) {
                    preferences.setMaxPriceDistanceInPercent(value);
                } else {
                    new Popup().warning(Res.get("setting.preferences.deviationToLarge", maxDeviation * 100)).show();
                    UserThread.runAfter(() -> deviationInputTextField.setText(FormattingUtils.formatToPercentWithSymbol(preferences.getMaxPriceDistanceInPercent())), 100, TimeUnit.MILLISECONDS);
                }
            } catch (NumberFormatException t) {
                log.error("Exception at parseDouble deviation: " + t.toString());
                UserThread.runAfter(() -> deviationInputTextField.setText(FormattingUtils.formatToPercentWithSymbol(preferences.getMaxPriceDistanceInPercent())), 100, TimeUnit.MILLISECONDS);
            }
        };
        deviationFocusedListener = (observable1, oldValue1, newValue1) -> {
            if (oldValue1 && !newValue1)
                UserThread.runAfter(() -> deviationInputTextField.setText(FormattingUtils.formatToPercentWithSymbol(preferences.getMaxPriceDistanceInPercent())), 100, TimeUnit.MILLISECONDS);
        };

        // ignoreTraders
        ignoreTradersListInputTextField = addInputTextField(root, ++gridRow,
                Res.get("setting.preferences.ignorePeers"));
        RegexValidator regexValidator = RegexValidatorFactory.addressRegexValidator();
        ignoreTradersListInputTextField.setValidator(regexValidator);
        ignoreTradersListInputTextField.setErrorMessage(Res.get("validation.invalidAddressList"));
        ignoreTradersListListener = (observable, oldValue, newValue) -> {
            if (regexValidator.validate(newValue).isValid && !newValue.equals(oldValue)) {
                preferences.setIgnoreTradersList(Arrays.asList(StringUtils.deleteWhitespace(newValue).split(",")));
            }
        };


        // ignoreDustThreshold
        ignoreDustThresholdInputTextField = addInputTextField(root, ++gridRow, Res.get("setting.preferences.ignoreDustThreshold"));
        IntegerValidator validator = new IntegerValidator();
        validator.setMinValue((int) Restrictions.getMinNonDustOutput().value);
        validator.setMaxValue(2000);
        ignoreDustThresholdInputTextField.setValidator(validator);
        ignoreDustThresholdListener = (observable, oldValue, newValue) -> {
            try {
                int value = Integer.parseInt(newValue);
                checkArgument(value >= Restrictions.getMinNonDustOutput().value,
                        "Input must be at least " + Restrictions.getMinNonDustOutput().value);
                checkArgument(value <= 2000,
                        "Input must not be higher than 2000 Satoshis");
                if (!newValue.equals(oldValue)) {
                    preferences.setIgnoreDustThreshold(value);
                }
            } catch (Throwable ignore) {
            }
        };

        if (displayStandbyModeFeature) {
            // AvoidStandbyModeService feature works only on OSX & Windows
            avoidStandbyMode = addSlideToggleButton(root, ++gridRow,
                    Res.get("setting.preferences.avoidStandbyMode"));
        }

        useSoundForNotifications = addSlideToggleButton(root, ++gridRow,
                Res.get("setting.preferences.useSoundForNotifications"), Layout.GROUP_DISTANCE * -1); // TODO: why must negative value be used to place toggle consistently?
    }

    private void initializeSeparator() {
        final Separator separator = new Separator(Orientation.VERTICAL);
        separator.setPadding(new Insets(0, 10, 0, 10));
        GridPane.setColumnIndex(separator, 1);
        GridPane.setHalignment(separator, HPos.CENTER);
        GridPane.setRowIndex(separator, 0);
        GridPane.setRowSpan(separator, GridPane.REMAINING);
        root.getChildren().add(separator);
    }

    private void initializeDisplayCurrencies() {

        TitledGroupBg titledGroupBg = addTitledGroupBg(root, displayCurrenciesGridRowIndex, 8,
                Res.get("setting.preferences.currenciesInList"), hideXmrAutoConf ? 0.0 :Layout.GROUP_DISTANCE);
        GridPane.setColumnIndex(titledGroupBg, 2);
        GridPane.setColumnSpan(titledGroupBg, 2);

        preferredTradeCurrencyComboBox = addComboBox(root, displayCurrenciesGridRowIndex++,
                Res.get("setting.preferences.prefCurrency"),
                Layout.FIRST_ROW_DISTANCE);
        GridPane.setColumnIndex(preferredTradeCurrencyComboBox, 2);

        preferredTradeCurrencyComboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(TradeCurrency object) {
                return object.getCode() + " - " + object.getName();
            }

            @Override
            public TradeCurrency fromString(String string) {
                return null;
            }
        });

        preferredTradeCurrencyComboBox.setButtonCell(GUIUtil.getTradeCurrencyButtonCell("", "",
                FXCollections.emptyObservableMap()));
        preferredTradeCurrencyComboBox.setCellFactory(GUIUtil.getTradeCurrencyCellFactory("", "",
                FXCollections.emptyObservableMap()));

        Tuple3<Label, ListView<TraditionalCurrency>, VBox> traditionalTuple = addTopLabelListView(root, displayCurrenciesGridRowIndex,
                Res.get("setting.preferences.displayTraditional"));

        int listRowSpan = 6;
        GridPane.setColumnIndex(traditionalTuple.third, 2);
        GridPane.setRowSpan(traditionalTuple.third, listRowSpan);

        GridPane.setValignment(traditionalTuple.third, VPos.TOP);
        GridPane.setMargin(traditionalTuple.third, new Insets(10, 0, 0, 0));
        traditionalCurrenciesListView = traditionalTuple.second;
        traditionalCurrenciesListView.setMinHeight(9 * Layout.LIST_ROW_HEIGHT + 2);
        traditionalCurrenciesListView.setPrefHeight(10 * Layout.LIST_ROW_HEIGHT + 2);
        Label placeholder = new AutoTooltipLabel(Res.get("setting.preferences.noTraditional"));
        placeholder.setWrapText(true);
        traditionalCurrenciesListView.setPlaceholder(placeholder);
        traditionalCurrenciesListView.setCellFactory(new Callback<>() {
            @Override
            public ListCell<TraditionalCurrency> call(ListView<TraditionalCurrency> list) {
                return new ListCell<>() {
                    final Label label = new AutoTooltipLabel();
                    final ImageView icon = ImageUtil.getImageViewById(ImageUtil.REMOVE_ICON);
                    final Button removeButton = new AutoTooltipButton("", icon);
                    final AnchorPane pane = new AnchorPane(label, removeButton);

                    {
                        label.setLayoutY(5);
                        removeButton.setId("icon-button");
                        AnchorPane.setRightAnchor(removeButton, -30d);
                    }

                    @Override
                    public void updateItem(final TraditionalCurrency item, boolean empty) {
                        super.updateItem(item, empty);
                        if (item != null && !empty) {
                            label.setText(item.getNameAndCode());
                            removeButton.setOnAction(e -> {
                                if (item.equals(preferences.getPreferredTradeCurrency())) {
                                    new Popup().warning(Res.get("setting.preferences.cannotRemovePrefCurrency")).show();
                                } else {
                                    preferences.removeTraditionalCurrency(item);
                                    if (!allTraditionalCurrencies.contains(item)) {
                                        allTraditionalCurrencies.add(item);
                                        allTraditionalCurrencies.sort(TradeCurrency::compareTo);
                                    }
                                }
                            });
                            setGraphic(pane);
                        } else {
                            setGraphic(null);
                            removeButton.setOnAction(null);
                        }
                    }
                };
            }
        });

        Tuple3<Label, ListView<CryptoCurrency>, VBox> cryptoCurrenciesTuple = addTopLabelListView(root,
                displayCurrenciesGridRowIndex, Res.get("setting.preferences.displayCryptos"));

        GridPane.setColumnIndex(cryptoCurrenciesTuple.third, 3);
        GridPane.setRowSpan(cryptoCurrenciesTuple.third, listRowSpan);

        GridPane.setValignment(cryptoCurrenciesTuple.third, VPos.TOP);
        GridPane.setMargin(cryptoCurrenciesTuple.third, new Insets(0, 0, 0, 20));
        cryptoCurrenciesListView = cryptoCurrenciesTuple.second;
        cryptoCurrenciesListView.setMinHeight(9 * Layout.LIST_ROW_HEIGHT + 2);
        cryptoCurrenciesListView.setPrefHeight(10 * Layout.LIST_ROW_HEIGHT + 2);
        placeholder = new AutoTooltipLabel(Res.get("setting.preferences.noCryptos"));
        placeholder.setWrapText(true);
        cryptoCurrenciesListView.setPlaceholder(placeholder);
        cryptoCurrenciesListView.setCellFactory(new Callback<>() {
            @Override
            public ListCell<CryptoCurrency> call(ListView<CryptoCurrency> list) {
                return new ListCell<>() {
                    final Label label = new AutoTooltipLabel();
                    final ImageView icon = ImageUtil.getImageViewById(ImageUtil.REMOVE_ICON);
                    final Button removeButton = new AutoTooltipButton("", icon);
                    final AnchorPane pane = new AnchorPane(label, removeButton);

                    {
                        label.setLayoutY(5);
                        removeButton.setId("icon-button");
                        AnchorPane.setRightAnchor(removeButton, -30d);
                    }

                    @Override
                    public void updateItem(final CryptoCurrency item, boolean empty) {
                        super.updateItem(item, empty);
                        if (item != null && !empty) {
                            label.setText(item.getNameAndCode());
                            removeButton.setOnAction(e -> {
                                if (item.equals(preferences.getPreferredTradeCurrency())) {
                                    new Popup().warning(Res.get("setting.preferences.cannotRemovePrefCurrency")).show();
                                } else {
                                    preferences.removeCryptoCurrency(item);
                                    if (!allCryptoCurrencies.contains(item)) {
                                        allCryptoCurrencies.add(item);
                                        allCryptoCurrencies.sort(TradeCurrency::compareTo);
                                    }
                                }
                            });
                            setGraphic(pane);
                        } else {
                            setGraphic(null);
                            removeButton.setOnAction(null);
                        }
                    }
                };
            }
        });

        traditionalCurrenciesComboBox = addComboBox(root, displayCurrenciesGridRowIndex + listRowSpan);
        GridPane.setColumnIndex(traditionalCurrenciesComboBox, 2);
        GridPane.setValignment(traditionalCurrenciesComboBox, VPos.TOP);
        traditionalCurrenciesComboBox.setPromptText(Res.get("setting.preferences.addTraditional"));
        traditionalCurrenciesComboBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(final TraditionalCurrency item, boolean empty) {
                super.updateItem(item, empty);
                this.setVisible(item != null || !empty);

                if (empty || item == null) {
                    setText(Res.get("setting.preferences.addTraditional"));
                } else {
                    setText(item.getNameAndCode());
                }
            }
        });
        traditionalCurrenciesComboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(TraditionalCurrency tradeCurrency) {
                return tradeCurrency.getNameAndCode();
            }

            @Override
            public TraditionalCurrency fromString(String s) {
                return null;
            }
        });

        cryptoCurrenciesComboBox = addComboBox(root, displayCurrenciesGridRowIndex + listRowSpan);
        GridPane.setColumnIndex(cryptoCurrenciesComboBox, 3);
        GridPane.setValignment(cryptoCurrenciesComboBox, VPos.TOP);
        GridPane.setMargin(cryptoCurrenciesComboBox, new Insets(Layout.FLOATING_LABEL_DISTANCE,
                0, 0, 20));
        cryptoCurrenciesComboBox.setPromptText(Res.get("setting.preferences.addCrypto"));
        cryptoCurrenciesComboBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(final CryptoCurrency item, boolean empty) {
                super.updateItem(item, empty);
                this.setVisible(item != null || !empty);


                if (empty || item == null) {
                    setText(Res.get("setting.preferences.addCrypto"));
                } else {
                    setText(item.getNameAndCode());
                }
            }
        });
        cryptoCurrenciesComboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(CryptoCurrency tradeCurrency) {
                return tradeCurrency.getNameAndCode();
            }

            @Override
            public CryptoCurrency fromString(String s) {
                return null;
            }
        });

        displayCurrenciesGridRowIndex += listRowSpan;
    }

    private void initializeDisplayOptions() {
        TitledGroupBg titledGroupBg = addTitledGroupBg(root, ++gridRow, 7, Res.get("setting.preferences.displayOptions"), Layout.GROUP_DISTANCE);
        GridPane.setColumnSpan(titledGroupBg, 1);

        showOwnOffersInOfferBook = addSlideToggleButton(root, gridRow, Res.get("setting.preferences.showOwnOffers"), Layout.FIRST_ROW_AND_GROUP_DISTANCE);
        useAnimations = addSlideToggleButton(root, ++gridRow, Res.get("setting.preferences.useAnimations"));
        useDarkMode = addSlideToggleButton(root, ++gridRow, Res.get("setting.preferences.useDarkMode"));
        sortMarketCurrenciesNumerically = addSlideToggleButton(root, ++gridRow, Res.get("setting.preferences.sortWithNumOffers"));
        hideNonAccountPaymentMethodsToggle = addSlideToggleButton(root, ++gridRow, Res.get("setting.preferences.onlyShowPaymentMethodsFromAccount"));
        //denyApiTakerToggle = addSlideToggleButton(root, ++gridRow, Res.get("setting.preferences.denyApiTaker")); // TODO: re-enable?
        //notifyOnPreReleaseToggle = addSlideToggleButton(root, ++gridRow, Res.get("setting.preferences.notifyOnPreRelease"));
        resetDontShowAgainButton = addButton(root, ++gridRow, Res.get("setting.preferences.resetAllFlags"), 0);
        resetDontShowAgainButton.getStyleClass().add("compact-button");
        resetDontShowAgainButton.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(resetDontShowAgainButton, Priority.ALWAYS);
        GridPane.setColumnIndex(resetDontShowAgainButton, 0);
    }

    private void initializeAutoConfirmOptions() {
        GridPane autoConfirmGridPane = new GridPane();
        GridPane.setHgrow(autoConfirmGridPane, Priority.ALWAYS);
        if (!hideXmrAutoConf) root.add(autoConfirmGridPane, 2, displayCurrenciesGridRowIndex, 2, 10);
        addTitledGroupBg(autoConfirmGridPane, 0, 4, Res.get("setting.preferences.autoConfirmXMR"), 0);
        int localRowIndex = 0;
        autoConfirmXmrToggle = addSlideToggleButton(autoConfirmGridPane, localRowIndex, Res.get("setting.preferences.autoConfirmEnabled"), Layout.FIRST_ROW_DISTANCE);

        autoConfRequiredConfirmationsTf = addInputTextField(autoConfirmGridPane, ++localRowIndex, Res.get("setting.preferences.autoConfirmRequiredConfirmations"));
        autoConfRequiredConfirmationsTf.setValidator(new IntegerValidator(1, DevEnv.isDevMode() ? 100000000 : 1000));

        autoConfTradeLimitTf = addInputTextField(autoConfirmGridPane, ++localRowIndex, Res.get("setting.preferences.autoConfirmMaxTradeSize"));
        autoConfTradeLimitTf.setValidator(new XmrValidator());

        autoConfServiceAddressTf = addInputTextField(autoConfirmGridPane, ++localRowIndex, Res.get("setting.preferences.autoConfirmServiceAddresses"));
        GridPane.setHgrow(autoConfServiceAddressTf, Priority.ALWAYS);
        if (!hideXmrAutoConf) displayCurrenciesGridRowIndex += 4;

        autoConfServiceAddressListener = (observable, oldValue, newValue) -> {
            if (!newValue.equals(oldValue)) {

                RegexValidator onionRegex = RegexValidatorFactory.onionAddressRegexValidator();
                RegexValidator localhostRegex = RegexValidatorFactory.localhostAddressRegexValidator();
                RegexValidator localnetRegex = RegexValidatorFactory.localnetAddressRegexValidator();

                List<String> serviceAddressesRaw = Arrays.asList(StringUtils.deleteWhitespace(newValue).split(","));

                // revert to default service providers when user empties the list
                if (serviceAddressesRaw.size() == 1 && serviceAddressesRaw.get(0).isEmpty()) {
                    serviceAddressesRaw = preferences.getDefaultXmrTxProofServices();
                }

                // we must always communicate with XMR explorer API securely
                // if *.onion hostname, we use Tor normally
                // if localhost, LAN address, or *.local FQDN we use HTTP without Tor
                // otherwise we enforce https:// for any clearnet FQDN hostname
                List<String> serviceAddressesParsed = new ArrayList<String>();
                serviceAddressesRaw.forEach((addr) -> {
                    addr = addr.replaceAll("http://", "").replaceAll("https://", "");
                    if (onionRegex.validate(addr).isValid) {
                        log.info("Using Tor for onion hostname: {}", addr);
                        serviceAddressesParsed.add(addr);
                    } else if (localhostRegex.validate(addr).isValid) {
                        log.info("Using HTTP without Tor for Loopback address: {}", addr);
                        serviceAddressesParsed.add("http://" + addr);
                    } else if (localnetRegex.validate(addr).isValid) {
                        log.info("Using HTTP without Tor for LAN address: {}", addr);
                        serviceAddressesParsed.add("http://" + addr);
                    } else {
                        log.info("Using HTTPS with Tor for Clearnet address: {}", addr);
                        serviceAddressesParsed.add("https://" + addr);
                    }
                });

                preferences.setAutoConfServiceAddresses("XMR", serviceAddressesParsed);
            }
        };

        autoConfTradeLimitListener = (observable, oldValue, newValue) -> {
            if (!newValue.equals(oldValue) && autoConfTradeLimitTf.getValidator().validate(newValue).isValid) {
                BigInteger amount = HavenoUtils.parseXmr(newValue);
                preferences.setAutoConfTradeLimit("XMR", amount.longValueExact());
            }
        };

        autoConfServiceAddressFocusOutListener = (observable, oldValue, newValue) -> {
            if (oldValue && !newValue) {
                log.info("Service address focus out, check and re-display default option");
                if (autoConfServiceAddressTf.getText().isEmpty()) {
                    preferences.findAutoConfirmSettings("XMR").ifPresent(autoConfirmSettings -> {
                        List<String> serviceAddresses = autoConfirmSettings.getServiceAddresses();
                        autoConfServiceAddressTf.setText(String.join(", ", serviceAddresses));
                    });
                }
            }
        };

        // We use a focus out handler to not update the data during entering text as that might lead to lower than
        // intended numbers which could be lead in the worst case to auto completion as number of confirmations is
        // reached. E.g. user had value 10 and wants to change it to 15 and deletes the 0, so current value would be 1.
        // If the service result just comes in at that moment the service might be considered complete as 1 is at that
        // moment used. We read the data just in time to make changes more flexible, otherwise user would need to
        // restart to apply changes from the number of confirmations settings.
        // Other fields like service addresses and limits are not affected and are taken at service start and cannot be
        // changed for already started services.
        autoConfRequiredConfirmationsFocusOutListener = (observable, oldValue, newValue) -> {
            if (oldValue && !newValue) {
                String txt = autoConfRequiredConfirmationsTf.getText();
                if (autoConfRequiredConfirmationsTf.getValidator().validate(txt).isValid) {
                    int requiredConfirmations = Integer.parseInt(txt);
                    preferences.setAutoConfRequiredConfirmations("XMR", requiredConfirmations);
                } else {
                    preferences.findAutoConfirmSettings("XMR")
                            .ifPresent(e -> autoConfRequiredConfirmationsTf
                                    .setText(String.valueOf(e.getRequiredConfirmations())));
                }
            }
        };

        filterChangeListener = (observable, oldValue, newValue) -> {
            autoConfirmGridPane.setDisable(newValue != null && newValue.isDisableAutoConf());
        };
        autoConfirmGridPane.setDisable(filterManager.getFilter() != null && filterManager.getFilter().isDisableAutoConf());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Activate
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void activateGeneralOptions() {
        ignoreTradersListInputTextField.setText(String.join(", ", preferences.getIgnoreTradersList()));
        /* referralIdService.getOptionalReferralId().ifPresent(referralId -> referralIdInputTextField.setText(referralId));
        referralIdInputTextField.setPromptText(Res.get("setting.preferences.refererId.prompt"));*/
        ignoreDustThresholdInputTextField.setText(String.valueOf(preferences.getIgnoreDustThreshold()));
        userLanguageComboBox.setItems(languageCodes);
        userLanguageComboBox.getSelectionModel().select(preferences.getUserLanguage());
        userLanguageComboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(String code) {
                return LanguageUtil.getDisplayName(code);
            }

            @Override
            public String fromString(String string) {
                return null;
            }
        });

        userLanguageComboBox.setOnAction(e -> {
            String selectedItem = userLanguageComboBox.getSelectionModel().getSelectedItem();
            if (selectedItem != null) {
                preferences.setUserLanguage(selectedItem);
                new Popup().information(Res.get("settings.preferences.languageChange"))
                        .closeButtonText(Res.get("shared.ok"))
                        .show();

                if (model.needsSupportLanguageWarning()) {
                    new Popup().warning(Res.get("settings.preferences.supportLanguageWarning",
                            model.getArbitrationLanguages()))
                            .closeButtonText(Res.get("shared.ok"))
                            .show();
                }
            }
        });

        userCountryComboBox.setItems(countries);
        userCountryComboBox.getSelectionModel().select(preferences.getUserCountry());
        userCountryComboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(Country country) {
                return CountryUtil.getNameByCode(country.code);
            }

            @Override
            public Country fromString(String string) {
                return null;
            }
        });
        userCountryComboBox.setOnAction(e -> {
            Country country = userCountryComboBox.getSelectionModel().getSelectedItem();
            if (country != null) {
                preferences.setUserCountry(country);
            }
        });

        xmrExplorerTextField.setText(preferences.getBlockChainExplorer().name);

        deviationInputTextField.setText(FormattingUtils.formatToPercentWithSymbol(preferences.getMaxPriceDistanceInPercent()));
        deviationInputTextField.textProperty().addListener(deviationListener);
        deviationInputTextField.focusedProperty().addListener(deviationFocusedListener);

        ignoreTradersListInputTextField.textProperty().addListener(ignoreTradersListListener);
        //referralIdInputTextField.textProperty().addListener(referralIdListener);
        ignoreDustThresholdInputTextField.textProperty().addListener(ignoreDustThresholdListener);
    }

    private void activateDisplayCurrencies() {
        preferredTradeCurrencyComboBox.setItems(tradeCurrencies);
        preferredTradeCurrencyComboBox.getSelectionModel().select(preferences.getPreferredTradeCurrency());
        preferredTradeCurrencyComboBox.setVisibleRowCount(12);
        preferredTradeCurrencyComboBox.setOnAction(e -> {
            TradeCurrency selectedItem = preferredTradeCurrencyComboBox.getSelectionModel().getSelectedItem();
            if (selectedItem != null)
                preferences.setPreferredTradeCurrency(selectedItem);
        });

        traditionalCurrenciesComboBox.setItems(allTraditionalCurrencies);
        traditionalCurrenciesListView.setItems(traditionalCurrencies);
        traditionalCurrenciesComboBox.setOnHiding(e -> {
            TraditionalCurrency selectedItem = traditionalCurrenciesComboBox.getSelectionModel().getSelectedItem();
            if (selectedItem != null) {
                preferences.addTraditionalCurrency(selectedItem);
                if (allTraditionalCurrencies.contains(selectedItem)) {
                    UserThread.execute(() -> {
                        traditionalCurrenciesComboBox.getSelectionModel().clearSelection();
                        allTraditionalCurrencies.remove(selectedItem);

                    });
                }
            }
        });
        cryptoCurrenciesComboBox.setItems(allCryptoCurrencies);
        cryptoCurrenciesListView.setItems(cryptoCurrencies);
        cryptoCurrenciesComboBox.setOnHiding(e -> {
            CryptoCurrency selectedItem = cryptoCurrenciesComboBox.getSelectionModel().getSelectedItem();
            if (selectedItem != null) {
                preferences.addCryptoCurrency(selectedItem);
                if (allCryptoCurrencies.contains(selectedItem)) {
                    UserThread.execute(() -> {
                        cryptoCurrenciesComboBox.getSelectionModel().clearSelection();
                        allCryptoCurrencies.remove(selectedItem);

                    });
                }
            }
        });
    }

    private void activateDisplayPreferences() {
        showOwnOffersInOfferBook.setSelected(preferences.isShowOwnOffersInOfferBook());
        showOwnOffersInOfferBook.setOnAction(e -> preferences.setShowOwnOffersInOfferBook(showOwnOffersInOfferBook.isSelected()));

        useAnimations.setSelected(preferences.isUseAnimations());
        useAnimations.setOnAction(e -> preferences.setUseAnimations(useAnimations.isSelected()));

        useDarkMode.setSelected(preferences.getCssTheme() == 1);
        useDarkMode.setOnAction(e -> preferences.setCssTheme(useDarkMode.isSelected()));

        sortMarketCurrenciesNumerically.setSelected(preferences.isSortMarketCurrenciesNumerically());
        sortMarketCurrenciesNumerically.setOnAction(e -> preferences.setSortMarketCurrenciesNumerically(sortMarketCurrenciesNumerically.isSelected()));

        boolean disableToggle = false;
        if (user.getPaymentAccounts() != null) {
            Set<PaymentMethod> supportedPaymentMethods = user.getPaymentAccounts().stream()
                    .map(PaymentAccount::getPaymentMethod).collect(Collectors.toSet());
            disableToggle = supportedPaymentMethods.isEmpty();
        }
        hideNonAccountPaymentMethodsToggle.setSelected(preferences.isHideNonAccountPaymentMethods() && !disableToggle);
        hideNonAccountPaymentMethodsToggle.setOnAction(e -> preferences.setHideNonAccountPaymentMethods(hideNonAccountPaymentMethodsToggle.isSelected()));
        hideNonAccountPaymentMethodsToggle.setDisable(disableToggle);

        //denyApiTakerToggle.setSelected(preferences.isDenyApiTaker());
        //denyApiTakerToggle.setOnAction(e -> preferences.setDenyApiTaker(denyApiTakerToggle.isSelected()));

        //notifyOnPreReleaseToggle.setSelected(preferences.isNotifyOnPreRelease());
        //notifyOnPreReleaseToggle.setOnAction(e -> preferences.setNotifyOnPreRelease(notifyOnPreReleaseToggle.isSelected()));

        resetDontShowAgainButton.setOnAction(e -> preferences.resetDontShowAgain());

        editCustomBtcExplorer.setOnAction(e -> {
            EditCustomExplorerWindow urlWindow = new EditCustomExplorerWindow("XMR",
                    preferences.getBlockChainExplorer(), preferences.getBlockChainExplorers());
            urlWindow
                    .actionButtonText(Res.get("shared.save"))
                    .onAction(() -> {
                        preferences.setBlockChainExplorer(urlWindow.getEditedBlockChainExplorer());
                        xmrExplorerTextField.setText(preferences.getBlockChainExplorer().name);
                    })
                    .closeButtonText(Res.get("shared.cancel"))
                    .onClose(urlWindow::hide)
                    .show();
        });

        // We use opposite property (useStandbyMode) in preferences to have the default value (false) set as we want it,
        // so users who update gets set avoidStandbyMode=true (useStandbyMode=false)
        if (displayStandbyModeFeature) {
            avoidStandbyMode.setSelected(!preferences.isUseStandbyMode());
            avoidStandbyMode.setOnAction(e -> preferences.setUseStandbyMode(!avoidStandbyMode.isSelected()));
        } else {
            preferences.setUseStandbyMode(false);
        }

        useSoundForNotifications.setSelected(preferences.isUseSoundForNotifications());
        useSoundForNotifications.setOnAction(e -> preferences.setUseSoundForNotifications(useSoundForNotifications.isSelected()));
    }

    private void activateAutoConfirmPreferences() {
        preferences.findAutoConfirmSettings("XMR").ifPresent(autoConfirmSettings -> {
            autoConfirmXmrToggle.setSelected(autoConfirmSettings.isEnabled());
            autoConfRequiredConfirmationsTf.setText(String.valueOf(autoConfirmSettings.getRequiredConfirmations()));
            autoConfTradeLimitTf.setText(HavenoUtils.formatXmr(autoConfirmSettings.getTradeLimit()));
            autoConfServiceAddressTf.setText(String.join(", ", autoConfirmSettings.getServiceAddresses()));
            autoConfRequiredConfirmationsTf.focusedProperty().addListener(autoConfRequiredConfirmationsFocusOutListener);
            autoConfTradeLimitTf.textProperty().addListener(autoConfTradeLimitListener);
            autoConfServiceAddressTf.textProperty().addListener(autoConfServiceAddressListener);
            autoConfServiceAddressTf.focusedProperty().addListener(autoConfServiceAddressFocusOutListener);
            autoConfirmXmrToggle.setOnAction(e -> {
                preferences.setAutoConfEnabled(autoConfirmSettings.getCurrencyCode(), autoConfirmXmrToggle.isSelected());
            });
            filterManager.filterProperty().addListener(filterChangeListener);
        });
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Deactivate
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void deactivateGeneralOptions() {
        //selectBaseCurrencyNetworkComboBox.setOnAction(null);
        userLanguageComboBox.setOnAction(null);
        userCountryComboBox.setOnAction(null);
        editCustomBtcExplorer.setOnAction(null);
        deviationInputTextField.textProperty().removeListener(deviationListener);
        deviationInputTextField.focusedProperty().removeListener(deviationFocusedListener);
        ignoreTradersListInputTextField.textProperty().removeListener(ignoreTradersListListener);
        //referralIdInputTextField.textProperty().removeListener(referralIdListener);
        ignoreDustThresholdInputTextField.textProperty().removeListener(ignoreDustThresholdListener);
    }

    private void deactivateDisplayCurrencies() {
        preferredTradeCurrencyComboBox.setOnAction(null);
    }

    private void deactivateDisplayPreferences() {
        useAnimations.setOnAction(null);
        useDarkMode.setOnAction(null);
        sortMarketCurrenciesNumerically.setOnAction(null);
        hideNonAccountPaymentMethodsToggle.setOnAction(null);
        //denyApiTakerToggle.setOnAction(null);
        //notifyOnPreReleaseToggle.setOnAction(null);
        showOwnOffersInOfferBook.setOnAction(null);
        resetDontShowAgainButton.setOnAction(null);
        if (displayStandbyModeFeature) {
            avoidStandbyMode.setOnAction(null);
        }
    }

    private void deactivateAutoConfirmPreferences() {
        preferences.findAutoConfirmSettings("XMR").ifPresent(autoConfirmSettings -> {
            autoConfirmXmrToggle.setOnAction(null);
            autoConfTradeLimitTf.textProperty().removeListener(autoConfTradeLimitListener);
            autoConfServiceAddressTf.textProperty().removeListener(autoConfServiceAddressListener);
            autoConfServiceAddressTf.focusedProperty().removeListener(autoConfServiceAddressFocusOutListener);
            autoConfRequiredConfirmationsTf.focusedProperty().removeListener(autoConfRequiredConfirmationsFocusOutListener);
            filterManager.filterProperty().removeListener(filterChangeListener);
        });
    }
}
