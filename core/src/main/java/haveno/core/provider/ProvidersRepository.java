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

package haveno.core.provider;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import haveno.common.config.Config;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProvidersRepository {

    private static final String DEFAULT_LOCAL_NODE = "http://localhost:8078/";
    private static final List<String> DEFAULT_NODES = Arrays.asList(
            "http://elaxlgigphpicy5q7pi5wkz2ko2vgjbq4576vic7febmx4xcxvk6deqd.onion/", // Haveno
            "http://lrrgpezvdrbpoqvkavzobmj7dr2otxc5x6wgktrw337bk6mxsvfp5yid.onion/", // Cake
            "http://2c6y3sqmknakl3fkuwh4tjhxb2q5isr53dnfcqs33vt3y7elujc6tyad.onion/" // boldsuck
    );

    private final Config config;
    private final List<String> providersFromProgramArgs;
    private final boolean useLocalhostForP2P;

    private List<String> providerList;
    @Getter
    private String baseUrl = "";
    @Getter
    @Nullable
    private List<String> bannedNodes;
    private int index = -1;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public ProvidersRepository(Config config,
                               @Named(Config.PROVIDERS) List<String> providers,
                               @Named(Config.USE_LOCALHOST_FOR_P2P) boolean useLocalhostForP2P) {

        this.config = config;
        this.providersFromProgramArgs = providers;
        this.useLocalhostForP2P = useLocalhostForP2P;

        Collections.shuffle(DEFAULT_NODES); // randomize order of default nodes

        applyBannedNodes(config.bannedPriceRelayNodes);
    }

    public void applyBannedNodes(@Nullable List<String> bannedNodes) {
        this.bannedNodes = bannedNodes;

        // fill provider list
        fillProviderList();

        // select next provider if current provider is null or banned
        if (baseUrl.isEmpty() || isBanned(baseUrl)) selectNextProviderBaseUrl();

        if (bannedNodes != null && !bannedNodes.isEmpty()) {
            log.info("Excluded provider nodes from filter: nodes={}, selected provider baseUrl={}, providerList={}", bannedNodes, baseUrl, providerList);
        }
    }

    // returns true if provider selection loops to beginning
    public synchronized boolean selectNextProviderBaseUrl() {
        boolean looped = false;
        if (!providerList.isEmpty()) {

            // increment index
            index++;

            // loop to beginning
            if (index >= providerList.size()) {
                index = 0;
                looped = true;
            }

            // update base url
            baseUrl = providerList.get(index);
            log.info("Selected price provider: " + baseUrl);

            if (providerList.size() == 1 && config.baseCurrencyNetwork.isMainnet())
                log.warn("We only have one provider");
        } else {
            baseUrl = "";
            log.warn("We do not have any providers. That can be if all providers are filtered or providersFromProgramArgs is set but empty. " +
                    "bannedNodes={}. providersFromProgramArgs={}", bannedNodes, providersFromProgramArgs);
        }
        return looped;
    }

    private void fillProviderList() {
        List<String> providers;
        if (providersFromProgramArgs.isEmpty()) {
            if (useLocalhostForP2P) {
                // If we run in localhost mode we don't have the tor node running, so we need a clearnet host
                // Use localhost for using a locally running provider
                providers = List.of(
                    DEFAULT_LOCAL_NODE,
                    "https://price.haveno.network/",
                    "http://173.230.142.36:8078/");
            } else {
                providers = new ArrayList<String>();
                //providers.add(DEFAULT_LOCAL_NODE); // try local provider first
                providers.addAll(DEFAULT_NODES);
            }
        } else {
            providers = providersFromProgramArgs;
        }
        providerList = providers.stream()
                .filter(e -> !isBanned(e))
                .map(e -> e.endsWith("/") ? e : e + "/")
                .map(e -> e.startsWith("http") ? e : "http://" + e)
                .collect(Collectors.toList());
    }

    private boolean isBanned(String provider) {
        if (bannedNodes == null) return false;
        return bannedNodes.stream()
                .anyMatch(e -> provider.replace("http://", "")
                        .replace("/", "")
                        .replace(".onion", "")
                        .equals(e));
    }
}
