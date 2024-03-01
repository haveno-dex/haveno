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

package haveno.core.app;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import haveno.common.UserThread;
import haveno.common.config.Config;
import haveno.core.api.CoreContext;
import haveno.core.api.XmrConnectionService;
import haveno.core.locale.Res;
import haveno.core.offer.OpenOfferManager;
import haveno.core.trade.TradeManager;
import haveno.core.user.Preferences;
import haveno.core.user.Preferences.UseTorForXmr;
import haveno.core.util.FormattingUtils;
import haveno.core.xmr.exceptions.InvalidHostException;
import haveno.core.xmr.exceptions.RejectedTxException;
import haveno.core.xmr.setup.WalletsSetup;
import haveno.core.xmr.wallet.WalletsManager;
import haveno.core.xmr.wallet.XmrWalletService;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroUtils;
import org.bitcoinj.core.RejectMessage;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.ChainFileLockedException;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.monadic.MonadicBinding;

@Slf4j
@Singleton
public class WalletAppSetup {

    private final CoreContext coreContext;
    private final WalletsManager walletsManager;
    private final WalletsSetup walletsSetup;
    private final XmrConnectionService xmrConnectionService;
    private final XmrWalletService xmrWalletService;
    private final Config config;
    private final Preferences preferences;

    @SuppressWarnings("FieldCanBeLocal")
    private MonadicBinding<String> xmrInfoBinding;

    @Getter
    private final DoubleProperty xmrDaemonSyncProgress = new SimpleDoubleProperty(-1);
    @Getter
    private final DoubleProperty xmrWalletSyncProgress = new SimpleDoubleProperty(-1);
    @Getter
    private final StringProperty walletServiceErrorMsg = new SimpleStringProperty();
    @Getter
    private final StringProperty xmrSplashSyncIconId = new SimpleStringProperty();
    @Getter
    private final StringProperty xmrInfo = new SimpleStringProperty(Res.get("mainView.footer.xmrInfo.initializing"));
    @Getter
    private final ObjectProperty<RejectedTxException> rejectedTxException = new SimpleObjectProperty<>();
    @Getter
    private final ObjectProperty<UseTorForXmr> useTorForXmr = new SimpleObjectProperty<UseTorForXmr>();

    @Inject
    public WalletAppSetup(CoreContext coreContext,
                          WalletsManager walletsManager,
                          WalletsSetup walletsSetup,
                          XmrConnectionService xmrConnectionService,
                          XmrWalletService xmrWalletService,
                          Config config,
                          Preferences preferences) {
        this.coreContext = coreContext;
        this.walletsManager = walletsManager;
        this.walletsSetup = walletsSetup;
        this.xmrConnectionService = xmrConnectionService;
        this.xmrWalletService = xmrWalletService;
        this.config = config;
        this.preferences = preferences;
        this.useTorForXmr.set(preferences.getUseTorForXmr());
    }

