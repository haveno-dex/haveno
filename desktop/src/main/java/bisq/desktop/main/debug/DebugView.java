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
import bisq.core.offer.placeoffer.tasks.MakerReservesOfferFunds;
import bisq.core.offer.placeoffer.tasks.ValidateOffer;
import bisq.core.trade.protocol.tasks.ApplyFilter;
import bisq.core.trade.protocol.tasks.BuyerPreparesPaymentSentMessage;
import bisq.core.trade.protocol.tasks.BuyerProcessesPaymentReceivedMessage;
import bisq.core.trade.protocol.tasks.BuyerSendsPaymentSentMessage;
import bisq.core.trade.protocol.tasks.BuyerSetupPayoutTxListener;
import bisq.core.trade.protocol.tasks.MakerSetsLockTime;
import bisq.core.trade.protocol.tasks.MaybeRemoveOpenOffer;
import bisq.core.trade.protocol.tasks.SellerPreparesPaymentReceivedMessage;
import bisq.core.trade.protocol.tasks.SellerProcessesPaymentSentMessage;
import bisq.core.trade.protocol.tasks.SellerPublishesDepositTx;
import bisq.core.trade.protocol.tasks.SellerPublishesTradeStatistics;
import bisq.core.trade.protocol.tasks.SellerSendsPaymentReceivedMessage;
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
                        MakerReservesOfferFunds.class,
                        AddToOfferBook.class)
                ));


        addGroup("SellerAsTakerProtocol",
                FXCollections.observableArrayList(Arrays.asList(
                        ApplyFilter.class,
                        TakerVerifyMakerFeePayment.class,

                        ApplyFilter.class,
                        VerifyPeersAccountAgeWitness.class,

                        //SellerSendsDepositTxAndDelayedPayoutTxMessage.class,
                        SellerPublishesDepositTx.class,
                        SellerPublishesTradeStatistics.class,

                        SellerProcessesPaymentSentMessage.class,
                        ApplyFilter.class,
                        TakerVerifyMakerFeePayment.class,

                        ApplyFilter.class,
                        TakerVerifyMakerFeePayment.class,
                        SellerPreparesPaymentReceivedMessage.class,
                        //SellerBroadcastPayoutTx.class, // TODO (woodser): removed from main pipeline; debug view?
                        SellerSendsPaymentReceivedMessage.class

                        )
                ));
        addGroup("BuyerAsMakerProtocol",
                FXCollections.observableArrayList(Arrays.asList(
                        ApplyFilter.class,
                        VerifyPeersAccountAgeWitness.class,
                        MakerSetsLockTime.class,

                        MaybeRemoveOpenOffer.class,

                        ApplyFilter.class,
                        BuyerPreparesPaymentSentMessage.class,
                        BuyerSetupPayoutTxListener.class,
                        BuyerSendsPaymentSentMessage.class,

                        BuyerProcessesPaymentReceivedMessage.class
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
                        BuyerPreparesPaymentSentMessage.class,
                        BuyerSetupPayoutTxListener.class,
                        BuyerSendsPaymentSentMessage.class,

                        BuyerProcessesPaymentReceivedMessage.class)
                ));
        addGroup("SellerAsMakerProtocol",
                FXCollections.observableArrayList(Arrays.asList(
                        ApplyFilter.class,
                        VerifyPeersAccountAgeWitness.class,
                        MakerSetsLockTime.class,

                        //SellerAsMakerProcessDepositTxMessage.class,
                        MaybeRemoveOpenOffer.class,

                        //SellerSendsDepositTxAndDelayedPayoutTxMessage.class,
                        SellerPublishesDepositTx.class,
                        SellerPublishesTradeStatistics.class,

                        SellerProcessesPaymentSentMessage.class,
                        ApplyFilter.class,

                        ApplyFilter.class,
                        SellerPreparesPaymentReceivedMessage.class,
                        //SellerBroadcastPayoutTx.class, // TODO (woodser): removed from main pipeline; debug view?
                        SellerSendsPaymentReceivedMessage.class
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

