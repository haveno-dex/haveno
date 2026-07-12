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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import com.google.inject.Inject;
import com.runjva.sourceforge.jsocks.protocol.Socks5Proxy;

import haveno.common.ThreadUtils;
import haveno.common.app.Version;
import haveno.common.util.Utilities;
import haveno.network.Socks5ProxyProvider;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import monero.common.NetworkUtils;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.socket.ConnectionSocketFactory;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.pool.PoolReusePolicy;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

// TODO close connection if failing
@Slf4j
public class HttpClientImpl implements HttpClient {
    @Nullable
    private Socks5ProxyProvider socks5ProxyProvider;
    @Nullable
    private volatile HttpURLConnection connection;
    @Nullable
    private volatile CloseableHttpClient closeableHttpClient;
    private static final long SHUTDOWN_TIMEOUT_MS = 5000l;

    @Getter
    @Setter
    private volatile String baseUrl;
    @Setter
    private boolean ignoreSocks5Proxy;
    @Getter
    private final String uid;
    private final AtomicBoolean hasPendingRequest = new AtomicBoolean();
    private final AtomicLong requestGen = new AtomicLong(); // bumped on cancel so a canceled request cannot clear the next request's state
    private final Object requestLock = new Object(); // guards claiming and canceling the pending request atomically
    protected volatile int connectTimeoutMs = (int) TimeUnit.SECONDS.toMillis(120);
    protected volatile int readTimeoutMs = (int) TimeUnit.SECONDS.toMillis(120);

    @Inject
    public HttpClientImpl(@Nullable Socks5ProxyProvider socks5ProxyProvider) {
        this.socks5ProxyProvider = socks5ProxyProvider;
        uid = UUID.randomUUID().toString();
    }

    public HttpClientImpl(String baseUrl) {
        this.baseUrl = baseUrl;
        uid = UUID.randomUUID().toString();
    }

    @Override
    public void shutDown() {
        HttpURLConnection connectionToClose = connection;
        CloseableHttpClient closeableHttpClientToClose = closeableHttpClient;
        try {
            ThreadUtils.awaitTask(() -> {
                doShutDown(connectionToClose, closeableHttpClientToClose);
                // only clear shared state if not superseded by a newer request
                if (connection == connectionToClose) connection = null;
                if (closeableHttpClient == closeableHttpClientToClose) closeableHttpClient = null;
            }, SHUTDOWN_TIMEOUT_MS);
        } catch (Exception e) {
            // ignore
        }
    }

    private void doShutDown(HttpURLConnection connection, CloseableHttpClient closeableHttpClient) {
        try {
            if (connection != null) {
                connection.getInputStream().close();
                connection.disconnect();
            }
            if (closeableHttpClient != null) {
                closeableHttpClient.close();
            }
        } catch (IOException ignore) {
        }
    }

    @Override
    public boolean hasPendingRequest() {
        return hasPendingRequest.get();
    }

    @Override
    public String get(String param,
                      @Nullable String headerKey,
                      @Nullable String headerValue) throws IOException {
        return doRequest(param, HttpMethod.GET, headerKey, headerValue);
    }

    @Override
    public String post(String param,
                       @Nullable String headerKey,
                       @Nullable String headerValue) throws IOException {
        return doRequest(param, HttpMethod.POST, headerKey, headerValue);
    }

    private String doRequest(String param,
                             HttpMethod httpMethod,
                             @Nullable String headerKey,
                             @Nullable String headerValue) throws IOException {
        checkNotNull(baseUrl, "baseUrl must be set before calling doRequest");
        long gen;
        synchronized (requestLock) {
            checkArgument(hasPendingRequest.compareAndSet(false, true),
                    "We got called on the same HttpClient again while a request is still open.");
            gen = requestGen.get();
        }

        Socks5Proxy socks5Proxy = getSocks5Proxy(socks5ProxyProvider);
        if (ignoreSocks5Proxy || socks5Proxy == null || NetworkUtils.isLoopbackUrl(baseUrl)) {
            return requestWithoutProxy(baseUrl, param, httpMethod, headerKey, headerValue, gen);
        } else {
            return doRequestWithProxy(baseUrl, param, httpMethod, socks5Proxy, headerKey, headerValue, gen);
        }
    }

    public void cancelPendingRequest() {
        synchronized (requestLock) {
            if (!hasPendingRequest.get()) return;
            requestGen.incrementAndGet(); // canceled request no longer owns the pending flag
            hasPendingRequest.set(false);
        }
        shutDown();
    }

