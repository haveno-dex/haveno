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

package haveno.desktop.main.market.spread;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import haveno.core.locale.Res;
import haveno.core.util.FormattingUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.desktop.common.view.FxmlView;
import static haveno.desktop.util.FormBuilder.addSlideToggleButton;
import javafx.scene.control.ToggleButton;

@FxmlView
public class SpreadViewPaymentMethod extends SpreadView {
    private ToggleButton expandedMode;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public SpreadViewPaymentMethod(SpreadViewModel model, @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter formatter) {
        super(model, formatter);
        model.setIncludePaymentMethod(true);
    }

    @Override
    public void initialize() {
        super.initialize();
        int gridRow = 0;
        expandedMode = addSlideToggleButton(root, ++gridRow, Res.get("market.spread.expanded"));
    }

    @Override
    protected void activate() {
        super.activate();
        expandedMode.setSelected(model.isExpandedView());
        expandedMode.setOnAction(e -> model.setExpandedView(expandedMode.isSelected()));
    }

    @Override
    protected void deactivate() {
        expandedMode.setOnAction(null);
        super.deactivate();
    }
}


