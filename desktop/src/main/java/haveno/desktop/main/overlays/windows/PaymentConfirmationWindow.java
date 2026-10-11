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
import haveno.core.locale.Res;
import haveno.core.payment.payload.AssetAccountPayload;
import haveno.core.payment.payload.CashDepositAccountPayload;
import haveno.core.payment.payload.HalCashAccountPayload;
import haveno.core.payment.payload.BlikAccountPayload;
import haveno.core.payment.payload.F2FAccountPayload;
import haveno.core.payment.payload.PayByMailAccountPayload;
import haveno.core.payment.payload.USPostalMoneyOrderAccountPayload;
import haveno.core.payment.payload.MoneyGramAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.SepaAccountPayload;
import haveno.core.payment.payload.SepaInstantAccountPayload;
import haveno.core.payment.payload.WesternUnionAccountPayload;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.user.DontShowAgainLookup;
import javafx.scene.Node;
import javafx.geometry.Insets;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/** Review of the external payment before advancing the trade protocol. */
public class PaymentConfirmationWindow extends ConfirmationWindow<PaymentConfirmationWindow> {
    private final Trade trade;
    private final PaymentAccountPayload account;
    private final boolean sent;
    private final String holderName;
    private final boolean signing;

    public PaymentConfirmationWindow(Trade trade, PaymentAccountPayload account) {
        this(trade, account, true, null, false);
    }

    public PaymentConfirmationWindow(Trade trade, PaymentAccountPayload account, String holderName, boolean signing) {
        this(trade, account, false, holderName, signing);
    }

    private PaymentConfirmationWindow(Trade trade, PaymentAccountPayload account, boolean sent, String holderName, boolean signing) {
        super(sent ? 500 : 560);
        this.trade = trade;
        this.account = account;
        this.sent = sent;
        this.holderName = holderName;
        this.signing = signing;
        headLine = Res.get(sent ? "portfolio.pending.confirmSent.title" : "portfolio.pending.confirmReceived.title");
        actionButtonText = Res.get(sent ? "portfolio.pending.confirmSent.action" : "portfolio.pending.confirmReceived.action");
        if (sent) closeButtonText = Res.get("portfolio.pending.confirmSent.cancel");
    }

    @Override
    public String toString() {
        return super.toString() + ": " + trade.getId();
    }