    private String requestWithoutProxy(String baseUrl,
                                       String param,
                                       HttpMethod httpMethod,
                                       @Nullable String headerKey,
                                       @Nullable String headerValue,
                                       long gen) throws IOException {
        long ts = System.currentTimeMillis();
        log.debug("requestWithoutProxy: URL={}, param={}, httpMethod={}", baseUrl, param, httpMethod);
        HttpURLConnection connection = null;
        try {
            String spec = httpMethod == HttpMethod.GET ? baseUrl + param : baseUrl;
            URL url = new URL(spec);
            connection = (HttpURLConnection) url.openConnection();
            this.connection = connection; // expose for cancellation
            if (requestGen.get() != gen) throw new IOException("Request was canceled");
            connection.setRequestMethod(httpMethod.name());
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setRequestProperty("User-Agent", "haveno/" + Version.VERSION);
            if (headerKey != null && headerValue != null) {
                connection.setRequestProperty(headerKey, headerValue);
            }

            if (httpMethod == HttpMethod.POST) {
                connection.setDoOutput(true);
                connection.getOutputStream().write(param.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                String response = convertInputStreamToString(connection.getInputStream());
                log.debug("Response from {} with param {} took {} ms. Data size:{}, response: {}",
                        baseUrl,
                        param,
                        System.currentTimeMillis() - ts,
                        Utilities.readableFileSize(response.getBytes().length),
                        Utilities.toTruncatedString(response));
                return response;
            } else {
                InputStream errorStream = connection.getErrorStream();
                if (errorStream != null) {
                    String error = convertInputStreamToString(errorStream);
                    errorStream.close();
                    log.info("Received errorMsg '{}' with responseCode {} from {}. Response took: {} ms. param: {}",
                            error,
                            responseCode,
                            baseUrl,
                            System.currentTimeMillis() - ts,
                            param);
                    throw new HttpException(error, responseCode);
                } else {
                    log.info("Response with responseCode {} from {}. Response took: {} ms. param: {}",
                            responseCode,
                            baseUrl,
                            System.currentTimeMillis() - ts,
                            param);
                    throw new HttpException("Request failed", responseCode);
                }
            }
        } catch (Throwable t) {
            String message = "Error at requestWithoutProxy with url " + baseUrl + " and param " + param +
                    ". Throwable=" + t.getMessage();
            throw new IOException(message, t);
        } finally {
            try {
                if (connection != null) {
                    connection.getInputStream().close();
                    connection.disconnect();
                }
            } catch (Throwable ignore) {
            }
            // only clear shared state if not superseded by a newer request or cancel
            if (this.connection == connection) this.connection = null;
            if (requestGen.get() == gen) hasPendingRequest.set(false);
        }
    }

    private String doRequestWithProxy(String baseUrl,
                                      String param,
                                      HttpMethod httpMethod,
                                      Socks5Proxy socks5Proxy,
                                      @Nullable String headerKey,
                                      @Nullable String headerValue,
                                      long gen) throws IOException {
        long ts = System.currentTimeMillis();
        log.debug("doRequestWithProxy: baseUrl={}, param={}, httpMethod={}", baseUrl, param, httpMethod);
        // This code is adapted from:
        //  http://stackoverflow.com/a/25203021/5616248

        // HC 5.6 routes plain HTTP via DefaultHttpClientConnectionOperator + SocketConfig socks
        // proxy (not ConnectionSocketFactory.connectSocket). Keep custom socket factories for
        // HTTPS TLS upgrade; pair with FakeDnsResolver so .onion hostnames stay unresolved.
        var reg = RegistryBuilder.<ConnectionSocketFactory>create()
                .register("http", SocksConnectionSocketFactory.INSTANCE)
                .register("https", new SocksSSLConnectionSocketFactory(SSLContexts.createSystemDefault()))
                .build();

        DnsResolver dnsResolver = socks5Proxy.resolveAddrLocally()
                ? SystemDefaultDnsResolver.INSTANCE
                : new FakeDnsResolver();
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager(
                reg,
                PoolConcurrencyPolicy.STRICT,
                PoolReusePolicy.LIFO,
                TimeValue.ofMinutes(5),
                null,
                dnsResolver,
                null);
        InetSocketAddress socksAddress = new InetSocketAddress(socks5Proxy.getInetAddress(), socks5Proxy.getPort());
        cm.setDefaultSocketConfig(SocketConfig.custom()
                .setSocksProxyAddress(socksAddress)
                .setSoTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                .build());
        cm.setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setSocketTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                .build());
        CloseableHttpClient httpclient = null;
        try {
            httpclient = checkNotNull(HttpClients.custom()
                    .setConnectionManager(cm)
                    .setDefaultRequestConfig(RequestConfig.custom()
                            .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                            .setResponseTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                            .build())
                    .build());
            this.closeableHttpClient = httpclient; // expose for cancellation
            if (requestGen.get() != gen) throw new IOException("Request was canceled");

            HttpClientContext context = HttpClientContext.create();
            context.setAttribute("socks.address", socksAddress);

            HttpUriRequest request = getHttpUriRequest(httpMethod, baseUrl, param);
            request.setHeader("User-Agent", "haveno/" + Version.VERSION);
            if (headerKey != null && headerValue != null) {
                request.setHeader(headerKey, headerValue);
            }

            try (CloseableHttpResponse httpResponse = httpclient.execute(request, context)) {
                int statusCode = httpResponse.getCode();
                String response = "";
                if (httpResponse.getEntity() != null) {
                    response = convertInputStreamToString(httpResponse.getEntity().getContent());
                }
                if (statusCode == 200) {
                    log.debug("Response from {} took {} ms. Data size:{}, response: {}, param: {}",
                            baseUrl,
                            System.currentTimeMillis() - ts,
                            Utilities.readableFileSize(response.getBytes().length),
                            Utilities.toTruncatedString(response),
                            param);
                    return response;
                } else {
                    log.info("Received errorMsg '{}' with statusCode {} from {}. Response took: {} ms. param: {}",
                            response,
                            statusCode,
                            baseUrl,
                            System.currentTimeMillis() - ts,
                            param);
                    throw new HttpException(response, statusCode);
                }
            }
        } catch (Throwable t) {
            String message = "Error at doRequestWithProxy with url " + baseUrl + " and param " + param +
                    ". Throwable=" + t.getMessage();
            throw new IOException(message, t);
        } finally {
            try {
                if (httpclient != null) httpclient.close();
            } catch (Throwable ignore) {
            }
            // only clear shared state if not superseded by a newer request or cancel
            if (this.closeableHttpClient == httpclient) this.closeableHttpClient = null;
            if (requestGen.get() == gen) hasPendingRequest.set(false);
        }
    }

