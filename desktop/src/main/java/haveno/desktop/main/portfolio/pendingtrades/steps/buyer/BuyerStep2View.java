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

package haveno.desktop.main.portfolio.pendingtrades.steps.buyer;

import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.app.DevEnv;
import haveno.common.util.Tuple4;
import haveno.core.locale.Res;
import haveno.core.offer.Offer;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.PaymentAccountUtil;
import haveno.core.payment.payload.AssetAccountPayload;
import haveno.core.payment.payload.CashDepositAccountPayload;
import haveno.core.payment.payload.F2FAccountPayload;
import haveno.core.payment.payload.FasterPaymentsAccountPayload;
import haveno.core.payment.payload.HalCashAccountPayload;
import haveno.core.payment.payload.MoneyGramAccountPayload;
import haveno.core.payment.payload.PayByMailAccountPayload;
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
import haveno.desktop.components.paymentmethods.AustraliaPayidForm;
import haveno.desktop.components.paymentmethods.BizumForm;
import haveno.desktop.components.paymentmethods.CapitualForm;
import haveno.desktop.components.paymentmethods.CashAppForm;
import haveno.desktop.components.paymentmethods.CashAtAtmForm;
import haveno.desktop.components.paymentmethods.PayByMailForm;
import haveno.desktop.components.paymentmethods.PayPalForm;
import haveno.desktop.components.paymentmethods.PaysafeForm;
import haveno.desktop.components.paymentmethods.CashDepositForm;
import haveno.desktop.components.paymentmethods.CelPayForm;
import haveno.desktop.components.paymentmethods.ChaseQuickPayForm;
import haveno.desktop.components.paymentmethods.ZelleForm;
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
import haveno.desktop.components.paymentmethods.VenmoForm;
import haveno.desktop.components.paymentmethods.VerseForm;
import haveno.desktop.components.paymentmethods.WeChatPayForm;
import haveno.desktop.components.paymentmethods.WesternUnionForm;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.portfolio.pendingtrades.PendingTradesViewModel;
import haveno.desktop.main.portfolio.pendingtrades.steps.TradeStepView;
import haveno.desktop.util.Layout;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.text.Font;

import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;

