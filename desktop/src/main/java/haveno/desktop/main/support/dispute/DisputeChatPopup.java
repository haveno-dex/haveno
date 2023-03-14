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

package haveno.desktop.main.support.dispute;

import haveno.common.UserThread;
import haveno.core.locale.Res;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.DisputeList;
import haveno.core.support.dispute.DisputeManager;
import haveno.core.support.dispute.DisputeSession;
import haveno.core.user.Preferences;
import haveno.core.util.coin.CoinFormatter;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.main.MainView;
import haveno.desktop.main.shared.ChatView;
import haveno.desktop.util.CssTheme;
import javafx.beans.value.ChangeListener;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import lombok.Getter;

public class DisputeChatPopup {
    public interface ChatCallback {
        void onCloseDisputeFromChatWindow(Dispute dispute);
    }

    private Stage chatPopupStage;
    protected final DisputeManager<? extends DisputeList<Dispute>> disputeManager;
    protected final CoinFormatter formatter;
    protected final Preferences preferences;
    private ChatCallback chatCallback;
    private double chatPopupStageXPosition = -1;
    private double chatPopupStageYPosition = -1;
    private ChangeListener<Number> xPositionListener;
    private ChangeListener<Number> yPositionListener;
    @Getter private Dispute selectedDispute;

    DisputeChatPopup(DisputeManager<? extends DisputeList<Dispute>> disputeManager,
                     CoinFormatter formatter,
                     Preferences preferences,
                     ChatCallback chatCallback) {
        this.disputeManager = disputeManager;
        this.formatter = formatter;
        this.preferences = preferences;
        this.chatCallback = chatCallback;
    }

    public boolean isChatShown() {
        return chatPopupStage != null;
    }

    public void closeChat() {
        if (chatPopupStage != null)
            chatPopupStage.close();
        selectedDispute = null;
    }

    public void openChat(Dispute selectedDispute, DisputeSession concreteDisputeSession, String counterpartyName) {
        closeChat();
        this.selectedDispute = selectedDispute;
        selectedDispute.getChatMessages().forEach(m -> m.setWasDisplayed(true));
        disputeManager.requestPersistence();

        ChatView chatView = new ChatView(disputeManager, formatter, counterpartyName);
        chatView.setAllowAttachments(true);
        chatView.setDisplayHeader(false);
        chatView.initialize();

        AnchorPane pane = new AnchorPane(chatView);
        pane.setPrefSize(760, 500);
        AnchorPane.setLeftAnchor(chatView, 10d);
        AnchorPane.setRightAnchor(chatView, 10d);
        AnchorPane.setTopAnchor(chatView, -20d);
        AnchorPane.setBottomAnchor(chatView, 10d);
        pane.getStyleClass().add("dispute-chat-border");
        Button closeDisputeButton = null;
        if (!selectedDispute.isClosed() && !disputeManager.isTrader(selectedDispute)) {
            closeDisputeButton = new AutoTooltipButton(Res.get("support.closeTicket"));
            closeDisputeButton.setOnAction(e -> chatCallback.onCloseDisputeFromChatWindow(selectedDispute));
        }
        chatView.display(concreteDisputeSession, closeDisputeButton, pane.widthProperty());
        chatView.activate();
        chatView.scrollToBottom();
        chatPopupStage = new Stage();
        chatPopupStage.setTitle(Res.get("disputeChat.chatWindowTitle", selectedDispute.getShortTradeId())
                + " " + selectedDispute.getRoleString());
        StackPane owner = MainView.getRootContainer();
        Scene rootScene = owner.getScene();
        chatPopupStage.initOwner(rootScene.getWindow());
        chatPopupStage.initModality(Modality.NONE);
        chatPopupStage.initStyle(StageStyle.DECORATED);
        chatPopupStage.setOnHiding(event -> {
            chatView.deactivate();
            // at close we set all as displayed. While open we ignore updates of the numNewMsg in the list icon.
            selectedDispute.getChatMessages().forEach(m -> m.setWasDisplayed(true));
            disputeManager.requestPersistence();
            chatPopupStage = null;
        });

        Scene scene = new Scene(pane);
        CssTheme.loadSceneStyles(scene, preferences.getCssTheme(), false);
        scene.addEventHandler(KeyEvent.KEY_RELEASED, ev -> {
            if (ev.getCode() == KeyCode.ESCAPE) {
                ev.consume();
                chatPopupStage.hide();
            }
        });
        chatPopupStage.setScene(scene);
        chatPopupStage.setOpacity(0);
        chatPopupStage.show();

        xPositionListener = (observable, oldValue, newValue) -> chatPopupStageXPosition = (double) newValue;
        chatPopupStage.xProperty().addListener(xPositionListener);
        yPositionListener = (observable, oldValue, newValue) -> chatPopupStageYPosition = (double) newValue;
        chatPopupStage.yProperty().addListener(yPositionListener);

        if (chatPopupStageXPosition == -1) {
            Window rootSceneWindow = rootScene.getWindow();
            double titleBarHeight = rootSceneWindow.getHeight() - rootScene.getHeight();
            chatPopupStage.setX(Math.round(rootSceneWindow.getX() + (owner.getWidth() - chatPopupStage.getWidth() / 4 * 3)));
            chatPopupStage.setY(Math.round(rootSceneWindow.getY() + titleBarHeight + (owner.getHeight() - chatPopupStage.getHeight() / 4 * 3)));
        } else {
            chatPopupStage.setX(chatPopupStageXPosition);
            chatPopupStage.setY(chatPopupStageYPosition);
        }

        // Delay display to next render frame to avoid that the popup is first quickly displayed in default position
        // and after a short moment in the correct position
        UserThread.execute(() -> chatPopupStage.setOpacity(1));
    }
}
