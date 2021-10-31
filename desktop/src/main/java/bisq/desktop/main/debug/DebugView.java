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

package haveno.desktop.main.debug;

import haveno.desktop.common.view.FxmlView;
import haveno.desktop.common.view.InitializableView;
import haveno.desktop.components.TitledGroupBg;

import haveno.core.offer.availability.tasks.ProcessOfferAvailabilityResponse;
import haveno.core.offer.availability.tasks.SendOfferAvailabilityRequest;
import haveno.core.offer.placeoffer.tasks.AddToOfferBook;
import haveno.core.offer.placeoffer.tasks.MakerReservesTradeFunds;
import haveno.core.offer.placeoffer.tasks.ValidateOffer;
import haveno.core.trade.protocol.tasks.ApplyFilter;
import haveno.core.trade.protocol.tasks.VerifyPeersAccountAgeWitness;
import haveno.core.trade.protocol.tasks.buyer.BuyerCreateAndSignPayoutTx;
import haveno.core.trade.protocol.tasks.buyer.BuyerProcessDelayedPayoutTxSignatureRequest;
import haveno.core.trade.protocol.tasks.buyer.BuyerProcessDepositTxAndDelayedPayoutTxMessage;
import haveno.core.trade.protocol.tasks.buyer.BuyerProcessPayoutTxPublishedMessage;
import haveno.core.trade.protocol.tasks.buyer.BuyerSendCounterCurrencyTransferStartedMessage;
import haveno.core.trade.protocol.tasks.buyer.BuyerSendsDelayedPayoutTxSignatureResponse;
import haveno.core.trade.protocol.tasks.buyer.BuyerSetupPayoutTxListener;
import haveno.core.trade.protocol.tasks.buyer.BuyerSignsDelayedPayoutTx;
import haveno.core.trade.protocol.tasks.buyer.BuyerVerifiesFinalDelayedPayoutTx;
import haveno.core.trade.protocol.tasks.buyer.BuyerVerifiesPreparedDelayedPayoutTx;
import haveno.core.trade.protocol.tasks.buyer_as_maker.BuyerAsMakerCreatesAndSignsDepositTx;
import haveno.core.trade.protocol.tasks.buyer_as_maker.BuyerAsMakerSendsInputsForDepositTxResponse;
import haveno.core.trade.protocol.tasks.buyer_as_taker.BuyerAsTakerCreatesDepositTxInputs;
import haveno.core.trade.protocol.tasks.buyer_as_taker.BuyerAsTakerSendsDepositTxMessage;
import haveno.core.trade.protocol.tasks.buyer_as_taker.BuyerAsTakerSignsDepositTx;
import haveno.core.trade.protocol.tasks.maker.MakerRemovesOpenOffer;
import haveno.core.trade.protocol.tasks.maker.MakerSetsLockTime;
import haveno.core.trade.protocol.tasks.maker.MakerVerifyTakerFeePayment;
import haveno.core.trade.protocol.tasks.seller.SellerCreatesDelayedPayoutTx;
import haveno.core.trade.protocol.tasks.seller.SellerFinalizesDelayedPayoutTx;
import haveno.core.trade.protocol.tasks.seller.SellerProcessCounterCurrencyTransferStartedMessage;
import haveno.core.trade.protocol.tasks.seller.SellerProcessDelayedPayoutTxSignatureResponse;
import haveno.core.trade.protocol.tasks.seller.SellerPublishesDepositTx;
import haveno.core.trade.protocol.tasks.seller.SellerPublishesTradeStatistics;
import haveno.core.trade.protocol.tasks.seller.SellerSendDelayedPayoutTxSignatureRequest;
import haveno.core.trade.protocol.tasks.seller.SellerSendPayoutTxPublishedMessage;
import haveno.core.trade.protocol.tasks.seller.SellerSignAndPublishPayoutTx;
import haveno.core.trade.protocol.tasks.seller.SellerSignsDelayedPayoutTx;
import haveno.core.trade.protocol.tasks.seller_as_maker.SellerAsMakerCreatesUnsignedDepositTx;
import haveno.core.trade.protocol.tasks.seller_as_maker.SellerAsMakerFinalizesDepositTx;
import haveno.core.trade.protocol.tasks.seller_as_maker.SellerAsMakerSendsInputsForDepositTxResponse;
import haveno.core.trade.protocol.tasks.seller_as_taker.SellerAsTakerCreatesDepositTxInputs;
import haveno.core.trade.protocol.tasks.seller_as_taker.SellerAsTakerSignsDepositTx;
import haveno.core.trade.protocol.tasks.taker.TakerCreateFeeTx;
import haveno.core.trade.protocol.tasks.taker.TakerProcessesInputsForDepositTxResponse;
import haveno.core.trade.protocol.tasks.taker.TakerPublishFeeTx;
import haveno.core.trade.protocol.tasks.taker.TakerVerifyMakerFeePayment;

