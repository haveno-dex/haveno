package haveno.desktop;

import com.jfoenix.controls.JFXBadge;
import com.jfoenix.controls.JFXSnackbar;
import haveno.common.util.Tuple3;
import haveno.core.locale.CryptoCurrency;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.Res;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.components.FundsTextField;
import haveno.desktop.components.InfoInputTextField;
import haveno.desktop.components.InputTextField;
import haveno.desktop.components.TitledGroupBg;
import haveno.desktop.util.FormBuilder;
import haveno.desktop.util.Layout;
import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.Locale;

import static haveno.desktop.util.FormBuilder.addFundsTextfield;
import static haveno.desktop.util.FormBuilder.addTopLabelInputTextFieldSlideToggleButton;

public class ComponentsDemo extends Application {
    private JFXSnackbar bar;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        final CryptoCurrency xmr = new CryptoCurrency("XMR", "monero");
        GlobalSettings.setDefaultTradeCurrency(xmr);
        GlobalSettings.setLocale(Locale.US);
        Res.setup();

        StackPane stackPane = new StackPane();

        //MainView.rootContainer = stackPane;

        bar = new JFXSnackbar(stackPane);
        bar.setPrefWidth(450);

        GridPane gridPane = new GridPane();
        gridPane.getStyleClass().add("content-pane");
        gridPane.setVgap(20);

        int rowIndex = 0;

        final TitledGroupBg helloGroup = FormBuilder.addTitledGroupBg(gridPane, rowIndex++, 4, "Hello Group", 20);

        final Button button = FormBuilder.addButton(gridPane, rowIndex++, "Hello World");
        button.setDisable(true);
        button.getStyleClass().add("action-button");

        Label label = new Label("PORTFOLIO");
        label.setStyle("-fx-background-color: green");

        final JFXBadge jfxBadge = new JFXBadge(label);
        jfxBadge.setPosition(Pos.TOP_RIGHT);
        jfxBadge.setPrefHeight(100);
        jfxBadge.setMaxWidth(110);

        final Button buttonEnabled = FormBuilder.addButton(gridPane, rowIndex++, "Hello World");
        buttonEnabled.setOnMouseClicked((click) -> {
            //bar.enqueue(new JFXSnackbar.SnackbarEvent(Res.get("notification.walletUpdate.msg", "0.345 XMR"), "CLOSE", 3000, true, b -> bar.close()));
//                new Popup<>().headLine(Res.get("popup.roundedFiatValues.headline"))
//                    .message(Res.get("popup.roundedFiatValues.msg", "XMR"))
//                    .show();
//            new Notification().headLine(Res.get("notification.tradeCompleted.headline"))
//                    .notification(Res.get("notification.tradeCompleted.msg"))
//                    .autoClose()
//                    .show();
            jfxBadge.refreshBadge();
        });
        buttonEnabled.getStyleClass().add("action-button");

        InputTextField inputTextField = FormBuilder.addInputTextField(gridPane, rowIndex++, "Enter something title");
        inputTextField.setLabelFloat(true);
        inputTextField.getStyleClass().add("label-float");
        inputTextField.setText("Hello");
        inputTextField.setPromptText("Enter something");

        final Tuple3<HBox, InfoInputTextField, Label> editableValueBox = FormBuilder.getEditableValueBoxWithInfo("Please Enter!");
        final HBox box = editableValueBox.first;
//        box.setMaxWidth(243);
        //box.setMaxWidth(200);
        editableValueBox.third.setText("BTC");
        editableValueBox.second.setContentForInfoPopOver(new Label("Hello World!"));
        GridPane.setRowIndex(box, rowIndex++);
        GridPane.setColumnIndex(box, 1);
        gridPane.getChildren().add(box);

        final FundsTextField fundsTextField = addFundsTextfield(gridPane, rowIndex++,
                "Total Needed", Layout.FIRST_ROW_AND_GROUP_DISTANCE);
        //fundsTextField.setText("Hello World");

