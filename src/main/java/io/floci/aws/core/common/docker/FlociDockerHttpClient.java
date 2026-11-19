/*
 * Adapted from docker-java 3.7.1, docker-java-transport-httpclient5,
 * com.github.dockerjava.httpclient5.ApacheDockerHttpClient and ApacheDockerHttpClientImpl.
 * Copyright docker-java contributors, licensed under the Apache License, Version 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0). Changes: one Floci-owned class with a builder,
 * JBoss Logging, switch expressions; the pool, socket and request settings are unchanged.
 */
package io.floci.aws.core.common.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.transport.NamedPipeSocket;
import com.github.dockerjava.transport.SSLConfig;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.io.HttpClientConnectionOperator;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ConnectionClosedException;
import org.apache.hc.core5.http.ContentLengthStrategy;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpMessage;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.impl.DefaultContentLengthStrategy;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EmptyInputStream;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.net.URIAuthority;
import org.apache.hc.core5.pool.PoolStats;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Floci's own Docker HTTP transport over httpclient5: the transport docker-java's
 * {@code ApacheDockerHttpClient} builds, owned here so Floci can set what that builder does not
 * expose (idle-connection validation and eviction, the connection-lease timeout). It applies
 * docker-java's settings (npipe and tcp hosts, TLS from the SSL config, a pool of
 * {@code maxConnections} for one route, no socket read timeout on the pool, hijacked exec/attach
 * upgrades through {@link HijackingHttpRequestExecutor}), validates a pooled connection idle over a
 * second before reuse on transports that honour socket read timeouts, and evicts idle ones, where
 * docker-java disabled both. A {@code unix://} host connects through {@link UnixDomainSocket},
 * which honours read timeouts where docker-java's socket ignored them. Named pipes skip stale
 * validation because docker-java's {@link NamedPipeSocket} read can block despite the 1 ms timeout
 * used by httpclient5's validation check. Requests that hold a stream open get no response timeout
 * ({@link #isLongLivedStream}), while every other call keeps it.
 */
public final class FlociDockerHttpClient implements DockerHttpClient {

    private static final ObjectMapper EXEC_CONFIG_MAPPER = new ObjectMapper();

    /**
     * A pooled connection idle this long is checked before it is leased again, so a socket the daemon
     * (or Podman) already closed is replaced instead of failing on its next write. The check reads with
     * a 1 ms timeout, which {@link UnixDomainSocket} honours.
     */
    static final TimeValue VALIDATE_AFTER_INACTIVITY = TimeValue.ofSeconds(1);
    /** Idle pooled connections are closed in the background after this long. */
    static final TimeValue EVICT_IDLE_AFTER = TimeValue.ofSeconds(30);

    private final CloseableHttpClient httpClient;
    private final PoolingHttpClientConnectionManager connectionManager;
    /** Responses still open per {@link #streamKind} of the request that opened them. */
    private final Map<String, AtomicInteger> openStreams = new ConcurrentHashMap<>();
    /** The same responses per service of the container they follow, see {@link DockerContainerServices}. */
    private final Map<String, AtomicInteger> openStreamsByService = new ConcurrentHashMap<>();
    private final HttpHost host;
    private final String pathPrefix;
    /** The same settings with no response timeout, for requests that hold a stream open (see isLongLivedStream). */
    private final RequestConfig streamRequestConfig;

    private FlociDockerHttpClient(URI dockerHost, SSLConfig sslConfig, int maxConnections,
                                  Duration connectionTimeout, Duration responseTimeout,
                                  Duration connectionRequestTimeout) {
        SSLContext sslContext;
        try {
            sslContext = sslConfig != null ? sslConfig.getSSLContext() : null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        HttpClientConnectionOperator connectionOperator = createConnectionOperator(dockerHost, sslContext);

        host = switch (dockerHost.getScheme()) {
            case "unix", "npipe" -> new HttpHost(dockerHost.getScheme(), "localhost", 2375);
            case "tcp" -> new HttpHost(sslContext != null ? "https" : "http", dockerHost.getHost(), dockerHost.getPort());
            default -> throw new IllegalArgumentException("Unsupported protocol scheme: " + dockerHost);
        };
        String rawPath = "tcp".equals(dockerHost.getScheme()) ? dockerHost.getRawPath() : "";
        pathPrefix = rawPath.endsWith("/") ? rawPath.substring(0, rawPath.length() - 1) : rawPath;

        connectionManager = new PoolingHttpClientConnectionManager(
                connectionOperator, null, null, null,
                new ManagedHttpClientConnectionFactory(null, null, null, null,
                        FlociDockerHttpClient::determineLength, null));
        // A pooled socket must never time out by itself: follow streams stay silent for as long as
        // the container is quiet. Per-request timeouts come from the RequestConfig below.
        // See https://github.com/docker-java/docker-java/pull/1590#issuecomment-870581289
        connectionManager.setDefaultSocketConfig(SocketConfig.copy(SocketConfig.DEFAULT)
                .setSoTimeout(Timeout.ZERO_MILLISECONDS)
                .build());
        connectionManager.setMaxTotal(maxConnections);
        connectionManager.setDefaultMaxPerRoute(maxConnections);
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setValidateAfterInactivity(staleConnectionValidationFor(dockerHost))
                .setConnectTimeout(connectionTimeout != null
                        ? Timeout.of(connectionTimeout.toNanos(), TimeUnit.NANOSECONDS) : null)
                .build();
        connectionManager.setDefaultConnectionConfig(connectionConfig);

        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(responseTimeout != null
                        ? Timeout.of(responseTimeout.toNanos(), TimeUnit.NANOSECONDS) : null)
                .setConnectionRequestTimeout(connectionRequestTimeout != null
                        ? Timeout.of(connectionRequestTimeout.toNanos(), TimeUnit.NANOSECONDS) : null)
                .build();
        streamRequestConfig = RequestConfig.copy(requestConfig).setResponseTimeout(Timeout.DISABLED).build();
        httpClient = HttpClients.custom()
                .setRequestExecutor(new HijackingHttpRequestExecutor(null))
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .evictIdleConnections(EVICT_IDLE_AFTER)
                .disableConnectionState()
                .build();
    }

    /** A {@code Transfer-Encoding: identity} body is read to the end of the stream, not by length. */
    private static long determineLength(HttpMessage message) throws HttpException {
        Header transferEncoding = message.getFirstHeader(HttpHeaders.TRANSFER_ENCODING);
        if (transferEncoding != null && "identity".equalsIgnoreCase(transferEncoding.getValue())) {
            return ContentLengthStrategy.UNDEFINED;
        }
        return DefaultContentLengthStrategy.INSTANCE.determineLength(message);
    }

    private static HttpClientConnectionOperator createConnectionOperator(URI dockerHost, SSLContext sslContext) {
        String scheme = dockerHost.getScheme();
        String path = dockerHost.getPath();
        TlsSocketStrategy tlsSocketStrategy = sslContext != null
                ? new DefaultClientTlsStrategy(sslContext) : DefaultClientTlsStrategy.createSystemDefault();
        return new DefaultHttpClientConnectionOperator(
                socksProxy -> {
                    if ("unix".equalsIgnoreCase(scheme)) {
                        return UnixDomainSocket.connect(path);
                    }
                    if ("npipe".equalsIgnoreCase(scheme)) {
                        return new FailFastNamedPipeSocket(path);
                    }
                    return socksProxy == null ? new Socket() : new Socket(socksProxy);
                },
                DefaultSchemePortResolver.INSTANCE,
                SystemDefaultDnsResolver.INSTANCE,
                name -> "https".equalsIgnoreCase(name) ? tlsSocketStrategy : null);
    }

    /** Selects stale validation only when the socket honours its short read timeout. */
    static TimeValue staleConnectionValidationFor(URI dockerHost) {
        return "npipe".equalsIgnoreCase(dockerHost.getScheme())
                ? TimeValue.NEG_ONE_MILLISECOND : VALIDATE_AFTER_INACTIVITY;
    }

    @Override
    public Response execute(Request request) {
        HttpContext context = new HttpCoreContext();
        HttpUriRequestBase httpRequest =
                new HttpUriRequestBase(request.method(), URI.create(pathPrefix + request.path()));
        httpRequest.setScheme(host.getSchemeName());
        httpRequest.setAuthority(new URIAuthority(host.getHostName(), host.getPort()));

        request.headers().forEach(httpRequest::addHeader);

        byte[] bodyBytes = request.bodyBytes();
        if (bodyBytes != null) {
            httpRequest.setEntity(new ByteArrayEntity(bodyBytes, null));
        } else if (request.body() != null) {
            httpRequest.setEntity(new InputStreamEntity(request.body(), null));
        }

        String streamKind = streamKind(request);
        if (streamKind != null) {
            httpRequest.setConfig(streamRequestConfig);
        }

        if (request.hijackedInput() != null) {
            context.setAttribute(HijackingHttpRequestExecutor.HIJACKED_INPUT_ATTRIBUTE, request.hijackedInput());
            httpRequest.setHeader("Upgrade", "tcp");
            httpRequest.setHeader("Connection", "Upgrade");
        }

        try {
            boolean ttyExec = isTtyExec(request);
            ClassicHttpResponse response = httpClient.executeOpen(host, httpRequest, context);
            AtomicInteger openOfKind = null;
            AtomicInteger openOfService = null;
            if (streamKind != null) {
                openOfKind = openStreams.computeIfAbsent(streamKind, kind -> new AtomicInteger());
                openOfKind.incrementAndGet();
                openOfService = openStreamsByService.computeIfAbsent(
                        DockerContainerServices.serviceOf(streamContainer(request)), service -> new AtomicInteger());
                openOfService.incrementAndGet();
            } else {
                forgetIfRemoved(request, response.getCode());
            }
            return new FlociResponse(httpRequest, response, ttyExec && response.getCode() == 200,
                    openOfKind, openOfService);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (LinkageError e) {
            // docker-java's named-pipe socket loads JNA when the pipe will not open. A JNA that
            // cannot load (no native dispatch library for this platform) throws an Error, which
            // the callers' catch (Exception) blocks would not stop, and one thrown during startup
            // took down the whole emulator. As an exception it fails this one Docker call, the
            // same way an unreachable daemon does.
            throw new IllegalStateException("Docker's " + host.getSchemeName()
                    + " transport could not load its native library: " + e, e);
        }
    }

    @Override
    public void close() throws IOException {
        httpClient.close();
    }

    /**
     * The pool's leased, available and pending connections and its limit, and the long-lived stream
     * responses still open by kind and by service (only those with an open stream are listed), read
     * at this moment.
     */
    public PoolGauges gauges() {
        PoolStats stats = connectionManager.getTotalStats();
        return new PoolGauges(stats.getLeased(), stats.getAvailable(), stats.getPending(), stats.getMax(),
                nonZero(openStreams), nonZero(openStreamsByService));
    }

    private static Map<String, Integer> nonZero(Map<String, AtomicInteger> counts) {
        Map<String, Integer> open = new TreeMap<>();
        counts.forEach((key, count) -> {
            int value = count.get();
            if (value > 0) {
                open.put(key, value);
            }
        });
        return open;
    }

    /**
     * A point-in-time reading of one pool, see {@link #gauges()}. {@code openStreamsByService} counts
     * the same streams as {@code openStreams}, by the service of the container each follows.
     */
    public record PoolGauges(int leased, int available, int pending, int max, Map<String, Integer> openStreams,
                             Map<String, Integer> openStreamsByService) {

        /** Whether nothing is in use: no lease, no caller waiting for one and no stream open. */
        public boolean idle() {
            return leased == 0 && pending == 0 && openStreams.isEmpty();
        }
    }

    private static boolean isTtyExec(Request request) throws IOException {
        String route = request.path().split("\\?", 2)[0];
        if (!"POST".equals(request.method()) || !route.contains("/exec/") || !route.endsWith("/start")
                || request.bodyBytes() == null) {
            return false;
        }
        JsonNode config = EXEC_CONFIG_MAPPER.readTree(request.bodyBytes());
        return config != null && config.path("Tty").asBoolean() && !config.path("Detach").asBoolean();
    }

    /**
     * Requests whose response is a stream that stays open, and may stay silent, for as long as what it
     * follows: a container wait, exec and attach output, followed logs, streamed stats, daemon events,
     * and the progress of an image pull or build. The response timeout bounds how long a silent
     * daemon is waited for, so applied to these it would cut off a quiet but healthy stream; they get
     * none. Every other call keeps the response timeout.
     */
    static boolean isLongLivedStream(Request request) {
        return streamKind(request) != null;
    }

    /**
     * The kind of long-lived stream a request opens ({@code wait}, {@code attach}, {@code exec},
     * {@code build}, {@code pull}, {@code events}, {@code logs}, {@code stats}, or {@code hijacked} for
     * another upgraded connection), or null for an ordinary call. See {@link #isLongLivedStream}.
     */
    static String streamKind(Request request) {
        String path = request.path();
        int queryStart = path.indexOf('?');
        String route = queryStart < 0 ? path : path.substring(0, queryStart);
        String query = queryStart < 0 ? "" : path.substring(queryStart + 1);
        String kind = switch (request.method()) {
            case "POST" -> {
                if (route.endsWith("/wait")) {
                    yield "wait";
                } else if (route.endsWith("/attach")) {
                    yield "attach";
                } else if (route.endsWith("/build")) {
                    yield "build";
                } else if (route.endsWith("/images/create")) {
                    yield "pull";
                } else if (route.contains("/exec/") && route.endsWith("/start")) {
                    yield "exec";
                }
                yield null;
            }
            case "GET" -> {
                if (route.endsWith("/events")) {
                    yield "events";
                } else if (route.endsWith("/logs") && queryFlag(query, "follow", false)) {
                    yield "logs";
                } else if (route.endsWith("/stats") && queryFlag(query, "stream", true)) {
                    yield "stats";
                }
                yield null;
            }
            default -> null;
        };
        return kind == null && request.hijackedInput() != null ? "hijacked" : kind;
    }

    /**
     * The container a stream follows, as the request names it (an id or a name), or null for a stream
     * that follows none: an exec start names its exec, and pulls, builds and events no container.
     */
    static String streamContainer(Request request) {
        String[] segments = containerRoute(request.path());
        return segments != null && segments.length == 2 ? segments[0] : null;
    }

    /**
     * After an ordinary call: a container removed (or already gone) is forgotten by
     * {@link DockerContainerServices}, so its entry does not outlive it.
     */
    private static void forgetIfRemoved(Request request, int statusCode) {
        if (!"DELETE".equals(request.method()) || (statusCode >= 300 && statusCode != 404)) {
            return;
        }
        String[] segments = containerRoute(request.path());
        if (segments != null && segments.length == 1) {
            DockerContainerServices.forget(segments[0]);
        }
    }

    /**
     * The route segments after {@code /containers/}, at most two (the container and its action), or
     * null when the route is not about one container.
     */
    private static String[] containerRoute(String path) {
        int queryStart = path.indexOf('?');
        String route = queryStart < 0 ? path : path.substring(0, queryStart);
        int start = route.indexOf("/containers/");
        if (start < 0) {
            return null;
        }
        String[] segments = route.substring(start + "/containers/".length()).split("/");
        if (segments.length == 0 || segments.length > 2 || segments[0].isEmpty() || "create".equals(segments[0])
                || ("json".equals(segments[0]) && segments.length == 1) || "prune".equals(segments[0])) {
            return null;
        }
        return segments;
    }

    /** A boolean query parameter as Docker reads it ({@code 1} or {@code true}), or its default when absent. */
    private static boolean queryFlag(String query, String name, boolean defaultValue) {
        for (String param : query.split("&")) {
            if (param.startsWith(name + "=")) {
                String value = param.substring(name.length() + 1);
                return "1".equals(value) || "true".equalsIgnoreCase(value);
            }
        }
        return defaultValue;
    }

    /** Builds a client; mirrors docker-java's {@code ApacheDockerHttpClient.Builder}. */
    public static final class Builder {

        private URI dockerHost;
        private SSLConfig sslConfig;
        private int maxConnections = Integer.MAX_VALUE;
        private Duration connectionTimeout;
        private Duration responseTimeout;
        private Duration connectionRequestTimeout;

        public Builder dockerHost(URI value) {
            this.dockerHost = Objects.requireNonNull(value, "dockerHost");
            return this;
        }

        public Builder sslConfig(SSLConfig value) {
            this.sslConfig = value;
            return this;
        }

        public Builder maxConnections(int value) {
            this.maxConnections = value;
            return this;
        }

        public Builder connectionTimeout(Duration value) {
            this.connectionTimeout = value;
            return this;
        }

        public Builder responseTimeout(Duration value) {
            this.responseTimeout = value;
            return this;
        }

        /**
         * How long a call waits to lease a pooled connection before failing with
         * {@code ConnectionRequestTimeoutException}; unset keeps httpclient5's own default (3 minutes).
         */
        public Builder connectionRequestTimeout(Duration value) {
            this.connectionRequestTimeout = value;
            return this;
        }

        public FlociDockerHttpClient build() {
            Objects.requireNonNull(dockerHost, "dockerHost");
            return new FlociDockerHttpClient(dockerHost, sslConfig, maxConnections, connectionTimeout, responseTimeout,
                    connectionRequestTimeout);
        }
    }

    private static final class FlociResponse implements Response {

        private static final Logger LOG = Logger.getLogger(FlociResponse.class);

        private final HttpUriRequestBase request;
        private final ClassicHttpResponse response;
        private final boolean ttyExec;
        /** The open-stream counts this response is in, by kind and by service, or null for an ordinary call. */
        private final AtomicInteger openOfKind;
        private final AtomicInteger openOfService;
        private final AtomicBoolean closed = new AtomicBoolean();
        private InputStream body;

        FlociResponse(HttpUriRequestBase request, ClassicHttpResponse response, boolean ttyExec,
                      AtomicInteger openOfKind, AtomicInteger openOfService) {
            this.request = request;
            this.response = response;
            this.ttyExec = ttyExec;
            this.openOfKind = openOfKind;
            this.openOfService = openOfService;
        }

        @Override
        public int getStatusCode() {
            return response.getCode();
        }

        @Override
        public Map<String, List<String>> getHeaders() {
            return Stream.of(response.getHeaders()).collect(Collectors.groupingBy(
                    NameValuePair::getName,
                    Collectors.mapping(NameValuePair::getValue, Collectors.toList())));
        }

        @Override
        public String getHeader(String name) {
            Header header = response.getFirstHeader(name);
            return header != null ? header.getValue() : null;
        }

        @Override
        public InputStream getBody() {
            try {
                if (body == null) {
                    InputStream content = response.getEntity() != null
                            ? response.getEntity().getContent() : EmptyInputStream.INSTANCE;
                    body = ttyExec ? new TtyFramingInputStream(content) : content;
                }
                return body;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void close() {
            if (openOfKind != null && closed.compareAndSet(false, true)) {
                openOfKind.decrementAndGet();
                openOfService.decrementAndGet();
            }
            try {
                request.abort();
            } catch (Exception e) {
                LOG.debugv(e, "Failed to abort the Docker request");
            }
            try {
                response.close();
            } catch (ConnectionClosedException e) {
                LOG.tracev(e, "Docker response already closed");
            } catch (Exception e) {
                LOG.debugv(e, "Failed to close the Docker response");
            }
        }
    }
}