    void init(@Nullable Consumer<String> chainFileLockedExceptionHandler,
              @Nullable Runnable showFirstPopupIfResyncSPVRequestedHandler,
              @Nullable Runnable showPopupIfInvalidBtcConfigHandler,
              Runnable downloadCompleteHandler,
              Runnable walletInitializedHandler) {
        log.info("Initialize WalletAppSetup with monero-java version {}", MoneroUtils.getVersion());

        ObjectProperty<Throwable> walletServiceException = new SimpleObjectProperty<>();
        xmrInfoBinding = EasyBind.combine(
                xmrConnectionService.numUpdatesProperty(), // receives notification of any connection update
                xmrWalletService.downloadPercentageProperty(),
                xmrWalletService.walletHeightProperty(),
                walletServiceException,
                getWalletServiceErrorMsg(),
                (numConnectionUpdates, walletDownloadPercentage, walletHeight, exception, errorMsg) -> {
                    String result;
                    if (exception == null && errorMsg == null) {

                        // update wallet sync progress
                        double walletDownloadPercentageD = (double) walletDownloadPercentage;
                        xmrWalletSyncProgress.set(walletDownloadPercentageD);
                        Long bestWalletHeight = walletHeight == null ? null : (Long) walletHeight;
                        String walletHeightAsString = bestWalletHeight != null && bestWalletHeight > 0 ? String.valueOf(bestWalletHeight) : "";
                        if (walletDownloadPercentageD == 1) {
                            String synchronizedWith = Res.get("mainView.footer.xmrInfo.syncedWith", getXmrWalletNetworkAsString(), walletHeightAsString);
                            String feeInfo = ""; // TODO: feeService.isFeeAvailable() returns true, disable
                            result = Res.get("mainView.footer.xmrInfo", synchronizedWith, feeInfo);
                            getXmrSplashSyncIconId().set("image-connection-synced");
                            downloadCompleteHandler.run();
                        } else if (walletDownloadPercentageD > 0) {
                            String synchronizingWith = Res.get("mainView.footer.xmrInfo.synchronizingWalletWith", getXmrWalletNetworkAsString(), walletHeightAsString, FormattingUtils.formatToRoundedPercentWithSymbol(walletDownloadPercentageD));
                            result = Res.get("mainView.footer.xmrInfo", synchronizingWith, "");
                            getXmrSplashSyncIconId().set(""); // clear synced icon
                        } else {

                            // update daemon sync progress
                            double chainDownloadPercentageD = xmrConnectionService.downloadPercentageProperty().doubleValue();
                            xmrDaemonSyncProgress.set(chainDownloadPercentageD);
                            Long bestChainHeight = xmrConnectionService.chainHeightProperty().get();
                            String chainHeightAsString = bestChainHeight != null && bestChainHeight > 0 ? String.valueOf(bestChainHeight) : "";
                            if (chainDownloadPercentageD == 1) {
                                String synchronizedWith = Res.get("mainView.footer.xmrInfo.connectedTo", getXmrDaemonNetworkAsString(), chainHeightAsString);
                                String feeInfo = ""; // TODO: feeService.isFeeAvailable() returns true, disable
                                result = Res.get("mainView.footer.xmrInfo", synchronizedWith, feeInfo);
                                getXmrSplashSyncIconId().set("image-connection-synced");
                            } else if (chainDownloadPercentageD > 0.0) {
                                String synchronizingWith = Res.get("mainView.footer.xmrInfo.synchronizingWith", getXmrDaemonNetworkAsString(), chainHeightAsString, FormattingUtils.formatToRoundedPercentWithSymbol(chainDownloadPercentageD));
                                result = Res.get("mainView.footer.xmrInfo", synchronizingWith, "");
                            } else {
                                result = Res.get("mainView.footer.xmrInfo",
                                        Res.get("mainView.footer.xmrInfo.connectingTo"),
                                        getXmrDaemonNetworkAsString());
                            }
                        }
                    } else {
                        result = Res.get("mainView.footer.xmrInfo",
                                Res.get("mainView.footer.xmrInfo.connectionFailed"),
                                getXmrDaemonNetworkAsString());
                        if (exception != null) {
                            if (exception instanceof TimeoutException) {
                                getWalletServiceErrorMsg().set(Res.get("mainView.walletServiceErrorMsg.timeout"));
                            } else if (exception.getCause() instanceof BlockStoreException) {
                                if (exception.getCause().getCause() instanceof ChainFileLockedException && chainFileLockedExceptionHandler != null) {
                                    chainFileLockedExceptionHandler.accept(Res.get("popup.warning.startupFailed.twoInstances"));
                                }
                            } else if (exception instanceof RejectedTxException) {
                                rejectedTxException.set((RejectedTxException) exception);
                                getWalletServiceErrorMsg().set(Res.get("mainView.walletServiceErrorMsg.rejectedTxException", exception.getMessage()));
                            } else {
                                getWalletServiceErrorMsg().set(Res.get("mainView.walletServiceErrorMsg.connectionError", exception.getMessage()));
                            }
                        }
                    }
                    return result;
                });
        xmrInfoBinding.subscribe((observable, oldValue, newValue) -> UserThread.execute(() -> xmrInfo.set(newValue)));

        walletsSetup.initialize(null,
                () -> {
                    walletInitializedHandler.run();
                },
                exception -> {
                    if (exception instanceof InvalidHostException && showPopupIfInvalidBtcConfigHandler != null) {
                        showPopupIfInvalidBtcConfigHandler.run();
                    } else {
                        walletServiceException.set(exception);
                    }
                });
    }

