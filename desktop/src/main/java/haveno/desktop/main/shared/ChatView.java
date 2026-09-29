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
import haveno.desktop.components.TableGroupHeadline;
import haveno.desktop.main.overlays.notifications.Notification;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.util.Accessibility;
import haveno.desktop.util.DisplayUtils;
import haveno.desktop.util.GUIUtil;
import haveno.desktop.util.Layout;
import haveno.core.locale.Res;
import haveno.core.support.SupportManager;
import haveno.core.support.SupportSession;
import haveno.core.support.dispute.Attachment;
import haveno.core.support.dispute.DisputeSession;
import haveno.core.support.messages.ChatMessage;

import haveno.network.p2p.network.Connection;

import haveno.common.UserThread;
import haveno.common.util.Utilities;

import com.google.common.io.ByteStreams;

import haveno.desktop.util.GlyphsDude;
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon;
import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.beans.WeakInvalidationListener;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.stage.FileChooser;

import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import javafx.geometry.Insets;
import javafx.geometry.Pos;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;

import javafx.beans.property.ReadOnlyDoubleProperty;

import javafx.event.EventHandler;

import javafx.collections.FXCollections;
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

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

@Slf4j
public class ChatView extends AnchorPane {

    private static final PseudoClass INPUT_VISIBLE_PSEUDO_CLASS = PseudoClass.getPseudoClass("input-visible");

    // UI
    private TextArea inputTextArea;
    private Button sendButton;
    private ListView<ChatMessage> messageListView;
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
    private static final ObservableList<ChatMessage> sendingMessages = FXCollections.observableArrayList(); // shared so a reopened chat window still shows sending
    private ObservableList<ChatMessage> chatMessages;
    private ListChangeListener<ChatMessage> disputeDirectMessageListListener;
    private Subscription inputTextAreaTextSubscription;
    private final List<Attachment> tempAttachments = new ArrayList<>();

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
        AnchorPane.setTopAnchor(messageListView, displayHeader ? 30d : 0d);
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
        sendButton.setStyle("-fx-pref-width: 125; -fx-min-width: 110; -fx-padding: 3 3 3 3;");
        inputTextAreaTextSubscription = EasyBind.subscribe(inputTextArea.textProperty(), t -> sendButton.setDisable(t.isEmpty()));

        Button uploadButton = new AutoTooltipButton(Res.get("support.addAttachments"));
        uploadButton.setOnAction(e -> onRequestUpload());
        Button clipboardButton = new AutoTooltipButton(Res.get("shared.copyToClipboard"));
        clipboardButton.setOnAction(e -> copyChatMessagesToClipboard(clipboardButton));
        uploadButton.setStyle("-fx-pref-width: 125; -fx-min-width: 110; -fx-padding: 3 3 3 3;");
        clipboardButton.setStyle("-fx-pref-width: 125; -fx-min-width: 110; -fx-padding: 3 3 3 3;");

        if (displayHeader)
            this.getChildren().add(tableGroupHeadline);

        if (supportSession.chatIsOpen()) {
            HBox buttonBox = new HBox();
            buttonBox.setSpacing(10);
            if (allowAttachments)
                buttonBox.getChildren().addAll(uploadButton, clipboardButton);
            if (extraButton != null)
                buttonBox.getChildren().add(extraButton);
            Pane spacer = new Pane();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            buttonBox.getChildren().addAll(spacer, sendButton);

            messagesInputBox = new VBox();
            messagesInputBox.setSpacing(10);
            messagesInputBox.getChildren().addAll(inputTextArea, buttonBox);
            VBox.setVgrow(buttonBox, Priority.ALWAYS);

            AnchorPane.setRightAnchor(messagesInputBox, displayHeader ? 0d : 10d);
            AnchorPane.setBottomAnchor(messagesInputBox, displayHeader ? 5d : 15d);
            AnchorPane.setLeftAnchor(messagesInputBox, displayHeader ? 0d : 10d);

            AnchorPane.setBottomAnchor(messageListView, displayHeader ? 125d : 135d);
            messageListView.pseudoClassStateChanged(INPUT_VISIBLE_PSEUDO_CLASS, true);

            this.getChildren().addAll(messageListView, messagesInputBox);
        } else {
            AnchorPane.setBottomAnchor(messageListView, 0d);
            this.getChildren().add(messageListView);
        }

