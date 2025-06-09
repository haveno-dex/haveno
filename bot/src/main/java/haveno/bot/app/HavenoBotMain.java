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

package haveno.bot.app;

import haveno.common.UserThread;
import haveno.common.app.AppModule;
import haveno.common.handlers.ResultHandler;
import lombok.extern.slf4j.Slf4j;

import haveno.common.Timer;
import haveno.common.config.BaseCurrencyNetwork;
import haveno.common.config.Config;
import haveno.core.app.TorSetup;
import haveno.core.app.misc.ExecutableForAppWithP2p;
import haveno.core.app.misc.ModuleForAppWithP2p;
import haveno.core.user.Cookie;
import haveno.core.user.CookieKey;
import haveno.core.user.User;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.P2PServiceListener;
import haveno.network.p2p.peers.PeerManager;

@Slf4j
public class HavenoBotMain extends ExecutableForAppWithP2p {

    private static final long CHECK_CONNECTION_LOSS_SEC = 30;
    private static final String VERSION = "1.1.2";
    private HavenoBot bot;
    private Timer checkConnectionLossTime;

    public HavenoBotMain() {
        super("Haveno Bot", "haveno-bot", "haveno_bot", VERSION);
    }

    public static void main(String[] args) {
        System.out.println("HavenoBot.VERSION: " + VERSION);
        new HavenoBotMain().execute(args);
    }

    @Override
    protected int doExecute() {
        super.doExecute();

        checkMemory(config, this);

        return keepRunning();
    }

    @Override
    protected void addCapabilities() {
    }

    @Override
    protected void launchApplication() {
        UserThread.execute(() -> {
            try {
                bot = new HavenoBot();
                UserThread.execute(this::onApplicationLaunched);
            } catch (Exception e) {
                log.error("Error launching haveno bot: {}\n", e.toString(), e);
            }
        });
    }

    @Override
    protected void onApplicationLaunched() {
        super.onApplicationLaunched();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // We continue with a series of synchronous execution tasks
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected AppModule getModule() {
        return new ModuleForAppWithP2p(config);
    }

    @Override
    protected void applyInjector() {
        super.applyInjector();

        bot.setInjector(injector);

    }

    @Override
    protected void startApplication() {
        Cookie cookie = injector.getInstance(User.class).getCookie();
        cookie.getAsOptionalBoolean(CookieKey.CLEAN_TOR_DIR_AT_RESTART).ifPresent(wasCleanTorDirSet -> {
            if (wasCleanTorDirSet) {
                injector.getInstance(TorSetup.class).cleanupTorFiles(() -> {
                    log.info("Tor directory reset");
                    cookie.remove(CookieKey.CLEAN_TOR_DIR_AT_RESTART);
                }, log::error);
            }
        });

        bot.startApplication();

        injector.getInstance(P2PService.class).addP2PServiceListener(new P2PServiceListener() {
            @Override
            public void onDataReceived() {
                // Do nothing
            }

            @Override
            public void onNoSeedNodeAvailable() {
                // Do nothing
            }

            @Override
            public void onNoPeersAvailable() {
                // Do nothing
            }

            @Override
            public void onUpdatedDataReceived() {
                // Do nothing
            }

            @Override
            public void onTorNodeReady() {
                // Do nothing
            }

            @Override
            public void onHiddenServicePublished() {
                UserThread.runAfter(() -> setupConnectionLossCheck(), 60);
            }

            @Override
            public void onSetupFailed(Throwable throwable) {
                // Do nothing
            }

            @Override
            public void onRequestCustomBridges() {
                // Do nothing
            }
        });
    }

    private void setupConnectionLossCheck() {
        // For dev testing (usually on XMR_LOCAL) we don't want to get the seed shut
        // down as it is normal that the seed is the only actively running node.
        if (Config.baseCurrencyNetwork() != BaseCurrencyNetwork.XMR_MAINNET) {
            return;
        }

        if (checkConnectionLossTime != null) {
            return;
        }

        checkConnectionLossTime = UserThread.runPeriodically(() -> {
            if (injector.getInstance(PeerManager.class).getNumAllConnectionsLostEvents() > 1) {
                // We set a flag to clear tor cache files at re-start. We cannot clear it now as Tor is used and
                // that can cause problems.
                injector.getInstance(User.class).getCookie().putAsBoolean(CookieKey.CLEAN_TOR_DIR_AT_RESTART, true);
                shutDown(this);
            }
        }, CHECK_CONNECTION_LOSS_SEC);

    }

    @Override
    public void gracefulShutDown(ResultHandler resultHandler) {
        bot.shutDown();
        super.gracefulShutDown(resultHandler);
    }
}
