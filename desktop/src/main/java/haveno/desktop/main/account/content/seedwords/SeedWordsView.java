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

package haveno.desktop.main.account.content.seedwords;

import com.google.inject.Inject;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.core.locale.Res;
import haveno.core.offer.OpenOfferManager;
import haveno.core.user.DontShowAgainLookup;
import haveno.core.util.validation.RestoreHeightValidator;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.common.view.ActivatableView;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.components.InputTextField;
import haveno.desktop.main.SharedPresentation;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.overlays.windows.WalletPasswordWindow;
import static haveno.desktop.util.FormBuilder.addMultilineLabel;
import static haveno.desktop.util.FormBuilder.addPrimaryActionButtonAFterGroup;
import static haveno.desktop.util.FormBuilder.addTitledGroupBg;
import static haveno.desktop.util.FormBuilder.addTopLabelDatePicker;
import static haveno.desktop.util.FormBuilder.addTopLabelTextArea;
import static haveno.desktop.util.FormBuilder.addTopLabelWithVBox;
import haveno.desktop.util.GUIUtil;
import haveno.desktop.util.Layout;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import monero.common.MoneroUtils;
import static javafx.beans.binding.Bindings.createBooleanBinding;
import static javafx.beans.binding.Bindings.createDoubleBinding;

@FxmlView
public class SeedWordsView extends ActivatableView<GridPane, Void> {
    private final OpenOfferManager openOfferManager;
    private final XmrWalletService xmrWalletService;
    private final WalletPasswordWindow walletPasswordWindow;

    private Button restoreButton;
    private TextArea displaySeedWordsTextArea, seedWordsTextArea;
    private DatePicker datePicker;
    private InputTextField restoreHeightInputTextField;

    private int gridRow = 0;
    private ChangeListener<Boolean> seedFocusListener;
    private final SimpleBooleanProperty seedWordsValid = new SimpleBooleanProperty(false);
    private ChangeListener<String> seedWordsTextAreaChangeListener;
    private final BooleanProperty seedWordsEdited = new SimpleBooleanProperty();
    private Timer seedValidationTimer;
    // cache the last async seed check so an unchanged seed does not re-run it
    private String validatedSeed;
    private boolean validatedSeedValid;
    private String seedWordText;
    private LocalDate walletCreationDate;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    private SeedWordsView(OpenOfferManager openOfferManager,
                          XmrWalletService xmrWalletService,
                          WalletPasswordWindow walletPasswordWindow) {
        this.openOfferManager = openOfferManager;
        this.xmrWalletService = xmrWalletService;
        this.walletPasswordWindow = walletPasswordWindow;
    }

    @Override
    protected void initialize() {
        addTitledGroupBg(root, gridRow, 2, Res.get("account.seed.backup.title"));
        displaySeedWordsTextArea = addTopLabelTextArea(root, gridRow, Res.get("seed.seedWords"), "", Layout.FIRST_ROW_DISTANCE).second;
        displaySeedWordsTextArea.getStyleClass().add("wallet-seed-words");
        displaySeedWordsTextArea.setEditable(false);
        fitHeightToSeedWords(displaySeedWordsTextArea);

        datePicker = addTopLabelDatePicker(root, ++gridRow, Res.get("seed.date"), 10).second;
        datePicker.setMouseTransparent(true);

        addTitledGroupBg(root, ++gridRow, 3, Res.get("seed.restore.title"), Layout.GROUP_DISTANCE);
        seedWordsTextArea = addTopLabelTextArea(root, gridRow, Res.get("seed.seedWords"), "", Layout.FIRST_ROW_AND_GROUP_DISTANCE).second;
        seedWordsTextArea.getStyleClass().add("wallet-seed-words");
        fitHeightToSeedWords(seedWordsTextArea);

        restoreHeightInputTextField = new InputTextField();
        restoreHeightInputTextField.setValidator(new RestoreHeightValidator());
        addTopLabelWithVBox(root, ++gridRow, Res.get("seed.restore.height"), GUIUtil.wrapWithCalendarPicker(restoreHeightInputTextField), 10);
        restoreButton = addPrimaryActionButtonAFterGroup(root, ++gridRow, Res.get("seed.restore"));

        addTitledGroupBg(root, ++gridRow, 1, Res.get("shared.information"), Layout.GROUP_DISTANCE);
        addMultilineLabel(root, gridRow, Res.get("account.seed.info"),
                Layout.FIRST_ROW_AND_GROUP_DISTANCE);

        seedFocusListener = (observable, oldValue, focused) -> {
            if (!focused && seedWordsEdited.get() && !seedWordsValid.get()) applySeedValidity(false);
        };

        seedWordsTextAreaChangeListener = (observable, oldValue, newValue) -> {
            seedWordsEdited.set(true);
            validateSeedWords(oldValue, newValue);
        };
    }