    private HttpUriRequest getHttpUriRequest(HttpMethod httpMethod, String baseUrl, String param)
            throws UnsupportedEncodingException {
        switch (httpMethod) {
            case GET:
                return new HttpGet(baseUrl + param);
            case POST:
                HttpPost httpPost = new HttpPost(baseUrl);
                httpPost.setEntity(new StringEntity(param, ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8)));
                return httpPost;

            default:
                throw new IllegalArgumentException("HttpMethod not supported: " + httpMethod);
        }
    }

    @Nullable
    private Socks5Proxy getSocks5Proxy(Socks5ProxyProvider socks5ProxyProvider) {
        if (socks5ProxyProvider == null) {
            return null;
        }

        // We use the custom socks5ProxyHttp.
        Socks5Proxy socks5Proxy = socks5ProxyProvider.getSocks5ProxyHttp();
        if (socks5Proxy != null) {
            return socks5Proxy;
        }

        // If not set we request socks5ProxyProvider.getSocks5Proxy()
        // which delivers the btc proxy if set, otherwise the internal proxy.
        return socks5ProxyProvider.getSocks5Proxy();
    }

    private String convertInputStreamToString(InputStream inputStream) throws IOException {
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
        StringBuilder stringBuilder = new StringBuilder();
        String line;
        while ((line = bufferedReader.readLine()) != null) {
            stringBuilder.append(line);
        }
        return stringBuilder.toString();
    }

    @Override
    public String toString() {
        return "HttpClientImpl{" +
                "\n     socks5ProxyProvider=" + socks5ProxyProvider +
                ",\n     baseUrl='" + baseUrl + '\'' +
                ",\n     ignoreSocks5Proxy=" + ignoreSocks5Proxy +
                ",\n     uid='" + uid + '\'' +
                ",\n     connection=" + connection +
                ",\n     httpclient=" + closeableHttpClient +
                "\n}";
    }
}
