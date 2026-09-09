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

package haveno.desktop.main.portfolio.pendingtrades.steps.seller;

import haveno.core.locale.Res;
import haveno.core.payment.payload.F2FAccountPayload;
import haveno.desktop.components.paymentmethods.F2FForm;
import haveno.desktop.main.portfolio.pendingtrades.PendingTradesViewModel;
import haveno.desktop.main.portfolio.pendingtrades.steps.TradeStepView;
import haveno.desktop.main.portfolio.pendingtrades.TradeFormPane;

import static com.google.common.base.Preconditions.checkNotNull;

public class SellerStep2View extends TradeStepView {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, Initialisation
    ///////////////////////////////////////////////////////////////////////////////////////////

    public SellerStep2View(PendingTradesViewModel model) {
        super(model);
    }

    @Override
    protected void addContent() {
        addInfoBlock();
        gridPane.add(createAmountPanel(Res.get("portfolio.pending.tradeView.expectedPayment")), 0, ++gridRow, 2, 1);
        checkNotNull(model.dataModel.getTrade(), "No trade found");
        checkNotNull(model.dataModel.getTrade().getOffer(), "No offer found");
        if (model.dataModel.getSellersPaymentAccountPayload() instanceof F2FAccountPayload) {
            TradeFormPane details = new TradeFormPane();
            F2FForm.addStep2Form(details, -1, model.dataModel.getSellersPaymentAccountPayload(),
                    model.dataModel.getTrade().getOffer(), 0, false);
            details.finish(false);
            gridPane.add(details, 0, ++gridRow, 2, 1);
        }
    }

    @Override
    public void activate() {
        super.activate();
    }

    @Override
    public void deactivate() {
        super.deactivate();
    }

    @Override
    protected void onPendingTradesInitialized() {
        super.onPendingTradesInitialized();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Info
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected String getInfoBlockTitle() {
        return Res.get("portfolio.pending.tradeView.waitingBuyerTitle");
    }

    @Override
    protected String getInfoText() {
        return Res.get("portfolio.pending.tradeView.waitingBuyerInfo", getCurrencyCode(trade));
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Warning
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected String getFirstHalfOverWarnText() {
        return Res.get("portfolio.pending.tradeView.waitingBuyerHalf");
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Dispute
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected String getPeriodOverWarnText() {
        return Res.get("portfolio.pending.tradeView.waitingBuyerExpired");
    }

    @Override
    protected void applyOnDisputeOpened() {
    }
}


