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

package bisq.apitest.linux;

import java.nio.file.Paths;

import java.io.File;
import java.io.IOException;

import java.util.ArrayList;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

import static bisq.apitest.linux.BashCommand.isAlive;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MILLISECONDS;



import bisq.apitest.config.ApiTestConfig;
import bisq.apitest.config.HavenoAppConfig;
import bisq.daemon.app.HavenoDaemonMain;

/**
 * Runs a regtest/dao Bisq application instance in the background.
 */
@Slf4j
public class HavenoProcess extends AbstractLinuxProcess implements LinuxProcess {

    private final HavenoAppConfig havenoAppConfig;
    private final String baseCurrencyNetwork;
    private final String genesisTxId;
    private final int genesisBlockHeight;
    private final String seedNodes;
    private final boolean useLocalhostForP2P;
    public final boolean useDevPrivilegeKeys;
    private final String findBisqPidScript;
    private final String debugOpts;

    public HavenoProcess(HavenoAppConfig havenoAppConfig, ApiTestConfig config) {
        super(havenoAppConfig.appName, config);
        this.havenoAppConfig = havenoAppConfig;
        this.baseCurrencyNetwork = "XMR_STAGENET";
        this.genesisTxId = "30af0050040befd8af25068cc697e418e09c2d8ebd8d411d2240591b9ec203cf";
        this.genesisBlockHeight = 111;
        this.seedNodes = "localhost:2002";
        this.useLocalhostForP2P = true;
        this.useDevPrivilegeKeys = true;
        this.findBisqPidScript = (config.isRunningTest ? "." : "./apitest")
                + "/scripts/get-bisq-pid.sh";
        this.debugOpts = config.enableBisqDebugging
                ? " -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:" + havenoAppConfig.remoteDebugPort
                : "";
    }

    @Override
    public void start() {
        try {
            if (config.runSubprojectJars)
                runJar();           // run subproject/build/lib/*.jar (not full build)
            else
                runStartupScript(); // run bisq-* script for end to end test (default)
        } catch (Throwable t) {
            startupExceptions.add(t);
        }
    }

    @Override
    public long getPid() {
        return this.pid;
    }

    @Override
    public void shutdown() {
        try {
            log.info("Shutting down {} ...", havenoAppConfig.appName);
            if (!isAlive(pid)) {
                this.shutdownExceptions.add(new IllegalStateException(format("%s already shut down", havenoAppConfig.appName)));
                return;
            }

            String killCmd = "kill -15 " + pid;
            if (new BashCommand(killCmd).run().getExitStatus() != 0) {
                this.shutdownExceptions.add(new IllegalStateException(format("Could not shut down %s", havenoAppConfig.appName)));
                return;
            }

            // Be lenient about the time it takes for a java app to shut down.
            for (int i = 0; i < 5; i++) {
                if (!isAlive(pid)) {
                    log.info("{} stopped", havenoAppConfig.appName);
                    break;
                }
                MILLISECONDS.sleep(2500);
            }

            if (isAlive(pid)) {
                this.shutdownExceptions.add(new IllegalStateException(format("%s shutdown did not work", havenoAppConfig.appName)));
            }

        } catch (Exception e) {
            this.shutdownExceptions.add(new IllegalStateException(format("Error shutting down %s", havenoAppConfig.appName), e));
        }
    }

    public void verifyAppNotRunning() throws IOException, InterruptedException {
        long pid = findBisqAppPid();
        if (pid >= 0)
            throw new IllegalStateException(format("%s %s already running with pid %d",
                    havenoAppConfig.mainClassName, havenoAppConfig.appName, pid));
    }

    public void verifyAppDataDirInstalled() {
        // If we're running an Alice or Bob daemon, make sure the dao-setup directory
        // are installed.
        switch (havenoAppConfig) {
            case alicedaemon:
            case alicedesktop:
            case bobdaemon:
            case bobdesktop:
                File bisqDataDir = new File(config.rootAppDataDir, havenoAppConfig.appName);
                if (!bisqDataDir.exists())
                    throw new IllegalStateException(format("Application dataDir %s/%s not found",
                            config.rootAppDataDir, havenoAppConfig.appName));
                break;
            default:
                break;
        }
    }

    // This is the non-default way of running a Bisq app (--runSubprojectJars=true).
    // It runs a java cmd, and does not depend on a full build.  Bisq jars are loaded
    // from the :subproject/build/libs directories.
    private void runJar() throws IOException, InterruptedException {
        String java = getJavaExecutable().getAbsolutePath();
        String classpath = System.getProperty("java.class.path");
        String bisqCmd = getJavaOptsSpec()
                + " " + java + " -cp " + classpath
                + " " + havenoAppConfig.mainClassName
                + " " + String.join(" ", getOptsList())
                + " &"; // run in background without nohup
        runBashCommand(bisqCmd);
    }

