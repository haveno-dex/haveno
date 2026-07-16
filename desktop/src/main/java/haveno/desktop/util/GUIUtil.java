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

package haveno.desktop.util;

import com.google.common.base.Charsets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIconView;
import haveno.common.UserThread;
import haveno.common.config.Config;
import haveno.common.file.CorruptedStorageFileHandler;
import haveno.common.persistence.PersistenceManager;
import haveno.common.proto.persistable.PersistableEnvelope;
import haveno.common.proto.persistable.PersistenceProtoResolver;
import haveno.common.util.Tuple2;
import haveno.common.util.Tuple3;
import haveno.common.util.Utilities;
import haveno.core.account.witness.AccountAgeWitness;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.api.XmrConnectionService;
import haveno.core.locale.Country;
import haveno.core.locale.CountryUtil;
import haveno.core.locale.CryptoCurrency;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.PaymentAccountList;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.user.DontShowAgainLookup;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.core.util.FormattingUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.Navigation;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.components.InfoAutoTooltipLabel;
import haveno.desktop.components.indicator.TxConfidenceIndicator;
import haveno.desktop.main.MainView;
import haveno.desktop.main.account.AccountView;
import haveno.desktop.main.account.content.traditionalaccounts.TraditionalAccountsView;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.network.p2p.P2PService;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Callback;
import javafx.util.StringConverter;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroUtils;
import monero.daemon.model.MoneroTx;
import monero.wallet.model.MoneroTxConfig;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.lang3.StringUtils;
import org.bitcoinj.core.Coin;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static haveno.desktop.util.FormBuilder.addTopLabelComboBoxComboBox;

@Slf4j
public class GUIUtil {
    public final static String SHOW_ALL_FLAG = "list.currency.showAll"; // Used for accessing the i18n resource
    public final static String EDIT_FLAG = "list.currency.editList"; // Used for accessing the i18n resource

    public final static String OPEN_WEB_PAGE_KEY = "warnOpenURLWhenTorEnabled";

    public final static int NUM_DECIMALS_UNIT = 0;
    public final static int NUM_DECIMALS_PRICE_LESS_PRECISE = 3;
    public final static int NUM_DECIMALS_PRECISE = 7;
    public final static int AMOUNT_DECIMALS_WITH_ZEROS = 3;
    public final static int AMOUNT_DECIMALS = 4;
    public static final double NUM_OFFERS_TRANSLATE_X = -13.0;

    public static final boolean disablePaymentUriLabel = true; // universally disable payment uri labels, allowing bigger xmr logo overlays
    private static Preferences preferences;

    public static void setPreferences(Preferences preferences) {
        GUIUtil.preferences = preferences;
    }

    public static String getUserLanguage() {
        return preferences.getUserLanguage();
    }

    public static double getScrollbarWidth(Node scrollablePane) {
        Node node = scrollablePane.lookup(".scroll-bar");
        if (node instanceof ScrollBar) {
            final ScrollBar bar = (ScrollBar) node;
            if (bar.getOrientation().equals(Orientation.VERTICAL))
                return bar.getWidth();
        }
        return 0;
    }

    public static void focusWhenAddedToScene(Node node) {
        node.sceneProperty().addListener((observable, oldValue, newValue) -> {
            if (null != newValue) {
                node.requestFocus();
            }
        });
    }

    public static void exportAccounts(ArrayList<PaymentAccount> accounts,
                                      String fileName,
                                      Preferences preferences,
                                      Stage stage,
                                      PersistenceProtoResolver persistenceProtoResolver,
                                      CorruptedStorageFileHandler corruptedStorageFileHandler) {
        if (!accounts.isEmpty()) {
            String directory = getDirectoryFromChooser(preferences, stage);
            if (!directory.isEmpty()) {
                PersistenceManager<PersistableEnvelope> persistenceManager = new PersistenceManager<>(new File(directory), persistenceProtoResolver, corruptedStorageFileHandler, null);
                PaymentAccountList paymentAccounts = new PaymentAccountList(accounts);
                persistenceManager.initialize(paymentAccounts, fileName, PersistenceManager.Source.PRIVATE_LOW_PRIO);
                persistenceManager.persistNow(() -> {
                    persistenceManager.shutdown();
                    new Popup().feedback(Res.get("guiUtil.accountExport.savedToPath",
                            Paths.get(directory, fileName).toAbsolutePath()))
                            .show();
                });
            }
        } else {
            new Popup().warning(Res.get("guiUtil.accountExport.noAccountSetup")).show();
        }
    }

    public static void importAccounts(User user,
                                      String fileName,
                                      Preferences preferences,
                                      Stage stage,
                                      PersistenceProtoResolver persistenceProtoResolver,
                                      CorruptedStorageFileHandler corruptedStorageFileHandler) {
        FileChooser fileChooser = new FileChooser();
        File initDir = new File(preferences.getDirectoryChooserPath());
        if (initDir.isDirectory()) {
            fileChooser.setInitialDirectory(initDir);
        }
        fileChooser.setTitle(Res.get("guiUtil.accountExport.selectPath", fileName));
        File file = fileChooser.showOpenDialog(stage.getOwner());
        if (file != null) {
            String path = file.getAbsolutePath();
            if (Paths.get(path).getFileName().toString().equals(fileName)) {
                String directory = Paths.get(path).getParent().toString();
                preferences.setDirectoryChooserPath(directory);
                PersistenceManager<PaymentAccountList> persistenceManager = new PersistenceManager<>(new File(directory), persistenceProtoResolver, corruptedStorageFileHandler, null);
                persistenceManager.readPersisted(fileName, persisted -> {
                            StringBuilder msg = new StringBuilder();
                            HashSet<PaymentAccount> paymentAccounts = new HashSet<>();
                            synchronized (persisted.getList()) {
                                persisted.getList().forEach(paymentAccount -> {
                                    String id = paymentAccount.getId();
                                    if (user.getPaymentAccount(id) == null) {
                                        paymentAccounts.add(paymentAccount);
                                        msg.append(Res.get("guiUtil.accountExport.tradingAccount", id));
                                    } else {
                                        msg.append(Res.get("guiUtil.accountImport.noImport", id));
                                    }
                                });
                            }
                            user.addImportedPaymentAccounts(paymentAccounts);
                            new Popup().feedback(Res.get("guiUtil.accountImport.imported", path, msg)).show();
                        },
                        () -> {
                            new Popup().warning(Res.get("guiUtil.accountImport.noAccountsFound", path, fileName)).show();
                        });
            } else {
                log.error("The selected file is not the expected file for import. The expected file name is: " + fileName + ".");
            }
        }
    }


