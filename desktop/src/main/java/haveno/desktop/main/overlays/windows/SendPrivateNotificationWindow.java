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

package haveno.desktop.main.overlays.windows;

import static haveno.desktop.util.FormBuilder.addInputTextField;
import static haveno.desktop.util.FormBuilder.addTopLabelTextArea;

import haveno.common.UserThread;
import haveno.common.app.DevEnv;
import haveno.common.crypto.PubKeyRing;
import haveno.common.util.Tuple2;
import haveno.core.alert.PrivateNotificationManager;
import haveno.core.alert.PrivateNotificationPayload;
import haveno.core.locale.Res;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.InputTextField;
import haveno.desktop.main.overlays.Overlay;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.SendMailboxMessageListener;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;

import javafx.geometry.Insets;
import javafx.geometry.Pos;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SendPrivateNotificationWindow extends Overlay<SendPrivateNotificationWindow> {
    private static final Logger log = LoggerFactory.getLogger(SendPrivateNotificationWindow.class);

    private final PrivateNotificationManager privateNotificationManager;
    private final PubKeyRing pubKeyRing;
    private final NodeAddress nodeAddress;
    private final boolean useDevPrivilegeKeys;

    public SendPrivateNotificationWindow(PrivateNotificationManager privateNotificationManager,
                                         PubKeyRing pubKeyRing,
                                         NodeAddress nodeAddress,
                                         boolean useDevPrivilegeKeys) {
        this.privateNotificationManager = privateNotificationManager;
        this.pubKeyRing = pubKeyRing;
        this.nodeAddress = nodeAddress;
        this.useDevPrivilegeKeys = useDevPrivilegeKeys;
        type = Type.Attention;
    }

    public void show() {
        if (headLine == null)
            headLine = Res.get("sendPrivateNotificationWindow.headline");

        width = 868;
        createGridPane();
        addHeadLine();
        addContent();
        applyStyles();
        display();
    }

    @Override
    protected void setupKeyHandler(Scene scene) {
        if (!hideCloseButton) {
            scene.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ESCAPE) {
                    e.consume();
                    doClose();
                }
            });
        }
    }

    private void addContent() {
        InputTextField keyInputTextField = addInputTextField(gridPane, ++rowIndex, Res.get("shared.unlock"), 10);
        if (useDevPrivilegeKeys)
            keyInputTextField.setText(DevEnv.DEV_PRIVILEGE_PRIV_KEY);

        Tuple2<Label, TextArea> labelTextAreaTuple2 = addTopLabelTextArea(gridPane, ++rowIndex,
                Res.get("sendPrivateNotificationWindow.privateNotification"),
                Res.get("sendPrivateNotificationWindow.enterNotification"));
        TextArea alertMessageTextArea = labelTextAreaTuple2.second;
        Label first = labelTextAreaTuple2.first;
        first.setMinWidth(200);

        Button sendButton = new AutoTooltipButton(Res.get("sendPrivateNotificationWindow.send"));
        sendButton.setOnAction(e -> {
            if (alertMessageTextArea.getText().length() > 0 && keyInputTextField.getText().length() > 0) {
                PrivateNotificationPayload privateNotification = new PrivateNotificationPayload(alertMessageTextArea.getText());
                boolean wasKeyValid = privateNotificationManager.sendPrivateNotificationMessageIfKeyIsValid(
                        privateNotification,
                        pubKeyRing,
                        nodeAddress,
                        keyInputTextField.getText(),
                        new SendMailboxMessageListener() {
                            @Override
                            public void onArrived() {
                                log.info("PrivateNotificationPayload arrived at peer {}.", nodeAddress);
                                UserThread.runAfter(() -> new Popup().feedback(Res.get("shared.messageArrived"))
                                        .onClose(SendPrivateNotificationWindow.this::hide)
                                        .show(), 100, TimeUnit.MILLISECONDS);
                            }

                            @Override
                            public void onStoredInMailbox() {
                                log.info("PrivateNotificationPayload stored in mailbox for peer {}.", nodeAddress);
                                UserThread.runAfter(() -> new Popup().feedback(Res.get("shared.messageStoredInMailbox"))
                                        .onClose(SendPrivateNotificationWindow.this::hide)
                                        .show(), 100, TimeUnit.MILLISECONDS);
                            }

                            @Override
                            public void onFault(String errorMessage) {
                                log.error("PrivateNotificationPayload failed: Peer {}, errorMessage={}", nodeAddress,
                                        errorMessage);
                                UserThread.runAfter(() -> new Popup().feedback(Res.get("shared.messageSendingFailed", errorMessage))
                                        .onClose(SendPrivateNotificationWindow.this::hide)
                                        .show(), 100, TimeUnit.MILLISECONDS);
                            }
                        });
                if (wasKeyValid) {
                    doClose();
                } else {
                    UserThread.runAfter(() -> new Popup().warning(Res.get("shared.invalidKey"))
                            .width(300)
                            .onClose(this::blurAgain)
                            .show(), 100, TimeUnit.MILLISECONDS);
                }
            }
        });

        closeButton = new AutoTooltipButton(Res.get("shared.close"));
        closeButton.setOnAction(e -> {
            hide();
            closeHandlerOptional.ifPresent(Runnable::run);
        });

        HBox hBox = new HBox();
        hBox.setSpacing(10);
        hBox.setAlignment(Pos.CENTER_RIGHT);
        GridPane.setRowIndex(hBox, ++rowIndex);
        GridPane.setColumnSpan(hBox, 2);
        GridPane.setColumnIndex(hBox, 0);
        hBox.getChildren().addAll(sendButton, closeButton);
        gridPane.getChildren().add(hBox);
        GridPane.setMargin(hBox, new Insets(10, 0, 0, 0));
    }
}
