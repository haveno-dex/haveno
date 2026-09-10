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
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.Res;
import haveno.desktop.main.overlays.Overlay;
import haveno.desktop.util.FormBuilder;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.stage.Modality;
import javafx.stage.Window;
import javafx.util.Duration;

public class Notification extends Overlay<Notification> {
    private boolean hasBeenDisplayed;
    private boolean autoClose;
    private Timer autoCloseTimer;
    private final Timeline animation = new Timeline();
    private static final int BORDER_PADDING = 10;

    public Notification() {
        width = 413; // 320 visible bg because of insets
        NotificationCenter.add(this);
        type = Type.Notification;
    }

    void onReadyForDisplay() {
        super.display();

        if (autoClose && autoCloseTimer == null)
            autoCloseTimer = UserThread.runAfter(this::doClose, 6);

        UserThread.execute(() -> {
            stage.addEventHandler(MouseEvent.MOUSE_PRESSED, (event) -> doClose());
        });
    }

    @Override
    public void hide() {
        if (gridPane != null)
            animateHide();
    }

    @Override
    protected void onShow() {
        NotificationManager.queueForDisplay(this);
    }

    @Override
    protected void onHidden() {
        NotificationManager.onHidden(this);
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
    protected void animateHide(Runnable onFinishedHandler) {
        if (autoCloseTimer != null) {
            autoCloseTimer.stop();
            autoCloseTimer = null;
        }

        Region rootContainer = getDisplayContainer();
        double startX = rootContainer.getTranslateX();
        double startOpacity = rootContainer.getOpacity();
        animation.stop();
        rootContainer.setTranslateX(startX);
        rootContainer.setOpacity(startOpacity);

        if (NotificationCenter.useAnimations && useAnimation && GlobalSettings.getUseAnimations()) {
            Interpolator interpolator = Interpolator.SPLINE(0.4, 0, 1, 1);
            // continue from the current pose if dismissed during the entrance
            animation.getKeyFrames().setAll(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(rootContainer.translateXProperty(), startX),
                            new KeyValue(rootContainer.opacityProperty(), startOpacity)),
                    new KeyFrame(Duration.millis(140),
                            new KeyValue(rootContainer.translateXProperty(), 16, interpolator),
                            new KeyValue(rootContainer.opacityProperty(), 0, interpolator)));
            animation.setOnFinished(event -> onFinishedHandler.run());
            animation.play();
        } else {
            onFinishedHandler.run();
        }
    }

    @Override
    protected void animateDisplay() {
        animation.stop();
        animation.setOnFinished(null);
        getRootContainer().setOpacity(1); // undo the pre-show hide
        Region rootContainer = getDisplayContainer();
        rootContainer.setTranslateX(0);
        rootContainer.setOpacity(1);

        if (NotificationCenter.useAnimations && useAnimation && GlobalSettings.getUseAnimations()) {
            Interpolator interpolator = Interpolator.SPLINE(0, 0, 0.2, 1);
            rootContainer.setTranslateX(16);
            rootContainer.setOpacity(0);
            animation.getKeyFrames().setAll(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(rootContainer.translateXProperty(), 16),
                            new KeyValue(rootContainer.opacityProperty(), 0)),
                    new KeyFrame(Duration.millis(200),
                            new KeyValue(rootContainer.translateXProperty(), 0, interpolator),
                            new KeyValue(rootContainer.opacityProperty(), 1, interpolator)));
            animation.play();
        }
    }


    @Override
    protected void createGridPane() {
        super.createGridPane();
        gridPane.setPadding(new Insets(62, 62, 62, 62));
    }

    @Override
    protected void addButtons() {
        buttonDistance = 10;
        super.addButtons();
    }

    @Override
    protected void applyStyles() {
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
        Window window = owner.getScene().getWindow();
        double titleBarHeight = window.getHeight() - owner.getScene().getHeight();
        double shadowInset = 44;
        stage.setX(Math.round(window.getX() + window.getWidth() + shadowInset - stage.getWidth() - BORDER_PADDING));
        stage.setY(Math.round(window.getY() + titleBarHeight - shadowInset + BORDER_PADDING));
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
