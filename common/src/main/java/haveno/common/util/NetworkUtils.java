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

package haveno.common.util;

import com.google.common.net.InetAddresses;

import java.net.Inet6Address;
import java.net.InetAddress;

public class NetworkUtils {

    private NetworkUtils() {
    }

    public static HostAndPort parseHostAndPort(String address, int defaultPort) {
        return parseHostAndPort(address, defaultPort, false);
    }

    public static HostAndPort parseHostAndPort(String address, int defaultPort, boolean portRequired) {
        if (address == null) throw new IllegalArgumentException("Address must not be null");
        String trimmedAddress = address.trim();
        if (trimmedAddress.isEmpty()) throw new IllegalArgumentException("Address must not be empty");

        String host;
        int port = defaultPort;
        if (trimmedAddress.startsWith("[")) {
            int closingBracketIndex = trimmedAddress.indexOf("]");
            if (closingBracketIndex <= 0) throw new IllegalArgumentException("Invalid bracketed IPv6 address");
            host = trimmedAddress.substring(1, closingBracketIndex);
            if (!isIpv6Literal(host)) throw new IllegalArgumentException("Invalid bracketed IPv6 address");

            String remainder = trimmedAddress.substring(closingBracketIndex + 1);
            if (remainder.isEmpty()) {
                if (portRequired) throw new IllegalArgumentException("Missing port");
            } else {
                if (!remainder.startsWith(":") || remainder.length() == 1) throw new IllegalArgumentException("Missing port");
                port = parsePort(remainder.substring(1));
            }
        } else {
            int colonCount = countChars(trimmedAddress, ':');
            if (colonCount == 0) {
                host = trimmedAddress;
                if (portRequired) throw new IllegalArgumentException("Missing port");
            } else if (colonCount == 1) {
                int colonIndex = trimmedAddress.lastIndexOf(':');
                host = trimmedAddress.substring(0, colonIndex);
                if (host.isEmpty()) throw new IllegalArgumentException("Address format not recognised");
                port = parsePort(trimmedAddress.substring(colonIndex + 1));
            } else if (isIpv6Literal(trimmedAddress)) {
                host = trimmedAddress;
                if (portRequired) throw new IllegalArgumentException("Missing port");
            } else {
                throw new IllegalArgumentException("Invalid IPv6 address");
            }
        }

        if (host.isEmpty()) throw new IllegalArgumentException("Address format not recognised");
        return new HostAndPort(stripIpv6Brackets(host), port);
    }

    public static int parsePort(String portString) {
        try {
            int port = Integer.parseInt(portString);
            if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid port: " + portString);
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port: " + portString, e);
        }
    }

    public static String formatHost(String host) {
        host = stripIpv6Brackets(host);
        return isIpv6Literal(host) ? "[" + host + "]" : host;
    }

    public static String formatHostAndPort(String host, int port) {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid port: " + port);
        return formatHost(host) + ":" + port;
    }

    public static String stripIpv6Brackets(String host) {
        return host != null && host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    public static boolean isLiteralIp(String host) {
        host = stripIpv6Brackets(host);
        return host != null && InetAddresses.isInetAddress(host);
    }

    public static boolean isIpv6Literal(String host) {
        host = stripIpv6Brackets(host);
        return host != null && host.indexOf(':') >= 0 && InetAddresses.isInetAddress(host) && InetAddresses.forString(host) instanceof Inet6Address;
    }

    public static InetAddress getLiteralIpAddress(String host) {
        host = stripIpv6Brackets(host);
        if (!isLiteralIp(host)) throw new IllegalArgumentException("Host is not a literal IP address: " + host);
        return InetAddresses.forString(host);
    }

    private static int countChars(String value, char character) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == character) count++;
        }
        return count;
    }

    public static class HostAndPort {
        private final String host;
        private final int port;

        private HostAndPort(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public boolean hasPort() {
            return port >= 0;
        }
    }
}
