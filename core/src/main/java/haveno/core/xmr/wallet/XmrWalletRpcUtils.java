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

package haveno.core.xmr.wallet;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import monero.common.MoneroUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class XmrWalletRpcUtils {

    private static final Logger log = LoggerFactory.getLogger(XmrWalletRpcUtils.class);

    private XmrWalletRpcUtils() {
    }

    static String getProxyUri(String proxyUri, boolean supportsSocks5ProxyScheme) {
        if (!supportsSocks5ProxyScheme || proxyUri.contains("://")) return proxyUri;
        return "socks5://" + proxyUri;
    }

    static boolean detectSocks5ProxySchemeSupport(String walletRpcPath) {
        if (!new File(walletRpcPath).exists()) return false;
        try {
            Process process = new ProcessBuilder(walletRpcPath, "--help").redirectErrorStream(true).start();
            boolean exited = process.waitFor(10, TimeUnit.SECONDS);
            String helpText = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!exited) {
                process.destroyForcibly();
                return false;
            }
            return helpText.contains("socks5://");
        } catch (Exception e) {
            log.warn("Could not detect monero-wallet-rpc SOCKS5 proxy URI support", e);
            return false;
        }
    }

    static boolean isIpv6Uri(String uri) {
        try {
            String host = MoneroUtils.parseUri(uri).getHost();
            return host != null && host.contains(":");
        } catch (Exception e) {
            return false;
        }
    }
}