    // This is the default way of running a Bisq app (--runSubprojectJars=false).
    // It runs a bisq-* startup script, and depends on a full build.  Bisq jars
    // are loaded from the root project's lib directory.
    private void runStartupScript() throws IOException, InterruptedException {
        String startupScriptPath = config.rootProjectDir
                + "/" + havenoAppConfig.startupScript;
        String bisqCmd = getJavaOptsSpec()
                + " " + startupScriptPath
                + " " + String.join(" ", getOptsList())
                + " &"; // run in background without nohup
        runBashCommand(bisqCmd);
    }

    private void runBashCommand(String bisqCmd) throws IOException, InterruptedException {
        String cmdDescription = config.runSubprojectJars
                ? "java -> " + havenoAppConfig.mainClassName + " -> " + havenoAppConfig.appName
                : havenoAppConfig.startupScript + " -> " + havenoAppConfig.appName;
        BashCommand bashCommand = new BashCommand(bisqCmd);
        log.info("Starting {} ...\n$ {}", cmdDescription, bashCommand.getCommand());
        bashCommand.runInBackground();

        if (bashCommand.getExitStatus() != 0)
            throw new IllegalStateException(format("Error starting BisqApp%n%s%nError: %s",
                    havenoAppConfig.appName,
                    bashCommand.getError()));

        // Sometimes it takes a little extra time to find the linux process id.
        // Wait up to two seconds before giving up and throwing an Exception.
        for (int i = 0; i < 4; i++) {
            pid = findBisqAppPid();
            if (pid != -1)
                break;

            MILLISECONDS.sleep(500L);
        }
        if (!isAlive(pid))
            throw new IllegalStateException(format("Error finding pid for %s", this.name));

        log.info("{} running with pid {}", cmdDescription, pid);
        log.info("Log {}", config.rootAppDataDir + "/" + havenoAppConfig.appName + "/bisq.log");
    }

    private long findBisqAppPid() throws IOException, InterruptedException {
        // Find the pid of the java process by grepping for the mainClassName and appName.
        String findPidCmd = findBisqPidScript + " " + havenoAppConfig.mainClassName + " " + havenoAppConfig.appName;
        String psCmdOutput = new BashCommand(findPidCmd).run().getOutput();
        return (psCmdOutput == null || psCmdOutput.isEmpty()) ? -1 : Long.parseLong(psCmdOutput);
    }

    private String getJavaOptsSpec() {
        return "export JAVA_OPTS=\"" + havenoAppConfig.javaOpts + debugOpts + "\"; ";
    }

    private List<String> getOptsList() {
        return new ArrayList<>() {{
            add("--appName=" + havenoAppConfig.appName);
            add("--appDataDir=" + config.rootAppDataDir.getAbsolutePath() + "/" + havenoAppConfig.appName);
            add("--nodePort=" + havenoAppConfig.nodePort);
            add("--rpcBlockNotificationPort=" + havenoAppConfig.rpcBlockNotificationPort);
            add("--rpcUser=" + config.bitcoinRpcUser);
            add("--rpcPassword=" + config.bitcoinRpcPassword);
            add("--rpcPort=" + config.bitcoinRpcPort);
            add("--seedNodes=" + seedNodes);
            add("--baseCurrencyNetwork=" + baseCurrencyNetwork);
            add("--useDevPrivilegeKeys=" + useDevPrivilegeKeys);
            add("--useLocalhostForP2P=" + useLocalhostForP2P);
            switch (havenoAppConfig) {
                case seednode:
                    break;   // no extra opts needed for seed node
                case arbdaemon:
                case arbdesktop:
                case alicedaemon:
                case alicedesktop:
                case bobdaemon:
                case bobdesktop:
                    add("--genesisBlockHeight=" + genesisBlockHeight);
                    add("--genesisTxId=" + genesisTxId);
                    if (havenoAppConfig.mainClassName.equals(HavenoDaemonMain.class.getName())) {
                        add("--apiPassword=" + config.apiPassword);
                        add("--apiPort=" + havenoAppConfig.apiPort);
                    }
                    break;
                default:
                    throw new IllegalStateException("Unknown HavenoAppConfig " + havenoAppConfig.name());
            }
        }};
    }

    private File getJavaExecutable() {
        File javaHome = Paths.get(System.getProperty("java.home")).toFile();
        if (!javaHome.exists())
            throw new IllegalStateException(format("$JAVA_HOME not found, cannot run %s", havenoAppConfig.mainClassName));

        File javaExecutable = Paths.get(javaHome.getAbsolutePath(), "bin", "java").toFile();
        if (javaExecutable.exists() || javaExecutable.canExecute())
            return javaExecutable;
        else
            throw new IllegalStateException("$JAVA_HOME/bin/java not found or executable");
    }
}
