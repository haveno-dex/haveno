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

package bisq.core.btc;

import bisq.core.btc.model.AddressEntryList;
import bisq.core.btc.model.EncryptedConnectionList;
import bisq.core.btc.model.XmrAddressEntryList;
import bisq.core.btc.nodes.BtcNodes;
import bisq.core.btc.setup.RegTestHost;
import bisq.core.btc.setup.WalletsSetup;
import bisq.core.btc.wallet.BtcWalletService;
import bisq.core.btc.wallet.NonBsqCoinSelector;
import bisq.core.btc.wallet.TradeWalletService;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.provider.ProvidersRepository;
import bisq.core.provider.fee.FeeProvider;
import bisq.core.provider.price.PriceFeedService;

import bisq.common.app.AppModule;
import bisq.common.config.Config;

import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;

import java.io.File;

import java.util.Arrays;
import java.util.List;

import static bisq.common.config.Config.PROVIDERS;
import static bisq.common.config.Config.WALLET_DIR;
import static bisq.common.config.Config.WALLET_RPC_BIND_PORT;
import static com.google.inject.name.Names.named;

public class BitcoinModule extends AppModule {

    public BitcoinModule(Config config) {
        super(config);
    }

    @Override
    protected void configure() {
        // If we have selected BTC_DAO_REGTEST or BTC_DAO_TESTNET we use our master regtest node,
        // otherwise the specified host or default (localhost)
        String regTestHost = config.bitcoinRegtestHost;
        if (regTestHost.isEmpty()) {
            regTestHost = Config.DEFAULT_REGTEST_HOST;
        }

        RegTestHost.HOST = regTestHost;
        if (Arrays.asList("localhost", "127.0.0.1").contains(regTestHost)) {
            bind(RegTestHost.class).toInstance(RegTestHost.LOCALHOST);
        } else if ("none".equals(regTestHost)) {
            bind(RegTestHost.class).toInstance(RegTestHost.NONE);
        } else {
            bind(RegTestHost.class).toInstance(RegTestHost.REMOTE_HOST);
        }

        bind(File.class).annotatedWith(named(WALLET_DIR)).toInstance(config.walletDir);
        bind(int.class).annotatedWith(named(WALLET_RPC_BIND_PORT)).toInstance(config.walletRpcBindPort);

        bindConstant().annotatedWith(named(Config.XMR_NODE)).to(config.xmrNode);
        bindConstant().annotatedWith(named(Config.XMR_NODE_USERNAME)).to(config.xmrNodeUsername);
        bindConstant().annotatedWith(named(Config.XMR_NODE_PASSWORD)).to(config.xmrNodePassword);
        bindConstant().annotatedWith(named(Config.BTC_NODES)).to(config.btcNodes);
        bindConstant().annotatedWith(named(Config.USER_AGENT)).to(config.userAgent);
        bindConstant().annotatedWith(named(Config.NUM_CONNECTIONS_FOR_BTC)).to(config.numConnectionsForBtc);
        bindConstant().annotatedWith(named(Config.USE_ALL_PROVIDED_NODES)).to(config.useAllProvidedNodes);
        bindConstant().annotatedWith(named(Config.SOCKS5_DISCOVER_MODE)).to(config.socks5DiscoverMode);
        bind(new TypeLiteral<List<String>>(){}).annotatedWith(named(PROVIDERS)).toInstance(config.providers);

        bind(AddressEntryList.class).in(Singleton.class);
        bind(XmrAddressEntryList.class).in(Singleton.class);
        bind(EncryptedConnectionList.class).in(Singleton.class);
        bind(WalletsSetup.class).in(Singleton.class);
        bind(XmrWalletService.class).in(Singleton.class);
        bind(BtcWalletService.class).in(Singleton.class);
        bind(TradeWalletService.class).in(Singleton.class);
        bind(NonBsqCoinSelector.class).in(Singleton.class);
        bind(BtcNodes.class).in(Singleton.class);
        bind(Balances.class).in(Singleton.class);

        bind(ProvidersRepository.class).in(Singleton.class);
        bind(FeeProvider.class).in(Singleton.class);
        bind(PriceFeedService.class).in(Singleton.class);
    }
}

