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

import haveno.common.app.Version;
import haveno.network.Socks5ProxyProvider;
import haveno.network.http.HttpClientImpl;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;

@Singleton
public class MempoolHttpClient extends HttpClientImpl {
    @Inject
    public MempoolHttpClient(@Nullable Socks5ProxyProvider socks5ProxyProvider) {
        super(socks5ProxyProvider);
    }

    // returns JSON of the transaction details
    public String getTxDetails(String txId) throws IOException {
        super.shutDown(); // close any prior incomplete request
        String api = "/" + txId;
        return get(api, "User-Agent", "haveno/" + Version.VERSION);
    }
}