    @Override
    protected void addContent() {
        String method = Res.get(trade.getOffer().getPaymentMethod().getId());
        VBox header = hero(sent ? MaterialDesignIcon.SEND : MaterialDesignIcon.CHECK_ALL, "confirm-send-icon", headLine,
                HeroWindowComponents.amount(HeroWindowComponents.volume(trade.getVolume()), trade.getOffer().getCounterCurrencyCode(), "review-hero-amount", true),
                sent ? Res.get("portfolio.pending.confirmSent.subtitle", method, HavenoUtils.formatXmr(trade.getAmount(), true, 8)) :
                        Res.get("portfolio.pending.confirmReceived.subtitle", method));
        header.getStyleClass().add(sent ? "hero-accent-primary" : "hero-accent-green");
        VBox content = new VBox(14);
        if (sent) {
            VBox details = new VBox();
            String recipient = account == null ? null : account.getPaymentDetailsForTradePopup();
            if (recipient != null && !recipient.isBlank()) {
                Node value;
                if (account instanceof SepaAccountPayload sepa) {
                    value = new VBox(3, selectableLabel(sepa.getHolderName(), "confirm-send-row-value", true), selectableLabel(sepa.getIban(), "review-mono", true));
                    recipient = sepa.getHolderName() + "\n" + sepa.getIban();
                } else if (account instanceof SepaInstantAccountPayload sepa) {
                    value = new VBox(3, selectableLabel(sepa.getHolderName(), "confirm-send-row-value", true), selectableLabel(sepa.getIban(), "review-mono", true));
                    recipient = sepa.getHolderName() + "\n" + sepa.getIban();
                } else {
                    value = selectableLabel(recipient, account instanceof AssetAccountPayload ? "review-mono" : "confirm-send-row-value", true);
                }
                details.getChildren().add(sheetGroup(Res.get("portfolio.pending.confirmSent.paidTo"), value, copyIcon(recipient)));
            }
            details.getChildren().add(detailRow(Res.get("shared.tradeId"), selectableValue(trade.getShortId())));
            details.getChildren().add(footer(MaterialDesignIcon.INFORMATION_OUTLINE, Res.get(account instanceof HalCashAccountPayload || account instanceof BlikAccountPayload ? "portfolio.pending.confirmSent.footer.code" :
                    account instanceof CashDepositAccountPayload || account instanceof WesternUnionAccountPayload || account instanceof MoneyGramAccountPayload ||
                    account instanceof F2FAccountPayload || account instanceof PayByMailAccountPayload || account instanceof USPostalMoneyOrderAccountPayload ?
                            "portfolio.pending.confirmSent.footer.sent" : "portfolio.pending.confirmSent.footer")));
            content.getChildren().add(sheet(details.getChildren().toArray(new Node[0])));
            Node requirement = paymentRequirement();
            if (requirement != null) content.getChildren().add(requirement);
        } else {
            if (!(account instanceof AssetAccountPayload) && holderName != null && !holderName.isBlank()) {
                content.getChildren().add(callout(MaterialDesignIcon.ACCOUNT_CHECK, "hero-accent-amber",
                        Res.get("portfolio.pending.confirmReceived.name.title"), Res.get("portfolio.pending.confirmReceived.name.body"), holderName));
            }
            var releaseAmount = selectableValue(HavenoUtils.formatXmr(trade.getAmount(), true));
            releaseAmount.getStyleClass().add("review-strong-value");
            content.getChildren().add(sheet(detailRow(Res.get("portfolio.pending.confirmReceived.release"), releaseAmount),
                    detailRow(Res.get("portfolio.pending.confirmReceived.deposit"), selectableValue(HavenoUtils.formatXmr(trade.getSellerSecurityDepositBeforeMiningFee(), true))),
                    footer(MaterialDesignIcon.ALERT_CIRCLE_OUTLINE, Res.get("portfolio.pending.confirmReceived.footer"))));
            if (signing) content.getChildren().add(callout(MaterialDesignIcon.SEAL, "hero-accent-purple",
                    Res.get("portfolio.pending.confirmReceived.sign.title"), Res.get("portfolio.pending.confirmReceived.sign.body"), null));
        }
        addReview(header, content);
        if (!sent) GridPane.setMargin(pinnedScrollPane, new Insets(14, 0, 0, 0));
    }

    private Node paymentRequirement() {
        if (account instanceof CashDepositAccountPayload cash) {
            return callout(MaterialDesignIcon.RECEIPT, "hero-accent-amber", Res.get("portfolio.pending.instructions.receipt.title"),
                    Res.get("portfolio.pending.instructions.receipt.body") + " " + Res.get("portfolio.pending.instructions.confirm.cash"), cash.getHolderEmail());
        }
        if (account instanceof WesternUnionAccountPayload western) {
            return callout(MaterialDesignIcon.RECEIPT, "hero-accent-amber", Res.get("portfolio.pending.instructions.westernUnion.title"),
                    Res.get("portfolio.pending.instructions.westernUnion.body"), western.getEmail());
        }
        if (account instanceof MoneyGramAccountPayload moneyGram) {
            return callout(MaterialDesignIcon.RECEIPT, "hero-accent-amber", Res.get("portfolio.pending.instructions.moneyGram.title"),
                    Res.get("portfolio.pending.instructions.moneyGram.body"), moneyGram.getEmail());
        }
        if (account instanceof HalCashAccountPayload halCash) {
            return callout(MaterialDesignIcon.MESSAGE_TEXT, "hero-accent-amber", Res.get("portfolio.pending.instructions.halCash.title"),
                    Res.get("portfolio.pending.instructions.halCash.body", trade.getShortId(), halCash.getMobileNr()), null);
        }
        return null;
    }

    public static String methodConfirmationKey(PaymentAccountPayload account) {
        if (account instanceof CashDepositAccountPayload) return "confirmPaperReceiptSent";
        if (account instanceof WesternUnionAccountPayload) return "westernUnionMTCNSent";
        if (account instanceof MoneyGramAccountPayload) return "moneyGramMTCNSent";
        if (account instanceof HalCashAccountPayload) return "halCashCodeInfo";
        return null;
    }

    @Override
    protected void onDontShowAgainSelected(boolean selected) {
        super.onDontShowAgainSelected(selected);
        if (sent) {
            DontShowAgainLookup.dontShowAgain("confirmPaymentSent", selected);
            String methodKey = methodConfirmationKey(account);
            if (methodKey != null) DontShowAgainLookup.dontShowAgain(methodKey, selected);
        }
    }

}
