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
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.payment.payload.AmazonGiftCardAccountPayload;
import haveno.core.payment.payload.AssetAccountPayload;
import haveno.core.payment.payload.BlikAccountPayload;
import haveno.core.payment.payload.CashDepositAccountPayload;
import haveno.core.payment.payload.F2FAccountPayload;
import haveno.core.payment.payload.FasterPaymentsAccountPayload;
import haveno.core.payment.payload.HalCashAccountPayload;
import haveno.core.payment.payload.MoneyGramAccountPayload;
import haveno.core.payment.payload.PayByMailAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.SwiftAccountPayload;
import haveno.core.payment.payload.USPostalMoneyOrderAccountPayload;
import haveno.core.payment.payload.WesternUnionAccountPayload;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import java.util.ArrayList;
import java.util.List;

public class PaymentInstructionsWindow extends ReviewInfoWindow<PaymentInstructionsWindow> {
    private final Trade trade;
    private final PaymentAccountPayload account;
    private final boolean sending;
    private final String holderName;
    private final boolean signing;
    private final List<Node> rows = new ArrayList<>();

    public PaymentInstructionsWindow(Trade trade, PaymentAccountPayload account, boolean sending, String holderName, boolean signing) {
        this.trade = trade;
        this.account = account;
        this.sending = sending;
        this.holderName = holderName;
        this.signing = signing;
        headLine = text(sending ? "send.title" : "received.title");
        if (sending) {
            actionButtonText = text("send.details");
            hideCloseButton = true;
        } else {
            closeButtonText = text("gotIt");
        }
    }

    @Override
    public String toString() {
        return super.toString() + ": " + trade.getId();
    }

    @Override
    protected void addMessage() {
        rows.clear();
        String method = Res.get(trade.getOffer().getPaymentMethod().getId());
        String amount = HeroWindowComponents.volume(trade.getVolume()) + " " + trade.getOffer().getCounterCurrencyCode();
        String currency = CurrencyUtil.getNameByCode(trade.getOffer().getCounterCurrencyCode());
        if (sending) addSendingRows(amount, currency);
        else addReceivingRows(amount, currency, method);
        boolean periodStarted = trade.isDepositsFinalized();
        Label rightLabel = HeroWindowComponents.label(sending && !periodStarted ? Res.get("portfolio.pending.tradeView.deadline") :
                text(sending ? "send.deadline" : "received.release"), "review-strip-label");
        Node rightValue;
        if (sending) {
            Label deadline = HeroWindowComponents.label(periodStarted ? HeroWindowComponents.dateTime(trade.getMaxTradePeriodDate()) :
                    Res.get("portfolio.pending.remainingTimeDetail.startsAfter", Trade.NUM_BLOCKS_DEPOSITS_FINALIZED), "review-pill");
            deadline.setGraphic(HeroWindowComponents.icon(MaterialDesignIcon.CALENDAR_CLOCK, "14", "glyph-icon"));
            rightValue = deadline;
        } else {
            rightValue = HeroWindowComponents.amount(HavenoUtils.formatXmr(trade.getAmount()), "XMR", "review-small-amount", false);
        }
        VBox right = new VBox(3, rightLabel, rightValue);
        right.setAlignment(Pos.CENTER_RIGHT);
        VBox left = new VBox(3, HeroWindowComponents.label(sending && account instanceof CashDepositAccountPayload ? text("send.cash") :
                text(sending ? "send.amount" : "received.amount", method), "review-strip-label"),
                HeroWindowComponents.amount(HeroWindowComponents.volume(trade.getVolume()), trade.getOffer().getCounterCurrencyCode(), "review-strip-amount", false));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox strip = new HBox(12, left, spacer, right);
        strip.setAlignment(Pos.CENTER_LEFT);
        strip.getStyleClass().add("review-strip");
        addReview(sending ? ACCENT_PRIMARY : ACCENT_GREEN,
                createHeader(sending ? account instanceof CashDepositAccountPayload ? MaterialDesignIcon.CASH : MaterialDesignIcon.BANK : MaterialDesignIcon.CASH_MULTIPLE,
                        headLine, text(sending ? "send.subtitle" : "received.subtitle", trade.getShortId())),
                strip, HeroWindowComponents.rows(rows.toArray(new Node[0])));
    }

