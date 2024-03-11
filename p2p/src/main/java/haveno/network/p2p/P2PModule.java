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

package haveno.network.p2p;

import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import static com.google.inject.name.Names.named;
import static com.google.inject.util.Providers.of;
import haveno.common.app.AppModule;
import haveno.common.config.Config;
import static haveno.common.config.Config.BAN_LIST;
import static haveno.common.config.Config.MAX_CONNECTIONS;
import static haveno.common.config.Config.NODE_PORT;
import static haveno.common.config.Config.REPUBLISH_MAILBOX_ENTRIES;
import static haveno.common.config.Config.SOCKS_5_PROXY_HTTP_ADDRESS;
import static haveno.common.config.Config.SOCKS_5_PROXY_XMR_ADDRESS;
import static haveno.common.config.Config.TORRC_FILE;
import static haveno.common.config.Config.TORRC_OPTIONS;
import static haveno.common.config.Config.TOR_CONTROL_COOKIE_FILE;
import static haveno.common.config.Config.TOR_CONTROL_HOST;
import static haveno.common.config.Config.TOR_CONTROL_PASSWORD;
import static haveno.common.config.Config.TOR_CONTROL_PORT;
import static haveno.common.config.Config.TOR_CONTROL_USE_SAFE_COOKIE_AUTH;
import static haveno.common.config.Config.TOR_DIR;
import static haveno.common.config.Config.TOR_STREAM_ISOLATION;
import static haveno.common.config.Config.USE_LOCALHOST_FOR_P2P;
import haveno.network.Socks5ProxyProvider;
import haveno.network.http.HttpClient;
import haveno.network.http.HttpClientImpl;
import haveno.network.p2p.network.Connection;
import haveno.network.p2p.network.NetworkNode;
import haveno.network.p2p.peers.Broadcaster;
import haveno.network.p2p.peers.PeerManager;
import haveno.network.p2p.peers.getdata.RequestDataManager;
import haveno.network.p2p.peers.keepalive.KeepAliveManager;
import haveno.network.p2p.peers.peerexchange.PeerExchangeManager;
import haveno.network.p2p.storage.P2PDataStorage;
import haveno.network.p2p.storage.persistence.AppendOnlyDataStoreService;
import haveno.network.p2p.storage.persistence.ProtectedDataStoreService;
import haveno.network.p2p.storage.persistence.ResourceDataStoreService;
import java.io.File;
import java.time.Clock;
import java.util.List;

public class P2PModule extends AppModule {

    public P2PModule(Config config) {
        super(config);
    }

    @Override
    protected void configure() {
        bind(Clock.class).toInstance(Clock.systemDefaultZone());
        bind(P2PService.class).in(Singleton.class);
        bind(PeerManager.class).in(Singleton.class);
        bind(P2PDataStorage.class).in(Singleton.class);
        bind(AppendOnlyDataStoreService.class).in(Singleton.class);
        bind(ProtectedDataStoreService.class).in(Singleton.class);
        bind(ResourceDataStoreService.class).in(Singleton.class);
        bind(RequestDataManager.class).in(Singleton.class);
        bind(PeerExchangeManager.class).in(Singleton.class);
        bind(KeepAliveManager.class).in(Singleton.class);
        bind(Broadcaster.class).in(Singleton.class);
        bind(NetworkNode.class).toProvider(NetworkNodeProvider.class).in(Singleton.class);
        bind(Socks5ProxyProvider.class).in(Singleton.class);
        bind(HttpClient.class).to(HttpClientImpl.class);

        requestStaticInjection(Connection.class);

        bindConstant().annotatedWith(named(USE_LOCALHOST_FOR_P2P)).to(config.useLocalhostForP2P);

        bind(File.class).annotatedWith(named(TOR_DIR)).toInstance(config.torDir);

        bind(int.class).annotatedWith(named(NODE_PORT)).toInstance(config.nodePort);

        bindConstant().annotatedWith(named(MAX_CONNECTIONS)).to(config.maxConnections);

        bind(new TypeLiteral<List<String>>(){}).annotatedWith(named(BAN_LIST)).toInstance(config.banList);
        bindConstant().annotatedWith(named(SOCKS_5_PROXY_XMR_ADDRESS)).to(config.socks5ProxyXmrAddress);
        bindConstant().annotatedWith(named(SOCKS_5_PROXY_HTTP_ADDRESS)).to(config.socks5ProxyHttpAddress);
        bind(File.class).annotatedWith(named(TORRC_FILE)).toProvider(of(config.torrcFile)); // allow null value
        bindConstant().annotatedWith(named(TORRC_OPTIONS)).to(config.torrcOptions);
        bindConstant().annotatedWith(named(TOR_CONTROL_HOST)).to(config.torControlHost);
        bindConstant().annotatedWith(named(TOR_CONTROL_PORT)).to(config.torControlPort);
        bindConstant().annotatedWith(named(TOR_CONTROL_PASSWORD)).to(config.torControlPassword);
        bind(File.class).annotatedWith(named(TOR_CONTROL_COOKIE_FILE)).toProvider(of(config.torControlCookieFile));
        bindConstant().annotatedWith(named(TOR_CONTROL_USE_SAFE_COOKIE_AUTH)).to(config.useTorControlSafeCookieAuth);
        bindConstant().annotatedWith(named(TOR_STREAM_ISOLATION)).to(config.torStreamIsolation);
        bindConstant().annotatedWith(named("MAX_SEQUENCE_NUMBER_MAP_SIZE_BEFORE_PURGE")).to(1000);
        bind(Boolean.class).annotatedWith(named(REPUBLISH_MAILBOX_ENTRIES)).toInstance(config.republishMailboxEntries);
    }
}
