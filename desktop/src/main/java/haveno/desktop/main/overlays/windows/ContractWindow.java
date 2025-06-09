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

import com.google.common.base.Joiner;
import com.google.inject.Inject;
import haveno.common.UserThread;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.CountryUtil;
import haveno.core.locale.Res;
import haveno.core.offer.Offer;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.DisputeList;
import haveno.core.support.dispute.DisputeManager;
import haveno.core.support.dispute.arbitration.ArbitrationManager;
import haveno.core.support.dispute.mediation.MediationManager;
import haveno.core.support.dispute.refund.RefundManager;
import haveno.core.trade.Contract;
import haveno.core.trade.HavenoUtils;
import haveno.core.util.FormattingUtils;
import haveno.core.util.VolumeUtil;
import haveno.desktop.components.HavenoTextArea;
import haveno.desktop.main.MainView;
import haveno.desktop.main.overlays.Overlay;
import haveno.desktop.util.DisplayUtils;
import static haveno.desktop.util.DisplayUtils.getAccountWitnessDescription;
import static haveno.desktop.util.FormBuilder.addButtonAfterGroup;
import static haveno.desktop.util.FormBuilder.addConfirmationLabelButton;
import static haveno.desktop.util.FormBuilder.addConfirmationLabelTextField;
import static haveno.desktop.util.FormBuilder.addConfirmationLabelTextFieldWithCopyIcon;
import static haveno.desktop.util.FormBuilder.addLabelExplorerAddressTextField;
import static haveno.desktop.util.FormBuilder.addLabelTxIdTextField;
import static haveno.desktop.util.FormBuilder.addSeparator;
import static haveno.desktop.util.FormBuilder.addTitledGroupBg;
import haveno.desktop.util.Layout;
import haveno.network.p2p.NodeAddress;
import java.util.List;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Utils;

@Slf4j
public class ContractWindow extends Overlay<ContractWindow> {
    private final ArbitrationManager arbitrationManager;
    private final MediationManager mediationManager;
    private final RefundManager refundManager;
    private final AccountAgeWitnessService accountAgeWitnessService;
    private Dispute dispute;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Public API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public ContractWindow(ArbitrationManager arbitrationManager,
                          MediationManager mediationManager,
                          RefundManager refundManager,
                          AccountAgeWitnessService accountAgeWitnessService) {
        this.arbitrationManager = arbitrationManager;
        this.mediationManager = mediationManager;
        this.refundManager = refundManager;
        this.accountAgeWitnessService = accountAgeWitnessService;
        type = Type.Confirmation;
    }

