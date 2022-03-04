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

package bisq.desktop.main.overlays.windows;

import bisq.desktop.components.AutoTooltipCheckBox;
import bisq.desktop.components.AutoTooltipRadioButton;
import bisq.desktop.components.HavenoTextArea;
import bisq.desktop.components.InputTextField;
import bisq.desktop.main.overlays.Overlay;
import bisq.desktop.main.overlays.popups.Popup;

import bisq.core.api.CoreDisputesService;
import bisq.desktop.util.DisplayUtils;
import bisq.desktop.util.Layout;

import bisq.core.btc.wallet.TradeWalletService;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.locale.Res;
import bisq.core.offer.Offer;
import bisq.core.support.dispute.Dispute;
import bisq.core.support.dispute.DisputeList;
import bisq.core.support.dispute.DisputeManager;
import bisq.core.support.dispute.DisputeResult;
import bisq.core.support.dispute.arbitration.ArbitrationManager;
import bisq.core.support.dispute.mediation.MediationManager;
import bisq.core.support.dispute.refund.RefundManager;
import bisq.core.trade.Contract;
import bisq.core.util.FormattingUtils;
import bisq.core.util.ParsingUtils;
import bisq.core.util.coin.CoinFormatter;

import bisq.common.UserThread;
import bisq.common.app.DevEnv;
import bisq.common.handlers.ResultHandler;
import bisq.common.util.Tuple2;
import bisq.common.util.Tuple3;

import org.bitcoinj.core.Coin;

import com.google.inject.Inject;
import com.google.inject.name.Named;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import javafx.geometry.HPos;
import javafx.geometry.Insets;

import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;

import java.util.Date;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

