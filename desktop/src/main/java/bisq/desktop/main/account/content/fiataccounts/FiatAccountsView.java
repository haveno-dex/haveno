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

package haveno.desktop.main.account.content.fiataccounts;

import haveno.desktop.common.view.FxmlView;
import haveno.desktop.components.TitledGroupBg;
import haveno.desktop.components.paymentmethods.AdvancedCashForm;
import haveno.desktop.components.paymentmethods.AliPayForm;
import haveno.desktop.components.paymentmethods.AmazonGiftCardForm;
import haveno.desktop.components.paymentmethods.AustraliaPayidForm;
import haveno.desktop.components.paymentmethods.CashByMailForm;
import haveno.desktop.components.paymentmethods.CashDepositForm;
import haveno.desktop.components.paymentmethods.ChaseQuickPayForm;
import haveno.desktop.components.paymentmethods.ClearXchangeForm;
import haveno.desktop.components.paymentmethods.F2FForm;
import haveno.desktop.components.paymentmethods.FasterPaymentsForm;
import haveno.desktop.components.paymentmethods.HalCashForm;
import haveno.desktop.components.paymentmethods.InteracETransferForm;
import haveno.desktop.components.paymentmethods.JapanBankTransferForm;
import haveno.desktop.components.paymentmethods.MoneyBeamForm;
import haveno.desktop.components.paymentmethods.MoneyGramForm;
import haveno.desktop.components.paymentmethods.NationalBankForm;
import haveno.desktop.components.paymentmethods.PaymentMethodForm;
import haveno.desktop.components.paymentmethods.PerfectMoneyForm;
import haveno.desktop.components.paymentmethods.PopmoneyForm;
import haveno.desktop.components.paymentmethods.PromptPayForm;
import haveno.desktop.components.paymentmethods.RevolutForm;
import haveno.desktop.components.paymentmethods.SameBankForm;
import haveno.desktop.components.paymentmethods.SepaForm;
import haveno.desktop.components.paymentmethods.SepaInstantForm;
import haveno.desktop.components.paymentmethods.SpecificBankForm;
import haveno.desktop.components.paymentmethods.SwishForm;
import haveno.desktop.components.paymentmethods.TransferwiseForm;
import haveno.desktop.components.paymentmethods.USPostalMoneyOrderForm;
import haveno.desktop.components.paymentmethods.UpholdForm;
import haveno.desktop.components.paymentmethods.WeChatPayForm;
import haveno.desktop.components.paymentmethods.WesternUnionForm;
import haveno.desktop.main.account.content.PaymentAccountsView;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.util.FormBuilder;
import haveno.desktop.util.GUIUtil;
import haveno.desktop.util.Layout;
import haveno.desktop.util.validation.AdvancedCashValidator;
import haveno.desktop.util.validation.AliPayValidator;
import haveno.desktop.util.validation.AustraliaPayidValidator;
import haveno.desktop.util.validation.BICValidator;
import haveno.desktop.util.validation.ChaseQuickPayValidator;
import haveno.desktop.util.validation.ClearXchangeValidator;
import haveno.desktop.util.validation.F2FValidator;
import haveno.desktop.util.validation.HalCashValidator;
import haveno.desktop.util.validation.IBANValidator;
import haveno.desktop.util.validation.InteracETransferValidator;
import haveno.desktop.util.validation.JapanBankTransferValidator;
import haveno.desktop.util.validation.MoneyBeamValidator;
import haveno.desktop.util.validation.PerfectMoneyValidator;
import haveno.desktop.util.validation.PopmoneyValidator;
import haveno.desktop.util.validation.PromptPayValidator;
import haveno.desktop.util.validation.RevolutValidator;
import haveno.desktop.util.validation.SwishValidator;
import haveno.desktop.util.validation.TransferwiseValidator;
import haveno.desktop.util.validation.USPostalMoneyOrderValidator;
import haveno.desktop.util.validation.UpholdValidator;
import haveno.desktop.util.validation.WeChatPayValidator;
import haveno.desktop.util.validation.LengthValidator;

