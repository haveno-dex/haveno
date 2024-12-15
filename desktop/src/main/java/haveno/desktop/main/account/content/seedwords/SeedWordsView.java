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

package haveno.desktop.main.account.content.seedwords;

import com.google.common.base.Splitter;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import haveno.common.config.Config;
import haveno.core.locale.Res;
import haveno.core.offer.OpenOfferManager;
import haveno.core.user.DontShowAgainLookup;
import haveno.core.xmr.wallet.WalletsManager;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.common.view.ActivatableView;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.main.SharedPresentation;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.overlays.windows.WalletPasswordWindow;
import static haveno.desktop.util.FormBuilder.addMultilineLabel;
import static haveno.desktop.util.FormBuilder.addTitledGroupBg;
import static haveno.desktop.util.FormBuilder.addTopLabelDatePicker;
import static haveno.desktop.util.FormBuilder.addTopLabelTextArea;
import haveno.desktop.util.Layout;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;
import java.util.List;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import net.glxn.qrgen.QRCode;
import net.glxn.qrgen.image.ImageType;
import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.crypto.MnemonicException;
import org.bitcoinj.wallet.DeterministicSeed;
//import static javafx.beans.binding.Bindings.createBooleanBinding;

@FxmlView
public class SeedWordsView extends ActivatableView<GridPane, Void> {
    private final WalletsManager walletsManager;
    private final OpenOfferManager openOfferManager;
    private final XmrWalletService xmrWalletService;
    private final WalletPasswordWindow walletPasswordWindow;
    private final File storageDir;

    private Button restoreButton;
    private TextArea displaySeedWordsTextArea, seedWordsTextArea;
    private DatePicker datePicker, restoreDatePicker;

    private int gridRow = 0;
    private ChangeListener<Boolean> seedWordsValidChangeListener;
    private final SimpleBooleanProperty seedWordsValid = new SimpleBooleanProperty(false);
    private ChangeListener<String> seedWordsTextAreaChangeListener;
    private final BooleanProperty seedWordsEdited = new SimpleBooleanProperty();
    private char[] seedWordText;
    private LocalDate walletCreationDate;

    private ImageView qrCodeImageView;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    private SeedWordsView(WalletsManager walletsManager,
                        OpenOfferManager openOfferManager,
                        XmrWalletService xmrWalletService,
                        WalletPasswordWindow walletPasswordWindow,
                        @Named(Config.STORAGE_DIR) File storageDir) {
        this.walletsManager = walletsManager;
        this.openOfferManager = openOfferManager;
        this.xmrWalletService = xmrWalletService;
        this.walletPasswordWindow = walletPasswordWindow;
        this.storageDir = storageDir;
    }

    @Override
    protected void initialize() {
        addTitledGroupBg(root, gridRow, 2, Res.get("account.seed.backup.title"));
        displaySeedWordsTextArea = addTopLabelTextArea(root, gridRow, Res.get("seed.seedWords"), "", Layout.FIRST_ROW_DISTANCE).second;
        displaySeedWordsTextArea.getStyleClass().add("wallet-seed-words");
        displaySeedWordsTextArea.setPrefHeight(70);
        displaySeedWordsTextArea.setMaxHeight(70);
        displaySeedWordsTextArea.setEditable(false);

        // Create a container for seed words and QR code
        HBox hBox = new HBox();
        qrCodeImageView = new ImageView();
        qrCodeImageView.setFitWidth(150);
        qrCodeImageView.setFitHeight(150);
        hBox.getChildren().add(displaySeedWordsTextArea);  // Seed words text
        hBox.getChildren().add(qrCodeImageView);           // QR code image view

        root.add(hBox, 0, ++gridRow, 2, 1);  // Add the HBox to the grid

        datePicker = addTopLabelDatePicker(root, ++gridRow, Res.get("seed.date"), 10).second;
        datePicker.setMouseTransparent(true);

        // TODO: to re-enable restore functionality:
        // - uncomment code throughout this file
        // - support getting wallet's restore height
        // - support translating between date and restore height
        // - clear XmrAddressEntries which are incompatible with new wallet and other tests
        // - update mnemonic validation and restore calls

        // addTitledGroupBg(root, ++gridRow, 3, Res.get("seed.restore.title"), Layout.GROUP_DISTANCE);
        // seedWordsTextArea = addTopLabelTextArea(root, gridRow, Res.get("seed.seedWords"), "", Layout.FIRST_ROW_AND_GROUP_DISTANCE).second;
        // seedWordsTextArea.getStyleClass().add("wallet-seed-words");
        // seedWordsTextArea.setPrefHeight(40);
        // seedWordsTextArea.setMaxHeight(40);

        // restoreDatePicker = addTopLabelDatePicker(root, ++gridRow, Res.get("seed.date"), 10).second;
        // restoreButton = addPrimaryActionButtonAFterGroup(root, ++gridRow, Res.get("seed.restore"));

        addTitledGroupBg(root, ++gridRow, 1, Res.get("shared.information"), Layout.GROUP_DISTANCE);
        addMultilineLabel(root, gridRow, Res.get("account.seed.info"),
        Layout.FIRST_ROW_AND_GROUP_DISTANCE);

        seedWordsValidChangeListener = (observable, oldValue, newValue) -> {
            if (newValue) {
                seedWordsTextArea.getStyleClass().remove("validation-error");
            } else {
                seedWordsTextArea.getStyleClass().add("validation-error");
            }
        };

        seedWordsTextAreaChangeListener = (observable, oldValue, newValue) -> {
            seedWordsEdited.set(true);
            try {
                MnemonicCode codec = new MnemonicCode();
                codec.check(Splitter.on(" ").splitToList(newValue));
                seedWordsValid.set(true);
            } catch (IOException | MnemonicException e) {
                seedWordsValid.set(false);
            }
        };
    }