    public void show(Dispute dispute) {
        this.dispute = dispute;

        rowIndex = -1;
        width = 1168;
        createGridPane();
        addContent();
        display();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Protected
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void createGridPane() {
        super.createGridPane();

        gridPane.getColumnConstraints().get(0).setMinWidth(250d);

        gridPane.setPadding(new Insets(35, 40, 30, 40));
        gridPane.getStyleClass().add("grid-pane");
    }

    private void addContent() {
        Contract contract = dispute.getContract();
        Offer offer = new Offer(contract.getOfferPayload());

        List<String> acceptedBanks = offer.getAcceptedBankIds();
        boolean showAcceptedBanks = acceptedBanks != null && !acceptedBanks.isEmpty();
        List<String> acceptedCountryCodes = offer.getAcceptedCountryCodes();
        boolean showAcceptedCountryCodes = acceptedCountryCodes != null && !acceptedCountryCodes.isEmpty();

        int rows = 18;
        if (dispute.getPayoutTxSerialized() != null)
            rows++;
        if (dispute.getDelayedPayoutTxId() != null)
            rows++;
        if (dispute.getDonationAddressOfDelayedPayoutTx() != null)
            rows++;
        if (showAcceptedCountryCodes)
            rows++;
        if (showAcceptedBanks)
            rows++;

        addTitledGroupBg(gridPane, ++rowIndex, rows, Res.get("contractWindow.title"));
        addConfirmationLabelTextField(gridPane, rowIndex, Res.get("shared.offerId"), offer.getId(),
                Layout.TWICE_FIRST_ROW_DISTANCE);
        addSeparator(gridPane, ++rowIndex);
        addConfirmationLabelTextField(gridPane, ++rowIndex, Res.get("contractWindow.dates"),
                DisplayUtils.formatDateTime(offer.getDate()) + " / " + DisplayUtils.formatDateTime(dispute.getTradeDate()));
        String currencyCode = offer.getCurrencyCode();
        addSeparator(gridPane, ++rowIndex);
        addConfirmationLabelTextField(gridPane, ++rowIndex, Res.get("shared.offerType"),
                DisplayUtils.getDirectionBothSides(offer.getDirection(), offer.isPrivateOffer()));
        addSeparator(gridPane, ++rowIndex);        
        addConfirmationLabelTextField(gridPane, ++rowIndex, Res.get("shared.tradePrice"),
                FormattingUtils.formatPrice(contract.getPrice()));
        addSeparator(gridPane, ++rowIndex);
        addConfirmationLabelTextField(gridPane, ++rowIndex, Res.get("shared.tradeAmount"),
                HavenoUtils.formatXmr(contract.getTradeAmount(), true));
        addSeparator(gridPane, ++rowIndex);
        addConfirmationLabelTextField(gridPane,
                ++rowIndex,
                VolumeUtil.formatVolumeLabel(currencyCode, ":"),
                VolumeUtil.formatVolumeWithCode(contract.getTradeVolume()));
        String securityDeposit = Res.getWithColAndCap("shared.buyer") +
                " " +
                HavenoUtils.formatXmr(offer.getOfferPayload().getBuyerSecurityDepositForTradeAmount(contract.getTradeAmount()), true) +
                " / " +
                Res.getWithColAndCap("shared.seller") +
                " " +
                HavenoUtils.formatXmr(offer.getOfferPayload().getSellerSecurityDepositForTradeAmount(contract.getTradeAmount()), true);
        addSeparator(gridPane, ++rowIndex);                
        addConfirmationLabelTextField(gridPane, ++rowIndex, Res.get("shared.securityDeposit"), securityDeposit);
        addSeparator(gridPane, ++rowIndex);
        addConfirmationLabelTextField(gridPane,
                ++rowIndex,
                Res.get("contractWindow.xmrAddresses"),
                contract.getBuyerPayoutAddressString() + " / " + contract.getSellerPayoutAddressString());
        addSeparator(gridPane, ++rowIndex);
        addConfirmationLabelTextField(gridPane,
                ++rowIndex,
                Res.get("contractWindow.onions"),
                contract.getBuyerNodeAddress().getFullAddress() + " / " + contract.getSellerNodeAddress().getFullAddress());

        addSeparator(gridPane, ++rowIndex);
        addConfirmationLabelTextField(gridPane,
                ++rowIndex,
                Res.get("contractWindow.accountAge"),
                getAccountWitnessDescription(accountAgeWitnessService, offer.getPaymentMethod(), dispute.getBuyerPaymentAccountPayload(), contract.getBuyerPubKeyRing()) + " / " +
                        getAccountWitnessDescription(accountAgeWitnessService, offer.getPaymentMethod(), dispute.getSellerPaymentAccountPayload(), contract.getSellerPubKeyRing()));

        DisputeManager<? extends DisputeList<Dispute>> disputeManager = getDisputeManager(dispute);
        String nrOfDisputesAsBuyer = disputeManager != null ? disputeManager.getNrOfDisputes(true, contract) : "";
        String nrOfDisputesAsSeller = disputeManager != null ? disputeManager.getNrOfDisputes(false, contract) : "";
        addSeparator(gridPane, ++rowIndex);
        addConfirmationLabelTextField(gridPane,
                ++rowIndex,
                Res.get("contractWindow.numDisputes"),
                nrOfDisputesAsBuyer + " / " + nrOfDisputesAsSeller);
        addSeparator(gridPane, ++rowIndex);
        addConfirmationLabelTextField(gridPane,
                ++rowIndex,
                Res.get("shared.paymentDetails", Res.get("shared.buyer")),
                dispute.getBuyerPaymentAccountPayload() != null
                        ? dispute.getBuyerPaymentAccountPayload().getPaymentDetails()
                        : "NA");
        addSeparator(gridPane, ++rowIndex);
        addConfirmationLabelTextField(gridPane,
                ++rowIndex,
                Res.get("shared.paymentDetails", Res.get("shared.seller")),
                dispute.getSellerPaymentAccountPayload() != null
                        ? dispute.getSellerPaymentAccountPayload().getPaymentDetails()
                        : "NA");

        String title = "";
        String agentMatrixUserName = "";
        if (dispute.getSupportType() != null) {
            switch (dispute.getSupportType()) {
                case ARBITRATION:
                    title = Res.get("shared.selectedArbitrator");
                    break;
                case MEDIATION:
                    throw new RuntimeException("Mediation type not adapted to XMR");
//                    agentMatrixUserName = DisputeAgentLookupMap.getMatrixUserName(contract.getMediatorNodeAddress().getFullAddress());
//                    title = Res.get("shared.selectedMediator");
//                    break;
                case TRADE:
                    break;
                case REFUND:
                    throw new RuntimeException("Refund type not adapted to XMR");
//                    agentMatrixUserName = DisputeAgentLookupMap.getMatrixUserName(contract.getRefundAgentNodeAddress().getFullAddress());
//                    title = Res.get("shared.selectedRefundAgent");
//                    break;
            }
        }

        if (disputeManager != null) {
            NodeAddress agentNodeAddress = disputeManager.getAgentNodeAddress(dispute);
            if (agentNodeAddress != null) {
                String value = agentMatrixUserName + " (" + agentNodeAddress.getFullAddress() + ")";
                addSeparator(gridPane, ++rowIndex);
                addConfirmationLabelTextField(gridPane, ++rowIndex, title, value);
            }
        }

        if (showAcceptedCountryCodes) {
            String countries;
            Tooltip tooltip = null;
            if (CountryUtil.containsAllSepaEuroCountries(acceptedCountryCodes)) {
                countries = Res.get("shared.allEuroCountries");
            } else {
                countries = CountryUtil.getCodesString(acceptedCountryCodes);
                tooltip = new Tooltip(CountryUtil.getNamesByCodesString(acceptedCountryCodes));
            }
            addSeparator(gridPane, ++rowIndex);
            addConfirmationLabelTextField(gridPane, ++rowIndex, Res.get("shared.acceptedTakerCountries"), countries)
                    .second.setTooltip(tooltip);
        }

        if (showAcceptedBanks) {
            if (offer.getPaymentMethod().equals(PaymentMethod.SAME_BANK)) {
                addSeparator(gridPane, ++rowIndex);
                addConfirmationLabelTextField(gridPane, ++rowIndex, Res.get("shared.bankName"), acceptedBanks.get(0));
            } else if (offer.getPaymentMethod().equals(PaymentMethod.SPECIFIC_BANKS)) {
                String value = Joiner.on(", ").join(acceptedBanks);
                Tooltip tooltip = new Tooltip(Res.get("shared.acceptedBanks") + value);
                addSeparator(gridPane, ++rowIndex);
                addConfirmationLabelTextField(gridPane, ++rowIndex, Res.get("shared.acceptedBanks"), value)
                        .second.setTooltip(tooltip);
            }
        }

        addSeparator(gridPane, ++rowIndex);
        addLabelTxIdTextField(gridPane, ++rowIndex, Res.get("shared.makerDepositTransactionId"), contract.getMakerDepositTxHash());
        if (contract.getTakerDepositTxHash() != null) {
            addLabelTxIdTextField(gridPane, ++rowIndex, Res.get("shared.takerDepositTransactionId"), contract.getTakerDepositTxHash());
        }

        if (dispute.getDelayedPayoutTxId() != null) {
            addSeparator(gridPane, ++rowIndex);
            addLabelTxIdTextField(gridPane, ++rowIndex, Res.get("shared.delayedPayoutTxId"), dispute.getDelayedPayoutTxId());
        }

        if (dispute.getDonationAddressOfDelayedPayoutTx() != null) {
            addSeparator(gridPane, ++rowIndex);
            addLabelExplorerAddressTextField(gridPane, ++rowIndex, Res.get("shared.delayedPayoutTxReceiverAddress"),
                    dispute.getDonationAddressOfDelayedPayoutTx());
        }

        if (dispute.getPayoutTxSerialized() != null) {
            addSeparator(gridPane, ++rowIndex);
            addLabelTxIdTextField(gridPane, ++rowIndex, Res.get("shared.payoutTxId"), dispute.getPayoutTxId());
        }

        if (dispute.getContractHash() != null) {
            addSeparator(gridPane, ++rowIndex);
            addConfirmationLabelTextFieldWithCopyIcon(gridPane, ++rowIndex, Res.get("contractWindow.contractHash"),
                    Utils.HEX.encode(dispute.getContractHash())).second.setMouseTransparent(false);
        }

        addSeparator(gridPane, ++rowIndex);
        Button viewContractButton = addConfirmationLabelButton(gridPane, ++rowIndex, Res.get("shared.contractAsJson"),
                Res.get("shared.viewContractAsJson"), 0).second;
        viewContractButton.setDefaultButton(false);
        viewContractButton.setOnAction(e -> {
            TextArea textArea = new HavenoTextArea();
            String contractAsJson = dispute.getContractAsJson();
            textArea.setText(contractAsJson);
            textArea.setPrefHeight(50);
            textArea.setEditable(false);
            textArea.setWrapText(true);
            textArea.setPrefSize(800, 600);

            Scene viewContractScene = new Scene(textArea);
            Stage viewContractStage = new Stage();
            viewContractStage.setTitle(Res.get("shared.contract.title", dispute.getShortTradeId()));
            viewContractStage.setScene(viewContractScene);
            if (owner == null)
                owner = MainView.getRootContainer();
            Scene rootScene = owner.getScene();
            viewContractStage.initOwner(rootScene.getWindow());
            viewContractStage.initModality(Modality.NONE);
            viewContractStage.initStyle(StageStyle.UTILITY);
            viewContractStage.setOpacity(0);
            viewContractStage.show();

            Window window = rootScene.getWindow();
            double titleBarHeight = window.getHeight() - rootScene.getHeight();
            viewContractStage.setX(Math.round(window.getX() + (owner.getWidth() - viewContractStage.getWidth()) / 2) + 200);
            viewContractStage.setY(Math.round(window.getY() + titleBarHeight + (owner.getHeight() - viewContractStage.getHeight()) / 2) + 50);
            // Delay display to next render frame to avoid that the popup is first quickly displayed in default position
            // and after a short moment in the correct position
            UserThread.execute(() -> viewContractStage.setOpacity(1));

            viewContractScene.setOnKeyPressed(ev -> {
                if (ev.getCode() == KeyCode.ESCAPE) {
                    ev.consume();
                    viewContractStage.hide();
                }
            });
        });

        Button closeButton = addButtonAfterGroup(gridPane, ++rowIndex, Res.get("shared.close"));
        GridPane.setColumnSpan(closeButton, 2);
        closeButton.setOnAction(e -> {
            closeHandlerOptional.ifPresent(Runnable::run);
            hide();
        });
    }

    private DisputeManager<? extends DisputeList<Dispute>> getDisputeManager(Dispute dispute) {
        if (dispute.getSupportType() != null) {
            switch (dispute.getSupportType()) {
                case ARBITRATION:
                    return arbitrationManager;
                case MEDIATION:
                    return mediationManager;
                case TRADE:
                    break;
                case REFUND:
                    return refundManager;
            }
        }
        return null;
    }
}