    private void addSendingRows(String amount, String currency) {
        if (account instanceof AssetAccountPayload) {
            row(MaterialDesignIcon.SWAP_HORIZONTAL, ACCENT_PRIMARY, text("crypto.title", currency), text("crypto.body"));
        } else if (account instanceof CashDepositAccountPayload cash) {
            row(MaterialDesignIcon.BANK, ACCENT_PRIMARY, text("cash.title"), text("cash.body", amount));
            row(MaterialDesignIcon.RECEIPT, ACCENT_AMBER, text("receipt.title"), text("receipt.body"));
            email(cash.getHolderEmail());
        } else if (account instanceof WesternUnionAccountPayload western) {
            row(MaterialDesignIcon.CASH, ACCENT_PRIMARY, text("pay.title"), Res.get("portfolio.pending.step2_buyer.westernUnion", amount).trim());
            row(MaterialDesignIcon.RECEIPT, ACCENT_AMBER, text("westernUnion.title"), text("westernUnion.body"));
            email(western.getEmail());
        } else if (account instanceof MoneyGramAccountPayload moneyGram) {
            row(MaterialDesignIcon.CASH, ACCENT_PRIMARY, text("pay.title"), Res.get("portfolio.pending.step2_buyer.moneyGram", amount).trim());
            row(MaterialDesignIcon.RECEIPT, ACCENT_AMBER, text("moneyGram.title"), text("moneyGram.body"));
            email(moneyGram.getEmail());
        } else if (account instanceof F2FAccountPayload) {
            row(MaterialDesignIcon.ACCOUNT_MULTIPLE, ACCENT_PRIMARY, text("f2f.title"), Res.get("portfolio.pending.step2_buyer.f2f", amount).trim());
        } else if (account instanceof USPostalMoneyOrderAccountPayload) {
            row(MaterialDesignIcon.EMAIL, ACCENT_PRIMARY, text("postal.title"), Res.get("portfolio.pending.step2_buyer.postal", amount).trim());
        } else if (account instanceof PayByMailAccountPayload) {
            row(MaterialDesignIcon.EMAIL, ACCENT_PRIMARY, text("mail.title"), text("mail.body", amount));
        } else if (account instanceof BlikAccountPayload) {
            row(MaterialDesignIcon.MESSAGE_TEXT, ACCENT_PRIMARY, text("blik.title"), Res.get("portfolio.pending.step2_buyer.pay.blik", amount).trim());
        } else {
            row(MaterialDesignIcon.BANK, ACCENT_PRIMARY, text("pay.title"), text("pay.body"));
            if (account instanceof FasterPaymentsAccountPayload) row(MaterialDesignIcon.ACCOUNT_CHECK, ACCENT_AMBER, text("fasterPayments.title"), text("fasterPayments.body"));
            if (account instanceof HalCashAccountPayload halCash) row(MaterialDesignIcon.MESSAGE_TEXT, ACCENT_AMBER,
                    text("halCash.title"), text("halCash.body", trade.getShortId(), halCash.getMobileNr()));
        }
        if (shouldShowReferenceWarning(account)) row(MaterialDesignIcon.MESSAGE_ALERT, ACCENT_AMBER, text("reference.title"), text("reference.body"));
        boolean fees = !(account instanceof AssetAccountPayload || account instanceof WesternUnionAccountPayload ||
                account instanceof MoneyGramAccountPayload || account instanceof USPostalMoneyOrderAccountPayload ||
                account instanceof F2FAccountPayload || account instanceof PayByMailAccountPayload ||
                account instanceof HalCashAccountPayload || account instanceof BlikAccountPayload);
        if (fees) row(MaterialDesignIcon.CASH, ACCENT_GREEN, text(account instanceof SwiftAccountPayload ? "swift.title" : "fees.title"),
                text(account instanceof SwiftAccountPayload ? "swift.body" : "fees.body"));
        String confirmation = account instanceof CashDepositAccountPayload ? "confirm.cash" :
                account instanceof WesternUnionAccountPayload || account instanceof MoneyGramAccountPayload ? "confirm.code" :
                account instanceof HalCashAccountPayload || account instanceof BlikAccountPayload ? "confirm.paymentCode" :
                account instanceof F2FAccountPayload || account instanceof PayByMailAccountPayload || account instanceof USPostalMoneyOrderAccountPayload ? "confirm.sent" : "confirm.body";
        row(MaterialDesignIcon.CHECK_CIRCLE, ACCENT_PURPLE, text("confirm.title"), text(confirmation));
    }

