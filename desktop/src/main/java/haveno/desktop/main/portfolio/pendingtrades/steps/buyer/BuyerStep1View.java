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

package haveno.desktop.main.portfolio.pendingtrades.steps.buyer;

import haveno.core.locale.Res;
import haveno.desktop.main.portfolio.pendingtrades.PendingTradesViewModel;
import haveno.desktop.main.portfolio.pendingtrades.steps.TradeStepView;

public class BuyerStep1View extends TradeStepView {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, Initialisation
    ///////////////////////////////////////////////////////////////////////////////////////////

    public BuyerStep1View(PendingTradesViewModel model) {
        super(model);
    }

    @Override
    protected void onPendingTradesInitialized() {
        super.onPendingTradesInitialized();
        //validatePayoutTx(); // TODO (woodser): no payout tx in xmr integration, do something else?
        //validateDepositInputs();
        checkForUnconfirmedTimeout();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Info
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected String getInfoBlockTitle() {
        return Res.get("portfolio.pending.step1.waitForConf");
    }

    @Override
    protected String getInfoText() {
        return Res.get("portfolio.pending.step1.info", Res.get("shared.You"));
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Warning
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected String getFirstHalfOverWarnText() {
        return Res.get("portfolio.pending.step1.warn");
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Dispute
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected String getPeriodOverWarnText() {
        return Res.get("portfolio.pending.step1.openForDispute");
    }
}


