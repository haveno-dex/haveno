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

package haveno.desktop.main.shared;

import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.components.HavenoTextArea;
import haveno.desktop.components.BusyAnimation;
import haveno.desktop.components.TableGroupHeadline;
import haveno.desktop.main.overlays.notifications.Notification;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.util.DisplayUtils;
import haveno.desktop.util.GUIUtil;

import haveno.core.locale.Res;
import haveno.core.support.SupportManager;
import haveno.core.support.SupportSession;
import haveno.core.support.dispute.Attachment;
import haveno.core.support.messages.ChatMessage;

import haveno.network.p2p.network.Connection;

import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.util.Utilities;

import com.google.common.io.ByteStreams;

import de.jensd.fx.fontawesome.AwesomeDude;
import de.jensd.fx.fontawesome.AwesomeIcon;
import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIconView;
import javafx.stage.FileChooser;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;

import javafx.geometry.Insets;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;

import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.value.ChangeListener;

import javafx.event.EventHandler;

import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.SortedList;

import javafx.util.Callback;

import java.net.MalformedURLException;
import java.net.URL;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

@Slf4j
public class ChatView extends AnchorPane {

    // UI
    private TextArea inputTextArea;
    private Button sendButton;
    private ListView<ChatMessage> messageListView;
    private Label sendMsgInfoLabel;
    private BusyAnimation sendMsgBusyAnimation;
    private TableGroupHeadline tableGroupHeadline;
    private VBox messagesInputBox;

    // Options
    @Getter
    Node extraButton;
    @Getter
    private ReadOnlyDoubleProperty widthProperty;
    @Setter
    boolean allowAttachments;
    @Setter
    boolean displayHeader;

    // Communication stuff, to be renamed to something more generic
    private ChatMessage chatMessage;
    private ObservableList<ChatMessage> chatMessages;
    private ListChangeListener<ChatMessage> disputeDirectMessageListListener;
    private Subscription inputTextAreaTextSubscription;
    private final List<Attachment> tempAttachments = new ArrayList<>();
    private ChangeListener<Boolean> storedInMailboxPropertyListener, acknowledgedPropertyListener;
    private ChangeListener<String> sendMessageErrorPropertyListener;

    private EventHandler<KeyEvent> keyEventEventHandler;
    private SupportManager supportManager;
    private Optional<SupportSession> optionalSupportSession = Optional.empty();
    private String counterpartyName;

    public ChatView(SupportManager supportManager, String counterpartyName) {
        this.supportManager = supportManager;
        this.counterpartyName = counterpartyName;
        allowAttachments = true;
        displayHeader = true;
    }

    public void initialize() {
        disputeDirectMessageListListener = c -> scrollToBottom();

        keyEventEventHandler = event -> {
            if (Utilities.isAltOrCtrlPressed(KeyCode.ENTER, event)) {
                optionalSupportSession.ifPresent(supportSession -> {
                    if (supportSession.chatIsOpen() && inputTextArea.isFocused()) {
                        onTrySendMessage();
                    }
                });
            }
        };
    }

    public void activate() {
        addEventHandler(KeyEvent.KEY_RELEASED, keyEventEventHandler);
    }

    public void deactivate() {
        removeEventHandler(KeyEvent.KEY_RELEASED, keyEventEventHandler);
        removeListenersOnSessionChange();
    }

    public void display(SupportSession supportSession, ReadOnlyDoubleProperty widthProperty) {
        display(supportSession, null, widthProperty);
    }

