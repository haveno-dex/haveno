/*
 * This file is part of haveno.
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
 * along with haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.desktop.main.support.dispute;

import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.main.MainView;
import haveno.desktop.main.shared.ChatView;
import haveno.desktop.util.CssTheme;
import haveno.desktop.util.DisplayUtils;
import haveno.desktop.util.GUIUtil;

import haveno.core.locale.Res;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.DisputeList;
import haveno.core.support.dispute.DisputeManager;
import haveno.core.support.dispute.DisputeSession;
import haveno.core.support.messages.ChatMessage;
import haveno.core.user.Preferences;
import haveno.core.util.coin.CoinFormatter;

import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.AnchorPane;

import java.util.Date;
import java.util.List;

import lombok.Getter;

public class DisputeChatPopup {
    public interface ChatCallback {
        void onCloseDisputeFromChatWindow(Dispute dispute);
    }

    private Stage chatPopupStage;
    protected final DisputeManager<? extends DisputeList<Dispute>> disputeManager;
    protected final CoinFormatter formatter;
    protected final Preferences preferences;
    private final ChatCallback chatCallback;
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

        ChatView chatView = new ChatView(disputeManager, counterpartyName);
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
        if (selectedDispute.isClosed()) {
            chatView.display(concreteDisputeSession, null, pane.widthProperty());
        } else {
            if (disputeManager.isAgent(selectedDispute)) {
                Button closeDisputeButton = new AutoTooltipButton(Res.get("support.closeTicket"));
                closeDisputeButton.setDefaultButton(true);
                closeDisputeButton.setOnAction(e -> chatCallback.onCloseDisputeFromChatWindow(selectedDispute));
                chatView.display(concreteDisputeSession, closeDisputeButton, pane.widthProperty());
            } else {
                Button uploadChatButton = new AutoTooltipButton(Res.get("support.uploadTraderChat"));
                uploadChatButton.setOnAction(e -> doTextAttachment(chatView));
                setChatUploadEnabledState(uploadChatButton);
                chatView.display(concreteDisputeSession, uploadChatButton, pane.widthProperty());
            }
        }
        chatView.activate();
        chatView.scrollToBottom();
        chatPopupStage = new Stage();
        chatPopupStage.setTitle(Res.get("disputeChat.chatWindowTitle", selectedDispute.getShortTradeId())
                + " " + selectedDispute.getRoleString());
        Scene rootScene = MainView.getRootContainer().getScene();

        // keep a top-level window so the WM shows maximize/fullscreen
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
        GUIUtil.showCenteredChatWindow(chatPopupStage, rootScene);
    }

    private void doTextAttachment(ChatView chatView) {
        disputeManager.findTrade(selectedDispute).ifPresent(t -> {
            List<ChatMessage> chatMessages = t.getChatMessages();
            if (chatMessages.size() > 0) {
                StringBuilder stringBuilder = new StringBuilder();
                chatMessages.forEach(i -> {
                    boolean isMyMsg = i.isSenderIsTrader();
                    String metaData = DisplayUtils.formatDateTime(new Date(i.getDate()));
                    if (!i.isSystemMessage())
                        metaData = (isMyMsg ? "Sent " : "Received ") + metaData
                                + (isMyMsg ? "" : " from Trader");
                    stringBuilder.append(metaData).append("\n").append(i.getMessage()).append("\n\n");
                });
                String fileName = selectedDispute.getShortTradeId() + "_" + selectedDispute.getRoleStringForLogFile() + "_TraderChat.txt";
                chatView.onAttachText(stringBuilder.toString(), fileName);
            }
        });
    }

    private void setChatUploadEnabledState(Button button) {
        disputeManager.findTrade(selectedDispute).ifPresentOrElse(t -> {
            button.setDisable(t.getChatMessages().size() == 0);
        }, () -> {
            button.setDisable(true);
        });
    }
}
