package bisq.core.api;

import bisq.common.config.Config;
import bisq.core.btc.Balances;
import bisq.core.btc.setup.WalletsSetup;
import bisq.core.btc.wallet.WalletsManager;
import bisq.network.p2p.P2PService;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@Slf4j
class StatusCheck {

    private final Config config;
    private final P2PService p2PService;
    private final WalletsSetup walletsSetup;
    private final WalletsManager walletsManager;
    private final Balances balances;

    @Inject
    public StatusCheck(Config config,
                       P2PService p2PService,
                       WalletsSetup walletsSetup,
                       WalletsManager walletsManager,
                       Balances balances) {
        this.config = config;
        this.p2PService = p2PService;
        this.walletsSetup = walletsSetup;
        this.walletsManager = walletsManager;
        this.balances = balances;
    }

    public void verifyCanTrade() {
        if (!p2PService.isBootstrapped())
            throw new IllegalStateException("p2p service is not yet bootstrapped");

        if (config.baseCurrencyNetwork.isMainnet()
                && p2PService.getNumConnectedPeers().get() < walletsSetup.getMinBroadcastConnections())
            throw new IllegalStateException("not enough connected peers");

        if (!walletsManager.areWalletsAvailable())
            throw new IllegalStateException("wallet is not yet available");

        if (balances.getAvailableBalance().get() == null)
            throw new IllegalStateException("balance is not yet available");
    }
}