        messageListView.setCellFactory(new Callback<>() {
            @Override
            public ListCell<ChatMessage> call(ListView<ChatMessage> list) {
                return new ListCell<>() {
                    ChatMessage stateMessage;
                    InvalidationListener msgStateListener = o -> UserThread.execute(() -> {
                        if (stateMessage != null) updateMsgState(stateMessage);
                    });
                    WeakInvalidationListener weakMsgStateListener = new WeakInvalidationListener(msgStateListener);
                    Pane bg = new Pane();
                    Region arrow = new Region();
                    AnchorPane bubblePane = new AnchorPane(bg, arrow);
                    Label headerLabel = new AutoTooltipLabel();
                    TextArea messageTextArea = new TextArea();
                    Label copyLabel = new Label();
                    HBox attachmentsBox = new HBox();
                    AnchorPane messageAnchorPane = new AnchorPane();
                    Label statusIcon = new Label();
                    Label statusInfoLabel = new Label();
                    HBox statusHBox = new HBox();
                    Tooltip statusTooltip = new Tooltip();
                    double arrowWidth = 15d;
                    double attachmentsBoxHeight = 20d;
                    double border = 10d;
                    double bottomBorder = 25d;
                    double padding = border + 10d;
                    double msgLabelPaddingRight = padding + 20d;

                    {
                        bg.setMinHeight(30);
                        arrow.getStyleClass().add("chat-bubble-arrow");
                        headerLabel.getStyleClass().add("message-header");
                        messageTextArea.setEditable(false);
                        messageTextArea.setFocusTraversable(false);
                        messageTextArea.setWrapText(true);
                        messageTextArea.setMinHeight(0);
                        messageTextArea.setPrefRowCount(1);
                        messageTextArea.getStyleClass().add("selectable-label");
                        // clear the selection when focus moves within the window, keep it across window switches
                        messageTextArea.focusedProperty().addListener((observable, wasFocused, isFocused) -> {
                            if (!isFocused && messageTextArea.getScene() != null && messageTextArea.getScene().getFocusOwner() != messageTextArea)
                                messageTextArea.deselect();
                        });
                        // size to wrapped text without adding listeners whenever a cell rejoins the scene
                        messageTextArea.skinProperty().addListener((observable, oldSkin, newSkin) -> {
                            if (newSkin == null) return;
                            messageTextArea.applyCss();
                            Node text = messageTextArea.lookup(".text");
                            if (text == null) return;
                            messageTextArea.prefHeightProperty().bind(Bindings.createDoubleBinding(
                                    () -> text.getBoundsInLocal().getHeight()
                                            + messageTextArea.snappedTopInset() + messageTextArea.snappedBottomInset(),
                                    text.boundsInLocalProperty()));
                            text.boundsInLocalProperty().addListener(o -> Platform.runLater(messageTextArea::requestLayout));
                        });
                        headerLabel.setTextAlignment(TextAlignment.CENTER);
                        attachmentsBox.setSpacing(5);
                        statusIcon.getStyleClass().addAll("small-text", "status-icon");
                        statusInfoLabel.getStyleClass().addAll("small-text", "status-icon");
                        copyLabel.setTooltip(new Tooltip(Res.get("shared.copyToClipboard")));
                        Accessibility.asButton(copyLabel, Res.get("shared.copyToClipboard"));
                        statusHBox.setSpacing(5);
                        statusHBox.setAlignment(Pos.CENTER_LEFT);
                        // the status row may extend past the bubble, capped like the bubble
                        statusHBox.maxWidthProperty().bind(messageListView.widthProperty().multiply(0.75));
                        Tooltip.install(statusHBox, statusTooltip);
                        sendingMessages.addListener(weakMsgStateListener);
                        statusHBox.getChildren().addAll(statusIcon, statusInfoLabel);
                        AnchorPane.setTopAnchor(bubblePane, 0d);
                        AnchorPane.setRightAnchor(bubblePane, 0d);
                        AnchorPane.setBottomAnchor(bubblePane, 0d);
                        AnchorPane.setLeftAnchor(bubblePane, 0d);
                        messageAnchorPane.getChildren().addAll(bubblePane, headerLabel, messageTextArea, copyLabel, attachmentsBox, statusHBox);
                        messageAnchorPane.setMaxWidth(Region.USE_PREF_SIZE);
                        messageAnchorPane.setMinWidth(Region.USE_PREF_SIZE); // keep the status row from widening the bubble
                    }

                    @Override
                    protected void updateItem(ChatMessage message, boolean empty) {
                        UserThread.execute(() -> {
                            if (message != getItem()) messageTextArea.deselect();
                            super.updateItem(message, empty);
                            observeMsgState(null);
                            if (message != null && !empty) {
                                copyLabel.setOnMouseClicked(e -> {
                                    Utilities.copyToClipboard(messageTextArea.getText());
                                    Tooltip tp = new Tooltip(Res.get("shared.copiedToClipboard"));
                                    Node node = (Node) e.getSource();
                                    UserThread.runAfter(() -> tp.hide(), 1);
                                    tp.show(node, e.getScreenX() + Layout.PADDING, e.getScreenY() + Layout.PADDING);
                                });
    
                                boolean senderIsTrader = message.isSenderIsTrader();
                                boolean isMyMsg = supportSession.isClient() == senderIsTrader;
                                messageAnchorPane.prefWidthProperty().bind(bubbleWidth(message.getMessage(), message.isSystemMessage()));
                                setAlignment(isMyMsg && !message.isSystemMessage() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
    
                                AnchorPane.clearConstraints(bg);
                                AnchorPane.clearConstraints(headerLabel);
                                AnchorPane.clearConstraints(arrow);
                                AnchorPane.clearConstraints(messageTextArea);
                                AnchorPane.clearConstraints(copyLabel);
                                AnchorPane.clearConstraints(statusHBox);
                                AnchorPane.clearConstraints(attachmentsBox);
    
                                AnchorPane.setTopAnchor(bg, 15d);
                                AnchorPane.setBottomAnchor(bg, bottomBorder);
                                AnchorPane.setTopAnchor(headerLabel, 0d);
                                AnchorPane.setBottomAnchor(arrow, bottomBorder + 5d);
                                AnchorPane.setTopAnchor(messageTextArea, 25d);
                                AnchorPane.setTopAnchor(copyLabel, 25d);
                                AnchorPane.setBottomAnchor(attachmentsBox, bottomBorder + 10);
    
                                arrow.setVisible(!message.isSystemMessage());
                                arrow.setManaged(!message.isSystemMessage());
                                statusHBox.setVisible(false);
    
                                messageTextArea.getStyleClass().removeAll("my-message", "message");
                                copyLabel.getStyleClass().removeAll("my-message", "message");
                                bubblePane.getStyleClass().remove("chat-bubble-raised");
                                if (!message.isSystemMessage()) bubblePane.getStyleClass().add("chat-bubble-raised");
    
                                if (message.isSystemMessage()) {
                                    bg.setId("message-bubble-green");
                                    messageTextArea.getStyleClass().add("message");
                                    copyLabel.getStyleClass().add("message");
                                    observeMsgState(message);
                                    updateMsgState(message);
                                } else if (isMyMsg) {
                                    bg.setId("message-bubble-blue");
                                    messageTextArea.getStyleClass().add("my-message");
                                    copyLabel.getStyleClass().add("my-message");
                                    arrow.setId("bubble_arrow_blue_right");
    
                                    observeMsgState(message);
                                    updateMsgState(message);
                                } else {
                                    bg.setId("message-bubble-grey");
                                    messageTextArea.getStyleClass().add("message");
                                    copyLabel.getStyleClass().add("message");
                                    arrow.setId("bubble_arrow_grey_left");
                                }
    
                                if (message.isSystemMessage()) {
                                    AnchorPane.setLeftAnchor(headerLabel, padding);
                                    AnchorPane.setRightAnchor(headerLabel, padding);
                                    AnchorPane.setLeftAnchor(bg, border);
                                    AnchorPane.setRightAnchor(bg, border);
                                    AnchorPane.setLeftAnchor(messageTextArea, padding);
                                    AnchorPane.setRightAnchor(messageTextArea, msgLabelPaddingRight);
                                    AnchorPane.setRightAnchor(copyLabel, padding);
                                    AnchorPane.setLeftAnchor(attachmentsBox, padding);
                                    AnchorPane.setRightAnchor(attachmentsBox, padding);
                                    AnchorPane.setLeftAnchor(statusHBox, padding);
                                } else if (!isMyMsg) {
                                    AnchorPane.setLeftAnchor(headerLabel, padding + arrowWidth);
                                    AnchorPane.setLeftAnchor(bg, border + arrowWidth);
                                    AnchorPane.setRightAnchor(bg, border);
                                    AnchorPane.setLeftAnchor(arrow, border);
                                    AnchorPane.setLeftAnchor(messageTextArea, padding + arrowWidth);
                                    AnchorPane.setRightAnchor(messageTextArea, msgLabelPaddingRight);
                                    AnchorPane.setRightAnchor(copyLabel, padding);
                                    AnchorPane.setLeftAnchor(attachmentsBox, padding + arrowWidth);
                                    AnchorPane.setRightAnchor(attachmentsBox, padding);
                                    AnchorPane.setLeftAnchor(statusHBox, padding + arrowWidth);
                                } else {
                                    AnchorPane.setRightAnchor(headerLabel, padding + arrowWidth);
                                    AnchorPane.setRightAnchor(bg, border + arrowWidth);
                                    AnchorPane.setLeftAnchor(bg, border);
                                    AnchorPane.setRightAnchor(arrow, border);
                                    AnchorPane.setLeftAnchor(messageTextArea, padding);
                                    AnchorPane.setRightAnchor(messageTextArea, msgLabelPaddingRight + arrowWidth);
                                    AnchorPane.setRightAnchor(copyLabel, padding + arrowWidth);
                                    AnchorPane.setLeftAnchor(attachmentsBox, padding);
                                    AnchorPane.setRightAnchor(attachmentsBox, padding + arrowWidth);
                                    AnchorPane.setRightAnchor(statusHBox, padding + arrowWidth);
                                }
                                AnchorPane.setBottomAnchor(statusHBox, 6d);
                                String metaData = DisplayUtils.formatDateTime(new Date(message.getDate()));
                                if (!message.isSystemMessage())
                                    metaData = (isMyMsg ? "Sent " : "Received ") + metaData
                                            + (isMyMsg ? "" : " from " + counterpartyName);
                                headerLabel.setText(metaData);
                                messageTextArea.setText(message.getMessage());
                                attachmentsBox.getChildren().clear();
                                if (allowAttachments &&
                                        message.getAttachments() != null &&
                                        message.getAttachments().size() > 0) {
                                    AnchorPane.setBottomAnchor(messageTextArea, bottomBorder + attachmentsBoxHeight + 10);
                                    attachmentsBox.getChildren().add(new AutoTooltipLabel(Res.get("support.attachments") + " ") {{
                                        setPadding(new Insets(0, 0, 3, 0));
                                        if (isMyMsg)
                                            getStyleClass().add("my-message");
                                        else
                                            getStyleClass().add("message");
                                    }});
                                    message.getAttachments().forEach(attachment -> {
                                        Label icon = new Label();
                                        if (isMyMsg)
                                            icon.getStyleClass().add("attachment-icon");
                                        else
                                            icon.getStyleClass().add("attachment-icon-black");
    
                                        GlyphsDude.setIcon(icon, FontAwesomeIcon.FILE_TEXT);
                                        icon.setPadding(new Insets(-2, 0, 0, 0));
                                        icon.setTooltip(new Tooltip(attachment.getFileName()));
                                        icon.setOnMouseClicked(event -> onOpenAttachment(attachment));
                                        Accessibility.asButton(icon, attachment.getFileName());
                                        attachmentsBox.getChildren().add(icon);
                                    });
                                } else {
                                    AnchorPane.setBottomAnchor(messageTextArea, bottomBorder + 10);
                                }
    
                                // Need to set it here otherwise style is not correct
                                copyLabel.getStyleClass().addAll("icon", "copy-icon-disputes");
                                Text copyIcon = GlyphsDude.createIcon(MaterialDesignIcon.CONTENT_COPY, "16.0");
                                copyLabel.setGraphic(copyIcon);
    
                                // TODO There are still some cell rendering issues on updates
                                setGraphic(messageAnchorPane);
                                setAccessibleText(headerLabel.getText() + ". " + messageTextArea.getText());
                            } else {
    
                                messageAnchorPane.prefWidthProperty().unbind();
    
                                copyLabel.setOnMouseClicked(null);
                                messageTextArea.clear();
                                setGraphic(null);
                                setAccessibleText(null);
                            }
                        });
                    }

                    // observe state of the cell's current message only, since cells are reused
                    private void observeMsgState(ChatMessage message) {
                        if (stateMessage != null) msgStateProperties(stateMessage).forEach(p -> p.removeListener(weakMsgStateListener));
                        stateMessage = message;
                        if (message != null) msgStateProperties(message).forEach(p -> p.addListener(weakMsgStateListener));
                    }

                    private List<Observable> msgStateProperties(ChatMessage message) {
                        return List.of(message.arrivedProperty(), message.storedInMailboxProperty(),
                                message.acknowledgedProperty(), message.ackErrorProperty(), message.sendMessageErrorProperty());
                    }

                    // fit the bubble to its content up to a share of the list width; system messages span it
                    private DoubleBinding bubbleWidth(String text, boolean fullWidth) {
                        return Bindings.createDoubleBinding(() -> {
                            double available = messageListView.getWidth() - padding - GUIUtil.getScrollbarWidth(messageListView);
                            if (fullWidth) return available;
                            Text measure = new Text(text);
                            measure.setFont(messageTextArea.getFont());
                            double content = Math.max(Math.ceil(measure.getLayoutBounds().getWidth()) + 2 + padding + msgLabelPaddingRight,
                                    headerLabel.prefWidth(-1) + 2 * padding);
                            content = Math.max(content, attachmentsBox.prefWidth(-1) + 2 * padding);
                            return Math.min(available * 0.75, content + arrowWidth);
                        }, messageListView.widthProperty(), messageTextArea.fontProperty(), headerLabel.textProperty(),
                                headerLabel.fontProperty(), attachmentsBox.getChildren());
                    }

                    private void updateMsgState(ChatMessage message) {
                        boolean visible;
                        FontAwesomeIcon icon = null;
                        String text = null;
                        statusIcon.getStyleClass().removeAll("error-text");
                        statusInfoLabel.getStyleClass().removeAll("error-text");
                        log.debug("updateMsgState msg-{}, ack={}, arrived={}", message.getMessage(),
                                message.acknowledgedProperty().get(), message.arrivedProperty().get());
                        if (message.acknowledgedProperty().get()) {
                            visible = true;
                            icon = FontAwesomeIcon.CHECK_CIRCLE;
                            text = Res.get("support.acknowledged");
                        } else if (message.ackErrorProperty().get() != null) {
                            visible = true;
                            icon = FontAwesomeIcon.EXCLAMATION_CIRCLE;
                            text = Res.get("support.error", message.ackErrorProperty().get());
                            statusIcon.getStyleClass().add("error-text");
                            statusInfoLabel.getStyleClass().add("error-text");
                        } else if (message.sendMessageErrorProperty().get() != null) {
                            visible = true;
                            icon = FontAwesomeIcon.EXCLAMATION_CIRCLE;
                            text = Res.get("support.sendMessageError", message.sendMessageErrorProperty().get());
                            statusIcon.getStyleClass().add("error-text");
                            statusInfoLabel.getStyleClass().add("error-text");
                        } else if (message.storedInMailboxProperty().get()) {
                            visible = true;
                            icon = FontAwesomeIcon.ENVELOPE;
                            text = Res.get("support.savedInMailbox");
                        } else if (message.arrivedProperty().get()) {
                            visible = true;
                            icon = FontAwesomeIcon.MAIL_REPLY;
                            text = Res.get("support.transient");
                        } else if (sendingMessages.contains(message)) {
                            visible = true;
                            icon = FontAwesomeIcon.CLOCK_ALT;
                            text = Res.get("support.sendingMessage");
                        } else {
                            visible = false;
                            log.debug("updateMsgState called but no msg state available. message={}", message);
                        }

                        statusHBox.setVisible(visible);
                        if (visible) {
                            GlyphsDude.setIcon(statusIcon, icon, "14");
                            // keep the row to one line, e.g. for multiline network errors
                            statusTooltip.setText(text);
                            statusInfoLabel.setText(text.lines().findFirst().orElse(""));
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
                            appendAttachmentTag(result.getName());
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
                appendAttachmentTag(name);
            }
        } catch (Exception e) {
            log.error(ExceptionUtils.getStackTrace(e));
        }
    }

    // append the attachment tag to the input text area, without a leading newline when empty
    private void appendAttachmentTag(String name) {
        String text = inputTextArea.getText();
        inputTextArea.setText((text.isEmpty() ? "" : text + "\n") + "[" + Res.get("support.attachment") + " " + name + "]");
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
        ChatMessage message = sendDisputeDirectMessage(inputText, new ArrayList<>(tempAttachments));
        tempAttachments.clear();
        scrollToBottom();
        inputTextArea.clear();
        inputTextArea.requestFocus();
        if (message == null) return;

        // show as sending until confirmed, saved to the mailbox, or failed
        message.startAckTimer();
        sendingMessages.add(message);
        List<Observable> sendStates = List.of(message.acknowledgedProperty(), message.storedInMailboxProperty(),
                message.ackErrorProperty(), message.sendMessageErrorProperty());
        InvalidationListener sendDoneListener = new InvalidationListener() {
            @Override
            public void invalidated(Observable observable) {
                if (!isSendingDone(message)) return;
                UserThread.execute(() -> {
                    sendStates.forEach(state -> state.removeListener(this));
                    sendingMessages.remove(message);
                });
            }
        };
        sendStates.forEach(state -> state.addListener(sendDoneListener));
        sendDoneListener.invalidated(null); // the send can finish before the listener is added
    }

    private boolean isSendingDone(ChatMessage message) {
        return message.acknowledgedProperty().get() || message.storedInMailboxProperty().get() ||
                message.ackErrorProperty().get() != null || message.sendMessageErrorProperty().get() != null;
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
            // add to the session's dispute directly, since multiple disputes can exist for a trade
            if (supportSession instanceof DisputeSession && ((DisputeSession) supportSession).getDispute() != null) {
                ((DisputeSession) supportSession).getDispute().addAndPersistChatMessage(message);
                supportManager.requestPersistence();
            } else {
                supportManager.addAndPersistChatMessage(message);
            }
            return supportManager.sendChatMessage(message);
        }).orElse(null);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Helpers
    ///////////////////////////////////////////////////////////////////////////////////////////

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
            double inputBoxHeight = displayHeader ? 125d : 135d;
            AnchorPane.setBottomAnchor(messageListView, visible ? inputBoxHeight : 0d);
            messageListView.pseudoClassStateChanged(INPUT_VISIBLE_PSEUDO_CLASS, visible);
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

        if (messageListView != null)
            messageListView.prefWidthProperty().unbind();

        if (tableGroupHeadline != null)
            tableGroupHeadline.prefWidthProperty().unbind();

        this.prefWidthProperty().unbind();

        if (inputTextAreaTextSubscription != null)
            inputTextAreaTextSubscription.unsubscribe();
    }

}