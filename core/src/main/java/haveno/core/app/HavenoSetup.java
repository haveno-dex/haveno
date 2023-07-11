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

import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.app.DevEnv;
import haveno.common.app.Version;
import haveno.common.config.BaseCurrencyNetwork;
import haveno.common.config.Config;
import haveno.common.file.FileUtil;
import haveno.common.util.InvalidVersionException;
import haveno.common.util.Utilities;
import haveno.core.account.sign.SignedWitness;
import haveno.core.account.sign.SignedWitnessStorageService;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.alert.Alert;
import haveno.core.alert.AlertManager;
import haveno.core.alert.PrivateNotificationManager;
import haveno.core.alert.PrivateNotificationPayload;
import haveno.core.api.CoreMoneroNodeService;
import haveno.core.locale.Res;
import haveno.core.offer.OpenOfferManager;
import haveno.core.payment.AmazonGiftCardAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.RevolutAccount;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.arbitration.ArbitrationManager;
import haveno.core.support.dispute.mediation.MediationManager;
import haveno.core.support.dispute.refund.RefundManager;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.TradeManager;
import haveno.core.trade.TradeTxException;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.core.util.FormattingUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.xmr.model.AddressEntry;
import haveno.core.xmr.nodes.LocalBitcoinNode;
import haveno.core.xmr.setup.WalletsSetup;
import haveno.core.xmr.wallet.BtcWalletService;
import haveno.core.xmr.wallet.WalletsManager;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.Socks5ProxyProvider;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.storage.payload.PersistableNetworkPayload;
import haveno.network.utils.Utils;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.SetChangeListener;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Coin;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.monadic.MonadicBinding;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

@Slf4j
@Singleton
public class HavenoSetup {
    private static final String VERSION_FILE_NAME = "version";

    private static final long STARTUP_TIMEOUT_MINUTES = 5;

    private final DomainInitialisation domainInitialisation;
    private final P2PNetworkSetup p2PNetworkSetup;
    private final WalletAppSetup walletAppSetup;
    private final WalletsManager walletsManager;
    private final WalletsSetup walletsSetup;
    private final BtcWalletService btcWalletService;
    private final XmrWalletService xmrWalletService;
    private final P2PService p2PService;
    private final PrivateNotificationManager privateNotificationManager;
    private final SignedWitnessStorageService signedWitnessStorageService;
    private final TradeManager tradeManager;
    private final OpenOfferManager openOfferManager;
    private final Preferences preferences;
    private final User user;
    private final AlertManager alertManager;
    private final Config config;
    private final AccountAgeWitnessService accountAgeWitnessService;
    private final TorSetup torSetup;
    private final CoinFormatter formatter;
    private final LocalBitcoinNode localBitcoinNode;
    private final AppStartupState appStartupState;
    private final MediationManager mediationManager;
    private final RefundManager refundManager;
    private final ArbitrationManager arbitrationManager;
    @Setter
    @Nullable
    private Consumer<Runnable> displayTacHandler;
    @Setter
    @Nullable
    private Consumer<String> chainFileLockedExceptionHandler,
            lockedUpFundsHandler, filterWarningHandler,
            displaySecurityRecommendationHandler, displayLocalhostHandler,
            wrongOSArchitectureHandler, displaySignedByArbitratorHandler,
            displaySignedByPeerHandler, displayPeerLimitLiftedHandler, displayPeerSignerHandler,
            rejectedTxErrorMessageHandler;
    @Setter
    @Nullable
    private Consumer<Boolean> displayTorNetworkSettingsHandler;
    @Setter
    @Nullable
    private Runnable showFirstPopupIfResyncSPVRequestedHandler;
    @Setter
    @Nullable
    private Consumer<Alert> displayAlertHandler;
    @Setter
    @Nullable
    private BiConsumer<Alert, String> displayUpdateHandler;
    @Setter
    @Nullable
    private Consumer<PrivateNotificationPayload> displayPrivateNotificationHandler;
    @Setter
    @Nullable
    private Runnable showPopupIfInvalidBtcConfigHandler;
    @Setter
    @Nullable
    private Consumer<List<RevolutAccount>> revolutAccountsUpdateHandler;
    @Setter
    @Nullable
    private Consumer<List<AmazonGiftCardAccount>> amazonGiftCardAccountsUpdateHandler;
    @Setter
    @Nullable
    private Runnable osxKeyLoggerWarningHandler;
    @Setter
    @Nullable
    private Runnable qubesOSInfoHandler;
    @Setter
    @Nullable
    private Runnable torAddressUpgradeHandler;
    @Setter
    @Nullable
    private Consumer<String> downGradePreventionHandler;
    @Getter
    final BooleanProperty newVersionAvailableProperty = new SimpleBooleanProperty(false);
    private BooleanProperty p2pNetworkReady;
    private final BooleanProperty walletInitialized = new SimpleBooleanProperty();
    private boolean allBasicServicesInitialized;
    @SuppressWarnings("FieldCanBeLocal")
    private MonadicBinding<Boolean> p2pNetworkAndWalletInitialized;
    private final List<HavenoSetupListener> havenoSetupListeners = new ArrayList<>();

