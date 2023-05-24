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

import haveno.core.locale.CountryUtil;
import haveno.core.locale.Res;
import haveno.core.payment.payload.SwiftAccountPayload;
import haveno.core.trade.Trade;
import haveno.core.util.VolumeUtil;
import haveno.desktop.main.overlays.Overlay;
import javafx.geometry.Insets;
import javafx.scene.control.Label;

import java.util.ArrayList;
import java.util.List;

import static haveno.common.util.Utilities.cleanString;
import static haveno.common.util.Utilities.copyToClipboard;
import static haveno.core.payment.payload.SwiftAccountPayload.ADDRESS;
import static haveno.core.payment.payload.SwiftAccountPayload.BANKPOSTFIX;
import static haveno.core.payment.payload.SwiftAccountPayload.BENEFICIARYPOSTFIX;
import static haveno.core.payment.payload.SwiftAccountPayload.BRANCH;
import static haveno.core.payment.payload.SwiftAccountPayload.COUNTRY;
import static haveno.core.payment.payload.SwiftAccountPayload.INTERMEDIARYPOSTFIX;
import static haveno.core.payment.payload.SwiftAccountPayload.PHONE;
import static haveno.core.payment.payload.SwiftAccountPayload.SNAME;
import static haveno.core.payment.payload.SwiftAccountPayload.SWIFT_ACCOUNT;
import static haveno.core.payment.payload.SwiftAccountPayload.SWIFT_CODE;
import static haveno.desktop.util.FormBuilder.addConfirmationLabelLabel;
import static haveno.desktop.util.FormBuilder.addTitledGroupBg;

public class SwiftPaymentDetails extends Overlay<SwiftPaymentDetails> {
    private final SwiftAccountPayload payload;
    private final Trade trade;
    private final List<String> copyToClipboardData = new ArrayList<>();

    public SwiftPaymentDetails(SwiftAccountPayload swiftAccountPayload, Trade trade) {
        this.payload = swiftAccountPayload;
        this.trade = trade;
    }

    @Override
    public void show() {
        rowIndex = -1;
        width = 918;
        createGridPane();
        addContent();
        addButtons();
        display();
    }

    @Override
    protected void cleanup() {
    }

    @Override
    protected void createGridPane() {
        super.createGridPane();
        gridPane.setPadding(new Insets(35, 40, 30, 40));
        gridPane.getStyleClass().add("grid-pane");
    }

    private void addContent() {
        int rows = payload.usesIntermediaryBank() ? 22 : 16;
        addTitledGroupBg(gridPane, ++rowIndex, rows, Res.get("payment.swift.headline"));

        gridPane.add(new Label(""), 0, ++rowIndex);  // spacer
        addLabelsAndCopy(Res.get("portfolio.pending.step2_buyer.amountToTransfer"),
                VolumeUtil.formatVolumeWithCode(trade.getVolume()));
        addLabelsAndCopy(Res.get(SWIFT_CODE + BANKPOSTFIX), payload.getBankSwiftCode());
        addLabelsAndCopy(Res.get(SNAME + BANKPOSTFIX), payload.getBankName());
        addLabelsAndCopy(Res.get(BRANCH + BANKPOSTFIX), payload.getBankBranch());
        addLabelsAndCopy(Res.get(ADDRESS + BANKPOSTFIX), cleanString(payload.getBankAddress()));
        addLabelsAndCopy(Res.get(COUNTRY + BANKPOSTFIX), CountryUtil.getNameAndCode(payload.getBankCountryCode()));

        if (payload.usesIntermediaryBank()) {
            gridPane.add(new Label(""), 0, ++rowIndex);  // spacer
            addLabelsAndCopy(Res.get(SWIFT_CODE + INTERMEDIARYPOSTFIX), payload.getIntermediarySwiftCode());
            addLabelsAndCopy(Res.get(SNAME + INTERMEDIARYPOSTFIX), payload.getIntermediaryName());
            addLabelsAndCopy(Res.get(BRANCH + INTERMEDIARYPOSTFIX), payload.getIntermediaryBranch());
            addLabelsAndCopy(Res.get(ADDRESS + INTERMEDIARYPOSTFIX), cleanString(payload.getIntermediaryAddress()));
            addLabelsAndCopy(Res.get(COUNTRY + INTERMEDIARYPOSTFIX),
                    CountryUtil.getNameAndCode(payload.getIntermediaryCountryCode()));
        }

        gridPane.add(new Label(""), 0, ++rowIndex);  // spacer
        addLabelsAndCopy(Res.get("payment.account.owner"), payload.getBeneficiaryName());
        addLabelsAndCopy(Res.get(SWIFT_ACCOUNT), payload.getBeneficiaryAccountNr());
        addLabelsAndCopy(Res.get(ADDRESS + BENEFICIARYPOSTFIX), cleanString(payload.getBeneficiaryAddress()));
        addLabelsAndCopy(Res.get(PHONE + BENEFICIARYPOSTFIX), payload.getBeneficiaryPhone());
        addLabelsAndCopy(Res.get("payment.account.city"), payload.getBeneficiaryCity());
        addLabelsAndCopy(Res.get("payment.country"), CountryUtil.getNameAndCode(payload.getBankCountryCode()));
        addLabelsAndCopy(Res.get("payment.shared.extraInfo"), cleanString(payload.getSpecialInstructions()));

        actionButtonText(Res.get("shared.copyToClipboard"));
        onAction(() -> {
            StringBuilder work = new StringBuilder();
            for (String s : copyToClipboardData) {
                work.append(s).append(System.lineSeparator());
            }
            copyToClipboard(work.toString());
        });
    }

    private void addLabelsAndCopy(String title, String value) {
        addConfirmationLabelLabel(gridPane, ++rowIndex, title, value);
        copyToClipboardData.add(title + " : " + value);
    }
}