import static bisq.desktop.util.FormBuilder.add2ButtonsWithBox;
import static bisq.desktop.util.FormBuilder.addConfirmationLabelLabel;
import static bisq.desktop.util.FormBuilder.addTitledGroupBg;
import static bisq.desktop.util.FormBuilder.addTopLabelWithVBox;
import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public class DisputeSummaryWindow extends Overlay<DisputeSummaryWindow> {
    private final CoinFormatter formatter;
    private final ArbitrationManager arbitrationManager;
    private final MediationManager mediationManager;
    private final XmrWalletService walletService;
    private final TradeWalletService tradeWalletService;  // TODO (woodser): remove for xmr or adapt to get/create multisig wallets for tx creation utils
    private final CoreDisputesService disputesService;
    private Dispute dispute;
    private ToggleGroup tradeAmountToggleGroup, reasonToggleGroup;
    private DisputeResult disputeResult;
    private RadioButton buyerGetsTradeAmountRadioButton, sellerGetsTradeAmountRadioButton,
            buyerGetsAllRadioButton, sellerGetsAllRadioButton, customRadioButton;
    private RadioButton reasonWasBugRadioButton, reasonWasUsabilityIssueRadioButton,
            reasonProtocolViolationRadioButton, reasonNoReplyRadioButton, reasonWasScamRadioButton,
            reasonWasOtherRadioButton, reasonWasBankRadioButton, reasonWasOptionTradeRadioButton,
            reasonWasSellerNotRespondingRadioButton, reasonWasWrongSenderAccountRadioButton,
            reasonWasPeerWasLateRadioButton, reasonWasTradeAlreadySettledRadioButton;

    // Dispute object of other trade peer. The dispute field is the one from which we opened the close dispute window.
    private Optional<Dispute> peersDisputeOptional;
    private String role;
    private TextArea summaryNotesTextArea;

    private ChangeListener<Boolean> customRadioButtonSelectedListener;
    private ChangeListener<Toggle> reasonToggleSelectionListener;
    private InputTextField buyerPayoutAmountInputTextField, sellerPayoutAmountInputTextField;
    private ChangeListener<String> buyerPayoutAmountListener, sellerPayoutAmountListener;
    private CheckBox isLoserPublisherCheckBox;
    private ChangeListener<Toggle> tradeAmountToggleGroupListener;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Public API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public DisputeSummaryWindow(@Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter formatter,
                                ArbitrationManager arbitrationManager,
                                MediationManager mediationManager,
                                XmrWalletService walletService,
                                TradeWalletService tradeWalletService,
                                CoreDisputesService disputesService) {

        this.formatter = formatter;
        this.arbitrationManager = arbitrationManager;
        this.mediationManager = mediationManager;
        this.walletService = walletService;
        this.tradeWalletService = tradeWalletService;
        this.disputesService = disputesService;

        type = Type.Confirmation;
    }

    public void show(Dispute dispute) {
        this.dispute = dispute;

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
        gridPane.getStyleClass().add("grid-pane");
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
            disputeResult.setBuyerPayoutAmount(peersDisputeResult.getBuyerPayoutAmount());
            disputeResult.setSellerPayoutAmount(peersDisputeResult.getSellerPayoutAmount());
            disputeResult.setWinner(peersDisputeResult.getWinner());
            disputeResult.setLoserPublisher(peersDisputeResult.isLoserPublisher());
            disputeResult.setReason(peersDisputeResult.getReason());
            disputeResult.setSummaryNotes(peersDisputeResult.summaryNotesProperty().get());

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

            isLoserPublisherCheckBox.setDisable(true);
            isLoserPublisherCheckBox.setSelected(peersDisputeResult.isLoserPublisher());

            applyPayoutAmounts(tradeAmountToggleGroup.selectedToggleProperty().get());
            applyTradeAmountRadioButtonStates();
        } else {
            isLoserPublisherCheckBox.setSelected(false);
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
                role = Res.get("support.buyerOfferer");
            else
                role = Res.get("support.sellerOfferer");
        } else {
            if (dispute.isDisputeOpenerIsBuyer())
                role = Res.get("support.buyerTaker");
            else
                role = Res.get("support.sellerTaker");
        }
        addConfirmationLabelLabel(gridPane, ++rowIndex, Res.get("disputeSummaryWindow.role"), role);
        addConfirmationLabelLabel(gridPane, ++rowIndex, Res.get("shared.tradeAmount"),
                formatter.formatCoinWithCode(contract.getTradeAmount()));
        addConfirmationLabelLabel(gridPane, ++rowIndex, Res.get("shared.tradePrice"),
            FormattingUtils.formatPrice(contract.getTradePrice()));
        addConfirmationLabelLabel(gridPane, ++rowIndex, Res.get("shared.tradeVolume"),
            DisplayUtils.formatVolumeWithCode(contract.getTradeVolume()));
        String securityDeposit = Res.getWithColAndCap("shared.buyer") +
                " " +
                formatter.formatCoinWithCode(contract.getOfferPayload().getBuyerSecurityDeposit()) +
                " / " +
                Res.getWithColAndCap("shared.seller") +
                " " +
                formatter.formatCoinWithCode(contract.getOfferPayload().getSellerSecurityDeposit());
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

        buyerPayoutAmountListener = (observable1, oldValue1, newValue1) -> applyCustomAmounts(buyerPayoutAmountInputTextField);
        sellerPayoutAmountListener = (observable1, oldValue1, newValue1) -> applyCustomAmounts(sellerPayoutAmountInputTextField);

        customRadioButtonSelectedListener = (observable, oldValue, newValue) -> {
            buyerPayoutAmountInputTextField.setEditable(newValue);
            sellerPayoutAmountInputTextField.setEditable(newValue);

            if (newValue) {
                buyerPayoutAmountInputTextField.textProperty().addListener(buyerPayoutAmountListener);
                sellerPayoutAmountInputTextField.textProperty().addListener(sellerPayoutAmountListener);
            } else {
                removePayoutAmountListeners();
            }
        };
        customRadioButton.selectedProperty().addListener(customRadioButtonSelectedListener);
    }

    private void removePayoutAmountListeners() {
        if (buyerPayoutAmountInputTextField != null && buyerPayoutAmountListener != null)
            buyerPayoutAmountInputTextField.textProperty().removeListener(buyerPayoutAmountListener);

        if (sellerPayoutAmountInputTextField != null && sellerPayoutAmountListener != null)
            sellerPayoutAmountInputTextField.textProperty().removeListener(sellerPayoutAmountListener);

    }

    private boolean isPayoutAmountValid() {
        Coin buyerAmount = ParsingUtils.parseToCoin(buyerPayoutAmountInputTextField.getText(), formatter);
        Coin sellerAmount = ParsingUtils.parseToCoin(sellerPayoutAmountInputTextField.getText(), formatter);
        Contract contract = dispute.getContract();
        Coin tradeAmount = contract.getTradeAmount();
        Offer offer = new Offer(contract.getOfferPayload());
        Coin available = tradeAmount
                .add(offer.getBuyerSecurityDeposit())
                .add(offer.getSellerSecurityDeposit());
        Coin totalAmount = buyerAmount.add(sellerAmount);

        boolean isRefundAgent = getDisputeManager(dispute) instanceof RefundManager;
        if (isRefundAgent) {
            // We allow to spend less in case of RefundAgent or even zero to both, so in that case no payout tx will
            // be made
            return totalAmount.compareTo(available) <= 0;
        } else {
            if (!totalAmount.isPositive()) {
                return false;
            }
            return totalAmount.compareTo(available) == 0;
        }
    }

    private void applyCustomAmounts(InputTextField inputTextField) {
//        // We only apply adjustments at focus out, otherwise we cannot enter certain values if we update at each
//        // keystroke.
//        if (!oldFocusValue || newFocusValue) {
//            return;
//        }

        Contract contract = dispute.getContract();
        Offer offer = new Offer(contract.getOfferPayload());
        Coin available = contract.getTradeAmount()
                .add(offer.getBuyerSecurityDeposit())
                .add(offer.getSellerSecurityDeposit());
        Coin enteredAmount = ParsingUtils.parseToCoin(inputTextField.getText(), formatter);
        if (enteredAmount.compareTo(available) > 0) {
            enteredAmount = available;
            Coin finalEnteredAmount = enteredAmount;
            inputTextField.setText(formatter.formatCoin(finalEnteredAmount));
        }
        Coin counterPartAsCoin = available.subtract(enteredAmount);
        String formattedCounterPartAmount = formatter.formatCoin(counterPartAsCoin);
        Coin buyerAmount;
        Coin sellerAmount;
        if (inputTextField == buyerPayoutAmountInputTextField) {
            buyerAmount = enteredAmount;
            sellerAmount = counterPartAsCoin;
            sellerPayoutAmountInputTextField.setText(formattedCounterPartAmount);
        } else {
            sellerAmount = enteredAmount;
            buyerAmount = counterPartAsCoin;
            buyerPayoutAmountInputTextField.setText(formattedCounterPartAmount);
        }

        disputeResult.setBuyerPayoutAmount(buyerAmount);
        disputeResult.setSellerPayoutAmount(sellerAmount);
        disputeResult.setWinner(buyerAmount.compareTo(sellerAmount) > 0 ?
                DisputeResult.Winner.BUYER :
                DisputeResult.Winner.SELLER);
    }

    private void addPayoutAmountTextFields() {
        buyerPayoutAmountInputTextField = new InputTextField();
        buyerPayoutAmountInputTextField.setLabelFloat(true);
        buyerPayoutAmountInputTextField.setEditable(false);
        buyerPayoutAmountInputTextField.setPromptText(Res.get("disputeSummaryWindow.payoutAmount.buyer"));

        sellerPayoutAmountInputTextField = new InputTextField();
        sellerPayoutAmountInputTextField.setLabelFloat(true);
        sellerPayoutAmountInputTextField.setPromptText(Res.get("disputeSummaryWindow.payoutAmount.seller"));
        sellerPayoutAmountInputTextField.setEditable(false);

        isLoserPublisherCheckBox = new AutoTooltipCheckBox(Res.get("disputeSummaryWindow.payoutAmount.invert"));

        VBox vBox = new VBox();
        vBox.setSpacing(15);
        vBox.getChildren().addAll(buyerPayoutAmountInputTextField, sellerPayoutAmountInputTextField, isLoserPublisherCheckBox);
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
          disputesService.resolveDisputePayout(dispute, disputeResult, contract);
          doClose(closeTicketButton);

//          if (dispute.getDepositTxSerialized() == null) {
//              log.warn("dispute.getDepositTxSerialized is null");
//              return;
//          }
//
//          if (dispute.getSupportType() == SupportType.REFUND &&
//                  peersDisputeOptional.isPresent() &&
//                  !peersDisputeOptional.get().isClosed()) {
//              showPayoutTxConfirmation(contract,
//                      disputeResult,
//                      () -> doCloseIfValid(closeTicketButton));
//          } else {
//              doCloseIfValid(closeTicketButton);
//          }
      });

      cancelButton.setOnAction(e -> {
          dispute.setDisputeResult(disputeResult);
          checkNotNull(getDisputeManager(dispute)).requestPersistence();
          hide();
      });
    }

    private void showPayoutTxConfirmation(Contract contract, DisputeResult disputeResult, ResultHandler resultHandler) {
        throw new RuntimeException("DisputeSummaryWindow.showPayoutTxConfimration() needs updated for XMR");
//        Coin buyerPayoutAmount = disputeResult.getBuyerPayoutAmount();
//        String buyerPayoutAddressString = contract.getBuyerPayoutAddressString();
//        Coin sellerPayoutAmount = disputeResult.getSellerPayoutAmount();
//        String sellerPayoutAddressString = contract.getSellerPayoutAddressString();
//        Coin outputAmount = buyerPayoutAmount.add(sellerPayoutAmount);
//        Tuple2<Coin, Integer> feeTuple = txFeeEstimationService.getEstimatedFeeAndTxSize(outputAmount, feeService, btcWalletService);
//        Coin fee = feeTuple.first;
//        Integer txSize = feeTuple.second;
//        double feePerByte = CoinUtil.getFeePerByte(fee, txSize);
//        double kb = txSize / 1000d;
//        Coin inputAmount = outputAmount.add(fee);
//        String buyerDetails = "";
//        if (buyerPayoutAmount.isPositive()) {
//            buyerDetails = Res.get("disputeSummaryWindow.close.txDetails.buyer",
//                    formatter.formatCoinWithCode(buyerPayoutAmount),
//                    buyerPayoutAddressString);
//        }
//        String sellerDetails = "";
//        if (sellerPayoutAmount.isPositive()) {
//            sellerDetails = Res.get("disputeSummaryWindow.close.txDetails.seller",
//                    formatter.formatCoinWithCode(sellerPayoutAmount),
//                    sellerPayoutAddressString);
//        }
//        if (outputAmount.isPositive()) {
//            new Popup().width(900)
//                    .headLine(Res.get("disputeSummaryWindow.close.txDetails.headline"))
//                    .confirmation(Res.get("disputeSummaryWindow.close.txDetails",
//                            formatter.formatCoinWithCode(inputAmount),
//                            buyerDetails,
//                            sellerDetails,
//                            formatter.formatCoinWithCode(fee),
//                            feePerByte,
//                            kb))
//                    .actionButtonText(Res.get("shared.yes"))
//                    .onAction(() -> {
//                        doPayout(buyerPayoutAmount,
//                                sellerPayoutAmount,
//                                fee,
//                                buyerPayoutAddressString,
//                                sellerPayoutAddressString,
//                                resultHandler);
//                    })
//                    .closeButtonText(Res.get("shared.cancel"))
//                    .show();
//        } else {
//            // No payout will be made
//            new Popup().headLine(Res.get("disputeSummaryWindow.close.noPayout.headline"))
//                    .confirmation(Res.get("disputeSummaryWindow.close.noPayout.text"))
//                    .actionButtonText(Res.get("shared.yes"))
//                    .onAction(resultHandler::handleResult)
//                    .closeButtonText(Res.get("shared.cancel"))
//                    .show();
//        }
    }

    private void doPayout(Coin buyerPayoutAmount,
                          Coin sellerPayoutAmount,
                          Coin fee,
                          String buyerPayoutAddressString,
                          String sellerPayoutAddressString,
                          ResultHandler resultHandler) {
        throw new RuntimeException("DisputeSummaryWindow.doPayout() needs updated for XMR");
//        try {
//            Transaction tx = btcWalletService.createRefundPayoutTx(buyerPayoutAmount,
//                    sellerPayoutAmount,
//                    fee,
//                    buyerPayoutAddressString,
//                    sellerPayoutAddressString);
//            tradeWalletService.broadcastTx(tx, new TxBroadcaster.Callback() {
//                @Override
//                public void onSuccess(Transaction transaction) {
//                    resultHandler.handleResult();
//                }
//
//                @Override
//                public void onFailure(TxBroadcastException exception) {
//                    log.error("TxBroadcastException at doPayout", exception);
//                    new Popup().error(exception.toString()).show();
//                }
//            });
//        } catch (InsufficientMoneyException | WalletException | TransactionVerificationException e) {
//            log.error("Exception at doPayout", e);
//            new Popup().error(e.toString()).show();
//        }
    }

    private void doCloseIfValid(Button closeTicketButton) {
        throw new RuntimeException("DisputeSummaryWindow.doCloseIfValid() needs updated for XMR");
//        var disputeManager = checkNotNull(getDisputeManager(dispute));
//        try {
//            TradeDataValidation.validateDonationAddress(dispute.getDonationAddressOfDelayedPayoutTx());
//            TradeDataValidation.testIfDisputeTriesReplay(dispute, disputeManager.getDisputesAsObservableList());
//            doClose(closeTicketButton);
//        } catch (TradeDataValidation.AddressException exception) {
//            String addressAsString = dispute.getDonationAddressOfDelayedPayoutTx();
//            String tradeId = dispute.getTradeId();
//
//            // For mediators we do not enforce that the case cannot be closed to stay flexible,
//            // but for refund agents we do.
//            if (disputeManager instanceof MediationManager) {
//                new Popup().width(900)
//                        .warning(Res.get("support.warning.disputesWithInvalidDonationAddress",
//                                addressAsString,
//                                tradeId,
//                                Res.get("support.warning.disputesWithInvalidDonationAddress.mediator")))
//                        .onAction(() -> {
//                            doClose(closeTicketButton);
//                        })
//                        .actionButtonText(Res.get("shared.yes"))
//                        .closeButtonText(Res.get("shared.no"))
//                        .show();
//            } else {
//                new Popup().width(900)
//                        .warning(Res.get("support.warning.disputesWithInvalidDonationAddress",
//                                addressAsString,
//                                tradeId,
//                                Res.get("support.warning.disputesWithInvalidDonationAddress.refundAgent")))
//                        .show();
//            }
//        } catch (TradeDataValidation.DisputeReplayException exception) {
//            if (disputeManager instanceof MediationManager) {
//                new Popup().width(900)
//                        .warning(exception.getMessage())
//                        .onAction(() -> {
//                            doClose(closeTicketButton);
//                        })
//                        .actionButtonText(Res.get("shared.yes"))
//                        .closeButtonText(Res.get("shared.no"))
//                        .show();
//            } else {
//                new Popup().width(900)
//                        .warning(exception.getMessage())
//                        .show();
//            }
//        }
    }

    private void doClose(Button closeTicketButton) {
        DisputeManager<? extends DisputeList<Dispute>> disputeManager = getDisputeManager(dispute);
        if (disputeManager == null) {
            return;
        }

        summaryNotesTextArea.textProperty().unbindBidirectional(disputeResult.summaryNotesProperty());

        boolean isRefundAgent = disputeManager instanceof RefundManager;
        disputeResult.setLoserPublisher(isLoserPublisherCheckBox.isSelected());
        disputeResult.setCloseDate(new Date());
        disputesService.closeDispute(disputeManager, dispute, disputeResult, isRefundAgent);

        if (peersDisputeOptional.isPresent() && !peersDisputeOptional.get().isClosed() && !DevEnv.isDevMode()) {
            UserThread.runAfter(() -> new Popup()
                            .attention(Res.get("disputeSummaryWindow.close.closePeer"))
                            .show(),
                    200, TimeUnit.MILLISECONDS);
        }

        disputeManager.requestPersistence();
        closeTicketButton.disableProperty().unbind();
        hide();
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
        CoreDisputesService.DisputePayout payout;
        if (selectedTradeAmountToggle == buyerGetsTradeAmountRadioButton) {
            payout = CoreDisputesService.DisputePayout.BUYER_GETS_TRADE_AMOUNT;
            disputeResult.setWinner(DisputeResult.Winner.BUYER);
        } else if (selectedTradeAmountToggle == buyerGetsAllRadioButton) {
            payout = CoreDisputesService.DisputePayout.BUYER_GETS_ALL;
            disputeResult.setWinner(DisputeResult.Winner.BUYER);
        } else if (selectedTradeAmountToggle == sellerGetsTradeAmountRadioButton) {
            payout = CoreDisputesService.DisputePayout.SELLER_GETS_TRADE_AMOUNT;
            disputeResult.setWinner(DisputeResult.Winner.SELLER);
        } else if (selectedTradeAmountToggle == sellerGetsAllRadioButton) {
            payout = CoreDisputesService.DisputePayout.SELLER_GETS_ALL;
            disputeResult.setWinner(DisputeResult.Winner.SELLER);
        } else {
            // should not happen
            throw new IllegalStateException("Unknown radio button");
        }
        disputesService.applyPayoutAmountsToDisputeResult(payout, dispute, disputeResult, -1);
        buyerPayoutAmountInputTextField.setText(formatter.formatCoin(disputeResult.getBuyerPayoutAmount()));
        sellerPayoutAmountInputTextField.setText(formatter.formatCoin(disputeResult.getSellerPayoutAmount()));
    }

    private void applyTradeAmountRadioButtonStates() {
        Contract contract = dispute.getContract();
        Offer offer = new Offer(contract.getOfferPayload());
        Coin buyerSecurityDeposit = offer.getBuyerSecurityDeposit();
        Coin sellerSecurityDeposit = offer.getSellerSecurityDeposit();
        Coin tradeAmount = contract.getTradeAmount();

        Coin buyerPayoutAmount = disputeResult.getBuyerPayoutAmount();
        Coin sellerPayoutAmount = disputeResult.getSellerPayoutAmount();

        buyerPayoutAmountInputTextField.setText(formatter.formatCoin(buyerPayoutAmount));
        sellerPayoutAmountInputTextField.setText(formatter.formatCoin(sellerPayoutAmount));

        if (buyerPayoutAmount.equals(tradeAmount.add(buyerSecurityDeposit)) &&
                sellerPayoutAmount.equals(sellerSecurityDeposit)) {
            buyerGetsTradeAmountRadioButton.setSelected(true);
        } else if (buyerPayoutAmount.equals(tradeAmount.add(buyerSecurityDeposit).add(sellerSecurityDeposit)) &&
                sellerPayoutAmount.equals(Coin.ZERO)) { // TODO (woodser): apply min payout to incentivize loser (see post v1.1.7)
            buyerGetsAllRadioButton.setSelected(true);
        } else if (sellerPayoutAmount.equals(tradeAmount.add(sellerSecurityDeposit))
                && buyerPayoutAmount.equals(buyerSecurityDeposit)) {
            sellerGetsTradeAmountRadioButton.setSelected(true);
        } else if (sellerPayoutAmount.equals(tradeAmount.add(buyerSecurityDeposit).add(sellerSecurityDeposit))
                && buyerPayoutAmount.equals(Coin.ZERO)) {
            sellerGetsAllRadioButton.setSelected(true);
        } else {
            customRadioButton.setSelected(true);
        }
    }
}
