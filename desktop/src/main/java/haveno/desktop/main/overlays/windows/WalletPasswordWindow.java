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

import static com.google.common.base.Preconditions.checkArgument;
import com.google.inject.Inject;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.crypto.IncorrectPasswordException;
import haveno.core.api.CoreAccountService;
import haveno.core.locale.Res;
import haveno.core.offer.OpenOfferManager;
import haveno.core.util.validation.RestoreHeightValidator;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.components.InputTextField;
import haveno.desktop.components.PasswordTextField;
import haveno.desktop.main.SharedPresentation;
import haveno.desktop.main.overlays.Overlay;
import haveno.desktop.main.overlays.popups.Popup;
import static haveno.desktop.util.FormBuilder.addPasswordTextField;
import static haveno.desktop.util.FormBuilder.addPrimaryActionButton;
import static haveno.desktop.util.FormBuilder.addTextArea;
import static haveno.desktop.util.FormBuilder.addTopLabelInputTextField;
import haveno.desktop.util.Layout;
import haveno.desktop.util.validation.JFXInputValidator;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import static javafx.beans.binding.Bindings.createBooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroUtils;

@Slf4j
public class WalletPasswordWindow extends Overlay<WalletPasswordWindow> {
    private final CoreAccountService accountService;
    private final XmrWalletService xmrWalletService;
    private final OpenOfferManager openOfferManager;

    private Button unlockButton;
    private WalletPasswordHandler passwordHandler;
    private PasswordTextField passwordTextField;
    private JFXInputValidator errorValidator;
    private Button forgotPasswordButton;
    private Button restoreButton;
    private TextArea seedWordsTextArea;
    private InputTextField restoreHeightInputTextField;
    private final SimpleBooleanProperty seedWordsValid = new SimpleBooleanProperty(false);
    private final BooleanProperty seedWordsEdited = new SimpleBooleanProperty();
    private ChangeListener<String> changeListener;
    private ChangeListener<String> wordsTextAreaChangeListener;
    private ChangeListener<Boolean> seedFocusListener;
    private Timer seedValidationTimer;
    // cache the last async seed check so an unchanged seed does not re-run it
    private String validatedSeed;
    private boolean validatedSeedValid;
    private boolean hideForgotPasswordButton = false;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Interface
    ///////////////////////////////////////////////////////////////////////////////////////////

    public interface WalletPasswordHandler {
        void onSuccess();
    }