import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.Res;
import haveno.core.offer.OfferRestrictions;
import haveno.core.payment.AmazonGiftCardAccount;
import haveno.core.payment.AustraliaPayid;
import haveno.core.payment.CashByMailAccount;
import haveno.core.payment.CashDepositAccount;
import haveno.core.payment.ClearXchangeAccount;
import haveno.core.payment.F2FAccount;
import haveno.core.payment.HalCashAccount;
import haveno.core.payment.MoneyGramAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.PaymentAccountFactory;
import haveno.core.payment.RevolutAccount;
import haveno.core.payment.USPostalMoneyOrderAccount;
import haveno.core.payment.WesternUnionAccount;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.util.FormattingUtils;
import haveno.core.util.coin.CoinFormatter;

import haveno.common.config.Config;
import haveno.common.util.Tuple2;
import haveno.common.util.Tuple3;
import haveno.common.util.Utilities;

import org.bitcoinj.core.Coin;

import javax.inject.Inject;
import javax.inject.Named;

import javafx.stage.Stage;

import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import javafx.util.StringConverter;

import java.util.List;
import java.util.stream.Collectors;

import static haveno.desktop.util.FormBuilder.add2ButtonsAfterGroup;
import static haveno.desktop.util.FormBuilder.add3ButtonsAfterGroup;
import static haveno.desktop.util.FormBuilder.addTitledGroupBg;
import static haveno.desktop.util.FormBuilder.addTopLabelListView;

@FxmlView
public class FiatAccountsView extends PaymentAccountsView<GridPane, FiatAccountsViewModel> {

    private final IBANValidator ibanValidator;
    private final BICValidator bicValidator;
    private final LengthValidator inputValidator;
    private final UpholdValidator upholdValidator;
    private final MoneyBeamValidator moneyBeamValidator;
    private final PopmoneyValidator popmoneyValidator;
    private final RevolutValidator revolutValidator;
    private final AliPayValidator aliPayValidator;
    private final PerfectMoneyValidator perfectMoneyValidator;
    private final SwishValidator swishValidator;
    private final ClearXchangeValidator clearXchangeValidator;
    private final ChaseQuickPayValidator chaseQuickPayValidator;
    private final InteracETransferValidator interacETransferValidator;
    private final JapanBankTransferValidator japanBankTransferValidator;
    private final AustraliaPayidValidator australiapayidValidator;
    private final USPostalMoneyOrderValidator usPostalMoneyOrderValidator;
    private final WeChatPayValidator weChatPayValidator;
    private final HalCashValidator halCashValidator;
    private final F2FValidator f2FValidator;
    private final PromptPayValidator promptPayValidator;
    private final AdvancedCashValidator advancedCashValidator;
    private final TransferwiseValidator transferwiseValidator;
    private final CoinFormatter formatter;
    private ComboBox<PaymentMethod> paymentMethodComboBox;
    private PaymentMethodForm paymentMethodForm;
    private TitledGroupBg accountTitledGroupBg;
    private Button saveNewAccountButton;
    private int gridRow = 0;

