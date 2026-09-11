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
import javafx.geometry.Insets;
import javafx.scene.input.MouseEvent;
import javafx.stage.Modality;
import javafx.stage.Window;

public class Notification extends Overlay<Notification> {
    private boolean hasBeenDisplayed;
    private boolean autoClose;
    private Timer autoCloseTimer;
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
            if (stage != null && stage.isShowing())
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

        super.animateHide(onFinishedHandler);
    }

    @Override
    protected double getDuration(double duration) {
        return NotificationCenter.useAnimations ? super.getDuration(duration) : 1;
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
