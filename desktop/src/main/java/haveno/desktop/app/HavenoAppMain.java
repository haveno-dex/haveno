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

package haveno.desktop.app;

import haveno.common.UserThread;
import haveno.common.app.AppModule;
import haveno.common.app.Version;
import haveno.common.crypto.IncorrectPasswordException;
import haveno.core.app.AvoidStandbyModeService;
import haveno.core.app.HavenoExecutable;
import haveno.desktop.common.UITimer;
import haveno.desktop.common.view.guice.InjectorViewFactory;
import haveno.desktop.setup.DesktopPersistedDataHost;
import haveno.desktop.util.ImageUtil;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@Slf4j
public class HavenoAppMain extends HavenoExecutable {

    private HavenoApp application;

    public HavenoAppMain() {
        super("Haveno Desktop", "haveno-desktop", HavenoExecutable.DEFAULT_APP_NAME, Version.VERSION);
    }

    public static void main(String[] args) {
        // For some reason the JavaFX launch process results in us losing the thread
        // context class loader: reset it. In order to work around a bug in JavaFX 8u25
        // and below, you must include the following code as the first line of your
        // realMain method:
        Thread.currentThread().setContextClassLoader(HavenoAppMain.class.getClassLoader());

        new HavenoAppMain().execute(args);
    }

    @Override
    public void onSetupComplete() {
        log.debug("onSetupComplete");
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // First synchronous execution tasks
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void configUserThread() {
        UserThread.setExecutor(Platform::runLater);
        UserThread.setTimerClass(UITimer.class);
    }

    @Override
    protected void launchApplication() {
        HavenoApp.setAppLaunchedHandler(application -> {
            HavenoAppMain.this.application = (HavenoApp) application;
            // Map to user thread!
            UserThread.execute(this::onApplicationLaunched);
        });

        Application.launch(HavenoApp.class);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // As application is a JavaFX application we need to wait for onApplicationLaunched
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void onApplicationLaunched() {
        super.onApplicationLaunched();
        application.setGracefulShutDownHandler(this);
    }

    @Override
    public void handleUncaughtException(Throwable throwable, boolean doShutDown) {
        application.handleUncaughtException(throwable, doShutDown);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // We continue with a series of synchronous execution tasks
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected AppModule getModule() {
        return new HavenoAppModule(config);
    }

    @Override
    protected void applyInjector() {
        super.applyInjector();

        application.setInjector(injector);
        injector.getInstance(InjectorViewFactory.class).setInjector(injector);
    }

    @Override
    protected void readAllPersisted(Runnable completeHandler) {
        super.readAllPersisted(DesktopPersistedDataHost.getPersistedDataHosts(injector), completeHandler);
    }

    @Override
    protected void setupAvoidStandbyMode() {
        injector.getInstance(AvoidStandbyModeService.class).init();
    }

    @Override
    protected void startApplication() {
        // We need to be in user thread! We mapped at launchApplication already.  Once
        // the UI is ready we get onApplicationStarted called and start the setup there.
        application.startApplication(this::onApplicationStarted);
    }

    @Override
    protected void onApplicationStarted() {
        super.onApplicationStarted();

        // Relevant to have this in the logs, for support cases
        // This can only be called after JavaFX is initialized, otherwise the version logged will be null
        // Therefore, calling this as part of onApplicationStarted()
        log.info("Using JavaFX {}", System.getProperty("javafx.version"));
    }

    @Override
    protected CompletableFuture<Boolean> loginAccount() {

        // attempt default login
        CompletableFuture<Boolean> result = super.loginAccount();
        try {
            if (result.get()) return result;
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException(e);
        }

        // login using dialog
        CompletableFuture<Boolean> dialogResult = new CompletableFuture<>();
        Platform.setImplicitExit(false);
        Platform.runLater(() -> {

            // show password dialog until account open
            String errorMessage = null;
            while (!accountService.isAccountOpen()) {

                // create the password dialog
                PasswordDialog passwordDialog = new PasswordDialog(errorMessage);

                // wait for user to enter password
                Optional<String> passwordResult = passwordDialog.showAndWait();
                if (passwordResult.isPresent()) {
                    try {
                        accountService.openAccount(passwordResult.get());
                        dialogResult.complete(accountService.isAccountOpen());
                    } catch (IncorrectPasswordException e) {
                        errorMessage = "Incorrect password";
                    }
                } else {
                    // if the user cancelled the dialog, complete the passwordFuture exceptionally
                    dialogResult.completeExceptionally(new Exception("Password dialog cancelled"));
                    break;
                }
            }
        });
        return dialogResult;
    }

    private class PasswordDialog extends Dialog<String> {

        public PasswordDialog(String errorMessage) {
            setTitle("Enter Password");
            setHeaderText("Please enter your Haveno password:");

            // Add an icon to the dialog
            Stage stage = (Stage) getDialogPane().getScene().getWindow();
            stage.getIcons().add(ImageUtil.getImageByPath("lock@2x.png"));

            // Create the password field
            PasswordField passwordField = new PasswordField();
            passwordField.setPromptText("Password");

            // Create the error message field
            Label errorMessageField = new Label(errorMessage);
            errorMessageField.setTextFill(Color.color(1, 0, 0));

            // Create the version field
            Label versionField = new Label("v" + Version.VERSION);

            // Set the dialog content
            VBox vbox = new VBox(10);
            ImageView logoImageView = new ImageView(ImageUtil.getImageByPath("logo_splash_light.png"));
            logoImageView.setFitWidth(342);
            logoImageView.setPreserveRatio(true);
            vbox.getChildren().addAll(logoImageView, passwordField, errorMessageField, versionField);
            vbox.setAlignment(Pos.TOP_CENTER);
            getDialogPane().setContent(vbox);

            // Add OK and Cancel buttons
            ButtonType okButton = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            getDialogPane().getButtonTypes().addAll(okButton, cancelButton);

            // Convert the result to a string when the OK button is clicked
            setResultConverter(buttonType -> {
                if (buttonType == okButton) {
                    return passwordField.getText();
                } else {
                    new Thread(() -> HavenoApp.getShutDownHandler().run()).start();
                    return null;
                }
            });

            // Focus the password field when dialog is shown
            Window window = getDialogPane().getScene().getWindow();
            if (window instanceof Stage) {
                Stage dialogStage = (Stage) window;
                dialogStage.focusedProperty().addListener((observable, oldValue, newValue) -> {
                    if (newValue) {
                        passwordField.requestFocus();
                    }
                });
            }
        }
    }
}
