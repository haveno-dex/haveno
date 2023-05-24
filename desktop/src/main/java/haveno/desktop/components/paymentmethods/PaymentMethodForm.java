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

package haveno.desktop.components.paymentmethods;

import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.common.util.Tuple3;
import haveno.common.util.Utilities;
import haveno.core.account.witness.AccountAgeWitness;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.payment.AssetAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.trade.HavenoUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import haveno.desktop.components.AutoTooltipCheckBox;
import haveno.desktop.components.InfoTextField;
import haveno.desktop.components.InputTextField;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.util.DisplayUtils;
import haveno.desktop.util.FormBuilder;
import haveno.desktop.util.GUIUtil;
import haveno.desktop.util.Layout;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Date;
import java.util.concurrent.TimeUnit;

import static haveno.desktop.util.DisplayUtils.createAccountName;
import static haveno.desktop.util.FormBuilder.addCompactTopLabelInfoTextField;
import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextFieldWithCopyIcon;
import static haveno.desktop.util.FormBuilder.addInputTextField;
import static haveno.desktop.util.FormBuilder.addTopLabelInfoTextField;
import static haveno.desktop.util.FormBuilder.addTopLabelInputTextFieldSlideToggleButton;
import static haveno.desktop.util.FormBuilder.addTopLabelTextField;

@Slf4j
public abstract class PaymentMethodForm {
    protected final PaymentAccount paymentAccount;
    private final AccountAgeWitnessService accountAgeWitnessService;
    protected final InputValidator inputValidator;
    protected final GridPane gridPane;
    protected int gridRow;
    private final CoinFormatter formatter;
    protected final BooleanProperty allInputsValid = new SimpleBooleanProperty();

    protected int gridRowFrom;
    InputTextField accountNameTextField;
    protected TextField paymentLimitationsTextField;
    ToggleButton useCustomAccountNameToggleButton;
    protected ComboBox<TradeCurrency> currencyComboBox;

    public PaymentMethodForm(PaymentAccount paymentAccount, AccountAgeWitnessService accountAgeWitnessService,
                             InputValidator inputValidator, GridPane gridPane, int gridRow, CoinFormatter formatter) {
        this.paymentAccount = paymentAccount;
        this.accountAgeWitnessService = accountAgeWitnessService;
        this.inputValidator = inputValidator;
        this.gridPane = gridPane;
        this.gridRow = gridRow;
        this.formatter = formatter;
    }

