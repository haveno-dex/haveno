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

import static haveno.desktop.util.Layout.INITIAL_WINDOW_HEIGHT;
import static haveno.desktop.util.Layout.INITIAL_WINDOW_WIDTH;
import static haveno.desktop.util.Layout.MIN_WINDOW_HEIGHT;
import static haveno.desktop.util.Layout.MIN_WINDOW_WIDTH;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.google.common.base.Joiner;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.name.Names;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.app.DevEnv;
import haveno.common.app.Log;
import haveno.common.app.Version;
import haveno.common.config.BaseCurrencyNetwork;
import haveno.common.config.Config;
import haveno.common.crypto.Hash;
import haveno.common.setup.GracefulShutDownHandler;
import haveno.common.setup.UncaughtExceptionHandler;
import haveno.common.util.Utilities;
import haveno.core.locale.Res;
import haveno.core.offer.OpenOfferManager;
import haveno.core.support.dispute.arbitration.ArbitrationManager;
import haveno.core.support.dispute.mediation.MediationManager;
import haveno.core.support.dispute.refund.RefundManager;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.user.Cookie;
import haveno.core.user.CookieKey;
import haveno.core.user.Preferences;
import haveno.core.user.StartupSettings;
import haveno.core.user.User;
import haveno.core.xmr.wallet.WalletsManager;
import haveno.desktop.common.view.CachingViewLoader;
import haveno.desktop.common.view.View;
import haveno.desktop.common.view.ViewLoader;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.components.DarkModeToggle;
import haveno.desktop.main.MainView;
import haveno.desktop.main.debug.DebugView;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.overlays.windows.FilterWindow;
import haveno.desktop.main.overlays.windows.SendAlertMessageWindow;
import haveno.desktop.main.overlays.windows.ShowWalletDataWindow;
import haveno.desktop.util.CssTheme;
import haveno.desktop.util.DisplayUtils;
import haveno.desktop.util.ImageUtil;
import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javafx.application.Application;
import javafx.beans.value.ChangeListener;
import javafx.geometry.BoundingBox;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;

@Slf4j
public class HavenoApp extends Application implements UncaughtExceptionHandler {
    @Setter
    private static Consumer<Application> appLaunchedHandler;
    @Getter
    private static Runnable shutDownHandler;
    @Setter
    private static Runnable onGracefulShutDownHandler;

    @Setter
    private Injector injector;
    @Setter
    private GracefulShutDownHandler gracefulShutDownHandler;
    private Stage stage;
    private boolean popupOpened;
    private Scene scene;
    private boolean shutDownRequested;
    private MainView mainView;
    // debounces writing window bounds to the unencrypted startup store during a drag/resize
    private Timer stageBoundsPersistenceTimer;
    // true if the window was placed from the saved bounds before login (so we must not re-apply them after)
    private boolean startupWindowBoundsApplied;