    @Override
    public void activate() {
        // seedWordsValid.addListener(seedWordsValidChangeListener);
        // seedWordsTextArea.textProperty().addListener(seedWordsTextAreaChangeListener);
        // restoreButton.disableProperty().bind(createBooleanBinding(() -> !seedWordsValid.get() || !seedWordsEdited.get(),
        //         seedWordsValid, seedWordsEdited));

        // restoreButton.setOnAction(e -> {
        //     new Popup().information(Res.get("account.seed.restore.info"))
        //             .closeButtonText(Res.get("shared.cancel"))
        //             .actionButtonText(Res.get("account.seed.restore.ok"))
        //             .onAction(this::onRestore)
        //             .show();
        // });

        // seedWordsTextArea.getStyleClass().remove("validation-error");
        // restoreDatePicker.getStyleClass().remove("validation-error");

        String key = "showBackupWarningAtSeedPhrase";
        if (DontShowAgainLookup.showAgain(key)) {
            new Popup().warning(Res.get("account.seed.backup.warning"))
                    .onAction(this::showSeedPhrase)
                    .actionButtonText(Res.get("shared.iUnderstand"))
                    .useIUnderstandButton()
                    .dontShowAgainId(key)
                    .hideCloseButton()
                    .show();
        } else {
            showSeedPhrase();
        }
    }

    private void showSeedPhrase() {
        if (xmrWalletService.isWalletEncrypted()) {
            askForPassword();
        } else {
            String key = "showSeedWordsWarning";
            if (DontShowAgainLookup.showAgain(key)) {
                new Popup().warning(Res.get("account.seed.warn.noPw.msg"))
                        .actionButtonText(Res.get("account.seed.warn.noPw.yes"))
                        .onAction(() -> {
                            DontShowAgainLookup.dontShowAgain(key, true);
                            initSeedWords(xmrWalletService.getWallet().getSeed());
                            Platform.runLater(this::showSeedScreen);
                        })
                        .closeButtonText(Res.get("shared.no"))
                        .show();
            } else {
                initSeedWords(xmrWalletService.getWallet().getSeed());
                Platform.runLater(this::showSeedScreen);
            }
        }
    }

    @Override
    protected void deactivate() {
        clearSensitiveData();
        datePicker.setValue(null);

        // seedWordsValid.removeListener(seedWordsValidChangeListener);
        // seedWordsTextArea.textProperty().removeListener(seedWordsTextAreaChangeListener);
        // restoreButton.disableProperty().unbind();
        // restoreButton.setOnAction(null);

        // seedWordsTextArea.setText("");

        // restoreDatePicker.setValue(null);

        // seedWordsTextArea.getStyleClass().remove("validation-error");
        // restoreDatePicker.getStyleClass().remove("validation-error");
    }

    private void clearSensitiveData() {
        if (seedWordText != null) {
            // Overwrite seed words in memory before clearing
            java.util.Arrays.fill(seedWordText, ' ');
        }
    }

