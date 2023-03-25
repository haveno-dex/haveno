package haveno.desktop;

import com.google.inject.Guice;
import com.google.inject.Injector;
import haveno.common.ClockWatcher;
import haveno.common.config.Config;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.file.CorruptedStorageFileHandler;
import haveno.common.persistence.PersistenceManager;
import haveno.common.proto.network.NetworkProtoResolver;
import haveno.common.proto.persistable.PersistenceProtoResolver;
import haveno.core.app.AvoidStandbyModeService;
import haveno.core.app.P2PNetworkSetup;
import haveno.core.app.TorSetup;
import haveno.core.app.WalletAppSetup;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.network.p2p.seed.DefaultSeedNodeRepository;
import haveno.core.notifications.MobileMessageEncryption;
import haveno.core.notifications.MobileModel;
import haveno.core.notifications.MobileNotificationService;
import haveno.core.notifications.MobileNotificationValidator;
import haveno.core.notifications.alerts.MyOfferTakenEvents;
import haveno.core.notifications.alerts.TradeEvents;
import haveno.core.notifications.alerts.market.MarketAlerts;
import haveno.core.notifications.alerts.price.PriceAlert;
import haveno.core.payment.ChargeBackRisk;
import haveno.core.payment.TradeLimits;
import haveno.core.proto.network.CoreNetworkProtoResolver;
import haveno.core.proto.persistable.CorePersistenceProtoResolver;
import haveno.core.support.dispute.arbitration.ArbitrationDisputeListService;
import haveno.core.support.dispute.arbitration.ArbitrationManager;
import haveno.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import haveno.core.support.dispute.arbitration.arbitrator.ArbitratorService;
import haveno.core.support.dispute.mediation.MediationDisputeListService;
import haveno.core.support.dispute.mediation.MediationManager;
import haveno.core.support.dispute.mediation.mediator.MediatorManager;
import haveno.core.support.dispute.mediation.mediator.MediatorService;
import haveno.core.support.traderchat.TraderChatManager;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.desktop.app.HavenoAppModule;
import haveno.desktop.common.view.CachingViewLoader;
import haveno.desktop.common.view.ViewLoader;
import haveno.desktop.common.view.guice.InjectorViewFactory;
import haveno.desktop.main.funds.transactions.DisplayedTransactionsFactory;
import haveno.desktop.main.funds.transactions.TradableRepository;
import haveno.desktop.main.funds.transactions.TransactionAwareTradableFactory;
import haveno.desktop.main.funds.transactions.TransactionListItemFactory;
import haveno.desktop.main.offer.offerbook.OfferBook;
import haveno.desktop.main.overlays.notifications.NotificationCenter;
import haveno.desktop.main.overlays.windows.TorNetworkSettingsWindow;
import haveno.desktop.main.presentation.MarketPricePresentation;
import haveno.desktop.util.Transitions;
import haveno.network.p2p.network.BridgeAddressProvider;
import haveno.network.p2p.seed.SeedNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GuiceSetupTest {

    private Injector injector;

    @BeforeEach
    public void setUp() {
        Res.setup();
        CurrencyUtil.setup();

        injector = Guice.createInjector(new HavenoAppModule(new Config()));
    }

    @Test
    public void testGuiceSetup() {
        injector.getInstance(AvoidStandbyModeService.class);
        // desktop module
        assertSingleton(OfferBook.class);
        assertSingleton(CachingViewLoader.class);
        assertSingleton(Navigation.class);
        assertSingleton(InjectorViewFactory.class);
        assertSingleton(NotificationCenter.class);
        assertSingleton(TorNetworkSettingsWindow.class);
        assertSingleton(MarketPricePresentation.class);
        assertSingleton(ViewLoader.class);
        assertSingleton(Transitions.class);
        assertSingleton(TradableRepository.class);
        assertSingleton(TransactionListItemFactory.class);
        assertSingleton(TransactionAwareTradableFactory.class);
        assertSingleton(DisplayedTransactionsFactory.class);

        // core module
//        assertSingleton(HavenoSetup.class); // this is a can of worms
//        assertSingleton(DisputeMsgEvents.class);
        assertSingleton(TorSetup.class);
        assertSingleton(P2PNetworkSetup.class);
        assertSingleton(WalletAppSetup.class);
        assertSingleton(TradeLimits.class);
        assertSingleton(KeyStorage.class);
        assertSingleton(KeyRing.class);
        assertSingleton(User.class);
        assertSingleton(ClockWatcher.class);
        assertSingleton(Preferences.class);
        assertSingleton(BridgeAddressProvider.class);
        assertSingleton(CorruptedStorageFileHandler.class);
        assertSingleton(AvoidStandbyModeService.class);
        assertSingleton(DefaultSeedNodeRepository.class);
        assertSingleton(SeedNodeRepository.class);
        assertTrue(injector.getInstance(SeedNodeRepository.class) instanceof DefaultSeedNodeRepository);
        assertSingleton(CoreNetworkProtoResolver.class);
        assertSingleton(NetworkProtoResolver.class);
        assertTrue(injector.getInstance(NetworkProtoResolver.class) instanceof CoreNetworkProtoResolver);
        assertSingleton(PersistenceProtoResolver.class);
        assertSingleton(CorePersistenceProtoResolver.class);
        assertTrue(injector.getInstance(PersistenceProtoResolver.class) instanceof CorePersistenceProtoResolver);
        assertSingleton(MobileMessageEncryption.class);
        assertSingleton(MobileNotificationService.class);
        assertSingleton(MobileNotificationValidator.class);
        assertSingleton(MobileModel.class);
        assertSingleton(MyOfferTakenEvents.class);
        assertSingleton(TradeEvents.class);
        assertSingleton(PriceAlert.class);
        assertSingleton(MarketAlerts.class);
        assertSingleton(ChargeBackRisk.class);
        assertSingleton(ArbitratorService.class);
        assertSingleton(ArbitratorManager.class);
        assertSingleton(ArbitrationManager.class);
        assertSingleton(ArbitrationDisputeListService.class);
        assertSingleton(MediatorService.class);
        assertSingleton(MediatorManager.class);
        assertSingleton(MediationManager.class);
        assertSingleton(MediationDisputeListService.class);
        assertSingleton(TraderChatManager.class);

        assertNotSingleton(PersistenceManager.class);
    }

    private void assertSingleton(Class<?> type) {
        assertSame(injector.getInstance(type), injector.getInstance(type));
    }

    private void assertNotSingleton(Class<?> type) {
        assertNotSame(injector.getInstance(type), injector.getInstance(type));
    }
}
