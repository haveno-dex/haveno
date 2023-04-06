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

import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.app.DevEnv;
import haveno.common.util.Tuple4;
import haveno.core.locale.Res;
import haveno.core.network.MessageState;
import haveno.core.offer.Offer;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.PaymentAccountUtil;
import haveno.core.payment.payload.AssetAccountPayload;
import haveno.core.payment.payload.CashByMailAccountPayload;
import haveno.core.payment.payload.CashDepositAccountPayload;
import haveno.core.payment.payload.F2FAccountPayload;
import haveno.core.payment.payload.FasterPaymentsAccountPayload;
import haveno.core.payment.payload.HalCashAccountPayload;
import haveno.core.payment.payload.MoneyGramAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.payload.SwiftAccountPayload;
import haveno.core.payment.payload.USPostalMoneyOrderAccountPayload;
import haveno.core.payment.payload.WesternUnionAccountPayload;
import haveno.core.trade.Trade;
import haveno.core.user.DontShowAgainLookup;
import haveno.core.util.VolumeUtil;
import haveno.desktop.components.BusyAnimation;
import haveno.desktop.components.TextFieldWithCopyIcon;
import haveno.desktop.components.TitledGroupBg;
import haveno.desktop.components.paymentmethods.AchTransferForm;
import haveno.desktop.components.paymentmethods.AdvancedCashForm;
import haveno.desktop.components.paymentmethods.AliPayForm;
import haveno.desktop.components.paymentmethods.AmazonGiftCardForm;
import haveno.desktop.components.paymentmethods.AssetsForm;
import haveno.desktop.components.paymentmethods.BizumForm;
import haveno.desktop.components.paymentmethods.CapitualForm;
import haveno.desktop.components.paymentmethods.CashByMailForm;
import haveno.desktop.components.paymentmethods.CashDepositForm;
import haveno.desktop.components.paymentmethods.CelPayForm;
import haveno.desktop.components.paymentmethods.ChaseQuickPayForm;
import haveno.desktop.components.paymentmethods.ClearXchangeForm;
import haveno.desktop.components.paymentmethods.DomesticWireTransferForm;
import haveno.desktop.components.paymentmethods.F2FForm;
import haveno.desktop.components.paymentmethods.FasterPaymentsForm;
import haveno.desktop.components.paymentmethods.HalCashForm;
import haveno.desktop.components.paymentmethods.ImpsForm;
import haveno.desktop.components.paymentmethods.InteracETransferForm;
import haveno.desktop.components.paymentmethods.JapanBankTransferForm;
import haveno.desktop.components.paymentmethods.MoneseForm;
import haveno.desktop.components.paymentmethods.MoneyBeamForm;
import haveno.desktop.components.paymentmethods.MoneyGramForm;
import haveno.desktop.components.paymentmethods.NationalBankForm;
import haveno.desktop.components.paymentmethods.NeftForm;
import haveno.desktop.components.paymentmethods.NequiForm;
import haveno.desktop.components.paymentmethods.PaxumForm;
import haveno.desktop.components.paymentmethods.PayseraForm;
import haveno.desktop.components.paymentmethods.PaytmForm;
import haveno.desktop.components.paymentmethods.PerfectMoneyForm;
import haveno.desktop.components.paymentmethods.PixForm;
import haveno.desktop.components.paymentmethods.PopmoneyForm;
import haveno.desktop.components.paymentmethods.PromptPayForm;
import haveno.desktop.components.paymentmethods.RevolutForm;
import haveno.desktop.components.paymentmethods.RtgsForm;
import haveno.desktop.components.paymentmethods.SameBankForm;
import haveno.desktop.components.paymentmethods.SatispayForm;
import haveno.desktop.components.paymentmethods.SepaForm;
import haveno.desktop.components.paymentmethods.SepaInstantForm;
import haveno.desktop.components.paymentmethods.SpecificBankForm;
import haveno.desktop.components.paymentmethods.StrikeForm;
import haveno.desktop.components.paymentmethods.SwiftForm;
import haveno.desktop.components.paymentmethods.SwishForm;
import haveno.desktop.components.paymentmethods.TikkieForm;
import haveno.desktop.components.paymentmethods.TransferwiseForm;
import haveno.desktop.components.paymentmethods.TransferwiseUsdForm;
import haveno.desktop.components.paymentmethods.USPostalMoneyOrderForm;
import haveno.desktop.components.paymentmethods.UpholdForm;
import haveno.desktop.components.paymentmethods.UpiForm;
import haveno.desktop.components.paymentmethods.VerseForm;
import haveno.desktop.components.paymentmethods.WeChatPayForm;
import haveno.desktop.components.paymentmethods.WesternUnionForm;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.overlays.windows.SetXmrTxKeyWindow;
import haveno.desktop.main.portfolio.pendingtrades.PendingTradesViewModel;
import haveno.desktop.main.portfolio.pendingtrades.steps.TradeStepView;
import haveno.desktop.util.Layout;
import haveno.desktop.util.Transitions;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkNotNull;
import static haveno.desktop.util.FormBuilder.addButtonBusyAnimationLabel;
import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextFieldWithCopyIcon;
import static haveno.desktop.util.FormBuilder.addTitledGroupBg;
import static haveno.desktop.util.FormBuilder.addTopLabelTextFieldWithCopyIcon;

