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

package haveno.desktop.main.account.content.cryptoaccounts;

import haveno.asset.CryptoAccountDisclaimer;
import haveno.asset.Asset;
import haveno.asset.coins.Monero;
import haveno.common.util.Tuple2;
import haveno.common.util.Tuple3;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.filter.FilterManager;
import haveno.core.locale.CryptoCurrency;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.PaymentAccountFactory;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.validation.CryptoAddressValidator;
import haveno.core.user.Preferences;
import haveno.core.util.FormattingUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.components.TitledGroupBg;
import haveno.desktop.components.paymentmethods.AssetsForm;
import haveno.desktop.components.paymentmethods.PaymentMethodForm;
import haveno.desktop.main.account.content.PaymentAccountsView;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.util.FormBuilder;
import haveno.desktop.util.Layout;
import javafx.collections.ObservableList;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.Optional;

import static haveno.desktop.components.paymentmethods.AssetsForm.INSTANT_TRADE_NEWS;
import static haveno.desktop.util.FormBuilder.add2ButtonsAfterGroup;
import static haveno.desktop.util.FormBuilder.add3ButtonsAfterGroup;
import static haveno.desktop.util.FormBuilder.addTitledGroupBg;
import static haveno.desktop.util.FormBuilder.addTopLabelListView;

@FxmlView
public class CryptoAccountsView extends PaymentAccountsView<GridPane, CryptoAccountsViewModel> {

    private final InputValidator inputValidator;
    private final CryptoAddressValidator altCoinAddressValidator;
    private final FilterManager filterManager;
    private final CoinFormatter formatter;
    private final Preferences preferences;

    private PaymentMethodForm paymentMethodForm;
    private TitledGroupBg accountTitledGroupBg;
    private Button saveNewAccountButton;
    private int gridRow = 0;

    @Inject
    public CryptoAccountsView(CryptoAccountsViewModel model,
                               InputValidator inputValidator,
                               CryptoAddressValidator altCoinAddressValidator,
                               AccountAgeWitnessService accountAgeWitnessService,
                               FilterManager filterManager,
                               @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter formatter,
                               Preferences preferences) {
        super(model, accountAgeWitnessService);

        this.inputValidator = inputValidator;
        this.altCoinAddressValidator = altCoinAddressValidator;
        this.filterManager = filterManager;
        this.formatter = formatter;
        this.preferences = preferences;
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
        TradeCurrency selectedTradeCurrency = paymentAccount.getSelectedTradeCurrency();
        if (selectedTradeCurrency != null) {
            if (selectedTradeCurrency instanceof CryptoCurrency && ((CryptoCurrency) selectedTradeCurrency).isAsset()) {
                String name = selectedTradeCurrency.getName();
                new Popup().information(Res.get("account.crypto.popup.wallet.msg",
                        selectedTradeCurrency.getCodeAndName(),
                        name,
                        name))
                        .closeButtonText(Res.get("account.crypto.popup.wallet.confirm"))
                        .show();
            }

            final Optional<Asset> asset = CurrencyUtil.findAsset(selectedTradeCurrency.getCode());
            if (asset.isPresent()) {
                final CryptoAccountDisclaimer disclaimerAnnotation = asset.get().getClass().getAnnotation(CryptoAccountDisclaimer.class);
                if (disclaimerAnnotation != null) {
                    new Popup()
                            .width(asset.get() instanceof Monero ? 1000 : 669)
                            .maxMessageLength(2500)
                            .information(Res.get(disclaimerAnnotation.value()))
                            .useIUnderstandButton()
                            .show();
                }
            }

            if (model.getPaymentAccounts().stream().noneMatch(e -> e.getAccountName() != null &&
                    e.getAccountName().equals(paymentAccount.getAccountName()))) {
                model.onSaveNewAccount(paymentAccount);
                removeNewAccountForm();
            } else {
                new Popup().warning(Res.get("shared.accountNameAlreadyUsed")).show();
            }

            preferences.dontShowAgain(INSTANT_TRADE_NEWS, true);
        }
    }