    @Inject
    private WalletPasswordWindow(CoreAccountService accountService,
                                 XmrWalletService xmrWalletService,
                                 OpenOfferManager openOfferManager) {
        this.accountService = accountService;
        this.xmrWalletService = xmrWalletService;
        this.openOfferManager = openOfferManager;
        type = Type.Attention;
        width = 900;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Public API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void show() {
        if (gridPane != null) {
            rowIndex = -1;
            gridPane.getChildren().clear();
        }

        if (headLine == null)
            headLine = Res.get("walletPasswordWindow.headline");

        createGridPane();
        addHeadLine();
        addInputFields();
        addButtons();
        applyStyles();
        display();
    }

    public WalletPasswordWindow onSuccess(WalletPasswordHandler passwordHandler) {
        this.passwordHandler = passwordHandler;
        return this;
    }

    public WalletPasswordWindow hideForgotPasswordButton() {
        this.hideForgotPasswordButton = true;
        return this;
    }

    @Override
    protected void cleanup() {
        if (passwordTextField != null)
            passwordTextField.textProperty().removeListener(changeListener);

        if (seedFocusListener != null) {
            if (seedValidationTimer != null) seedValidationTimer.stop();
            seedWordsTextArea.focusedProperty().removeListener(seedFocusListener);
            seedWordsTextArea.textProperty().removeListener(wordsTextAreaChangeListener);
            restoreButton.disableProperty().unbind();
            restoreButton.setOnAction(null);
            seedWordsTextArea.setText("");
            restoreHeightInputTextField.setText("");
            seedWordsTextArea.getStyleClass().remove("validation-error");
            validatedSeed = null;
            seedWordsValid.set(false);
            seedWordsEdited.set(false);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Protected
    ///////////////////////////////////////////////////////////////////////////////////////////

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

    private void addInputFields() {
        passwordTextField = addPasswordTextField(gridPane, ++rowIndex, Res.get("password.enterPassword"), Layout.FLOATING_LABEL_DISTANCE);
        passwordTextField.setMaxWidth(Double.MAX_VALUE); // span the full popup width
        errorValidator = new JFXInputValidator();
        passwordTextField.getValidators().add(errorValidator);
        changeListener = (observable, oldValue, newValue) -> {
            errorValidator.resetValidation();
            passwordTextField.validate(); // clear any shown error while editing
            unlockButton.setDisable(newValue.isEmpty());
        };
        passwordTextField.textProperty().addListener(changeListener);
    }

    @Override
    protected void addButtons() {
        unlockButton = new AutoTooltipButton(Res.get("shared.unlock"));
        unlockButton.setDefaultButton(true);
        unlockButton.getStyleClass().add("action-button");
        unlockButton.setDisable(true);
        unlockButton.setOnAction(e -> onUnlock());

        forgotPasswordButton = new AutoTooltipButton(Res.get("password.forgotPassword"));
        forgotPasswordButton.setOnAction(e -> {
            forgotPasswordButton.setDisable(true);
            unlockButton.setDefaultButton(false);
            showRestoreScreen();
        });

        Button cancelButton = new AutoTooltipButton(Res.get("shared.cancel"));
        cancelButton.setOnAction(event -> {
            hide();
            closeHandlerOptional.ifPresent(Runnable::run);
        });

        HBox hBox = new HBox(10);
        hBox.setAlignment(Pos.CENTER_LEFT);
        GridPane.setRowIndex(hBox, ++rowIndex);
        hBox.getChildren().add(unlockButton);
        if (!hideForgotPasswordButton)
            hBox.getChildren().add(forgotPasswordButton);
        if (!hideCloseButton)
            hBox.getChildren().add(cancelButton);
        gridPane.getChildren().add(hBox);
    }

    private void onUnlock() {
        String password = passwordTextField.getText();
        checkArgument(password.length() < 500, Res.get("password.tooLong"));
        try {
            accountService.verifyPassword(password);
            if (passwordHandler != null) passwordHandler.onSuccess();
            hide();
        } catch (IncorrectPasswordException e) {
            errorValidator.applyErrorMessage(Res.get("password.startup.wrongPw"));
            passwordTextField.validate(); // show the error inline below the field
            passwordTextField.selectAll();
            passwordTextField.requestFocus();
        }
    }

    private void showRestoreScreen() {
        Label headLine2Label = new AutoTooltipLabel(Res.get("seed.restore.title"));
        headLine2Label.getStyleClass().add("popup-headline");
        headLine2Label.setMouseTransparent(true);
        GridPane.setHalignment(headLine2Label, HPos.LEFT);
        GridPane.setRowIndex(headLine2Label, ++rowIndex);
        GridPane.setMargin(headLine2Label, new Insets(30, 0, 0, 0));
        gridPane.getChildren().add(headLine2Label);

        seedWordsTextArea = addTextArea(gridPane, ++rowIndex, Res.get("seed.enterSeedWords"), 5);
        seedWordsTextArea.setPrefHeight(60);

        restoreHeightInputTextField = addTopLabelInputTextField(gridPane, ++rowIndex, Res.get("seed.restore.height"), 10).second;
        restoreHeightInputTextField.setValidator(new RestoreHeightValidator());
        restoreButton = addPrimaryActionButton(gridPane, ++rowIndex, Res.get("seed.restore"), 0);
        restoreButton.setDefaultButton(true);
        stage.setHeight(570);

        seedFocusListener = (observable, oldValue, focused) -> {
            if (!focused && seedWordsEdited.get() && !seedWordsValid.get()) applySeedValidity(false);
        };

        wordsTextAreaChangeListener = (observable, oldValue, newValue) -> {
            seedWordsEdited.set(true);
            validateSeedWords(oldValue, newValue);
        };

        seedWordsTextArea.focusedProperty().addListener(seedFocusListener);
        seedWordsTextArea.textProperty().addListener(wordsTextAreaChangeListener);
        restoreButton.disableProperty().bind(createBooleanBinding(
                () -> !seedWordsValid.get() || !seedWordsEdited.get() || !restoreHeightInputTextField.validationResultProperty().get().isValid,
                seedWordsValid, seedWordsEdited, restoreHeightInputTextField.validationResultProperty()));

        restoreButton.setOnAction(e -> onRestore());

        seedWordsTextArea.getStyleClass().remove("validation-error");

        layout();
    }

    // Clear the error while editing, fail fast on word count, then validate the full seed off the
    // UI thread; a paste runs instantly, typing is debounced, an unchanged seed reuses the last result.
    private void validateSeedWords(String oldValue, String seedWords) {
        if (seedValidationTimer != null) seedValidationTimer.stop();
        seedWordsTextArea.getStyleClass().remove("validation-error");
        String seed = SharedPresentation.normalizeSeedWords(seedWords);
        if (!hasValidWordCount(seed)) {
            seedWordsValid.set(false);
            return;
        }
        if (seed.equals(validatedSeed)) {
            applySeedValidity(validatedSeedValid);
            return;
        }
        Runnable validation = () -> new Thread(() -> {
            boolean valid = isSeedValid(seed);
            UserThread.execute(() -> {
                validatedSeed = seed;
                validatedSeedValid = valid;
                if (seedWords.equals(seedWordsTextArea.getText())) applySeedValidity(valid);
            });
        }, "ValidateSeedWords").start();
        if (Math.abs(seedWords.length() - oldValue.length()) > 1) validation.run();
        else seedValidationTimer = UserThread.runAfter(validation, 300, TimeUnit.MILLISECONDS);
    }

    private void applySeedValidity(boolean valid) {
        seedWordsValid.set(valid);
        if (valid) seedWordsTextArea.getStyleClass().remove("validation-error");
        else if (!seedWordsTextArea.getStyleClass().contains("validation-error")) seedWordsTextArea.getStyleClass().add("validation-error");
    }

    private boolean hasValidWordCount(String seedWords) {
        try {
            MoneroUtils.validateMnemonic(seedWords);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // defer to validation at restore if the seed cannot be checked
    private boolean isSeedValid(String seed) {
        try {
            return xmrWalletService.isSeedValid(seed);
        } catch (Exception e) {
            log.warn("Could not validate seed, deferring to restore, error={}", e.getMessage());
            return true;
        }
    }

    private void onRestore() {
        final Long restoreHeight;
        final LocalDate restoreDate;
        try {
            restoreHeight = getRestoreHeight();
            restoreDate = getRestoreDate();
        } catch (Exception e) {
            new Popup().warning(Res.get("seed.restore.height.invalid")).show();
            return;
        }
        BigInteger balance = xmrWalletService.getBalance();
        if (balance != null && balance.compareTo(BigInteger.ZERO) > 0) {
            new Popup().warning(Res.get("seed.warn.walletNotEmpty.msg"))
                    .actionButtonText(Res.get("seed.warn.walletNotEmpty.restore"))
                    .onAction(() -> confirmRestoreHeight(restoreHeight, restoreDate))
                    .closeButtonText(Res.get("seed.warn.walletNotEmpty.emptyWallet"))
                    .show();
        } else {
            confirmRestoreHeight(restoreHeight, restoreDate);
        }
    }

    private void confirmRestoreHeight(Long restoreHeight, LocalDate restoreDate) {
        if (restoreHeight == null && restoreDate == null) {
            new Popup().information(Res.get("seed.warn.emptyRestoreHeight"))
                    .closeButtonText(Res.get("shared.no"))
                    .actionButtonText(Res.get("shared.yes"))
                    .onAction(() -> doRestore(null, null))
                    .show();
        } else {
            doRestore(restoreHeight, restoreDate);
        }
    }

    // Parse the restore height, or null if blank or a date was entered.
    private Long getRestoreHeight() {
        String text = restoreHeightInputTextField.getText() == null ? "" : restoreHeightInputTextField.getText().trim();
        if (!text.matches("\\d+")) return null;
        return Long.parseLong(text);
    }

    // Parse the wallet creation date to restore from, or null if blank or a height was entered.
    private LocalDate getRestoreDate() {
        String text = restoreHeightInputTextField.getText() == null ? "" : restoreHeightInputTextField.getText().trim();
        if (text.isEmpty() || text.matches("\\d+")) return null;
        LocalDate date = LocalDate.parse(text);
        if (date.isAfter(LocalDate.now())) throw new IllegalArgumentException("Restore date cannot be in the future");
        return date;
    }

    private void doRestore(Long restoreHeight, LocalDate restoreDate) {
        SharedPresentation.restoreSeedWords(xmrWalletService, openOfferManager, SharedPresentation.normalizeSeedWords(seedWordsTextArea.getText()), restoreHeight, restoreDate);
    }
}