import java.util.List;

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
    private int paymentAccountGridRow = 0;
    private GridPane paymentAccountGridPane;
    private GridPane moreConfirmationsGridPane;

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
                    busyAnimation.stop();
                    statusLabel.setText("");
                    showPopup();
                } else if (state.ordinal() <= Trade.State.SELLER_RECEIVED_PAYMENT_SENT_MSG.ordinal()) {
                    switch (state) {
                        case BUYER_CONFIRMED_PAYMENT_SENT:
                            busyAnimation.play();
                            statusLabel.setText(Res.get("shared.preparingConfirmation"));
                            break;
                        case BUYER_SENT_PAYMENT_SENT_MSG:
                            busyAnimation.play();
                            statusLabel.setText(Res.get("shared.sendingConfirmation"));
                            timeoutTimer = UserThread.runAfter(() -> {
                                busyAnimation.stop();
                                statusLabel.setText(Res.get("shared.sendingConfirmationAgain"));
                            }, 30);
                            break;
                        case BUYER_STORED_IN_MAILBOX_PAYMENT_SENT_MSG:
                            busyAnimation.stop();
                            statusLabel.setText(Res.get("shared.messageStoredInMailbox"));
                            break;
                        case BUYER_SAW_ARRIVED_PAYMENT_SENT_MSG:
                        case SELLER_RECEIVED_PAYMENT_SENT_MSG:
                            busyAnimation.stop();
                            statusLabel.setText(Res.get("shared.messageArrived"));
                            break;
                        case BUYER_SEND_FAILED_PAYMENT_SENT_MSG:
                            // We get a popup and the trade closed, so we dont need to show anything here
                            busyAnimation.stop();
                            statusLabel.setText("");
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
        createPaymentDetailsGridPane();
        createRecommendationGridPane();

        // attach grid pane based on current state
        EasyBind.subscribe(trade.statePhaseProperty(), newValue -> {
            if (trade.isPaymentSent() || model.getShowPaymentDetailsEarly() || trade.isDepositsFinalized()) {
                attachPaymentDetailsGrid();
            } else {
                attachRecommendationGrid();
            }
        });
    }

    private void createPaymentDetailsGridPane() {
        PaymentAccountPayload paymentAccountPayload = model.dataModel.getSellersPaymentAccountPayload();
        String paymentMethodId = paymentAccountPayload != null ? paymentAccountPayload.getPaymentMethodId() : "<pending>";
        
        paymentAccountGridPane = createGridPane();
        TitledGroupBg accountTitledGroupBg = addTitledGroupBg(paymentAccountGridPane, paymentAccountGridRow, 4,
                Res.get("portfolio.pending.step2_buyer.startPaymentUsing", Res.get(paymentMethodId)),
                Layout.COMPACT_GROUP_DISTANCE);
        TextFieldWithCopyIcon field = addTopLabelTextFieldWithCopyIcon(paymentAccountGridPane, paymentAccountGridRow, 0,
                Res.get("portfolio.pending.step2_buyer.amountToTransfer"),
                model.getFiatVolume(),
                Layout.COMPACT_FIRST_ROW_AND_GROUP_DISTANCE).second;
        field.setCopyWithoutCurrencyPostFix(true);

        //preland: this fixes a textarea layout glitch // TODO: can this be removed now?
        TextArea uiHack = new TextArea();
        uiHack.setMaxHeight(1);
        GridPane.setRowIndex(uiHack, 1);
        GridPane.setMargin(uiHack, new Insets(0, 0, 0, 0));
        uiHack.setVisible(false);
        paymentAccountGridPane.getChildren().add(uiHack);

        switch (paymentMethodId) {
            case PaymentMethod.UPHOLD_ID:
                paymentAccountGridRow = UpholdForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.MONEY_BEAM_ID:
                paymentAccountGridRow = MoneyBeamForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.POPMONEY_ID:
                paymentAccountGridRow = PopmoneyForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.REVOLUT_ID:
                paymentAccountGridRow = RevolutForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.PERFECT_MONEY_ID:
                paymentAccountGridRow = PerfectMoneyForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.SEPA_ID:
                paymentAccountGridRow = SepaForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.SEPA_INSTANT_ID:
                paymentAccountGridRow = SepaInstantForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.FASTER_PAYMENTS_ID:
                paymentAccountGridRow = FasterPaymentsForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.NATIONAL_BANK_ID:
                paymentAccountGridRow = NationalBankForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.AUSTRALIA_PAYID_ID:
                paymentAccountGridRow = AustraliaPayidForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.SAME_BANK_ID:
                paymentAccountGridRow = SameBankForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.SPECIFIC_BANKS_ID:
                paymentAccountGridRow = SpecificBankForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.SWISH_ID:
                paymentAccountGridRow = SwishForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.ALI_PAY_ID:
                paymentAccountGridRow = AliPayForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.WECHAT_PAY_ID:
                paymentAccountGridRow = WeChatPayForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.ZELLE_ID:
                paymentAccountGridRow = ZelleForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.CHASE_QUICK_PAY_ID:
                paymentAccountGridRow = ChaseQuickPayForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.INTERAC_E_TRANSFER_ID:
                paymentAccountGridRow = InteracETransferForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.JAPAN_BANK_ID:
                paymentAccountGridRow = JapanBankTransferForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.US_POSTAL_MONEY_ORDER_ID:
                paymentAccountGridRow = USPostalMoneyOrderForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.CASH_DEPOSIT_ID:
                paymentAccountGridRow = CashDepositForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.PAY_BY_MAIL_ID:
                paymentAccountGridRow = PayByMailForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.CASH_AT_ATM_ID:
                paymentAccountGridRow = CashAtAtmForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.MONEY_GRAM_ID:
                paymentAccountGridRow = MoneyGramForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.WESTERN_UNION_ID:
                paymentAccountGridRow = WesternUnionForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.HAL_CASH_ID:
                paymentAccountGridRow = HalCashForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.F2F_ID:
                checkNotNull(model.dataModel.getTrade(), "model.dataModel.getTrade() must not be null");
                checkNotNull(model.dataModel.getTrade().getOffer(), "model.dataModel.getTrade().getOffer() must not be null");
                paymentAccountGridRow = F2FForm.addStep2Form(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload, model.dataModel.getTrade().getOffer(), 0, true);
                break;
            case PaymentMethod.BLOCK_CHAINS_ID:
            case PaymentMethod.BLOCK_CHAINS_INSTANT_ID:
                String labelTitle = Res.get("portfolio.pending.step2_buyer.sellersAddress", getCurrencyName(trade));
                paymentAccountGridRow = AssetsForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload, labelTitle);
                break;
            case PaymentMethod.PROMPT_PAY_ID:
                paymentAccountGridRow = PromptPayForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.ADVANCED_CASH_ID:
                paymentAccountGridRow = AdvancedCashForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.TRANSFERWISE_ID:
                paymentAccountGridRow = TransferwiseForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.TRANSFERWISE_USD_ID:
                paymentAccountGridRow = TransferwiseUsdForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.PAYSERA_ID:
                paymentAccountGridRow = PayseraForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.PAXUM_ID:
                paymentAccountGridRow = PaxumForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.NEFT_ID:
                paymentAccountGridRow = NeftForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.RTGS_ID:
                paymentAccountGridRow = RtgsForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.IMPS_ID:
                paymentAccountGridRow = ImpsForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.UPI_ID:
                paymentAccountGridRow = UpiForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.PAYTM_ID:
                paymentAccountGridRow = PaytmForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.NEQUI_ID:
                paymentAccountGridRow = NequiForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.BIZUM_ID:
                paymentAccountGridRow = BizumForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.PIX_ID:
                paymentAccountGridRow = PixForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.AMAZON_GIFT_CARD_ID:
                paymentAccountGridRow = AmazonGiftCardForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.CAPITUAL_ID:
                paymentAccountGridRow = CapitualForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.CELPAY_ID:
                paymentAccountGridRow = CelPayForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.MONESE_ID:
                paymentAccountGridRow = MoneseForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.SATISPAY_ID:
                paymentAccountGridRow = SatispayForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.TIKKIE_ID:
                paymentAccountGridRow = TikkieForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.VERSE_ID:
                paymentAccountGridRow = VerseForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.STRIKE_ID:
                paymentAccountGridRow = StrikeForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.SWIFT_ID:
                paymentAccountGridRow = SwiftForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload, trade);
                break;
            case PaymentMethod.ACH_TRANSFER_ID:
                paymentAccountGridRow = AchTransferForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.DOMESTIC_WIRE_TRANSFER_ID:
                paymentAccountGridRow = DomesticWireTransferForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.CASH_APP_ID:
                paymentAccountGridRow = CashAppForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.PAYPAL_ID:
                paymentAccountGridRow = PayPalForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.VENMO_ID:
                paymentAccountGridRow = VenmoForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
                break;
            case PaymentMethod.PAYSAFE_ID:
                paymentAccountGridRow = PaysafeForm.addFormForBuyer(paymentAccountGridPane, paymentAccountGridRow, paymentAccountPayload);
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
                            addCompactTopLabelTextFieldWithCopyIcon(paymentAccountGridPane, ++paymentAccountGridRow, 0,
                                    Res.get("portfolio.pending.step2_buyer.buyerAccount"), accountName);
                        });
            }
        }

        GridPane.setRowSpan(accountTitledGroupBg, gridRow + paymentAccountGridRow - 1);

        Tuple4<Button, BusyAnimation, Label, HBox> tuple3 = addButtonBusyAnimationLabel(paymentAccountGridPane, ++paymentAccountGridRow, 0,
                Res.get("portfolio.pending.step2_buyer.paymentSent"), 10);

        HBox confirmButtonHBox = tuple3.fourth;
        GridPane.setColumnSpan(confirmButtonHBox, 2);
        confirmButton = tuple3.first;
        confirmButton.setDisable(!confirmPaymentSentPermitted());
        confirmButton.setOnAction(e -> onPaymentSent());
        busyAnimation = tuple3.second;
        statusLabel = tuple3.third;
    }

    private void createRecommendationGridPane() {

        // create grid pane to show recommendation for more blocks
        moreConfirmationsGridPane = new GridPane();
        moreConfirmationsGridPane.setStyle("-fx-background-color: -bs-content-background-gray;");
        moreConfirmationsGridPane.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        // add title
        addTitledGroupBg(moreConfirmationsGridPane, 0, 1,  Res.get("portfolio.pending.step1.waitForConf"), Layout.COMPACT_GROUP_DISTANCE);

        // add text
        Label label = new Label(Res.get("portfolio.pending.step2_buyer.additionalConf", Trade.NUM_BLOCKS_DEPOSITS_FINALIZED));
        label.setFont(new Font(16));
        GridPane.setMargin(label, new Insets(20, 0, 0, 0));
        moreConfirmationsGridPane.add(label, 0, 1, 2, 1);

        // add button to show payment details
        Button showPaymentDetailsButton = new Button("Show payment details early");
        showPaymentDetailsButton.getStyleClass().add("action-button");
        GridPane.setMargin(showPaymentDetailsButton, new Insets(20, 0, 0, 0));
        showPaymentDetailsButton.setOnAction(e -> {
            model.setShowPaymentDetailsEarly(true);
            gridPane.getChildren().remove(moreConfirmationsGridPane);
            gridPane.getChildren().add(paymentAccountGridPane);
            GridPane.setRowIndex(paymentAccountGridPane, gridRow + 1);
            GridPane.setColumnSpan(paymentAccountGridPane, 2);
        });
        moreConfirmationsGridPane.add(showPaymentDetailsButton, 0, 2);
    }

    private GridPane createGridPane() {
        GridPane gridPane = new GridPane();
        gridPane.setHgap(Layout.GRID_GAP);
        gridPane.setVgap(Layout.GRID_GAP);
        ColumnConstraints columnConstraints1 = new ColumnConstraints();
        columnConstraints1.setHgrow(Priority.ALWAYS);
        ColumnConstraints columnConstraints2 = new ColumnConstraints();
        columnConstraints2.setHgrow(Priority.ALWAYS);
        gridPane.getColumnConstraints().addAll(columnConstraints1, columnConstraints2);
        return gridPane;
    }

    private void attachRecommendationGrid() {
        if (gridPane.getChildren().contains(moreConfirmationsGridPane)) return;
        if (gridPane.getChildren().contains(paymentAccountGridPane)) gridPane.getChildren().remove(paymentAccountGridPane);
        gridPane.getChildren().add(moreConfirmationsGridPane);
        GridPane.setRowIndex(moreConfirmationsGridPane, gridRow + 1);
        GridPane.setColumnSpan(moreConfirmationsGridPane, 2);
    }

    private void attachPaymentDetailsGrid() {
        if (gridPane.getChildren().contains(paymentAccountGridPane)) return;
        if (gridPane.getChildren().contains(moreConfirmationsGridPane)) gridPane.getChildren().remove(moreConfirmationsGridPane);
        gridPane.getChildren().add(paymentAccountGridPane);
        GridPane.setRowIndex(paymentAccountGridPane, gridRow + 1);
        GridPane.setColumnSpan(paymentAccountGridPane, 2);
    }

    private boolean confirmPaymentSentPermitted() {
        if (!trade.confirmPermitted()) return false;
        if (trade.getState() == Trade.State.BUYER_SEND_FAILED_PAYMENT_SENT_MSG) return true;
        return trade.isDepositsUnlocked() && trade.getState().ordinal() < Trade.State.BUYER_CONFIRMED_PAYMENT_SENT.ordinal();
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
        confirmButton.setDisable(!confirmPaymentSentPermitted());
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
        } else {
            showConfirmPaymentSentPopup();
        }
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
        statusLabel.setText(Res.get("shared.preparingConfirmation"));
        confirmButton.setDisable(true);

        model.dataModel.onPaymentSent(() -> {
        }, errorMessage -> {
            busyAnimation.stop();
            new Popup().warning(Res.get("popup.warning.sendMsgFailed") + "\n\n" + errorMessage).show();
            confirmButton.setDisable(!confirmPaymentSentPermitted());
            UserThread.execute(() -> statusLabel.setText("Error confirming payment sent."));
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
                message += Res.get("portfolio.pending.step2_buyer.crypto",
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
            } else if (paymentAccountPayload instanceof PayByMailAccountPayload ||
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
