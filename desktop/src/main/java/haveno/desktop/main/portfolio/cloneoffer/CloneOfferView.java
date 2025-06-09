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

package haveno.desktop.main.portfolio.cloneoffer;

import haveno.desktop.Navigation;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.BusyAnimation;
import haveno.desktop.main.offer.MutableOfferView;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.overlays.windows.OfferDetailsWindow;

import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.offer.OpenOffer;
import haveno.core.payment.PaymentAccount;
import haveno.core.user.DontShowAgainLookup;
import haveno.core.user.Preferences;
import haveno.core.util.FormattingUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.common.UserThread;
import haveno.common.util.Tuple4;

import com.google.inject.Inject;
import com.google.inject.name.Named;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;

import javafx.collections.ObservableList;

import static haveno.desktop.util.FormBuilder.addButtonBusyAnimationLabelAfterGroup;

@FxmlView
public class CloneOfferView extends MutableOfferView<CloneOfferViewModel> {

    private BusyAnimation busyAnimation;
    private Button cloneButton;
    private Button cancelButton;
    private Label spinnerInfoLabel;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    private CloneOfferView(CloneOfferViewModel model,
                        Navigation navigation,
                        Preferences preferences,
                        OfferDetailsWindow offerDetailsWindow,
                        @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter btcFormatter) {
        super(model, navigation, preferences, offerDetailsWindow, btcFormatter);
    }

    @Override
    protected void initialize() {
        super.initialize();

        addCloneGroup();
        renameAmountGroup();
    }

    private void renameAmountGroup() {
        amountTitledGroupBg.setText(Res.get("editOffer.setPrice"));
    }

    @Override
    protected void doSetFocus() {
        // Don't focus in any field before data was set
    }

    @Override
    protected void doActivate() {
        super.doActivate();


        addBindings();

        hideOptionsGroup();
        hideNextButtons();

        // Lock amount field as it would require bigger changes to support increased amount values.
        amountTextField.setDisable(true);
        amountBtcLabel.setDisable(true);
        minAmountTextField.setDisable(true);
        minAmountBtcLabel.setDisable(true);
        volumeTextField.setDisable(true);
        volumeCurrencyLabel.setDisable(true);

        // Workaround to fix margin on top of amount group
        gridPane.setPadding(new Insets(-20, 25, -1, 25));

        updatePriceToggle();
        updateElementsWithDirection();

        model.isNextButtonDisabled.setValue(false);
        cancelButton.setDisable(false);

        model.onInvalidateMarketPriceMargin();
        model.onInvalidatePrice();

        // To force re-validation of payment account validation
        onPaymentAccountsComboBoxSelected();
    }

    @Override
    protected void deactivate() {
        super.deactivate();

        removeBindings();
    }

    @Override
    public void onClose() {
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void applyOpenOffer(OpenOffer openOffer) {
        model.applyOpenOffer(openOffer);

        initWithData(openOffer.getOffer().getDirection(),
                CurrencyUtil.getTradeCurrency(openOffer.getOffer().getCurrencyCode()).get(),
                false,
                null);

        if (!model.isSecurityDepositValid()) {
            new Popup().warning(Res.get("editOffer.invalidDeposit"))
                    .onClose(this::close)
                    .show();
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Bindings, Listeners
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void addBindings() {
        cloneButton.disableProperty().bind(model.isNextButtonDisabled);
    }

    private void removeBindings() {
        cloneButton.disableProperty().unbind();
    }

    @Override
    protected ObservableList<PaymentAccount> filterPaymentAccounts(ObservableList<PaymentAccount> paymentAccounts) {
        return paymentAccounts;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Build UI elements
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void addCloneGroup() {
        Tuple4<Button, BusyAnimation, Label, HBox> tuple4 = addButtonBusyAnimationLabelAfterGroup(gridPane, 6, Res.get("cloneOffer.clone"));

        HBox hBox = tuple4.fourth;
        hBox.setAlignment(Pos.CENTER_LEFT);
        GridPane.setHalignment(hBox, HPos.LEFT);

        cloneButton = tuple4.first;
        cloneButton.setMinHeight(40);
        cloneButton.setPadding(new Insets(0, 20, 0, 20));
        cloneButton.setGraphicTextGap(10);

        busyAnimation = tuple4.second;
        spinnerInfoLabel = tuple4.third;

        cancelButton = new AutoTooltipButton(Res.get("shared.cancel"));
        cancelButton.setDefaultButton(false);
        cancelButton.setOnAction(event -> close());
        hBox.getChildren().add(cancelButton);

        cloneButton.setOnAction(e -> {
            cloneButton.requestFocus();   // fix issue #5460 (when enter key used, focus is wrong)
            onClone();
        });
    }

    private void onClone() {
        if (model.dataModel.hasConflictingClone()) {
            new Popup().warning(Res.get("cloneOffer.hasConflictingClone"))
                    .actionButtonText(Res.get("shared.yes"))
                    .onAction(this::doClone)
                    .closeButtonText(Res.get("shared.no"))
                    .show();
        } else {
            doClone();
        }
    }

    private void doClone() {
        if (model.isPriceInRange()) {
            model.isNextButtonDisabled.setValue(true);
            cancelButton.setDisable(true);
            busyAnimation.play();
            spinnerInfoLabel.setText(Res.get("cloneOffer.publishOffer"));
            model.onCloneOffer(() -> {
                UserThread.execute(() -> {
                    String key = "cloneOfferSuccess";
                    if (DontShowAgainLookup.showAgain(key)) {
                        new Popup()
                                .feedback(Res.get("cloneOffer.success"))
                                .dontShowAgainId(key)
                                .show();
                    }
                    spinnerInfoLabel.setText("");
                    busyAnimation.stop();
                    close();
                });
            },
            errorMessage -> {
                UserThread.execute(() -> {
                    log.error(errorMessage);
                    spinnerInfoLabel.setText("");
                    busyAnimation.stop();
                    model.isNextButtonDisabled.setValue(false);
                    cancelButton.setDisable(false);
                    new Popup().warning(errorMessage).show();
                });
            });
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Utils
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void updateElementsWithDirection() {
        ImageView iconView = new ImageView();
        iconView.setId(model.isShownAsSellOffer() ? "image-sell-white" : "image-buy-white");
        cloneButton.setGraphic(iconView);
        cloneButton.setId(model.isShownAsSellOffer() ? "sell-button-big" : "buy-button-big");
    }
}