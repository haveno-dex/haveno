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

package haveno.desktop.components;

import com.jfoenix.controls.JFXTextField;
import de.jensd.fx.fontawesome.AwesomeDude;
import de.jensd.fx.fontawesome.AwesomeIcon;
import haveno.common.UserThread;
import haveno.common.util.Utilities;
import haveno.core.locale.Res;
import haveno.core.user.BlockChainExplorer;
import haveno.core.user.Preferences;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.components.indicator.TxConfidenceIndicator;
import haveno.desktop.util.GUIUtil;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.AnchorPane;
import lombok.Getter;
import lombok.Setter;
import monero.daemon.model.MoneroTx;
import monero.wallet.model.MoneroWalletListener;

import javax.annotation.Nullable;

public class TxIdTextField extends AnchorPane {
    @Setter
    private static Preferences preferences;
    @Setter
    private static XmrWalletService xmrWalletService;

    @Getter
    private final TextField textField;
    private final Tooltip progressIndicatorTooltip;
    private final TxConfidenceIndicator txConfidenceIndicator;
    private final Label copyIcon, blockExplorerIcon, missingTxWarningIcon;

    private MoneroWalletListener txUpdater;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public TxIdTextField() {
        txConfidenceIndicator = new TxConfidenceIndicator();
        txConfidenceIndicator.setFocusTraversable(false);
        txConfidenceIndicator.setMaxSize(20, 20);
        txConfidenceIndicator.setId("funds-confidence");
        txConfidenceIndicator.setLayoutY(1);
        txConfidenceIndicator.setProgress(0);
        txConfidenceIndicator.setVisible(false);
        AnchorPane.setRightAnchor(txConfidenceIndicator, 0.0);
        AnchorPane.setTopAnchor(txConfidenceIndicator, 3.0);
        progressIndicatorTooltip = new Tooltip("-");
        txConfidenceIndicator.setTooltip(progressIndicatorTooltip);

        copyIcon = new Label();
        copyIcon.setLayoutY(3);
        copyIcon.getStyleClass().addAll("icon", "highlight");
        copyIcon.setTooltip(new Tooltip(Res.get("txIdTextField.copyIcon.tooltip")));
        AwesomeDude.setIcon(copyIcon, AwesomeIcon.COPY);
        AnchorPane.setRightAnchor(copyIcon, 30.0);

        Tooltip tooltip = new Tooltip(Res.get("txIdTextField.blockExplorerIcon.tooltip"));

        blockExplorerIcon = new Label();
        blockExplorerIcon.getStyleClass().addAll("icon", "highlight");
        blockExplorerIcon.setTooltip(tooltip);
        AwesomeDude.setIcon(blockExplorerIcon, AwesomeIcon.EXTERNAL_LINK);
        blockExplorerIcon.setMinWidth(20);
        AnchorPane.setRightAnchor(blockExplorerIcon, 52.0);
        AnchorPane.setTopAnchor(blockExplorerIcon, 4.0);

        missingTxWarningIcon = new Label();
        missingTxWarningIcon.getStyleClass().addAll("icon", "error-icon");
        AwesomeDude.setIcon(missingTxWarningIcon, AwesomeIcon.WARNING_SIGN);
        missingTxWarningIcon.setTooltip(new Tooltip(Res.get("txIdTextField.missingTx.warning.tooltip")));
        missingTxWarningIcon.setMinWidth(20);
        AnchorPane.setRightAnchor(missingTxWarningIcon, 52.0);
        AnchorPane.setTopAnchor(missingTxWarningIcon, 4.0);
        missingTxWarningIcon.setVisible(false);
        missingTxWarningIcon.setManaged(false);

        textField = new JFXTextField();
        textField.setId("address-text-field");
        textField.setEditable(false);
        textField.setTooltip(tooltip);
        AnchorPane.setRightAnchor(textField, 80.0);
        AnchorPane.setLeftAnchor(textField, 0.0);
        textField.focusTraversableProperty().set(focusTraversableProperty().get());
        getChildren().addAll(textField, missingTxWarningIcon, blockExplorerIcon, copyIcon, txConfidenceIndicator);
    }

    public void setup(@Nullable String txId) {
        if (txUpdater != null) {
            xmrWalletService.removeWalletListener(txUpdater);
            txUpdater = null;
        }

        if (txId == null) {
            textField.setText(Res.get("shared.na"));
            textField.setId("address-text-field-error");
            blockExplorerIcon.setVisible(false);
            blockExplorerIcon.setManaged(false);
            copyIcon.setVisible(false);
            copyIcon.setManaged(false);
            txConfidenceIndicator.setVisible(false);
            missingTxWarningIcon.setVisible(true);
            missingTxWarningIcon.setManaged(true);
            return;
        }

        // listen for tx updates
        // TODO: this only listens for new blocks, listen for double spend
        txUpdater = new MoneroWalletListener() {
            @Override
            public void onNewBlock(long lastBlockHeight) {
                updateConfidence(txId, false, lastBlockHeight + 1);
            }
        };
        xmrWalletService.addWalletListener(txUpdater);

        textField.setText(txId);
        textField.setOnMouseClicked(mouseEvent -> openBlockExplorer(txId));
        blockExplorerIcon.setOnMouseClicked(mouseEvent -> openBlockExplorer(txId));
        copyIcon.setOnMouseClicked(e -> Utilities.copyToClipboard(txId));
        txConfidenceIndicator.setVisible(true);

        // update off main thread
        new Thread(() -> updateConfidence(txId, true, null)).start();
    }

    public void cleanup() {
        if (xmrWalletService != null && txUpdater != null) {
            xmrWalletService.removeWalletListener(txUpdater);
            txUpdater = null;
        }
        textField.setOnMouseClicked(null);
        blockExplorerIcon.setOnMouseClicked(null);
        copyIcon.setOnMouseClicked(null);
        textField.setText("");
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void openBlockExplorer(String txId) {
        if (preferences != null) {
            BlockChainExplorer blockChainExplorer = preferences.getBlockChainExplorer();
            GUIUtil.openWebPage(blockChainExplorer.txUrl + txId, false);
        }
    }

    private synchronized void updateConfidence(String txId, boolean useCache, Long height) {
        MoneroTx tx = null;
        try {
            tx = useCache ? xmrWalletService.getTxWithCache(txId) : xmrWalletService.getTx(txId);
            tx.setNumConfirmations(tx.isConfirmed() ? (height == null ? xmrWalletService.getConnectionsService().getLastInfo().getHeight() : height) - tx.getHeight(): 0l); // TODO: don't set if tx.getNumConfirmations() works reliably on non-local testnet
        } catch (Exception e) {
            // do nothing
        }
        updateConfidence(tx);
    }

    private void updateConfidence(MoneroTx tx) {
        UserThread.execute(() -> {
            GUIUtil.updateConfidence(tx, progressIndicatorTooltip, txConfidenceIndicator);
            if (txConfidenceIndicator.getProgress() != 0) {
                AnchorPane.setRightAnchor(txConfidenceIndicator, 0.0);
            }
            if (txConfidenceIndicator.getProgress() >= 1.0 && txUpdater != null) {
                xmrWalletService.removeWalletListener(txUpdater); // unregister listener
                txUpdater = null;
            }
        });
    }
}