public class BuyerStep2View extends TradeStepView {

    private Button confirmButton;
    private Label statusLabel;
    private BusyAnimation busyAnimation;
    private Subscription tradeStatePropertySubscription;
    private Timer timeoutTimer;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, Initialisation
    ///////////////////////////////////////////////////////////////////////////////////////////

    public BuyerStep2View(PendingTradesViewModel model) {
        super(model);
    }

    @Override
    public void activate() {
        super.activate();

        if (timeoutTimer != null)
            timeoutTimer.stop();

        //TODO we get called twice, check why
        if (tradeStatePropertySubscription == null) {
            tradeStatePropertySubscription = EasyBind.subscribe(trade.stateProperty(), state -> {
                if (timeoutTimer != null)
                    timeoutTimer.stop();

                if (trade.isDepositsUnlocked() && !trade.isPaymentSent()) {
                    showPopup();
                } else if (state.ordinal() <= Trade.State.BUYER_SEND_FAILED_PAYMENT_SENT_MSG.ordinal()) {
                    switch (state) {
                    case BUYER_CONFIRMED_IN_UI_PAYMENT_SENT:
                        busyAnimation.play();
                        statusLabel.setText(Res.get("shared.preparingConfirmation"));
                        break;
                    case BUYER_SENT_PAYMENT_SENT_MSG:
                        busyAnimation.play();
                        statusLabel.setText(Res.get("shared.sendingConfirmation"));
                        model.setMessageStateProperty(MessageState.SENT);
                        timeoutTimer = UserThread.runAfter(() -> {
                            busyAnimation.stop();
                            statusLabel.setText(Res.get("shared.sendingConfirmationAgain"));
                        }, 10);
                        break;
                    case BUYER_SAW_ARRIVED_PAYMENT_SENT_MSG:
                        busyAnimation.stop();
                        statusLabel.setText(Res.get("shared.messageArrived"));
                        model.setMessageStateProperty(MessageState.ARRIVED);
                        break;
                    case BUYER_STORED_IN_MAILBOX_PAYMENT_SENT_MSG:
                        busyAnimation.stop();
                        statusLabel.setText(Res.get("shared.messageStoredInMailbox"));
                        model.setMessageStateProperty(MessageState.STORED_IN_MAILBOX);
                        break;
                    case BUYER_SEND_FAILED_PAYMENT_SENT_MSG:
                        // We get a popup and the trade closed, so we dont need to show anything here
                        busyAnimation.stop();
                        statusLabel.setText("");
                        model.setMessageStateProperty(MessageState.FAILED);
                        break;
                    default:
                        log.warn("Unexpected case: State={}, tradeId={} ", state.name(), trade.getId());
                        busyAnimation.stop();
                        statusLabel.setText(Res.get("shared.sendingConfirmationAgain"));
                        break;
                    }
                }
            });
        }
    }

    @Override
    public void deactivate() {
        super.deactivate();

        busyAnimation.stop();

        if (timeoutTimer != null)
            timeoutTimer.stop();

        if (tradeStatePropertySubscription != null) {
            tradeStatePropertySubscription.unsubscribe();
            tradeStatePropertySubscription = null;
        }
    }

