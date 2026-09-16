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

package haveno.network.http;

import com.runjva.sourceforge.jsocks.protocol.Socks5Proxy;
import haveno.common.app.Version;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import javax.annotation.Nullable;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.socket.ConnectionSocketFactory;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.pool.PoolReusePolicy;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

// Downloads a file, proxying only http/https URLs (other schemes, e.g. jar:/file:, don't support a proxy).
public class Socks5FileDownloader {

    public interface ProgressListener {
        void onProgress(long bytesRead, long totalBytes);
    }

    private Socks5FileDownloader() {
    }

    public static void download(URL url,
                                File destination,
                                @Nullable Socks5Proxy socks5Proxy,
                                int connectTimeoutMs,
                                int readTimeoutMs,
                                @Nullable ProgressListener progressListener) throws IOException {
        if (socks5Proxy == null || !isHttpOrHttps(url)) {
            downloadDirect(url, destination, connectTimeoutMs, readTimeoutMs, progressListener);
        } else {
            downloadViaProxy(url, destination, socks5Proxy, connectTimeoutMs, readTimeoutMs, progressListener);
        }
    }

    public static boolean isHttpOrHttps(URL url) {
        String protocol = url.getProtocol();
        return "http".equalsIgnoreCase(protocol) || "https".equalsIgnoreCase(protocol);
    }

    private static void downloadDirect(URL url,
                                       File destination,
                                       int connectTimeoutMs,
                                       int readTimeoutMs,
                                       @Nullable ProgressListener progressListener) throws IOException {
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.connect();
        try (InputStream inputStream = connection.getInputStream()) {
            streamToFile(inputStream, destination, connection.getContentLengthLong(), progressListener);
        }
    }

    private static void downloadViaProxy(URL url,
                                         File destination,
                                         Socks5Proxy socks5Proxy,
                                         int connectTimeoutMs,
                                         int readTimeoutMs,
                                         @Nullable ProgressListener progressListener) throws IOException {
        var registry = RegistryBuilder.<ConnectionSocketFactory>create()
                .register("http", new SocksAuthenticatedConnectionSocketFactory(socks5Proxy))
                .register("https", new SocksAuthenticatedSSLConnectionSocketFactory(SSLContexts.createSystemDefault(), socks5Proxy))
                .build();

        // FakeDnsResolver avoids a local DNS lookup happening before our socket factory connects via jsocks.
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager(
                registry,
                PoolConcurrencyPolicy.STRICT,
                PoolReusePolicy.LIFO,
                TimeValue.ofMinutes(5),
                null,
                new FakeDnsResolver(),
                null);
        cm.setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setSocketTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                .build());

        try (CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                        .setResponseTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                        .build())
                .build()) {
            HttpGet request = new HttpGet(url.toString());
            request.setHeader("User-Agent", "haveno/" + Version.VERSION);
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getCode();
                HttpEntity entity = response.getEntity();
                if (statusCode != 200) {
                    throw new IOException("Download failed with HTTP status " + statusCode + " for " + url);
                }
                if (entity == null) {
                    throw new IOException("Empty response body for " + url);
                }
                try (InputStream inputStream = entity.getContent()) {
                    streamToFile(inputStream, destination, entity.getContentLength(), progressListener);
                }
            }
        }
    }

    private static void streamToFile(InputStream source,
                                     File destination,
                                     long totalBytes,
                                     @Nullable ProgressListener progressListener) throws IOException {
        try (OutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[4096];
            long count = 0;
            int n;
            while ((n = source.read(buffer)) != -1) {
                output.write(buffer, 0, n);
                count += n;
                if (progressListener != null) {
                    progressListener.onProgress(count, totalBytes);
                }
            }
        }
    }
}