    public HavenoApp() {
        shutDownHandler = this::stop;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // JavaFx Application implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    // NOTE: This method is not called on the JavaFX Application Thread.
    @Override
    public void init() {
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        appLaunchedHandler.accept(this);
    }

    public void startApplication(Runnable onApplicationStartedHandler) {
        log.info("Starting application");
        try {
            mainView = loadMainView(injector);
            mainView.setOnApplicationStartedHandler(onApplicationStartedHandler);

            User user = injector.getInstance(User.class);
            if (scene == null) {

                // no password screen was shown, so build the scene, configure the stage and restore the saved layout
                scene = createAndConfigScene(mainView.getRoot(), injector);
                configureStage(scene);
                layoutStageFromPersistedData(stage, user);
            } else {

                // reuse the window already shown for the password screen and swap in the main view
                scene.setRoot(mainView.getRoot());
                if (startupWindowBoundsApplied) {
                    // already placed (and maybe dragged) before login; keep and persist the current position, don't jump
                    persistStageBounds(stage);
                } else {
                    // first run with no stored value: keep the current window and seed the store for next startup
                    seedStartupWindowFromCookie(user);
                }
            }

            // track future layout changes, then show
            addStageLayoutListeners(stage);
            stage.show();
        } catch (Throwable throwable) {
            log.error("Error during app init", throwable);
            handleUncaughtException(throwable, false);
        }
    }

    /**
     * Show a password prompt within the primary application window. The same window is
     * reused for the rest of the application startup once the password is accepted.
     *
     * @param passwordHandler verifies each submitted password off the JavaFX thread and reports the
     *                        outcome (null if accepted, otherwise an error message to display)
     * @param onQuit          called if the user chooses to quit instead of logging in
     */
    public void showPasswordScreen(PasswordHandler passwordHandler, Runnable onQuit) {
        // preferences applies the persisted theme before login, so this screen uses the user's chosen theme
        scene = createAndConfigScene(createPasswordScreen(passwordHandler, onQuit), injector);
        configureStage(scene);

        // open the window in its last saved place (from the unencrypted startup store) without waiting for login
        Optional<BoundingBox> savedBounds = readStartupWindowBounds();
        if (savedBounds.isPresent()) startupWindowBoundsApplied = applyStageBounds(stage, savedBounds.get());

        stage.show();
    }

    public interface PasswordHandler {
        /**
         * Verify the entered password and report the outcome via {@code resultHandler}: null if the
         * password was accepted, otherwise an error message to display. The verification decrypts the
         * account keys (slow), so it should be run off the JavaFX thread
         */
        void onPasswordEntered(String password, Consumer<String> resultHandler);
    }

    @Override
    public void stop() {
        if (!shutDownRequested) {
            new Popup().headLine(Res.get("popup.shutDownInProgress.headline"))
                    .backgroundInfo(Res.get("popup.shutDownInProgress.msg"))
                    .hideCloseButton()
                    .useAnimation(false)
                    .show();
            new Thread(() -> {
                gracefulShutDownHandler.gracefulShutDown(() -> {
                    log.info("App shutdown complete");
                    if (onGracefulShutDownHandler != null) onGracefulShutDownHandler.run();
                });
            }).start();
            shutDownRequested = true;
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // UncaughtExceptionHandler implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void handleUncaughtException(Throwable throwable, boolean doShutDown) {
        if (!shutDownRequested) {
            if (scene == null) {
                log.warn("Scene not available yet, we create a new scene. The bug might be caused by an exception in a constructor or by a circular dependency in Guice. throwable=" + throwable.toString());
                scene = new Scene(new StackPane(), 1000, 650);
                CssTheme.loadSceneStyles(scene, CssTheme.CSS_THEME_LIGHT, false);
                stage.setScene(scene);
                stage.show();
            }
            try {
                try {
                    if (!popupOpened) {
                        popupOpened = true;
                        new Popup().error(Objects.requireNonNullElse(throwable.getMessage(), throwable.toString()))
                                .onClose(() -> popupOpened = false)
                                .show();
                    }
                } catch (Throwable throwable3) {
                    log.error("Error at displaying Throwable.");
                    throwable3.printStackTrace();
                }
                if (doShutDown)
                    stop();
            } catch (Throwable throwable2) {
                // If printStackTrace cause a further exception we don't pass the throwable to the Popup.
                log.error(throwable2.toString());
                if (doShutDown)
                    stop();
            }
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private Scene createAndConfigScene(Parent root, Injector injector) {
        //Rectangle maxWindowBounds = new Rectangle();
        Rectangle2D maxWindowBounds = new Rectangle2D(0, 0, 0, 0);
        try {
            maxWindowBounds = Screen.getPrimary().getBounds();
        } catch (IllegalArgumentException e) {
            // Multi-screen environments may encounter IllegalArgumentException (Window must not be zero)
            // Just ignore the exception and continue, which means the window will use the minimum window size below
            // since we are unable to determine if we can use a larger size
        }
        Scene scene = new Scene(root,
                maxWindowBounds.getWidth() < INITIAL_WINDOW_WIDTH ?
                        Math.max(maxWindowBounds.getWidth(), MIN_WINDOW_WIDTH) :
                        INITIAL_WINDOW_WIDTH,
                maxWindowBounds.getHeight() < INITIAL_WINDOW_HEIGHT ?
                        Math.max(maxWindowBounds.getHeight(), MIN_WINDOW_HEIGHT) :
                        INITIAL_WINDOW_HEIGHT);

        addSceneKeyEventHandler(scene, injector);

        Preferences preferences = injector.getInstance(Preferences.class);
        var config = injector.getInstance(Config.class);
        preferences.getCssThemeProperty().addListener((ov) -> {
            CssTheme.loadSceneStyles(scene, preferences.getCssTheme(), config.useDevModeHeader);
        });
        CssTheme.loadSceneStyles(scene, preferences.getCssTheme(), config.useDevModeHeader);
        
        // set initial background color
        scene.setFill(CssTheme.isDarkTheme() ? Color.BLACK : Color.WHITE);

        return scene;
    }

    private Parent createPasswordScreen(PasswordHandler passwordHandler, Runnable onQuit) {

        Preferences preferences = injector.getInstance(Preferences.class);

        // logo (theme-aware via css id, matching the main splash screen)
        ImageView logo = new ImageView();
        logo.setId(Config.baseCurrencyNetwork() == BaseCurrencyNetwork.XMR_MAINNET ? "image-logo-splash" : "image-logo-splash-testnet");
        logo.setFitWidth(342);
        logo.setPreserveRatio(true);
        logo.setSmooth(true);

        Label headline = new AutoTooltipLabel(Res.get("password.enterPassword"));
        headline.setStyle("-fx-font-size: 1.15em;");

        PasswordField passwordField = new PasswordField();
        passwordField.setMaxWidth(340);
        passwordField.getStyleClass().add("login-password-field");
        // PasswordField blocks cut/copy for security; allow ctrl/cmd+x to clear the selection (without clipboard)
        passwordField.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.X && (event.isShortcutDown() || event.isControlDown())
                    && !passwordField.getSelectedText().isEmpty()) {
                passwordField.replaceSelection("");
                event.consume();
            }
        });

        Label statusLabel = new AutoTooltipLabel();
        statusLabel.setMinHeight(24);
        statusLabel.setAlignment(Pos.CENTER);

        Consumer<String> showWorking = message -> {
            statusLabel.getStyleClass().remove("error-text");
            statusLabel.setStyle("-fx-text-fill: -bs-color-gray-6;");
            statusLabel.setText(message);
        };
        Consumer<String> showError = message -> {
            statusLabel.setStyle(null);
            if (!statusLabel.getStyleClass().contains("error-text")) statusLabel.getStyleClass().add("error-text");
            statusLabel.setText(message);
        };

        Button unlockButton = new AutoTooltipButton(Res.get("shared.unlock"));
        unlockButton.setDefaultButton(true);
        unlockButton.getStyleClass().add("action-button");
        Button quitButton = new AutoTooltipButton(Res.get("shared.shutDown"));

        Consumer<Boolean> setControlsDisabled = disabled -> {
            passwordField.setDisable(disabled);
            unlockButton.setDisable(disabled);
            quitButton.setDisable(disabled);
        };

        Label versionLabel = new AutoTooltipLabel(Res.get("mainView.footer.version", Version.VERSION));
        versionLabel.setStyle("-fx-font-size: 0.9em; -fx-text-fill: -bs-color-gray-6;");

        boolean[] submitting = {false};
        Runnable submitHandler = () -> {
            if (submitting[0]) return;
            submitting[0] = true;

            // enter the working state before the off-thread verification so the UI stays responsive while decrypting
            setControlsDisabled.accept(true);
            showWorking.accept(Res.get("password.startup.unlocking"));

            passwordHandler.onPasswordEntered(passwordField.getText(), errorMessage -> UserThread.execute(() -> {
                if (errorMessage == null) return; // accepted; keep the working state until the main view loads

                // wrong password: reset the screen and show the error
                submitting[0] = false;
                showError.accept(errorMessage);
                setControlsDisabled.accept(false);
                passwordField.clear();
                passwordField.requestFocus();
            }));
        };
        unlockButton.setOnAction(event -> submitHandler.run());
        passwordField.setOnAction(event -> submitHandler.run());
        quitButton.setOnAction(event -> {
            setControlsDisabled.accept(true);
            showWorking.accept(Res.get("password.startup.shuttingDown"));
            onQuit.run();
        });

        // enter should shut down and consume event when shut down button is focused
        quitButton.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                quitButton.fire();
                event.consume();
            }
        });

        HBox buttonBox = new HBox(10, unlockButton, quitButton);
        buttonBox.setAlignment(Pos.CENTER);

        VBox contentBox = new VBox(15, logo, headline, passwordField, statusLabel, buttonBox, versionLabel);
        contentBox.setAlignment(Pos.CENTER);
        contentBox.setPadding(new Insets(20));
        VBox.setMargin(versionLabel, new Insets(20, 0, 0, 0));

        // light/dark toggle in the corner so the user can switch theme while entering their password
        DarkModeToggle themeToggle = new DarkModeToggle(preferences);
        themeToggle.setFitHeight(20);

        StackPane root = new StackPane(contentBox, themeToggle);
        root.setId("splash");
        StackPane.setAlignment(themeToggle, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(themeToggle, new Insets(12));

        // focus the password field once the screen is rendered
        UserThread.execute(passwordField::requestFocus);

        return root;
    }

    private void configureStage(Scene scene) {
        stage.setOnCloseRequest(event -> {
            event.consume();
            shutDownByUser();
        });

        // configure the primary stage
        String appName = injector.getInstance(Key.get(String.class, Names.named(Config.APP_NAME)));
        List<String> postFixes = new ArrayList<>();
        if (!Config.baseCurrencyNetwork().isMainnet()) {
            postFixes.add(Config.baseCurrencyNetwork().name());
        }
        if (injector.getInstance(Config.class).useLocalhostForP2P) {
            postFixes.add("LOCALHOST");
        }
        if (injector.getInstance(Config.class).useDevMode) {
            postFixes.add("DEV MODE");
        }
        if (!postFixes.isEmpty()) {
            appName += " [" + Joiner.on(", ").join(postFixes) + "]";
        }

        stage.setTitle(appName);
        stage.setScene(scene);
        stage.setMinWidth(MIN_WINDOW_WIDTH);
        stage.setMinHeight(MIN_WINDOW_HEIGHT);
        stage.getIcons().add(ImageUtil.getApplicationIconImage());
    }

    private void layoutStageFromPersistedData(Stage stage, User user) {
        loadWindowBounds(user).ifPresent(bounds -> applyStageBounds(stage, bounds));
    }

    // saved window bounds, preferring the startup store and migrating from the legacy cookie on first upgrade
    private Optional<BoundingBox> loadWindowBounds(User user) {
        Optional<BoundingBox> bounds = readStartupWindowBounds();
        if (bounds.isEmpty()) {
            bounds = readLegacyCookieBounds(user);
            bounds.ifPresent(this::seedStartupWindow);
        }
        return bounds;
    }

    // seed the store from the legacy bounds without moving the window, so it opens in place on the next startup
    private void seedStartupWindowFromCookie(User user) {
        readLegacyCookieBounds(user).ifPresent(this::seedStartupWindow);
    }

    // read the window bounds from the unencrypted startup store (available before login)
    private Optional<BoundingBox> readStartupWindowBounds() {
        return stageBoundsFromCookie(StartupSettings.read(appDataDir()));
    }

    // read the window bounds from the legacy encrypted user cookie (migration source only)
    private Optional<BoundingBox> readLegacyCookieBounds(User user) {
        return stageBoundsFromCookie(user.getCookie());
    }

    private Optional<BoundingBox> stageBoundsFromCookie(Cookie cookie) {
        return cookie.getAsOptionalDouble(CookieKey.STAGE_X).flatMap(x ->
                cookie.getAsOptionalDouble(CookieKey.STAGE_Y).flatMap(y ->
                        cookie.getAsOptionalDouble(CookieKey.STAGE_W).flatMap(w ->
                                cookie.getAsOptionalDouble(CookieKey.STAGE_H).map(h -> new BoundingBox(x, y, w, h)))));
    }

    private void seedStartupWindow(BoundingBox bounds) {
        writeStartupWindowBounds(bounds.getMinX(), bounds.getMinY(), bounds.getWidth(), bounds.getHeight());
    }

    // persist the window bounds to the startup store (single source of truth) so it opens in place next login
    private void writeStartupWindowBounds(double x, double y, double width, double height) {
        Cookie updates = new Cookie();
        updates.putAsDouble(CookieKey.STAGE_X, x);
        updates.putAsDouble(CookieKey.STAGE_Y, y);
        updates.putAsDouble(CookieKey.STAGE_W, width);
        updates.putAsDouble(CookieKey.STAGE_H, height);
        StartupSettings.write(appDataDir(), updates);
    }

    // apply saved window bounds, ignoring them if they are entirely off-screen (e.g. a monitor was disconnected)
    private boolean applyStageBounds(Stage stage, BoundingBox bounds) {
        boolean onScreen = Screen.getScreens().stream().anyMatch(screen ->
                screen.getVisualBounds().intersects(bounds.getMinX(), bounds.getMinY(), bounds.getWidth(), bounds.getHeight()));
        if (!onScreen) return false;
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
        stage.setWidth(bounds.getWidth());
        stage.setHeight(bounds.getHeight());
        return true;
    }

    private void persistStageBounds(Stage stage) {
        writeStartupWindowBounds(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
    }

    private void addStageLayoutListeners(Stage stage) {
        ChangeListener<Number> boundsChangeListener = (observable, oldValue, newValue) -> {
            // debounce the store write since these fire on every pixel of a drag/resize
            if (stageBoundsPersistenceTimer != null) stageBoundsPersistenceTimer.stop();
            stageBoundsPersistenceTimer = UserThread.runAfter(() -> writeStartupWindowBounds(
                    stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()), 500, TimeUnit.MILLISECONDS);
        };
        stage.widthProperty().addListener(boundsChangeListener);
        stage.heightProperty().addListener(boundsChangeListener);
        stage.xProperty().addListener(boundsChangeListener);
        stage.yProperty().addListener(boundsChangeListener);
    }

    private File appDataDir() {
        return injector.getInstance(Config.class).appDataDir;
    }

    private MainView loadMainView(Injector injector) {
        CachingViewLoader viewLoader = injector.getInstance(CachingViewLoader.class);
        return (MainView) viewLoader.load(MainView.class);
    }

    private void addSceneKeyEventHandler(Scene scene, Injector injector) {
        scene.addEventHandler(KeyEvent.KEY_RELEASED, keyEvent -> {
            if (Utilities.isCtrlPressed(KeyCode.W, keyEvent) ||
                    Utilities.isCtrlPressed(KeyCode.Q, keyEvent)) {
                shutDownByUser();
            } else {
                if (Utilities.isAltOrCtrlPressed(KeyCode.M, keyEvent)) {
                    injector.getInstance(SendAlertMessageWindow.class).show();
                } else if (Utilities.isAltOrCtrlPressed(KeyCode.F, keyEvent)) {
                    injector.getInstance(FilterWindow.class).show();
                }
		else if (Utilities.isAltOrCtrlPressed(KeyCode.T, keyEvent)) {
                    // Toggle between show tor logs and only show warnings. Helpful in case of connection problems
                    String pattern = "org.berndpruenster.netlayer";
                    Level logLevel = ((Logger) LoggerFactory.getLogger(pattern)).getLevel();
                    if (logLevel != Level.DEBUG) {
                        log.info("Set log level for org.berndpruenster.netlayer classes to DEBUG");
                        Log.setCustomLogLevel(pattern, Level.DEBUG);
                    } else {
                        log.info("Set log level for org.berndpruenster.netlayer classes to WARN");
                        Log.setCustomLogLevel(pattern, Level.WARN);
                    }
                } else if (Utilities.isAltOrCtrlPressed(KeyCode.J, keyEvent)) {
                    WalletsManager walletsManager = injector.getInstance(WalletsManager.class);
                    if (walletsManager.areWalletsAvailable())
                        new ShowWalletDataWindow(walletsManager).show();
                    else
                        new Popup().warning(Res.get("popup.warning.walletNotInitialized")).show();
                } else if (DevEnv.isDevMode()) {
                    if (Utilities.isAltOrCtrlPressed(KeyCode.Z, keyEvent))
                        showDebugWindow(scene, injector);
                }
            }
        });
    }

    private void shutDownByUser() {

        // services are not initialized before login, so shut down directly without prompting
        if (mainView == null) {
            stop();
            return;
        }

        promptUserAtShutdown().thenAccept(okToShutDown -> {
            if (okToShutDown) {
                stop();
            }
        });
    }

    private CompletableFuture<Boolean> promptUserAtShutdown() {
        final CompletableFuture<Boolean> resp = new CompletableFuture<>();

        // check for trade or dispute issues
        String issues = checkTradesAtShutdown() + checkDisputesAtShutdown();
        if (issues.length() > 0) {
            String key = Utilities.encodeToHex(Hash.getSha256Hash(issues));
            if (injector.getInstance(Preferences.class).showAgain(key) && !DevEnv.isDevMode()) {
                new Popup().warning(issues)
                        .actionButtonText(Res.get("shared.okWait"))
                        .onAction(() -> resp.complete(false))
                        .closeButtonText(Res.get("shared.closeAnywayDanger"))
                        .onClose(() -> resp.complete(true))
                        .dontShowAgainId(key)
                        .width(800)
                        .show();
                return resp;
            }
        }

        // check for open offers
        if (injector.getInstance(OpenOfferManager.class).hasAvailableOpenOffers()) {
            String key = "showOpenOfferWarnPopupAtShutDown";
            if (injector.getInstance(Preferences.class).showAgain(key) && !DevEnv.isDevMode()) {
                new Popup().warning(Res.get("popup.info.shutDownWithOpenOffers"))
                        .actionButtonText(Res.get("shared.shutDown"))
                        .onAction(() -> resp.complete(true))
                        .closeButtonText(Res.get("shared.cancel"))
                        .onClose(() -> resp.complete(false))
                        .dontShowAgainId(key)
                        .show();
                return resp;
            }
        }

        // if no warning popup has been shown yet, prompt user if they really intend to shut down
        String key = "popup.info.shutDownQuery";
        if (injector.getInstance(Preferences.class).showAgain(key) && !DevEnv.isDevMode()) {
            new Popup().headLine(Res.get(key))
                    .actionButtonText(Res.get("shared.yes"))
                    .onAction(() -> resp.complete(true))
                    .closeButtonText(Res.get("shared.no"))
                    .onClose(() -> resp.complete(false))
                    .dontShowAgainId(key)
                    .show();
        } else {
            resp.complete(true);
        }
        return resp;
    }

    private String checkTradesAtShutdown() {
       log.info("Checking trades at shutdown");
       Instant fiveMinutesAgo = Instant.ofEpochSecond(Instant.now().getEpochSecond() - TimeUnit.MINUTES.toSeconds(5));
       for (Trade trade : injector.getInstance(TradeManager.class).getObservableList()) {
           if (trade.getPhase().equals(Trade.Phase.DEPOSIT_REQUESTED) &&
                   trade.getTakeOfferDate().toInstant().isAfter(fiveMinutesAgo)) {
               String tradeDateString = DisplayUtils.formatDateTime(trade.getTakeOfferDate());
               String tradeInfo = Res.get("shared.tradeId") + ": " + trade.getShortId() + " " +
                       Res.get("shared.dateTime") + ": " + tradeDateString;
               return Res.get("popup.info.shutDownWithTradeInit", tradeInfo) + System.lineSeparator() + System.lineSeparator();
           }
       }
       return "";
    }

    private String checkDisputesAtShutdown() {
        log.info("Checking disputes at shutdown");
        if (injector.getInstance(ArbitrationManager.class).hasPendingMessageAtShutdown() ||
                injector.getInstance(MediationManager.class).hasPendingMessageAtShutdown() ||
                injector.getInstance(RefundManager.class).hasPendingMessageAtShutdown()) {
            return Res.get("popup.info.shutDownWithDisputeInit") + System.lineSeparator() + System.lineSeparator();
        }
        return "";
    }

    // Used for debugging trade process
    private void showDebugWindow(Scene scene, Injector injector) {
        ViewLoader viewLoader = injector.getInstance(ViewLoader.class);
        View debugView = viewLoader.load(DebugView.class);
        Parent parent = (Parent) debugView.getRoot();
        Stage stage = new Stage();
        stage.setScene(new Scene(parent));
        stage.setTitle("Debug window"); // Don't translate, just for dev
        stage.initModality(Modality.NONE);
        stage.initStyle(StageStyle.UTILITY);
        stage.initOwner(scene.getWindow());
        stage.setX(this.stage.getX() + this.stage.getWidth() + 10);
        stage.setY(this.stage.getY());
        stage.show();
    }
}
