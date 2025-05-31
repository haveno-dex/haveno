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

package haveno.desktop.main.overlays.windows;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import haveno.common.UserThread;
import haveno.common.app.DevEnv;
import haveno.common.handlers.ResultHandler;
import haveno.common.util.Tuple2;
import haveno.common.util.Tuple3;
import haveno.core.api.CoreDisputesService;
import haveno.core.api.CoreDisputesService.PayoutSuggestion;
import haveno.core.locale.Res;
import haveno.core.support.SupportType;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.DisputeList;
import haveno.core.support.dispute.DisputeManager;
import haveno.core.support.dispute.DisputeResult;
import haveno.core.support.dispute.arbitration.ArbitrationManager;
import haveno.core.support.dispute.mediation.MediationManager;
import haveno.core.support.dispute.refund.RefundManager;
import haveno.core.trade.Contract;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.util.FormattingUtils;
import haveno.core.util.VolumeUtil;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.xmr.wallet.TradeWalletService;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.components.AutoTooltipRadioButton;
import haveno.desktop.components.HavenoTextArea;
import haveno.desktop.components.InputTextField;
import haveno.desktop.main.overlays.Overlay;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.util.DisplayUtils;
import haveno.desktop.util.Layout;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.model.MoneroDestination;
import monero.wallet.model.MoneroTxWallet;

import java.math.BigInteger;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;
import static haveno.desktop.util.FormBuilder.add2ButtonsWithBox;
import static haveno.desktop.util.FormBuilder.addConfirmationLabelLabel;
import static haveno.desktop.util.FormBuilder.addTitledGroupBg;
import static haveno.desktop.util.FormBuilder.addTopLabelWithVBox;

@Slf4j
public class DisputeSummaryWindow extends Overlay<DisputeSummaryWindow> {
    private final CoinFormatter formatter;
    private final TradeManager tradeManager;
    private final ArbitrationManager arbitrationManager;
    private final MediationManager mediationManager;
    private final CoreDisputesService disputesService;

    private Dispute dispute;
    private Trade trade;
    private ToggleGroup tradeAmountToggleGroup, reasonToggleGroup;
    private DisputeResult disputeResult;
    private RadioButton buyerGetsTradeAmountRadioButton, sellerGetsTradeAmountRadioButton,
            buyerGetsAllRadioButton, sellerGetsAllRadioButton, customRadioButton;
    private RadioButton reasonWasBugRadioButton, reasonWasUsabilityIssueRadioButton,
            reasonProtocolViolationRadioButton, reasonNoReplyRadioButton, reasonWasScamRadioButton,
            reasonWasOtherRadioButton, reasonWasBankRadioButton, reasonWasOptionTradeRadioButton,
            reasonWasSellerNotRespondingRadioButton, reasonWasWrongSenderAccountRadioButton,
            reasonWasPeerWasLateRadioButton, reasonWasTradeAlreadySettledRadioButton;
    private CoreDisputesService.PayoutSuggestion payoutSuggestion;

    // Dispute object of other trade peer. The dispute field is the one from which we opened the close dispute window.
    private Optional<Dispute> peersDisputeOptional;
    private String role;
    private TextArea summaryNotesTextArea;

    private ChangeListener<Boolean> customRadioButtonSelectedListener;
    private ChangeListener<Toggle> reasonToggleSelectionListener;
    private InputTextField buyerPayoutAmountInputTextField, sellerPayoutAmountInputTextField;
    private ChangeListener<Boolean> buyerPayoutAmountListener, sellerPayoutAmountListener;
    private ChangeListener<Toggle> tradeAmountToggleGroupListener;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Public API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public DisputeSummaryWindow(@Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter formatter,
                                TradeManager tradeManager,
                                ArbitrationManager arbitrationManager,
                                MediationManager mediationManager,
                                XmrWalletService walletService,
                                TradeWalletService tradeWalletService,
                                CoreDisputesService disputesService) {

        this.formatter = formatter;
        this.tradeManager = tradeManager;
        this.arbitrationManager = arbitrationManager;
        this.mediationManager = mediationManager;
        this.disputesService = disputesService;

        type = Type.Confirmation;
    }