        final ComboBox<String> comboBox = FormBuilder.<String>addTopLabelComboBox(gridPane, rowIndex++, "Numbers", "Select currency", 0).second;
        ObservableList list = FXCollections.observableArrayList();
        list.addAll("EUR", "USD", "GBP");
        comboBox.setItems(list);
        /*comboBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                this.setVisible(item != null || !empty);

                if (item != null && !empty) {
                    AnchorPane pane = new AnchorPane();
                    Label currency = new AutoTooltipLabel(item + " - US Dollar");
                    currency.getStyleClass().add("currency-label-selected");
                    AnchorPane.setLeftAnchor(currency, 0.0);
                    Label numberOfOffers = new AutoTooltipLabel("21 offers");
                    numberOfOffers.getStyleClass().add("offer-label-small");
                    AnchorPane.setRightAnchor(numberOfOffers, 0.0);
                    AnchorPane.setBottomAnchor(numberOfOffers, 0.0);
                    pane.getChildren().addAll(currency, numberOfOffers);
                    setGraphic(pane);
                    setText("");
                } else {
                    setGraphic(null);
                    setText("");
                }
            }
        });*/
        comboBox.setCellFactory(p -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);

                if (item != null && !empty) {
                    HBox box = new HBox();
                    box.setSpacing(20);
                    Label currencyType = new AutoTooltipLabel("Crypto");
                    currencyType.getStyleClass().add("currency-label-small");
                    Label currency = new AutoTooltipLabel(item);
                    currency.getStyleClass().add("currency-label");
                    Label offers = new AutoTooltipLabel("Euro (21 offers)");
                    offers.getStyleClass().add("currency-label");
                    box.getChildren().addAll(currencyType, currency, offers);

                    setGraphic(box);
                } else {
                    setGraphic(null);
                }
            }
        });

        Label xLabel = new Label();
//        xLabel.getStyleClass().add("opaque-icon");
        //final Text icon = getIconForLabel(MaterialDesignIcon.CLOSE, "2em", xLabel);
        //final Text icon = getIconForLabel(MaterialDesignIcon.CLOSE, "2em", xLabel);
        /*icon.getStyleClass().add("opaque-icon");
        GridPane.setRowIndex(xLabel, rowIndex++);
        GridPane.setColumnIndex(xLabel, 0);
        gridPane.getChildren().add(xLabel);*/

//        FlowPane iconsPane = new FlowPane(3,3);
//        for (MaterialDesignIcon ic : MaterialDesignIcon.values()) {
//            iconsPane.getChildren().add(new MaterialDesignIconView(ic, "3em"));
//        }
//
//        GridPane.setRowIndex(iconsPane, rowIndex++);
//        gridPane.getChildren().add(iconsPane);

        jfxBadge.setText("2");
//        jfxBadge.setEnabled(false);
        GridPane.setRowIndex(jfxBadge, rowIndex++);
        GridPane.setColumnIndex(jfxBadge, 0);
        gridPane.getChildren().add(jfxBadge);

        Tuple3<Label, InputTextField, ToggleButton> tuple = addTopLabelInputTextFieldSlideToggleButton(gridPane, ++rowIndex,
                Res.get("setting.preferences.txFee"), Res.get("setting.preferences.useCustomValue"));
        tuple.second.setDisable(true);

        FormBuilder.addInputTextField(gridPane, ++rowIndex, Res.get("setting.preferences.deviation"));

        final ScrollPane scrollPane = new ScrollPane();
        scrollPane.setContent(gridPane);
        stackPane.getChildren().add(scrollPane);

        Scene scene = new Scene(stackPane, 1000, 650);
        scene.getStylesheets().setAll(
                "/haveno/desktop/haveno.css",
                "/haveno/desktop/images.css");
        primaryStage.setScene(scene);
        primaryStage.show();
    }
}