    @Inject
    public FiatAccountsView(FiatAccountsViewModel model,
                            IBANValidator ibanValidator,
                            BICValidator bicValidator,
                            LengthValidator inputValidator,
                            UpholdValidator upholdValidator,
                            MoneyBeamValidator moneyBeamValidator,
                            PopmoneyValidator popmoneyValidator,
                            RevolutValidator revolutValidator,
                            AliPayValidator aliPayValidator,
                            PerfectMoneyValidator perfectMoneyValidator,
                            SwishValidator swishValidator,
                            ClearXchangeValidator clearXchangeValidator,
                            ChaseQuickPayValidator chaseQuickPayValidator,
                            InteracETransferValidator interacETransferValidator,
                            JapanBankTransferValidator japanBankTransferValidator,
                            AustraliaPayidValidator australiaPayIDValidator,
                            USPostalMoneyOrderValidator usPostalMoneyOrderValidator,
                            WeChatPayValidator weChatPayValidator,
                            HalCashValidator halCashValidator,
                            F2FValidator f2FValidator,
                            PromptPayValidator promptPayValidator,
                            AdvancedCashValidator advancedCashValidator,
                            TransferwiseValidator transferwiseValidator,
                            AccountAgeWitnessService accountAgeWitnessService,
                            @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter formatter) {
        super(model, accountAgeWitnessService);

        this.ibanValidator = ibanValidator;
        this.bicValidator = bicValidator;
        this.inputValidator = inputValidator;
        this.inputValidator.setMaxLength(100); // restrict general field entry length
        this.inputValidator.setMinLength(2);
        this.upholdValidator = upholdValidator;
        this.moneyBeamValidator = moneyBeamValidator;
        this.popmoneyValidator = popmoneyValidator;
        this.revolutValidator = revolutValidator;
        this.aliPayValidator = aliPayValidator;
        this.perfectMoneyValidator = perfectMoneyValidator;
        this.swishValidator = swishValidator;
        this.clearXchangeValidator = clearXchangeValidator;
        this.chaseQuickPayValidator = chaseQuickPayValidator;
        this.interacETransferValidator = interacETransferValidator;
        this.japanBankTransferValidator = japanBankTransferValidator;
        this.australiapayidValidator = australiaPayIDValidator;
        this.usPostalMoneyOrderValidator = usPostalMoneyOrderValidator;
        this.weChatPayValidator = weChatPayValidator;
        this.halCashValidator = halCashValidator;
        this.f2FValidator = f2FValidator;
        this.promptPayValidator = promptPayValidator;
        this.advancedCashValidator = advancedCashValidator;
        this.transferwiseValidator = transferwiseValidator;
        this.formatter = formatter;
    }

    @Override
    protected ObservableList<PaymentAccount> getPaymentAccounts() {
        return model.getPaymentAccounts();
    }

    @Override
    protected void importAccounts() {
        model.dataModel.importAccounts((Stage) root.getScene().getWindow());
    }