    // fit height to the wrapped seed words (min 2 rows) so they sit vertically centered
    private void fitHeightToSeedWords(TextArea textArea) {
        textArea.sceneProperty().addListener((o, oldScene, newScene) -> {
            if (newScene == null) return;
            textArea.applyCss();
            Node text = textArea.lookup(".text");
            Region content = (Region) textArea.lookup(".content");
            textArea.prefHeightProperty().bind(createDoubleBinding(() -> {
                Text refLine = new Text("X");
                refLine.setFont(textArea.getFont());
                double textHeight = Math.max(text.getBoundsInLocal().getHeight(), 2 * refLine.getLayoutBounds().getHeight());
                return Math.ceil(textHeight + content.getInsets().getTop() + content.getInsets().getBottom()
                        + textArea.getInsets().getTop() + textArea.getInsets().getBottom());
            }, text.boundsInLocalProperty()));
            textArea.maxHeightProperty().bind(textArea.prefHeightProperty());
        });
    }

    @Override
    public void activate() {
        seedWordsTextArea.focusedProperty().addListener(seedFocusListener);
        seedWordsTextArea.textProperty().addListener(seedWordsTextAreaChangeListener);
        restoreButton.disableProperty().bind(createBooleanBinding(
                () -> !seedWordsValid.get() || !seedWordsEdited.get() || !restoreHeightInputTextField.validationResultProperty().get().isValid,
                seedWordsValid, seedWordsEdited, restoreHeightInputTextField.validationResultProperty()));

        restoreButton.setOnAction(e -> {
            new Popup().information(Res.get("account.seed.restore.info"))
                    .closeButtonText(Res.get("shared.cancel"))
                    .actionButtonText(Res.get("account.seed.restore.ok"))
                    .onAction(this::onRestore)
                    .show();
        });

        seedWordsTextArea.getStyleClass().remove("validation-error");

        String key = "showBackupWarningAtSeedPhrase";
        if (DontShowAgainLookup.showAgain(key)) {
            new Popup().warning(Res.get("account.seed.backup.warning"))
                    .onAction(this::showSeedPhrase)
                    .actionButtonText(Res.get("shared.iUnderstand"))
                    .useIUnderstandButton()
                    .dontShowAgainId(key)
                    .hideCloseButton()
                    .show();
        } else {
            showSeedPhrase();
        }
    }

    private void showSeedPhrase() {
        if (xmrWalletService.isWalletEncrypted()) {
            askForPassword();
        } else {
            String key = "showSeedWordsWarning";
            if (DontShowAgainLookup.showAgain(key)) {
                new Popup().warning(Res.get("account.seed.warn.noPw.msg"))
                        .actionButtonText(Res.get("account.seed.warn.noPw.yes"))
                        .onAction(() -> {
                            DontShowAgainLookup.dontShowAgain(key, true);
                            initSeedWords(xmrWalletService.getSeed());
                            showSeedScreen();
                        })
                        .closeButtonText(Res.get("shared.no"))
                        .show();
            } else {
                initSeedWords(xmrWalletService.getSeed());
                showSeedScreen();
            }
        }
    }

    @Override
    protected void deactivate() {
        if (seedValidationTimer != null) seedValidationTimer.stop();
        seedWordsTextArea.focusedProperty().removeListener(seedFocusListener);
        seedWordsTextArea.textProperty().removeListener(seedWordsTextAreaChangeListener);
        restoreButton.disableProperty().unbind();
        restoreButton.setOnAction(null);

        displaySeedWordsTextArea.setText("");
        seedWordsTextArea.setText("");
        restoreHeightInputTextField.setText("");
        datePicker.setValue(null);
        validatedSeed = null;
        seedWordsValid.set(false);
        seedWordsEdited.set(false);

        seedWordsTextArea.getStyleClass().remove("validation-error");
    }

    private void askForPassword() {
        walletPasswordWindow.headLine(Res.get("account.seed.enterPw")).onSuccess(() -> {
            initSeedWords(xmrWalletService.getSeed());
            showSeedScreen();
        }).hideForgotPasswordButton().show();
    }

    private void initSeedWords(String seed) {
        seedWordText = seed;
    }

    private void showSeedScreen() {
        displaySeedWordsTextArea.setText(seedWordText);
        walletCreationDate = Instant.ofEpochSecond(xmrWalletService.getWalletCreationDate()).atZone(ZoneId.systemDefault()).toLocalDate();
        datePicker.setValue(walletCreationDate);
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
