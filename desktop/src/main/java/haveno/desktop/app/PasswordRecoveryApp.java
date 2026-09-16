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

import ch.qos.logback.classic.LoggerContext;
import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.common.crypto.IncorrectPasswordException;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.Res;
import haveno.core.util.RecoverPassword;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.AutoTooltipCheckBox;
import haveno.desktop.util.CssTheme;
import haveno.desktop.util.ImageUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.LoggerFactory;

/** A separate offline application: no account services, trading or persistence startup. */
public class PasswordRecoveryApp extends Application {
    private Path networkDir;
    private Path readinessFile;
    private int theme;
    private long parentPid;
    private boolean busy;
    private boolean finished;
    private final List<PasswordField> passwords = new ArrayList<>();
    private final Label status = new Label();
    private final ProgressBar progress = new ProgressBar();
    private final CheckBox backup = new AutoTooltipCheckBox();
    private final Button repair = new AutoTooltipButton();
    private final Button close = new AutoTooltipButton();
    private VBox form;

    @Override
    public void init() {
        // disable wire logging before any password can be entered or sent to wallet RPC
        ((LoggerContext) LoggerFactory.getILoggerFactory()).stop();
        List<String> args = getParameters().getRaw();
        if (args.isEmpty() || args.size() > 5) throw new IllegalArgumentException("Expected a network data directory");
        networkDir = Path.of(args.get(0)).toAbsolutePath().normalize();
        if (!Files.isDirectory(networkDir.resolve("keys")) || !Files.isDirectory(networkDir.resolve("wallet"))) {
            throw new IllegalArgumentException("Expected a network data directory containing keys and wallet directories");
        }
        theme = args.size() > 1 ? Integer.parseInt(args.get(1)) : CssTheme.CSS_THEME_LIGHT;
        parentPid = args.size() > 2 ? Long.parseLong(args.get(2)) : 0;
        if (args.size() > 3) GlobalSettings.setLocale(Locale.forLanguageTag(args.get(3)));
        if (args.size() > 4) readinessFile = Path.of(args.get(4));
        Res.setBaseCurrencyCode("XMR");
        Res.setBaseCurrencyName("Monero");
    }

