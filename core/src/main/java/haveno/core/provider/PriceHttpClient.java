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

package haveno.core.provider;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import haveno.network.Socks5ProxyProvider;
import haveno.network.http.HttpClientImpl;
import javax.annotation.Nullable;

@Singleton
public class PriceHttpClient extends HttpClientImpl {
    @Inject
    public PriceHttpClient(@Nullable Socks5ProxyProvider socks5ProxyProvider) {
        super(socks5ProxyProvider);
    }
}
