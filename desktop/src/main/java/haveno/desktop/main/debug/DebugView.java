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

package haveno.desktop.main.debug;

import com.google.inject.Inject;
import haveno.common.taskrunner.Task;
import haveno.common.util.Tuple2;
import haveno.core.offer.availability.tasks.ProcessOfferAvailabilityResponse;
import haveno.core.offer.availability.tasks.SendOfferAvailabilityRequest;
import haveno.core.offer.placeoffer.tasks.AddToOfferBook;
import haveno.core.offer.placeoffer.tasks.MakerReserveOfferFunds;
import haveno.core.offer.placeoffer.tasks.ValidateOffer;
import haveno.core.trade.protocol.tasks.ApplyFilter;
import haveno.core.trade.protocol.tasks.BuyerPreparePaymentSentMessage;
import haveno.core.trade.protocol.tasks.BuyerSendPaymentSentMessage;
import haveno.core.trade.protocol.tasks.MakerSetLockTime;
import haveno.core.trade.protocol.tasks.ProcessPaymentReceivedMessage;
import haveno.core.trade.protocol.tasks.ProcessPaymentSentMessage;
import haveno.core.trade.protocol.tasks.RemoveOffer;
import haveno.core.trade.protocol.tasks.SellerPreparePaymentReceivedMessage;
import haveno.core.trade.protocol.tasks.SellerPublishDepositTx;
import haveno.core.trade.protocol.tasks.SellerPublishTradeStatistics;
import haveno.core.trade.protocol.tasks.SellerSendPaymentReceivedMessageToBuyer;
import haveno.core.trade.protocol.tasks.VerifyPeersAccountAgeWitness;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.common.view.InitializableView;
import haveno.desktop.components.TitledGroupBg;
import static haveno.desktop.util.FormBuilder.addTopLabelComboBox;
import java.util.Arrays;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.util.StringConverter;

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

                        ApplyFilter.class,
                        VerifyPeersAccountAgeWitness.class,

                        //SellerSendsDepositTxAndDelayedPayoutTxMessage.class,
                        SellerPublishDepositTx.class,
                        SellerPublishTradeStatistics.class,

                        ProcessPaymentSentMessage.class,
                        ApplyFilter.class,

                        ApplyFilter.class,
                        SellerPreparePaymentReceivedMessage.class,
                        //SellerBroadcastPayoutTx.class, // TODO (woodser): removed from main pipeline; debug view?
                        SellerSendPaymentReceivedMessageToBuyer.class

                        )
                ));
        addGroup("BuyerAsMakerProtocol",
                FXCollections.observableArrayList(Arrays.asList(
                        ApplyFilter.class,
                        VerifyPeersAccountAgeWitness.class,
                        MakerSetLockTime.class,

                        RemoveOffer.class,

                        ApplyFilter.class,
                        BuyerPreparePaymentSentMessage.class,
                        BuyerSendPaymentSentMessage.class,

                        ProcessPaymentReceivedMessage.class
                        )
                ));


        addGroup("BuyerAsTakerProtocol",
                FXCollections.observableArrayList(Arrays.asList(
                        ApplyFilter.class,

                        ApplyFilter.class,
                        VerifyPeersAccountAgeWitness.class,

                        ApplyFilter.class,
                        BuyerPreparePaymentSentMessage.class,
                        BuyerSendPaymentSentMessage.class,

                        ProcessPaymentReceivedMessage.class)
                ));
        addGroup("SellerAsMakerProtocol",
                FXCollections.observableArrayList(Arrays.asList(
                        ApplyFilter.class,
                        VerifyPeersAccountAgeWitness.class,
                        MakerSetLockTime.class,

                        //SellerAsMakerProcessDepositTxMessage.class,
                        RemoveOffer.class,

                        //SellerSendsDepositTxAndDelayedPayoutTxMessage.class,
                        SellerPublishDepositTx.class,
                        SellerPublishTradeStatistics.class,

                        ProcessPaymentSentMessage.class,
                        ApplyFilter.class,

                        ApplyFilter.class,
                        SellerPreparePaymentReceivedMessage.class,
                        //SellerBroadcastPayoutTx.class, // TODO (woodser): removed from main pipeline; debug view?
                        SellerSendPaymentReceivedMessageToBuyer.class
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

