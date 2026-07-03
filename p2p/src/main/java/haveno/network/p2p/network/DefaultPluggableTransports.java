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

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Built-in pluggable transport configuration for Haveno's bundled Tor.
 * <p>
 * Haveno bundles only Snowflake. Unlike obfs4/webtunnel bridge <i>lists</i> - which name
 * specific relays/domains that censors enumerate and block, and which therefore go stale and
 * must be handed out fresh per user - a Snowflake configuration is static yet self-renewing: it
 * rendezvous (via domain fronting) with an ever-changing pool of volunteer proxies. Users who
 * need obfs4/webtunnel should fetch fresh bridges from https://bridges.torproject.org and paste
 * them as custom bridges.
 */
public class DefaultPluggableTransports {

    /**
     * Pluggable transports that Haveno's bundled Tor actually registers. These must stay in sync
     * with the {@code ClientTransportPlugin} lines in netlayer's {@code torrc.native} (obfs4,
     * webtunnel, snowflake, conjure). Bridge lines using any other transport (e.g. obfs3, meek)
     * cannot be started by the bundled Tor and are therefore filtered out before being passed to it.
     */
    public static final Set<String> SUPPORTED_TRANSPORTS = Set.of("obfs4", "webtunnel", "snowflake", "conjure");

    /**
     * Canonical Snowflake bridges. Copied verbatim from the bundled Tor expert bundle's
     * {@code pluggable_transports/pt_config.json} (see the tor-binary project). Refresh these when
     * bumping the bundled Tor version, as the broker URL/fronts are occasionally rotated.
     */
    public static final List<String> SNOWFLAKE = Arrays.asList(
            "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=https://1098762253.rsc.cdn77.org/ fronts=app.datapacket.com,www.datapacket.com ice=stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.telnyx.com:3478,stun:stun.hot-chilli.net:3478,stun:stun.fitauto.ru:3478,stun:stun.m-online.net:3478 utls-imitate=hellorandomizedalpn",
            "snowflake 192.0.2.4:80 8838024498816A039FCBBAB14E6F40A0843051FA fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA url=https://1098762253.rsc.cdn77.org/ fronts=app.datapacket.com,www.datapacket.com ice=stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.telnyx.com:3478,stun:stun.hot-chilli.net:3478,stun:stun.fitauto.ru:3478,stun:stun.m-online.net:3478 utls-imitate=hellorandomizedalpn");

    /**
     * Returns the pluggable transport name of a bridge line in lower case, or an empty string for a
     * vanilla {@code host:port [fingerprint]} bridge (which names no transport) and for blank or
     * comment lines. A transport name (e.g. {@code obfs4}) contains neither a dot nor a colon,
     * whereas the leading {@code host:port} of a vanilla bridge always does.
     */
    public static String transportOf(String bridgeLine) {
        if (bridgeLine == null) return "";
        String line = bridgeLine.trim();
        if (line.isEmpty() || line.startsWith("#")) return "";
        String firstToken = line.split("\\s+", 2)[0];
        if (firstToken.contains(":") || firstToken.contains(".")) return ""; // vanilla host:port bridge
        return firstToken.toLowerCase(Locale.ROOT);
    }

    /**
     * Returns true if the given non-blank, non-comment bridge line can be run by Haveno's bundled
     * Tor: either a vanilla bridge or one using a {@link #SUPPORTED_TRANSPORTS supported transport}.
     */
    public static boolean isSupportedBridge(String bridgeLine) {
        String transport = transportOf(bridgeLine);
        if (transport.isEmpty()) {
            String line = bridgeLine == null ? "" : bridgeLine.trim();
            return !line.isEmpty() && !line.startsWith("#"); // vanilla bridge, but not a blank/comment line
        }
        return SUPPORTED_TRANSPORTS.contains(transport);
    }
}