    public void show(Dispute dispute) {
        this.dispute = dispute;
        this.trade = tradeManager.getTrade(dispute.getTradeId());
        this.payoutSuggestion = null;

        rowIndex = -1;
        width = 1150;
        createGridPane();
        addContent();
        display();

        if (DevEnv.isDevMode()) {
            UserThread.execute(() -> {
                summaryNotesTextArea.setText("dummy result....");
            });
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Protected
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void cleanup() {
        if (reasonToggleGroup != null)
            reasonToggleGroup.selectedToggleProperty().removeListener(reasonToggleSelectionListener);

        if (customRadioButton != null)
            customRadioButton.selectedProperty().removeListener(customRadioButtonSelectedListener);

        if (tradeAmountToggleGroup != null)
            tradeAmountToggleGroup.selectedToggleProperty().removeListener(tradeAmountToggleGroupListener);

        removePayoutAmountListeners();
    }

    @Override
    protected void setupKeyHandler(Scene scene) {
        if (!hideCloseButton) {
            scene.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ESCAPE) {
                    e.consume();
                    doClose();
                }
            });
        }
    }

    @Override
    protected void createGridPane() {
        super.createGridPane();
        gridPane.setPadding(new Insets(35, 40, 30, 40));
        gridPane.getStyleClass().addAll("grid-pane", "popup-with-input");
        gridPane.getColumnConstraints().get(0).setHalignment(HPos.LEFT);
        gridPane.setPrefWidth(width);
    }

    private void addContent() {
        Contract contract = dispute.getContract();
        if (dispute.getDisputeResultProperty().get() == null)
            disputeResult = new DisputeResult(dispute.getTradeId(), dispute.getTraderId());
        else
            disputeResult = dispute.getDisputeResultProperty().get();

        peersDisputeOptional = checkNotNull(getDisputeManager(dispute)).getDisputesAsObservableList().stream()
                .filter(d -> dispute.getTradeId().equals(d.getTradeId()) && dispute.getTraderId() != d.getTraderId())
                .findFirst();

        addInfoPane();

        addTradeAmountPayoutControls();
        addPayoutAmountTextFields();
        addReasonControls();

        boolean applyPeersDisputeResult = peersDisputeOptional.isPresent() && peersDisputeOptional.get().isClosed();
        if (applyPeersDisputeResult) {
            // If the other peers dispute has been closed we apply the result to ourselves
            DisputeResult peersDisputeResult = peersDisputeOptional.get().getDisputeResultProperty().get();
            disputeResult.setBuyerPayoutAmountBeforeCost(peersDisputeResult.getBuyerPayoutAmountBeforeCost());
            disputeResult.setSellerPayoutAmountBeforeCost(peersDisputeResult.getSellerPayoutAmountBeforeCost());
            disputeResult.setWinner(peersDisputeResult.getWinner());
            disputeResult.setReason(peersDisputeResult.getReason());
            disputeResult.setSummaryNotes(peersDisputeResult.summaryNotesProperty().get());
            disputeResult.setSubtractFeeFrom(peersDisputeResult.getSubtractFeeFrom());

            buyerGetsTradeAmountRadioButton.setDisable(true);
            buyerGetsAllRadioButton.setDisable(true);
            sellerGetsTradeAmountRadioButton.setDisable(true);
            sellerGetsAllRadioButton.setDisable(true);
            customRadioButton.setDisable(true);

            buyerPayoutAmountInputTextField.setDisable(true);
            sellerPayoutAmountInputTextField.setDisable(true);
            buyerPayoutAmountInputTextField.setEditable(false);
            sellerPayoutAmountInputTextField.setEditable(false);

            reasonWasBugRadioButton.setDisable(true);
            reasonWasUsabilityIssueRadioButton.setDisable(true);
            reasonProtocolViolationRadioButton.setDisable(true);
            reasonNoReplyRadioButton.setDisable(true);
            reasonWasScamRadioButton.setDisable(true);
            reasonWasOtherRadioButton.setDisable(true);
            reasonWasBankRadioButton.setDisable(true);
            reasonWasOptionTradeRadioButton.setDisable(true);
            reasonWasSellerNotRespondingRadioButton.setDisable(true);
            reasonWasWrongSenderAccountRadioButton.setDisable(true);
            reasonWasPeerWasLateRadioButton.setDisable(true);
            reasonWasTradeAlreadySettledRadioButton.setDisable(true);

            applyTradeAmountRadioButtonStates();
        }

        setReasonRadioButtonState();

        addSummaryNotes();
        addButtons(contract);
    }

    private void addInfoPane() {
        Contract contract = dispute.getContract();
        addTitledGroupBg(gridPane, ++rowIndex, 17, Res.get("disputeSummaryWindow.title")).getStyleClass().add("last");
        addConfirmationLabelLabel(gridPane, rowIndex, Res.get("shared.tradeId"), dispute.getShortTradeId(),
                Layout.TWICE_FIRST_ROW_DISTANCE);
        addConfirmationLabelLabel(gridPane, ++rowIndex, Res.get("disputeSummaryWindow.openDate"), DisplayUtils.formatDateTime(dispute.getOpeningDate()));
        if (dispute.isDisputeOpenerIsMaker()) {
            if (dispute.isDisputeOpenerIsBuyer())
                role = Res.get("support.buyerMaker");
            else
                role = Res.get("support.sellerMaker");
        } else {
            if (dispute.isDisputeOpenerIsBuyer())
                role = Res.get("support.buyerTaker");
            else
                role = Res.get("support.sellerTaker");
        }
        addConfirmationLabelLabel(gridPane, ++rowIndex, Res.get("disputeSummaryWindow.role"), role);
        addConfirmationLabelLabel(gridPane, ++rowIndex, Res.get("shared.tradeAmount"),
                HavenoUtils.formatXmr(contract.getTradeAmount(), true));
        addConfirmationLabelLabel(gridPane, ++rowIndex, Res.get("shared.tradePrice"),
            FormattingUtils.formatPrice(contract.getPrice()));
        addConfirmationLabelLabel(gridPane, ++rowIndex, Res.get("shared.tradeVolume"),
            VolumeUtil.formatVolumeWithCode(contract.getTradeVolume()));
        String tradeFee = Res.getWithColAndCap("shared.buyer") +
                " " +
                HavenoUtils.formatXmr(trade.getBuyer() == trade.getMaker() ? trade.getMakerFee() : trade.getTakerFee(), true) +
                " / " +
                Res.getWithColAndCap("shared.seller") +
                " " +
                HavenoUtils.formatXmr(trade.getSeller() == trade.getMaker() ? trade.getMakerFee() : trade.getTakerFee(), true);
        addConfirmationLabelLabel(gridPane, ++rowIndex, Res.get("shared.tradeFee"), tradeFee);
        String securityDeposit = Res.getWithColAndCap("shared.buyer") +
                " " +
                HavenoUtils.formatXmr(trade.getBuyer().getSecurityDeposit(), true) +
                " / " +
                Res.getWithColAndCap("shared.seller") +
                " " +
                HavenoUtils.formatXmr(trade.getSeller().getSecurityDeposit(), true);
        addConfirmationLabelLabel(gridPane, ++rowIndex, Res.get("shared.securityDeposit"), securityDeposit);
    }

    private void addTradeAmountPayoutControls() {
        buyerGetsTradeAmountRadioButton = new AutoTooltipRadioButton(Res.get("disputeSummaryWindow.payout.getsTradeAmount",
                Res.get("shared.buyer")));
        buyerGetsAllRadioButton = new AutoTooltipRadioButton(Res.get("disputeSummaryWindow.payout.getsAll",
                Res.get("shared.buyer")));
        sellerGetsTradeAmountRadioButton = new AutoTooltipRadioButton(Res.get("disputeSummaryWindow.payout.getsTradeAmount",
                Res.get("shared.seller")));
        sellerGetsAllRadioButton = new AutoTooltipRadioButton(Res.get("disputeSummaryWindow.payout.getsAll",
                Res.get("shared.seller")));
        customRadioButton = new AutoTooltipRadioButton(Res.get("disputeSummaryWindow.payout.custom"));

        VBox radioButtonPane = new VBox();
        radioButtonPane.setSpacing(10);
        radioButtonPane.getChildren().addAll(buyerGetsTradeAmountRadioButton, buyerGetsAllRadioButton,
                sellerGetsTradeAmountRadioButton, sellerGetsAllRadioButton,
                customRadioButton);

        addTopLabelWithVBox(gridPane, ++rowIndex, Res.get("disputeSummaryWindow.payout"), radioButtonPane, 0);

        tradeAmountToggleGroup = new ToggleGroup();
        buyerGetsTradeAmountRadioButton.setToggleGroup(tradeAmountToggleGroup);
        buyerGetsAllRadioButton.setToggleGroup(tradeAmountToggleGroup);
        sellerGetsTradeAmountRadioButton.setToggleGroup(tradeAmountToggleGroup);
        sellerGetsAllRadioButton.setToggleGroup(tradeAmountToggleGroup);
        customRadioButton.setToggleGroup(tradeAmountToggleGroup);

        tradeAmountToggleGroupListener = (observable, oldValue, newValue) -> applyPayoutAmounts(newValue);
        tradeAmountToggleGroup.selectedToggleProperty().addListener(tradeAmountToggleGroupListener);

        buyerPayoutAmountListener = (observable, oldValue, newValue) -> applyCustomAmounts(buyerPayoutAmountInputTextField, oldValue, newValue);
        sellerPayoutAmountListener = (observable, oldValue, newValue) -> applyCustomAmounts(sellerPayoutAmountInputTextField, oldValue, newValue);

        customRadioButtonSelectedListener = (observable, oldValue, newValue) -> {
            buyerPayoutAmountInputTextField.setEditable(newValue);
            sellerPayoutAmountInputTextField.setEditable(newValue);

            if (newValue) {
                buyerPayoutAmountInputTextField.focusedProperty().addListener(buyerPayoutAmountListener);
                sellerPayoutAmountInputTextField.focusedProperty().addListener(sellerPayoutAmountListener);
            } else {
                removePayoutAmountListeners();
            }
        };
        customRadioButton.selectedProperty().addListener(customRadioButtonSelectedListener);
    }

    private void removePayoutAmountListeners() {
        if (buyerPayoutAmountInputTextField != null && buyerPayoutAmountListener != null)
            buyerPayoutAmountInputTextField.focusedProperty().removeListener(buyerPayoutAmountListener);

        if (sellerPayoutAmountInputTextField != null && sellerPayoutAmountListener != null)
            sellerPayoutAmountInputTextField.focusedProperty().removeListener(sellerPayoutAmountListener);
    }

    private boolean isPayoutAmountValid() {
        BigInteger buyerAmount = HavenoUtils.parseXmr(buyerPayoutAmountInputTextField.getText());
        BigInteger sellerAmount = HavenoUtils.parseXmr(sellerPayoutAmountInputTextField.getText());
        Contract contract = dispute.getContract();
        BigInteger tradeAmount = contract.getTradeAmount();
        BigInteger available = tradeAmount
                .add(trade.getBuyer().getSecurityDeposit())
                .add(trade.getSeller().getSecurityDeposit());
        BigInteger totalAmount = buyerAmount.add(sellerAmount);

        boolean isRefundAgent = getDisputeManager(dispute) instanceof RefundManager;
        if (isRefundAgent) {
            // We allow to spend less in case of RefundAgent or even zero to both, so in that case no payout tx will
            // be made
            return totalAmount.compareTo(available) <= 0;
        } else {
            if (totalAmount.compareTo(BigInteger.ZERO) <= 0) {
                return false;
            }
            return totalAmount.compareTo(available) == 0;
        }
    }

    private void applyCustomAmounts(InputTextField inputTextField, boolean oldFocusValue, boolean newFocusValue) {
        // We only apply adjustments at focus out, otherwise we cannot enter certain values if we update at each
        // keystroke.
        if (!oldFocusValue || newFocusValue) {
            return;
        }

        Contract contract = dispute.getContract();
        BigInteger available = contract.getTradeAmount()
                .add(trade.getBuyer().getSecurityDeposit())
                .add(trade.getSeller().getSecurityDeposit());
        BigInteger enteredAmount = HavenoUtils.parseXmr(inputTextField.getText());
        if (enteredAmount.compareTo(available) > 0) {
            enteredAmount = available;
            BigInteger finalEnteredAmount = enteredAmount;
            inputTextField.setText(HavenoUtils.formatXmr(finalEnteredAmount));
        }
        BigInteger counterPart = available.subtract(enteredAmount);
        String formattedCounterPartAmount = HavenoUtils.formatXmr(counterPart);
        BigInteger buyerAmount;
        BigInteger sellerAmount;
        if (inputTextField == buyerPayoutAmountInputTextField) {
            buyerAmount = enteredAmount;
            sellerAmount = counterPart;
            sellerPayoutAmountInputTextField.setText(formattedCounterPartAmount);
        } else {
            sellerAmount = enteredAmount;
            buyerAmount = counterPart;
            buyerPayoutAmountInputTextField.setText(formattedCounterPartAmount);
        }

        disputeResult.setBuyerPayoutAmountBeforeCost(buyerAmount);
        disputeResult.setSellerPayoutAmountBeforeCost(sellerAmount);
        disputeResult.setWinner(buyerAmount.compareTo(sellerAmount) > 0 ? DisputeResult.Winner.BUYER : DisputeResult.Winner.SELLER); // TODO: UI should allow selection of receiver of exact custom amount, otherwise defaulting to bigger receiver. could extend API to specify who pays payout tx fee: buyer, seller, or both
        disputeResult.setSubtractFeeFrom(buyerAmount.compareTo(sellerAmount) > 0 ? DisputeResult.SubtractFeeFrom.SELLER_ONLY : DisputeResult.SubtractFeeFrom.BUYER_ONLY);
    }

    private void addPayoutAmountTextFields() {
        buyerPayoutAmountInputTextField = new InputTextField();
        buyerPayoutAmountInputTextField.setLabelFloat(true);
        buyerPayoutAmountInputTextField.getStyleClass().add("label-float");
        buyerPayoutAmountInputTextField.setEditable(false);
        buyerPayoutAmountInputTextField.setPromptText(Res.get("disputeSummaryWindow.payoutAmount.buyer"));

        sellerPayoutAmountInputTextField = new InputTextField();
        sellerPayoutAmountInputTextField.setLabelFloat(true);
        sellerPayoutAmountInputTextField.getStyleClass().add("label-float");
        sellerPayoutAmountInputTextField.setPromptText(Res.get("disputeSummaryWindow.payoutAmount.seller"));
        sellerPayoutAmountInputTextField.setEditable(false);

        VBox vBox = new VBox();
        vBox.setSpacing(15);
        vBox.getChildren().addAll(buyerPayoutAmountInputTextField, sellerPayoutAmountInputTextField);
        GridPane.setMargin(vBox, new Insets(Layout.FIRST_ROW_AND_GROUP_DISTANCE, 0, 0, 0));
        GridPane.setRowIndex(vBox, rowIndex);
        GridPane.setColumnIndex(vBox, 1);
        gridPane.getChildren().add(vBox);
    }

    private void addReasonControls() {
        reasonWasBugRadioButton = new AutoTooltipRadioButton(Res.get("disputeSummaryWindow.reason." + DisputeResult.Reason.BUG.name()));
        reasonWasUsabilityIssueRadioButton = new AutoTooltipRadioButton(Res.get("disputeSummaryWindow.reason." + DisputeResult.Reason.USABILITY.name()));
        reasonProtocolViolationRadioButton = new AutoTooltipRadioButton(Res.get("disputeSummaryWindow.reason." + DisputeResult.Reason.PROTOCOL_VIOLATION.name()));
        reasonNoReplyRadioButton = new AutoTooltipRadioButton(Res.get("disputeSummaryWindow.reason." + DisputeResult.Reason.NO_REPLY.name()));
        reasonWasScamRadioButton = new AutoTooltipRadioButton(Res.get("disputeSummaryWindow.reason." + DisputeResult.Reason.SCAM.name()));
        reasonWasBankRadioButton = new AutoTooltipRadioButton(Res.get("disputeSummaryWindow.reason." + DisputeResult.Reason.BANK_PROBLEMS.name()));
        reasonWasOtherRadioButton = new AutoTooltipRadioButton(Res.get("disputeSummaryWindow.reason." + DisputeResult.Reason.OTHER.name()));
        reasonWasOptionTradeRadioButton = new AutoTooltipRadioButton(Res.get("disputeSummaryWindow.reason." + DisputeResult.Reason.OPTION_TRADE.name()));
        reasonWasSellerNotRespondingRadioButton = new AutoTooltipRadioButton(Res.get("disputeSummaryWindow.reason." + DisputeResult.Reason.SELLER_NOT_RESPONDING.name()));
        reasonWasWrongSenderAccountRadioButton = new AutoTooltipRadioButton(Res.get("disputeSummaryWindow.reason." + DisputeResult.Reason.WRONG_SENDER_ACCOUNT.name()));
        reasonWasPeerWasLateRadioButton = new AutoTooltipRadioButton(Res.get("disputeSummaryWindow.reason." + DisputeResult.Reason.PEER_WAS_LATE.name()));
        reasonWasTradeAlreadySettledRadioButton = new AutoTooltipRadioButton(Res.get("disputeSummaryWindow.reason." + DisputeResult.Reason.TRADE_ALREADY_SETTLED.name()));

        HBox feeRadioButtonPane = new HBox();
        feeRadioButtonPane.setSpacing(20);
        // We don't show no reply and protocol violation as those should be covered by more specific ones. We still leave
        // the code to enable it if it turns out it is still requested by mediators.
        feeRadioButtonPane.getChildren().addAll(
                reasonWasTradeAlreadySettledRadioButton,
                reasonWasPeerWasLateRadioButton,
                reasonWasOptionTradeRadioButton,
                reasonWasSellerNotRespondingRadioButton,
                reasonWasWrongSenderAccountRadioButton,
                reasonWasBugRadioButton,
                reasonWasUsabilityIssueRadioButton,
                reasonWasBankRadioButton,
                reasonWasOtherRadioButton
        );

        VBox vBox = addTopLabelWithVBox(gridPane, ++rowIndex,
                Res.get("disputeSummaryWindow.reason"),
                feeRadioButtonPane, 10).second;
        GridPane.setColumnSpan(vBox, 2);

        reasonToggleGroup = new ToggleGroup();
        reasonWasBugRadioButton.setToggleGroup(reasonToggleGroup);
        reasonWasUsabilityIssueRadioButton.setToggleGroup(reasonToggleGroup);
        reasonProtocolViolationRadioButton.setToggleGroup(reasonToggleGroup);
        reasonNoReplyRadioButton.setToggleGroup(reasonToggleGroup);
        reasonWasScamRadioButton.setToggleGroup(reasonToggleGroup);
        reasonWasOtherRadioButton.setToggleGroup(reasonToggleGroup);
        reasonWasBankRadioButton.setToggleGroup(reasonToggleGroup);
        reasonWasOptionTradeRadioButton.setToggleGroup(reasonToggleGroup);
        reasonWasSellerNotRespondingRadioButton.setToggleGroup(reasonToggleGroup);
        reasonWasWrongSenderAccountRadioButton.setToggleGroup(reasonToggleGroup);
        reasonWasPeerWasLateRadioButton.setToggleGroup(reasonToggleGroup);
        reasonWasTradeAlreadySettledRadioButton.setToggleGroup(reasonToggleGroup);

        reasonToggleSelectionListener = (observable, oldValue, newValue) -> {
            if (newValue == reasonWasBugRadioButton) {
                disputeResult.setReason(DisputeResult.Reason.BUG);
            } else if (newValue == reasonWasUsabilityIssueRadioButton) {
                disputeResult.setReason(DisputeResult.Reason.USABILITY);
            } else if (newValue == reasonProtocolViolationRadioButton) {
                disputeResult.setReason(DisputeResult.Reason.PROTOCOL_VIOLATION);
            } else if (newValue == reasonNoReplyRadioButton) {
                disputeResult.setReason(DisputeResult.Reason.NO_REPLY);
            } else if (newValue == reasonWasScamRadioButton) {
                disputeResult.setReason(DisputeResult.Reason.SCAM);
            } else if (newValue == reasonWasBankRadioButton) {
                disputeResult.setReason(DisputeResult.Reason.BANK_PROBLEMS);
            } else if (newValue == reasonWasOtherRadioButton) {
                disputeResult.setReason(DisputeResult.Reason.OTHER);
            } else if (newValue == reasonWasOptionTradeRadioButton) {
                disputeResult.setReason(DisputeResult.Reason.OPTION_TRADE);
            } else if (newValue == reasonWasSellerNotRespondingRadioButton) {
                disputeResult.setReason(DisputeResult.Reason.SELLER_NOT_RESPONDING);
            } else if (newValue == reasonWasWrongSenderAccountRadioButton) {
                disputeResult.setReason(DisputeResult.Reason.WRONG_SENDER_ACCOUNT);
            } else if (newValue == reasonWasTradeAlreadySettledRadioButton) {
                disputeResult.setReason(DisputeResult.Reason.TRADE_ALREADY_SETTLED);
            } else if (newValue == reasonWasPeerWasLateRadioButton) {
                disputeResult.setReason(DisputeResult.Reason.PEER_WAS_LATE);
            }
        };
        reasonToggleGroup.selectedToggleProperty().addListener(reasonToggleSelectionListener);
    }

    private void setReasonRadioButtonState() {
        if (disputeResult.getReason() != null) {
            switch (disputeResult.getReason()) {
                case BUG:
                    reasonToggleGroup.selectToggle(reasonWasBugRadioButton);
                    break;
                case USABILITY:
                    reasonToggleGroup.selectToggle(reasonWasUsabilityIssueRadioButton);
                    break;
                case PROTOCOL_VIOLATION:
                    reasonToggleGroup.selectToggle(reasonProtocolViolationRadioButton);
                    break;
                case NO_REPLY:
                    reasonToggleGroup.selectToggle(reasonNoReplyRadioButton);
                    break;
                case SCAM:
                    reasonToggleGroup.selectToggle(reasonWasScamRadioButton);
                    break;
                case BANK_PROBLEMS:
                    reasonToggleGroup.selectToggle(reasonWasBankRadioButton);
                    break;
                case OTHER:
                    reasonToggleGroup.selectToggle(reasonWasOtherRadioButton);
                    break;
                case OPTION_TRADE:
                    reasonToggleGroup.selectToggle(reasonWasOptionTradeRadioButton);
                    break;
                case SELLER_NOT_RESPONDING:
                    reasonToggleGroup.selectToggle(reasonWasSellerNotRespondingRadioButton);
                    break;
                case WRONG_SENDER_ACCOUNT:
                    reasonToggleGroup.selectToggle(reasonWasWrongSenderAccountRadioButton);
                    break;
                case PEER_WAS_LATE:
                    reasonToggleGroup.selectToggle(reasonWasPeerWasLateRadioButton);
                    break;
                case TRADE_ALREADY_SETTLED:
                    reasonToggleGroup.selectToggle(reasonWasTradeAlreadySettledRadioButton);
                    break;
            }
        }
    }

    private void addSummaryNotes() {
        summaryNotesTextArea = new HavenoTextArea();
        summaryNotesTextArea.setPromptText(Res.get("disputeSummaryWindow.addSummaryNotes"));
        summaryNotesTextArea.setWrapText(true);

        Tuple2<Label, VBox> topLabelWithVBox = addTopLabelWithVBox(gridPane, ++rowIndex,
                Res.get("disputeSummaryWindow.summaryNotes"), summaryNotesTextArea, 0);
        GridPane.setColumnSpan(topLabelWithVBox.second, 2);

        summaryNotesTextArea.setPrefHeight(50);
        summaryNotesTextArea.textProperty().bindBidirectional(disputeResult.summaryNotesProperty());
    }

    private void addButtons(Contract contract) {
        Tuple3<Button, Button, HBox> tuple = add2ButtonsWithBox(gridPane, ++rowIndex,
                Res.get("disputeSummaryWindow.close.button"),
                Res.get("shared.cancel"), 15, true);
        Button closeTicketButton = tuple.first;
        closeTicketButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> tradeAmountToggleGroup.getSelectedToggle() == null
                        || summaryNotesTextArea.getText() == null
                        || summaryNotesTextArea.getText().length() == 0
                        || !isPayoutAmountValid(),
            tradeAmountToggleGroup.selectedToggleProperty(),
            summaryNotesTextArea.textProperty(),
            buyerPayoutAmountInputTextField.textProperty(),
            sellerPayoutAmountInputTextField.textProperty()));

        Button cancelButton = tuple.second;

        closeTicketButton.setOnAction(e -> {
            closeTicketButton.disableProperty().unbind();
            closeTicketButton.setDisable(true);
            if (dispute.getSupportType() == SupportType.ARBITRATION &&
                    peersDisputeOptional.isPresent() &&
                    !peersDisputeOptional.get().isClosed() &&
                    !trade.isPayoutPublished()) {

                // create payout tx
                MoneroTxWallet payoutTx = arbitrationManager.createDisputePayoutTx(trade, dispute.getContract(), disputeResult, true);

                // show confirmation
                showPayoutTxConfirmation(contract,
                        payoutTx,
                        () -> doClose(closeTicketButton, cancelButton),
                        () -> {
                            closeTicketButton.setDisable(false);
                            cancelButton.setDisable(false);
                        });
            } else {
                doClose(closeTicketButton, cancelButton);
            }
        });

        cancelButton.setOnAction(e -> {
            dispute.setDisputeResult(disputeResult);
            checkNotNull(getDisputeManager(dispute)).requestPersistence();
            hide();
        });
    }

    private void showPayoutTxConfirmation(Contract contract, MoneroTxWallet payoutTx, ResultHandler resultHandler, ResultHandler cancelHandler) {

        // get buyer and seller destinations (order not preserved)
        String buyerPayoutAddressString = contract.getBuyerPayoutAddressString();
        String sellerPayoutAddressString = contract.getSellerPayoutAddressString();
        List<MoneroDestination> destinations = payoutTx.getOutgoingTransfer().getDestinations();
        boolean buyerFirst = destinations.get(0).getAddress().equals(buyerPayoutAddressString);
        BigInteger buyerPayoutAmount = buyerFirst ? destinations.get(0).getAmount() : destinations.size() == 2 ? destinations.get(1).getAmount() : BigInteger.ZERO;
        BigInteger sellerPayoutAmount = buyerFirst ? (destinations.size() == 2 ? destinations.get(1).getAmount() : BigInteger.ZERO) : destinations.get(0).getAmount();

        String buyerDetails = "";
        if (buyerPayoutAmount.compareTo(BigInteger.ZERO) > 0) {
            buyerDetails = Res.get("disputeSummaryWindow.close.txDetails.buyer",
                    HavenoUtils.formatXmr(buyerPayoutAmount, true),
                    buyerPayoutAddressString);
        }
        String sellerDetails = "";
        if (sellerPayoutAmount.compareTo(BigInteger.ZERO) > 0) {
            sellerDetails = Res.get("disputeSummaryWindow.close.txDetails.seller",
                    HavenoUtils.formatXmr(sellerPayoutAmount, true),
                    sellerPayoutAddressString);
        }
        BigInteger outputAmount = buyerPayoutAmount.add(sellerPayoutAmount).add(payoutTx.getFee());
        if (outputAmount.compareTo(BigInteger.ZERO) > 0) {
            new Popup().width(900)
                    .headLine(Res.get("disputeSummaryWindow.close.txDetails.headline"))
                    .confirmation(Res.get("disputeSummaryWindow.close.txDetails",
                            HavenoUtils.formatXmr(outputAmount, true),
                            buyerDetails,
                            sellerDetails,
                            formatter.formatCoinWithCode(HavenoUtils.atomicUnitsToCoin(payoutTx.getFee()))))
                    .actionButtonText(Res.get("shared.yes"))
                    .onAction(() -> resultHandler.handleResult())
                    .closeButtonText(Res.get("shared.cancel"))
                    .onClose(() -> cancelHandler.handleResult())
                    .show();
        } else {
            // No payout will be made
            new Popup().headLine(Res.get("disputeSummaryWindow.close.noPayout.headline"))
                    .confirmation(Res.get("disputeSummaryWindow.close.noPayout.text"))
                    .actionButtonText(Res.get("shared.yes"))
                    .onAction(resultHandler::handleResult)
                    .closeButtonText(Res.get("shared.cancel"))
                    .onClose(() -> cancelHandler.handleResult())
                    .show();
        }
    }

    private void doClose(Button closeTicketButton, Button cancelButton) {
        cancelButton.setDisable(true);

        DisputeManager<? extends DisputeList<Dispute>> disputeManager = getDisputeManager(dispute);
        if (disputeManager == null) {
            return;
        }

        summaryNotesTextArea.textProperty().unbindBidirectional(disputeResult.summaryNotesProperty());

        disputeResult.setCloseDate(new Date());
        disputesService.closeDisputeTicket(disputeManager, dispute, disputeResult, () -> {
            if (peersDisputeOptional.isPresent() && !peersDisputeOptional.get().isClosed() && !DevEnv.isDevMode()) {
                new Popup().attention(Res.get("disputeSummaryWindow.close.closePeer")).show();
            }
            disputeManager.requestPersistence();
            closeTicketButton.disableProperty().unbind();
            hide();
        }, (errMessage, err) -> {
            log.error("Error closing dispute ticket: " + errMessage + "\n", err);
            new Popup().error(err.toString()).show();
        });
    }

    private DisputeManager<? extends DisputeList<Dispute>> getDisputeManager(Dispute dispute) {
        return dispute.isMediationDispute() ? mediationManager : arbitrationManager;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Controller
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void applyPayoutAmounts(Toggle selectedTradeAmountToggle) {
        if (selectedTradeAmountToggle != customRadioButton && selectedTradeAmountToggle != null) {
            applyPayoutAmountsToDisputeResult(selectedTradeAmountToggle);
            applyTradeAmountRadioButtonStates();
        }
    }

    private void applyPayoutAmountsToDisputeResult(Toggle selectedTradeAmountToggle) {
        if (selectedTradeAmountToggle == buyerGetsTradeAmountRadioButton) {
            payoutSuggestion = CoreDisputesService.PayoutSuggestion.BUYER_GETS_TRADE_AMOUNT;
            disputeResult.setWinner(DisputeResult.Winner.BUYER);
        } else if (selectedTradeAmountToggle == buyerGetsAllRadioButton) {
            payoutSuggestion = CoreDisputesService.PayoutSuggestion.BUYER_GETS_ALL;
            disputeResult.setWinner(DisputeResult.Winner.BUYER);
        } else if (selectedTradeAmountToggle == sellerGetsTradeAmountRadioButton) {
            payoutSuggestion = CoreDisputesService.PayoutSuggestion.SELLER_GETS_TRADE_AMOUNT;
            disputeResult.setWinner(DisputeResult.Winner.SELLER);
        } else if (selectedTradeAmountToggle == sellerGetsAllRadioButton) {
            payoutSuggestion = CoreDisputesService.PayoutSuggestion.SELLER_GETS_ALL;
            disputeResult.setWinner(DisputeResult.Winner.SELLER);
        } else {
            // should not happen
            throw new IllegalStateException("Unknown radio button");
        }
        disputesService.applyPayoutAmountsToDisputeResult(payoutSuggestion, dispute, disputeResult, -1);
        buyerPayoutAmountInputTextField.setText(HavenoUtils.formatXmr(disputeResult.getBuyerPayoutAmountBeforeCost()));
        sellerPayoutAmountInputTextField.setText(HavenoUtils.formatXmr(disputeResult.getSellerPayoutAmountBeforeCost()));
    }

    private void applyTradeAmountRadioButtonStates() {

        if (payoutSuggestion == null) {
            payoutSuggestion = getPayoutSuggestionFromDisputeResult();
        }

        BigInteger buyerPayoutAmount = disputeResult.getBuyerPayoutAmountBeforeCost();
        BigInteger sellerPayoutAmount = disputeResult.getSellerPayoutAmountBeforeCost();

        buyerPayoutAmountInputTextField.setText(HavenoUtils.formatXmr(buyerPayoutAmount));
        sellerPayoutAmountInputTextField.setText(HavenoUtils.formatXmr(sellerPayoutAmount));

        switch (payoutSuggestion) {
            case BUYER_GETS_TRADE_AMOUNT:
                buyerGetsTradeAmountRadioButton.setSelected(true);
                break;
            case BUYER_GETS_ALL:
                buyerGetsAllRadioButton.setSelected(true);
                break;
            case SELLER_GETS_TRADE_AMOUNT:
                sellerGetsTradeAmountRadioButton.setSelected(true);
                break;
            case SELLER_GETS_ALL:
                sellerGetsAllRadioButton.setSelected(true);
                break;
            case CUSTOM:
                customRadioButton.setSelected(true);
                break;
        }
    }

    // TODO: Persist the payout suggestion to DisputeResult like Bisq upstream?
    // That would be a better design, but it's not currently needed.
    private PayoutSuggestion getPayoutSuggestionFromDisputeResult() {
        if (disputeResult.getBuyerPayoutAmountBeforeCost().equals(BigInteger.ZERO)) {
            return PayoutSuggestion.SELLER_GETS_ALL;
        } else if (disputeResult.getSellerPayoutAmountBeforeCost().equals(BigInteger.ZERO)) {
            return PayoutSuggestion.BUYER_GETS_ALL;
        } else if (disputeResult.getBuyerPayoutAmountBeforeCost().equals(trade.getAmount().add(trade.getBuyer().getSecurityDeposit()))) {
            return PayoutSuggestion.BUYER_GETS_TRADE_AMOUNT;
        } else if (disputeResult.getSellerPayoutAmountBeforeCost().equals(trade.getAmount().add(trade.getSeller().getSecurityDeposit()))) {
            return PayoutSuggestion.SELLER_GETS_TRADE_AMOUNT;
        } else {
            return PayoutSuggestion.CUSTOM;
        }
    }
}