    private void generateAndDisplayQRCode(String seedWords) {
        Platform.runLater(() -> {
            // Generate QR Code using the net.glxn.qrgen library
            ByteArrayInputStream qrCodeStream = new ByteArrayInputStream(
                QRCode.from(seedWords).to(ImageType.PNG).stream().toByteArray()
            );
            Image qrCodeImage = new Image(qrCodeStream);
            qrCodeImageView.setImage(qrCodeImage);
        });
    }

    private void askForPassword() {
        walletPasswordWindow.headLine(Res.get("account.seed.enterPw")).onSuccess(() -> {
            initSeedWords(xmrWalletService.getWallet().getSeed());
            Platform.runLater(this::showSeedScreen);
        }).hideForgotPasswordButton().show();
    }

    private void initSeedWords(String seed) {
        seedWordText = seed.toCharArray();
    }

    private void showSeedScreen() {
        displaySeedWordsTextArea.setText(new String(seedWordText));
        walletCreationDate = Instant.ofEpochSecond(xmrWalletService.getWalletCreationDate()).atZone(ZoneId.systemDefault()).toLocalDate();
        datePicker.setValue(walletCreationDate);
        generateAndDisplayQRCode(new String(seedWordText));
    }

    private void onRestore() {
        if (walletsManager.hasPositiveBalance()) {
            new Popup().warning(Res.get("seed.warn.walletNotEmpty.msg"))
                    .actionButtonText(Res.get("seed.warn.walletNotEmpty.restore"))
                    .onAction(this::checkIfEncrypted)
                    .closeButtonText(Res.get("seed.warn.walletNotEmpty.emptyWallet"))
                    .show();
        } else {
            checkIfEncrypted();
        }
    }

    private void checkIfEncrypted() {
        if (walletsManager.areWalletsEncrypted()) {
            new Popup().information(Res.get("seed.warn.notEncryptedAnymore"))
                    .closeButtonText(Res.get("shared.no"))
                    .actionButtonText(Res.get("shared.yes"))
                    .onAction(this::doRestoreDateCheck)
                    .show();
        } else {
            doRestoreDateCheck();
        }
    }

    private void doRestoreDateCheck() {
        if (restoreDatePicker.getValue() == null) {
            // Provide feedback when attempting to restore a wallet from seed words without specifying a date
            new Popup().information(Res.get("seed.warn.walletDateEmpty"))
                    .closeButtonText(Res.get("shared.no"))
                    .actionButtonText(Res.get("shared.yes"))
                    .onAction(this::doRestore)
                    .show();
        } else {
            doRestore();
        }
    }

    private LocalDate getWalletDate() {
        LocalDate walletDate = restoreDatePicker.getValue();
        // Even though no current Haveno wallet could have been created before the v0.5 release date (2017.06.28),
        // the user may want to import from a seed generated by another wallet.
        // So use when the BIP39 standard was finalised (2013.10.09) as the oldest possible wallet date.
        LocalDate oldestWalletDate = LocalDate.ofInstant(
                Instant.ofEpochMilli(MnemonicCode.BIP39_STANDARDISATION_TIME_SECS * 1000),
                TimeZone.getDefault().toZoneId());
        if (walletDate == null) {
            // No date was specified, perhaps the user doesn't know the wallet date
            walletDate = oldestWalletDate;
        } else if (walletDate.isBefore(oldestWalletDate)) {
            walletDate = oldestWalletDate;
        } else if (walletDate.isAfter(LocalDate.now())) {
            walletDate = LocalDate.now();
        }
        return walletDate;
    }

    private void doRestore() {
        LocalDate walletDate = getWalletDate();
        // We subtract 1 day to be sure to not have any issues with timezones. Even if we can be sure that the timezone
        // is handled correctly it could be that the user created the wallet in one timezone and make a restore at
        // a different timezone which could lead in the worst case that he miss the first day of the wallet transactions.
        LocalDateTime localDateTime = walletDate.atStartOfDay().minusDays(1);
        long date = localDateTime.toEpochSecond(ZoneOffset.UTC);

        DeterministicSeed seed = new DeterministicSeed(List.of(new String(seedWordText).split(" ")), null, "", date);
        SharedPresentation.restoreSeedWords(walletsManager, openOfferManager, seed, storageDir);
    }
}
