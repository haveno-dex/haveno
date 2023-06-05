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
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
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
                    new XmrNode(MoneroNodesOption.PROVIDED, null, null, "45.63.8.26", 38081, 1, "@haveno"),
                    new XmrNode(MoneroNodesOption.PROVIDED, null, null, "stagenet.community.rino.io", 38081, 2, "@RINOwallet"),
                    new XmrNode(MoneroNodesOption.PUBLIC, null, null, "stagenet.melo.tools", 38081, 2, null),
                    new XmrNode(MoneroNodesOption.PUBLIC, null, null, "node.sethforprivacy.com", 38089, 2, null),
                    new XmrNode(MoneroNodesOption.PUBLIC, null, null, "node2.sethforprivacy.com", 38089, 2, null),
                    new XmrNode(MoneroNodesOption.PUBLIC, null, "plowsof3t5hogddwabaeiyrno25efmzfxyro2vligremt7sxpsclfaid.onion", null, 38089, 2, null)
                );
            case XMR_MAINNET:
                return Arrays.asList(
                    new XmrNode(MoneroNodesOption.PROVIDED, null, null, "127.0.0.1", 18081, 1, "@local"),
                    new XmrNode(MoneroNodesOption.PROVIDED, null, null, "xmr-node.cakewallet.com", 18081, 1, "@cakewallet"),
                    new XmrNode(MoneroNodesOption.PUBLIC, null, null, "node.community.rino.io", 18081, 2, "@RINOwallet"),
                    new XmrNode(MoneroNodesOption.PUBLIC, null, null, "xmr-node-eu.cakewallet.com", 18081, 2, "@cakewallet"),
                    new XmrNode(MoneroNodesOption.PUBLIC, null, null, "xmr-node-usa-east.cakewallet.com", 18081, 2, "@cakewallet"),
                    new XmrNode(MoneroNodesOption.PUBLIC, null, null, "xmr-node-uk.cakewallet.com", 18081, 2, "@cakewallet"),
                    new XmrNode(MoneroNodesOption.PUBLIC, null, null, "node.sethforprivacy.com", 18089, 2, "@sethforprivacy")
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
        private static final int DEFAULT_PORT = Config.baseCurrencyNetworkParameters().getPort();

        private final MoneroNodesOption type;
        @Nullable
        private final String onionAddress;
        @Nullable
        private final String hostName;
        @Nullable
        private final String operator; // null in case the user provides a list of custom btc nodes
        @Nullable
        private final String address; // IPv4 address
        private int port = DEFAULT_PORT;
        private int priority = 0;

        /**
         * @param fullAddress [IPv4 address:port or onion:port]
         * @return XmrNode instance
         */
        public static XmrNode fromFullAddress(String fullAddress) {
            String[] parts = fullAddress.split("]");
            checkArgument(parts.length > 0);
            String host = "";
            int port = DEFAULT_PORT;
            if (parts[0].contains("[") && parts[0].contains(":")) {
                // IPv6 address and optional port number
                // address part delimited by square brackets e.g. [2a01:123:456:789::2]:8333
                host = parts[0].replace("[", "").replace("]", "");
                if (parts.length == 2)
                    port = Integer.parseInt(parts[1].replace(":", ""));
            } else if (parts[0].contains(":") && !parts[0].contains(".")) {
                // IPv6 address only; not delimited by square brackets
                host = parts[0];
            } else if (parts[0].contains(".")) {
                // address and an optional port number
                // e.g. 127.0.0.1:8333 or abcdef123xyz.onion:9999
                parts = fullAddress.split(":");
                checkArgument(parts.length > 0);
                host = parts[0];
                if (parts.length == 2)
                    port = Integer.parseInt(parts[1]);
            }

            checkArgument(host.length() > 0, "XmrNode address format not recognised");
            return host.contains(".onion") ? new XmrNode(MoneroNodesOption.CUSTOM, null, host, null, port, null, null) : new XmrNode(MoneroNodesOption.CUSTOM, null, null, host, port, null, null);
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

        public boolean hasOnionAddress() {
            return onionAddress != null;
        }

        public String getHostNameOrAddress() {
            if (hostName != null)
                return hostName;
            else
                return address;
        }

        public boolean hasClearNetAddress() {
            return hostName != null || address != null;
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