    private void addReceivingRows(String amount, String currency, String method) {
        if (account instanceof AssetAccountPayload asset) {
            HBox row = row(MaterialDesignIcon.SWAP_HORIZONTAL, ACCENT_PRIMARY, text("received.crypto.title", currency), text("received.crypto.body", amount, Res.get("portfolio.pending.step3_seller.crypto." +
                    ("XMR".equals(trade.getOffer().getCounterCurrencyCode()) ? "wallet" : "explorer"), currency)));
            HeroWindowComponents.rowText(row).getChildren().add(HeroWindowComponents.copyValue(asset.getAddress(), true));
        } else {
            String methodBody = null;
            if (account instanceof USPostalMoneyOrderAccountPayload) methodBody = Res.get("portfolio.pending.step3_seller.postal", "", amount);
            else if (account instanceof PayByMailAccountPayload) methodBody = Res.get("portfolio.pending.step3_seller.payByMail", "", amount);
            if (account instanceof WesternUnionAccountPayload || account instanceof MoneyGramAccountPayload) {
                row(MaterialDesignIcon.RECEIPT, ACCENT_AMBER, text("received.receipt.title"),
                        text(account instanceof WesternUnionAccountPayload ? "received.westernUnion.body" : "received.moneyGram.body"));
                row(MaterialDesignIcon.CASH, ACCENT_PRIMARY, text("received.collect.title"), text("received.collect.body", method));
            } else if (account instanceof HalCashAccountPayload) {
                row(MaterialDesignIcon.MESSAGE_TEXT, ACCENT_PRIMARY, text("received.halCash.title"), text("received.halCash.body"));
                row(MaterialDesignIcon.CASH, ACCENT_AMBER, text("received.halCash.collect.title"), text("received.halCash.collect.body"));
            } else if (account instanceof BlikAccountPayload) {
                row(MaterialDesignIcon.MESSAGE_TEXT, ACCENT_PRIMARY, text("received.blik.title"), text("received.blik.body"));
            } else if (account instanceof AmazonGiftCardAccountPayload) {
                row(MaterialDesignIcon.EMAIL, ACCENT_PRIMARY, text("received.gift.title"), text("received.gift.body"));
            } else if (account instanceof F2FAccountPayload) {
                row(MaterialDesignIcon.ACCOUNT_MULTIPLE, ACCENT_PRIMARY, text("received.inPerson.title"), text("received.inPerson.body", amount));
            } else {
                row(methodBody == null ? MaterialDesignIcon.BANK : MaterialDesignIcon.EMAIL, ACCENT_PRIMARY,
                        methodBody == null ? text("received.bank.title", amount) : text("received.method.title", method),
                        methodBody == null ? text("received.bank.body") : methodBody.trim());
            }
            if (account instanceof CashDepositAccountPayload) row(MaterialDesignIcon.RECEIPT, ACCENT_AMBER, text("received.cash.title"), text("received.cash.body"));
            if (holderName != null && !holderName.isBlank()) row(MaterialDesignIcon.ACCOUNT_CHECK, ACCENT_AMBER, text("received.name.title", holderName), text("received.name.body"));
            if (signing) row(MaterialDesignIcon.SEAL, ACCENT_PURPLE, Res.get("portfolio.pending.confirmReceived.sign.title"), Res.get("portfolio.pending.confirmReceived.sign.body"));
        }
        row(MaterialDesignIcon.CHECK_CIRCLE, ACCENT_GREEN, text("received.confirm.title"), text(account instanceof AssetAccountPayload ? "received.confirm.body.crypto" : "received.confirm.body"));
    }

    private HBox row(MaterialDesignIcon icon, String accent, String title, String body) {
        HBox row = HeroWindowComponents.featureRow(icon, accent, title, body);
        rows.add(row);
        return row;
    }

    private void email(String email) {
        HBox row = row(MaterialDesignIcon.EMAIL, ACCENT_AMBER, text("email.title"), email == null || email.isBlank() ? text("email.missing") : null);
        if (email != null && !email.isBlank()) {
            HBox value = HeroWindowComponents.copyValue(email, true);
            HeroWindowComponents.rowText(row).getChildren().add(value);
            VBox.setMargin(value, new Insets(5, 0, 0, 0));
        }
    }

    public static boolean shouldShowReferenceWarning(PaymentAccountPayload account) {
        return !(account instanceof AssetAccountPayload || account instanceof WesternUnionAccountPayload ||
                account instanceof MoneyGramAccountPayload || account instanceof F2FAccountPayload ||
                account instanceof PayByMailAccountPayload || account instanceof HalCashAccountPayload || account instanceof BlikAccountPayload);
    }

    private static String text(String key, Object... args) {
        return Res.get("portfolio.pending.instructions." + key, args);
    }
}
