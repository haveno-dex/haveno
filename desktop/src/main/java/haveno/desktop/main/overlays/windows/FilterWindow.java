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

package haveno.desktop.main.overlays.windows;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import haveno.common.UserThread;
import haveno.common.app.DevEnv;
import haveno.common.config.Config;
import haveno.core.filter.Filter;
import haveno.core.filter.FilterManager;
import haveno.core.filter.PaymentAccountFilter;
import haveno.core.locale.Res;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.InputTextField;
import haveno.desktop.main.overlays.Overlay;
import haveno.desktop.main.overlays.popups.Popup;
import static haveno.desktop.util.FormBuilder.addInputTextField;
import static haveno.desktop.util.FormBuilder.addLabelCheckBox;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import javafx.collections.FXCollections;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import org.apache.commons.lang3.StringUtils;

public class FilterWindow extends Overlay<FilterWindow> {
    private final FilterManager filterManager;
    private final boolean useDevPrivilegeKeys;

    @Inject
    public FilterWindow(FilterManager filterManager,
                        @Named(Config.USE_DEV_PRIVILEGE_KEYS) boolean useDevPrivilegeKeys) {
        this.filterManager = filterManager;
        this.useDevPrivilegeKeys = useDevPrivilegeKeys;
        type = Type.Attention;
    }

    public void show() {
        if (headLine == null)
            headLine = Res.get("filterWindow.headline");

        width = 1000;

        createGridPane();
        gridPane.setMaxHeight(Double.MAX_VALUE); // taller than the screen; the base overlay caps it with a scroll pane
        addHeadLine();
        addContent();
        applyStyles();
        display();
    }