    @Override
    protected void exportAccounts() {
        model.dataModel.exportAccounts((Stage) root.getScene().getWindow());
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // UI actions
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void onSaveNewAccount(PaymentAccount paymentAccount) {
        Coin maxTradeLimitAsCoin = paymentAccount.getPaymentMethod().getMaxTradeLimitAsCoin("USD");
        Coin maxTradeLimitSecondMonth = maxTradeLimitAsCoin.divide(2L);
        Coin maxTradeLimitFirstMonth = maxTradeLimitAsCoin.divide(4L);
        if (paymentAccount instanceof F2FAccount) {
            new Popup().information(Res.get("payment.f2f.info"))
                    .width(700)
                    .closeButtonText(Res.get("payment.f2f.info.openURL"))
                    .onClose(() -> GUIUtil.openWebPage("https://docs.haveno.network/trading-rules.html#f2f-trading"))
                    .actionButtonText(Res.get("shared.iUnderstand"))
                    .onAction(() -> doSaveNewAccount(paymentAccount))
                    .show();
        } else if (paymentAccount instanceof CashByMailAccount) {
            // CashByMail has no chargeback risk so we don't show the text from payment.limits.info.
            new Popup().information(Res.get("payment.cashByMail.info"))
                    .width(850)
                    .closeButtonText(Res.get("shared.cancel"))
                    .actionButtonText(Res.get("shared.iUnderstand"))
                    .onAction(() -> doSaveNewAccount(paymentAccount))
                    .show();
        } else if (paymentAccount instanceof HalCashAccount) {
            // HalCash has no chargeback risk so we don't show the text from payment.limits.info.
            new Popup().information(Res.get("payment.halCash.info"))
                    .width(700)
                    .closeButtonText(Res.get("shared.cancel"))
                    .actionButtonText(Res.get("shared.iUnderstand"))
                    .onAction(() -> doSaveNewAccount(paymentAccount))
                    .show();
        } else {

            String limitsInfoKey = "payment.limits.info";
            String initialLimit = formatter.formatCoinWithCode(maxTradeLimitFirstMonth);

            if (PaymentMethod.hasChargebackRisk(paymentAccount.getPaymentMethod(), paymentAccount.getTradeCurrencies())) {
                limitsInfoKey = "payment.limits.info.withSigning";
                initialLimit = formatter.formatCoinWithCode(OfferRestrictions.TOLERATED_SMALL_TRADE_AMOUNT);
            }

            new Popup().information(Res.get(limitsInfoKey,
                    initialLimit,
                    formatter.formatCoinWithCode(maxTradeLimitSecondMonth),
                    formatter.formatCoinWithCode(maxTradeLimitAsCoin)))
                    .width(700)
                    .closeButtonText(Res.get("shared.cancel"))
                    .actionButtonText(Res.get("shared.iUnderstand"))
                    .onAction(() -> {
                        final String currencyName = Config.baseCurrencyNetwork().getCurrencyName();
                        if (paymentAccount instanceof ClearXchangeAccount) {
                            new Popup().information(Res.get("payment.clearXchange.info", currencyName, currencyName))
                                    .width(900)
                                    .closeButtonText(Res.get("shared.cancel"))
                                    .actionButtonText(Res.get("shared.iConfirm"))
                                    .onAction(() -> doSaveNewAccount(paymentAccount))
                                    .show();
                        } else if (paymentAccount instanceof WesternUnionAccount) {
                            new Popup().information(Res.get("payment.westernUnion.info"))
                                    .width(700)
                                    .closeButtonText(Res.get("shared.cancel"))
                                    .actionButtonText(Res.get("shared.iUnderstand"))
                                    .onAction(() -> doSaveNewAccount(paymentAccount))
                                    .show();
                        } else if (paymentAccount instanceof MoneyGramAccount) {
                            new Popup().information(Res.get("payment.moneyGram.info"))
                                    .width(700)
                                    .closeButtonText(Res.get("shared.cancel"))
                                    .actionButtonText(Res.get("shared.iUnderstand"))
                                    .onAction(() -> doSaveNewAccount(paymentAccount))
                                    .show();
                        } else if (paymentAccount instanceof CashDepositAccount) {
                            new Popup().information(Res.get("payment.cashDeposit.info"))
                                    .width(700)
                                    .closeButtonText(Res.get("shared.cancel"))
                                    .actionButtonText(Res.get("shared.iConfirm"))
                                    .onAction(() -> doSaveNewAccount(paymentAccount))
                                    .show();
                        } else if (paymentAccount instanceof RevolutAccount) {
                            new Popup().information(Res.get("payment.revolut.info"))
                                    .width(700)
                                    .closeButtonText(Res.get("shared.cancel"))
                                    .actionButtonText(Res.get("shared.iConfirm"))
                                    .onAction(() -> doSaveNewAccount(paymentAccount))
                                    .show();
                        } else if (paymentAccount instanceof USPostalMoneyOrderAccount) {
                            new Popup().information(Res.get("payment.usPostalMoneyOrder.info"))
                                    .width(700)
                                    .closeButtonText(Res.get("shared.cancel"))
                                    .actionButtonText(Res.get("shared.iUnderstand"))
                                    .onAction(() -> doSaveNewAccount(paymentAccount))
                                    .show();
                        } else if (paymentAccount instanceof AustraliaPayid) {
                            new Popup().information(Res.get("payment.payid.info", currencyName, currencyName))
                                    .width(900)
                                    .closeButtonText(Res.get("shared.cancel"))
                                    .actionButtonText(Res.get("shared.iConfirm"))
                                    .onAction(() -> doSaveNewAccount(paymentAccount))
                                    .show();
                        } else if (paymentAccount instanceof AmazonGiftCardAccount) {
                            new Popup().information(Res.get("payment.amazonGiftCard.info", currencyName, currencyName))
                                    .width(900)
                                    .closeButtonText(Res.get("shared.cancel"))
                                    .actionButtonText(Res.get("shared.iUnderstand"))
                                    .onAction(() -> doSaveNewAccount(paymentAccount))
                                    .show();
                        } else {
                            doSaveNewAccount(paymentAccount);
                        }
                    })
                    .show();
        }
    }

    private void doSaveNewAccount(PaymentAccount paymentAccount) {
        if (getPaymentAccounts().stream().noneMatch(e -> e.getAccountName() != null &&
                e.getAccountName().equals(paymentAccount.getAccountName()))) {
            model.onSaveNewAccount(paymentAccount);
            removeNewAccountForm();
        } else {
            new Popup().warning(Res.get("shared.accountNameAlreadyUsed")).show();
        }
    }

    private void onCancelNewAccount() {
        removeNewAccountForm();
    }

    protected boolean deleteAccountFromModel(PaymentAccount paymentAccount) {
        return model.onDeleteAccount(paymentAccount);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Base form
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void buildForm() {
        addTitledGroupBg(root, gridRow, 2, Res.get("shared.manageAccounts"));

        Tuple3<Label, ListView<PaymentAccount>, VBox> tuple = addTopLabelListView(root, gridRow, Res.get("account.fiat.yourFiatAccounts"), Layout.FIRST_ROW_DISTANCE);
        paymentAccountsListView = tuple.second;
        int prefNumRows = Math.min(4, Math.max(2, model.dataModel.getNumPaymentAccounts()));
        paymentAccountsListView.setMinHeight(prefNumRows * Layout.LIST_ROW_HEIGHT + 28);
        setPaymentAccountsCellFactory();

        Tuple3<Button, Button, Button> tuple3 = add3ButtonsAfterGroup(root, ++gridRow, Res.get("shared.addNewAccount"),
                Res.get("shared.ExportAccounts"), Res.get("shared.importAccounts"));
        addAccountButton = tuple3.first;
        exportButton = tuple3.second;
        importButton = tuple3.third;
    }

    // Add new account form
    @Override
    protected void addNewAccount() {
        paymentAccountsListView.getSelectionModel().clearSelection();
        removeAccountRows();
        addAccountButton.setDisable(true);
        accountTitledGroupBg = addTitledGroupBg(root, ++gridRow, 2, Res.get("shared.createNewAccount"), Layout.GROUP_DISTANCE);
        paymentMethodComboBox = FormBuilder.addComboBox(root, gridRow, Res.get("shared.selectPaymentMethod"), Layout.FIRST_ROW_AND_GROUP_DISTANCE);
        paymentMethodComboBox.setVisibleRowCount(11);
        paymentMethodComboBox.setPrefWidth(250);
        List<PaymentMethod> list = PaymentMethod.getPaymentMethods().stream()
                .filter(paymentMethod -> !paymentMethod.isAsset())
                .sorted()
                .collect(Collectors.toList());
        paymentMethodComboBox.setItems(FXCollections.observableArrayList(list));
        paymentMethodComboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(PaymentMethod paymentMethod) {
                return paymentMethod != null ? Res.get(paymentMethod.getId()) : "";
            }

            @Override
            public PaymentMethod fromString(String s) {
                return null;
            }
        });
        paymentMethodComboBox.setOnAction(e -> {
            if (paymentMethodForm != null) {
                FormBuilder.removeRowsFromGridPane(root, 3, paymentMethodForm.getGridRow() + 1);
                GridPane.setRowSpan(accountTitledGroupBg, paymentMethodForm.getRowSpan() + 1);
            }
            gridRow = 2;
            paymentMethodForm = getPaymentMethodForm(paymentMethodComboBox.getSelectionModel().getSelectedItem());
            if (paymentMethodForm != null) {
                paymentMethodForm.addFormForAddAccount();
                gridRow = paymentMethodForm.getGridRow();
                Tuple2<Button, Button> tuple2 = add2ButtonsAfterGroup(root, ++gridRow, Res.get("shared.saveNewAccount"), Res.get("shared.cancel"));
                saveNewAccountButton = tuple2.first;
                saveNewAccountButton.setOnAction(event -> onSaveNewAccount(paymentMethodForm.getPaymentAccount()));
                saveNewAccountButton.disableProperty().bind(paymentMethodForm.allInputsValidProperty().not());
                Button cancelButton = tuple2.second;
                cancelButton.setOnAction(event -> onCancelNewAccount());
                GridPane.setRowSpan(accountTitledGroupBg, paymentMethodForm.getRowSpan() + 1);
            }
        });
    }