    @Override
    protected void onPendingTradesInitialized() {
        super.onPendingTradesInitialized();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Content
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void addContent() {
        gridPane.getColumnConstraints().get(1).setHgrow(Priority.ALWAYS);

        addTradeInfoBlock();

        PaymentAccountPayload paymentAccountPayload = model.dataModel.getSellersPaymentAccountPayload();
        String paymentMethodId = paymentAccountPayload != null ? paymentAccountPayload.getPaymentMethodId() : "<missing payment account payload>";
        TitledGroupBg accountTitledGroupBg = addTitledGroupBg(gridPane, ++gridRow, 4,
                Res.get("portfolio.pending.step2_buyer.startPaymentUsing", Res.get(paymentMethodId)),
                Layout.COMPACT_GROUP_DISTANCE);
        TextFieldWithCopyIcon field = addTopLabelTextFieldWithCopyIcon(gridPane, gridRow, 0,
                Res.get("portfolio.pending.step2_buyer.amountToTransfer"),
                model.getFiatVolume(),
                Layout.COMPACT_FIRST_ROW_AND_GROUP_DISTANCE).second;
        field.setCopyWithoutCurrencyPostFix(true);

        switch (paymentMethodId) {
            case PaymentMethod.UPHOLD_ID:
                gridRow = UpholdForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.MONEY_BEAM_ID:
                gridRow = MoneyBeamForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.POPMONEY_ID:
                gridRow = PopmoneyForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.REVOLUT_ID:
                gridRow = RevolutForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.PERFECT_MONEY_ID:
                gridRow = PerfectMoneyForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.SEPA_ID:
                gridRow = SepaForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.SEPA_INSTANT_ID:
                gridRow = SepaInstantForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.FASTER_PAYMENTS_ID:
                gridRow = FasterPaymentsForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.NATIONAL_BANK_ID:
                gridRow = NationalBankForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.SAME_BANK_ID:
                gridRow = SameBankForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.SPECIFIC_BANKS_ID:
                gridRow = SpecificBankForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.SWISH_ID:
                gridRow = SwishForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.ALI_PAY_ID:
                gridRow = AliPayForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.WECHAT_PAY_ID:
                gridRow = WeChatPayForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.CLEAR_X_CHANGE_ID:
                gridRow = ClearXchangeForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.CHASE_QUICK_PAY_ID:
                gridRow = ChaseQuickPayForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.INTERAC_E_TRANSFER_ID:
                gridRow = InteracETransferForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.JAPAN_BANK_ID:
                gridRow = JapanBankTransferForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.US_POSTAL_MONEY_ORDER_ID:
                gridRow = USPostalMoneyOrderForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.CASH_DEPOSIT_ID:
                gridRow = CashDepositForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.CASH_BY_MAIL_ID:
                gridRow = CashByMailForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.MONEY_GRAM_ID:
                gridRow = MoneyGramForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.WESTERN_UNION_ID:
                gridRow = WesternUnionForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.HAL_CASH_ID:
                gridRow = HalCashForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.F2F_ID:
                checkNotNull(model.dataModel.getTrade(), "model.dataModel.getTrade() must not be null");
                checkNotNull(model.dataModel.getTrade().getOffer(), "model.dataModel.getTrade().getOffer() must not be null");
                gridRow = F2FForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload, model.dataModel.getTrade().getOffer(), 0);
                break;
            case PaymentMethod.BLOCK_CHAINS_ID:
            case PaymentMethod.BLOCK_CHAINS_INSTANT_ID:
                String labelTitle = Res.get("portfolio.pending.step2_buyer.sellersAddress", getCurrencyName(trade));
                gridRow = AssetsForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload, labelTitle);
                break;
            case PaymentMethod.PROMPT_PAY_ID:
                gridRow = PromptPayForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.ADVANCED_CASH_ID:
                gridRow = AdvancedCashForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.TRANSFERWISE_ID:
                gridRow = TransferwiseForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.TRANSFERWISE_USD_ID:
                gridRow = TransferwiseUsdForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.PAYSERA_ID:
                gridRow = PayseraForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.PAXUM_ID:
                gridRow = PaxumForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.NEFT_ID:
                gridRow = NeftForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.RTGS_ID:
                gridRow = RtgsForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.IMPS_ID:
                gridRow = ImpsForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.UPI_ID:
                gridRow = UpiForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.PAYTM_ID:
                gridRow = PaytmForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.NEQUI_ID:
                gridRow = NequiForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.BIZUM_ID:
                gridRow = BizumForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.PIX_ID:
                gridRow = PixForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.AMAZON_GIFT_CARD_ID:
                gridRow = AmazonGiftCardForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.CAPITUAL_ID:
                gridRow = CapitualForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.CELPAY_ID:
                gridRow = CelPayForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.MONESE_ID:
                gridRow = MoneseForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.SATISPAY_ID:
                gridRow = SatispayForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.TIKKIE_ID:
                gridRow = TikkieForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.VERSE_ID:
                gridRow = VerseForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.STRIKE_ID:
                gridRow = StrikeForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.SWIFT_ID:
                gridRow = SwiftForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload, trade);
                break;
            case PaymentMethod.ACH_TRANSFER_ID:
                gridRow = AchTransferForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            case PaymentMethod.DOMESTIC_WIRE_TRANSFER_ID:
                gridRow = DomesticWireTransferForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
                break;
            default:
                log.error("Not supported PaymentMethod: " + paymentMethodId);
        }

        Trade trade = model.getTrade();
        if (trade != null && model.getUser().getPaymentAccounts() != null) {
            Offer offer = trade.getOffer();
            List<PaymentAccount> possiblePaymentAccounts = PaymentAccountUtil.getPossiblePaymentAccounts(offer,
                    model.getUser().getPaymentAccounts(), model.dataModel.getAccountAgeWitnessService());
            PaymentAccountPayload buyersPaymentAccountPayload = model.dataModel.getBuyersPaymentAccountPayload();
            if (buyersPaymentAccountPayload != null && possiblePaymentAccounts.size() > 1) {
                String id = buyersPaymentAccountPayload.getId();
                possiblePaymentAccounts.stream()
                        .filter(paymentAccount -> paymentAccount.getId().equals(id))
                        .findFirst()
                        .ifPresent(paymentAccount -> {
                            String accountName = paymentAccount.getAccountName();
                            addCompactTopLabelTextFieldWithCopyIcon(gridPane, ++gridRow, 0,
                                    Res.get("portfolio.pending.step2_buyer.buyerAccount"), accountName);
                        });
            }
        }

        GridPane.setRowSpan(accountTitledGroupBg, gridRow - 1);

        Tuple4<Button, BusyAnimation, Label, HBox> tuple3 = addButtonBusyAnimationLabel(gridPane, ++gridRow, 0,
                Res.get("portfolio.pending.step2_buyer.paymentSent"), 10);

        HBox hBox = tuple3.fourth;
        GridPane.setColumnSpan(hBox, 2);
        confirmButton = tuple3.first;
        confirmButton.setOnAction(e -> onPaymentSent());
        busyAnimation = tuple3.second;
        statusLabel = tuple3.third;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Warning
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected String getFirstHalfOverWarnText() {
        return Res.get("portfolio.pending.step2_buyer.warn",
                getCurrencyCode(trade),
                model.getDateForOpenDispute());
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Dispute
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected String getPeriodOverWarnText() {
        return Res.get("portfolio.pending.step2_buyer.openForDispute");
    }

    @Override
    protected void applyOnDisputeOpened() {
    }

    @Override
    protected void updateDisputeState(Trade.DisputeState disputeState) {
        super.updateDisputeState(disputeState);

        confirmButton.setDisable(!trade.confirmPermitted());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // UI Handlers
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void onPaymentSent() {
        if (!model.dataModel.isBootstrappedOrShowPopup()) {
            return;
        }

        if (!model.dataModel.isReadyForTxBroadcast()) {
            return;
        }

        PaymentAccountPayload sellersPaymentAccountPayload = model.dataModel.getSellersPaymentAccountPayload();
        Trade trade = checkNotNull(model.dataModel.getTrade(), "trade must not be null");
        if (sellersPaymentAccountPayload instanceof CashDepositAccountPayload) {
            String key = "confirmPaperReceiptSent";
            if (!DevEnv.isDevMode() && DontShowAgainLookup.showAgain(key)) {
                Popup popup = new Popup();
                popup.headLine(Res.get("portfolio.pending.step2_buyer.paperReceipt.headline"))
                        .feedback(Res.get("portfolio.pending.step2_buyer.paperReceipt.msg"))
                        .onAction(this::showConfirmPaymentSentPopup)
                        .closeButtonText(Res.get("shared.no"))
                        .onClose(popup::hide)
                        .dontShowAgainId(key)
                        .show();
            } else {
                showConfirmPaymentSentPopup();
            }
        } else if (sellersPaymentAccountPayload instanceof WesternUnionAccountPayload) {
            String key = "westernUnionMTCNSent";
            if (!DevEnv.isDevMode() && DontShowAgainLookup.showAgain(key)) {
                String email = ((WesternUnionAccountPayload) sellersPaymentAccountPayload).getEmail();
                Popup popup = new Popup();
                popup.headLine(Res.get("portfolio.pending.step2_buyer.westernUnionMTCNInfo.headline"))
                        .feedback(Res.get("portfolio.pending.step2_buyer.westernUnionMTCNInfo.msg", email))
                        .onAction(this::showConfirmPaymentSentPopup)
                        .actionButtonText(Res.get("shared.yes"))
                        .closeButtonText(Res.get("shared.no"))
                        .onClose(popup::hide)
                        .dontShowAgainId(key)
                        .show();
            } else {
                showConfirmPaymentSentPopup();
            }
        } else if (sellersPaymentAccountPayload instanceof MoneyGramAccountPayload) {
            String key = "moneyGramMTCNSent";
            if (!DevEnv.isDevMode() && DontShowAgainLookup.showAgain(key)) {
                String email = ((MoneyGramAccountPayload) sellersPaymentAccountPayload).getEmail();
                Popup popup = new Popup();
                popup.headLine(Res.get("portfolio.pending.step2_buyer.moneyGramMTCNInfo.headline"))
                        .feedback(Res.get("portfolio.pending.step2_buyer.moneyGramMTCNInfo.msg", email))
                        .onAction(this::showConfirmPaymentSentPopup)
                        .actionButtonText(Res.get("shared.yes"))
                        .closeButtonText(Res.get("shared.no"))
                        .onClose(popup::hide)
                        .dontShowAgainId(key)
                        .show();
            } else {
                showConfirmPaymentSentPopup();
            }
        } else if (sellersPaymentAccountPayload instanceof HalCashAccountPayload) {
            String key = "halCashCodeInfo";
            if (!DevEnv.isDevMode() && DontShowAgainLookup.showAgain(key)) {
                String mobileNr = ((HalCashAccountPayload) sellersPaymentAccountPayload).getMobileNr();
                Popup popup = new Popup();
                popup.headLine(Res.get("portfolio.pending.step2_buyer.halCashInfo.headline"))
                        .feedback(Res.get("portfolio.pending.step2_buyer.halCashInfo.msg",
                                trade.getShortId(), mobileNr))
                        .onAction(this::showConfirmPaymentSentPopup)
                        .actionButtonText(Res.get("shared.yes"))
                        .closeButtonText(Res.get("shared.no"))
                        .onClose(popup::hide)
                        .dontShowAgainId(key)
                        .show();
            } else {
                showConfirmPaymentSentPopup();
            }
        } else if (sellersPaymentAccountPayload instanceof AssetAccountPayload && isXmrTrade()) {
            SetXmrTxKeyWindow setXmrTxKeyWindow = new SetXmrTxKeyWindow();
            setXmrTxKeyWindow
                    .actionButtonText(Res.get("portfolio.pending.step2_buyer.confirmStart.headline"))
                    .onAction(() -> {
                        String txKey = setXmrTxKeyWindow.getTxKey();
                        String txHash = setXmrTxKeyWindow.getTxHash();
                        if (txKey == null || txHash == null || txKey.isEmpty() || txHash.isEmpty()) {
                            UserThread.runAfter(this::showProofWarningPopup, Transitions.DEFAULT_DURATION, TimeUnit.MILLISECONDS);
                            return;
                        }

                        trade.setCounterCurrencyExtraData(txKey);
                        trade.setCounterCurrencyTxId(txHash);

                        model.dataModel.getTradeManager().requestPersistence();
                        showConfirmPaymentSentPopup();
                    })
                    .closeButtonText(Res.get("shared.cancel"))
                    .onClose(setXmrTxKeyWindow::hide)
                    .show();
        } else {
            showConfirmPaymentSentPopup();
        }
    }

    private void showProofWarningPopup() {
        Popup popup = new Popup();
        popup.headLine(Res.get("portfolio.pending.step2_buyer.confirmStart.proof.warningTitle"))
                .confirmation(Res.get("portfolio.pending.step2_buyer.confirmStart.proof.noneProvided"))
                .width(700)
                .actionButtonText(Res.get("portfolio.pending.step2_buyer.confirmStart.warningButton"))
                .onAction(this::showConfirmPaymentSentPopup)
                .closeButtonText(Res.get("shared.cancel"))
                .onClose(popup::hide)
                .show();
    }

    private void showConfirmPaymentSentPopup() {
        String key = "confirmPaymentSent";
        if (!DevEnv.isDevMode() && DontShowAgainLookup.showAgain(key)) {
            Popup popup = new Popup();
            popup.headLine(Res.get("portfolio.pending.step2_buyer.confirmStart.headline"))
                    .confirmation(Res.get("portfolio.pending.step2_buyer.confirmStart.msg", getCurrencyName(trade)))
                    .width(700)
                    .actionButtonText(Res.get("portfolio.pending.step2_buyer.confirmStart.yes"))
                    .onAction(this::confirmPaymentSent)
                    .closeButtonText(Res.get("shared.no"))
                    .onClose(popup::hide)
                    .dontShowAgainId(key)
                    .show();
        } else {
            confirmPaymentSent();
        }
    }

    private void confirmPaymentSent() {
        busyAnimation.play();
        statusLabel.setText(Res.get("shared.sendingConfirmation"));

        model.dataModel.onPaymentSent(() -> {
        }, errorMessage -> {
            busyAnimation.stop();
            new Popup().warning(Res.get("popup.warning.sendMsgFailed")).show();
        });
    }

    private void showPopup() {
        PaymentAccountPayload paymentAccountPayload = model.dataModel.getSellersPaymentAccountPayload();
        if (paymentAccountPayload != null) {
            String message = Res.get("portfolio.pending.step2.confReached");
            String refTextWarn = Res.get("portfolio.pending.step2_buyer.refTextWarn");
            String fees = Res.get("portfolio.pending.step2_buyer.fees");
            String id = trade.getShortId();
            String amount = VolumeUtil.formatVolumeWithCode(trade.getVolume());
            if (paymentAccountPayload instanceof AssetAccountPayload) {
                message += Res.get("portfolio.pending.step2_buyer.altcoin",
                        getCurrencyName(trade),
                        amount);
            } else if (paymentAccountPayload instanceof CashDepositAccountPayload) {
                message += Res.get("portfolio.pending.step2_buyer.cash",
                        amount) +
                        refTextWarn + "\n\n" +
                        fees + "\n\n" +
                        Res.get("portfolio.pending.step2_buyer.cash.extra");
            } else if (paymentAccountPayload instanceof WesternUnionAccountPayload) {
                final String email = ((WesternUnionAccountPayload) paymentAccountPayload).getEmail();
                final String extra = Res.get("portfolio.pending.step2_buyer.westernUnion.extra", email);
                message += Res.get("portfolio.pending.step2_buyer.westernUnion",
                        amount) +
                        extra;
            } else if (paymentAccountPayload instanceof MoneyGramAccountPayload) {
                final String email = ((MoneyGramAccountPayload) paymentAccountPayload).getEmail();
                final String extra = Res.get("portfolio.pending.step2_buyer.moneyGram.extra", email);
                message += Res.get("portfolio.pending.step2_buyer.moneyGram",
                        amount) +
                        extra;
            } else if (paymentAccountPayload instanceof USPostalMoneyOrderAccountPayload) {
                message += Res.get("portfolio.pending.step2_buyer.postal", amount) +
                        refTextWarn;
            } else if (paymentAccountPayload instanceof F2FAccountPayload) {
                message += Res.get("portfolio.pending.step2_buyer.f2f", amount);
            } else if (paymentAccountPayload instanceof FasterPaymentsAccountPayload) {
                message += Res.get("portfolio.pending.step2_buyer.pay", amount) +
                        Res.get("portfolio.pending.step2_buyer.fasterPaymentsHolderNameInfo") + "\n\n" +
                        refTextWarn + "\n\n" +
                        fees;
            } else if (paymentAccountPayload instanceof CashByMailAccountPayload ||
                    paymentAccountPayload instanceof HalCashAccountPayload) {
                message += Res.get("portfolio.pending.step2_buyer.pay", amount);
            } else if (paymentAccountPayload instanceof SwiftAccountPayload) {
                message += Res.get("portfolio.pending.step2_buyer.pay", amount) +
                        refTextWarn + "\n\n" +
                        Res.get("portfolio.pending.step2_buyer.fees.swift");
            } else {
                message += Res.get("portfolio.pending.step2_buyer.pay", amount) +
                        refTextWarn + "\n\n" +
                        fees;
            }

            String key = "startPayment" + trade.getId();
            if (!DevEnv.isDevMode() && DontShowAgainLookup.showAgain(key)) {
                DontShowAgainLookup.dontShowAgain(key, true);
                new Popup().headLine(Res.get("popup.attention.forTradeWithId", id))
                        .attention(message)
                        .show();
            }
        }
    }
}