    public interface HavenoSetupListener {
        default void onInitP2pNetwork() {
        }

        default void onInitWallet() {
        }

        default void onRequestWalletPassword() {
        }

        void onSetupComplete();
    }

    @Inject
    public HavenoSetup(DomainInitialisation domainInitialisation,
                       P2PNetworkSetup p2PNetworkSetup,
                       WalletAppSetup walletAppSetup,
                       WalletsManager walletsManager,
                       WalletsSetup walletsSetup,
                       XmrWalletService xmrWalletService,
                       BtcWalletService btcWalletService,
                       P2PService p2PService,
                       PrivateNotificationManager privateNotificationManager,
                       SignedWitnessStorageService signedWitnessStorageService,
                       TradeManager tradeManager,
                       OpenOfferManager openOfferManager,
                       Preferences preferences,
                       User user,
                       AlertManager alertManager,
                       Config config,
                       AccountAgeWitnessService accountAgeWitnessService,
                       TorSetup torSetup,
                       @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter formatter,
                       LocalBitcoinNode localBitcoinNode,
                       AppStartupState appStartupState,
                       Socks5ProxyProvider socks5ProxyProvider,
                       MediationManager mediationManager,
                       RefundManager refundManager,
                       ArbitrationManager arbitrationManager) {
        this.domainInitialisation = domainInitialisation;
        this.p2PNetworkSetup = p2PNetworkSetup;
        this.walletAppSetup = walletAppSetup;
        this.walletsManager = walletsManager;
        this.walletsSetup = walletsSetup;
        this.xmrWalletService = xmrWalletService;
        this.btcWalletService = btcWalletService;
        this.p2PService = p2PService;
        this.privateNotificationManager = privateNotificationManager;
        this.signedWitnessStorageService = signedWitnessStorageService;
        this.tradeManager = tradeManager;
        this.openOfferManager = openOfferManager;
        this.preferences = preferences;
        this.user = user;
        this.alertManager = alertManager;
        this.config = config;
        this.accountAgeWitnessService = accountAgeWitnessService;
        this.torSetup = torSetup;
        this.formatter = formatter;
        this.localBitcoinNode = localBitcoinNode;
        this.appStartupState = appStartupState;
        this.mediationManager = mediationManager;
        this.refundManager = refundManager;
        this.arbitrationManager = arbitrationManager;

        HavenoUtils.havenoSetup = this;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void displayAlertIfPresent(Alert alert, boolean openNewVersionPopup) {
        if (alert == null)
            return;

        if (alert.isSoftwareUpdateNotification()) {
            // only process if the alert version is "newer" than ours
            if (alert.isNewVersion(preferences)) {
                user.setDisplayedAlert(alert);          // save context to compare later
                newVersionAvailableProperty.set(true);  // shows link in footer bar
                if ((alert.canShowPopup(preferences) || openNewVersionPopup) && displayUpdateHandler != null) {
                    displayUpdateHandler.accept(alert, alert.showAgainKey());
                }
            }
        } else {
            // it is a normal message alert
            final Alert displayedAlert = user.getDisplayedAlert();
            if ((displayedAlert == null || !displayedAlert.equals(alert)) && displayAlertHandler != null)
                displayAlertHandler.accept(alert);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Main startup tasks
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void addHavenoSetupListener(HavenoSetupListener listener) {
        havenoSetupListeners.add(listener);
    }

    public void start() {
        // If user tried to downgrade we require a shutdown
        if (Config.baseCurrencyNetwork() == BaseCurrencyNetwork.XMR_MAINNET &&
                hasDowngraded(downGradePreventionHandler)) {
            return;
        }

        persistHavenoVersion();
        maybeShowTac(this::step2);
    }

    private void step2() {
        readMapsFromResources(this::step3);
        checkForCorrectOSArchitecture();
        checkOSXVersion();
        checkIfRunningOnQubesOS();
    }

    private void step3() {
        maybeInstallDependencies();
        startP2pNetworkAndWallet(this::step4);
    }

    private void step4() {
        initDomainServices();

        havenoSetupListeners.forEach(HavenoSetupListener::onSetupComplete);

        // We set that after calling the setupCompleteHandler to not trigger a popup from the dev dummy accounts
        // in MainViewModel
        maybeShowSecurityRecommendation();
        maybeShowLocalhostRunningInfo();
        maybeShowAccountSigningStateInfo();
        maybeShowTorAddressUpgradeInformation();
        checkInboundConnections();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Sub tasks
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void maybeShowTac(Runnable nextStep) {
        if (!preferences.isTacAcceptedV120() && !DevEnv.isDevMode()) {
            if (displayTacHandler != null)
                displayTacHandler.accept(() -> {
                    preferences.setTacAcceptedV120(true);
                    nextStep.run();
                });
        } else {
            nextStep.run();
        }
    }

    private void maybeInstallDependencies() {
        try {
            File monerodFile = new File(CoreMoneroNodeService.MONEROD_PATH);
            if (!monerodFile.exists()) {
                log.info("Installing monerod");
                monerodFile.getParentFile().mkdirs();
                FileUtil.resourceToFile("bin/" + CoreMoneroNodeService.MONEROD_NAME, monerodFile);
                monerodFile.setExecutable(true);
            }

            File moneroWalletFile = new File(XmrWalletService.MONERO_WALLET_RPC_PATH);
            if (!moneroWalletFile.exists()) {
                log.info("Installing monero-wallet-rpc");
                moneroWalletFile.getParentFile().mkdirs();
                FileUtil.resourceToFile("bin/" + XmrWalletService.MONERO_WALLET_RPC_NAME, moneroWalletFile);
                moneroWalletFile.setExecutable(true);
            }
        } catch (Exception e) {
            e.printStackTrace();
            log.error(e.toString());
        }
    }

    private void readMapsFromResources(Runnable completeHandler) {
        String postFix = "_" + config.baseCurrencyNetwork.name();
        p2PService.getP2PDataStorage().readFromResources(postFix, completeHandler);
    }

    private void startP2pNetworkAndWallet(Runnable nextStep) {
        ChangeListener<Boolean> walletInitializedListener = (observable, oldValue, newValue) -> {
            // TODO that seems to be called too often if Tor takes longer to start up...
            if (newValue && !p2pNetworkReady.get() && displayTorNetworkSettingsHandler != null)
                displayTorNetworkSettingsHandler.accept(true);
        };

        Timer startupTimeout = UserThread.runAfter(() -> {
            if (p2PNetworkSetup.p2pNetworkFailed.get() || walletsSetup.walletsSetupFailed.get()) {
                // Skip this timeout action if the p2p network or wallet setup failed
                // since an error prompt will be shown containing the error message
                return;
            }
            log.warn("startupTimeout called");
            //TODO (niyid) This has a part to play in the display of the password prompt
//            if (walletsManager.areWalletsEncrypted())
//                walletInitialized.addListener(walletInitializedListener);
            if (displayTorNetworkSettingsHandler != null)
                displayTorNetworkSettingsHandler.accept(true);

            // log.info("Set log level for org.berndpruenster.netlayer classes to DEBUG to show more details for " +
            //         "Tor network connection issues");
            // Log.setCustomLogLevel("org.berndpruenster.netlayer", Level.DEBUG);

        }, STARTUP_TIMEOUT_MINUTES, TimeUnit.MINUTES);

        log.info("Init P2P network");
        havenoSetupListeners.forEach(HavenoSetupListener::onInitP2pNetwork);
        p2pNetworkReady = p2PNetworkSetup.init(this::initWallet, displayTorNetworkSettingsHandler);

        // need to store it to not get garbage collected
        p2pNetworkAndWalletInitialized = EasyBind.combine(walletInitialized, p2pNetworkReady,
                (a, b) -> {
                    log.info("walletInitialized={}, p2pNetWorkReady={}", a, b);
                    return a && b;
                });
        p2pNetworkAndWalletInitialized.subscribe((observable, oldValue, newValue) -> {
            if (newValue) {
                startupTimeout.stop();
                walletInitialized.removeListener(walletInitializedListener);
                if (displayTorNetworkSettingsHandler != null)
                    displayTorNetworkSettingsHandler.accept(false);
                nextStep.run();
            }
        });
    }

    private void initWallet() {
        log.info("Init wallet");
        havenoSetupListeners.forEach(HavenoSetupListener::onInitWallet);
        walletAppSetup.init(chainFileLockedExceptionHandler,
                showFirstPopupIfResyncSPVRequestedHandler,
                showPopupIfInvalidBtcConfigHandler,
                () -> {
                    if (allBasicServicesInitialized) {
                        checkForLockedUpFunds();
                        checkForInvalidMakerFeeTxs();
                    }
                },
                () -> {});
    }

    private void initDomainServices() {
        log.info("initDomainServices");

        domainInitialisation.initDomainServices(rejectedTxErrorMessageHandler,
                displayPrivateNotificationHandler,
                filterWarningHandler,
                revolutAccountsUpdateHandler,
                amazonGiftCardAccountsUpdateHandler);

        if (walletsSetup.downloadPercentageProperty().get() == 1) { // TODO: update for XMR
            checkForLockedUpFunds();
            checkForInvalidMakerFeeTxs();
        }

        alertManager.alertMessageProperty().addListener((observable, oldValue, newValue) ->
                displayAlertIfPresent(newValue, false));
        displayAlertIfPresent(alertManager.alertMessageProperty().get(), false);

        allBasicServicesInitialized = true;

        appStartupState.onDomainServicesInitialized();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Utils
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void checkForLockedUpFunds() {
        // We check if there are locked up funds in failed or closed trades
        try {
            Set<String> setOfAllTradeIds = tradeManager.getSetOfFailedOrClosedTradeIdsFromLockedInFunds();
            btcWalletService.getAddressEntriesForTrade().stream()
                    .filter(e -> setOfAllTradeIds.contains(e.getOfferId()) &&
                            e.getContext() == AddressEntry.Context.MULTI_SIG)
                    .forEach(e -> {
                        Coin balance = e.getCoinLockedInMultiSigAsCoin();
                        if (balance.isPositive()) {
                            String message = Res.get("popup.warning.lockedUpFunds",
                                    formatter.formatCoinWithCode(balance), e.getAddressString(), e.getOfferId());
                            log.warn(message);
                            if (lockedUpFundsHandler != null) {
                                lockedUpFundsHandler.accept(message);
                            }
                        }
                    });
        } catch (TradeTxException e) {
            log.warn(e.getMessage());
            if (lockedUpFundsHandler != null) {
                lockedUpFundsHandler.accept(e.getMessage());
            }
        }
    }

    private void checkForInvalidMakerFeeTxs() {
        // We check if we have open offers with no confidence object at the maker fee tx. That can happen if the
        // miner fee was too low and the transaction got removed from mempool and got out from our wallet after a
        // resync.
        // TODO (woodser): check for invalid maker fee txs with xmr?
//        openOfferManager.getObservableList().forEach(e -> {
//            String offerFeeTxId = e.getOffer().getOfferFeeTxId();
//            if (btcWalletService.getConfidenceForTxId(offerFeeTxId) == null) { // TODO (woodser): verify xmr maker fee tx
//                String message = Res.get("popup.warning.openOfferWithInvalidMakerFeeTx",
//                        e.getOffer().getShortId(), offerFeeTxId);
//                log.warn(message);
//                if (lockedUpFundsHandler != null) {
//                    lockedUpFundsHandler.accept(message);
//                }
//            }
//        });
    }

    @Nullable
    public static String getLastHavenoVersion() {
        File versionFile = getVersionFile();
        if (!versionFile.exists()) {
            return null;
        }
        try (Scanner scanner = new Scanner(versionFile)) {
            // We only expect 1 line
            if (scanner.hasNextLine()) {
                return scanner.nextLine();
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static File getVersionFile() {
        return new File(Config.appDataDir(), VERSION_FILE_NAME);
    }

    public static boolean hasDowngraded() {
        return hasDowngraded(getLastHavenoVersion());
    }

    public static boolean hasDowngraded(String lastVersion) {
        return lastVersion != null && Version.isNewVersion(lastVersion, Version.VERSION);
    }

    public static boolean hasDowngraded(@Nullable Consumer<String> downGradePreventionHandler) {
        String lastVersion = getLastHavenoVersion();
        boolean hasDowngraded = hasDowngraded(lastVersion);
        if (hasDowngraded) {
            log.error("Downgrade from version {} to version {} is not supported", lastVersion, Version.VERSION);
            if (downGradePreventionHandler != null) {
                downGradePreventionHandler.accept(lastVersion);
            }
        }
        return hasDowngraded;
    }

    public static void persistHavenoVersion() {
        File versionFile = getVersionFile();
        if (!versionFile.exists()) {
            try {
                if (!versionFile.createNewFile()) {
                    log.error("Version file could not be created");
                }
            } catch (IOException e) {
                e.printStackTrace();
                log.error("Version file could not be created. {}", e.toString());
            }
        }

        try (FileWriter fileWriter = new FileWriter(versionFile, false)) {
            fileWriter.write(Version.VERSION);
        } catch (IOException e) {
            e.printStackTrace();
            log.error("Writing Version failed. {}", e.toString());
        }
    }

    private void checkForCorrectOSArchitecture() {
        if (!Utilities.isCorrectOSArchitecture() && wrongOSArchitectureHandler != null) {
            String osArchitecture = Utilities.getOSArchitecture();
            // We don't force a shutdown as the osArchitecture might in strange cases return a wrong value.
            // Needs at least more testing on different machines...
            wrongOSArchitectureHandler.accept(Res.get("popup.warning.wrongVersion",
                    osArchitecture,
                    Utilities.getJVMArchitecture(),
                    osArchitecture));
        }
    }

    private void checkOSXVersion() {
        if (Utilities.isOSX() && osxKeyLoggerWarningHandler != null) {
            try {
                // Seems it was introduced at 10.14: https://github.com/wesnoth/wesnoth/issues/4109
                if (Utilities.getMajorVersion() >= 10 && Utilities.getMinorVersion() >= 14) {
                    osxKeyLoggerWarningHandler.run();
                }
            } catch (InvalidVersionException | NumberFormatException e) {
                log.warn(e.getMessage());
            }
        }
    }

    /**
     * If Haveno is running on an OS that is virtualized under Qubes, show info popup with
     * link to the Setup Guide. The guide documents what other steps are needed, in
     * addition to installing the Linux package (qube sizing, etc)
     */
    private void checkIfRunningOnQubesOS() {
        if (Utilities.isQubesOS() && qubesOSInfoHandler != null) {
            qubesOSInfoHandler.run();
        }
    }

    /**
     * Check if we have inbound connections.  If not, try to ping ourselves.
     * If Haveno cannot connect to its own onion address through Tor, display
     * an informative message to let the user know to configure their firewall else
     * their offers will not be reachable.
     * Repeat this test hourly.
     */
    private void checkInboundConnections() {
        NodeAddress onionAddress = p2PService.getNetworkNode().nodeAddressProperty().get();
        if (onionAddress == null || !onionAddress.getFullAddress().contains("onion")) {
            return;
        }

        if (p2PService.getNetworkNode().upTime() > TimeUnit.HOURS.toMillis(1) &&
                p2PService.getNetworkNode().getInboundConnectionCount() == 0) {
            // we've been online a while and did not find any inbound connections; lets try the self-ping check
            log.info("no recent inbound connections found, starting the self-ping test");
            privateNotificationManager.sendPing(onionAddress, stringResult -> {
                log.info(stringResult);
                if (stringResult.contains("failed")) {
                    getP2PNetworkStatusIconId().set("flashing:image-yellow_circle");
                }
            });
        }

        // schedule another inbound connection check for later
        int nextCheckInMinutes = 30 + new Random().nextInt(30);
        log.debug("next inbound connections check in {} minutes", nextCheckInMinutes);
        UserThread.runAfter(this::checkInboundConnections, nextCheckInMinutes, TimeUnit.MINUTES);
    }

    private void maybeShowSecurityRecommendation() {
        if (user.getPaymentAccountsAsObservable() == null) return;
        String key = "remindPasswordAndBackup";
        user.getPaymentAccountsAsObservable().addListener((SetChangeListener<PaymentAccount>) change -> {
            if (!walletsManager.areWalletsEncrypted() && !user.isPaymentAccountImport() && preferences.showAgain(key) && change.wasAdded() &&
                    displaySecurityRecommendationHandler != null)
                displaySecurityRecommendationHandler.accept(key);
        });
    }

    private void maybeShowLocalhostRunningInfo() {
        maybeTriggerDisplayHandler("bitcoinLocalhostNode", displayLocalhostHandler, localBitcoinNode.shouldBeUsed());
    }

    private void maybeShowAccountSigningStateInfo() {
        String keySignedByArbitrator = "accountSignedByArbitrator";
        String keySignedByPeer = "accountSignedByPeer";
        String keyPeerLimitedLifted = "accountLimitLifted";
        String keyPeerSigner = "accountPeerSigner";

        // check signed witness on startup
        checkSigningState(AccountAgeWitnessService.SignState.ARBITRATOR, keySignedByArbitrator, displaySignedByArbitratorHandler);
        checkSigningState(AccountAgeWitnessService.SignState.PEER_INITIAL, keySignedByPeer, displaySignedByPeerHandler);
        checkSigningState(AccountAgeWitnessService.SignState.PEER_LIMIT_LIFTED, keyPeerLimitedLifted, displayPeerLimitLiftedHandler);
        checkSigningState(AccountAgeWitnessService.SignState.PEER_SIGNER, keyPeerSigner, displayPeerSignerHandler);

        // check signed witness during runtime
        p2PService.getP2PDataStorage().addAppendOnlyDataStoreListener(
                payload -> {
                    maybeTriggerDisplayHandler(keySignedByArbitrator, displaySignedByArbitratorHandler,
                            isSignedWitnessOfMineWithState(payload, AccountAgeWitnessService.SignState.ARBITRATOR));
                    maybeTriggerDisplayHandler(keySignedByPeer, displaySignedByPeerHandler,
                            isSignedWitnessOfMineWithState(payload, AccountAgeWitnessService.SignState.PEER_INITIAL));
                    maybeTriggerDisplayHandler(keyPeerLimitedLifted, displayPeerLimitLiftedHandler,
                            isSignedWitnessOfMineWithState(payload, AccountAgeWitnessService.SignState.PEER_LIMIT_LIFTED));
                    maybeTriggerDisplayHandler(keyPeerSigner, displayPeerSignerHandler,
                            isSignedWitnessOfMineWithState(payload, AccountAgeWitnessService.SignState.PEER_SIGNER));
                });
    }

    private void checkSigningState(AccountAgeWitnessService.SignState state,
                                   String key, Consumer<String> displayHandler) {
        boolean signingStateFound = signedWitnessStorageService.getMap().values().stream()
                .anyMatch(payload -> isSignedWitnessOfMineWithState(payload, state));

        maybeTriggerDisplayHandler(key, displayHandler, signingStateFound);
    }

    private boolean isSignedWitnessOfMineWithState(PersistableNetworkPayload payload,
                                                   AccountAgeWitnessService.SignState state) {
        if (payload instanceof SignedWitness && user.getPaymentAccounts() != null) {
            // We know at this point that it is already added to the signed witness list
            // Check if new signed witness is for one of my own accounts
            return user.getPaymentAccounts().stream()
                    .filter(a -> PaymentMethod.hasChargebackRisk(a.getPaymentMethod(), a.getTradeCurrencies()))
                    .filter(a -> Arrays.equals(((SignedWitness) payload).getAccountAgeWitnessHash(),
                            accountAgeWitnessService.getMyWitness(a.getPaymentAccountPayload()).getHash()))
                    .anyMatch(a -> accountAgeWitnessService.getSignState(accountAgeWitnessService.getMyWitness(
                            a.getPaymentAccountPayload())).equals(state));
        }
        return false;
    }

    private void maybeTriggerDisplayHandler(String key, Consumer<String> displayHandler, boolean signingStateFound) {
        if (signingStateFound && preferences.showAgain(key) &&
                displayHandler != null) {
            displayHandler.accept(key);
        }
    }

    private void maybeShowTorAddressUpgradeInformation() {
        if (Config.baseCurrencyNetwork().isTestnet() ||
                Utils.isV3Address(Objects.requireNonNull(p2PService.getNetworkNode().getNodeAddress()).getHostName())) {
            return;
        }

        maybeRunTorNodeAddressUpgradeHandler();

        tradeManager.getNumPendingTrades().addListener((observable, oldValue, newValue) -> {
            long numPendingTrades = (long) newValue;
            if (numPendingTrades == 0) {
                maybeRunTorNodeAddressUpgradeHandler();
            }
        });
    }

    private void maybeRunTorNodeAddressUpgradeHandler() {
        if (mediationManager.getDisputesAsObservableList().stream().allMatch(Dispute::isClosed) &&
                refundManager.getDisputesAsObservableList().stream().allMatch(Dispute::isClosed) &&
                arbitrationManager.getDisputesAsObservableList().stream().allMatch(Dispute::isClosed) &&
                tradeManager.getNumPendingTrades().isEqualTo(0).get()) {
            Objects.requireNonNull(torAddressUpgradeHandler).run();
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    // Wallet
    public StringProperty getBtcInfo() {
        return walletAppSetup.getBtcInfo();
    }

    public DoubleProperty getBtcSyncProgress() {
        return walletAppSetup.getBtcSyncProgress();
    }

    public StringProperty getWalletServiceErrorMsg() {
        return walletAppSetup.getWalletServiceErrorMsg();
    }

    public StringProperty getBtcSplashSyncIconId() {
        return walletAppSetup.getBtcSplashSyncIconId();
    }

    public BooleanProperty getUseTorForBTC() {
        return walletAppSetup.getUseTorForBTC();
    }

    // P2P
    public StringProperty getP2PNetworkInfo() {
        return p2PNetworkSetup.getP2PNetworkInfo();
    }

    public BooleanProperty getSplashP2PNetworkAnimationVisible() {
        return p2PNetworkSetup.getSplashP2PNetworkAnimationVisible();
    }

    public StringProperty getP2pNetworkWarnMsg() {
        return p2PNetworkSetup.getP2pNetworkWarnMsg();
    }

    public StringProperty getP2PNetworkIconId() {
        return p2PNetworkSetup.getP2PNetworkIconId();
    }

    public StringProperty getP2PNetworkStatusIconId() {
        return p2PNetworkSetup.getP2PNetworkStatusIconId();
    }

    public BooleanProperty getUpdatedDataReceived() {
        return p2PNetworkSetup.getUpdatedDataReceived();
    }

    public StringProperty getP2pNetworkLabelId() {
        return p2PNetworkSetup.getP2pNetworkLabelId();
    }

    public BooleanProperty getWalletInitialized() {
        return walletInitialized;
    }

    public AppStartupState getAppStartupState() {
        return appStartupState;
    }
}
