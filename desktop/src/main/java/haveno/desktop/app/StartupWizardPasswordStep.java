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

package haveno.desktop.app;

import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.core.locale.Res;
import haveno.desktop.components.AutoTooltipLabel;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javax.annotation.Nullable;

/**
 * Wizard step to protect the new account with a password. Optional unless the app was
 * started with --passwordRequired; leaving the fields empty skips password protection.
 */
public class StartupWizardPasswordStep implements StartupWizard.Step {

    private static final double FIELD_WIDTH = 340;
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final boolean passwordRequired;
    private final VBox content;
    private final PasswordField passwordField, confirmField;
    private final Label statusLabel = new AutoTooltipLabel();

    public StartupWizardPasswordStep(boolean passwordRequired) {
        this.passwordRequired = passwordRequired;

        passwordField = createPasswordField(Res.get("password.enterPassword"));
        confirmField = createPasswordField(Res.get("password.confirmPassword"));

        Label info = new AutoTooltipLabel(Res.get(passwordRequired
                ? "startupWizard.password.info.required"
                : "startupWizard.password.info"));
        info.getStyleClass().add("startup-wizard-footer-label");
        info.setWrapText(true);
        info.setMaxWidth(FIELD_WIDTH * 1.6);

        statusLabel.setMinHeight(24);
        statusLabel.setAlignment(Pos.CENTER);

        content = new VBox(12,
                StartupWizard.createHeaderSection(MaterialDesignIcon.LOCK_OUTLINE,
                        Res.get("startupWizard.password.headline"),
                        Res.get(passwordRequired
                                ? "startupWizard.password.subtitle.required"
                                : "startupWizard.password.subtitle")),
                passwordField,
                confirmField,
                info,
                statusLabel);
        content.setAlignment(Pos.TOP_CENTER);
        VBox.setMargin(passwordField, new Insets(8, 0, 0, 0));
    }

    @Override
    public Region getContent() {
        return content;
    }

    @Override
    public void onShown() {
        passwordField.requestFocus();
    }

    @Override
    public void validate(Consumer<Boolean> resultHandler) {
        statusLabel.getStyleClass().remove("error-text");
        statusLabel.setText("");
        String password = passwordField.getText();
        String error = null;
        if (password.isEmpty() && confirmField.getText().isEmpty()) {
            if (passwordRequired) error = Res.get("validation.empty");
        } else if (password.length() < MIN_PASSWORD_LENGTH) {
            error = Res.get("validation.passwordTooShort");
        } else if (!password.equals(confirmField.getText())) {
            error = Res.get("password.passwordsDoNotMatch");
        }
        if (error != null) {
            if (!statusLabel.getStyleClass().contains("error-text")) statusLabel.getStyleClass().add("error-text");
            statusLabel.setText(error);
        }
        resultHandler.accept(error == null);
    }

    @Override
    public String getNextButtonText() {
        return Res.get("startupWizard.next");
    }

    /** The password to protect the account with, or null for none. */
    @Nullable
    public String getPassword() {
        return passwordField.getText().isEmpty() ? null : passwordField.getText();
    }

    private static PasswordField createPasswordField(String prompt) {
        PasswordField field = new PasswordField();
        field.setPromptText(prompt);
        field.getStyleClass().add("login-password-field");
        field.setMaxWidth(FIELD_WIDTH);
        // PasswordField blocks cut/copy for security; allow ctrl/cmd+x to clear the selection (without clipboard)
        field.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.X && (event.isShortcutDown() || event.isControlDown())
                    && !field.getSelectedText().isEmpty()) {
                field.replaceSelection("");
                event.consume();
            }
        });
        return field;
    }
}