    private void onCancelNewAccount() {
        removeNewAccountForm();

        preferences.dontShowAgain(INSTANT_TRADE_NEWS, true);
    }

    private void onUpdateAccount(PaymentAccount paymentAccount) {
        model.onUpdateAccount(paymentAccount);
        removeSelectAccountForm();
    }

    private void onCancelSelectedAccount(PaymentAccount paymentAccount) {
        paymentAccount.revertChanges();
        removeSelectAccountForm();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Base form
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected void buildForm() {
        addTitledGroupBg(root, gridRow, 2, Res.get("shared.manageAccounts"));

        Tuple3<Label, ListView<PaymentAccount>, VBox> tuple = addTopLabelListView(root, gridRow, Res.get("account.crypto.yourCryptoAccounts"), Layout.FIRST_ROW_DISTANCE);
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
    protected void addNewAccount() {
        paymentAccountsListView.getSelectionModel().clearSelection();
        removeAccountRows();
        addAccountButton.setDisable(true);
        accountTitledGroupBg = addTitledGroupBg(root, ++gridRow, 1, Res.get("shared.createNewAccount"), Layout.GROUP_DISTANCE);

        if (paymentMethodForm != null) {
            FormBuilder.removeRowsFromGridPane(root, 3, paymentMethodForm.getGridRow() + 1);
            GridPane.setRowSpan(accountTitledGroupBg, paymentMethodForm.getRowSpan() + 1);
        }
        gridRow = 2;
        paymentMethodForm = getPaymentMethodForm(PaymentMethod.BLOCK_CHAINS);
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

    // Select account form
    protected void onSelectAccount(PaymentAccount previous, PaymentAccount current) {
        if (previous != null) {
            previous.revertChanges();
        }
        removeAccountRows();
        addAccountButton.setDisable(false);
        accountTitledGroupBg = addTitledGroupBg(root, ++gridRow, 2, Res.get("shared.selectedAccount"), Layout.GROUP_DISTANCE);
        paymentMethodForm = getPaymentMethodForm(current);
        paymentMethodForm.addFormForEditAccount();
        gridRow = paymentMethodForm.getGridRow();
        Tuple3<Button, Button, Button> tuple = add3ButtonsAfterGroup(
                root,
                ++gridRow,
                Res.get("shared.save"),
                Res.get("shared.deleteAccount"),
                Res.get("shared.cancel")
        );

        Button saveAccountButton = tuple.first;
        saveAccountButton.setOnAction(event -> onUpdateAccount(current));
        Button deleteAccountButton = tuple.second;
        deleteAccountButton.setOnAction(event -> onDeleteAccount(current));
        Button cancelButton = tuple.third;
        cancelButton.setOnAction(event -> onCancelSelectedAccount(current));
        GridPane.setRowSpan(accountTitledGroupBg, paymentMethodForm.getRowSpan());
        model.onSelectAccount(current);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Utils
    ///////////////////////////////////////////////////////////////////////////////////////////

    private PaymentMethodForm getPaymentMethodForm(PaymentMethod paymentMethod) {
        PaymentAccount paymentAccount = PaymentAccountFactory.getPaymentAccount(paymentMethod);
        paymentAccount.init();
        return getPaymentMethodForm(paymentAccount);
    }

    private PaymentMethodForm getPaymentMethodForm(PaymentAccount paymentAccount) {
        return new AssetsForm(paymentAccount, accountAgeWitnessService, altCoinAddressValidator,
                inputValidator, root, gridRow, formatter, filterManager);
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

    @Override
    protected boolean deleteAccountFromModel(PaymentAccount paymentAccount) {
        return model.onDeleteAccount(paymentAccount);
    }

    private void removeAccountRows() {
        FormBuilder.removeRowsFromGridPane(root, 2, gridRow);
        gridRow = 1;
    }
}
