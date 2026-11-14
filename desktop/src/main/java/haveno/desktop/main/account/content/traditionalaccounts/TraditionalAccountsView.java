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

package haveno.desktop.main.account.content.traditionalaccounts;

import com.google.inject.Inject;
import haveno.common.util.Tuple2;
import haveno.common.util.Tuple3;
import haveno.common.util.Utilities;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.Country;
import haveno.core.locale.CountryUtil;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.PaymentAccountFactory;
import haveno.core.payment.payload.PaymentMethod;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.components.AutocompleteComboBox;
import haveno.desktop.components.TitledGroupBg;
import haveno.desktop.components.paymentmethods.PaymentAccountForms;
import haveno.desktop.components.paymentmethods.PaymentMethodForm;
import haveno.desktop.main.account.content.PaymentAccountsView;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.util.FormBuilder;
import static haveno.desktop.util.FormBuilder.add2ButtonsAfterGroup;
import static haveno.desktop.util.FormBuilder.add3ButtonsAfterGroup;
import static haveno.desktop.util.FormBuilder.addTitledGroupBg;
import static haveno.desktop.util.FormBuilder.addLabel;
import static haveno.desktop.util.FormBuilder.addTopLabelListView;
import haveno.desktop.util.Layout;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;

@FxmlView
public class TraditionalAccountsView extends PaymentAccountsView<GridPane, TraditionalAccountsViewModel> {

    private final PaymentAccountForms paymentAccountForms;
    private AutocompleteComboBox<PaymentMethod> paymentMethodComboBox;
    private PaymentMethodForm paymentMethodForm;
    private TitledGroupBg accountTitledGroupBg;
    private Button saveNewAccountButton;
    private int gridRow = 0;

