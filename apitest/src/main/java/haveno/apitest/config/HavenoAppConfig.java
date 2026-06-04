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

package haveno.apitest.config;

import haveno.daemon.app.HavenoDaemonMain;
import haveno.desktop.app.HavenoAppMain;
import haveno.seednode.SeedNodeMain;

/**
 Some non user configurable Haveno seednode, arb node, bob and alice daemon option values.
 Keep in sync with rootProject.ext JVM args in build.gradle.
 @see <a href="https://github.com/bisq-network/bisq/blob/master/docs/dev-setup.md">dev-setup.md</a>
 */
public enum HavenoAppConfig {

    seednode("haveno-XMR_STAGENET_Seed_2002",
            "haveno-seednode",
            spawnHeadlessOpts("2g", "256m", "2g"),
            SeedNodeMain.class.getName(),
            2002,
            5120,
            -1,
            49996),
    arbdaemon("haveno-XMR_STAGENET_Arb",
            "haveno-daemon",
            spawnHeadlessOpts("2g", "256m", "2g"),
            HavenoDaemonMain.class.getName(),
            4444,
            5121,
            9997,
            49997),
    arbdesktop("haveno-XMR_STAGENET_Arb",
            "haveno-desktop",
            spawnDesktopOpts("3g", "384m", "3g"),
            HavenoAppMain.class.getName(),
            4444,
            5121,
            -1,
            49997),
    alicedaemon("haveno-XMR_STAGENET_Alice",
            "haveno-daemon",
            spawnHeadlessOpts("2g", "256m", "2g"),
            HavenoDaemonMain.class.getName(),
            7777,
            5122,
            9998,
            49998),
    alicedesktop("haveno-XMR_STAGENET_Alice",
            "haveno-desktop",
            spawnDesktopOpts("4g", "512m", "4g"),
            HavenoAppMain.class.getName(),
            7777,
            5122,
            -1,
            49998),
    bobdaemon("haveno-XMR_STAGENET_Bob",
            "haveno-daemon",
            spawnHeadlessOpts("2g", "256m", "2g"),
            HavenoDaemonMain.class.getName(),
            8888,
            5123,
            9999,
            49999),
    bobdesktop("haveno-XMR_STAGENET_Bob",
            "haveno-desktop",
            spawnDesktopOpts("4g", "512m", "4g"),
            HavenoAppMain.class.getName(),
            8888,
            5123,
            -1,
            49999);

    private static final String LOGBACK_OPT =
            "-Dlogback.configurationFile=apitest/build/resources/main/logback.xml";

    private static final String[] COMMON_JVM_OPTS = {
            "-XX:+ExitOnOutOfMemoryError",
            "-XX:+HeapDumpOnOutOfMemoryError",
            "-XX:HeapDumpPath=haveno-%p.hprof",
            "-XX:+EnableDynamicAgentLoading",
            "-Djava.net.preferIPv4Stack=true",
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
    };

    private static final String[] GC_JVM_OPTS = {
            "-XX:+UseZGC",
            "-XX:+ZGenerational",
            "-XX:ZUncommitDelay=300",
            "-XX:+SegmentedCodeCache",
            "-XX:MaxMetaspaceSize=512m",
            "-XX:ReservedCodeCacheSize=256m",
    };

    private static final String[] DESKTOP_MODULE_OPENS = {
            "--add-modules=javafx.controls,javafx.fxml,javafx.swing",
            "--add-opens=javafx.controls/com.sun.javafx.scene.control.behavior=ALL-UNNAMED",
            "--add-opens=javafx.controls/com.sun.javafx.scene.control=ALL-UNNAMED",
            "--add-opens=javafx.base/com.sun.javafx.binding=ALL-UNNAMED",
            "--add-opens=javafx.base/com.sun.javafx.event=ALL-UNNAMED",
            "--add-opens=javafx.graphics/com.sun.javafx.scene=ALL-UNNAMED",
            "--add-opens=javafx.graphics/com.sun.javafx.scene.text=ALL-UNNAMED",
            "--add-opens=javafx.graphics/com.sun.javafx.css=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.base/java.nio=ALL-UNNAMED",
            "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    };

    private static String spawnHeadlessOpts(String maxRam, String xms, String xmx) {
        return String.join(" ",
                "-XX:MaxRAM=" + maxRam,
                "-Xms" + xms,
                "-Xmx" + xmx,
                softMaxHeapSize(maxRam),
                String.join(" ", GC_JVM_OPTS),
                String.join(" ", COMMON_JVM_OPTS),
                "-Djava.awt.headless=true",
                "-Xss1m",
                LOGBACK_OPT);
    }

    private static String spawnDesktopOpts(String maxRam, String xms, String xmx) {
        return String.join(" ",
                "-XX:MaxRAM=" + maxRam,
                "-Xms" + xms,
                "-Xmx" + xmx,
                softMaxHeapSize(maxRam),
                String.join(" ", GC_JVM_OPTS),
                String.join(" ", COMMON_JVM_OPTS),
                "-Xss1280k",
                String.join(" ", DESKTOP_MODULE_OPENS),
                LOGBACK_OPT);
    }

    private static String softMaxHeapSize(String maxRam) {
        return switch (maxRam) {
            case "4g" -> "-XX:SoftMaxHeapSize=3584m";
            case "3g" -> "-XX:SoftMaxHeapSize=2688m";
            default -> "-XX:SoftMaxHeapSize=1792m";
        };
    }

    public final String appName;
    public final String startupScript;
    public final String javaOpts;
    public final String mainClassName;
    public final int nodePort;
    public final int rpcBlockNotificationPort;
    // Daemons can use a global gRPC password, but each needs a unique apiPort.
    public final int apiPort;
    public final int remoteDebugPort;

    HavenoAppConfig(String appName,
                  String startupScript,
                  String javaOpts,
                  String mainClassName,
                  int nodePort,
                  int rpcBlockNotificationPort,
                  int apiPort,
                  int remoteDebugPort) {
        this.appName = appName;
        this.startupScript = startupScript;
        this.javaOpts = javaOpts;
        this.mainClassName = mainClassName;
        this.nodePort = nodePort;
        this.rpcBlockNotificationPort = rpcBlockNotificationPort;
        this.apiPort = apiPort;
        this.remoteDebugPort = remoteDebugPort;
    }

    @Override
    public String toString() {
        return "HavenoAppConfig{" + "\n" +
                "  appName='" + appName + '\'' + "\n" +
                ", startupScript='" + startupScript + '\'' + "\n" +
                ", javaOpts='" + javaOpts + '\'' + "\n" +
                ", mainClassName='" + mainClassName + '\'' + "\n" +
                ", nodePort=" + nodePort + "\n" +
                ", rpcBlockNotificationPort=" + rpcBlockNotificationPort + "\n" +
                ", apiPort=" + apiPort + "\n" +
                ", remoteDebugPort=" + remoteDebugPort + "\n" +
                '}';
    }
}
