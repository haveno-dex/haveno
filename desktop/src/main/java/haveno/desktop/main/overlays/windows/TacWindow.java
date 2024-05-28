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

import com.google.inject.Inject;
import haveno.core.locale.Res;
import haveno.desktop.app.HavenoApp;
import haveno.desktop.main.overlays.Overlay;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TacWindow extends Overlay<TacWindow> {

    private final boolean smallScreen;

    @Inject
    public TacWindow() {
        type = Type.Attention;

        Rectangle2D primaryScreenBounds = Screen.getPrimary().getVisualBounds();
        final double primaryScreenBoundsWidth = primaryScreenBounds.getWidth();
        smallScreen = primaryScreenBoundsWidth < 1024;
        if (smallScreen) {
            this.width = primaryScreenBoundsWidth * 0.8;
            log.warn("Very small screen: primaryScreenBounds=" + primaryScreenBounds.toString());
        } else {
            width = 1250;
        }
    }

    @Override
    public void show() {
        headLine(Res.get("tacWindow.headline"));

        // We do not translate the tacs because of the legal nature. We would need translations checked by lawyers
        // in each language which is too expensive atm.
        String text = "1. In no event, unless for damages caused by acts of intent and gross negligence, damages resulting from personal injury, " +
                "or damages ensuing from other instances where liability is required by applicable law or agreed to in writing, will any " +
                "developer, copyright holder and/or any other party who modifies and/or conveys the software as permitted above or " +
                "facilitates its operation, be liable for damages, including any general, special, incidental or consequential damages " +
                "arising out of the use or inability to use the software (including but not limited to loss of data or data being " +
                "rendered inaccurate or losses sustained by you or third parties or a failure of the software to operate with any " +
                "other software), even if such developer, copyright holder and/or other party has been advised of the possibility of such damages.\n\n" +

                "2. The user is responsible for using the software in compliance with local laws. Don't use the software if using it is not legal in your jurisdiction.\n\n" +

                "3. Any " + Res.getBaseCurrencyName() + " market prices, network fee estimates, or other data obtained from servers operated by Haveno is provided on an 'as is, as available' basis without representation or warranty of any kind. It is your responsibility to verify any data provided in regards to inaccuracies or omissions.\n\n" +

                "4. Any Fiat payment method carries a potential risk for bank chargeback. By accepting the \"User Agreement\" the user confirms " +
                "to be aware of those risks and in no case will claim legal responsibility to the authors or copyright holders of the software.\n\n" +

                "5. Any dispute, controversy or claim arising out of or relating to the use of the software shall be settled by arbitration in " +
                "accordance with the Haveno arbitration rules as at present in force. The arbitration is conducted online. " +
                "The language to be used in the arbitration proceedings shall be English if not otherwise stated.\n\n" +

                "6. The user confirms that they have read and agreed to the rules regarding the trade and dispute processes:\n" +
                "    - You must complete trades within the maximum duration specified for each payment method.\n" +
                "    - Leave the \"reason for payment\" field empty. DO NOT put the trade ID or any other text like 'monero', 'XMR', or 'Haveno'.\n" +
                "    - If the bank of the fiat sender charges fees, the fiat sender (" + Res.getBaseCurrencyCode() + " buyer) has to cover the fees.\n" +
                "    - If either trader opens a dispute, the arbitrator can settle the dispute and pay out trade funds accordingly.\n" +
                "    - In case of arbitration, you must cooperate with the arbitrator and respond to each message within 48 hours.\n" +
                "    - The arbitrator may penalize offer makers and traders for breaching Haveno rules and the principle of acting in good faith within the network, up to the value of the security deposit.\n";
        message(text);
        actionButtonText(Res.get("tacWindow.agree"));
        closeButtonText(Res.get("tacWindow.disagree"));
        onClose(HavenoApp.getShutDownHandler());

        super.show();
    }

    @Override
    protected void addMessage() {
        super.addMessage();
        String fontStyleClass = smallScreen ? "small-text" : "normal-text";
        messageLabel.getStyleClass().add(fontStyleClass);

        // TODO: link to the wiki
        // HyperlinkWithIcon hyperlinkWithIcon = addHyperlinkWithIcon(gridPane, ++rowIndex, Res.get("tacWindow.arbitrationSystem"),
        //         "https://haveno.exchange/wiki/Dispute_resolution");
        // hyperlinkWithIcon.getStyleClass().add(fontStyleClass);
        // GridPane.setMargin(hyperlinkWithIcon, new Insets(-6, 0, -20, -4));
    }

    @Override
    protected void setTruncatedMessage() {
        truncatedMessage = message;
    }

    @Override
    protected void onShow() {
        display();
    }
}
