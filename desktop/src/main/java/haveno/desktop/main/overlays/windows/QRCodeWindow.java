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

package haveno.desktop.main.overlays.windows;

import haveno.common.util.Tuple2;
import haveno.common.util.Utilities;
import haveno.core.locale.Res;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.main.overlays.Overlay;
import haveno.desktop.util.GUIUtil;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import net.glxn.qrgen.QRCode;
import net.glxn.qrgen.image.ImageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.net.URI;

public class QRCodeWindow extends Overlay<QRCodeWindow> {
    private static final Logger log = LoggerFactory.getLogger(QRCodeWindow.class);
    private final StackPane qrCodePane;
    private final String moneroUri;

    public QRCodeWindow(String moneroUri) {
        this.moneroUri = moneroUri;

        Tuple2<StackPane, ImageView> qrCodeTuple = GUIUtil.getBigXmrQrCodePane();
        qrCodePane = qrCodeTuple.first;
        ImageView qrCodeImageView = qrCodeTuple.second;
        
        final byte[] imageBytes = QRCode
                .from(moneroUri)
                .withSize(300, 300)
                .to(ImageType.PNG)
                .stream()
                .toByteArray();
        Image qrImage = new Image(new ByteArrayInputStream(imageBytes));
        qrCodeImageView.setImage(qrImage);

        type = Type.Information;
        width = 468;
        headLine(Res.get("qRCodeWindow.headline"));
        message(Res.get("qRCodeWindow.msg"));
    }

    @Override
    public void show() {
        createGridPane();
        addHeadLine();
        addMessage();

        qrCodePane.setOnMouseClicked(event -> openWallet());
        GridPane.setRowIndex(qrCodePane, ++rowIndex);
        GridPane.setColumnSpan(qrCodePane, 2);
        GridPane.setHalignment(qrCodePane, HPos.CENTER);
        gridPane.getChildren().add(qrCodePane);

        String request = moneroUri.replace("%20", " ").replace("?", "\n?").replace("&", "\n&");
        Label infoLabel = new AutoTooltipLabel(Res.get("qRCodeWindow.request", request));
        infoLabel.setMouseTransparent(true);
        infoLabel.setWrapText(true);
        infoLabel.setId("popup-qr-code-info");
        GridPane.setHalignment(infoLabel, HPos.CENTER);
        GridPane.setHgrow(infoLabel, Priority.ALWAYS);
        GridPane.setMargin(infoLabel, new Insets(3, 0, 0, 0));
        GridPane.setRowIndex(infoLabel, ++rowIndex);
        GridPane.setColumnIndex(infoLabel, 0);
        GridPane.setColumnSpan(infoLabel, 2);
        gridPane.getChildren().add(infoLabel);

        addButtons();
        applyStyles();
        display();
    }

    public String getClipboardText() {
        return moneroUri;
    }

    private void openWallet() {
        try {
            Utilities.openURI(URI.create(moneroUri));
        } catch (Exception e) {
            log.warn(e.getMessage());
        }
    }
}
