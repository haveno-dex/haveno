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
import haveno.desktop.main.overlays.Overlay;
import haveno.desktop.main.portfolio.closedtrades.ClosedTradesViewModel;
import haveno.desktop.util.Layout;
import javafx.geometry.Insets;

import javax.inject.Inject;
import java.math.BigInteger;
import java.util.Map;

import static haveno.desktop.util.FormBuilder.addConfirmationLabelLabel;
import static haveno.desktop.util.FormBuilder.addTitledGroupBg;

public class ClosedTradesSummaryWindow extends Overlay<ClosedTradesSummaryWindow> {
    private final ClosedTradesViewModel model;

    @Inject
    public ClosedTradesSummaryWindow(ClosedTradesViewModel model) {
        this.model = model;
        type = Type.Information;
    }

    public void show() {
        rowIndex = 0;
        width = 900;
        createGridPane();
        addContent();
        addButtons();
        display();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Protected
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void createGridPane() {
        super.createGridPane();
        gridPane.setPadding(new Insets(35, 40, 30, 40));
        gridPane.getStyleClass().add("grid-pane");
    }

    private void addContent() {
        Map<String, String> totalVolumeByCurrency = model.getTotalVolumeByCurrency();
        int rowSpan = totalVolumeByCurrency.size() + 4;
        addTitledGroupBg(gridPane, rowIndex, rowSpan, Res.get("closedTradesSummaryWindow.headline"));
        BigInteger totalTradeAmount = model.getTotalTradeAmount();
        addConfirmationLabelLabel(gridPane, rowIndex,
                Res.get("closedTradesSummaryWindow.totalAmount.title"),
                model.getTotalAmountWithVolume(totalTradeAmount), Layout.TWICE_FIRST_ROW_DISTANCE);
        totalVolumeByCurrency.entrySet().forEach(entry -> {
            addConfirmationLabelLabel(gridPane, ++rowIndex,
                    Res.get("closedTradesSummaryWindow.totalVolume.title", entry.getKey()), entry.getValue());
        });
        addConfirmationLabelLabel(gridPane, ++rowIndex,
                Res.get("closedTradesSummaryWindow.totalMinerFee.title"),
                model.getTotalTxFee(totalTradeAmount));
        addConfirmationLabelLabel(gridPane, ++rowIndex,
                Res.get("closedTradesSummaryWindow.totalTradeFeeInXmr.title"),
                model.getTotalTradeFee(totalTradeAmount));
    }
}
