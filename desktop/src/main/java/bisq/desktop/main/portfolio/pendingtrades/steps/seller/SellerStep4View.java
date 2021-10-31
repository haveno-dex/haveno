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

package haveno.desktop.main.portfolio.pendingtrades.steps.seller;

import haveno.desktop.main.portfolio.pendingtrades.PendingTradesViewModel;
import haveno.desktop.main.portfolio.pendingtrades.steps.buyer.BuyerStep4View;

import haveno.core.locale.Res;

public class SellerStep4View extends BuyerStep4View {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, Initialisation
    ///////////////////////////////////////////////////////////////////////////////////////////

    public SellerStep4View(PendingTradesViewModel model) {
        super(model);
    }

    @Override
    protected String getBtcTradeAmountLabel() {
        return Res.get("portfolio.pending.step5_seller.sold");
    }

    @Override
    protected String getFiatTradeAmountLabel() {
        return Res.get("portfolio.pending.step5_seller.received");
    }
}
