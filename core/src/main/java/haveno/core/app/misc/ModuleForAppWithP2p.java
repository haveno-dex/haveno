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

package haveno.core.app.misc;

import com.google.inject.Singleton;
import haveno.common.ClockWatcher;
import haveno.common.app.AppModule;
import haveno.common.config.Config;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.proto.network.NetworkProtoResolver;
import haveno.common.proto.persistable.PersistenceProtoResolver;
import haveno.core.alert.AlertModule;
import haveno.core.app.TorSetup;
import haveno.core.btc.MoneroModule;
import haveno.core.filter.FilterModule;
import haveno.core.network.CoreNetworkFilter;
import haveno.core.network.p2p.seed.DefaultSeedNodeRepository;
import haveno.core.offer.OfferModule;
import haveno.core.proto.network.CoreNetworkProtoResolver;
import haveno.core.proto.persistable.CorePersistenceProtoResolver;
import haveno.core.trade.TradeModule;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.core.xmr.connection.MoneroConnectionModule;
import haveno.network.crypto.EncryptionServiceModule;
import haveno.network.p2p.P2PModule;
import haveno.network.p2p.network.BridgeAddressProvider;
import haveno.network.p2p.network.NetworkFilter;
import haveno.network.p2p.seed.SeedNodeRepository;
import java.io.File;

import static com.google.inject.name.Names.named;
import static haveno.common.config.Config.*;

public class ModuleForAppWithP2p extends AppModule {

    public ModuleForAppWithP2p(Config config) {
        super(config);
    }

    @Override
    protected void configure() {
        bind(Config.class).toInstance(config);

        bind(KeyStorage.class).in(Singleton.class);
        bind(KeyRing.class).in(Singleton.class);
        bind(User.class).in(Singleton.class);
        bind(ClockWatcher.class).in(Singleton.class);
        bind(NetworkProtoResolver.class).to(CoreNetworkProtoResolver.class).in(Singleton.class);
        bind(PersistenceProtoResolver.class).to(CorePersistenceProtoResolver.class).in(Singleton.class);
        bind(Preferences.class).in(Singleton.class);
        bind(BridgeAddressProvider.class).to(Preferences.class).in(Singleton.class);
        bind(TorSetup.class).in(Singleton.class);

        bind(SeedNodeRepository.class).to(DefaultSeedNodeRepository.class).in(Singleton.class);
        bind(NetworkFilter.class).to(CoreNetworkFilter.class).in(Singleton.class);

        bind(File.class).annotatedWith(named(STORAGE_DIR)).toInstance(config.storageDir);
        bind(File.class).annotatedWith(named(KEY_STORAGE_DIR)).toInstance(config.keyStorageDir);

        bindConstant().annotatedWith(named(USE_DEV_PRIVILEGE_KEYS)).to(config.useDevPrivilegeKeys);
        bindConstant().annotatedWith(named(USE_DEV_MODE)).to(config.useDevMode);
        bindConstant().annotatedWith(named(USE_DEV_MODE_HEADER)).to(config.useDevModeHeader);
        bindConstant().annotatedWith(named(REFERRAL_ID)).to(config.referralId);
        bindConstant().annotatedWith(named(PREVENT_PERIODIC_SHUTDOWN_AT_SEED_NODE)).to(config.preventPeriodicShutdownAtSeedNode);

        // ordering is used for shut down sequence
        install(new TradeModule(config));
        install(new EncryptionServiceModule(config));
        install(new OfferModule(config));
        install(new P2PModule(config));
        install(new MoneroModule(config));
        install(new AlertModule(config));
        install(new FilterModule(config));
        install(new MoneroConnectionModule(config));
    }
}
