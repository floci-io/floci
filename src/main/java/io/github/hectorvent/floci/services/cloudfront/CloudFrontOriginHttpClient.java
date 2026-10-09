package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.SsrfProtection;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.routing.RoutingSupport;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.util.Timeout;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLContext;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * HTTP/1.1 transport for CloudFront custom origins that validates and pins each DNS resolution to
 * the addresses used by the connection. Keeping the logical hostname in the request URI preserves
 * the origin Host header, TLS SNI, and certificate hostname verification.
 */
final class CloudFrontOriginHttpClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(CloudFrontOriginHttpClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);
    static final int MAX_CONNECTIONS = 100;

    private final CloseableHttpClient client;

    CloudFrontOriginHttpClient(Collection<String> allowedPrivateOriginHosts) {
        this(SystemDefaultDnsResolver.INSTANCE, allowedPrivateOriginHosts, null);
    }

    CloudFrontOriginHttpClient(DnsResolver delegate, Collection<String> allowedPrivateOriginHosts) {
        this(delegate, allowedPrivateOriginHosts, null);
    }

    CloudFrontOriginHttpClient(
            DnsResolver delegate,
            Collection<String> allowedPrivateOriginHosts,
            SSLContext sslContext) {
        Set<String> allowedHosts = allowedPrivateOriginHosts.stream()
                .map(CloudFrontServingController::normalizeHost)
                .collect(Collectors.toUnmodifiableSet());
        DnsResolver validatingResolver = new ValidatingDnsResolver(delegate, allowedHosts);

        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(CONNECT_TIMEOUT))
                .setSocketTimeout(Timeout.of(RESPONSE_TIMEOUT))
                .build();
        TlsConfig tlsConfig = TlsConfig.custom()
                .setHandshakeTimeout(Timeout.of(CONNECT_TIMEOUT))
                .setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_1)
                .build();
        PoolingHttpClientConnectionManagerBuilder connectionManagerBuilder =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(validatingResolver)
                        .setDefaultConnectionConfig(connectionConfig)
                        .setDefaultTlsConfig(tlsConfig)
                        // CloudFront has no per-origin connection limit, and a streamed response holds its
                        // connection for the whole transfer, so one origin may use the whole pool.
                        .setMaxConnTotal(MAX_CONNECTIONS)
                        .setMaxConnPerRoute(MAX_CONNECTIONS);
        if (sslContext != null) {
            connectionManagerBuilder.setTlsSocketStrategy(ClientTlsStrategyBuilder.create()
                    .setSslContext(sslContext)
                    .buildClassic());
        }
        PoolingHttpClientConnectionManager connectionManager = connectionManagerBuilder.build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.of(CONNECT_TIMEOUT))
                .setResponseTimeout(Timeout.of(RESPONSE_TIMEOUT))
                .setRedirectsEnabled(false)
                .setContentCompressionEnabled(false)
                .setProtocolUpgradeEnabled(false)
                .build();

        this.client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableContentCompression()
                .disableCookieManagement()
                .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
                .build();
    }

    /**
     * An origin response whose body has not been read. The caller reads {@code body} as it needs it
     * and closes it, which releases the connection. {@code contentLength} is the length the body
     * arrives with, {@code -1} when it comes chunked or with none.
     */
    record OpenResponse(int statusCode, HttpHeaders headers, long contentLength, InputStream body) {
    }

    /**
     * Sends {@code request} with {@code forwardedHeaders} added and {@code requestBody}, when it is
     * not {@code null}, streamed as the entity, with {@code requestBodyLength} as its
     * {@code Content-Length}, or chunked when the length is negative. Each origin header replaces
     * every same-named request or forwarded header, so a viewer cannot repeat a header to smuggle its
     * own value past an origin custom header. The response comes back before its body is read, so
     * neither body has to fit in memory.
     */
    OpenResponse open(HttpRequest request,
                      List<CloudFrontOriginRequestBuilder.Header> forwardedHeaders,
                      Map<String, String> originHeaders, InputStream requestBody, long requestBodyLength)
            throws IOException, InterruptedException {
        // No entity content type: the caller's Content-Type request header is sent as is.
        HttpUriRequestBase originRequest = originRequest(request, forwardedHeaders, originHeaders,
                requestBody != null ? new InputStreamEntity(requestBody, requestBodyLength, null) : null);
        ClassicHttpResponse response;
        try {
            response = client.executeOpen(RoutingSupport.determineHost(originRequest), originRequest, context(request));
        } catch (HttpException e) {
            throw new IOException("CloudFront origin request has no target host", e);
        } catch (InterruptedIOException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("CloudFront origin request interrupted");
            }
            throw e;
        }
        try {
            HttpEntity entity = response.getEntity();
            InputStream body = entity != null
                    ? new ResponseBody(entity.getContent(), originRequest, response)
                    : new ResponseBody(InputStream.nullInputStream(), null, response);
            // The entity's length follows the framing the body arrives with; with no entity, as for a
            // HEAD, the header describes the body a GET would get.
            long contentLength = entity != null ? entity.getContentLength() : headerContentLength(response);
            return new OpenResponse(response.getCode(), responseHeaders(response), contentLength, body);
        } catch (IOException | RuntimeException e) {
            originRequest.cancel();
            closeAborted(response);
            throw e;
        }
    }

    /**
     * An origin response body that releases its connection when it is closed. Closed at its end, the
     * connection goes back to the pool. Closed before its end, as when a viewer goes away or a custom
     * error page replaces it, the request is cancelled and the connection closed, since closing the
     * response normally reads the rest of the body to reuse the connection, however long it is.
     */
    private static final class ResponseBody extends FilterInputStream {

        private final HttpUriRequestBase request;
        private final ClassicHttpResponse response;
        private boolean finished;

        ResponseBody(InputStream content, HttpUriRequestBase request, ClassicHttpResponse response) {
            super(content);
            this.request = request;
            this.response = response;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            finished |= b < 0;
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            finished |= read < 0;
            return read;
        }

        @Override
        public void close() throws IOException {
            if (finished || request == null) {
                try {
                    super.close();
                } finally {
                    response.close();
                }
                return;
            }
            request.cancel();
            closeAborted(response);
        }
    }

    /** Closes a response whose request was cancelled; reading the rest of it fails, as it should. */
    private static void closeAborted(ClassicHttpResponse response) {
        try {
            response.close();
        } catch (IOException e) {
            LOG.debugv("Closed an unfinished CloudFront origin response: {0}", e.getMessage());
        }
    }

    private static long headerContentLength(ClassicHttpResponse response) {
        Header header = response.getFirstHeader("Content-Length");
        if (header == null) {
            return -1;
        }
        try {
            return Long.parseLong(header.getValue().trim());
        } catch (NumberFormatException e) {
            LOG.debugv("Ignoring an invalid custom-origin Content-Length: {0}", e.getMessage());
            return -1;
        }
    }

    private HttpUriRequestBase originRequest(HttpRequest request,
                                             List<CloudFrontOriginRequestBuilder.Header> forwardedHeaders,
                                             Map<String, String> originHeaders, HttpEntity entity)
            throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("CloudFront origin request interrupted");
        }
        if (request.bodyPublisher().filter(publisher -> publisher.contentLength() != 0).isPresent()) {
            throw new IOException("CloudFront origin request bodies must be passed separately");
        }

        // A request that can be cancelled, so a response closed before its end is not read to the end.
        HttpUriRequestBase builder = new HttpUriRequestBase(request.method(), request.uri());
        builder.setVersion(HttpVersion.HTTP_1_1);
        Set<String> replaced = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        replaced.addAll(originHeaders.keySet());
        request.headers().map().forEach((name, values) -> {
            if (!replaced.contains(name)) {
                values.forEach(value -> builder.addHeader(name, value));
            }
        });
        for (CloudFrontOriginRequestBuilder.Header header : forwardedHeaders) {
            if (!replaced.contains(header.name())) {
                builder.addHeader(header.name(), header.value());
            }
        }
        originHeaders.forEach(builder::addHeader);
        if (entity != null) {
            builder.setEntity(entity);
        }
        return builder;
    }

    private static HttpClientContext context(HttpRequest request) {
        HttpClientContext context = HttpClientContext.create();
        Duration responseTimeout = request.timeout().orElse(RESPONSE_TIMEOUT);
        context.setRequestConfig(RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.of(CONNECT_TIMEOUT))
                .setResponseTimeout(Timeout.of(responseTimeout))
                .setRedirectsEnabled(false)
                .setContentCompressionEnabled(false)
                .setProtocolUpgradeEnabled(false)
                .build());
        return context;
    }

    private static HttpHeaders responseHeaders(ClassicHttpResponse response) {
        Map<String, List<String>> responseHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Header header : response.getHeaders()) {
            responseHeaders.computeIfAbsent(header.getName(), ignored -> new ArrayList<>())
                    .add(header.getValue());
        }
        return HttpHeaders.of(responseHeaders, (name, value) -> true);
    }

    @Override
    public void close() throws IOException {
        client.close();
    }

    private record ValidatingDnsResolver(DnsResolver delegate, Set<String> allowedHosts)
            implements DnsResolver {

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            InetAddress[] addresses = delegate.resolve(host);
            String normalizedHost = CloudFrontServingController.normalizeHost(host);
            if (addresses == null || addresses.length == 0) {
                throw new UnknownHostException("CloudFront origin host has no addresses: " + normalizedHost);
            }
            if (!allowedHosts.contains(normalizedHost)) {
                for (InetAddress address : addresses) {
                    if (SsrfProtection.isBlockedAddress(address)) {
                        throw new UnknownHostException(
                                "CloudFront origin host resolves to a blocked address: " + normalizedHost);
                    }
                }
            }
            return addresses.clone();
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return CloudFrontServingController.normalizeHost(host);
        }
    }
}
