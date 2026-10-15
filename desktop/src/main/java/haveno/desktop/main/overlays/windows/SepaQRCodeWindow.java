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

import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.Encoder;
import haveno.common.util.Tuple2;
import haveno.core.locale.Res;
import haveno.core.monetary.TraditionalMoney;
import haveno.core.monetary.Volume;
import haveno.desktop.main.overlays.Overlay;
import haveno.desktop.util.GUIUtil;
import javafx.geometry.HPos;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;

import javax.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Shows an EPC QR code which banking apps scan to fill in a SEPA credit transfer. */
public class SepaQRCodeWindow extends Overlay<SepaQRCodeWindow> {
    private static final Map<EncodeHintType, ?> EPC_HINTS = Map.of(
            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
    private static final int MAX_NAME_LENGTH = 70;
    private static final int MAX_QR_VERSION = 13;
    private static final BigDecimal MIN_AMOUNT = new BigDecimal("0.01");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999.99");
    private static final Pattern BIC_PATTERN = Pattern.compile("[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?", Pattern.CASE_INSENSITIVE); // ascii only
    private static final Pattern IBAN_PATTERN = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTROL_CHAR_PATTERN = Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}]");

    private final StackPane qrCodePane;

    public SepaQRCodeWindow(String epcPayload) {
        Tuple2<StackPane, ImageView> qrCodeTuple = GUIUtil.getBigQrCodePane();
        qrCodePane = qrCodeTuple.first;
        int qrCodeSize = (int) qrCodeTuple.second.getFitWidth();
        byte[] imageBytes = GUIUtil.generateQrCodePng(epcPayload, qrCodeSize, qrCodeSize, EPC_HINTS);
        qrCodeTuple.second.setImage(new Image(new ByteArrayInputStream(imageBytes)));

        type = Type.Information;
        width = 468;
        headLine(Res.get("qRCodeWindow.headline"));
        message(Res.get("sepaQRCodeWindow.msg"));
    }

    @Override
    public void show() {
        createGridPane();
        addHeadLine();
        addMessage();

        GridPane.setRowIndex(qrCodePane, ++rowIndex);
        GridPane.setColumnSpan(qrCodePane, 2);
        GridPane.setHalignment(qrCodePane, HPos.CENTER);
        gridPane.getChildren().add(qrCodePane);

        addButtons();
        applyStyles();
        display();
    }

    // EPC069-12 version 001 (BIC required) with UTF-8 and no reason for payment, or null if the fields cannot be encoded as shown
    @Nullable
    public static String getEpcPayload(String holderName, String bic, String iban, Volume volume) {
        if (holderName == null || bic == null || iban == null || volume == null || !"EUR".equals(volume.getCurrencyCode())) return null;
        String name = holderName.strip();
        bic = bic.replace(" ", "");
        iban = iban.replace(" ", "");
        BigDecimal amount = BigDecimal.valueOf(volume.getValue(), TraditionalMoney.SMALLEST_UNIT_EXPONENT).setScale(2, RoundingMode.HALF_UP);
        if (name.isEmpty() || name.codePointCount(0, name.length()) > MAX_NAME_LENGTH || CONTROL_CHAR_PATTERN.matcher(name).find()) return null;
        if (!BIC_PATTERN.matcher(bic).matches() || !IBAN_PATTERN.matcher(iban).matches()) return null;
        if (amount.compareTo(MIN_AMOUNT) < 0 || amount.compareTo(MAX_AMOUNT) > 0) return null;
        String payload = String.join("\n", "BCD", "001", "1", "SCT", bic.toUpperCase(Locale.ROOT), name, iban.toUpperCase(Locale.ROOT), "EUR" + amount.toPlainString());
        try {
            return Encoder.encode(payload, ErrorCorrectionLevel.M, EPC_HINTS).getVersion().getVersionNumber() <= MAX_QR_VERSION ? payload : null;
        } catch (WriterException e) {
            return null;
        }
    }
}
