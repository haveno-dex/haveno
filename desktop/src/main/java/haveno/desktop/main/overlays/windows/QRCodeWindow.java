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

import haveno.core.locale.Res;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.main.overlays.Overlay;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import net.glxn.qrgen.QRCode;
import net.glxn.qrgen.image.ImageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;

public class QRCodeWindow extends Overlay<QRCodeWindow> {
    private static final Logger log = LoggerFactory.getLogger(QRCodeWindow.class);
    private final ImageView qrCodeImageView;
    private final String bitcoinURI;

    public QRCodeWindow(String bitcoinURI) {
        this.bitcoinURI = bitcoinURI;
        final byte[] imageBytes = QRCode
                .from(bitcoinURI)
                .withSize(300, 300)
                .to(ImageType.PNG)
                .stream()
                .toByteArray();
        Image qrImage = new Image(new ByteArrayInputStream(imageBytes));
        qrCodeImageView = new ImageView(qrImage);
        qrCodeImageView.setFitHeight(250);
        qrCodeImageView.setFitWidth(250);
        qrCodeImageView.getStyleClass().add("qr-code");

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

        GridPane.setRowIndex(qrCodeImageView, ++rowIndex);
        GridPane.setColumnSpan(qrCodeImageView, 2);
        GridPane.setHalignment(qrCodeImageView, HPos.CENTER);
        gridPane.getChildren().add(qrCodeImageView);

        String request = bitcoinURI.replace("%20", " ").replace("?", "\n?").replace("&", "\n&");
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
}