    public void display(SupportSession supportSession,
                        @Nullable Node extraButton,
                        ReadOnlyDoubleProperty widthProperty) {
        optionalSupportSession = Optional.of(supportSession);
        removeListenersOnSessionChange();
        this.getChildren().clear();
        this.extraButton = extraButton;
        this.widthProperty = widthProperty;

        tableGroupHeadline = new TableGroupHeadline();
        tableGroupHeadline.setText(Res.get("support.messages"));

        AnchorPane.setTopAnchor(tableGroupHeadline, 10d);
        AnchorPane.setRightAnchor(tableGroupHeadline, 0d);
        AnchorPane.setBottomAnchor(tableGroupHeadline, 0d);
        AnchorPane.setLeftAnchor(tableGroupHeadline, 0d);

        chatMessages = supportSession.getObservableChatMessageList();
        SortedList<ChatMessage> sortedList = new SortedList<>(chatMessages);
        sortedList.setComparator(Comparator.comparing(o -> new Date(o.getDate())));
        messageListView = new ListView<>(sortedList);
        messageListView.setId("message-list-view");

        messageListView.setMinHeight(150);
        AnchorPane.setTopAnchor(messageListView, 30d);
        AnchorPane.setRightAnchor(messageListView, 0d);
        AnchorPane.setLeftAnchor(messageListView, 0d);

        VBox.setVgrow(this, Priority.ALWAYS);

        inputTextArea = new HavenoTextArea();
        inputTextArea.setPrefHeight(70);
        inputTextArea.setWrapText(true);
        inputTextArea.getStyleClass().add("input-with-border");

        if (!supportSession.isDisputeAgent()) {
            inputTextArea.setPromptText(Res.get("support.input.prompt"));
        }

        sendButton = new AutoTooltipButton(Res.get("support.send"));
        sendButton.setDefaultButton(true);
        sendButton.setOnAction(e -> onTrySendMessage());
        inputTextAreaTextSubscription = EasyBind.subscribe(inputTextArea.textProperty(), t -> sendButton.setDisable(t.isEmpty()));

        Button uploadButton = new AutoTooltipButton(Res.get("support.addAttachments"));
        uploadButton.setOnAction(e -> onRequestUpload());
        Button clipboardButton = new AutoTooltipButton(Res.get("shared.copyToClipboard"));
        clipboardButton.setOnAction(e -> copyChatMessagesToClipboard(clipboardButton));
        uploadButton.setStyle("-fx-pref-width: 125; -fx-padding: 3 3 3 3;");
        clipboardButton.setStyle("-fx-pref-width: 125; -fx-padding: 3 3 3 3;");

        sendMsgInfoLabel = new AutoTooltipLabel();
        sendMsgInfoLabel.setVisible(false);
        sendMsgInfoLabel.setManaged(false);
        sendMsgInfoLabel.setPadding(new Insets(5, 0, 0, 0));

        sendMsgBusyAnimation = new BusyAnimation(false);

        if (displayHeader)
            this.getChildren().add(tableGroupHeadline);

        if (supportSession.chatIsOpen()) {
            HBox buttonBox = new HBox();
            buttonBox.setSpacing(10);
            if (allowAttachments)
                buttonBox.getChildren().addAll(sendButton, uploadButton, clipboardButton, sendMsgBusyAnimation, sendMsgInfoLabel);
            else
                buttonBox.getChildren().addAll(sendButton, sendMsgBusyAnimation, sendMsgInfoLabel);

            if (extraButton != null) {
                Pane spacer = new Pane();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                buttonBox.getChildren().addAll(spacer, extraButton);
            }

            messagesInputBox = new VBox();
            messagesInputBox.setSpacing(10);
            messagesInputBox.getChildren().addAll(inputTextArea, buttonBox);
            VBox.setVgrow(buttonBox, Priority.ALWAYS);

            AnchorPane.setRightAnchor(messagesInputBox, 0d);
            AnchorPane.setBottomAnchor(messagesInputBox, 5d);
            AnchorPane.setLeftAnchor(messagesInputBox, 0d);

            AnchorPane.setBottomAnchor(messageListView, 120d);

            this.getChildren().addAll(messageListView, messagesInputBox);
        } else {
            AnchorPane.setBottomAnchor(messageListView, 0d);
            this.getChildren().add(messageListView);
        }

        messageListView.setCellFactory(new Callback<>() {
            @Override
            public ListCell<ChatMessage> call(ListView<ChatMessage> list) {
                return new ListCell<>() {
                    ChangeListener<Boolean> sendMsgBusyAnimationListener;
                    Pane bg = new Pane();
                    ImageView arrow = new ImageView();
                    Label headerLabel = new AutoTooltipLabel();
                    Label messageLabel = new AutoTooltipLabel();
                    Label copyLabel = new Label();
                    HBox attachmentsBox = new HBox();
                    AnchorPane messageAnchorPane = new AnchorPane();
                    Label statusIcon = new Label();
                    Label statusInfoLabel = new Label();
                    HBox statusHBox = new HBox();
                    double arrowWidth = 15d;
                    double attachmentsBoxHeight = 20d;
                    double border = 10d;
                    double bottomBorder = 25d;
                    double padding = border + 10d;
                    double msgLabelPaddingRight = padding + 20d;

                    {
                        bg.setMinHeight(30);
                        messageLabel.setWrapText(true);
                        headerLabel.setTextAlignment(TextAlignment.CENTER);
                        attachmentsBox.setSpacing(5);
                        statusIcon.getStyleClass().add("small-text");
                        statusInfoLabel.getStyleClass().add("small-text");
                        statusInfoLabel.setPadding(new Insets(3, 0, 0, 0));
                        copyLabel.setTooltip(new Tooltip(Res.get("shared.copyToClipboard")));
                        statusHBox.setSpacing(5);
                        statusHBox.getChildren().addAll(statusIcon, statusInfoLabel);
                        messageAnchorPane.getChildren().addAll(bg, arrow, headerLabel, messageLabel, copyLabel, attachmentsBox, statusHBox);
                    }

                    @Override
                    protected void updateItem(ChatMessage message, boolean empty) {
                        UserThread.execute(() -> {
                            super.updateItem(message, empty);
                            if (message != null && !empty) {
                                copyLabel.setOnMouseClicked(e -> Utilities.copyToClipboard(messageLabel.getText()));
                                messageLabel.setOnMouseClicked(event -> {
                                    if (2 > event.getClickCount()) {
                                        return;
                                    }
                                    GUIUtil.showSelectableTextModal(headerLabel.getText(), messageLabel.getText());
                                });
    
                                if (!messageAnchorPane.prefWidthProperty().isBound())
                                    messageAnchorPane.prefWidthProperty()
                                            .bind(messageListView.widthProperty().subtract(padding + GUIUtil.getScrollbarWidth(messageListView)));
    
                                AnchorPane.clearConstraints(bg);
                                AnchorPane.clearConstraints(headerLabel);
                                AnchorPane.clearConstraints(arrow);
                                AnchorPane.clearConstraints(messageLabel);
                                AnchorPane.clearConstraints(copyLabel);
                                AnchorPane.clearConstraints(statusHBox);
                                AnchorPane.clearConstraints(attachmentsBox);
    
                                AnchorPane.setTopAnchor(bg, 15d);
                                AnchorPane.setBottomAnchor(bg, bottomBorder);
                                AnchorPane.setTopAnchor(headerLabel, 0d);
                                AnchorPane.setBottomAnchor(arrow, bottomBorder + 5d);
                                AnchorPane.setTopAnchor(messageLabel, 25d);
                                AnchorPane.setTopAnchor(copyLabel, 25d);
                                AnchorPane.setBottomAnchor(attachmentsBox, bottomBorder + 10);
    
                                boolean senderIsTrader = message.isSenderIsTrader();
                                boolean isMyMsg = supportSession.isClient() == senderIsTrader;
    
                                arrow.setVisible(!message.isSystemMessage());
                                arrow.setManaged(!message.isSystemMessage());
                                statusHBox.setVisible(false);
    
                                headerLabel.getStyleClass().removeAll("message-header", "my-message-header", "success-text",
                                        "highlight-static");
                                messageLabel.getStyleClass().removeAll("my-message", "message");
                                copyLabel.getStyleClass().removeAll("my-message", "message");
    
                                if (message.isSystemMessage()) {
                                    headerLabel.getStyleClass().addAll("message-header", "success-text");
                                    bg.setId("message-bubble-green");
                                    messageLabel.getStyleClass().add("my-message");
                                    copyLabel.getStyleClass().add("my-message");
                                    message.addWeakMessageStateListener(() -> UserThread.execute(() -> updateMsgState(message)));
                                    updateMsgState(message);
                                } else if (isMyMsg) {
                                    headerLabel.getStyleClass().add("my-message-header");
                                    bg.setId("message-bubble-blue");
                                    messageLabel.getStyleClass().add("my-message");
                                    copyLabel.getStyleClass().add("my-message");
                                    if (supportSession.isClient())
                                        arrow.setId("bubble_arrow_blue_left");
                                    else
                                        arrow.setId("bubble_arrow_blue_right");
    
                                    if (sendMsgBusyAnimationListener != null)
                                        sendMsgBusyAnimation.isRunningProperty().removeListener(sendMsgBusyAnimationListener);
    
                                    sendMsgBusyAnimationListener = (observable, oldValue, newValue) -> {
                                        if (!newValue)
                                            UserThread.execute(() -> updateMsgState(message));
                                    };
    
                                    sendMsgBusyAnimation.isRunningProperty().addListener(sendMsgBusyAnimationListener);
                                    message.addWeakMessageStateListener(() -> UserThread.execute(() -> updateMsgState(message)));
                                    updateMsgState(message);
                                } else {
                                    headerLabel.getStyleClass().add("message-header");
                                    bg.setId("message-bubble-grey");
                                    messageLabel.getStyleClass().add("message");
                                    copyLabel.getStyleClass().add("message");
                                    if (supportSession.isClient())
                                        arrow.setId("bubble_arrow_grey_right");
                                    else
                                        arrow.setId("bubble_arrow_grey_left");
                                }
    
                                if (message.isSystemMessage()) {
                                    AnchorPane.setLeftAnchor(headerLabel, padding);
                                    AnchorPane.setRightAnchor(headerLabel, padding);
                                    AnchorPane.setLeftAnchor(bg, border);
                                    AnchorPane.setRightAnchor(bg, border);
                                    AnchorPane.setLeftAnchor(messageLabel, padding);
                                    AnchorPane.setRightAnchor(messageLabel, msgLabelPaddingRight);
                                    AnchorPane.setRightAnchor(copyLabel, padding);
                                    AnchorPane.setLeftAnchor(attachmentsBox, padding);
                                    AnchorPane.setRightAnchor(attachmentsBox, padding);
                                    AnchorPane.setLeftAnchor(statusHBox, padding);
                                } else if (senderIsTrader) {
                                    AnchorPane.setLeftAnchor(headerLabel, padding + arrowWidth);
                                    AnchorPane.setLeftAnchor(bg, border + arrowWidth);
                                    AnchorPane.setRightAnchor(bg, border);
                                    AnchorPane.setLeftAnchor(arrow, border);
                                    AnchorPane.setLeftAnchor(messageLabel, padding + arrowWidth);
                                    AnchorPane.setRightAnchor(messageLabel, msgLabelPaddingRight);
                                    AnchorPane.setRightAnchor(copyLabel, padding);
                                    AnchorPane.setLeftAnchor(attachmentsBox, padding + arrowWidth);
                                    AnchorPane.setRightAnchor(attachmentsBox, padding);
                                    AnchorPane.setRightAnchor(statusHBox, padding);
                                } else {
                                    AnchorPane.setRightAnchor(headerLabel, padding + arrowWidth);
                                    AnchorPane.setRightAnchor(bg, border + arrowWidth);
                                    AnchorPane.setLeftAnchor(bg, border);
                                    AnchorPane.setRightAnchor(arrow, border);
                                    AnchorPane.setLeftAnchor(messageLabel, padding);
                                    AnchorPane.setRightAnchor(messageLabel, msgLabelPaddingRight + arrowWidth);
                                    AnchorPane.setRightAnchor(copyLabel, padding + arrowWidth);
                                    AnchorPane.setLeftAnchor(attachmentsBox, padding);
                                    AnchorPane.setRightAnchor(attachmentsBox, padding + arrowWidth);
                                    AnchorPane.setLeftAnchor(statusHBox, padding);
                                }
                                AnchorPane.setBottomAnchor(statusHBox, 7d);
                                String metaData = DisplayUtils.formatDateTime(new Date(message.getDate()));
                                if (!message.isSystemMessage())
                                    metaData = (isMyMsg ? "Sent " : "Received ") + metaData
                                            + (isMyMsg ? "" : " from " + counterpartyName);
                                headerLabel.setText(metaData);
                                messageLabel.setText(message.getMessage());
                                attachmentsBox.getChildren().clear();
                                if (allowAttachments &&
                                        message.getAttachments() != null &&
                                        message.getAttachments().size() > 0) {
                                    AnchorPane.setBottomAnchor(messageLabel, bottomBorder + attachmentsBoxHeight + 10);
                                    attachmentsBox.getChildren().add(new AutoTooltipLabel(Res.get("support.attachments") + " ") {{
                                        setPadding(new Insets(0, 0, 3, 0));
                                        if (isMyMsg)
                                            getStyleClass().add("my-message");
                                        else
                                            getStyleClass().add("message");
                                    }});
                                    message.getAttachments().forEach(attachment -> {
                                        Label icon = new Label();
                                        setPadding(new Insets(0, 0, 3, 0));
                                        if (isMyMsg)
                                            icon.getStyleClass().add("attachment-icon");
                                        else
                                            icon.getStyleClass().add("attachment-icon-black");
    
                                        AwesomeDude.setIcon(icon, AwesomeIcon.FILE_TEXT);
                                        icon.setPadding(new Insets(-2, 0, 0, 0));
                                        icon.setTooltip(new Tooltip(attachment.getFileName()));
                                        icon.setOnMouseClicked(event -> onOpenAttachment(attachment));
                                        attachmentsBox.getChildren().add(icon);
                                    });
                                } else {
                                    AnchorPane.setBottomAnchor(messageLabel, bottomBorder + 10);
                                }
    
                                // Need to set it here otherwise style is not correct
                                copyLabel.getStyleClass().addAll("icon", "copy-icon-disputes");
                                MaterialDesignIconView copyIcon = new MaterialDesignIconView(MaterialDesignIcon.CONTENT_COPY, "16.0");
                                copyLabel.setGraphic(copyIcon);
    
                                // TODO There are still some cell rendering issues on updates
                                setGraphic(messageAnchorPane);
                            } else {
                                if (sendMsgBusyAnimation != null && sendMsgBusyAnimationListener != null)
                                    sendMsgBusyAnimation.isRunningProperty().removeListener(sendMsgBusyAnimationListener);
    
                                messageAnchorPane.prefWidthProperty().unbind();
    
                                copyLabel.setOnMouseClicked(null);
                                messageLabel.setOnMouseClicked(null);
                                setGraphic(null);
                            }
                        });
                    }

                    private void updateMsgState(ChatMessage message) {
                        boolean visible;
                        AwesomeIcon icon = null;
                        String text = null;
                        statusIcon.getStyleClass().add("status-icon");
                        statusInfoLabel.getStyleClass().add("status-icon");
                        statusHBox.setOpacity(1);
                        log.debug("updateMsgState msg-{}, ack={}, arrived={}", message.getMessage(),
                                message.acknowledgedProperty().get(), message.arrivedProperty().get());
                        if (message.acknowledgedProperty().get()) {
                            visible = true;
                            icon = AwesomeIcon.OK_SIGN;
                            text = Res.get("support.acknowledged");
                        } else if (message.storedInMailboxProperty().get()) {
                            visible = true;
                            icon = AwesomeIcon.ENVELOPE;
                            text = Res.get("support.savedInMailbox");
                        } else if (message.ackErrorProperty().get() != null) {
                            visible = true;
                            icon = AwesomeIcon.EXCLAMATION_SIGN;
                            text = Res.get("support.error", message.ackErrorProperty().get());
                            statusIcon.getStyleClass().add("error-text");
                            statusInfoLabel.getStyleClass().add("error-text");
                        } else if (message.arrivedProperty().get()) {
                            visible = true;
                            icon = AwesomeIcon.MAIL_REPLY;
                            text = Res.get("support.transient");
                        } else {
                            visible = false;
                            log.debug("updateMsgState called but no msg state available. message={}", message);
                        }

                        statusHBox.setVisible(visible);
                        if (visible) {
                            AwesomeDude.setIcon(statusIcon, icon, "14");
                            statusIcon.setTooltip(new Tooltip(text));
                            statusInfoLabel.setText(text);
                        }
                    }
                };
            }
        });

        addListenersOnSessionChange(widthProperty);
        scrollToBottom();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Actions
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void onTrySendMessage() {
        if (supportManager.isBootstrapped()) {
            String text = inputTextArea.getText();
            if (!text.isEmpty()) {
                if (text.length() < 5_000) {
                    onSendMessage(text);
                } else {
                    new Popup().information(Res.get("popup.warning.messageTooLong")).show();
                }
            }
        } else {
            new Popup().information(Res.get("popup.warning.notFullyConnected")).show();
        }
    }

    private void onRequestUpload() {
        if (!allowAttachments)
            return;
        int totalSize = tempAttachments.stream().mapToInt(a -> a.getBytes().length).sum();
        if (tempAttachments.size() < 3) {
            FileChooser fileChooser = new FileChooser();
            int maxMsgSize = Connection.getPermittedMessageSize();
            int maxSizeInKB = maxMsgSize / 1024;
            fileChooser.setTitle(Res.get("support.openFile", maxSizeInKB));
        /* if (Utilities.isUnix())
                fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));*/
            File result = fileChooser.showOpenDialog(getScene().getWindow());
            if (result != null) {
                try {
                    URL url = result.toURI().toURL();
                    try (InputStream inputStream = url.openStream()) {
                        byte[] filesAsBytes = ByteStreams.toByteArray(inputStream);
                        int size = filesAsBytes.length;
                        int newSize = totalSize + size;
                        if (newSize > maxMsgSize) {
                            new Popup().warning(Res.get("support.attachmentTooLarge", (newSize / 1024), maxSizeInKB)).show();
                        } else if (size > maxMsgSize) {
                            new Popup().warning(Res.get("support.maxSize", maxSizeInKB)).show();
                        } else {
                            tempAttachments.add(new Attachment(result.getName(), filesAsBytes));
                            inputTextArea.setText(inputTextArea.getText() + "\n[" + Res.get("support.attachment") + " " + result.getName() + "]");
                        }
                    } catch (java.io.IOException e) {
                        log.error(ExceptionUtils.getStackTrace(e));
                    }
                } catch (MalformedURLException e2) {
                    log.error(ExceptionUtils.getStackTrace(e2));
                }
            }
        } else {
            new Popup().warning(Res.get("support.tooManyAttachments")).show();
        }
    }

    public void onAttachText(String textAttachment, String name) {
        if (!allowAttachments)
            return;
        try {
            byte[] filesAsBytes = textAttachment.getBytes("UTF8");
            int size = filesAsBytes.length;
            int maxMsgSize = Connection.getPermittedMessageSize();
            int maxSizeInKB = maxMsgSize / 1024;
            if (size > maxMsgSize) {
                new Popup().warning(Res.get("support.attachmentTooLarge", (size / 1024), maxSizeInKB)).show();
            } else {
                tempAttachments.add(new Attachment(name, filesAsBytes));
                inputTextArea.setText(inputTextArea.getText() + "\n[" + Res.get("support.attachment") + " " + name + "]");
            }
        } catch (Exception e) {
            log.error(ExceptionUtils.getStackTrace(e));
        }
    }

    private void copyChatMessagesToClipboard(Button sourceBtn) {
        optionalSupportSession.ifPresent(session -> {
            StringBuilder stringBuilder = new StringBuilder();
            chatMessages.forEach(i -> {
                String metaData = DisplayUtils.formatDateTime(new Date(i.getDate()));
                metaData = metaData + (i.isSystemMessage() ? " (System message)" :
                        (i.isSenderIsTrader() ? " (from Trader)" : " (from Agent)"));
                stringBuilder.append(metaData).append("\n").append(i.getMessage()).append("\n\n");
            });
            Utilities.copyToClipboard(stringBuilder.toString());
            new Notification()
                    .notification(Res.get("shared.copiedToClipboard"))
                    .hideCloseButton()
                    .autoClose()
                    .show();
        });
    }

    private void onOpenAttachment(Attachment attachment) {
        if (!allowAttachments)
            return;
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(Res.get("support.save"));
        fileChooser.setInitialFileName(attachment.getFileName());
    /* if (Utilities.isUnix())
            fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));*/
        File file = fileChooser.showSaveDialog(getScene().getWindow());
        if (file != null) {
            try (FileOutputStream fileOutputStream = new FileOutputStream(file.getAbsolutePath())) {
                fileOutputStream.write(attachment.getBytes());
            } catch (IOException e) {
                log.error("Error opening attachment: {}\n", e.getMessage(), e);
            }
        }
    }

    private void onSendMessage(String inputText) {
        if (chatMessage != null) {
            chatMessage.acknowledgedProperty().removeListener(acknowledgedPropertyListener);
            chatMessage.storedInMailboxProperty().removeListener(storedInMailboxPropertyListener);
            chatMessage.sendMessageErrorProperty().removeListener(sendMessageErrorPropertyListener);
        }

        chatMessage = sendDisputeDirectMessage(inputText, new ArrayList<>(tempAttachments));
        tempAttachments.clear();
        scrollToBottom();

        inputTextArea.setDisable(true);
        inputTextArea.clear();

        chatMessage.startAckTimer();

        Timer timer = UserThread.runAfter(() -> {
            sendMsgInfoLabel.setVisible(true);
            sendMsgInfoLabel.setManaged(true);
            sendMsgInfoLabel.setText(Res.get("support.sendingMessage"));

            sendMsgBusyAnimation.play();
        }, 500, TimeUnit.MILLISECONDS);

        acknowledgedPropertyListener = (observable, oldValue, newValue) -> {
            if (newValue) {
                sendMsgInfoLabel.setVisible(false);
                hideSendMsgInfo(timer);
            }
        };
        storedInMailboxPropertyListener = (observable, oldValue, newValue) -> {
            if (newValue) {
                sendMsgInfoLabel.setVisible(true);
                sendMsgInfoLabel.setManaged(true);
                sendMsgInfoLabel.setText(Res.get("support.receiverNotOnline"));
                hideSendMsgInfo(timer);
            }
        };
        sendMessageErrorPropertyListener = (observable, oldValue, newValue) -> {
            if (newValue != null) {
                sendMsgInfoLabel.setVisible(true);
                sendMsgInfoLabel.setManaged(true);
                sendMsgInfoLabel.setText(Res.get("support.sendMessageError", newValue));
                hideSendMsgInfo(timer);
            }
        };
        if (chatMessage != null) {
            chatMessage.acknowledgedProperty().addListener(acknowledgedPropertyListener);
            chatMessage.storedInMailboxProperty().addListener(storedInMailboxPropertyListener);
            chatMessage.sendMessageErrorProperty().addListener(sendMessageErrorPropertyListener);
        }
    }

    private ChatMessage sendDisputeDirectMessage(String text, ArrayList<Attachment> attachments) {
        return optionalSupportSession.map(supportSession -> {
            ChatMessage message = new ChatMessage(
                    supportManager.getSupportType(),
                    supportSession.getTradeId(),
                    supportSession.getClientId(),
                    supportSession.isClient(),
                    text,
                    supportManager.getMyAddress(),
                    attachments
            );
            supportManager.addAndPersistChatMessage(message);
            return supportManager.sendChatMessage(message);
        }).orElse(null);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Helpers
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void hideSendMsgInfo(Timer timer) {
        timer.stop();
        inputTextArea.setDisable(false);

        UserThread.runAfter(() -> {
            sendMsgInfoLabel.setVisible(false);
            sendMsgInfoLabel.setManaged(false);
        }, 5);
        sendMsgBusyAnimation.stop();
    }

    public void scrollToBottom() {
        UserThread.execute(() -> {
            if (messageListView != null && !messageListView.getItems().isEmpty()) {
                int lastIndex = messageListView.getItems().size();
                messageListView.scrollTo(lastIndex);
            }
        });
    }

    public void setInputBoxVisible(boolean visible) {
        if (messagesInputBox != null) {
            messagesInputBox.setVisible(visible);
            messagesInputBox.setManaged(visible);
            AnchorPane.setBottomAnchor(messageListView, visible ? 120d : 0d);
        }
    }

    public void removeInputBox() {
        this.getChildren().remove(messagesInputBox);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Bindings
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void addListenersOnSessionChange(ReadOnlyDoubleProperty widthProperty) {
        if (tableGroupHeadline != null) {
            tableGroupHeadline.prefWidthProperty().bind(widthProperty);
            messageListView.prefWidthProperty().bind(widthProperty);
            this.prefWidthProperty().bind(widthProperty);
            chatMessages.addListener(disputeDirectMessageListListener);
            inputTextAreaTextSubscription = EasyBind.subscribe(inputTextArea.textProperty(), t -> sendButton.setDisable(t.isEmpty()));
        }
    }

    private void removeListenersOnSessionChange() {
        if (chatMessages != null && disputeDirectMessageListListener != null)
            chatMessages.removeListener(disputeDirectMessageListListener);

        if (chatMessage != null) {
            if (acknowledgedPropertyListener != null)
                chatMessage.arrivedProperty().removeListener(acknowledgedPropertyListener);
            if (storedInMailboxPropertyListener != null)
                chatMessage.storedInMailboxProperty().removeListener(storedInMailboxPropertyListener);
        }

        if (messageListView != null)
            messageListView.prefWidthProperty().unbind();

        if (tableGroupHeadline != null)
            tableGroupHeadline.prefWidthProperty().unbind();

        this.prefWidthProperty().unbind();

        if (inputTextAreaTextSubscription != null)
            inputTextAreaTextSubscription.unsubscribe();
    }

}