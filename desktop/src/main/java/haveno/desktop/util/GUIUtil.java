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
import com.googlecode.jcsv.CSVStrategy;
import com.googlecode.jcsv.writer.CSVEntryConverter;
import com.googlecode.jcsv.writer.CSVWriter;
import com.googlecode.jcsv.writer.internal.CSVWriterBuilder;
import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
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
import haveno.core.locale.CurrencyUtil;
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
import haveno.desktop.components.HavenoTextArea;
import haveno.desktop.components.InfoAutoTooltipLabel;
import haveno.desktop.components.indicator.TxConfidenceIndicator;
import haveno.desktop.main.MainView;
import haveno.desktop.main.account.AccountView;
import haveno.desktop.main.account.content.traditionalaccounts.TraditionalAccountsView;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.network.p2p.P2PService;
import javafx.collections.FXCollections;
import javafx.geometry.HPos;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.shape.Rectangle;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Callback;
import javafx.util.StringConverter;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroUtils;
import monero.daemon.model.MoneroTx;
import monero.wallet.model.MoneroTxConfig;
import org.apache.commons.lang3.StringUtils;
import org.bitcoinj.core.Coin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

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


    public static <T> void exportCSV(String fileName, CSVEntryConverter<T> headerConverter,
                                     CSVEntryConverter<T> contentConverter, T emptyItem,
                                     List<T> list, Stage stage) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setInitialFileName(fileName);
        File file = fileChooser.showSaveDialog(stage);
        if (file != null) {
            try (OutputStreamWriter outputStreamWriter = new OutputStreamWriter(new FileOutputStream(file, false), Charsets.UTF_8)) {
                CSVWriter<T> headerWriter = new CSVWriterBuilder<T>(outputStreamWriter)
                        .strategy(CSVStrategy.UK_DEFAULT)
                        .entryConverter(headerConverter)
                        .build();
                headerWriter.write(emptyItem);

                CSVWriter<T> contentWriter = new CSVWriterBuilder<T>(outputStreamWriter)
                        .strategy(CSVStrategy.UK_DEFAULT)
                        .entryConverter(contentConverter)
                        .build();
                contentWriter.writeAll(list);
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
                                                                                                                  String postFixMulti,
                                                                                                                  Preferences preferences) {
        return p -> new ListCell<>() {
            @Override
            protected void updateItem(CurrencyListItem item, boolean empty) {
                super.updateItem(item, empty);

                if (item != null && !empty) {

                    String code = item.tradeCurrency.getCode();

                    HBox box = new HBox();
                    box.setSpacing(20);
                    Label currencyType = new AutoTooltipLabel(
                            CurrencyUtil.isTraditionalCurrency(code) ? Res.get("shared.traditional") : Res.get("shared.crypto"));

                    currencyType.getStyleClass().add("currency-label-small");
                    Label currency = new AutoTooltipLabel(code);
                    currency.getStyleClass().add("currency-label");
                    Label offers = new AutoTooltipLabel(item.tradeCurrency.getName());
                    offers.getStyleClass().add("currency-label");

                    box.getChildren().addAll(currencyType, currency, offers);

                    switch (code) {
                        case GUIUtil.SHOW_ALL_FLAG:
                            currencyType.setText(Res.get("shared.all"));
                            currency.setText(Res.get("list.currency.showAll"));
                            break;
                        case GUIUtil.EDIT_FLAG:
                            currencyType.setText(Res.get("shared.edit"));
                            currency.setText(Res.get("list.currency.editList"));
                            break;
                        default:
                            if (preferences.isSortMarketCurrenciesNumerically()) {
                                offers.setText(offers.getText() + " (" + item.numTrades + " " +
                                        (item.numTrades == 1 ? postFixSingle : postFixMulti) + ")");
                            }
                    }

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
                    Label currency = new AutoTooltipLabel(code + " - " + item.getName());
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

    public static StringConverter<TradeCurrency> getTradeCurrencyConverter(String postFixSingle,
                                                                           String postFixMulti,
                                                                           Map<String, Integer> offerCounts) {
        return new StringConverter<>() {
            @Override
            public String toString(TradeCurrency tradeCurrency) {
                String code = tradeCurrency.getCode();
                Optional<Integer> offerCountOptional = Optional.ofNullable(offerCounts.get(code));
                final String displayString;
                displayString = offerCountOptional
                        .map(offerCount -> CurrencyUtil.getNameAndCode(code)
                                + " - " + offerCount + " " + (offerCount == 1 ? postFixSingle : postFixMulti))
                        .orElseGet(() -> CurrencyUtil.getNameAndCode(code));
                // http://boschista.deviantart.com/journal/Cool-ASCII-Symbols-214218618
                if (code.equals(GUIUtil.SHOW_ALL_FLAG))
                    return "▶ " + Res.get("list.currency.showAll");
                else if (code.equals(GUIUtil.EDIT_FLAG))
                    return "▼ " + Res.get("list.currency.editList");
                return tradeCurrency.getDisplayPrefix() + displayString;
            }

            @Override
            public TradeCurrency fromString(String s) {
                return null;
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

                    HBox box = new HBox();
                    box.setSpacing(20);

                    Label label = new AutoTooltipLabel(
                        CurrencyUtil.isTraditionalCurrency(item.getCode()) ? Res.get("shared.traditional") : Res.get("shared.crypto"));
                    label.getStyleClass().add("currency-label-small");
                    Label currency = new AutoTooltipLabel(item.getCode());
                    currency.getStyleClass().add("currency-label");
                    Label offers = new AutoTooltipLabel(item.getName());
                    offers.getStyleClass().add("currency-label");

                    Optional<Integer> offerCountOptional = Optional.ofNullable(offerCounts.get(code));

                    switch (code) {
                        case GUIUtil.SHOW_ALL_FLAG:
                            label.setText(Res.get("shared.all"));
                            currency.setText(Res.get("list.currency.showAll"));
                            break;
                        case GUIUtil.EDIT_FLAG:
                            label.setText(Res.get("shared.edit"));
                            currency.setText(Res.get("list.currency.editList"));
                            break;
                        default:

                            // use icons for crypto
                            if (CurrencyUtil.isCryptoCurrency(item.getCode())) {
                                ImageView iconView = new ImageView();
                                iconView.setId("image-" + item.getCode().toLowerCase() + "-logo");
                                iconView.setFitHeight(24);
                                iconView.setFitWidth(24);
                                iconView.setSmooth(true);
                                label.setText("");
                                label.setGraphic(iconView);
                            }
                            offerCountOptional.ifPresent(numOffer -> offers.setText(offers.getText() + " (" + numOffer + " " +
                                    (numOffer == 1 ? postFixSingle : postFixMulti) + ")"));
                    }

                    box.getChildren().addAll(label, currency, offers);

                    setGraphic(box);

                } else {
                    setGraphic(null);
                }
            }
        };
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
        return p -> new ListCell<>() {
            @Override
            protected void updateItem(PaymentMethod method, boolean empty) {
                super.updateItem(method, empty);

                if (method != null && !empty) {
                    String id = method.getId();

                    HBox box = new HBox();
                    box.setSpacing(20);
                    Label paymentType = new AutoTooltipLabel(
                            method.isTraditional() ? Res.get("shared.traditional") : Res.get("shared.crypto"));

                    paymentType.getStyleClass().add("currency-label-small");
                    Label paymentMethod = new AutoTooltipLabel(Res.get(id));
                    paymentMethod.getStyleClass().add("currency-label");
                    box.getChildren().addAll(paymentType, paymentMethod);

                    if (id.equals(GUIUtil.SHOW_ALL_FLAG)) {
                        paymentType.setText(Res.get("shared.all"));
                        paymentMethod.setText(Res.get("list.currency.showAll"));
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
                tooltip.setText(Res.get("confidence.confirmed", ">=10"));
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
            Utilities.openURI(new URI(target));
        } catch (Exception e) {
            e.printStackTrace();
            log.error(e.getMessage());
        }
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
        return MoneroUtils.getPaymentUri(new MoneroTxConfig()
                .setAddress(address)
                .setAmount(amount)
                .setNote(label));
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
        TextArea textArea = new HavenoTextArea();
        textArea.setText(text);
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setPrefSize(800, 600);

        Scene scene = new Scene(textArea);
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

    public static void applyRoundedArc(TableView<?> tableView) {
        Rectangle clip = new Rectangle();
        clip.setArcWidth(Layout.ROUNDED_ARC);
        clip.setArcHeight(Layout.ROUNDED_ARC);
        tableView.setClip(clip);
        tableView.layoutBoundsProperty().addListener((obs, oldVal, newVal) -> {
            clip.setWidth(newVal.getWidth());
            clip.setHeight(newVal.getHeight());
        });
    }

    public static void initFilledStyle(TextField textField) {
        textField.textProperty().addListener((observable, oldValue, newValue) -> {
            setFilledStyle(textField);
        });
    }

    private static void setFilledStyle(TextField textField) {
        if (textField.getText() != null && !textField.getText().isEmpty()) {
            if (!textField.getStyleClass().contains("filled")) {
                textField.getStyleClass().add("filled");
            }
        } else {
            textField.getStyleClass().remove("filled");
        }
    }

    public static void initFilledStyle(ComboBox<?> comboBox) {
        comboBox.valueProperty().addListener((observable, oldValue, newValue) -> {
            setFilledStyle(comboBox);
        });
    }
    
    private static void setFilledStyle(ComboBox<?> comboBox) {
        if (comboBox.getValue() != null) {
            if (!comboBox.getStyleClass().contains("filled")) {
                comboBox.getStyleClass().add("filled");
            }
        } else {
            comboBox.getStyleClass().remove("filled");
        }
    }
}
