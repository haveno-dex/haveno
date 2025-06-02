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
import haveno.common.util.Tuple3;
import haveno.common.util.Utilities;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.Res;
import haveno.core.offer.Offer;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.support.dispute.arbitration.ArbitrationManager;
import haveno.core.trade.Contract;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.util.FormattingUtils;
import haveno.core.util.VolumeUtil;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.xmr.wallet.BtcWalletService;
import haveno.desktop.components.HavenoTextArea;
import haveno.desktop.main.MainView;
import haveno.desktop.main.overlays.Overlay;
import haveno.desktop.util.DisplayUtils;
import haveno.desktop.util.GUIUtil;

import static haveno.desktop.util.DisplayUtils.getAccountWitnessDescription;
import static haveno.desktop.util.FormBuilder.add2ButtonsWithBox;
import static haveno.desktop.util.FormBuilder.addConfirmationLabelTextArea;
import static haveno.desktop.util.FormBuilder.addConfirmationLabelTextField;
import static haveno.desktop.util.FormBuilder.addLabelTxIdTextField;
import static haveno.desktop.util.FormBuilder.addSeparator;
import static haveno.desktop.util.FormBuilder.addTitledGroupBg;
import haveno.desktop.util.Layout;
import haveno.network.p2p.NodeAddress;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.value.ChangeListener;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TradeDetailsWindow extends Overlay<TradeDetailsWindow> {
    protected static final Logger log = LoggerFactory.getLogger(TradeDetailsWindow.class);

    private final CoinFormatter formatter;
    private final ArbitrationManager arbitrationManager;
    private final TradeManager tradeManager;
    private final BtcWalletService btcWalletService;
    private final AccountAgeWitnessService accountAgeWitnessService;
    private Trade trade;
    private ChangeListener<Number> changeListener;
    private TextArea textArea;
    private String buyersAccountAge;
    private String sellersAccountAge;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Public API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public TradeDetailsWindow(@Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter formatter,
                              ArbitrationManager arbitrationManager,
                              TradeManager tradeManager,
                              BtcWalletService btcWalletService,
                              AccountAgeWitnessService accountAgeWitnessService) {
        this.formatter = formatter;
        this.arbitrationManager = arbitrationManager;
        this.tradeManager = tradeManager;
        this.btcWalletService = btcWalletService;
        this.accountAgeWitnessService = accountAgeWitnessService;
        type = Type.Confirmation;
    }

    public void show(Trade trade) {
        this.trade = trade;

        rowIndex = -1;
        width = Layout.DETAILS_WINDOW_WIDTH;
        createGridPane();
        addContent();
        display();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Protected
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void cleanup() {
        if (textArea != null)
            textArea.scrollTopProperty().addListener(changeListener);
    }

    @Override
    protected void createGridPane() {
        super.createGridPane();
        gridPane.getStyleClass().add("grid-pane");
    }

    private void addContent() {
        Offer offer = trade.getOffer();
        Contract contract = trade.getContract();

        int rows = 9;
        addTitledGroupBg(gridPane, ++rowIndex, rows, Res.get("tradeDetailsWindow.headline"));

        boolean myOffer = tradeManager.isMyOffer(offer);
        String counterCurrencyDirectionInfo;
        String xmrDirectionInfo;
        String toReceive = " " + Res.get("shared.toReceive");
        String toSpend = " " + Res.get("shared.toSpend");
        String offerType = Res.get("shared.offerType");
        if (tradeManager.isBuyer(offer)) {
            addConfirmationLabelTextField(gridPane, rowIndex, offerType,
                    DisplayUtils.getDirectionForBuyer(myOffer, offer.getCurrencyCode()), Layout.TWICE_FIRST_ROW_DISTANCE);
            counterCurrencyDirectionInfo = toSpend;
            xmrDirectionInfo = toReceive;
        } else {
            addConfirmationLabelTextField(gridPane, rowIndex, offerType,
                    DisplayUtils.getDirectionForSeller(myOffer, offer.getCurrencyCode()), Layout.TWICE_FIRST_ROW_DISTANCE);
            counterCurrencyDirectionInfo = toReceive;
            xmrDirectionInfo = toSpend;
        }

        addSeparator(gridPane, ++rowIndex);
        addConfirmationLabelTextField(gridPane, ++rowIndex, Res.get("shared.xmrAmount") + xmrDirectionInfo,
                HavenoUtils.formatXmr(trade.getAmount(), true));
        addSeparator(gridPane, ++rowIndex);
        addConfirmationLabelTextField(gridPane, ++rowIndex,
                VolumeUtil.formatVolumeLabel(offer.getCurrencyCode()) + counterCurrencyDirectionInfo,
                VolumeUtil.formatVolumeWithCode(trade.getVolume()));
        addSeparator(gridPane, ++rowIndex);
        addConfirmationLabelTextField(gridPane, ++rowIndex, Res.get("shared.tradePrice"),
                FormattingUtils.formatPrice(trade.getPrice()));
        String paymentMethodText = Res.get(offer.getPaymentMethod().getId());
        addSeparator(gridPane, ++rowIndex);
        addConfirmationLabelTextField(gridPane, ++rowIndex, Res.get("shared.paymentMethod"), paymentMethodText);

        // second group
        rows = 5;

        if (offer.getCombinedExtraInfo() != null && !offer.getCombinedExtraInfo().isEmpty())
            rows++;

        PaymentAccountPayload buyerPaymentAccountPayload = null;
        PaymentAccountPayload sellerPaymentAccountPayload = null;
        if (contract != null) {
            rows++;

            buyerPaymentAccountPayload = trade.getBuyer().getPaymentAccountPayload();
            sellerPaymentAccountPayload = trade.getSeller().getPaymentAccountPayload();
            if (buyerPaymentAccountPayload != null)
                rows++;

            if (sellerPaymentAccountPayload != null)
                rows++;

            if (buyerPaymentAccountPayload == null && sellerPaymentAccountPayload == null)
                rows++;
        }

        boolean showDisputedTx = arbitrationManager.findOwnDispute(trade.getId()).isPresent() &&
                arbitrationManager.findOwnDispute(trade.getId()).get().getDisputePayoutTxId() != null;
        if (showDisputedTx)
            rows++;
        else if (trade.getPayoutTxId() != null)
            rows++;
        if (trade.hasFailed())
            rows += 2;
        if (trade.getTradePeerNodeAddress() != null)
            rows++;

        addTitledGroupBg(gridPane, ++rowIndex, rows, Res.get("shared.details"), Layout.GROUP_DISTANCE);
        addConfirmationLabelTextField(gridPane, rowIndex, Res.get("shared.tradeId"),
                trade.getId(), Layout.TWICE_FIRST_ROW_AND_GROUP_DISTANCE);
        addSeparator(gridPane, ++rowIndex);
        addConfirmationLabelTextField(gridPane, ++rowIndex, Res.get("tradeDetailsWindow.tradeDate"),
                DisplayUtils.formatDateTime(trade.getDate()));
        String securityDeposit = Res.getWithColAndCap("shared.buyer") +
                " " +
                HavenoUtils.formatXmr(trade.getBuyerSecurityDepositBeforeMiningFee(), true) +
                " / " +
                Res.getWithColAndCap("shared.seller") +
                " " +
                HavenoUtils.formatXmr(trade.getSellerSecurityDepositBeforeMiningFee(), true);
        addSeparator(gridPane, ++rowIndex);
        addConfirmationLabelTextField(gridPane, ++rowIndex, Res.get("shared.securityDeposit"), securityDeposit);

        NodeAddress arbitratorNodeAddress = trade.getArbitratorNodeAddress();
        if (arbitratorNodeAddress != null) {
            addSeparator(gridPane, ++rowIndex);
            addConfirmationLabelTextField(gridPane, ++rowIndex,
                    Res.get("tradeDetailsWindow.agentAddresses"),
                    arbitratorNodeAddress.getFullAddress());
        }

        if (trade.getTradePeerNodeAddress() != null) {
            addSeparator(gridPane, ++rowIndex);
            addConfirmationLabelTextField(gridPane, ++rowIndex, Res.get("tradeDetailsWindow.tradePeersOnion"),
                    trade.getTradePeerNodeAddress().getFullAddress());
        }

        if (offer.getCombinedExtraInfo() != null && !offer.getCombinedExtraInfo().isEmpty()) {
            addSeparator(gridPane, ++rowIndex);
            TextArea textArea = addConfirmationLabelTextArea(gridPane, ++rowIndex, Res.get("payment.shared.extraInfo.offer"), "", 0).second;
            textArea.setText(offer.getCombinedExtraInfo().trim());
            textArea.setMaxHeight(Layout.DETAILS_WINDOW_EXTRA_INFO_MAX_HEIGHT);
            textArea.setEditable(false);
            GUIUtil.adjustHeightAutomatically(textArea, Layout.DETAILS_WINDOW_EXTRA_INFO_MAX_HEIGHT);
        }

        if (contract != null) {
            buyersAccountAge = getAccountWitnessDescription(accountAgeWitnessService, offer.getPaymentMethod(), buyerPaymentAccountPayload, contract.getBuyerPubKeyRing());
            sellersAccountAge = getAccountWitnessDescription(accountAgeWitnessService, offer.getPaymentMethod(), sellerPaymentAccountPayload, contract.getSellerPubKeyRing());
            if (buyerPaymentAccountPayload != null) {
                String paymentDetails = buyerPaymentAccountPayload.getPaymentDetails();
                String postFix = " / " + buyersAccountAge;
                addSeparator(gridPane, ++rowIndex);
                addConfirmationLabelTextField(gridPane, ++rowIndex,
                        Res.get("shared.paymentDetails", Res.get("shared.buyer")),
                        paymentDetails + postFix).second.setTooltip(new Tooltip(paymentDetails + postFix));
            }
            if (sellerPaymentAccountPayload != null) {
                String paymentDetails = sellerPaymentAccountPayload.getPaymentDetails();
                String postFix = " / " + sellersAccountAge;
                addSeparator(gridPane, ++rowIndex);
                addConfirmationLabelTextField(gridPane, ++rowIndex,
                        Res.get("shared.paymentDetails", Res.get("shared.seller")),
                        paymentDetails + postFix).second.setTooltip(new Tooltip(paymentDetails + postFix));
            }
            if (buyerPaymentAccountPayload == null && sellerPaymentAccountPayload == null) {
                addSeparator(gridPane, ++rowIndex);
                addConfirmationLabelTextField(gridPane, ++rowIndex, Res.get("shared.paymentMethod"),
                        Res.get(contract.getPaymentMethodId()));
            }
        }

        if (trade.getMaker().getDepositTxHash() != null) {
            addSeparator(gridPane, ++rowIndex);
            addLabelTxIdTextField(gridPane, ++rowIndex, Res.get("shared.makerDepositTransactionId"),
                    trade.getMaker().getDepositTxHash());
        }
        if (trade.getTaker().getDepositTxHash() != null) {
            addLabelTxIdTextField(gridPane, ++rowIndex, Res.get("shared.takerDepositTransactionId"),
                    trade.getTaker().getDepositTxHash());
        }


        if (showDisputedTx) {
            addLabelTxIdTextField(gridPane, ++rowIndex, Res.get("tradeDetailsWindow.disputedPayoutTxId"),
                    arbitrationManager.findOwnDispute(trade.getId()).get().getDisputePayoutTxId());
        } else if (trade.getPayoutTxId() != null && !trade.getPayoutTxId().isBlank()) {
            addLabelTxIdTextField(gridPane, ++rowIndex, Res.get("shared.payoutTxId"),
                    trade.getPayoutTxId());
        }

        if (trade.hasFailed()) {
            addSeparator(gridPane, ++rowIndex);
            textArea = addConfirmationLabelTextArea(gridPane, ++rowIndex, Res.get("shared.errorMessage"), "", 0).second;
            textArea.setText(trade.getErrorMessage());
            textArea.setEditable(false);
            //TODO paint red

            IntegerProperty count = new SimpleIntegerProperty(20);
            int rowHeight = 10;
            textArea.prefHeightProperty().bindBidirectional(count);
            changeListener = (ov, old, newVal) -> {
                if (newVal.intValue() > rowHeight)
                    count.setValue(count.get() + newVal.intValue() + 10);
            };
            textArea.scrollTopProperty().addListener(changeListener);
            textArea.setScrollTop(30);

            addSeparator(gridPane, ++rowIndex);
            addConfirmationLabelTextField(gridPane, ++rowIndex, Res.get("tradeDetailsWindow.tradePhase"), trade.getPhase().name());
        }

        Tuple3<Button, Button, HBox> tuple = add2ButtonsWithBox(gridPane, ++rowIndex,
                Res.get("tradeDetailsWindow.detailData"), Res.get("shared.close"), 15, false);
        Button viewContractButton = tuple.first;
        viewContractButton.setMaxWidth(Region.USE_COMPUTED_SIZE);
        Button closeButton = tuple.second;
        closeButton.setMaxWidth(Region.USE_COMPUTED_SIZE);
        HBox hBox = tuple.third;
        GridPane.setColumnSpan(hBox, 2);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        hBox.getChildren().add(0, spacer);

        String buyerWitnessHash = trade.getBuyer().getAccountAgeWitness() == null ? "null" : Utilities.bytesAsHexString(trade.getBuyer().getAccountAgeWitness().getHash());
        String buyerPubKeyRingHash = Utilities.bytesAsHexString(trade.getBuyer().getPubKeyRing().getSignaturePubKeyBytes());
        String sellerWitnessHash = trade.getSeller().getAccountAgeWitness() == null ? "null" : Utilities.bytesAsHexString(trade.getSeller().getAccountAgeWitness().getHash());
        String sellerPubKeyRingHash = Utilities.bytesAsHexString(trade.getSeller().getPubKeyRing().getSignaturePubKeyBytes());

        viewContractButton.setOnAction(e -> {
            TextArea textArea = new HavenoTextArea();
            textArea.setText(trade.getContractAsJson());
            String data = "Trade state: " + trade.getState();
            data += "\nTrade payout state: " + trade.getPayoutState();
            data += "\nTrade dispute state: " + trade.getDisputeState();
            data += "\n\nContract as json:\n";
            data += trade.getContractAsJson();
            data += "\n\nOther detail data:";
            if (!trade.isDepositsPublished()) {
                data += "\n\n" + (trade.getMaker() == trade.getBuyer() ? "Buyer" : "Seller") + " as maker reserve tx hex: " + trade.getMaker().getReserveTxHex();
                data += "\n\n" + (trade.getTaker() == trade.getBuyer() ? "Buyer" : "Seller") + " as taker reserve tx hex: " + trade.getTaker().getReserveTxHex();
            }
            if (offer.isTraditionalOffer()) {
                data += "\n\nBuyers witness hash,pub key ring hash: " + buyerWitnessHash + "," + buyerPubKeyRingHash;
                data += "\nBuyers account age: " + buyersAccountAge;
                data += "\nSellers witness hash,pub key ring hash: " + sellerWitnessHash + "," + sellerPubKeyRingHash;
                data += "\nSellers account age: " + sellersAccountAge;
            }

            // TODO (woodser): include maker and taker deposit tx hex in contract?
//              if (depositTx != null) {
//                  String depositTxAsHex = Utils.HEX.encode(depositTx.bitcoinSerialize(true));
//                  data += "\n\nRaw deposit transaction as hex:\n" + depositTxAsHex;
//              }


            data += "\n\nSelected arbitrator: " + trade.getArbitrator().getNodeAddress();

            textArea.setText(data);
            textArea.setPrefHeight(50);
            textArea.setEditable(false);
            textArea.setWrapText(true);
            textArea.setPrefSize(800, 600);

            Scene viewContractScene = new Scene(textArea);
            Stage viewContractStage = new Stage();
            viewContractStage.setTitle(Res.get("shared.contract.title", trade.getShortId()));
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

        closeButton.setOnAction(e -> {
            closeHandlerOptional.ifPresent(Runnable::run);
            hide();
        });
    }
}
