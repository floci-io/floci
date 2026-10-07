/*
 * Adapted from docker-java 3.7.1, docker-java-transport-httpclient5,
 * com.github.dockerjava.httpclient5.ApacheDockerHttpClient and ApacheDockerHttpClientImpl.
 * Copyright docker-java contributors, licensed under the Apache License, Version 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0). Changes: one Floci-owned class with a builder,
 * JBoss Logging, switch expressions; the pool, socket and request settings are unchanged.
 */
package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.transport.NamedPipeSocket;
import com.github.dockerjava.transport.SSLConfig;
import com.github.dockerjava.transport.UnixSocket;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Floci's own Docker HTTP transport over httpclient5: the same transport docker-java's
 * {@code ApacheDockerHttpClient} builds, owned here so Floci can set what that builder does not
 * expose (idle-connection validation and eviction, the connection-lease timeout). It currently
 * applies exactly docker-java's settings: unix, npipe and tcp hosts, TLS from the SSL config, a pool
 * of {@code maxConnections} for one route, no socket read timeout on the pool ({@code SO_TIMEOUT} 0),
 * the request's response timeout, no stale-connection validation, and hijacked exec/attach upgrades
 * through {@link HijackingHttpRequestExecutor}.
 */
public final class FlociDockerHttpClient implements DockerHttpClient {

    private final CloseableHttpClient httpClient;
    private final HttpHost host;
    private final String pathPrefix;

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

        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager(
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
        connectionManager.setDefaultConnectionConfig(ConnectionConfig.custom()
                .setValidateAfterInactivity(TimeValue.NEG_ONE_SECOND)
                .setConnectTimeout(connectionTimeout != null
                        ? Timeout.of(connectionTimeout.toNanos(), TimeUnit.NANOSECONDS) : null)
                .build());

        httpClient = HttpClients.custom()
                .setRequestExecutor(new HijackingHttpRequestExecutor(null))
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(responseTimeout != null
                                ? Timeout.of(responseTimeout.toNanos(), TimeUnit.NANOSECONDS) : null)
                        .setConnectionRequestTimeout(connectionRequestTimeout != null
                                ? Timeout.of(connectionRequestTimeout.toNanos(), TimeUnit.NANOSECONDS) : null)
                        .build())
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
                        return UnixSocket.get(path);
                    }
                    if ("npipe".equalsIgnoreCase(scheme)) {
                        return new NamedPipeSocket(path);
                    }
                    return socksProxy == null ? new Socket() : new Socket(socksProxy);
                },
                DefaultSchemePortResolver.INSTANCE,
                SystemDefaultDnsResolver.INSTANCE,
                name -> "https".equalsIgnoreCase(name) ? tlsSocketStrategy : null);
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

        if (request.hijackedInput() != null) {
            context.setAttribute(HijackingHttpRequestExecutor.HIJACKED_INPUT_ATTRIBUTE, request.hijackedInput());
            httpRequest.setHeader("Upgrade", "tcp");
            httpRequest.setHeader("Connection", "Upgrade");
        }

        try {
            ClassicHttpResponse response = httpClient.executeOpen(host, httpRequest, context);
            return new FlociResponse(httpRequest, response);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws IOException {
        httpClient.close();
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

        FlociResponse(HttpUriRequestBase request, ClassicHttpResponse response) {
            this.request = request;
            this.response = response;
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
                return response.getEntity() != null ? response.getEntity().getContent() : EmptyInputStream.INSTANCE;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void close() {
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
