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

package haveno.core.app.misc;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import haveno.common.ThreadUtils;
import haveno.common.UserThread;
import haveno.common.app.DevEnv;
import haveno.common.config.Config;
import haveno.common.file.JsonFileManager;
import haveno.common.handlers.ResultHandler;
import haveno.common.persistence.PersistenceManager;
import haveno.common.setup.GracefulShutDownHandler;
import haveno.common.util.Profiler;
import haveno.core.api.XmrConnectionService;
import haveno.core.app.AvoidStandbyModeService;
import haveno.core.app.HavenoExecutable;
import haveno.core.offer.OfferBookService;
import haveno.core.offer.OpenOfferManager;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import haveno.core.trade.TradeManager;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.core.xmr.setup.WalletsSetup;
import haveno.core.xmr.wallet.BtcWalletService;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.seed.SeedNodeRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@Slf4j
public abstract class ExecutableForAppWithP2p extends HavenoExecutable {
    private static final long CHECK_MEMORY_PERIOD_SEC = 300;
    private static final long CHECK_SHUTDOWN_SEC = TimeUnit.HOURS.toSeconds(1);
    private static final long SHUTDOWN_INTERVAL = TimeUnit.HOURS.toMillis(24);
    private volatile boolean stopped;
    private final long startTime = System.currentTimeMillis();

    public ExecutableForAppWithP2p(String fullName, String scriptName, String appName, String version) {
        super(fullName, scriptName, appName, version);
    }

    @Override
    protected void configUserThread() {
        final ThreadFactory threadFactory = new ThreadFactoryBuilder()
                .setNameFormat(this.getClass().getSimpleName())
                .setDaemon(true)
                .build();
        UserThread.setExecutor(Executors.newSingleThreadExecutor(threadFactory));
    }

    @Override
    public void onSetupComplete() {
        log.info("onSetupComplete");
    }

    // We don't use the gracefulShutDown implementation of the super class as we have a limited set of modules
    @Override
    public void gracefulShutDown(ResultHandler resultHandler) {
        log.info("Starting graceful shut down of {}", getClass().getSimpleName());

        // ignore if shut down started
        if (isShutDownStarted) {
            log.info("Ignoring call to gracefulShutDown, already started");
            return;
        }
        isShutDownStarted = true;

        try {
            if (injector != null) {

                // notify trade protocols and wallets to prepare for shut down
                Set<Runnable> tasks = new HashSet<Runnable>();
                tasks.add(() -> injector.getInstance(TradeManager.class).onShutDownStarted());
                tasks.add(() -> injector.getInstance(XmrWalletService.class).onShutDownStarted());
                tasks.add(() -> injector.getInstance(XmrConnectionService.class).onShutDownStarted());
                try {
                    ThreadUtils.awaitTasks(tasks, tasks.size(), 120000l); // run in parallel with timeout
                } catch (Exception e) {
                    log.error("Error awaiting tasks to complete: {}\n", e.getMessage(), e);
                }

                JsonFileManager.shutDownAllInstances();
                injector.getInstance(PriceFeedService.class).shutDown();
                injector.getInstance(ArbitratorManager.class).shutDown();
                injector.getInstance(TradeStatisticsManager.class).shutDown();
                injector.getInstance(AvoidStandbyModeService.class).shutDown();

                // shut down open offer manager
                log.info("Shutting down OpenOfferManager");
                injector.getInstance(OpenOfferManager.class).shutDown(() -> {

                    // listen for shut down of wallets setup
                    injector.getInstance(WalletsSetup.class).shutDownComplete.addListener((ov, o, n) -> {

                        // shut down p2p service
                        log.info("Shutting down P2P service");
                        injector.getInstance(P2PService.class).shutDown(() -> {
                            module.close(injector);
                            PersistenceManager.flushAllDataToDiskAtShutdown(() -> {

                                // done shutting down
                                log.info("Graceful shutdown completed. Exiting now.");
                                resultHandler.handleResult();
                                UserThread.runAfter(() -> System.exit(HavenoExecutable.EXIT_SUCCESS), 1);
                            });
                        });

                        // shut down trade and wallet services
                        log.info("Shutting down trade and wallet services");
                        injector.getInstance(OfferBookService.class).shutDown();
                        injector.getInstance(TradeManager.class).shutDown();
                        injector.getInstance(BtcWalletService.class).shutDown();
                        injector.getInstance(XmrWalletService.class).shutDown();
                        injector.getInstance(XmrConnectionService.class).shutDown();
                        injector.getInstance(WalletsSetup.class).shutDown();
                    });
                });

                // we wait max 5 sec.
                UserThread.runAfter(() -> {
                    PersistenceManager.flushAllDataToDiskAtShutdown(() -> {
                        resultHandler.handleResult();
                        log.info("Graceful shutdown caused a timeout. Exiting now.");
                        UserThread.runAfter(() -> System.exit(HavenoExecutable.EXIT_SUCCESS), 1);
                    });
                }, 5);
            } else {
                UserThread.runAfter(() -> {
                    resultHandler.handleResult();
                    System.exit(HavenoExecutable.EXIT_SUCCESS);
                }, 1);
            }
        } catch (Throwable t) {
            log.info("App shutdown failed with exception: {}\n", t.getMessage(), t);
            PersistenceManager.flushAllDataToDiskAtShutdown(() -> {
                resultHandler.handleResult();
                log.info("Graceful shutdown resulted in an error. Exiting now.");
                UserThread.runAfter(() -> System.exit(HavenoExecutable.EXIT_FAILURE), 1);
            });

        }
    }