    protected void addTradeCurrencyComboBox() {
        currencyComboBox = FormBuilder.addComboBox(gridPane, ++gridRow, Res.get("shared.currency"));
        currencyComboBox.setPromptText(Res.get("list.currency.select"));
        currencyComboBox.setItems(FXCollections.observableArrayList(CurrencyUtil.getMainTraditionalCurrencies()));
        currencyComboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(TradeCurrency tradeCurrency) {
                return tradeCurrency.getNameAndCode();
            }

            @Override
            public TradeCurrency fromString(String s) {
                return null;
            }
        });
        currencyComboBox.setOnAction(e -> {
            paymentAccount.setSingleTradeCurrency(currencyComboBox.getSelectionModel().getSelectedItem());
            updateFromInputs();
        });
    }

    protected void addAccountNameTextFieldWithAutoFillToggleButton() {
        boolean isEditMode = paymentAccount.getPersistedAccountName() != null;
        Tuple3<Label, InputTextField, ToggleButton> tuple = addTopLabelInputTextFieldSlideToggleButton(gridPane, ++gridRow,
                Res.get("payment.account.name"), Res.get("payment.useCustomAccountName"));
        accountNameTextField = tuple.second;
        accountNameTextField.setPrefWidth(300);
        accountNameTextField.setEditable(isEditMode);
        accountNameTextField.setValidator(inputValidator);
        accountNameTextField.setFocusTraversable(false);
        accountNameTextField.setText(paymentAccount.getAccountName());
        accountNameTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            paymentAccount.setAccountName(newValue);
            updateAllInputsValid();
        });
        useCustomAccountNameToggleButton = tuple.third;
        useCustomAccountNameToggleButton.setSelected(isEditMode);
        useCustomAccountNameToggleButton.setOnAction(e -> {
            boolean selected = useCustomAccountNameToggleButton.isSelected();
            accountNameTextField.setEditable(selected);
            accountNameTextField.setFocusTraversable(selected);
            autoFillNameTextField();
        });
    }

    public static InfoTextField addOpenTradeDuration(GridPane gridPane,
                                                     int gridRow,
                                                     Offer offer) {
        long hours = offer.getPaymentMethod().getMaxTradePeriod() / 3600_000;
        final Tuple3<Label, InfoTextField, VBox> labelInfoTextFieldVBoxTuple3 =
                addTopLabelInfoTextField(gridPane, gridRow, Res.get("payment.maxPeriod"),
                        getTimeText(hours), -Layout.FLOATING_LABEL_DISTANCE);
        return labelInfoTextFieldVBoxTuple3.second;
    }

    private static String getTimeText(long hours) {
        String time = hours + " " + Res.get("time.hours");
        if (hours == 1)
            time = Res.get("time.1hour");
        else if (hours == 24)
            time = Res.get("time.1day");
        else if (hours > 24)
            time = hours / 24 + " " + Res.get("time.days");

        return time;
    }

    protected String getLimitationsText() {
        final PaymentAccount paymentAccount = getPaymentAccount();
        long hours = paymentAccount.getMaxTradePeriod() / 3600_000;
        final TradeCurrency tradeCurrency;
        if (paymentAccount.getSingleTradeCurrency() != null)
            tradeCurrency = paymentAccount.getSingleTradeCurrency();
        else if (paymentAccount.getSelectedTradeCurrency() != null)
            tradeCurrency = paymentAccount.getSelectedTradeCurrency();
        else if (!paymentAccount.getTradeCurrencies().isEmpty() && paymentAccount.getTradeCurrencies().get(0) != null)
            tradeCurrency = paymentAccount.getTradeCurrencies().get(0);
        else
            tradeCurrency = paymentAccount instanceof AssetAccount ?
                    CurrencyUtil.getAllSortedCryptoCurrencies().iterator().next() :
                    CurrencyUtil.getDefaultTradeCurrency();
        final boolean isAddAccountScreen = paymentAccount.getAccountName() == null;
        final long accountAge = !isAddAccountScreen ? accountAgeWitnessService.getMyAccountAge(paymentAccount.getPaymentAccountPayload()) : 0L;

        final String limitationsText = paymentAccount instanceof AssetAccount ?
                Res.get("payment.maxPeriodAndLimitCrypto",
                        getTimeText(hours),
                        HavenoUtils.formatXmr(accountAgeWitnessService.getMyTradeLimit(
                            paymentAccount, tradeCurrency.getCode(), OfferDirection.BUY), true))
                :
                Res.get("payment.maxPeriodAndLimit",
                        getTimeText(hours),
                        HavenoUtils.formatXmr(accountAgeWitnessService.getMyTradeLimit(
                                paymentAccount, tradeCurrency.getCode(), OfferDirection.BUY), true),
                        HavenoUtils.formatXmr(accountAgeWitnessService.getMyTradeLimit(
                                paymentAccount, tradeCurrency.getCode(), OfferDirection.SELL), true),
                        DisplayUtils.formatAccountAge(accountAge));
        return limitationsText;
    }

    protected void addLimitations(boolean isDisplayForm) {
        final boolean isAddAccountScreen = paymentAccount.getAccountName() == null;

        if (isDisplayForm) {
            addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.limitations"), getLimitationsText());

            String accountSigningStateText;
            MaterialDesignIcon icon;

            boolean needsSigning = PaymentMethod.hasChargebackRisk(paymentAccount.getPaymentMethod(),
                    paymentAccount.getTradeCurrencies());

            if (needsSigning) {

                AccountAgeWitness myWitness = accountAgeWitnessService.getMyWitness(
                        paymentAccount.paymentAccountPayload);
                AccountAgeWitnessService.SignState signState =
                        accountAgeWitnessService.getSignState(myWitness);

                accountSigningStateText = StringUtils.capitalize(signState.getDisplayString());

                long daysSinceSigning = TimeUnit.MILLISECONDS.toDays(
                        accountAgeWitnessService.getWitnessSignAge(myWitness, new Date()));
                String timeSinceSigning = Res.get("offerbook.timeSinceSigning.daysSinceSigning.long",
                        Res.get("offerbook.timeSinceSigning.daysSinceSigning",
                                daysSinceSigning));

                if (!signState.equals(AccountAgeWitnessService.SignState.UNSIGNED)) {
                    accountSigningStateText += " / " + timeSinceSigning;
                }

                icon = GUIUtil.getIconForSignState(signState);

                InfoTextField accountSigningField = addCompactTopLabelInfoTextField(gridPane, ++gridRow, Res.get("shared.accountSigningState"),
                        accountSigningStateText).second;
                //TODO: add additional information regarding account signing
                accountSigningField.setContent(icon, accountSigningStateText, "", 0.4);
            }

        } else {
            paymentLimitationsTextField = addTopLabelTextField(gridPane, ++gridRow, Res.get("payment.limitations"), getLimitationsText()).second;
        }

        if (!(paymentAccount instanceof AssetAccount)) {
            if (isAddAccountScreen) {
                InputTextField inputTextField = addInputTextField(gridPane, ++gridRow, Res.get("payment.salt"), 0);
                inputTextField.setText(Utilities.bytesAsHexString(paymentAccount.getPaymentAccountPayload().getSalt()));
                inputTextField.textProperty().addListener((observable, oldValue, newValue) -> {
                    if (!newValue.isEmpty()) {
                        try {
                            // test if input is hex
                            Utilities.decodeFromHex(newValue);

                            paymentAccount.setSaltAsHex(newValue);
                        } catch (Throwable t) {
                            new Popup().warning(Res.get("payment.error.noHexSalt")).show();
                            inputTextField.setText(Utilities.bytesAsHexString(paymentAccount.getPaymentAccountPayload().getSalt()));
                            log.warn(t.toString());
                        }
                    }
                });
            } else {
                addCompactTopLabelTextFieldWithCopyIcon(gridPane, ++gridRow, Res.get("payment.salt",
                        Utilities.bytesAsHexString(paymentAccount.getPaymentAccountPayload().getSalt())),
                        Utilities.bytesAsHexString(paymentAccount.getPaymentAccountPayload().getSalt()));
            }
        }
    }

    void applyTradeCurrency(TradeCurrency tradeCurrency, TraditionalCurrency defaultCurrency) {
        if (!defaultCurrency.equals(tradeCurrency)) {
            new Popup().warning(Res.get("payment.foreign.currency"))
                    .actionButtonText(Res.get("shared.yes"))
                    .onAction(() -> {
                        paymentAccount.setSingleTradeCurrency(tradeCurrency);
                        autoFillNameTextField();
                    })
                    .closeButtonText(Res.get("payment.restore.default"))
                    .onClose(() -> currencyComboBox.getSelectionModel().select(defaultCurrency))
                    .show();
        } else {
            paymentAccount.setSingleTradeCurrency(tradeCurrency);
            autoFillNameTextField();
        }
    }

    void setAccountNameWithString(String name) {
        if (useCustomAccountNameToggleButton != null && !useCustomAccountNameToggleButton.isSelected()) {
            String accountName = createAccountName(paymentAccount.getPaymentMethod().getId(), name);
            accountNameTextField.setText(accountName);
        }
    }

    void fillUpFlowPaneWithCurrencies(boolean isEditable, FlowPane flowPane,
                                      TradeCurrency e, PaymentAccount paymentAccount) {
        CheckBox checkBox = new AutoTooltipCheckBox(e.getCode());
        checkBox.setMouseTransparent(!isEditable);
        checkBox.setSelected(paymentAccount.getTradeCurrencies().contains(e));
        checkBox.setMinWidth(60);
        checkBox.setMaxWidth(checkBox.getMinWidth());
        checkBox.setTooltip(new Tooltip(e.getName()));
        checkBox.setOnAction(event -> {
            if (checkBox.isSelected())
                paymentAccount.addCurrency(e);
            else
                paymentAccount.removeCurrency(e);

            updateAllInputsValid();
        });
        flowPane.getChildren().add(checkBox);
    }

    protected abstract void autoFillNameTextField();

    public abstract void addFormForAddAccount();

    public abstract void addFormForEditAccount();

    protected abstract void updateAllInputsValid();

    public void updateFromInputs() {
        autoFillNameTextField();
        updateAllInputsValid();
    }

    public boolean isAccountNameValid() {
        return inputValidator.validate(paymentAccount.getAccountName()).isValid;
    }

    public int getGridRow() {
        return gridRow;
    }

    public int getRowSpan() {
        return gridRow - gridRowFrom + 2;
    }

    public PaymentAccount getPaymentAccount() {
        return paymentAccount;
    }

    public BooleanProperty allInputsValidProperty() {
        return allInputsValid;
    }

    void removeAcceptedCountry(String countryCode) {
    }

    void addAcceptedCountry(String countryCode) {
    }
}