    @Inject
    public TraditionalAccountsView(TraditionalAccountsViewModel model,
                            PaymentAccountForms paymentAccountForms,
                            AccountAgeWitnessService accountAgeWitnessService) {
        super(model, accountAgeWitnessService);

        this.paymentAccountForms = paymentAccountForms;
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
        paymentAccountForms.confirmNewAccount(paymentAccount, () -> doSaveNewAccount(paymentAccount));
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

    private void onUpdateAccount(PaymentAccount paymentAccount) {
        model.onUpdateAccount(paymentAccount);
        removeSelectAccountForm();
    }

    private void onCancelSelectedAccount(PaymentAccount paymentAccount) {
        paymentAccount.revertChanges();
        removeSelectAccountForm();
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

        Tuple3<Label, ListView<PaymentAccount>, VBox> tuple = addTopLabelListView(root, gridRow, Res.get("account.traditional.yourTraditionalAccounts"), Layout.FIRST_ROW_DISTANCE);
        paymentAccountsListView = tuple.second;
        setPaymentAccountsListHeight();
        setPaymentAccountsCellFactory();

        Tuple3<Button, Button, Button> tuple3 = add3ButtonsAfterGroup(root, ++gridRow, Res.get("shared.addNewAccount"),
                Res.get("shared.ExportAccounts"), Res.get("shared.importAccounts"));
        addAccountButton = tuple3.first;
        addAccountButton.setId("buy-button-big");
        exportButton = tuple3.second;
        importButton = tuple3.third;
    }

    // Add new account form
    @Override
    protected void addNewAccount() {
        paymentAccountsListView.getSelectionModel().clearSelection();
        removeAccountRows();
        addAccountButton.setDisable(true);
        accountTitledGroupBg = addTitledGroupBg(root, ++gridRow, 2, Res.get("shared.createNewAccount"), Layout.COMPACT_GROUP_DISTANCE);
        addLabel(root, gridRow, Res.get("shared.createNewAccountDescription"));
        paymentMethodComboBox = FormBuilder.addAutocompleteComboBox(
            root, gridRow, Res.get("shared.selectPaymentMethod"), Layout.TWICE_FIRST_ROW_AND_COMPACT_GROUP_DISTANCE + Layout.PADDING
        );
        paymentMethodComboBox.setVisibleRowCount(Math.min(paymentMethodComboBox.getItems().size(), 10));
        paymentMethodComboBox.setPrefWidth(250);
        List<PaymentMethod> list = PaymentMethod.paymentMethods.stream()
                .filter(PaymentMethod::isTraditional)
                .sorted()
                .collect(Collectors.toList());
        paymentMethodComboBox.setAutocompleteItems(FXCollections.observableArrayList(list));
        paymentMethodComboBox.setQueryFilter(getPaymentMethodQueryFilter(list));
        paymentMethodComboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(PaymentMethod paymentMethod) {
                return paymentMethod != null ? Res.get(paymentMethod.getId()) : "";
            }

            @Override
            public PaymentMethod fromString(String s) {
                if (s.isEmpty())
                    return null;

                return paymentMethodComboBox.getItems().stream()
                                            .filter(item -> Res.get(item.getId()).equals(s))
                                            .findAny().orElse(null);
            }
        });
        paymentMethodComboBox.setOnChangeConfirmed(e -> {
            if (paymentMethodComboBox.getEditor().getText().isEmpty())
                return;
            if (paymentMethodForm != null) {
                FormBuilder.removeRowsFromGridPane(root, 3, paymentMethodForm.getGridRow() + 1);
                GridPane.setRowSpan(accountTitledGroupBg, paymentMethodForm.getRowSpan() + 1);
            }
            gridRow = 2;
            paymentMethodForm = getPaymentMethodForm(paymentMethodComboBox.getSelectionModel().getSelectedItem());
            if (paymentMethodForm != null) {
                if (paymentMethodForm.getPaymentAccount().getMessageForAccountCreation() != null) {
                    new Popup().information(Res.get(paymentMethodForm.getPaymentAccount().getMessageForAccountCreation()))
                            .width(900)
                            .closeButtonText(Res.get("shared.iUnderstand"))
                            .show();
                }
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

    // Method names take precedence over partial country matches; exact country names and codes still match.
    private static Function<String, Predicate<PaymentMethod>> getPaymentMethodQueryFilter(List<PaymentMethod> methods) {
        Map<PaymentMethod, String> currencyTextByMethod = new HashMap<>();
        Map<PaymentMethod, Set<String>> countryCodesByMethod = new HashMap<>();
        for (PaymentMethod method : methods) {
            PaymentAccount account = PaymentAccountFactory.getPaymentAccount(method);
            currencyTextByMethod.put(method, account.getSupportedCurrencies().stream()
                    .map(TradeCurrency::getCodeAndName).collect(Collectors.joining(" ")));
            List<Country> countries = account.getSupportedCountries();
            if (countries != null) countryCodesByMethod.put(method, countries.stream().map(country -> country.code).collect(Collectors.toSet()));
        }
        return query -> {
            Predicate<PaymentMethod> matchesName = method -> StringUtils.containsIgnoreCase(Res.get(method.getId()), query);
            boolean matchesMethodName = methods.stream().anyMatch(matchesName);
            Set<String> queriedCountryCodes = CountryUtil.getAllCountries().stream()
                    .filter(country -> country.code.equalsIgnoreCase(query) || country.name.equalsIgnoreCase(query)
                            || (!matchesMethodName && countryMatches(country, query)))
                    .map(country -> country.code).collect(Collectors.toSet());
            return method -> {
                if (matchesName.test(method)) return true;
                Set<String> supported = countryCodesByMethod.get(method);
                if (!queriedCountryCodes.isEmpty()) return supported == null || supported.stream().anyMatch(queriedCountryCodes::contains);
                return StringUtils.containsIgnoreCase(currencyTextByMethod.get(method), query);
            };
        };
    }

    // A query names a country when it matches the code or leads the name or one of its words.
    private static boolean countryMatches(Country country, String query) {
        if (country.code.equalsIgnoreCase(query) || StringUtils.startsWithIgnoreCase(country.name, query)) return true;
        for (String word : country.name.split(" ")) if (StringUtils.startsWithIgnoreCase(word, query)) return true;
        return false;
    }

    // Select account form
    @Override
    protected void onSelectAccount(PaymentAccount previous, PaymentAccount current) {
        if (previous != null) {
            previous.revertChanges();
        }
        removeAccountRows();
        addAccountButton.setDisable(false);
        accountTitledGroupBg = addTitledGroupBg(root, ++gridRow, 2, "", Layout.COMPACT_GROUP_DISTANCE);
        paymentMethodForm = getPaymentMethodForm(current);
        if (paymentMethodForm != null) {
            paymentMethodForm.addFormForEditAccount();
            gridRow = paymentMethodForm.getGridRow();
            Tuple3<Button, Button, Button> tuple = add3ButtonsAfterGroup(
                    root,
                    ++gridRow,
                    Res.get("shared.save"),
                    Res.get("shared.deleteAccount"),
                    Res.get("shared.cancel")
            );
            Button updateButton = tuple.first;
            updateButton.setOnAction(event -> onUpdateAccount(paymentMethodForm.getPaymentAccount()));
            Button deleteAccountButton = tuple.second;
            deleteAccountButton.setOnAction(event -> onDeleteAccount(paymentMethodForm.getPaymentAccount()));
            Button cancelButton = tuple.third;
            cancelButton.setOnAction(event -> onCancelSelectedAccount(paymentMethodForm.getPaymentAccount()));
            GridPane.setRowSpan(accountTitledGroupBg, paymentMethodForm.getRowSpan());
            model.onSelectAccount(current);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Utils
    ///////////////////////////////////////////////////////////////////////////////////////////

    private PaymentMethodForm getPaymentMethodForm(PaymentAccount paymentAccount) {
        return paymentAccountForms.getForm(paymentAccount, root, gridRow);
    }

    private PaymentMethodForm getPaymentMethodForm(PaymentMethod paymentMethod) {
        if (paymentMethod == null) return null;
        final PaymentAccount paymentAccount = PaymentAccountFactory.getPaymentAccount(paymentMethod);
        paymentAccount.init();
        return getPaymentMethodForm(paymentAccount);
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

    @Override
    protected void deactivate() {
        super.deactivate();
        var selectedAccount = paymentAccountsListView.getSelectionModel().getSelectedItem();
        if (selectedAccount != null) {
            onCancelSelectedAccount(selectedAccount);
        }
    }

}
