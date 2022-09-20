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

package bisq.desktop.main.debug;

import bisq.desktop.common.view.FxmlView;
import bisq.desktop.common.view.InitializableView;
import bisq.desktop.components.TitledGroupBg;

import bisq.core.offer.availability.tasks.ProcessOfferAvailabilityResponse;
import bisq.core.offer.availability.tasks.SendOfferAvailabilityRequest;
import bisq.core.offer.placeoffer.tasks.AddToOfferBook;
import bisq.core.offer.placeoffer.tasks.MakerReserveOfferFunds;
import bisq.core.offer.placeoffer.tasks.ValidateOffer;
import bisq.core.trade.protocol.tasks.ApplyFilter;
import bisq.core.trade.protocol.tasks.BuyerPreparePaymentSentMessage;
import bisq.core.trade.protocol.tasks.BuyerProcessPaymentReceivedMessage;
import bisq.core.trade.protocol.tasks.BuyerSendPaymentSentMessage;
import bisq.core.trade.protocol.tasks.BuyerSetupPayoutTxListener;
import bisq.core.trade.protocol.tasks.MakerSetLockTime;
import bisq.core.trade.protocol.tasks.MakerRemoveOpenOffer;
import bisq.core.trade.protocol.tasks.SellerPreparePaymentReceivedMessage;
import bisq.core.trade.protocol.tasks.SellerProcessPaymentSentMessage;
import bisq.core.trade.protocol.tasks.SellerPublishDepositTx;
import bisq.core.trade.protocol.tasks.SellerPublishTradeStatistics;
import bisq.core.trade.protocol.tasks.SellerSendPaymentReceivedMessage;
import bisq.core.trade.protocol.tasks.TakerVerifyMakerFeePayment;
import bisq.core.trade.protocol.tasks.VerifyPeersAccountAgeWitness;
import bisq.common.taskrunner.Task;
import bisq.common.util.Tuple2;

import javax.inject.Inject;

import javafx.fxml.FXML;

import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import javafx.util.StringConverter;

import java.util.Arrays;

import static bisq.desktop.util.FormBuilder.addTopLabelComboBox;

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
                        MakerReserveOfferFunds.class,
                        AddToOfferBook.class)
                ));


        addGroup("SellerAsTakerProtocol",
                FXCollections.observableArrayList(Arrays.asList(
                        ApplyFilter.class,
                        TakerVerifyMakerFeePayment.class,

                        ApplyFilter.class,
                        VerifyPeersAccountAgeWitness.class,

                        //SellerSendsDepositTxAndDelayedPayoutTxMessage.class,
                        SellerPublishDepositTx.class,
                        SellerPublishTradeStatistics.class,

                        SellerProcessPaymentSentMessage.class,
                        ApplyFilter.class,
                        TakerVerifyMakerFeePayment.class,

                        ApplyFilter.class,
                        TakerVerifyMakerFeePayment.class,
                        SellerPreparePaymentReceivedMessage.class,
                        //SellerBroadcastPayoutTx.class, // TODO (woodser): removed from main pipeline; debug view?
                        SellerSendPaymentReceivedMessage.class

                        )
                ));
        addGroup("BuyerAsMakerProtocol",
                FXCollections.observableArrayList(Arrays.asList(
                        ApplyFilter.class,
                        VerifyPeersAccountAgeWitness.class,
                        MakerSetLockTime.class,

                        MakerRemoveOpenOffer.class,

                        ApplyFilter.class,
                        BuyerPreparePaymentSentMessage.class,
                        BuyerSetupPayoutTxListener.class,
                        BuyerSendPaymentSentMessage.class,

                        BuyerProcessPaymentReceivedMessage.class
                        )
                ));


        addGroup("BuyerAsTakerProtocol",
                FXCollections.observableArrayList(Arrays.asList(
                        ApplyFilter.class,
                        TakerVerifyMakerFeePayment.class,

                        ApplyFilter.class,
                        VerifyPeersAccountAgeWitness.class,

                        ApplyFilter.class,
                        TakerVerifyMakerFeePayment.class,
                        BuyerPreparePaymentSentMessage.class,
                        BuyerSetupPayoutTxListener.class,
                        BuyerSendPaymentSentMessage.class,

                        BuyerProcessPaymentReceivedMessage.class)
                ));
        addGroup("SellerAsMakerProtocol",
                FXCollections.observableArrayList(Arrays.asList(
                        ApplyFilter.class,
                        VerifyPeersAccountAgeWitness.class,
                        MakerSetLockTime.class,

                        //SellerAsMakerProcessDepositTxMessage.class,
                        MakerRemoveOpenOffer.class,

                        //SellerSendsDepositTxAndDelayedPayoutTxMessage.class,
                        SellerPublishDepositTx.class,
                        SellerPublishTradeStatistics.class,

                        SellerProcessPaymentSentMessage.class,
                        ApplyFilter.class,

                        ApplyFilter.class,
                        SellerPreparePaymentReceivedMessage.class,
                        //SellerBroadcastPayoutTx.class, // TODO (woodser): removed from main pipeline; debug view?
                        SellerSendPaymentReceivedMessage.class
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

