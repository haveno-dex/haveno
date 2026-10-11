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

import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.common.ThreadUtils;
import haveno.common.UserThread;
import haveno.core.locale.Res;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.trade.HavenoUtils;
import haveno.core.util.FormattingUtils;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.VBox;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import java.math.BigInteger;

@Slf4j
public class RemoveOfferWindow extends ConfirmationWindow<RemoveOfferWindow> {
    private final Offer offer;
    private BigInteger reservedAmount;
    private VBox funds;
    private final boolean sharedFunds;

    public RemoveOfferWindow(Offer offer, BigInteger reservedAmount, boolean sharedFunds) {
        super(500);
        this.offer = offer;
        this.reservedAmount = reservedAmount;
        this.sharedFunds = sharedFunds;
        destructive = true;
        headLine = Res.get("popup.removeOffer.title");
        actionButtonText = Res.get("shared.removeOffer");
        closeButtonText = Res.get("popup.removeOffer.keep");
    }

    public static void show(Offer offer, boolean sharedFunds, Runnable onRemove) {
        RemoveOfferWindow window = new RemoveOfferWindow(offer, null, sharedFunds);
        window.dontShowAgainId("RemoveOfferWarning").onAction(onRemove).show();
        if (sharedFunds) return;
        ThreadUtils.submitToPool(() -> {
            BigInteger amount = null;
            try {
                amount = offer.getReservedAmount();
            } catch (Exception e) {
                log.warn("Could not read reserved amount for offer {}: {}", offer.getId(), e.getMessage());
            }
            BigInteger reserved = amount;
            UserThread.execute(() -> {
                window.reservedAmount = reserved;
                if (window.funds != null) window.funds.getChildren().setAll(window.fundsContent());
            });
        });
    }

    @Override
    public String toString() {
        return super.toString() + ": " + offer.getId();
    }

    @Override
    protected void addContent() {
        String price = offer.getPrice() == null ? Res.get("shared.na") : FormattingUtils.formatPrice(offer.getPrice()) + " " + offer.getCounterCurrencyCode() + "/XMR";
        VBox header = hero(MaterialDesignIcon.DELETE, "confirm-send-icon", headLine,
                HeroWindowComponents.amount(HavenoUtils.formatXmr(offer.getAmount()), "XMR", "review-hero-amount", true),
                Res.get("popup.removeOffer.subtitle", StringUtils.capitalize(Res.get(offer.getDirection() == OfferDirection.BUY ? "shared.buy" : "shared.sell")),
                        price, Res.get(offer.getPaymentMethod().getId())));
        header.getStyleClass().add("hero-accent-red");
        funds = new VBox(fundsContent());
        addReview(header, sheet(detailRow(Res.get("shared.offerId"), selectableValue(offer.getShortId())), funds,
                footer(MaterialDesignIcon.INFORMATION_OUTLINE, Res.get("popup.removeOffer.footer", Res.get("funds.tab.withdrawal")))));
    }

    private Node fundsContent() {
        if (sharedFunds || reservedAmount == null || reservedAmount.signum() == 0) {
            return new VBox(3, valueLabel(Res.get(sharedFunds ? "popup.removeOffer.shared.title" : "popup.removeOffer.unknown.title")),
                    HeroWindowComponents.label(Res.get(sharedFunds ? "popup.removeOffer.shared.body" : "popup.removeOffer.unknown.body"), "review-footer"));
        } else {
            var amount = selectableValue(HavenoUtils.formatXmr(reservedAmount, true));
            amount.getStyleClass().add("review-strong-value");
            VBox value = new VBox(2, amount,
                    rowLabel(Res.get("popup.removeOffer.available")));
            value.setAlignment(Pos.CENTER_RIGHT);
            return detailRow(Res.get("popup.removeOffer.unlocked"), value);
        }
    }
}
