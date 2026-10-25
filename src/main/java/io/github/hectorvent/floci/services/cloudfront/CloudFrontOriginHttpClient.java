package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.SsrfProtection;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.ConnectionKeepAliveStrategy;
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
import org.apache.hc.core5.http.HeaderElement;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.message.MessageSupport;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLContext;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * HTTP/1.1 transport for CloudFront custom origins that validates and pins each DNS resolution to
 * the addresses used by the connection. Keeping the logical hostname in the request URI preserves
 * the origin Host header, TLS SNI, and certificate hostname verification.
 */
final class CloudFrontOriginHttpClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(CloudFrontOriginHttpClient.class);

    private static final String ORIGIN_TIMEOUT_MESSAGE =
            "The origin didn't respond before the request expired.";
    /** The context attribute the origin's keep-alive timeout travels in, to the pool's strategy. */
    static final String KEEP_ALIVE_TIMEOUT_ATTRIBUTE = "floci.cloudfront.originKeepAliveTimeout";
    private static final Duration CONNECT_TIMEOUT = CloudFrontOriginTimeouts.DEFAULT_CONNECTION_TIMEOUT;
    private static final Duration RESPONSE_TIMEOUT = CloudFrontOriginTimeouts.DEFAULT_ORIGIN_READ_TIMEOUT;
    /** CloudFront retries a response timeout only for the methods it treats as idempotent. */
    private static final Set<String> IDEMPOTENT_METHODS = Set.of("GET", "HEAD");
    static final int MAX_CONNECTIONS = 100;

    /**
     * The connect timeout of the request being sent, for the TLS handshake its connection makes.
     * The pool resolves the TLS configuration of a connection while the request that opens it runs,
     * on the same thread, so the handshake answers within the connect timeout of that request.
     */
    private static final ThreadLocal<Duration> HANDSHAKE_TIMEOUT = new ThreadLocal<>();

    private final CloseableHttpClient client;
    /** The clock the completion budget reads, {@link System#nanoTime} unless a test supplies one. */
    private final LongSupplier nanoClock;

    CloudFrontOriginHttpClient(Collection<String> allowedPrivateOriginHosts) {
        this(SystemDefaultDnsResolver.INSTANCE, allowedPrivateOriginHosts, (SSLContext) null);
    }

    CloudFrontOriginHttpClient(DnsResolver delegate, Collection<String> allowedPrivateOriginHosts) {
        this(delegate, allowedPrivateOriginHosts, (SSLContext) null);
    }

    /** Reaches Floci's own listener, whose address the private address check refuses. */
    static CloudFrontOriginHttpClient toFloci() {
        return new CloudFrontOriginHttpClient(SystemDefaultDnsResolver.INSTANCE, (SSLContext) null,
                System::nanoTime);
    }

    CloudFrontOriginHttpClient(
            DnsResolver delegate,
            Collection<String> allowedPrivateOriginHosts,
            SSLContext sslContext) {
        this(delegate, allowedPrivateOriginHosts, sslContext, System::nanoTime);
    }

    /** The same client, reading the clock from {@code nanoClock} rather than {@link System#nanoTime}. */
    CloudFrontOriginHttpClient(
            DnsResolver delegate,
            Collection<String> allowedPrivateOriginHosts,
            LongSupplier nanoClock) {
        this(delegate, allowedPrivateOriginHosts, null, nanoClock);
    }

    private CloudFrontOriginHttpClient(
            DnsResolver delegate,
            Collection<String> allowedPrivateOriginHosts,
            SSLContext sslContext,
            LongSupplier nanoClock) {
        this(new ValidatingDnsResolver(delegate, allowedPrivateOriginHosts.stream()
                .map(CloudFrontServingController::normalizeHost)
                .collect(Collectors.toUnmodifiableSet())), sslContext, nanoClock);
    }

    private CloudFrontOriginHttpClient(
            DnsResolver resolver, SSLContext sslContext, LongSupplier nanoClock) {
        this.nanoClock = nanoClock;

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
                        .setDnsResolver(resolver)
                        .setDefaultConnectionConfig(connectionConfig)
                        .setDefaultTlsConfig(tlsConfig)
                        // The handshake of a connection answers within the connect timeout of the
                        // request that opens it, so it cannot outlive the completion timeout.
                        .setTlsConfigResolver(host -> tlsConfig(handshakeTimeout()))
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
                .setKeepAliveStrategy(new KeepAliveStrategy())
                // A connection leaves the pool when its own keep-alive expires, so the evictor only
                // has to clear the connections no origin will ever reuse again: the longest
                // keep-alive an origin can ask for.
                .evictIdleConnections(TimeValue.ofSeconds(
                        CloudFrontOriginTimeouts.MAX_ORIGIN_KEEPALIVE_TIMEOUT_SECONDS))
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableContentCompression()
                .disableCookieManagement()
                .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
                .build();
    }

    /**
     * The connection settings of a distribution's origin that one request must honor:
     * {@code Origin.ConnectionAttempts}, {@code Origin.ConnectionTimeout}, the custom origin's
     * {@code OriginReadTimeout}, {@code OriginKeepaliveTimeout} and {@code ResponseCompletionTimeout}.
     * Read per request, since the client and its pool are shared.
     */
    record OriginTimeouts(Duration connectTimeout, Duration responseTimeout, int connectionAttempts,
                          Duration responseCompletionTimeout, Duration keepAliveTimeout) {
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
        return open(request, forwardedHeaders, originHeaders, requestBody, requestBodyLength, null);
    }

    /**
     * Sends {@code request}, honoring {@code timeouts}. A connection attempt counts for every
     * method, so a connect timeout or a refused connection is retried up to the configured
     * connection attempts whatever the method is. A response timeout is retried only for an
     * idempotent method (GET, HEAD), whose request has no body to replay; every other method is
     * attempted once for it, as CloudFront does not retry it. When the attempts run out, a timeout
     * raises {@link CloudFrontOriginTimeoutException} so the viewer is answered 504 rather than
     * 502, while a refused connection propagates unchanged, as a bad gateway. The completion
     * timeout bounds the time the origin takes, connecting, answering and streaming the body
     * included, over every attempt, and is never retried. The time a slow viewer takes to receive
     * the response is not part of it: the origin answered in time, so the request did not expire.
     */
    OpenResponse open(HttpRequest request,
                      List<CloudFrontOriginRequestBuilder.Header> forwardedHeaders,
                      Map<String, String> originHeaders, InputStream requestBody, long requestBodyLength,
                      OriginTimeouts timeouts)
            throws IOException, InterruptedException {
        OriginTimeouts effective = timeouts != null
                ? timeouts
                : new OriginTimeouts(CONNECT_TIMEOUT, request.timeout().orElse(RESPONSE_TIMEOUT), 1,
                CloudFrontOriginTimeouts.DEFAULT_RESPONSE_COMPLETION_TIMEOUT,
                CloudFrontOriginTimeouts.DEFAULT_ORIGIN_KEEPALIVE_TIMEOUT);
        int attempts = Math.max(1, effective.connectionAttempts());
        CompletionBudget budget = new CompletionBudget(effective.responseCompletionTimeout(), nanoClock);
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (budget.expired()) {
                // The origin already took the time the completion timeout allows: another attempt
                // would only push the viewer further past it.
                throw new CloudFrontOriginTimeoutException(ORIGIN_TIMEOUT_MESSAGE);
            }
            boolean responseTimeout = false;
            try {
                return send(request, forwardedHeaders, originHeaders, requestBody, requestBodyLength,
                        effective, budget);
            } catch (ConnectTimeoutException e) {
                // A connection attempt counts for every method, so a connect timeout is retried.
                lastFailure = e;
            } catch (SocketTimeoutException e) {
                lastFailure = e;
                responseTimeout = true;
            } catch (ConnectException e) {
                lastFailure = e;
            }
            if (budget.expired()) {
                // The origin took the time the completion timeout allows, so the request is
                // expired rather than merely slow.
                throw new CloudFrontOriginTimeoutException(ORIGIN_TIMEOUT_MESSAGE, lastFailure);
            }
            if (responseTimeout && !idempotent(request.method())) {
                // A response timeout on a method CloudFront does not retry ends the request.
                throw new CloudFrontOriginTimeoutException(ORIGIN_TIMEOUT_MESSAGE, lastFailure);
            }
            if (attempt < attempts) {
                LOG.debugv("CloudFront origin connection attempt {0} of {1} failed; retrying",
                        attempt, attempts);
            }
        }
        if (lastFailure instanceof SocketTimeoutException timeout) {
            throw new CloudFrontOriginTimeoutException(ORIGIN_TIMEOUT_MESSAGE, timeout);
        }
        throw lastFailure;
    }

    /**
     * The time CloudFront waits for the origin, bounded by the completion timeout. The budget
     * counts the time the origin itself takes: connecting, sending the response, and streaming the
     * body. The time a slow viewer takes to receive what the origin sent is not part of it, so a
     * viewer that reads slowly never expires a response the origin finished on time. A budget with
     * no limit is the origin's own pace.
     */
    private static final class CompletionBudget {

        /** The time the origin may take, in nanoseconds, or 0 when it may take as long as it wants. */
        private final long limitNanos;
        /** The clock the budget reads, so a test can move time without waiting for it. */
        private final LongSupplier nanoClock;
        /** The time the origin has taken so far, in nanoseconds. */
        private long spentNanos;

        CompletionBudget(Duration responseCompletionTimeout, LongSupplier nanoClock) {
            this.nanoClock = nanoClock;
            this.limitNanos = responseCompletionTimeout == null || responseCompletionTimeout.isZero()
                    || responseCompletionTimeout.isNegative() ? 0 : responseCompletionTimeout.toNanos();
        }

        /** The clock's reading, in nanoseconds. */
        long now() {
            return nanoClock.getAsLong();
        }

        /** Whether the origin may take as long as it wants. */
        boolean unlimited() {
            return limitNanos == 0;
        }

        /** Adds the time the origin just took. */
        void spend(long nanos) {
            spentNanos += nanos;
        }

        /** Whether the origin has taken the whole time the completion timeout allows. */
        boolean expired() {
            return limitNanos > 0 && spentNanos >= limitNanos;
        }

        /** How much of the budget is left, in nanoseconds. */
        long remainingNanos() {
            return limitNanos > 0 ? Math.max(0L, limitNanos - spentNanos) : 0L;
        }
    }

    private OpenResponse send(HttpRequest request,
                              List<CloudFrontOriginRequestBuilder.Header> forwardedHeaders,
                              Map<String, String> originHeaders, InputStream requestBody, long requestBodyLength,
                              OriginTimeouts timeouts, CompletionBudget budget)
            throws IOException, InterruptedException {
        // No entity content type: the caller's Content-Type request header is sent as is.
        HttpUriRequestBase originRequest = originRequest(request, forwardedHeaders, originHeaders,
                requestBody != null ? new InputStreamEntity(requestBody, requestBodyLength, null) : null);
        ClassicHttpResponse response;
        OriginTimeouts effective = effectiveTimeouts(timeouts, budget);
        long attemptStart = budget.now();
        HANDSHAKE_TIMEOUT.set(effective.connectTimeout());
        try {
            response = client.executeOpen(RoutingSupport.determineHost(originRequest), originRequest,
                    context(effective));
        } catch (HttpException e) {
            throw new IOException("CloudFront origin request has no target host", e);
        } catch (InterruptedIOException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("CloudFront origin request interrupted");
            }
            throw e;
        } finally {
            HANDSHAKE_TIMEOUT.remove();
            // The time the origin took to connect and to answer is part of the time CloudFront
            // waits for it.
            budget.spend(budget.now() - attemptStart);
        }
        try {
            HttpEntity entity = response.getEntity();
            InputStream body = entity != null
                    ? new ResponseBody(entity.getContent(), originRequest, response)
                    : new ResponseBody(InputStream.nullInputStream(), null, response);
            // The entity's length follows the framing the body arrives with; with no entity, as for a
            // HEAD, the header describes the body a GET would get.
            long contentLength = entity != null ? entity.getContentLength() : headerContentLength(response);
            if (budget.expired()) {
                // The origin answered, but too late: the request has already expired.
                body.close();
                throw new CloudFrontOriginTimeoutException(ORIGIN_TIMEOUT_MESSAGE);
            }
            if (!budget.unlimited()) {
                body = new CompletionTimeoutInputStream(body, budget);
            }
            return new OpenResponse(response.getCode(), responseHeaders(response), contentLength, body);
        } catch (IOException | RuntimeException e) {
            originRequest.cancel();
            closeAborted(response);
            throw e;
        }
    }

    private static boolean idempotent(String method) {
        return method != null && IDEMPOTENT_METHODS.contains(method.toUpperCase(Locale.ROOT));
    }

    /**
     * How long CloudFront keeps a pooled origin connection, from the origin's
     * {@code OriginKeepaliveTimeout}. The duration the origin asks for in its own {@code Keep-Alive}
     * header only shortens it: the setting is the longest CloudFront holds a connection to that
     * origin, and a connection the origin is about to close fails the next request.
     */
    static final class KeepAliveStrategy implements ConnectionKeepAliveStrategy {
        @Override
        public TimeValue getKeepAliveDuration(HttpResponse response, HttpContext context) {
            TimeValue keepAlive = TimeValue.ofSeconds(keepAliveSeconds(context));
            TimeValue fromOrigin = originKeepAliveHeader(response);
            if (fromOrigin != null && fromOrigin.getDuration() > 0 && fromOrigin.compareTo(keepAlive) < 0) {
                return fromOrigin;
            }
            return keepAlive;
        }
    }

    /** The keep-alive the request to the origin asked for, in seconds. */
    private static int keepAliveSeconds(HttpContext context) {
        Object attribute = context != null ? context.getAttribute(KEEP_ALIVE_TIMEOUT_ATTRIBUTE) : null;
        if (attribute instanceof Duration duration && !duration.isNegative()) {
            return (int) Math.min(duration.toSeconds(), Integer.MAX_VALUE);
        }
        return CloudFrontOriginTimeouts.DEFAULT_ORIGIN_KEEPALIVE_TIMEOUT_SECONDS;
    }

    /** The keep-alive the origin asks for in its {@code Keep-Alive} header, or null when it asks for none. */
    private static TimeValue originKeepAliveHeader(HttpResponse response) {
        Iterator<HeaderElement> elements = MessageSupport.iterate(response, "keep-alive");
        while (elements.hasNext()) {
            HeaderElement element = elements.next();
            String name = element.getName();
            String value = element.getValue();
            if (value != null && name != null && name.equalsIgnoreCase("timeout")) {
                try {
                    return TimeValue.ofSeconds(Long.parseLong(value.trim()));
                } catch (NumberFormatException e) {
                    LOG.debugv("Ignoring an invalid CloudFront origin Keep-Alive timeout: {0}", value);
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * An origin response body that gives up when the origin's completion timeout expires, so a
     * response that trickles in forever does not hold the viewer request open. The origin
     * connection goes with it: CloudFront ends the connection when the origin does not complete
     * the response in time. Only the time the origin takes counts against the timeout, so a viewer
     * that reads slowly never loses a response the origin finished on time.
     */
    private static final class CompletionTimeoutInputStream extends FilterInputStream {

        private final CompletionBudget budget;
        private boolean closed;

        CompletionTimeoutInputStream(InputStream delegate, CompletionBudget budget) {
            super(delegate);
            this.budget = budget;
        }

        private void checkExpired() throws IOException {
            if (budget.expired()) {
                close();
                throw new CloudFrontOriginTimeoutException(ORIGIN_TIMEOUT_MESSAGE);
            }
        }

        @Override
        public int read() throws IOException {
            checkExpired();
            long start = budget.now();
            try {
                int value = super.read();
                budget.spend(budget.now() - start);
                if (value >= 0) {
                    checkExpired();
                }
                return value;
            } catch (SocketTimeoutException e) {
                budget.spend(budget.now() - start);
                if (budget.expired()) {
                    close();
                    throw new CloudFrontOriginTimeoutException(ORIGIN_TIMEOUT_MESSAGE, e);
                }
                throw e;
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            checkExpired();
            long start = budget.now();
            try {
                int read = super.read(buffer, offset, length);
                budget.spend(budget.now() - start);
                if (read >= 0) {
                    checkExpired();
                }
                return read;
            } catch (SocketTimeoutException e) {
                budget.spend(budget.now() - start);
                if (budget.expired()) {
                    close();
                    throw new CloudFrontOriginTimeoutException(ORIGIN_TIMEOUT_MESSAGE, e);
                }
                throw e;
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            super.close();
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

    /**
     * The TLS configuration of a connection: the handshake answers within {@code handshakeTimeout},
     * so it cannot outlive the request that opens the connection.
     */
    private static TlsConfig tlsConfig(Duration handshakeTimeout) {
        return TlsConfig.custom()
                .setHandshakeTimeout(Timeout.of(handshakeTimeout))
                .setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_1)
                .build();
    }

    /** The handshake timeout of the request being sent, or the default connect timeout. */
    private static Duration handshakeTimeout() {
        Duration timeout = HANDSHAKE_TIMEOUT.get();
        return timeout != null ? timeout : CONNECT_TIMEOUT;
    }

    /**
     * The timeouts of an attempt: neither the connection nor the response outlives the completion
     * timeout, so the origin runs out of time before the request does.
     */
    private static OriginTimeouts effectiveTimeouts(OriginTimeouts timeouts, CompletionBudget budget)
            throws CloudFrontOriginTimeoutException {
        if (budget.unlimited()) {
            return timeouts;
        }
        if (budget.expired()) {
            throw new CloudFrontOriginTimeoutException(ORIGIN_TIMEOUT_MESSAGE);
        }
        long remainingMillis = Math.max(1L, budget.remainingNanos() / 1_000_000L);
        return new OriginTimeouts(
                Duration.ofMillis(Math.min(timeouts.connectTimeout().toMillis(), remainingMillis)),
                Duration.ofMillis(Math.min(timeouts.responseTimeout().toMillis(), remainingMillis)),
                timeouts.connectionAttempts(),
                timeouts.responseCompletionTimeout(),
                timeouts.keepAliveTimeout());
    }

    static HttpClientContext context(OriginTimeouts timeouts) {
        HttpClientContext context = HttpClientContext.create();
        context.setRequestConfig(RequestConfig.custom()
                .setConnectTimeout(Timeout.of(timeouts.connectTimeout()))
                // Waiting for a free pooled connection is emulator-side backpressure, not an origin
                // setting, so it keeps the fixed default rather than the origin's connect timeout.
                .setConnectionRequestTimeout(Timeout.of(CONNECT_TIMEOUT))
                .setResponseTimeout(Timeout.of(timeouts.responseTimeout()))
                .setRedirectsEnabled(false)
                .setContentCompressionEnabled(false)
                .setProtocolUpgradeEnabled(false)
                .build());
        context.setAttribute(KEEP_ALIVE_TIMEOUT_ATTRIBUTE, timeouts.keepAliveTimeout());
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