    public static byte[] generateQrCodePng(String data, int width, int height) {
        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrCodeWriter.encode(data, BarcodeFormat.QR_CODE, width, height);
            ByteArrayOutputStream pngOutputStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", pngOutputStream);
            return pngOutputStream.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate QR code", e);
        }
    }

    public static <T> void exportCSV(String fileName, Function<T, String[]> headerConverter,
                                     Function<T, String[]> contentConverter, T emptyItem,
                                     List<T> list, Stage stage) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setInitialFileName(fileName);
        File file = fileChooser.showSaveDialog(stage);
        if (file != null) {
            try (OutputStreamWriter outputStreamWriter = new OutputStreamWriter(new FileOutputStream(file, false), Charsets.UTF_8);
                 CSVPrinter printer = new CSVPrinter(outputStreamWriter, CSVFormat.DEFAULT)) {
                printer.printRecord((Object[]) headerConverter.apply(emptyItem));
                for (T item : list) {
                    printer.printRecord((Object[]) contentConverter.apply(item));
                }
            } catch (RuntimeException | IOException e) {
                e.printStackTrace();
                log.error(e.getMessage());
                new Popup().error(Res.get("guiUtil.accountExport.exportFailed", e.getMessage())).show();
            }
        }
    }

    public static void exportJSON(String fileName, JsonElement data, Stage stage) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setInitialFileName(fileName);
        File file = fileChooser.showSaveDialog(stage);
        if (file != null) {
            try (OutputStreamWriter outputStreamWriter = new OutputStreamWriter(new FileOutputStream(file, false), Charsets.UTF_8)) {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                outputStreamWriter.write(gson.toJson(data));
            } catch (RuntimeException | IOException e) {
                e.printStackTrace();
                log.error(e.getMessage());
                new Popup().error(Res.get("guiUtil.accountExport.exportFailed", e.getMessage()));
            }
        }
    }

    private static String getDirectoryFromChooser(Preferences preferences, Stage stage) {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        File initDir = new File(preferences.getDirectoryChooserPath());
        if (initDir.isDirectory()) {
            directoryChooser.setInitialDirectory(initDir);
        }
        directoryChooser.setTitle(Res.get("guiUtil.accountExport.selectExportPath"));
        File dir = directoryChooser.showDialog(stage);
        if (dir != null) {
            String directory = dir.getAbsolutePath();
            preferences.setDirectoryChooserPath(directory);
            return directory;
        } else {
            return "";
        }
    }

    public static Callback<ListView<CurrencyListItem>, ListCell<CurrencyListItem>> getCurrencyListItemCellFactory(String postFixSingle,
                                                                                                                  String postFixMulti) {
        return p -> new ListCell<>() {
            @Override
            protected void updateItem(CurrencyListItem item, boolean empty) {
                super.updateItem(item, empty);

                if (item != null && !empty) {

                    String code = item.tradeCurrency.getCode();

                    boolean isCrypto = CurrencyUtil.isCryptoCurrency(code);

                    HBox box = new HBox();
                    box.setSpacing(20);
                    box.setAlignment(Pos.CENTER_LEFT);
                    Label label1 = new AutoTooltipLabel(getCurrencyType(code));
                    label1.getStyleClass().add("currency-label-small");
                    Label label2 = new AutoTooltipLabel(isCrypto ? item.tradeCurrency.getNameAndCode() : code);
                    label2.getStyleClass().add("currency-label");
                    Label label3 = new AutoTooltipLabel(isCrypto ? "" : item.tradeCurrency.getName());
                    if (!isCrypto) label3.getStyleClass().add("currency-label");
                    Label label4 = new AutoTooltipLabel();

                    boolean showOfferCount = false;

                    switch (code) {
                        case GUIUtil.SHOW_ALL_FLAG:
                            label1.setText(Res.get("shared.all"));
                            label2.setText(Res.get("list.currency.showAll"));
                            break;
                        case GUIUtil.EDIT_FLAG:
                            label1.setText(Res.get("shared.edit"));
                            label2.setText(Res.get("list.currency.editList"));
                            break;
                        default:

                            // use icon if available
                            Node currencyIcon = getCurrencyGraphic(code, CURRENCY_GRAPHIC_ROW_SIZE);
                            if (currencyIcon != null) {
                                label1.setText("");
                                label1.setGraphic(currencyIcon);
                            }

                            if (item.numTrades > 0) {
                                Label offersTarget = isCrypto ? label3 : label4;
                                HBox.setMargin(offersTarget, new Insets(0, 0, 0, NUM_OFFERS_TRANSLATE_X));
                                offersTarget.getStyleClass().add("offer-label");
                                offersTarget.setText(item.numTrades + " " + (item.numTrades == 1 ? postFixSingle : postFixMulti));
                                showOfferCount = true;
                            }
                    }

                    // append the offer pill only when present so the row ends at real content
                    box.getChildren().addAll(label1, label2);
                    if (!isCrypto) box.getChildren().add(label3);
                    if (showOfferCount) box.getChildren().add(isCrypto ? label3 : label4);

                    setGraphic(box);

                } else {
                    setGraphic(null);
                }
            }
        };
    }

    public static ListCell<TradeCurrency> getTradeCurrencyButtonCell(String postFixSingle,
                                                                     String postFixMulti,
                                                                     Map<String, Integer> offerCounts) {
        return new ListCell<>() {

            @Override
            protected void updateItem(TradeCurrency item, boolean empty) {
                super.updateItem(item, empty);

                if (item != null && !empty) {
                    String code = item.getCode();

                    AnchorPane pane = new AnchorPane();
                    Label currency = new AutoTooltipLabel(item.getName() + " (" + item.getCode() + ")");
                    currency.getStyleClass().add("currency-label-selected");
                    AnchorPane.setLeftAnchor(currency, 0.0);
                    pane.getChildren().add(currency);

                    Optional<Integer> offerCountOptional = Optional.ofNullable(offerCounts.get(code));

                    switch (code) {
                        case GUIUtil.SHOW_ALL_FLAG:
                            currency.setText(Res.get("list.currency.showAll"));
                            break;
                        case GUIUtil.EDIT_FLAG:
                            currency.setText(Res.get("list.currency.editList"));
                            break;
                        default:
                            if (offerCountOptional.isPresent()) {
                                Label numberOfOffers = new AutoTooltipLabel(offerCountOptional.get() + " " +
                                        (offerCountOptional.get() == 1 ? postFixSingle : postFixMulti));
                                numberOfOffers.getStyleClass().add("offer-label-small");
                                AnchorPane.setRightAnchor(numberOfOffers, 0.0);
                                AnchorPane.setBottomAnchor(numberOfOffers, 2.0);
                                pane.getChildren().add(numberOfOffers);
                            }
                    }

                    setGraphic(pane);
                    setText("");
                } else {
                    setGraphic(null);
                    setText("");
                }
            }
        };
    }

    public static Callback<ListView<TradeCurrency>, ListCell<TradeCurrency>> getTradeCurrencyCellFactory(String postFixSingle,
                                                                                                         String postFixMulti,
                                                                                                         Map<String, Integer> offerCounts) {
        return p -> new ListCell<>() {
            @Override
            protected void updateItem(TradeCurrency item, boolean empty) {
                super.updateItem(item, empty);

                if (item != null && !empty) {

                    String code = item.getCode();
                    boolean isCrypto = CurrencyUtil.isCryptoCurrency(code);

                    HBox box = new HBox();
                    box.setSpacing(20);
                    box.setAlignment(Pos.CENTER_LEFT);

                    Label label1 = new AutoTooltipLabel(getCurrencyType(code));
                    label1.getStyleClass().add("currency-label-small");
                    Label label2 = new AutoTooltipLabel(isCrypto ? item.getNameAndCode() : code);
                    label2.getStyleClass().add("currency-label");
                    Label label3 = new AutoTooltipLabel(isCrypto ? "" : item.getName());
                    if (!isCrypto) label3.getStyleClass().add("currency-label");
                    Label label4 = new AutoTooltipLabel();

                    Optional<Integer> offerCountOptional = Optional.ofNullable(offerCounts.get(code));

                    switch (code) {
                        case GUIUtil.SHOW_ALL_FLAG:
                            label1.setText(Res.get("shared.all"));
                            label2.setText(Res.get("list.currency.showAll"));
                            break;
                        case GUIUtil.EDIT_FLAG:
                            label1.setText(Res.get("shared.edit"));
                            label2.setText(Res.get("list.currency.editList"));
                            break;
                        default:

                            // use icon if available
                            Node currencyIcon = getCurrencyGraphic(code, CURRENCY_GRAPHIC_ROW_SIZE);
                            if (currencyIcon != null) {
                                label1.setText("");
                                label1.setGraphic(currencyIcon);
                            }

                            Label offersTarget = isCrypto ? label3 : label4;
                            offerCountOptional.ifPresent(numOffers -> {
                                HBox.setMargin(offersTarget, new Insets(0, 0, 0, NUM_OFFERS_TRANSLATE_X));
                                offersTarget.getStyleClass().add("offer-label");
                                offersTarget.setText(numOffers + " " + (numOffers == 1 ? postFixSingle : postFixMulti));
                            });
                    }

                    // append the offer pill only when present so the row ends at real content
                    box.getChildren().addAll(label1, label2);
                    if (!isCrypto) box.getChildren().add(label3);
                    if (offerCountOptional.isPresent()) box.getChildren().add(isCrypto ? label3 : label4);

                    setGraphic(box);

                } else {
                    setGraphic(null);
                }
            }
        };
    }

    public static Callback<ListView<TradeCurrency>, ListCell<TradeCurrency>> getTradeCurrencyCellFactoryNameAndCode() {
        return p -> new ListCell<>() {
            @Override
            protected void updateItem(TradeCurrency item, boolean empty) {
                super.updateItem(item, empty);

                if (item != null && !empty) {

                    HBox box = new HBox();
                    box.setSpacing(10);

                    Label label1 = new AutoTooltipLabel(getCurrencyType(item.getCode()));
                    label1.getStyleClass().add("currency-label-small");
                    Label label2 = new AutoTooltipLabel(item.getNameAndCode());
                    label2.getStyleClass().add("currency-label");

                    // use icon if available
                    Node currencyIcon = getCurrencyGraphic(item.getCode(), CURRENCY_GRAPHIC_ROW_SIZE);
                    if (currencyIcon != null) {
                        label1.setText("");
                        label1.setGraphic(currencyIcon);
                    }

                    box.getChildren().addAll(label1, label2);

                    setGraphic(box);

                } else {
                    setGraphic(null);
                }
            }
        };
    }
    
    private static String getCurrencyType(String code) {
        if (CurrencyUtil.isFiatCurrency(code)) {
            return Res.get("shared.fiat");
        } else if (CurrencyUtil.isTraditionalCurrency(code)) {
            return Res.get("shared.traditional");
        } else if (CurrencyUtil.isCryptoCurrency(code)) {
            return Res.get("shared.crypto");
        } else {
            return "";
        }
    }

    public static ListCell<PaymentMethod> getPaymentMethodButtonCell() {
        return new ListCell<>() {

            @Override
            protected void updateItem(PaymentMethod item, boolean empty) {
                super.updateItem(item, empty);

                if (item != null && !empty) {
                    String id = item.getId();

                    this.getStyleClass().add("currency-label-selected");

                    if (id.equals(GUIUtil.SHOW_ALL_FLAG)) {
                        setText(Res.get("list.currency.showAll"));
                    } else {
                        setText(Res.get(id));
                    }
                } else {
                    setText("");
                }
            }
        };
    }

    public static Callback<ListView<PaymentMethod>, ListCell<PaymentMethod>> getPaymentMethodCellFactory() {
        return getPaymentMethodCellFactory("", "", Map.of());
    }

    public static Callback<ListView<PaymentMethod>, ListCell<PaymentMethod>> getPaymentMethodCellFactory(String postFixSingle,
                                                                                                         String postFixMulti,
                                                                                                         Map<String, Integer> offerCounts) {
        return p -> new ListCell<>() {
            @Override
            protected void updateItem(PaymentMethod method, boolean empty) {
                super.updateItem(method, empty);

                if (method != null && !empty) {
                    String id = method.getId();

                    HBox box = new HBox();
                    box.setSpacing(20);
                    box.setAlignment(Pos.CENTER_LEFT);
                    box.setPadding(new Insets(0, 0, 0, (CURRENCY_LABEL_SMALL_WIDTH - CURRENCY_GRAPHIC_ROW_SIZE) / 2)); // match the currency list's leftmost inset (logo centered in its label)
                    Label paymentMethod = new AutoTooltipLabel(Res.get(id));
                    paymentMethod.getStyleClass().add("currency-label");
                    Label offerCount = new AutoTooltipLabel();
                    box.getChildren().addAll(paymentMethod, offerCount);

                    if (id.equals(GUIUtil.SHOW_ALL_FLAG)) {
                        paymentMethod.setText(Res.get("list.currency.showAll"));
                    } else {
                        Optional.ofNullable(offerCounts.get(id)).ifPresent(numOffers -> {
                            HBox.setMargin(offerCount, new Insets(0, 0, 0, NUM_OFFERS_TRANSLATE_X));
                            offerCount.getStyleClass().add("offer-label");
                            offerCount.setText(numOffers + " " + (numOffers == 1 ? postFixSingle : postFixMulti));
                        });
                    }

                    setGraphic(box);

                } else {
                    setGraphic(null);
                }
            }
        };
    }

    public static void updateConfidence(MoneroTx tx,
                                        Tooltip tooltip,
                                        TxConfidenceIndicator txConfidenceIndicator) {
        updateConfidence(tx, null, tooltip, txConfidenceIndicator);
    }

    public static void updateConfidence(MoneroTx tx,
                                        Trade trade,
                                        Tooltip tooltip,
                                        TxConfidenceIndicator txConfidenceIndicator) {
        if (tx == null || tx.getNumConfirmations() == null || !tx.isRelayed()) {
            if (trade != null && trade.isDepositsUnlocked()) {
                if (tx == null) {
                    tooltip.setText(Res.get("confidence.confirmed", ">=10"));
                } else {
                    tooltip.setText(Res.get("confidence.confirmed", tx.getNumConfirmations()));
                }
                txConfidenceIndicator.setProgress(1.0);
            } else {
                tooltip.setText(Res.get("confidence.unknown"));
                txConfidenceIndicator.setProgress(-1);
            }
        } else {
            if (tx.isFailed()) {
                tooltip.setText(Res.get("confidence.invalid"));
                txConfidenceIndicator.setProgress(0);
            } else if (tx.isConfirmed()) {
                tooltip.setText(Res.get("confidence.confirmed", tx.getNumConfirmations()));
                txConfidenceIndicator.setProgress((double) tx.getNumConfirmations() / (double) XmrWalletService.NUM_BLOCKS_UNLOCK);
            } else {
                tooltip.setText(Res.get("confidence.confirmed", 0));
                txConfidenceIndicator.setProgress(-1);
            }
        }

        txConfidenceIndicator.setPrefSize(24, 24);
    }

    public static void openWebPage(String target) {
        openWebPage(target, true, null);
    }

    public static void openWebPage(String target, boolean useReferrer) {
        openWebPage(target, useReferrer, null);
    }

    public static void openWebPageNoPopup(String target) {
        doOpenWebPage(target);
    }

    public static void openWebPage(String target, boolean useReferrer, Runnable closeHandler) {

        if (useReferrer && target.contains("haveno.network")) {
            // add utm parameters
            target = appendURI(target, "utm_source=desktop-client&utm_medium=in-app-link&utm_campaign=language_" +
                    preferences.getUserLanguage());
        }

        if (DontShowAgainLookup.showAgain(OPEN_WEB_PAGE_KEY)) {
            final String finalTarget = target;
            new Popup().information(Res.get("guiUtil.openWebBrowser.warning", target))
                    .actionButtonText(Res.get("guiUtil.openWebBrowser.doOpen"))
                    .onAction(() -> {
                        DontShowAgainLookup.dontShowAgain(OPEN_WEB_PAGE_KEY, true);
                        doOpenWebPage(finalTarget);
                    })
                    .closeButtonText(Res.get("guiUtil.openWebBrowser.copyUrl"))
                    .onClose(() -> {
                        Utilities.copyToClipboard(finalTarget);
                        if (closeHandler != null) {
                            closeHandler.run();
                        }
                    })
                    .show();
        } else {
            if (closeHandler != null) {
                closeHandler.run();
            }

            doOpenWebPage(target);
        }
    }

    private static String appendURI(String uri, String appendQuery) {
        try {
            final URI oldURI = new URI(uri);

            String newQuery = oldURI.getQuery();

            if (newQuery == null) {
                newQuery = appendQuery;
            } else {
                newQuery += "&" + appendQuery;
            }

            URI newURI = new URI(oldURI.getScheme(), oldURI.getAuthority(), oldURI.getPath(),
                    newQuery, oldURI.getFragment());

            return newURI.toString();
        } catch (URISyntaxException e) {
            e.printStackTrace();
            log.error(e.getMessage());

            return uri;
        }
    }

    private static void doOpenWebPage(String target) {
        try {
            Utilities.openURI(safeParse(target));
        } catch (Exception e) {
            e.printStackTrace();
            log.error(e.getMessage());
        }
    }

    private static URI safeParse(String url) throws URISyntaxException {
        int hashIndex = url.indexOf('#');

        if (hashIndex >= 0 && hashIndex < url.length() - 1) {
            String base = url.substring(0, hashIndex);
            String fragment = url.substring(hashIndex + 1);
            String encodedFragment = URLEncoder.encode(fragment, StandardCharsets.UTF_8);
            return new URI(base + "#" + encodedFragment);
        }

        return new URI(url); // no fragment
    }

    public static String getPercentageOfTradeAmount(BigInteger fee, BigInteger tradeAmount) {
        String result = " (" + getPercentage(fee, tradeAmount) +
                " " + Res.get("guiUtil.ofTradeAmount") + ")";

        return result;
    }

    public static String getPercentage(BigInteger part, BigInteger total) {
        return FormattingUtils.formatToPercentWithSymbol(HavenoUtils.divide(part, total));
    }

    public static <T> T getParentOfType(Node node, Class<T> t) {
        Node parent = node.getParent();
        while (parent != null) {
            if (parent.getClass().isAssignableFrom(t)) {
                break;
            } else {
                parent = parent.getParent();
            }
        }
        return t.cast(parent);
    }

    public static void showZelleWarning() {
        String key = "confirmZelleRequirements";
        final String currencyName = Config.baseCurrencyNetwork().getCurrencyName();
        new Popup().information(Res.get("payment.zelle.info", currencyName, currencyName))
                .width(900)
                .closeButtonText(Res.get("shared.iConfirm"))
                .dontShowAgainId(key)
                .show();
    }

    public static void showFasterPaymentsWarning(Navigation navigation) {
        String key = "recreateFasterPaymentsAccount";
        String currencyName = Config.baseCurrencyNetwork().getCurrencyName();
        new Popup().information(Res.get("payment.fasterPayments.newRequirements.info", currencyName))
                .width(900)
                .actionButtonTextWithGoTo("mainView.menu.account")
                .onAction(() -> {
                    navigation.setReturnPath(navigation.getCurrentPath());
                    navigation.navigateTo(MainView.class, AccountView.class, TraditionalAccountsView.class);
                })
                .dontShowAgainId(key)
                .show();
    }

    public static String getMoneroURI(String address, BigInteger amount, String label) {
        MoneroTxConfig txConfig = new MoneroTxConfig().setAddress(address);
        if (amount != null) txConfig.setAmount(amount);
        if (label != null && !label.isEmpty() && !disablePaymentUriLabel) txConfig.setNote(label);
        return MoneroUtils.getPaymentUri(txConfig);
    }

    public static boolean isBootstrappedOrShowPopup(P2PService p2PService) {
        if (p2PService.isBootstrapped() && p2PService.getNumConnectedPeers().get() > 0) {
            return true;
        }
        new Popup().information(Res.get("popup.warning.notFullyConnected")).show();
        return false;
    }

    public static boolean isReadyForTxBroadcastOrShowPopup(XmrWalletService xmrWalletService) {
        XmrConnectionService xmrConnectionService = xmrWalletService.getXmrConnectionService();
        if (!xmrConnectionService.hasSufficientPeersForBroadcast()) {
            new Popup().information(Res.get("popup.warning.notSufficientConnectionsToXmrNetwork", xmrConnectionService.getMinBroadcastConnections())).show();
            return false;
        }

        if (!xmrConnectionService.isDownloadComplete()) {
            new Popup().information(Res.get("popup.warning.downloadNotComplete")).show();
            return false;
        }

        if (!isWalletSyncedWithinToleranceOrShowPopup(xmrWalletService)) {
            return false;
        }

        try {
            xmrConnectionService.verifyConnection();
        } catch (Exception e) {
            new Popup().information(e.getMessage()).show();
            return false;
        }

        return true;
    }

    public static boolean isWalletSyncedWithinToleranceOrShowPopup(XmrWalletService xmrWalletService) {
        if (!xmrWalletService.isSyncedWithinTolerance()) {
            new Popup().information(Res.get("popup.warning.walletNotSynced")).show();
            return false;
        }
        return true;
    }

    public static boolean canCreateOrTakeOfferOrShowPopup(User user, Navigation navigation) {

        if (!user.hasAcceptedArbitrators()) {
            log.warn("There are no arbitrators available");
            new Popup().warning(Res.get("popup.warning.noArbitratorsAvailable")).show();
            return false;
        }

        if (user.currentPaymentAccountProperty().get() == null) {
            new Popup().headLine(Res.get("popup.warning.noTradingAccountSetup.headline"))
                    .instruction(Res.get("popup.warning.noTradingAccountSetup.msg"))
                    .actionButtonTextWithGoTo("mainView.menu.account")
                    .onAction(() -> {
                        navigation.setReturnPath(navigation.getCurrentPath());
                        navigation.navigateTo(MainView.class, AccountView.class, TraditionalAccountsView.class);
                    }).show();
            return false;
        }

        return true;
    }

    public static void showWantToBurnBTCPopup(Coin miningFee, Coin amount, CoinFormatter btcFormatter) {
        new Popup().warning(Res.get("popup.warning.burnXMR", btcFormatter.formatCoinWithCode(miningFee),
                btcFormatter.formatCoinWithCode(amount))).show();
    }

    public static void requestFocus(Node node) {
        UserThread.execute(node::requestFocus);
    }

    public static void rescanOutputs(Preferences preferences) {
        try {
            new Popup().information(Res.get("settings.net.rescanOutputsSuccess"))
                    .actionButtonText(Res.get("shared.yes"))
                    .onAction(() -> {
                        throw new RuntimeException("Rescanning wallet outputs not yet implemented");
                        //UserThread.runAfter(HavenoApp.getShutDownHandler(), 100, TimeUnit.MILLISECONDS);
                    })
                    .closeButtonText(Res.get("shared.cancel"))
                    .show();
        } catch (Throwable t) {
            new Popup().error(Res.get("settings.net.rescanOutputsFailed", t)).show();
        }
    }

    public static void showSelectableTextModal(String title, String text) {
        TextArea textArea = new TextArea();
        textArea.setText(text);
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setPrefSize(800, 600);
        textArea.getStyleClass().add("window-text-area");

        Scene scene = new Scene(textArea);
        CssTheme.loadSceneStyles(scene, CssTheme.getCurrentTheme(), false);
        Stage stage = new Stage();
        if (null != title) {
            stage.setTitle(title);
        }
        stage.setScene(scene);
        stage.initModality(Modality.NONE);
        stage.initStyle(StageStyle.UTILITY);
        stage.show();
    }

    public static StringConverter<PaymentAccount> getPaymentAccountsComboBoxStringConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(PaymentAccount paymentAccount) {
                if (paymentAccount.hasMultipleCurrencies()) {
                    return paymentAccount.getAccountName() + " (" + Res.get(paymentAccount.getPaymentMethod().getId()) + ")";
                } else {
                    TradeCurrency singleTradeCurrency = paymentAccount.getSingleTradeCurrency();
                    String prefix = singleTradeCurrency != null ? singleTradeCurrency.getCode() + ", " : "";
                    return paymentAccount.getAccountName() + " (" + prefix +
                            Res.get(paymentAccount.getPaymentMethod().getId()) + ")";
                }
            }

            @Override
            public PaymentAccount fromString(String s) {
                return null;
            }
        };
    }

    public static Callback<ListView<PaymentAccount>, ListCell<PaymentAccount>> getPaymentAccountListCellFactory(
            ComboBox<PaymentAccount> paymentAccountsComboBox,
            AccountAgeWitnessService accountAgeWitnessService) {
        return p -> new ListCell<>() {
            @Override
            protected void updateItem(PaymentAccount item, boolean empty) {
                super.updateItem(item, empty);

                if (item != null && !empty) {

                    boolean needsSigning = PaymentMethod.hasChargebackRisk(item.getPaymentMethod(),
                            item.getTradeCurrencies());

                    InfoAutoTooltipLabel label = new InfoAutoTooltipLabel(
                            paymentAccountsComboBox.getConverter().toString(item),
                            ContentDisplay.RIGHT);

                    if (needsSigning) {
                        AccountAgeWitness myWitness = accountAgeWitnessService.getMyWitness(
                                item.paymentAccountPayload);
                        AccountAgeWitnessService.SignState signState =
                                accountAgeWitnessService.getSignState(myWitness);
                        String info = StringUtils.capitalize(signState.getDisplayString());

                        MaterialDesignIcon icon = getIconForSignState(signState);

                        label.setIcon(icon, info);
                    }
                    setGraphic(label);
                } else {
                    setGraphic(null);
                }
            }
        };
    }

    public static void removeChildrenFromGridPaneRows(GridPane gridPane, int start, int end) {
        Map<Integer, List<Node>> childByRowMap = new HashMap<>();
        gridPane.getChildren().forEach(child -> {
            final Integer rowIndex = GridPane.getRowIndex(child);
            childByRowMap.computeIfAbsent(rowIndex, key -> new ArrayList<>());
            childByRowMap.get(rowIndex).add(child);
        });

        for (int i = Math.min(start, childByRowMap.size()); i < Math.min(end + 1, childByRowMap.size()); i++) {
            List<Node> nodes = childByRowMap.get(i);
            if (nodes != null) {
                nodes.stream()
                        .filter(Objects::nonNull)
                        .filter(node -> gridPane.getChildren().contains(node))
                        .forEach(node -> gridPane.getChildren().remove(node));
            }
        }
    }
        public static void setFitToRowsForTableView(TableView<?> tableView,
                                                int rowHeight,
                                                int headerHeight,
                                                int minNumRows,
                                                int maxNumRows) {
        int size = tableView.getItems().size();
        int minHeight = rowHeight * minNumRows + headerHeight;
        int maxHeight = rowHeight * maxNumRows + headerHeight;
        checkArgument(maxHeight >= minHeight, "maxHeight cannot be smaller as minHeight");
        int height = Math.min(maxHeight, Math.max(minHeight, size * rowHeight + headerHeight));

        tableView.setPrefHeight(-1);
        tableView.setVisible(false);
        // We need to delay the setter to the next render frame as otherwise views don' get updated in some cases
        // Not 100% clear what causes that issue, but seems the requestLayout method is not called otherwise.
        // We still need to set the height immediately, otherwise some views render an incorrect layout.
        tableView.setPrefHeight(height);

        UserThread.execute(() -> {
            tableView.setPrefHeight(height);
            tableView.setVisible(true);
        });
    }

    public static Tuple2<ComboBox<TradeCurrency>, Integer> addRegionCountryTradeCurrencyComboBoxes(GridPane gridPane,
                                                                                                   int gridRow,
                                                                                                   Consumer<Country> onCountrySelectedHandler,
                                                                                                   Consumer<TradeCurrency> onTradeCurrencySelectedHandler) {
        gridRow = addRegionCountry(gridPane, gridRow, onCountrySelectedHandler);

        ComboBox<TradeCurrency> currencyComboBox = FormBuilder.addComboBox(gridPane, ++gridRow,
                Res.get("shared.currency"));
        currencyComboBox.setPromptText(Res.get("list.currency.select"));
        currencyComboBox.setItems(FXCollections.observableArrayList(CurrencyUtil.getAllSortedTraditionalCurrencies()));

        currencyComboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(TradeCurrency currency) {
                return currency.getNameAndCode();
            }

            @Override
            public TradeCurrency fromString(String string) {
                return null;
            }
        });
        currencyComboBox.setDisable(true);

        currencyComboBox.setOnAction(e ->
                onTradeCurrencySelectedHandler.accept(currencyComboBox.getSelectionModel().getSelectedItem()));

        return new Tuple2<>(currencyComboBox, gridRow);
    }

    public static int addRegionCountry(GridPane gridPane,
                                       int gridRow,
                                       Consumer<Country> onCountrySelectedHandler) {
        Tuple3<Label, ComboBox<haveno.core.locale.Region>, ComboBox<Country>> tuple3 = addTopLabelComboBoxComboBox(gridPane, ++gridRow, Res.get("payment.country"));

        ComboBox<haveno.core.locale.Region> regionComboBox = tuple3.second;
        regionComboBox.setPromptText(Res.get("payment.select.region"));
        regionComboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(haveno.core.locale.Region region) {
                return region.name;
            }

            @Override
            public haveno.core.locale.Region fromString(String s) {
                return null;
            }
        });
        regionComboBox.setItems(FXCollections.observableArrayList(CountryUtil.getAllRegions()));

        ComboBox<Country> countryComboBox = tuple3.third;
        countryComboBox.setVisibleRowCount(15);
        countryComboBox.setDisable(true);
        countryComboBox.setPromptText(Res.get("payment.select.country"));
        countryComboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(Country country) {
                return country.name + " (" + country.code + ")";
            }

            @Override
            public Country fromString(String s) {
                return null;
            }
        });

        regionComboBox.setOnAction(e -> {
            haveno.core.locale.Region selectedItem = regionComboBox.getSelectionModel().getSelectedItem();
            if (selectedItem != null) {
                countryComboBox.setDisable(false);
                countryComboBox.setItems(FXCollections.observableArrayList(CountryUtil.getAllCountriesForRegion(selectedItem)));
            }
        });

        countryComboBox.setOnAction(e ->
                onCountrySelectedHandler.accept(countryComboBox.getSelectionModel().getSelectedItem()));

        return gridRow;
    }

    @NotNull
    public static <T> ListCell<T> getComboBoxButtonCell(String title, ComboBox<T> comboBox) {
        return getComboBoxButtonCell(title, comboBox, true);
    }

    @NotNull
    public static <T> ListCell<T> getComboBoxButtonCell(String title,
                                                        ComboBox<T> comboBox,
                                                        Boolean hideOriginalPrompt) {
        return new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);

                // See https://github.com/jfoenixadmin/JFoenix/issues/610
                if (hideOriginalPrompt)
                    this.setVisible(item != null || !empty);

                if (empty || item == null) {
                    setText(title);
                } else {
                    setText(comboBox.getConverter().toString(item));
                }
            }
        };
    }

    public static MaterialDesignIcon getIconForSignState(AccountAgeWitnessService.SignState state) {
        if (state.equals(AccountAgeWitnessService.SignState.PEER_INITIAL)) {
            return MaterialDesignIcon.CLOCK;
        }

        return (state.equals(AccountAgeWitnessService.SignState.ARBITRATOR) ||
                state.equals(AccountAgeWitnessService.SignState.PEER_SIGNER)) ?
                MaterialDesignIcon.APPROVAL : MaterialDesignIcon.ALERT_CIRCLE_OUTLINE;
    }

    public static ScrollPane createScrollPane() {
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        AnchorPane.setLeftAnchor(scrollPane, 0d);
        AnchorPane.setTopAnchor(scrollPane, 0d);
        AnchorPane.setRightAnchor(scrollPane, 0d);
        AnchorPane.setBottomAnchor(scrollPane, 0d);
        return scrollPane;
    }

    public static void setDefaultTwoColumnConstraintsForGridPane(GridPane gridPane) {
        ColumnConstraints columnConstraints1 = new ColumnConstraints();
        columnConstraints1.setHalignment(HPos.RIGHT);
        columnConstraints1.setHgrow(Priority.NEVER);
        columnConstraints1.setMinWidth(200);
        ColumnConstraints columnConstraints2 = new ColumnConstraints();
        columnConstraints2.setHgrow(Priority.ALWAYS);
        gridPane.getColumnConstraints().addAll(columnConstraints1, columnConstraints2);
    }

    public static void applyFilledStyle(TextField textField) {
        textField.textProperty().addListener((observable, oldValue, newValue) -> {
            updateFilledStyle(textField);
        });
    }

    private static void updateFilledStyle(TextField textField) {
        if (textField.getText() != null && !textField.getText().isEmpty()) {
            if (!textField.getStyleClass().contains("filled")) {
                textField.getStyleClass().add("filled");
            }
        } else {
            textField.getStyleClass().remove("filled");
        }
    }

    public static void applyFilledStyle(ComboBox<?> comboBox) {
        comboBox.valueProperty().addListener((observable, oldValue, newValue) -> {
            updateFilledStyle(comboBox);
        });
    }
    
    private static void updateFilledStyle(ComboBox<?> comboBox) {
        if (comboBox.getValue() != null) {
            if (!comboBox.getStyleClass().contains("filled")) {
                comboBox.getStyleClass().add("filled");
            }
        } else {
            comboBox.getStyleClass().remove("filled");
        }
    }

    // While the popup opens, the pointer can sweep down across the main scene before the popup window
    // renders under it (native popup-open latency), briefly showing the arrow. Force a hand cursor on
    // the scene root while the popup is showing; restore it on close. The root carries the hand rather
    // than the scene cursor: the popup scene copies the owner scene's cursor as it shows, and a non-null
    // popup scene cursor breaks per-node hand resolution on macOS. A stock combo shows its popup on
    // mouse release, so a fast sweep can exit the control before showing flips true - hence the hand is
    // set both at show time and on pointer exit. Deferring to exit while an editable combo is still
    // hovered keeps the editor's own text cursor when it is first clicked.
    public static void showHandCursorWhileOpening(ComboBox<?> comboBox) {
        Cursor[] savedRootCursor = new Cursor[1];
        comboBox.showingProperty().addListener((obs, wasShowing, showing) -> {
            Scene scene = comboBox.getScene();
            if (scene == null) return;
            if (showing) {
                savedRootCursor[0] = scene.getRoot().getCursor();
                if (!comboBox.isEditable() || !comboBox.isHover()) scene.getRoot().setCursor(Cursor.HAND);
            } else {
                scene.getRoot().setCursor(savedRootCursor[0]);
                savedRootCursor[0] = null;
            }
        });
        comboBox.addEventFilter(MouseEvent.MOUSE_EXITED, e -> {
            if (comboBox.isShowing() && comboBox.getScene() != null)
                comboBox.getScene().getRoot().setCursor(Cursor.HAND);
        });
    }

    public static void applyTableStyle(TableView<?> tableView) {
        applyTableStyle(tableView, true);
    }

    public static void applyTableStyle(TableView<?> tableView, boolean applyRoundedArc) {
        if (applyRoundedArc) applyRoundedArc(tableView);
        addSpacerColumns(tableView);
        applyEdgeColumnStyleClasses(tableView);
    }

    private static void applyRoundedArc(TableView<?> tableView) {
        Rectangle clip = new Rectangle();
        clip.setArcWidth(Layout.ROUNDED_ARC);
        clip.setArcHeight(Layout.ROUNDED_ARC);
        tableView.setClip(clip);
        tableView.layoutBoundsProperty().addListener((obs, oldVal, newVal) -> {
            clip.setWidth(newVal.getWidth());
            clip.setHeight(newVal.getHeight());
        });
    }

    private static <T> void addSpacerColumns(TableView<T> tableView) {
        TableColumn<T, Void> leftSpacer = new TableColumn<>();
        TableColumn<T, Void> rightSpacer = new TableColumn<>();

        configureSpacerColumn(leftSpacer);
        configureSpacerColumn(rightSpacer);

        tableView.getColumns().add(0, leftSpacer);
        tableView.getColumns().add(rightSpacer);
    }

    private static void configureSpacerColumn(TableColumn<?, ?> column) {
        column.setPrefWidth(15);
        column.setMaxWidth(15);
        column.setMinWidth(15);
        column.setReorderable(false);
        column.setResizable(false);
        column.setSortable(false);
        column.setCellFactory(col -> new TableCell<>()); // empty cell
    }

    private static <T> void applyEdgeColumnStyleClasses(TableView<T> tableView) {
        ListChangeListener<TableColumn<T, ?>> columnListener = change -> {
            UserThread.execute(() -> {
                updateEdgeColumnStyleClasses(tableView);
            });
        };

        tableView.getColumns().addListener(columnListener);
        tableView.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin != null) {
                UserThread.execute(() -> {
                    updateEdgeColumnStyleClasses(tableView);
                });
            }
        });

        // react to size changes
        ChangeListener<Number> sizeListener = (obs, oldVal, newVal) -> updateEdgeColumnStyleClasses(tableView);
        tableView.heightProperty().addListener(sizeListener);
        tableView.widthProperty().addListener(sizeListener);

        updateEdgeColumnStyleClasses(tableView);
    }

    private static <T> void updateEdgeColumnStyleClasses(TableView<T> tableView) {
        ObservableList<TableColumn<T, ?>> columns = tableView.getColumns();

        // find columns with "first-column" and "last-column" classes
        TableColumn<T, ?> firstCol = null;
        TableColumn<T, ?> lastCol = null;
        for (TableColumn<T, ?> col : columns) {
            if (col.getStyleClass().contains("first-column")) {
                firstCol = col;
            } else if (col.getStyleClass().contains("last-column")) {
                lastCol = col;
            }
        }

        // handle if columns do not exist
        if (firstCol == null || lastCol == null) {
            if (firstCol != null) throw new IllegalStateException("Missing column with 'last-column'");
            if (lastCol != null) throw new IllegalStateException("Missing column with 'first-column'");

            // remove all classes
            for (TableColumn<T, ?> col : columns) {
                col.getStyleClass().removeAll("first-column", "last-column");
            }

            // apply first and last classes
            if (!columns.isEmpty()) {
                TableColumn<T, ?> first = columns.get(0);
                TableColumn<T, ?> last = columns.get(columns.size() - 1);

                if (!first.getStyleClass().contains("first-column")) {
                    first.getStyleClass().add("first-column");
                }

                if (!last.getStyleClass().contains("last-column")) {
                    last.getStyleClass().add("last-column");
                }
            }
        } else {

            // done if correct order
            if (columns.get(0) == firstCol && columns.get(columns.size() - 1) == lastCol) {
                return;
            }

            // set first and last columns
            if (columns.get(0) != firstCol) {
                columns.remove(firstCol);
                columns.add(0, firstCol);
            }
            if (columns.get(columns.size() - 1) != lastCol) {
                columns.remove(lastCol);
                columns.add(firstCol == lastCol ? columns.size() - 1 : columns.size(), lastCol);
            }
        }
    }

    public static <T> ObservableList<TableColumn<T, ?>> getContentColumns(TableView<T> tableView) {
        ObservableList<TableColumn<T, ?>> contentColumns = FXCollections.observableArrayList();
        for (TableColumn<T, ?> column : tableView.getColumns()) {
            if (!column.getStyleClass().contains("first-column") && !column.getStyleClass().contains("last-column")) {
                contentColumns.add(column);
            }
        }
        return contentColumns;
    }

    private static final double CURRENCY_LOGO_DEFAULT_SIZE = 24; // currency logo size (px) outside list rows

    private static ImageView getCurrencyImageView(String currencyCode) {
        return getCurrencyImageView(currencyCode, CURRENCY_LOGO_DEFAULT_SIZE);
    }

    // crypto logos decoded once per device-pixel size and shared across icons; concurrent for the background pre-warm
    private static final Map<String, Image> currencyImageCache = new ConcurrentHashMap<>();
    private static final Set<String> currencyImageMisses = ConcurrentHashMap.newKeySet(); // codes without a decodable logo

    // Pre-decode all crypto logos at the sizes lists use, off the FX thread (safe while the images are
    // unattached), so pulldowns render from cache instead of stalling on PNG decodes, whose inflate cost
    // is O(native pixels) no matter how small the target.
    public static void warmCurrencyIconCache() {
        double scale = Screen.getPrimary().getOutputScaleX();
        Thread warmer = new Thread(() -> {
            try {
                for (CryptoCurrency currency : CurrencyUtil.getAllSortedCryptoCurrencies()) {
                    getCurrencyImage(currency.getCode(), currencyIconBoxPx(CURRENCY_GRAPHIC_ROW_SIZE, scale), currencyIconMarginPx(scale));
                    getCurrencyImage(currency.getCode(), Math.ceil(CURRENCY_LOGO_DEFAULT_SIZE * scale));
                }
            } catch (Exception e) {
                log.warn("Error pre-warming currency icon cache: {}", e.getMessage());
            }
        }, "currency-icon-warmer");
        warmer.setDaemon(true);
        warmer.start();
    }

    private static ImageView getCurrencyImageView(String currencyCode, double size) {
        if (currencyCode == null) return null;
        String imageId = getImageId(currencyCode);
        if (imageId == null) return null;
        ImageView icon = new ImageView();
        icon.setFitWidth(size);
        icon.setPreserveRatio(true);
        icon.setSmooth(true);

        // decode crypto logos at device-pixel size for crisp HiDPI downscaling; fall back to CSS-supplied image
        Image image = getCurrencyImage(currencyCode, Math.ceil(size * Screen.getPrimary().getOutputScaleX()));
        if (image != null) icon.setImage(image);
        else icon.setId(imageId);
        return icon;
    }

    private static Image getCurrencyImage(String currencyCode, double px) {
        return getCurrencyImage(currencyCode, (int) px, 0);
    }

    // decode the logo at native size (decoding at target size instead would flat-cut the right/bottom
    // edge AA), then area-average it into a transparent box inset by a margin so the antialiased edge
    // never snaps against the icon bounds and looks clipped
    private static Image getCurrencyImage(String currencyCode, int boxPx, int marginPx) {
        if (!CurrencyUtil.isCryptoCurrency(currencyCode)) return null;
        String code = currencyCode.toLowerCase();
        if (currencyImageMisses.contains(code)) return null; // avoid re-probing the classpath per cell update
        Image image = currencyImageCache.computeIfAbsent(code + "@" + boxPx + "+" + marginPx, k -> {
            try (InputStream in = GUIUtil.class.getResourceAsStream("/images/" + code + "_logo.png")) {
                if (in == null) return null;
                Image logo = new Image(in);
                return logo.isError() ? null : downscaleCentered(logo, boxPx, marginPx);
            } catch (IOException e) {
                return null;
            }
        });
        if (image == null) currencyImageMisses.add(code);
        return image;
    }

    // area-averaged downscale of src centered in a transparent boxPx square, inset by marginPx per side
    private static Image downscaleCentered(Image src, int boxPx, int marginPx) {
        int sw = (int) src.getWidth(), sh = (int) src.getHeight();
        int contentPx = Math.max(1, boxPx - 2 * marginPx);
        double scale = Math.min(1, Math.min(contentPx / (double) sw, contentPx / (double) sh));
        int dw = Math.max(1, (int) Math.round(sw * scale)), dh = Math.max(1, (int) Math.round(sh * scale));
        int[] pixels = new int[sw * sh];
        src.getPixelReader().getPixels(0, 0, sw, sh, PixelFormat.getIntArgbInstance(), pixels, 0, sw);
        WritableImage out = new WritableImage(boxPx, boxPx);
        PixelWriter writer = out.getPixelWriter();
        int ox = (boxPx - dw) / 2, oy = (boxPx - dh) / 2;
        double xr = sw / (double) dw, yr = sh / (double) dh;
        for (int y = 0; y < dh; y++) {
            double sy0 = y * yr, sy1 = Math.min(sh, sy0 + yr);
            for (int x = 0; x < dw; x++) {
                double sx0 = x * xr, sx1 = Math.min(sw, sx0 + xr);
                double a = 0, r = 0, g = 0, b = 0, area = 0;
                for (int sy = (int) sy0; sy < sy1; sy++) {
                    double wy = Math.min(sy + 1, sy1) - Math.max(sy, sy0);
                    for (int sx = (int) sx0; sx < sx1; sx++) {
                        double w = (Math.min(sx + 1, sx1) - Math.max(sx, sx0)) * wy;
                        int argb = pixels[sy * sw + sx];
                        double wa = (argb >>> 24) * w;
                        a += wa;
                        r += ((argb >> 16) & 0xFF) * wa;
                        g += ((argb >> 8) & 0xFF) * wa;
                        b += (argb & 0xFF) * wa;
                        area += w;
                    }
                }
                int alpha = (int) Math.round(a / area);
                writer.setArgb(ox + x, oy + y, alpha == 0 ? 0 : (alpha << 24)
                        | ((int) Math.round(r / a) << 16) | ((int) Math.round(g / a) << 8) | (int) Math.round(b / a));
            }
        }
        return out;
    }

    // PNG pixel dimensions read from the IHDR header, avoiding a full decode
    private static int[] pngSize(String resourcePath) {
        try (InputStream in = GUIUtil.class.getResourceAsStream(resourcePath)) {
            if (in == null) return null;
            byte[] h = in.readNBytes(24);
            if (h.length < 24) return null;
            int w = ((h[16] & 0xff) << 24) | ((h[17] & 0xff) << 16) | ((h[18] & 0xff) << 8) | (h[19] & 0xff);
            int ht = ((h[20] & 0xff) << 24) | ((h[21] & 0xff) << 16) | ((h[22] & 0xff) << 8) | (h[23] & 0xff);
            return w > 0 && ht > 0 ? new int[]{w, ht} : null;
        } catch (IOException e) {
            return null;
        }
    }

    // branding logos decoded once per theme + device-pixel size (never above native), kept in sync with the theme
    private static final Map<String, Image> brandingImageCache = new HashMap<>();

    // Render a branding logo crisply on HiDPI: decode the theme's PNG at device-pixel size, capped at the
    // source so it is never upscaled, and swap it on theme toggle. Pass the fixed dimension in fitWidth or
    // fitHeight and 0 for the other. The CSS id drives the logo only if a PNG can't be decoded.
    public static void setBrandingLogo(ImageView logo, Preferences preferences, String lightPath, String darkPath, double fitWidth, double fitHeight) {
        double scale = Screen.getPrimary().getOutputScaleX();
        double pxW = fitWidth <= 0 ? 0 : Math.ceil(fitWidth * scale);
        double pxH = fitHeight <= 0 ? 0 : Math.ceil(fitHeight * scale);
        ChangeListener<Number> themeListener = (ov, o, n) -> updateBrandingLogo(logo, n.intValue() == 1 ? darkPath : lightPath, pxW, pxH);
        updateBrandingLogo(logo, preferences.getCssTheme() == 1 ? darkPath : lightPath, pxW, pxH);

        // weak listener (strong ref held on the ImageView) lets the logo be GC'd with its scene without leaking
        logo.getProperties().put("brandingThemeListener", themeListener);
        preferences.getCssThemeProperty().addListener(new WeakChangeListener<>(themeListener));
    }

    private static void updateBrandingLogo(ImageView logo, String resourcePath, double pxW, double pxH) {
        Image image = brandingImageCache.computeIfAbsent(resourcePath + "@" + (int) pxW + "x" + (int) pxH, k -> {
            int[] source = pngSize(resourcePath);
            double w = source != null && pxW > source[0] ? source[0] : pxW;
            double h = source != null && pxH > source[1] ? source[1] : pxH;
            try (InputStream in = GUIUtil.class.getResourceAsStream(resourcePath)) {
                if (in == null) return null;
                Image img = new Image(in, w, h, true, true);
                return img.isError() ? null : img;
            } catch (IOException e) {
                return null;
            }
        });
        // drop the CSS id once decoded so the native-size -fx-image is never also loaded
        if (image != null) {
            logo.setImage(image);
            logo.setId(null);
        }
    }

    public static StackPane getCurrencyIcon(String currencyCode) {
        ImageView icon = getCurrencyImageView(currencyCode);
        return icon == null ? null : new StackPane(icon);
    }

    public static final double CURRENCY_GRAPHIC_ROW_SIZE = 27; // currency logo size (px) in list rows; adjust to resize
    private static final double CURRENCY_LABEL_SMALL_WIDTH = 45; // .currency-label-small -fx-pref-width
    private static final double CURRENCY_ICON_MARGIN = 1; // transparent margin (logical px) bled just outside list logos

    public static Node getCurrencyGraphic(String currencyCode, double size) {
        if (currencyCode == null) return null;
        if (CurrencyUtil.isFiatCurrency(currencyCode)) return getFiatCurrencyBadge(currencyCode, size);
        return getCurrencyIcon(currencyCode, size);
    }

    private static Node getFiatCurrencyBadge(String currencyCode, double size) {
        String symbol;
        try {
            symbol = Currency.getInstance(currencyCode).getSymbol(GlobalSettings.getLocale());
        } catch (Exception e) {
            symbol = currencyCode;
        }
        if (symbol == null || symbol.isEmpty()) symbol = currencyCode;
        if (symbol.length() > 3) symbol = symbol.substring(0, 3);

        Label label = new Label(symbol);
        label.getStyleClass().add("fiat-currency-symbol");
        double fontSize = symbol.length() >= 3 ? size * 0.34 : symbol.length() == 2 ? size * 0.5 : size * 0.72;
        label.setStyle("-fx-font-size: " + fontSize + "px;");

        StackPane badge = new StackPane(label);
        badge.getStyleClass().add("fiat-currency-badge");
        badge.setMinSize(size, size);
        badge.setPrefSize(size, size);
        badge.setMaxSize(size, size);
        return badge;
    }

    public static StackPane getCurrencyIcon(String currencyCode, double size) {
        ImageView icon = getCurrencyIconImageView(currencyCode, size);
        if (icon == null) return null;
        StackPane pane = new StackPane(icon);
        pane.setMinSize(size, size);
        pane.setPrefSize(size, size);
        pane.setMaxSize(size, size);
        return pane;
    }

    // list icon: the logo fills `size` while its transparent anti-clip margin bleeds just outside those
    // bounds (the oversized ImageView overflows the size-clamped StackPane), so the full-bleed logo
    // renders at full size yet its antialiased edge is never clipped
    private static ImageView getCurrencyIconImageView(String currencyCode, double size) {
        if (currencyCode == null) return null;
        String imageId = getImageId(currencyCode);
        if (imageId == null) return null;
        double box = size + 2 * CURRENCY_ICON_MARGIN; // margin bleeds outside `size` instead of shrinking the logo
        ImageView icon = new ImageView();
        icon.setFitWidth(box);
        icon.setFitHeight(box);
        icon.setPreserveRatio(true);
        icon.setSmooth(true);
        double scale = Screen.getPrimary().getOutputScaleX();
        Image image = getCurrencyImage(currencyCode, currencyIconBoxPx(size, scale), currencyIconMarginPx(scale));
        if (image != null) icon.setImage(image);
        else icon.setId(imageId);
        return icon;
    }

    // device-pixel box and margin for a list icon; shared with the cache pre-warm so keys always match
    private static int currencyIconBoxPx(double size, double scale) {
        return (int) Math.ceil((size + 2 * CURRENCY_ICON_MARGIN) * scale);
    }

    private static int currencyIconMarginPx(double scale) {
        return (int) Math.ceil(CURRENCY_ICON_MARGIN * scale);
    }

    public static StackPane getCurrencyIconWithBorder(String currencyCode) {
        return getCurrencyIconWithBorder(currencyCode, 25, 1);
    }

    public static StackPane getCurrencyIconWithBorder(String currencyCode, double size, double borderWidth) {
        if (currencyCode == null) return null;

        ImageView icon = getCurrencyImageView(currencyCode, size);
        icon.setFitWidth(size - 2 * borderWidth);
        icon.setFitHeight(size - 2 * borderWidth);

        StackPane circleWrapper = new StackPane(icon);
        circleWrapper.setPrefSize(size, size);
        circleWrapper.setMaxSize(size, size);
        circleWrapper.setMinSize(size, size);

        circleWrapper.setStyle(
            "-fx-background-color: white;" +
            "-fx-background-radius: 50%;" +
            "-fx-border-radius: 50%;" +
            "-fx-border-color: white;" +
            "-fx-border-width: " + borderWidth + "px;"
        );

        StackPane.setAlignment(icon, Pos.CENTER);

        return circleWrapper;
    }

    private static String getImageId(String currencyCode) {
        if (currencyCode == null) return null;
        if (CurrencyUtil.isCryptoCurrency(currencyCode)) return "image-" + currencyCode.toLowerCase() + "-logo";
        if (CurrencyUtil.isFiatCurrency(currencyCode)) return "image-fiat-logo";
        return null;
    }

    public static void adjustHeightAutomatically(TextArea textArea) {
        adjustHeightAutomatically(textArea, null);
    }

    public static void adjustHeightAutomatically(TextArea textArea, Double maxHeight) {
        textArea.sceneProperty().addListener((o, oldScene, newScene) -> {
            if (newScene != null) {
                // avoid javafx css warning
                CssTheme.loadSceneStyles(newScene, CssTheme.getCurrentTheme(), false);
                textArea.applyCss();
                var text = textArea.lookup(".text");

                textArea.prefHeightProperty().bind(Bindings.createDoubleBinding(() -> {
                    Insets padding = textArea.getInsets();
                    double topBottomPadding = padding.getTop() + padding.getBottom();
                    double prefHeight = textArea.getFont().getSize() + text.getBoundsInLocal().getHeight() + topBottomPadding;
                    return maxHeight == null ? prefHeight : Math.min(prefHeight, maxHeight);
                }, text.boundsInLocalProperty()));

                text.boundsInLocalProperty().addListener((observableBoundsAfter, boundsBefore, boundsAfter) -> {
                    Platform.runLater(() -> textArea.requestLayout());
                });
            }
        });
    }

    public static Label getLockLabel() {
        MaterialDesignIconView lockIcon = new MaterialDesignIconView(MaterialDesignIcon.LOCK, "16px");
        lockIcon.setFill(Color.WHITE);
        Label lockLabel = new Label();
        lockLabel.setGraphic(lockIcon);
        return lockLabel;
    }

    public static MaterialDesignIconView getLockIcon(String glyphSize) {
        MaterialDesignIconView lockIcon = new MaterialDesignIconView(MaterialDesignIcon.LOCK, glyphSize);
        lockIcon.getStyleClass().add("lock-icon");
        return lockIcon;
    }

    private static final double PRIVATE_OFFER_LOCK_ICON_OPACITY = 0.7;

    public static void addPrivateOfferLockIcon(Label label) {
        addPrivateOfferLockIcon(label, "1.231em", PRIVATE_OFFER_LOCK_ICON_OPACITY);
    }

    public static void addCompactPrivateOfferLockIcon(Label label) {
        addPrivateOfferLockIcon(label, "1.2em", PRIVATE_OFFER_LOCK_ICON_OPACITY);
    }

    private static void addPrivateOfferLockIcon(Label label, String glyphSize, double opacity) {
        MaterialDesignIconView lockIcon = getLockIcon(glyphSize);
        lockIcon.setOpacity(opacity);
        label.setGraphic(lockIcon);
        label.setContentDisplay(ContentDisplay.RIGHT);
        label.setGraphicTextGap(6);
    }

    public static MaterialDesignIconView getCopyIcon() {
        return new MaterialDesignIconView(MaterialDesignIcon.CONTENT_COPY, "1.35em");
    }

    public static Tuple2<StackPane, ImageView> getSmallXmrQrCodePane() {
        return getXmrQrCodePane(150, disablePaymentUriLabel ? 32 : 28, 2);
    }

    public static Tuple2<StackPane, ImageView> getBigXmrQrCodePane() {
        return getXmrQrCodePane(250, disablePaymentUriLabel ? 47 : 45, 3);
    }

    private static Tuple2<StackPane, ImageView> getXmrQrCodePane(int qrCodeSize, int logoSize, int logoBorderWidth) {
        ImageView qrCodeImageView = new ImageView();
        qrCodeImageView.setFitHeight(qrCodeSize);
        qrCodeImageView.setFitWidth(qrCodeSize);
        qrCodeImageView.getStyleClass().add("qr-code");

        StackPane xmrLogo = GUIUtil.getCurrencyIconWithBorder(Res.getBaseCurrencyCode(), logoSize, logoBorderWidth);
        StackPane qrCodePane = new StackPane(qrCodeImageView, xmrLogo);
        qrCodePane.setCursor(Cursor.HAND);
        qrCodePane.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        return new Tuple2<>(qrCodePane, qrCodeImageView);
    }
}