    // Select account form
    @Override
    protected void onSelectAccount(PaymentAccount paymentAccount) {
        removeAccountRows();
        addAccountButton.setDisable(false);
        accountTitledGroupBg = addTitledGroupBg(root, ++gridRow, 2, Res.get("shared.selectedAccount"), Layout.GROUP_DISTANCE);
        paymentMethodForm = getPaymentMethodForm(paymentAccount);
        if (paymentMethodForm != null) {
            paymentMethodForm.addFormForDisplayAccount();
            gridRow = paymentMethodForm.getGridRow();
            Tuple2<Button, Button> tuple = add2ButtonsAfterGroup(root, ++gridRow, Res.get("shared.deleteAccount"), Res.get("shared.cancel"));
            Button deleteAccountButton = tuple.first;
            deleteAccountButton.setOnAction(event -> onDeleteAccount(paymentMethodForm.getPaymentAccount()));
            Button cancelButton = tuple.second;
            cancelButton.setOnAction(event -> removeSelectAccountForm());
            GridPane.setRowSpan(accountTitledGroupBg, paymentMethodForm.getRowSpan());
            model.onSelectAccount(paymentAccount);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Utils
    ///////////////////////////////////////////////////////////////////////////////////////////

    private PaymentMethodForm getPaymentMethodForm(PaymentAccount paymentAccount) {
        return getPaymentMethodForm(paymentAccount.getPaymentMethod(), paymentAccount);
    }

    private PaymentMethodForm getPaymentMethodForm(PaymentMethod paymentMethod) {
        final PaymentAccount paymentAccount = PaymentAccountFactory.getPaymentAccount(paymentMethod);
        paymentAccount.init();
        return getPaymentMethodForm(paymentMethod, paymentAccount);
    }

    private PaymentMethodForm getPaymentMethodForm(PaymentMethod paymentMethod, PaymentAccount paymentAccount) {
        switch (paymentMethod.getId()) {
            case PaymentMethod.UPHOLD_ID:
                return new UpholdForm(paymentAccount, accountAgeWitnessService, upholdValidator, inputValidator, root, gridRow, formatter);
            case PaymentMethod.MONEY_BEAM_ID:
                return new MoneyBeamForm(paymentAccount, accountAgeWitnessService, moneyBeamValidator, inputValidator, root, gridRow, formatter);
            case PaymentMethod.POPMONEY_ID:
                return new PopmoneyForm(paymentAccount, accountAgeWitnessService, popmoneyValidator, inputValidator, root, gridRow, formatter);
            case PaymentMethod.REVOLUT_ID:
                return new RevolutForm(paymentAccount, accountAgeWitnessService, revolutValidator, inputValidator, root, gridRow, formatter);
            case PaymentMethod.PERFECT_MONEY_ID:
                return new PerfectMoneyForm(paymentAccount, accountAgeWitnessService, perfectMoneyValidator, inputValidator, root, gridRow, formatter);
            case PaymentMethod.SEPA_ID:
                return new SepaForm(paymentAccount, accountAgeWitnessService, ibanValidator, bicValidator, inputValidator, root, gridRow, formatter);
            case PaymentMethod.SEPA_INSTANT_ID:
                return new SepaInstantForm(paymentAccount, accountAgeWitnessService, ibanValidator, bicValidator, inputValidator, root, gridRow, formatter);
            case PaymentMethod.FASTER_PAYMENTS_ID:
                return new FasterPaymentsForm(paymentAccount, accountAgeWitnessService, inputValidator, root, gridRow, formatter);
            case PaymentMethod.NATIONAL_BANK_ID:
                return new NationalBankForm(paymentAccount, accountAgeWitnessService, inputValidator, root, gridRow, formatter);
            case PaymentMethod.SAME_BANK_ID:
                return new SameBankForm(paymentAccount, accountAgeWitnessService, inputValidator, root, gridRow, formatter);
            case PaymentMethod.SPECIFIC_BANKS_ID:
                return new SpecificBankForm(paymentAccount, accountAgeWitnessService, inputValidator, root, gridRow, formatter);
            case PaymentMethod.JAPAN_BANK_ID:
                return new JapanBankTransferForm(paymentAccount, accountAgeWitnessService, japanBankTransferValidator, inputValidator, root, gridRow, formatter);
            case PaymentMethod.AUSTRALIA_PAYID_ID:
                return new AustraliaPayidForm(paymentAccount, accountAgeWitnessService, australiapayidValidator, inputValidator, root, gridRow, formatter);
            case PaymentMethod.ALI_PAY_ID:
                return new AliPayForm(paymentAccount, accountAgeWitnessService, aliPayValidator, inputValidator, root, gridRow, formatter);
            case PaymentMethod.WECHAT_PAY_ID:
                return new WeChatPayForm(paymentAccount, accountAgeWitnessService, weChatPayValidator, inputValidator, root, gridRow, formatter);
            case PaymentMethod.SWISH_ID:
                return new SwishForm(paymentAccount, accountAgeWitnessService, swishValidator, inputValidator, root, gridRow, formatter);
            case PaymentMethod.CLEAR_X_CHANGE_ID:
                return new ClearXchangeForm(paymentAccount, accountAgeWitnessService, clearXchangeValidator, inputValidator, root, gridRow, formatter);
            case PaymentMethod.CHASE_QUICK_PAY_ID:
                return new ChaseQuickPayForm(paymentAccount, accountAgeWitnessService, chaseQuickPayValidator, inputValidator, root, gridRow, formatter);
            case PaymentMethod.INTERAC_E_TRANSFER_ID:
                return new InteracETransferForm(paymentAccount, accountAgeWitnessService, interacETransferValidator, inputValidator, root, gridRow, formatter);
            case PaymentMethod.US_POSTAL_MONEY_ORDER_ID:
                return new USPostalMoneyOrderForm(paymentAccount, accountAgeWitnessService, usPostalMoneyOrderValidator, inputValidator, root, gridRow, formatter);
            case PaymentMethod.MONEY_GRAM_ID:
                return new MoneyGramForm(paymentAccount, accountAgeWitnessService, inputValidator, root, gridRow, formatter);
            case PaymentMethod.WESTERN_UNION_ID:
                return new WesternUnionForm(paymentAccount, accountAgeWitnessService, inputValidator, root, gridRow, formatter);
            case PaymentMethod.CASH_DEPOSIT_ID:
                return new CashDepositForm(paymentAccount, accountAgeWitnessService, inputValidator, root, gridRow, formatter);
            case PaymentMethod.CASH_BY_MAIL_ID:
                return new CashByMailForm(paymentAccount, accountAgeWitnessService, inputValidator, root, gridRow, formatter);
            case PaymentMethod.HAL_CASH_ID:
                return new HalCashForm(paymentAccount, accountAgeWitnessService, halCashValidator, inputValidator, root, gridRow, formatter);
            case PaymentMethod.F2F_ID:
                return new F2FForm(paymentAccount, accountAgeWitnessService, f2FValidator, inputValidator, root, gridRow, formatter);
            case PaymentMethod.PROMPT_PAY_ID:
                return new PromptPayForm(paymentAccount, accountAgeWitnessService, promptPayValidator, inputValidator, root, gridRow, formatter);
            case PaymentMethod.ADVANCED_CASH_ID:
                return new AdvancedCashForm(paymentAccount, accountAgeWitnessService, advancedCashValidator, inputValidator, root, gridRow, formatter);
            case PaymentMethod.TRANSFERWISE_ID:
                return new TransferwiseForm(paymentAccount, accountAgeWitnessService, transferwiseValidator, inputValidator, root, gridRow, formatter);
            case PaymentMethod.AMAZON_GIFT_CARD_ID:
                return new AmazonGiftCardForm(paymentAccount, accountAgeWitnessService, inputValidator, root, gridRow, formatter);
            default:
                log.error("Not supported PaymentMethod: " + paymentMethod);
                return null;
        }
    }

    private void removeNewAccountForm() {
        saveNewAccountButton.disableProperty().unbind();
        removeAccountRows();
        addAccountButton.setDisable(false);
    }

    @Override
    protected void removeSelectAccountForm() {
        FormBuilder.removeRowsFromGridPane(root, 2, gridRow);
        gridRow = 1;
        addAccountButton.setDisable(false);
        paymentAccountsListView.getSelectionModel().clearSelection();
    }


    private void removeAccountRows() {
        FormBuilder.removeRowsFromGridPane(root, 2, gridRow);
        gridRow = 1;
    }

    @Override
    protected void copyAccount() {
        var selectedAccount = paymentAccountsListView.getSelectionModel().getSelectedItem();
        if (selectedAccount == null) {
            return;
        }
        Utilities.copyToClipboard(accountAgeWitnessService.getSignInfoFromAccount(selectedAccount));
    }

}

