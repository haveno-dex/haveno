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

package haveno.core.xmr.nodes;

import haveno.common.config.Config;
import haveno.core.trade.HavenoUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;

@Slf4j
public class XmrNodes {

    // TODO: rename to XmrNodeType ?
    public enum MoneroNodesOption {
        PROVIDED,
        CUSTOM,
        PUBLIC
    }

    public List<XmrNode> selectPreferredNodes(XmrNodesSetupPreferences xmrNodesSetupPreferences) {
        return xmrNodesSetupPreferences.selectPreferredNodes(this);
    }

    // TODO: always using null hostname
    public List<XmrNode> getAllXmrNodes() {
        switch (Config.baseCurrencyNetwork()) {
            case XMR_LOCAL:
                return Arrays.asList(
                    new XmrNode(MoneroNodesOption.PROVIDED, null, null, "127.0.0.1", 28081, 1, "@local")
                );
            case XMR_STAGENET:
                return Arrays.asList(
                    new XmrNode(MoneroNodesOption.PROVIDED, null, null, "127.0.0.1", 38081, 1, "@local"),
                    new XmrNode(MoneroNodesOption.PROVIDED, null, null, "127.0.0.1", 39081, 1, "@local"),
                    new XmrNode(MoneroNodesOption.PROVIDED, null, null, "45.63.8.26", 38081, 2, "@haveno"),
                    new XmrNode(MoneroNodesOption.PUBLIC, null, null, "node.sethforprivacy.com", 38089, 3, null),
                    new XmrNode(MoneroNodesOption.PUBLIC, null, null, "node2.sethforprivacy.com", 38089, 3, null),
                    new XmrNode(MoneroNodesOption.PUBLIC, null, "plowsof3t5hogddwabaeiyrno25efmzfxyro2vligremt7sxpsclfaid.onion", null, 38089, 3, null)
                );
            case XMR_MAINNET:
                return Arrays.asList(
                    new XmrNode(MoneroNodesOption.PUBLIC, null, null, "127.0.0.1", 18081, 1, "@local"),
                    new XmrNode(MoneroNodesOption.PUBLIC, null, null, "xmr-node.cakewallet.com", 18081, 2, "@cakewallet"),
                    new XmrNode(MoneroNodesOption.PUBLIC, null, null, "p2pmd.xmrvsbeast.com", 18081, 2, "@xmrvsbeast"),
                    new XmrNode(MoneroNodesOption.PUBLIC, null, null, "node.monerodevs.org", 18089, 2, "@monerodevs.org"),
                    new XmrNode(MoneroNodesOption.PUBLIC, null, null, "node2.monerodevs.org", 18089, 2, "@monerodevs.org"),
                    new XmrNode(MoneroNodesOption.PUBLIC, null, null, "node3.monerodevs.org", 18089, 2, "@monerodevs.org"),
                    new XmrNode(MoneroNodesOption.PUBLIC, null, null, "nodex.monerujo.io", 18081, 2, "@monerujo.io"),
                    new XmrNode(MoneroNodesOption.PUBLIC, null, null, "rucknium.me", 18081, 2, "@Rucknium"),
                    new XmrNode(MoneroNodesOption.PUBLIC, null, null, "node.sethforprivacy.com", 18089, 2, "@sethforprivacy"),
                    new XmrNode(MoneroNodesOption.PUBLIC, null, null, "selsta1.featherwallet.net", 18081, 2, "@selsta"),
                    new XmrNode(MoneroNodesOption.PUBLIC, null, null, "selsta2.featherwallet.net", 18081, 2, "@selsta"),
                    new XmrNode(MoneroNodesOption.PUBLIC, null, null, "node.xmr.ru", 18081, 2, "@xmr.ru"),
                    new XmrNode(MoneroNodesOption.PUBLIC, null, null, "xmr.stormycloud.org", 18089, 2, "@stormycloud"),
                    new XmrNode(MoneroNodesOption.PUBLIC, null, null, "ravfx.its-a-node.org", 18081, 2, "@ravfx"),
                    new XmrNode(MoneroNodesOption.PUBLIC, null, null, "ravfx2.its-a-node.org", 18089, 2, "@ravfx")
                    // new XmrNode(MoneroNodesOption.PUBLIC, null, "plowsof3t5hogddwabaeiyrno25efmzfxyro2vligremt7sxpsclfaid.onion", null, 18089, 3, "@plowsof"), // onions tend to have poorer performance
                    // new XmrNode(MoneroNodesOption.PUBLIC, null, "cakexmrl7bonq7ovjka5kuwuyd3f7qnkz6z6s6dmsy3uckwra7bvggyd.onion", null, 18081, 3, "@cakewallet")
                );
            default:
                throw new IllegalStateException("Unexpected base currency network: " + Config.baseCurrencyNetwork());
        }
    }