    @Override
    public void start(Stage stage) throws IOException {
        VBox fields = new VBox(14);
        addPassword(fields, "password.recovery.current", "password.recovery.current.help");
        addPassword(fields, "password.confirmPassword", "password.recovery.confirm.help");
        addPassword(fields, "password.recovery.previous", "password.recovery.previous.help");
        for (PasswordField field : passwords.subList(0, 2)) {
            field.textProperty().addListener((observable, oldValue, newValue) -> {
                if (passwords.get(1).getStyleClass().remove("validation-error")) setStatus("", false);
            });
        }
        Hyperlink addPassword = new Hyperlink(Res.get("password.recovery.addPassword"));
        addPassword.setOnAction(event -> {
            addPassword(fields, "password.recovery.additional", null);
            passwords.get(passwords.size() - 1).requestFocus();
        });

        Label directory = label(networkDir.toString(), "startup-wizard-footer-label");
        Hyperlink openDirectory = new Hyperlink(Res.get("account.backup.openDirectory"));
        openDirectory.setOnAction(event -> getHostServices().showDocument(networkDir.getParent().toUri().toString()));
        backup.setText(Res.get("password.recovery.backup"));
        backup.setWrapText(true);
        backup.selectedProperty().addListener((observable, oldValue, selected) -> repair.setDisable(!selected || busy));

        form = new VBox(12, directory, fields, addPassword,
                label(Res.get("password.recovery.backup.help"), "startup-wizard-footer-label"), openDirectory, backup);
        status.setWrapText(true);
        status.setMinHeight(24);
        status.setMaxWidth(Double.MAX_VALUE);
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.getStyleClass().add("splash-progress");
        progress.managedProperty().bind(progress.visibleProperty());

        repair.setText(Res.get("password.recovery.repair"));
        repair.getStyleClass().addAll("action-button", "tac-agreement-action-button");
        repair.setDefaultButton(true);
        repair.setDisable(true);
        repair.setOnAction(event -> recover());
        close.setText(Res.get("shared.close"));
        close.getStyleClass().add("tac-agreement-secondary-button");
        close.setOnAction(event -> exit());
        close.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                close.fire();
                event.consume();
            }
        });
        HBox actions = new HBox(12, close, repair);
        actions.setAlignment(Pos.CENTER_RIGHT);
        Region divider = new Region();
        divider.getStyleClass().add("tac-agreement-footer-separator");

        VBox card = new VBox(16, StartupWizard.createHeaderSection(MaterialDesignIcon.LOCK_RESET,
                Res.get("password.recovery.title"), Res.get("password.recovery.subtitle")),
                form, status, progress, divider, actions);
        card.getStyleClass().add("wizard-card");
        card.setMaxWidth(StartupWizard.PAGE_WIDTH);
        card.setMinHeight(Region.USE_PREF_SIZE);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane content = new StackPane(card);
        content.setPadding(new Insets(30));
        content.setId("splash");
        content.getStyleClass().add("startup-wizard-backdrop");
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        Scene scene = new Scene(scroll, 900, 790);
        CssTheme.loadSceneStyles(scene, theme, false);
        stage.setScene(scene);
        stage.setTitle(Res.get("password.recovery.title") + " — Haveno");
        stage.getIcons().add(ImageUtil.getApplicationIconImage());
        stage.setMinWidth(620);
        stage.setMinHeight(480);
        stage.setOnCloseRequest(event -> {
            event.consume();
            if (!busy) exit();
        });
        stage.show();

        form.setDisable(true);
        progress.setVisible(true);
        setStatus(Res.get("password.recovery.waiting"), false);
        // wait for process exit, including its final persistence flush and any failed native wallet closes
        CompletableFuture<?> parentExit = parentPid == 0 ? CompletableFuture.completedFuture(null)
                : ProcessHandle.of(parentPid).map(ProcessHandle::onExit)
                        .orElseGet(() -> CompletableFuture.completedFuture(null));
        parentExit.whenComplete((parent, error) -> Platform.runLater(() -> {
            if (error != null) {
                setStatus(Res.get("password.recovery.waitFailed"), true);
                progress.setVisible(false);
                return;
            }
            form.setDisable(false);
            progress.setVisible(false);
            setStatus("", false);
            passwords.get(0).requestFocus();
        }));
        // signal readiness only after the screen and shutdown wait are initialized
        if (readinessFile != null) Files.createFile(readinessFile);
    }

    private void addPassword(VBox fields, String key, String helpKey) {
        PasswordField field = new PasswordField();
        field.getStyleClass().add("login-password-field");
        field.setAccessibleText(Res.get(key));
        field.setMaxWidth(Double.MAX_VALUE);
        field.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.X && (event.isShortcutDown() || event.isControlDown())) {
                field.replaceSelection("");
                event.consume();
            }
        });
        Label title = label(Res.get(key), null);
        title.setLabelFor(field);
        VBox row = new VBox(5, title, field);
        if (helpKey != null) {
            field.setAccessibleHelp(Res.get(helpKey));
            row.getChildren().add(label(Res.get(helpKey), "startup-wizard-footer-label"));
        }
        fields.getChildren().add(row);
        passwords.add(field);
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.setWrapText(true);
        if (style != null) label.getStyleClass().add(style);
        return label;
    }

    private void recover() {
        if (busy || finished || form.isDisabled() || !backup.isSelected()) return;
        if (!passwords.get(0).getText().equals(passwords.get(1).getText())) {
            setStatus(Res.get("password.passwordsDoNotMatch"), true);
            if (!passwords.get(1).getStyleClass().contains("validation-error")) passwords.get(1).getStyleClass().add("validation-error");
            passwords.get(1).requestFocus();
            return;
        }
        busy = true;
        form.setDisable(true);
        repair.setDisable(true);
        close.setDisable(true);
        progress.setVisible(true);
        setStatus(Res.get("password.recovery.working"), false);
        List<String> supplied = new ArrayList<>();
        for (PasswordField field : passwords) {
            supplied.add(field.getText());
            field.clear();
        }
        new Thread(() -> {
            Throwable failure = null;
            List<String> retained = List.of();
            try {
                retained = RecoverPassword.recover(networkDir, supplied.get(0), supplied.get(1), supplied.subList(2, supplied.size()), null);
            } catch (Throwable error) {
                failure = error;
            } finally {
                supplied.clear();
            }
            Throwable result = failure;
            List<String> retainedBackups = retained;
            Platform.runLater(() -> showResult(result, retainedBackups));
        }, "RecoverPassword").start();
    }

    private void showResult(Throwable failure, List<String> retainedBackups) {
        busy = false;
        finished = true;
        progress.setVisible(false);
        close.setDisable(false);
        form.setVisible(false);
        form.setManaged(false);
        if (failure == null) {
            setStatus(Res.get("password.recovery.success") + (retainedBackups.isEmpty() ? ""
                    : "\n\n" + Res.get("password.recovery.retainedBackups") + "\n" + String.join(", ", retainedBackups)), false);
            repair.setVisible(false);
            repair.setManaged(false);
        } else {
            String message = failure instanceof IncorrectPasswordException
                    ? Res.get("password.recovery.wrongCurrent") : failure.getMessage();
            setStatus(Res.get("password.recovery.failed", message == null ? failure.getClass().getSimpleName() : message), true);
            repair.setText(Res.get("password.recovery.retry"));
            repair.setDisable(false);
            // a failed native close may retain a handle; retry in a fresh process with fresh password input
            repair.setOnAction(event -> {
                repair.setDisable(true);
                close.setDisable(true);
                busy = true;
                PasswordRecoveryLauncher.start(networkDir).whenComplete((result, error) -> Platform.runLater(() -> {
                    if (error == null) exit();
                    else {
                        busy = false;
                        close.setDisable(false);
                        repair.setDisable(false);
                        setStatus(Res.get("password.recovery.launchFailed"), true);
                    }
                }));
            });
        }
        close.requestFocus();
    }

    private void setStatus(String message, boolean error) {
        status.getStyleClass().remove("error-text");
        if (error) status.getStyleClass().add("error-text");
        status.setText(message);
    }

    private void exit() {
        passwords.forEach(PasswordField::clear);
        Platform.exit();
        System.exit(0);
    }
}