import haveno.common.taskrunner.Task;
import haveno.common.util.Tuple2;

import javax.inject.Inject;

import javafx.fxml.FXML;

import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import javafx.util.StringConverter;

import java.util.Arrays;

import static haveno.desktop.util.FormBuilder.addTopLabelComboBox;

// Not maintained anymore with new trade protocol, but leave it...If used needs to be adopted to current protocol.
@FxmlView
public class DebugView extends InitializableView<GridPane, Void> {

    @FXML
    TitledGroupBg titledGroupBg;
    private int rowIndex = 0;

    @Inject
    public DebugView() {
    }

    @Override
    public void initialize() {

        addGroup("OfferAvailabilityProtocol",
                FXCollections.observableArrayList(Arrays.asList(
                        SendOfferAvailabilityRequest.class,
                        ProcessOfferAvailabilityResponse.class)
                ));

        addGroup("PlaceOfferProtocol",
                FXCollections.observableArrayList(Arrays.asList(
                        ValidateOffer.class,
                        MakerReservesTradeFunds.class,
                        AddToOfferBook.class)
                ));


        addGroup("SellerAsTakerProtocol",
                FXCollections.observableArrayList(Arrays.asList(
                        ApplyFilter.class,
                        TakerVerifyMakerFeePayment.class,
                        TakerCreateFeeTx.class, // TODO (woodser): rename to TakerCreateFeeTx
                        SellerAsTakerCreatesDepositTxInputs.class,

                        TakerProcessesInputsForDepositTxResponse.class,
                        ApplyFilter.class,
                        VerifyPeersAccountAgeWitness.class,
                        TakerPublishFeeTx.class,
                        SellerAsTakerSignsDepositTx.class,
                        SellerCreatesDelayedPayoutTx.class,
                        SellerSendDelayedPayoutTxSignatureRequest.class,

                        SellerProcessDelayedPayoutTxSignatureResponse.class,
                        SellerSignsDelayedPayoutTx.class,
                        SellerFinalizesDelayedPayoutTx.class,
                        //SellerSendsDepositTxAndDelayedPayoutTxMessage.class,
                        SellerPublishesDepositTx.class,
                        SellerPublishesTradeStatistics.class,

                        SellerProcessCounterCurrencyTransferStartedMessage.class,
                        ApplyFilter.class,
                        TakerVerifyMakerFeePayment.class,

                        ApplyFilter.class,
                        TakerVerifyMakerFeePayment.class,
                        SellerSignAndPublishPayoutTx.class,
                        //SellerBroadcastPayoutTx.class, // TODO (woodser): removed from main pipeline; debug view?
                        SellerSendPayoutTxPublishedMessage.class

                        )
                ));
        addGroup("BuyerAsMakerProtocol",
                FXCollections.observableArrayList(Arrays.asList(
                        ApplyFilter.class,
                        VerifyPeersAccountAgeWitness.class,
                        MakerVerifyTakerFeePayment.class,
                        MakerSetsLockTime.class,
                        BuyerAsMakerCreatesAndSignsDepositTx.class,
                        BuyerAsMakerSendsInputsForDepositTxResponse.class,

                        BuyerProcessDelayedPayoutTxSignatureRequest.class,
                        MakerRemovesOpenOffer.class,
                        BuyerVerifiesPreparedDelayedPayoutTx.class,
                        BuyerSignsDelayedPayoutTx.class,
                        BuyerSendsDelayedPayoutTxSignatureResponse.class,

                        BuyerProcessDepositTxAndDelayedPayoutTxMessage.class,
                        BuyerVerifiesFinalDelayedPayoutTx.class,

                        ApplyFilter.class,
                        MakerVerifyTakerFeePayment.class,
                        BuyerCreateAndSignPayoutTx.class,
                        BuyerSetupPayoutTxListener.class,
                        BuyerSendCounterCurrencyTransferStartedMessage.class,

                        BuyerProcessPayoutTxPublishedMessage.class
                        )
                ));


        addGroup("BuyerAsTakerProtocol",
                FXCollections.observableArrayList(Arrays.asList(
                        ApplyFilter.class,
                        TakerVerifyMakerFeePayment.class,
                        TakerCreateFeeTx.class,
                        BuyerAsTakerCreatesDepositTxInputs.class,

                        TakerProcessesInputsForDepositTxResponse.class,
                        ApplyFilter.class,
                        VerifyPeersAccountAgeWitness.class,
                        TakerPublishFeeTx.class,
                        BuyerAsTakerSignsDepositTx.class,
                        BuyerAsTakerSendsDepositTxMessage.class,

                        BuyerProcessDelayedPayoutTxSignatureRequest.class,
                        BuyerVerifiesPreparedDelayedPayoutTx.class,
                        BuyerSignsDelayedPayoutTx.class,
                        BuyerSendsDelayedPayoutTxSignatureResponse.class,

                        BuyerProcessDepositTxAndDelayedPayoutTxMessage.class,
                        BuyerVerifiesFinalDelayedPayoutTx.class,

                        ApplyFilter.class,
                        TakerVerifyMakerFeePayment.class,
                        BuyerCreateAndSignPayoutTx.class,
                        BuyerSetupPayoutTxListener.class,
                        BuyerSendCounterCurrencyTransferStartedMessage.class,

                        BuyerProcessPayoutTxPublishedMessage.class)
                ));
        addGroup("SellerAsMakerProtocol",
                FXCollections.observableArrayList(Arrays.asList(
                        ApplyFilter.class,
                        VerifyPeersAccountAgeWitness.class,
                        MakerVerifyTakerFeePayment.class,
                        MakerSetsLockTime.class,
                        SellerAsMakerCreatesUnsignedDepositTx.class,
                        SellerAsMakerSendsInputsForDepositTxResponse.class,

                        //SellerAsMakerProcessDepositTxMessage.class,
                        MakerRemovesOpenOffer.class,
                        SellerAsMakerFinalizesDepositTx.class,
                        SellerCreatesDelayedPayoutTx.class,
                        SellerSendDelayedPayoutTxSignatureRequest.class,

                        SellerProcessDelayedPayoutTxSignatureResponse.class,
                        SellerSignsDelayedPayoutTx.class,
                        SellerFinalizesDelayedPayoutTx.class,
                        //SellerSendsDepositTxAndDelayedPayoutTxMessage.class,
                        SellerPublishesDepositTx.class,
                        SellerPublishesTradeStatistics.class,

                        SellerProcessCounterCurrencyTransferStartedMessage.class,
                        ApplyFilter.class,
                        MakerVerifyTakerFeePayment.class,

                        ApplyFilter.class,
                        MakerVerifyTakerFeePayment.class,
                        SellerSignAndPublishPayoutTx.class,
                        //SellerBroadcastPayoutTx.class, // TODO (woodser): removed from main pipeline; debug view?
                        SellerSendPayoutTxPublishedMessage.class
                        )
                ));
    }

    private void addGroup(String title, ObservableList<Class<? extends Task>> list) {
        final Tuple2<Label, ComboBox<Class<? extends Task>>> selectTaskToIntercept =
                addTopLabelComboBox(root, ++rowIndex, title, "Select task to intercept", 15);
        ComboBox<Class<? extends Task>> comboBox = selectTaskToIntercept.second;
        comboBox.setVisibleRowCount(list.size());
        comboBox.setItems(list);
        comboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(Class<? extends Task> item) {
                return item.getSimpleName();
            }

            @Override
            public Class<? extends Task> fromString(String s) {
                return null;
            }
        });
        comboBox.setOnAction(event -> Task.taskToIntercept = comboBox.getSelectionModel().getSelectedItem());
    }
}

