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

package haveno.desktop.main.overlays.notifications;

import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.app.DevEnv;
import haveno.core.locale.Res;
import haveno.desktop.main.MainView;
import haveno.desktop.main.overlays.Overlay;
import haveno.desktop.util.CssTheme;
import haveno.desktop.util.FormBuilder;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.event.EventTarget;
import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

public class Notification extends Overlay<Notification> {
    private static final int AUTO_CLOSE_MILLIS = 6000;
    private static final int CARD_INSET = 10;
    private static final int SHADOW_INSET = 44;

    private boolean hasBeenDisplayed;
    private boolean autoClose;
    private boolean displayReady;
    private boolean closing;
    private boolean suspended;
    protected StackPane notificationPane;
    private Scene ownerScene;
    private Window ownerWindow;
    private final Timeline notificationAnimation = new Timeline();
    private final ChangeListener<Number> sizeListener = (observable, oldValue, newValue) -> refitToContent();
    private final ChangeListener<Boolean> demandListener = (observable, oldValue, needsLayout) -> {
        if (needsLayout) UserThread.execute(this::refitIfDemandChanged);
    };
    private final ChangeListener<Boolean> showingListener = (observable, oldValue, showing) -> {
        if (!showing) hide();
    };
    private final ChangeListener<Boolean> inputBlockedListener = (observable, oldValue, blocked) -> {
        if (notificationPane != null) notificationPane.setVisible(!blocked);
        if (blocked) pauseAutoCloseTimer();
        else startAutoCloseTimer();
    };
    private Timer autoCloseTimer;
    private long autoCloseGeneration;
    private BooleanSupplier displayCondition = () -> true;

    public Notification() {
        width = 379; // 325 visible bg because of insets
        type = Type.Notification;
    }

    void onReadyForDisplay() {
        if (closing) return;
        if (!displayCondition.getAsBoolean()) {
            hide();
            return;
        }
        if (suspended) {
            suspended = false;
            // rebuild the content because a previous display may have wrapped it in a scroll pane
            rowIndex = -1;
            createContent(false);
        }
        if (!isDisplayed) display();
        if (!isDisplayed) hide();
        else if (displayReady) startAutoCloseTimer();
    }

    @Override
    public void hide() {
        if (closing) return;
        closing = true;
        if (isDisplayed) animateHide();
        else onHidden();
    }

    void suspend() {
        if (closing || suspended) return;
        suspended = true;
        displayReady = false;
        pauseAutoCloseTimer();
        animateHide();
    }

    @Override
    protected void onShow() {
        if (!isDisplayed) {
            closing = false;
            suspended = false;
            displayReady = false;
            isHiddenProperty.set(false);
        }
        NotificationManager.show(this);
    }

    @Override
    protected void onHidden() {
        if (closing) onDiscarded();
        NotificationManager.onHidden(this);
    }

    void onDiscarded() {
        suspended = false;
        pauseAutoCloseTimer();
        closing = true;
        isHiddenProperty.set(true);
    }

    public Notification tradeHeadLine(String tradeId) {
        return headLine(Res.get("notification.trade.headline", tradeId));
    }

    public Notification disputeHeadLine(String tradeId) {
        return headLine(Res.get("notification.ticket.headline", tradeId));
    }

    @Override
    public void show() {
        if (DevEnv.isDevMode()) {
            return;
        }
        super.show();
        hasBeenDisplayed = true;
    }


    public Notification autoClose() {
        autoClose = true;
        return this;
    }

    @Override
    public Notification message(String message) {
        super.message(message);
        if (messageTextArea != null) messageTextArea.setText(truncatedMessage);
        pauseAutoCloseTimer();
        NotificationManager.onUpdated(this);
        if (NotificationManager.isCurrent(this)) startAutoCloseTimer();
        return this;
    }

    public Notification onlyShowIf(BooleanSupplier condition) {
        displayCondition = condition;
        return this;
    }

    @Override
    protected void animateHide(Runnable onFinishedHandler) {
        pauseAutoCloseTimer();
        animateNotification(0, 180, onFinishedHandler);
    }

    @Override
    protected void animateDisplay() {
        notificationPane.setOpacity(0);
        getDisplayContainer().setTranslateX(getDuration(240) > 1 ? 10 : 0);
        animateNotification(1, 240, null);
        displayReady = true;
        startAutoCloseTimer();
    }

