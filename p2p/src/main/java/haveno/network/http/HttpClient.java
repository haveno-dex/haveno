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

package haveno.network.http;

import javax.annotation.Nullable;
import java.io.IOException;

public interface HttpClient {
    void setBaseUrl(String baseUrl);

    void setIgnoreSocks5Proxy(boolean ignoreSocks5Proxy);

    String get(String param,
               @Nullable String headerKey,
               @Nullable String headerValue) throws IOException;

    String post(String param,
                @Nullable String headerKey,
                @Nullable String headerValue) throws IOException;

    String getUid();

    String getBaseUrl();

    boolean hasPendingRequest();

    void shutDown();
}