    public List<XmrNode> getProvidedXmrNodes() {
        return getXmrNodes(MoneroNodesOption.PROVIDED);
    }

    public List<XmrNode> getPublicXmrNodes() {
        return getXmrNodes(MoneroNodesOption.PUBLIC);
    }

    public List<XmrNode> getCustomXmrNodes() {
        return getXmrNodes(MoneroNodesOption.CUSTOM);
    }

    private List<XmrNode>  getXmrNodes(MoneroNodesOption type) {
        List<XmrNode> nodes = new ArrayList<>();
        for (XmrNode node : getAllXmrNodes()) if (node.getType() == type) nodes.add(node);
        return nodes;
    }

    public static List<XmrNodes.XmrNode> toCustomXmrNodesList(Collection<String> nodes) {
        return nodes.stream()
                .filter(e -> !e.isEmpty())
                .map(XmrNodes.XmrNode::fromFullAddress)
                .collect(Collectors.toList());
    }

    @EqualsAndHashCode
    @Getter
    public static class XmrNode {

        private final MoneroNodesOption type;
        @Nullable
        private final String onionAddress;
        @Nullable
        private final String hostName;
        @Nullable
        private final String operator; // null in case the user provides a list of custom btc nodes
        @Nullable
        private final String address; // IP address or host name
        private int port = HavenoUtils.getDefaultMoneroPort();
        private int priority = 0;

        /**
         * @param fullAddress [IP address:port, host name:port, or onion:port]
         * @return XmrNode instance
         */
        public static XmrNode fromFullAddress(String fullAddress) {
            ParsedAddress parsedAddress = parseFullAddress(fullAddress);
            String host = parsedAddress.host;
            int port = parsedAddress.port;

            checkArgument(host.length() > 0, "XmrNode address format not recognised");
            return host.contains(".onion") ? new XmrNode(MoneroNodesOption.CUSTOM, null, host, null, port, null, null) : new XmrNode(MoneroNodesOption.CUSTOM, null, null, host, port, null, null);
        }