    private void animateNotification(double opacity, double duration, Runnable onFinishedHandler) {
        notificationAnimation.stop();
        if (notificationPane == null || (ownerWindow != null && !ownerWindow.isShowing()) || getDuration(duration) <= 1) {
            if (notificationPane != null) {
                notificationPane.setOpacity(opacity);
                getDisplayContainer().setTranslateX(0);
            }
            if (onFinishedHandler != null) onFinishedHandler.run();
            return;
        }
        notificationAnimation.getKeyFrames().setAll(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(notificationPane.opacityProperty(), notificationPane.getOpacity()),
                        new KeyValue(getDisplayContainer().translateXProperty(), getDisplayContainer().getTranslateX())),
                new KeyFrame(Duration.millis(getDuration(duration)),
                        new KeyValue(notificationPane.opacityProperty(), opacity, Interpolator.EASE_BOTH),
                        new KeyValue(getDisplayContainer().translateXProperty(), 0, Interpolator.EASE_BOTH)));
        notificationAnimation.setOnFinished(event -> {
            if (onFinishedHandler != null) onFinishedHandler.run();
        });
        notificationAnimation.play();
    }

    private void startAutoCloseTimer() {
        if (!autoClose || !displayReady || closing || !NotificationManager.isCurrent(this) || autoCloseTimer != null) return;
        if (owner != null && owner.isMouseTransparent()) return;
        long generation = ++autoCloseGeneration;
        autoCloseTimer = UserThread.runAfter(() -> {
            // a stopped timer may already have queued its callback on the user thread
            if (generation != autoCloseGeneration || !NotificationManager.isCurrent(this)) return;
            doClose();
        }, AUTO_CLOSE_MILLIS, TimeUnit.MILLISECONDS);
    }

    void pauseAutoCloseTimer() {
        autoCloseGeneration++;
        if (autoCloseTimer != null) {
            autoCloseTimer.stop();
            autoCloseTimer = null;
        }
    }

    boolean isClosing() {
        return closing;
    }

    @Override
    public void display() {
        if (isDisplayed) return;
        if (owner == null) owner = MainView.getRootContainer();
        if (owner == null || owner.getScene() == null || owner.getScene().getWindow() == null ||
                !owner.getScene().getWindow().isShowing()) return;
        long generation = startDisplay();
        UserThread.execute(() -> {
            if (isDisplayStale(generation)) return;
            ownerScene = owner.getScene();
            if (ownerScene == null || ownerScene.getWindow() == null || !ownerScene.getWindow().isShowing()) {
                hide();
                return;
            }
            ownerWindow = ownerScene.getWindow();
            // a scene-graph card cannot activate or reorder native windows
            notificationPane = new StackPane(getRootContainer());
            notificationPane.setMinSize(0, 0);
            notificationPane.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            notificationPane.setPickOnBounds(false);
            // defer notifications while a dialog blurs and blocks the application content
            notificationPane.setVisible(!owner.isMouseTransparent());
            owner.mouseTransparentProperty().addListener(inputBlockedListener);
            notificationPane.getProperties().put(Notification.class, true);
            setupKeyHandler(ownerScene);
            owner.getChildren().add(notificationPane);
            notificationPane.applyCss();
            notificationPane.autosize();
            notificationPane.layout();
            constrainToScreen(null);
            if (!CssTheme.isDarkTheme()) getDisplayContainer().getStyleClass().add("popup-dropshadow");
            layout();
            ownerScene.widthProperty().addListener(sizeListener);
            ownerScene.heightProperty().addListener(sizeListener);
            ownerWindow.showingProperty().addListener(showingListener);
            getRootContainer().needsLayoutProperty().addListener(demandListener);
            animateDisplay();
        });
    }

    @Override
    protected void setSceneRoot(Scene scene, Parent root) {
        notificationPane.getChildren().setAll(root);
    }

    @Override
    protected void setupKeyHandler(Scene scene) {
        notificationPane.setOnKeyPressed(event -> {
            if (!hideCloseButton && event.getCode() == KeyCode.ESCAPE) {
                event.consume();
                doClose();
            } else if (event.getCode() == KeyCode.ENTER && !event.isAltDown() && !event.isControlDown() &&
                    !event.isMetaDown() && !event.isShiftDown()) {
                // on macOS a non-default button lets Enter reach the underlying scene's default action
                event.consume();
                if (event.getTarget() instanceof ButtonBase button) button.fire();
                else if (actionButton != null && !actionButton.isDisabled()) actionButton.fire();
                else if (!hideCloseButton) doClose();
            }
        });
    }

    static boolean isNotificationTarget(EventTarget target) {
        while (target instanceof Node node) {
            if (node.hasProperties() && node.getProperties().containsKey(Notification.class)) return true;
            target = node.getParent();
        }
        return false;
    }

    @Override
    protected void cleanup() {
        notificationAnimation.stop();
        owner.mouseTransparentProperty().removeListener(inputBlockedListener);
        getRootContainer().needsLayoutProperty().removeListener(demandListener);
        if (ownerScene != null) {
            ownerScene.widthProperty().removeListener(sizeListener);
            ownerScene.heightProperty().removeListener(sizeListener);
            ownerScene = null;
        }
        if (ownerWindow != null) {
            ownerWindow.showingProperty().removeListener(showingListener);
            ownerWindow = null;
        }
        owner.getChildren().remove(notificationPane);
        notificationPane = null;
    }

    @Override
    public Notification onAction(Runnable actionHandler) {
        return super.onAction(() -> {
            focusOwner();
            actionHandler.run();
        });
    }

    @Override
    public Notification onSecondaryAction(Runnable actionHandler) {
        return super.onSecondaryAction(() -> {
            focusOwner();
            actionHandler.run();
        });
    }

    private void focusOwner() {
        Window window = owner.getScene().getWindow();
        if (window instanceof Stage) ((Stage) window).toFront();
        window.requestFocus();
    }

    @Override
    protected double getDuration(double duration) {
        return NotificationCenter.useAnimations ? super.getDuration(duration) : 1;
    }


    @Override
    protected Insets getCardInsets() {
        // an RTL root is mirrored, so the trimmed side is the logical left
        boolean rtl = gridPane.getEffectiveNodeOrientation() == NodeOrientation.RIGHT_TO_LEFT;
        return new Insets(CARD_INSET, rtl ? SHADOW_INSET : CARD_INSET,
                SHADOW_INSET, rtl ? CARD_INSET : SHADOW_INSET);
    }

    @Override
    protected void addButtons() {
        buttonDistance = 10;
        super.addButtons();
        // embedded notifications must not register Enter accelerators on the application's scene
        if (actionButton != null) actionButton.setDefaultButton(false);
        if (closeButton != null) closeButton.setDefaultButton(false);
        if (messageTextArea != null) messageTextArea.setFocusTraversable(false);
    }

    @Override
    protected void applyStyles() {
        Insets insets = getCardInsets();
        gridPane.setPadding(new Insets(insets.getTop() + 18, insets.getRight() + 18,
                insets.getBottom() + 18, insets.getLeft() + 18));
        gridPane.getStyleClass().add("notification-popup-bg");
        if (headLineLabel != null)
            headLineLabel.getStyleClass().add("notification-popup-headline");

        headlineIcon.getStyleClass().add("popup-icon-information");
        headlineIcon.setManaged(true);
        headlineIcon.setVisible(true);
        headlineIcon.setPadding(new Insets(1));
        FormBuilder.getIconForLabel(FontAwesomeIcon.INFO_CIRCLE, headlineIcon, "1em");
        if (actionButton != null)
            actionButton.getStyleClass().add("compact-button");
    }

    @Override
    protected void layout() {
        if (notificationPane == null) return;
        // StackPane mirrors alignment in RTL; keep the card at the visual right edge
        StackPane.setAlignment(notificationPane, owner.getEffectiveNodeOrientation() == NodeOrientation.RIGHT_TO_LEFT ?
                Pos.TOP_LEFT : Pos.TOP_RIGHT);
    }

    @Override
    protected void addEffectToBackground() {
    }

    @Override
    protected void removeEffectFromBackground() {
    }

    public boolean isHasBeenDisplayed() {
        return hasBeenDisplayed;
    }
}
