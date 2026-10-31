package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.RequestHost;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.common.WebSocketUpgrades;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tunnels a viewer's WebSocket connection through a CloudFront distribution to its custom origin, as
 * CloudFront does on every distribution with no setting. The handshake is caught before JAX-RS, which
 * cannot hand over the connection: {@link CloudFrontServingController#webSocketOrigin} resolves the
 * behavior and origin as an ordinary GET, the handshake goes to the origin with its {@code Upgrade}
 * and {@code Connection} fields, and once the origin answers {@code 101 Switching Protocols} the two
 * connections are joined into a byte tunnel. Any other origin answer is relayed as an ordinary
 * response. A handshake the distribution would not forward to a custom origin is left to JAX-RS,
 * which answers it as the GET it also is.
 *
 * <p>The {@code Sec-WebSocket-*} fields reach the origin only when the behavior's origin request
 * policy forwards them, as on AWS, which documents forwarding all viewer headers or at least
 * {@code Sec-WebSocket-Key} and {@code Sec-WebSocket-Version}.
 */
@ApplicationScoped
public class CloudFrontWebSocketProxy {

    private static final Logger LOG = Logger.getLogger(CloudFrontWebSocketProxy.class);
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade");

    private final CloudFrontService service;
    private final CloudFrontServingController servingController;
    private final ServiceConfigAccess serviceConfigAccess;
    private final Vertx vertx;
    private final HttpClient client;

    @Inject
    public CloudFrontWebSocketProxy(CloudFrontService service, CloudFrontServingController servingController,
                                    ServiceConfigAccess serviceConfigAccess, Vertx vertx) {
        this.service = service;
        this.servingController = servingController;
        this.serviceConfigAccess = serviceConfigAccess;
        this.vertx = vertx;
        // Keep-alive, since a client without it sends Connection: close in place of Connection: Upgrade.
        // A connection that becomes a tunnel never returns to the pool. The origin's certificate is
        // checked against its own name, which goes in SNI though the connection is made to its address.
        this.client = vertx.createHttpClient(new HttpClientOptions()
                .setKeepAlive(true)
                .setVerifyHost(true)
                .setForceSni(true));
    }

    @PreDestroy
    void close() {
        client.close();
    }

    void register(@Observes Router router) {
        router.route().handler(this::handle);
    }

    void handle(RoutingContext context) {
        HttpServerRequest request = context.request();
        // This route runs ahead of the filter that refuses a disabled service, so it checks for itself.
        if (!WebSocketUpgrades.isHandshake(request) || !serviceConfigAccess.isEnabled("cloudfront")) {
            context.next();
            return;
        }
        String host = RequestHost.of(request);
        if (host == null || service.findByHost(host) == null) {
            context.next();
            return;
        }
        request.pause();
        // Resolving the origin reads policies and the origin's DNS, so it runs off the event loop.
        vertx.<CloudFrontServingController.WebSocketOrigin>executeBlocking(
                        () -> servingController.webSocketOrigin(request), false)
                .onComplete(result -> {
                    if (result.failed() || result.result() == null) {
                        request.resume();
                        context.next();
                        return;
                    }
                    tunnel(request, result.result());
                });
    }

    private void tunnel(HttpServerRequest request, CloudFrontServingController.WebSocketOrigin origin) {
        URI uri = origin.uri();
        boolean ssl = "https".equalsIgnoreCase(uri.getScheme());
        String path = uri.getRawPath() + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
        RequestOptions options = new RequestOptions()
                .setServer(SocketAddress.inetSocketAddress(uri.getPort(), origin.address().getHostAddress()))
                .setHost(CloudFrontServingController.normalizeHost(uri.getHost()))
                .setPort(uri.getPort())
                .setSsl(ssl)
                .setURI(path)
                .setMethod(HttpMethod.GET)
                .setConnectTimeout(origin.connectTimeout().toMillis());
        client.request(options)
                .onSuccess(originRequest -> handshake(request, originRequest, origin))
                .onFailure(err -> {
                    LOG.debugv("CloudFront could not connect a WebSocket to origin {0}: {1}",
                            uri.getHost(), err.getMessage());
                    request.resume();
                    request.response().setStatusCode(502).end("Bad Gateway.");
                });
    }

    private void handshake(HttpServerRequest request, HttpClientRequest originRequest,
                           CloudFrontServingController.WebSocketOrigin origin) {
        for (CloudFrontOriginRequestBuilder.Header header : origin.headers()) {
            originRequest.headers().add(header.name(), header.value());
        }
        originRequest.putHeader("Connection", "Upgrade");
        originRequest.putHeader("Upgrade", request.getHeader("Upgrade"));
        // The origin's timeouts bound its answer to the handshake, and the body of an answer that
        // refuses the upgrade. Once the tunnel is open, data flows at the pace of the two ends.
        AtomicBoolean answered = new AtomicBoolean();
        OriginClock clock = new OriginClock(vertx, origin.responseTimeout(), origin.responseCompletionTimeout());
        clock.onTimeout(() -> {
            if (answered.compareAndSet(false, true)) {
                originRequest.reset();
                request.resume();
                request.response().setStatusCode(504).end(CloudFrontServingController.ORIGIN_TIMEOUT_MESSAGE);
            }
        });
        clock.arm();
        originRequest.connect()
                .onSuccess(response -> {
                    if (!answered.compareAndSet(false, true)) {
                        response.netSocket().close();
                        return;
                    }
                    HttpServerResponse viewerResponse = request.response();
                    viewerResponse.setStatusCode(response.statusCode());
                    copyEndToEndHeaders(response.headers(), viewerResponse);
                    if (response.statusCode() != 101) {
                        request.resume();
                        relayRefusal(response, viewerResponse, originRequest, clock);
                        return;
                    }
                    clock.stop();
                    viewerResponse.putHeader("Connection", "Upgrade");
                    viewerResponse.putHeader("Upgrade", response.getHeader("Upgrade"));
                    NetSocket originSocket = response.netSocket();
                    request.toNetSocket()
                            .onSuccess(viewerSocket -> splice(viewerSocket, originSocket))
                            .onFailure(err -> {
                                LOG.debugv("CloudFront WebSocket tunnel lost the viewer: {0}", err.getMessage());
                                originSocket.close();
                            });
                })
                .onFailure(err -> {
                    clock.stop();
                    if (!answered.compareAndSet(false, true)) {
                        return;
                    }
                    LOG.debugv("CloudFront WebSocket handshake with the origin failed: {0}", err.getMessage());
                    request.resume();
                    if (request.response().headWritten()) {
                        request.response().close();
                    } else {
                        request.response().setStatusCode(502).end("Bad Gateway.");
                    }
                });
    }

    /**
     * Streams the body of an origin answer that refused the upgrade, within the origin's timeouts. Its
     * head has already been relayed, so an origin that stalls past them ends the viewer's connection,
     * as CloudFront does when the origin does not complete a response in time.
     */
    private static void relayRefusal(HttpClientResponse response, HttpServerResponse viewerResponse,
                                     HttpClientRequest originRequest, OriginClock clock) {
        if (response.getHeader("Content-Length") == null) {
            viewerResponse.setChunked(true);
        }
        clock.onTimeout(() -> {
            LOG.debugv("CloudFront origin stalled while refusing a WebSocket upgrade");
            originRequest.reset();
            viewerResponse.reset();
        });
        clock.arm();
        viewerResponse.closeHandler(ignored -> {
            clock.stop();
            originRequest.reset();
        });
        response.handler(buffer -> {
            clock.arm();
            viewerResponse.write(buffer);
            if (viewerResponse.writeQueueFull()) {
                // The time a slow viewer takes to receive the body is not the origin's.
                clock.suspend();
                response.pause();
                viewerResponse.drainHandler(ignored -> {
                    clock.arm();
                    response.resume();
                });
            }
        });
        response.endHandler(ignored -> {
            clock.stop();
            viewerResponse.end();
        });
        response.exceptionHandler(err -> {
            clock.stop();
            LOG.debugv("CloudFront origin failed while refusing a WebSocket upgrade: {0}", err.getMessage());
            viewerResponse.reset();
        });
    }

    /**
     * The time the origin takes, bounded as CloudFront bounds it: no longer than the origin's response
     * timeout between one packet and the next, and no longer than its response completion timeout in
     * all, when it has one. Only armed time counts, so a viewer that reads slowly does not use up the
     * origin's time. Every method runs on the request's event loop.
     */
    static final class OriginClock {

        private final Vertx vertx;
        private final long readTimeoutMillis;
        private final boolean completionLimited;
        private long completionRemainingNanos;
        private Runnable onTimeout = () -> { };
        private long timerId = -1;
        private long armedAt;
        private boolean stopped;

        OriginClock(Vertx vertx, Duration readTimeout, Duration completionTimeout) {
            this.vertx = vertx;
            this.readTimeoutMillis = Math.max(1, readTimeout.toMillis());
            this.completionLimited = completionTimeout != null && !completionTimeout.isZero()
                    && !completionTimeout.isNegative();
            this.completionRemainingNanos = completionLimited ? completionTimeout.toNanos() : Long.MAX_VALUE;
        }

        void onTimeout(Runnable onTimeout) {
            this.onTimeout = onTimeout;
        }

        /** Starts, or restarts after a packet, the wait for the origin's next packet. */
        void arm() {
            if (stopped) {
                return;
            }
            suspend();
            long delayMillis = readTimeoutMillis;
            if (completionLimited) {
                delayMillis = Math.min(delayMillis, Math.max(1, completionRemainingNanos / 1_000_000));
            }
            armedAt = System.nanoTime();
            timerId = vertx.setTimer(delayMillis, ignored -> {
                timerId = -1;
                stopped = true;
                onTimeout.run();
            });
        }

        /** Stops counting until the next {@link #arm}, keeping the completion time already spent. */
        void suspend() {
            if (timerId < 0) {
                return;
            }
            vertx.cancelTimer(timerId);
            timerId = -1;
            if (completionLimited) {
                completionRemainingNanos -= System.nanoTime() - armedAt;
            }
        }

        void stop() {
            suspend();
            stopped = true;
        }
    }

    private static void copyEndToEndHeaders(MultiMap headers, HttpServerResponse response) {
        for (String name : headers.names()) {
            if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                response.putHeader(name, headers.getAll(name));
            }
        }
    }

    private static void splice(NetSocket viewer, NetSocket origin) {
        AtomicBoolean closed = new AtomicBoolean();
        Runnable close = () -> {
            if (closed.compareAndSet(false, true)) {
                viewer.close();
                origin.close();
            }
        };
        Runnable onData = () -> { };
        WebSocketUpgrades.relay(viewer, origin, onData, close);
        WebSocketUpgrades.relay(origin, viewer, onData, close);
    }
}