        private static ParsedAddress parseFullAddress(String fullAddress) {
            checkArgument(fullAddress != null, "XmrNode address must not be null");
            String trimmedAddress = fullAddress.trim();
            checkArgument(!trimmedAddress.isEmpty(), "XmrNode address must not be empty");

            int port = HavenoUtils.getDefaultMoneroPort();
            String host;
            if (trimmedAddress.startsWith("[")) {
                int closingBracketIndex = trimmedAddress.indexOf("]");
                checkArgument(closingBracketIndex > 0, "Invalid bracketed IPv6 address: %s", fullAddress);
                host = trimmedAddress.substring(1, closingBracketIndex);
                checkArgument(isIpv6Literal(host), "Invalid bracketed IPv6 address: %s", fullAddress);

                String remainder = trimmedAddress.substring(closingBracketIndex + 1);
                if (!remainder.isEmpty()) {
                    checkArgument(remainder.startsWith(":") && remainder.length() > 1, "Invalid bracketed IPv6 address: %s", fullAddress);
                    port = parsePort(remainder.substring(1));
                }
            } else {
                int colonCount = countChars(trimmedAddress, ':');
                if (colonCount == 0) {
                    host = trimmedAddress;
                } else if (colonCount == 1) {
                    int lastColonIndex = trimmedAddress.lastIndexOf(':');
                    host = trimmedAddress.substring(0, lastColonIndex);
                    port = parsePort(trimmedAddress.substring(lastColonIndex + 1));
                } else if (isIpv6Literal(trimmedAddress)) {
                    host = trimmedAddress;
                } else {
                    int lastColonIndex = trimmedAddress.lastIndexOf(':');
                    String hostCandidate = trimmedAddress.substring(0, lastColonIndex);
                    String portCandidate = trimmedAddress.substring(lastColonIndex + 1);
                    checkArgument(isIpv6Literal(hostCandidate), "Invalid IPv6 address: %s", fullAddress);
                    host = hostCandidate;
                    port = parsePort(portCandidate);
                }
            }

            checkArgument(!host.isEmpty(), "XmrNode address format not recognised");
            return new ParsedAddress(stripIpv6Brackets(host), port);
        }

        private static int parsePort(String portString) {
            try {
                int port = Integer.parseInt(portString);
                checkArgument(port >= 0 && port <= 65535, "Invalid port: %s", portString);
                return port;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid port: " + portString, e);
            }
        }

        private static int countChars(String value, char character) {
            int count = 0;
            for (int i = 0; i < value.length(); i++) {
                if (value.charAt(i) == character) count++;
            }
            return count;
        }

        private static String stripIpv6Brackets(String host) {
            return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        }

        private static boolean isIpv6Literal(String host) {
            try {
                return host.contains(":") && InetAddress.getByName(host) instanceof Inet6Address;
            } catch (Exception e) {
                return false;
            }
        }

        private static String formatHostAndPort(String host, int port) {
            host = stripIpv6Brackets(host);
            return isIpv6Literal(host) ? "[" + host + "]:" + port : host + ":" + port;
        }

        private static class ParsedAddress {
            private final String host;
            private final int port;

            private ParsedAddress(String host, int port) {
                this.host = host;
                this.port = port;
            }
        }

        public XmrNode(MoneroNodesOption type,
                       @Nullable String hostName,
                       @Nullable String onionAddress,
                       @Nullable String address,
                       int port,
                       Integer priority,
                       @Nullable String operator) {
            this.type = type;
            this.hostName = hostName;
            this.onionAddress = onionAddress;
            this.address = address;
            this.port = port;
            this.priority = priority == null ? 0 : priority;
            this.operator = operator;
        }

        public String getHostNameOrAddress() {
            if (hostName != null)
                return hostName;
            else
                return address;
        }

        public String getHostNameOrAddressWithPort() {
            if (!hasClearNetAddress()) throw new IllegalStateException("XmrNode does not have clearnet address");
            return formatHostAndPort(getHostNameOrAddress(), port);
        }

        public String getOnionAddressWithPort() {
            if (!hasOnionAddress()) throw new IllegalStateException("XmrNode does not have onion address");
            return onionAddress + ":" + port;
        }

        public boolean hasOnionAddress() {
            return onionAddress != null;
        }

        public boolean hasClearNetAddress() {
            return hostName != null || address != null;
        }

        public String getClearNetUri() {
            if (!hasClearNetAddress()) throw new IllegalStateException("XmrNode does not have clearnet address");
            return "http://" + getHostNameOrAddressWithPort();
        }

        @Override
        public String toString() {
            return "onionAddress='" + onionAddress + '\'' +
                    ", hostName='" + hostName + '\'' +
                    ", address='" + address + '\'' +
                    ", port='" + port + '\'' +
                    ", priority='" + priority + '\'' +
                    ", operator='" + operator;
        }
    }
}
