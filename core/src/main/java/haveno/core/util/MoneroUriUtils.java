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

package haveno.core.util;

import monero.wallet.model.MoneroDestination;
import monero.wallet.model.MoneroTxConfig;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class MoneroUriUtils {

    public static String makeUri(List<MoneroDestination> destinations, String label) {
        if (destinations == null || destinations.isEmpty()) {
            throw new IllegalArgumentException("Destinations cannot be null or empty");
        }

        StringBuilder sb = new StringBuilder("monero:");
        
        // Append addresses separated by semicolon
        for (int i = 0; i < destinations.size(); i++) {
            sb.append(destinations.get(i).getAddress());
            if (i < destinations.size() - 1) {
                sb.append(";");
            }
        }

        boolean firstParam = true;
        
        // Check if any amount is present
        boolean hasAmounts = false;
        for (MoneroDestination dest : destinations) {
            if (dest.getAmount() != null) {
                hasAmounts = true;
                break;
            }
        }

        if (hasAmounts) {
            sb.append("?tx_amount=");
            for (int i = 0; i < destinations.size(); i++) {
                BigInteger amount = destinations.get(i).getAmount();
                if (amount != null) {
                    String amountStr = new BigDecimal(amount).divide(new BigDecimal("1000000000000")).stripTrailingZeros().toPlainString();
                    if (!amountStr.contains(".")) amountStr += ".0";
                    sb.append(amountStr);
                }
                if (i < destinations.size() - 1) {
                    sb.append(";");
                }
            }
            firstParam = false;
        }

        if (label != null && !label.isEmpty()) {
            sb.append(firstParam ? "?" : "&");
            sb.append("tx_description=").append(URLEncoder.encode(label, StandardCharsets.UTF_8));
        }

        return sb.toString();
    }

    public static MoneroTxConfig parseUri(String uriStr) {
        if (uriStr == null || !uriStr.startsWith("monero:")) {
            throw new IllegalArgumentException("Invalid Monero URI");
        }

        String content = uriStr.substring("monero:".length());
        String path;
        String query = null;

        int queryIndex = content.indexOf('?');
        if (queryIndex != -1) {
            path = content.substring(0, queryIndex);
            query = content.substring(queryIndex + 1);
        } else {
            path = content;
        }

        String[] addresses = path.split(";");
        Map<String, String> params = parseQuery(query);

        List<MoneroDestination> destinations = new ArrayList<>();
        String[] amounts = params.getOrDefault("tx_amount", "").split(";");

        for (int i = 0; i < addresses.length; i++) {
            BigInteger atomicAmount = null;
            if (i < amounts.length && !amounts[i].isEmpty()) {
                try {
                    atomicAmount = new BigDecimal(amounts[i]).multiply(new BigDecimal("1000000000000")).toBigInteger();
                } catch (Exception ignored) {}
            }
            destinations.add(new MoneroDestination(addresses[i], atomicAmount));
        }

        MoneroTxConfig config = new MoneroTxConfig().setDestinations(destinations);
        
        // Extract note from potential label/description parameters
        String note = params.get("tx_description");
        if (note == null || note.isEmpty()) note = params.get("tx_note");
        if (note == null || note.isEmpty()) note = params.get("recipient_name");
        if (note == null || note.isEmpty()) note = params.get("label");
        
        if (note != null && !note.isEmpty()) {
            config.setNote(note);
        }

        return config;
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) return params;
        
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            if (idx != -1) {
                String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                params.put(key, value);
            }
        }
        return params;
    }
}