    void setRejectedTxErrorMessageHandler(Consumer<String> rejectedTxErrorMessageHandler,
                                          OpenOfferManager openOfferManager,
                                          TradeManager tradeManager) {
        getRejectedTxException().addListener((observable, oldValue, newValue) -> {
            if (newValue == null || newValue.getTxId() == null) {
                return;
            }

            RejectMessage rejectMessage = newValue.getRejectMessage();
            log.warn("We received reject message: {}", rejectMessage);

            // TODO: Find out which reject messages are critical and which not.
            // We got a report where a "tx already known" message caused a failed trade but the deposit tx was valid.
            // To avoid such false positives we only handle reject messages which we consider clearly critical.

            switch (rejectMessage.getReasonCode()) {
                case OBSOLETE:
                case DUPLICATE:
                case NONSTANDARD:
                case CHECKPOINT:
                case OTHER:
                    // We ignore those cases to avoid that not critical reject messages trigger a failed trade.
                    log.warn("We ignore that reject message as it is likely not critical.");
                    break;
                case MALFORMED:
                case INVALID:
                case DUST:
                case INSUFFICIENTFEE:
                    // We delay as we might get the rejected tx error before we have completed the create offer protocol
                    log.warn("We handle that reject message as it is likely critical.");
                    UserThread.runAfter(() -> {
                        String txId = newValue.getTxId();

                        tradeManager.getObservableList().stream()
                                .filter(trade -> trade.getOffer() != null)
                                .forEach(trade -> {
                                    String details = null;
                                    if (txId.equals(trade.getMaker().getDepositTxHash())) {
                                        details = Res.get("popup.warning.trade.txRejected.deposit");  // TODO (woodser): txRejected.maker_deposit, txRejected.taker_deposit
                                    }
                                    if (txId.equals(trade.getTaker().getDepositTxHash())) {
                                      details = Res.get("popup.warning.trade.txRejected.deposit");
                                    }
                                    if (details != null) {
                                        // We delay to avoid concurrent modification exceptions
                                        String finalDetails = details;
                                        UserThread.runAfter(() -> {
                                            trade.setErrorMessage(newValue.getMessage());
                                            tradeManager.requestPersistence();
                                            if (rejectedTxErrorMessageHandler != null) {
                                                rejectedTxErrorMessageHandler.accept(Res.get("popup.warning.trade.txRejected",
                                                        finalDetails, trade.getShortId(), txId));
                                            }
                                        }, 1);
                                    }
                                });
                    }, 3);
            }
        });
    }

    private String getXmrDaemonNetworkAsString() {
        String postFix;
        if (xmrConnectionService.isConnectionLocal())
            postFix = " " + Res.get("mainView.footer.localhostMoneroNode");
        else if (xmrConnectionService.isConnectionTor())
            postFix = " " + Res.get("mainView.footer.usingTor");
        else
            postFix = "";
        return Res.get(config.baseCurrencyNetwork.name()) + postFix;
    }

    private String getXmrWalletNetworkAsString() {
        String postFix;
        if (xmrConnectionService.isConnectionLocal())
            postFix = " " + Res.get("mainView.footer.localhostMoneroNode");
        else if (xmrWalletService.isProxyApplied())
            postFix = " " + Res.get("mainView.footer.usingTor");
        else
            postFix = " " + Res.get("mainView.footer.clearnet");
        return Res.get(config.baseCurrencyNetwork.name()) + postFix;
    }
}
