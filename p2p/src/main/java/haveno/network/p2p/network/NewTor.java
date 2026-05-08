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

package haveno.network.p2p.network;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.berndpruenster.netlayer.tor.NativeTor;
import org.berndpruenster.netlayer.tor.Tor;
import org.berndpruenster.netlayer.tor.TorCtlException;
import org.berndpruenster.netlayer.tor.Torrc;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

/**
 * This class creates a brand new instance of the Tor onion router.
 *
 * When asked, the class checks, whether command line parameters such as
 * --torrcFile and --torrcOptions are set and if so, takes these settings into
 * account. Then, a fresh set of Tor binaries is installed and Tor is launched.
 * Finally, a {@link Tor} instance is returned for further use.
 *
 * @author Florian Reimair
 *
 */
@Slf4j
public class NewTor extends TorMode {

    // default torrc options (user can override with --torrcOptions)
    private static final Map<String, String> TORRC_OPTIONS_DEFAULT = new LinkedHashMap<>() {{
        //put("NumCPUs", "0");
    }};

    private final File torrcFile;
    private final String torrcOptions;
    private final BridgeAddressProvider bridgeAddressProvider;

    public NewTor(File torWorkingDirectory, @Nullable File torrcFile, String torrcOptions, BridgeAddressProvider bridgeAddressProvider) {
        super(torWorkingDirectory);
        this.torrcFile = torrcFile;
        this.torrcOptions = torrcOptions;
        this.bridgeAddressProvider = bridgeAddressProvider;
    }

    @Override
    public Tor getTor() throws IOException, TorCtlException {
        long ts1 = new Date().getTime();

        Collection<String> bridgeEntries = bridgeAddressProvider.getBridgeAddresses();
        if (bridgeEntries != null)
            log.info("Using bridges: {}", bridgeEntries.stream().collect(Collectors.joining(",")));

        // build map with torrc cli options
        LinkedHashMap<String, String> torrcOptionsCli = new LinkedHashMap<>();
        if (torrcOptions != null && !torrcOptions.isEmpty()) {
            boolean parseError = false;
            for (String line : torrcOptions.split(",")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.matches("^[^\\s]+\\s.+")) {
                    String[] tmp = line.split("\\s", 2);
                    torrcOptionsCli.put(tmp[0].trim(), tmp[1].trim());
                } else {
                    log.error("Custom torrc override parse error ('{}'). Discarding all CLI overrides.", line);
                    parseError = true;
                    break; 
                }
            }
            if (parseError) torrcOptionsCli.clear();
        }

        // build map with all torrc overrides
        LinkedHashMap<String, String> torrcOptionsOverride = new LinkedHashMap<>(TORRC_OPTIONS_DEFAULT);
        torrcOptionsOverride.putAll(torrcOptionsCli);

        // build the final torrc object
        Torrc torrcOverride;
        if (torrcFile != null && torrcFile.exists()) {
            try (FileInputStream fis = new FileInputStream(torrcFile)) {
                torrcOverride = new Torrc(fis, torrcOptionsOverride);
            } catch (IOException e) {
                log.error("Error reading custom torrc file ('{}'). Proceeding with defaults.", torrcFile.getAbsolutePath());
                torrcOverride = new Torrc(torrcOptionsOverride);
            }
        } else {
            // Falls here if torrcFile is null or doesn't exist
            torrcOverride = new Torrc(torrcOptionsOverride);
        }

        log.info("Starting tor");
        NativeTor result = new NativeTor(torDir, bridgeEntries, torrcOverride);
        log.info(
                "\n################################################################\n"
                        + "Tor started after {} ms. Start publishing hidden service.\n"
                        + "################################################################",
                (new Date().getTime() - ts1)); // takes usually a few seconds

        return result;
    }

    @Override
    public String getHiddenServiceDirectory() {
        return "";
    }
}
