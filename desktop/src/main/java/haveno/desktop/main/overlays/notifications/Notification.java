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
import haveno.desktop.main.overlays.Overlay;
import haveno.desktop.util.FormBuilder;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

public class Notification extends Overlay<Notification> {
    private static final int AUTO_CLOSE_MILLIS = 6000;
    private static final int CARD_INSET = 10;
    private static final int SHADOW_INSET = 44;

    private boolean hasBeenDisplayed;
    private boolean autoClose;
    private boolean displayReady;
    private boolean closing;
    private boolean suspended;
    private Timer autoCloseTimer;
    private long autoCloseGeneration;
    private BooleanSupplier displayCondition = () -> true;

    public Notification() {
        width = 379; // 325 visible bg because of insets
        NotificationCenter.add(this);
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
        super.animateHide(() -> runWithPreservedFocus(onFinishedHandler, null));
    }

    @Override
    protected void animateDisplay() {
        super.animateDisplay();
        displayReady = true;
        startAutoCloseTimer();
    }

    private void startAutoCloseTimer() {
        if (!autoClose || !displayReady || closing || !NotificationManager.isCurrent(this) || autoCloseTimer != null) return;
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

    boolean ownsWindow(Window window) {
        return stage == window;
    }

    private static void runWithPreservedFocus(Runnable action, Window excludedWindow) {
        Stage focusedWindow = Window.getWindows().stream()
                .filter(window -> window instanceof Stage && window != excludedWindow && window.isFocused() && !NotificationManager.isNotificationWindow(window))
                .map(window -> (Stage) window)
                .findFirst().orElse(null);
        action.run();
        // showing or hiding an owned stage can activate its owner on some window managers
        if (focusedWindow != null) {
            focusedWindow.toFront();
            focusedWindow.requestFocus();
        }
    }

    @Override
    protected void showStage() {
        runWithPreservedFocus(super::showStage, owner.getScene().getWindow());
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
    protected void setModality() {
        stage.initOwner(owner.getScene().getWindow());
        stage.initModality(Modality.NONE);
    }

    @Override
    protected void layout() {
        if (stage == null || !stage.isShowing()) return;
        Scene scene = owner.getScene();
        Window window = scene.getWindow();
        // the trimmed margins keep the card 10px from the corner without covering the title bar
        double x = Math.max(0, scene.getWidth() - stage.getWidth());
        stage.setX(Math.round(window.getX() + scene.getX() + x));
        stage.setY(Math.round(window.getY() + scene.getY()));
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
