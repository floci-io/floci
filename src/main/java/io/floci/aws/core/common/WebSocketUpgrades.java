package io.floci.aws.core.common;

import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClosedException;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.net.NetSocket;
import org.jboss.logging.Logger;

/**
 * The parts of proxying a WebSocket handshake that the proxies Floci emulates share. An ALB and a
 * CloudFront distribution both forward a WebSocket upgrade to their origin with its upgrade headers
 * intact, which an ordinary proxied request cannot do: {@code Connection} and {@code Upgrade} are
 * hop-by-hop and are stripped. AWS only tunnels WebSocket upgrades, over HTTP/1.1.
 */
public final class WebSocketUpgrades {

    private static final Logger LOG = Logger.getLogger(WebSocketUpgrades.class);

    private WebSocketUpgrades() {
    }

    /** Whether the request is an HTTP/1.1 GET asking to upgrade the connection to WebSocket. */
    public static boolean isHandshake(HttpServerRequest req) {
        if (req.version() != HttpVersion.HTTP_1_1 || req.method() != HttpMethod.GET) {
            return false;
        }
        if (!"websocket".equalsIgnoreCase(req.getHeader("Upgrade"))) {
            return false;
        }
        for (String connection : req.headers().getAll("Connection")) {
            for (String token : connection.split(",")) {
                if ("upgrade".equalsIgnoreCase(token.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Releases a refused connect() request once forwarding has finished or failed. Reading its
     * response does not finish the request, so the connection still holds a pool slot. Only the
     * expected closed-connection notification from this deliberate cleanup is logged at debug.
     */
    public static void closeRefusedRequest(HttpClientRequest request) {
        HttpConnection connection = request.connection();
        request.exceptionHandler(err -> {
            if (err instanceof HttpClosedException) {
                LOG.debugv("Refused WebSocket origin connection closed: {0}", connection.remoteAddress());
            } else {
                LOG.warnv(err, "Unexpected error closing refused WebSocket origin connection to {0}",
                        connection.remoteAddress());
            }
        });
        connection.close().onFailure(err ->
                LOG.warnv(err, "Could not close refused WebSocket origin connection to {0}", connection.remoteAddress()));
    }

    /**
     * Copies everything {@code source} reads to {@code destination}, pausing the source while the
     * destination's write queue is full. {@code onData} runs for each buffer, and {@code close} when
     * the source ends, closes or fails.
     */
    public static void relay(NetSocket source, NetSocket destination, Runnable onData, Runnable close) {
        source.handler(buffer -> {
            onData.run();
            destination.write(buffer);
            if (destination.writeQueueFull()) {
                source.pause();
                destination.drainHandler(ignored -> source.resume());
            }
        });
        source.endHandler(ignored -> close.run());
        source.closeHandler(ignored -> close.run());
        source.exceptionHandler(err -> close.run());
    }
}
