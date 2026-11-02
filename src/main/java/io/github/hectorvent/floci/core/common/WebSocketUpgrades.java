package io.github.hectorvent.floci.core.common;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.net.NetSocket;

/**
 * The parts of proxying a WebSocket handshake that the proxies Floci emulates share. An ALB and a
 * CloudFront distribution both forward a WebSocket upgrade to their origin with its upgrade headers
 * intact, which an ordinary proxied request cannot do: {@code Connection} and {@code Upgrade} are
 * hop-by-hop and are stripped. AWS only tunnels WebSocket upgrades, over HTTP/1.1.
 */
public final class WebSocketUpgrades {

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