    public void startShutDownInterval(GracefulShutDownHandler gracefulShutDownHandler) {
        if (DevEnv.isDevMode() || injector.getInstance(Config.class).useLocalhostForP2P) {
            return;
        }

        List<NodeAddress> seedNodeAddresses = new ArrayList<>(injector.getInstance(SeedNodeRepository.class).getSeedNodeAddresses());
        seedNodeAddresses.sort(Comparator.comparing(NodeAddress::getFullAddress));

        NodeAddress myAddress = injector.getInstance(P2PService.class).getNetworkNode().getNodeAddress();
        int myIndex = -1;
        for (int i = 0; i < seedNodeAddresses.size(); i++) {
            if (seedNodeAddresses.get(i).equals(myAddress)) {
                myIndex = i;
                break;
            }
        }

        if (myIndex == -1) {
            log.warn("We did not find our node address in the seed nodes repository. " +
                            "We use a 24 hour delay after startup as shut down strategy." +
                            "myAddress={}, seedNodeAddresses={}",
                    myAddress, seedNodeAddresses);

            UserThread.runPeriodically(() -> {
                if (System.currentTimeMillis() - startTime > SHUTDOWN_INTERVAL) {
                    log.warn("\n\n%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\n" +
                                    "Shut down as node was running longer as {} hours" +
                                    "\n%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\n\n",
                            SHUTDOWN_INTERVAL / 3600000);

                    shutDown(gracefulShutDownHandler);
                }

            }, CHECK_SHUTDOWN_SEC);
            return;
        }

        // We interpret the value of myIndex as hour of day (0-23). That way we avoid the risk of a restart of
        // multiple nodes around the same time in case it would be not deterministic.

        // We wrap our periodic check in a delay of 2 hours to avoid that we get
        // triggered multiple times after a restart while being in the same hour. It can be that we miss our target
        // hour during that delay but that is not considered problematic, the seed would just restart a bit longer than
        // 24 hours.
        int target = myIndex;
        UserThread.runAfter(() -> {
            // We check every hour if we are in the target hour.
            UserThread.runPeriodically(() -> {
                int currentHour = ZonedDateTime.ofInstant(Instant.now(), ZoneId.of("UTC")).getHour();
                if (currentHour == target) {
                    log.warn("\n\n%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\n" +
                                    "Shut down node at hour {} (UTC time is {})" +
                                    "\n%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\n\n",
                            target,
                            ZonedDateTime.ofInstant(Instant.now(), ZoneId.of("UTC")).toString());
                    shutDown(gracefulShutDownHandler);
                }
            }, TimeUnit.MINUTES.toSeconds(10));
        }, TimeUnit.HOURS.toSeconds(2));
    }

    protected void checkMemory(Config config, GracefulShutDownHandler gracefulShutDownHandler) {
        int maxMemory = config.maxMemory;
        UserThread.runPeriodically(() -> {
            Profiler.printSystemLoad();
            if (!stopped) {
                long usedMemoryInMB = Profiler.getUsedMemoryInMB();
                double warningTrigger = maxMemory * 0.8;
                if (usedMemoryInMB > warningTrigger) {
                    log.warn("\n\n%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\n" +
                                    "We are over 80% of our memory limit ({}) and call the GC. usedMemory: {} MB. freeMemory: {} MB" +
                                    "\n%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\n\n",
                            (int) warningTrigger, usedMemoryInMB, Profiler.getFreeMemoryInMB());
                    System.gc();
                    Profiler.printSystemLoad();
                }

                UserThread.runAfter(() -> {
                    log.info("Memory 2 sec. after calling the GC. usedMemory: {} MB. freeMemory: {} MB",
                            Profiler.getUsedMemoryInMB(), Profiler.getFreeMemoryInMB());
                }, 2);

                UserThread.runAfter(() -> {
                    long usedMemory = Profiler.getUsedMemoryInMB();
                    if (usedMemory > maxMemory) {
                        log.warn("\n\n%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\n" +
                                        "We are over our memory limit ({}) and trigger a shutdown. usedMemory: {} MB. freeMemory: {} MB" +
                                        "\n%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\n\n",
                                maxMemory, usedMemory, Profiler.getFreeMemoryInMB());
                        shutDown(gracefulShutDownHandler);
                    }
                }, 5);
            }
        }, CHECK_MEMORY_PERIOD_SEC);
    }

    protected void shutDown(GracefulShutDownHandler gracefulShutDownHandler) {
        stopped = true;
        gracefulShutDownHandler.gracefulShutDown(() -> {
            log.info("Shutdown complete");
            System.exit(1);
        });
    }
}