    @Override
    protected void setupKeyHandler(Scene scene) {
        if (!hideCloseButton) {
            scene.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ESCAPE) {
                    e.consume();
                    doClose();
                }
            });
        }
    }

    private void addContent() {
        gridPane.getColumnConstraints().remove(1);
        gridPane.getColumnConstraints().get(0).setHalignment(HPos.LEFT);

        InputTextField keyTF = addInputTextField(gridPane, ++rowIndex,
                Res.get("shared.unlock"), 10);
        if (useDevPrivilegeKeys) {
            keyTF.setText(DevEnv.DEV_PRIVILEGE_PRIV_KEY);
        }

        InputTextField offerIdsTF = addInputTextField(gridPane, ++rowIndex,
                Res.get("filterWindow.offers"));
        InputTextField bannedFromTradingTF = addInputTextField(gridPane, ++rowIndex,
                Res.get("filterWindow.onions"));
        InputTextField bannedFromNetworkTF = addInputTextField(gridPane, ++rowIndex,
                Res.get("filterWindow.bannedFromNetwork"));
        InputTextField paymentAccountFilterTF = addInputTextField(gridPane, ++rowIndex,
                Res.get("filterWindow.accounts"));
        InputTextField bannedCurrenciesTF = addInputTextField(gridPane, ++rowIndex,
                Res.get("filterWindow.bannedCurrencies"));
        InputTextField bannedPaymentMethodsTF = addInputTextField(gridPane, ++rowIndex,
                Res.get("filterWindow.bannedPaymentMethods"));
        InputTextField bannedAccountWitnessSignerPubKeysTF = addInputTextField(gridPane, ++rowIndex,
                Res.get("filterWindow.bannedAccountWitnessSignerPubKeys"));
        InputTextField arbitratorsTF = addInputTextField(gridPane, ++rowIndex,
                Res.get("filterWindow.arbitrators"));
        InputTextField mediatorsTF = addInputTextField(gridPane, ++rowIndex,
                Res.get("filterWindow.mediators"));
        InputTextField refundAgentsTF = addInputTextField(gridPane, ++rowIndex,
                Res.get("filterWindow.refundAgents"));
        InputTextField xmrFeeReceiverAddressesTF = addInputTextField(gridPane, ++rowIndex,
                Res.get("filterWindow.xmrFeeReceiverAddresses"));
        InputTextField seedNodesTF = addInputTextField(gridPane, ++rowIndex,
                Res.get("filterWindow.seedNode"));
        InputTextField priceRelayNodesTF = addInputTextField(gridPane, ++rowIndex,
                Res.get("filterWindow.priceRelayNode"));
        InputTextField xmrNodesTF = addInputTextField(gridPane, ++rowIndex,
                Res.get("filterWindow.xmrNode"));
        CheckBox preventPublicXmrNetworkCheckBox = addLabelCheckBox(gridPane, ++rowIndex,
                Res.get("filterWindow.preventPublicXmrNetwork"));
        CheckBox disableAutoConfCheckBox = addLabelCheckBox(gridPane, ++rowIndex,
                Res.get("filterWindow.disableAutoConf"));
        InputTextField disableTradeBelowVersionTF = addInputTextField(gridPane, ++rowIndex,
                Res.get("filterWindow.disableTradeBelowVersion"));
        InputTextField bannedPrivilegedDevPubKeysTF = addInputTextField(gridPane, ++rowIndex,
                Res.get("filterWindow.bannedPrivilegedDevPubKeys"));
        InputTextField autoConfExplorersTF = addInputTextField(gridPane, ++rowIndex,
                Res.get("filterWindow.autoConfExplorers"));
        CheckBox disableMempoolValidationCheckBox = addLabelCheckBox(gridPane, ++rowIndex,
                Res.get("filterWindow.disableMempoolValidation"));
        CheckBox disableApiCheckBox = addLabelCheckBox(gridPane, ++rowIndex,
                Res.get("filterWindow.disableApi"));

        Filter filter = filterManager.getDevFilter();
        if (filter != null) {
            setupFieldFromList(offerIdsTF, filter.getBannedOfferIds());
            setupFieldFromList(bannedFromTradingTF, filter.getNodeAddressesBannedFromTrading());
            setupFieldFromList(bannedFromNetworkTF, filter.getNodeAddressesBannedFromNetwork());
            setupFieldFromPaymentAccountFiltersList(paymentAccountFilterTF, filter.getBannedPaymentAccounts());
            setupFieldFromList(bannedCurrenciesTF, filter.getBannedCurrencies());
            setupFieldFromList(bannedPaymentMethodsTF, filter.getBannedPaymentMethods());
            setupFieldFromList(bannedAccountWitnessSignerPubKeysTF, filter.getBannedAccountWitnessSignerPubKeys());
            setupFieldFromList(arbitratorsTF, filter.getArbitrators());
            setupFieldFromList(mediatorsTF, filter.getMediators());
            setupFieldFromList(refundAgentsTF, filter.getRefundAgents());
            setupFieldFromList(xmrFeeReceiverAddressesTF, filter.getXmrFeeReceiverAddresses());
            setupFieldFromList(seedNodesTF, filter.getSeedNodes());
            setupFieldFromList(priceRelayNodesTF, filter.getPriceRelayNodes());
            setupFieldFromList(xmrNodesTF, filter.getXmrNodes());
            setupFieldFromList(bannedPrivilegedDevPubKeysTF, filter.getBannedPrivilegedDevPubKeys());
            setupFieldFromList(autoConfExplorersTF, filter.getBannedAutoConfExplorers());

            preventPublicXmrNetworkCheckBox.setSelected(filter.isPreventPublicXmrNetwork());
            disableAutoConfCheckBox.setSelected(filter.isDisableAutoConf());
            disableTradeBelowVersionTF.setText(filter.getDisableTradeBelowVersion());
            disableMempoolValidationCheckBox.setSelected(filter.isDisableMempoolValidation());
            disableApiCheckBox.setSelected(filter.isDisableApi());
        }

        Button removeFilterMessageButton = new AutoTooltipButton(Res.get("filterWindow.remove"));
        removeFilterMessageButton.setDisable(filterManager.getDevFilter() == null);

        Button sendButton = new AutoTooltipButton(Res.get("filterWindow.add"));
        sendButton.getStyleClass().add("action-button");
        sendButton.setOnAction(e -> {
            String privKeyString = keyTF.getText();
            if (filterManager.canAddDevFilter(privKeyString)) {
                String signerPubKeyAsHex = filterManager.getSignerPubKeyAsHex(privKeyString);
                Filter newFilter = new Filter(
                        readAsList(offerIdsTF),
                        readAsList(bannedFromTradingTF),
                        readAsPaymentAccountFiltersList(paymentAccountFilterTF),
                        readAsList(bannedCurrenciesTF),
                        readAsList(bannedPaymentMethodsTF),
                        readAsList(arbitratorsTF),
                        readAsList(seedNodesTF),
                        readAsList(priceRelayNodesTF),
                        preventPublicXmrNetworkCheckBox.isSelected(),
                        readAsList(xmrNodesTF),
                        disableTradeBelowVersionTF.getText(),
                        readAsList(mediatorsTF),
                        readAsList(refundAgentsTF),
                        readAsList(bannedAccountWitnessSignerPubKeysTF),
                        readAsList(xmrFeeReceiverAddressesTF),
                        filterManager.getOwnerPubKey(),
                        signerPubKeyAsHex,
                        readAsList(bannedPrivilegedDevPubKeysTF),
                        disableAutoConfCheckBox.isSelected(),
                        readAsList(autoConfExplorersTF),
                        new HashSet<>(readAsList(bannedFromNetworkTF)),
                        disableMempoolValidationCheckBox.isSelected(),
                        disableApiCheckBox.isSelected()
                );

                // We remove first the old filter
                // We delay a bit with adding as it seems that the instant add/remove calls lead to issues that the
                // remove msg was rejected (P2P storage should handle it but seems there are edge cases where its not
                // working as expected)
                if (filterManager.canRemoveDevFilter(privKeyString)) {
                    filterManager.removeDevFilter(privKeyString);
                    UserThread.runAfter(() -> addDevFilter(removeFilterMessageButton, privKeyString, newFilter),
                            5);
                } else {
                    addDevFilter(removeFilterMessageButton, privKeyString, newFilter);
                }
            } else {
                new Popup().warning(Res.get("shared.invalidKey")).onClose(this::blurAgain).show();
            }
        });

        removeFilterMessageButton.setOnAction(e -> {
            String privKeyString = keyTF.getText();
            if (filterManager.canRemoveDevFilter(privKeyString)) {
                filterManager.removeDevFilter(privKeyString);
                hide();
            } else {
                new Popup().warning(Res.get("shared.invalidKey")).onClose(this::blurAgain).show();
            }
        });

        closeButton = new AutoTooltipButton(Res.get("shared.close"));
        closeButton.setOnAction(e -> {
            hide();
            closeHandlerOptional.ifPresent(Runnable::run);
        });

        HBox hBox = new HBox();
        hBox.setSpacing(10);
        GridPane.setRowIndex(hBox, ++rowIndex);
        hBox.getChildren().addAll(sendButton, removeFilterMessageButton, closeButton);
        gridPane.getChildren().add(hBox);
        GridPane.setMargin(hBox, new Insets(10, 0, 0, 0));
    }

    private void addDevFilter(Button removeFilterMessageButton, String privKeyString, Filter newFilter) {
        filterManager.addDevFilter(newFilter, privKeyString);
        removeFilterMessageButton.setDisable(filterManager.getDevFilter() == null);
        hide();
    }

    private void setupFieldFromList(InputTextField field, Collection<String> values) {
        if (values != null)
            field.setText(String.join(", ", values));
    }

    private void setupFieldFromPaymentAccountFiltersList(InputTextField field, List<PaymentAccountFilter> values) {
        if (values != null) {
            StringBuilder sb = new StringBuilder();
            values.forEach(e -> {
                if (e != null && e.getPaymentMethodId() != null) {
                    sb
                            .append(e.getPaymentMethodId())
                            .append("|")
                            .append(e.getGetMethodName())
                            .append("|")
                            .append(e.getValue())
                            .append(", ");
                }
            });
            field.setText(sb.toString());
        }
    }

    private List<String> readAsList(InputTextField field) {
        if (field.getText().isEmpty()) {
            return FXCollections.emptyObservableList();
        } else {
            return Arrays.asList(StringUtils.deleteWhitespace(field.getText()).split(","));
        }
    }

    private List<PaymentAccountFilter> readAsPaymentAccountFiltersList(InputTextField field) {
        return readAsList(field)
                .stream().map(item -> {
                    String[] list = item.split("\\|");
                    if (list.length == 3)
                        return new PaymentAccountFilter(list[0], list[1], list[2]);
                    else
                        return new PaymentAccountFilter("", "", "");
                })
                .collect(Collectors.toList());
    }
}
