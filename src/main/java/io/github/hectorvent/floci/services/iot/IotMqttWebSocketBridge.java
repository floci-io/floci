package io.github.hectorvent.floci.services.iot;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.vertx.core.Vertx;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Serves MQTT over WebSocket at {@code /mqtt} on the HTTP and HTTPS ports, the path and port AWS
 * IoT uses for browser clients, the Device SDK's WebSocket transport and secure tunnelling. Frames
 * are piped byte for byte to the broker's plaintext listener, so every broker feature and every
 * authorization rule applies without a second code path. No client certificate is involved: on
 * AWS this path authenticates by SigV4 or a custom authorizer. The broker enforces a custom
 * authorizer named in the MQTT username, the URL query or an upgrade header, and stays permissive
 * otherwise; the upgrade's headers, query, server name and client address reach it through
 * {@link IotMqttBrokerService#webSocketUpgrades()}.
 *
 * <p>Everything for one session runs on the event loop of the WebSocket's connection, the
 * broker socket included, so the handlers below never race each other.
 */
@ApplicationScoped
public class IotMqttWebSocketBridge {

    static final String PATH = "/mqtt";
    private static final Logger LOG = Logger.getLogger(IotMqttWebSocketBridge.class);
    private static final short CLOSE_UNSUPPORTED_DATA = 1003;
    private static final short CLOSE_INTERNAL_ERROR = 1011;

    private final EmulatorConfig config;
    private final Vertx vertx;
    private final NetClient client;
    private final IotMqttBrokerService broker;

    @Inject
    public IotMqttWebSocketBridge(EmulatorConfig config, Vertx vertx, IotMqttBrokerService broker) {
        this.config = config;
        this.vertx = vertx;
        this.client = vertx.createNetClient();
        this.broker = broker;
    }

    void init(@Observes Router router) {
        router.route(PATH).handler(this::upgrade);
    }

    private void upgrade(RoutingContext ctx) {
        if (!"websocket".equalsIgnoreCase(ctx.request().getHeader("Upgrade"))) {
            ctx.next();
            return;
        }
        // startIfEnabled blocks on the listen, and ordered blocking tasks queue behind every other
        // blocking task of this event loop (a Lambda invocation, say); the start is idempotent and
        // synchronized, so the handshake need not wait its turn.
        vertx.executeBlocking(() -> {
            broker.startIfEnabled();
            return broker.isRunning();
        }, false).onSuccess(running -> {
            if (!running) {
                ctx.response().setStatusCode(503).end();
                return;
            }
            ctx.request().toWebSocket()
                    .onSuccess(this::pipeToBroker)
                    .onFailure(error -> rejectUpgrade(ctx, error));
        }).onFailure(error -> {
            LOG.warnv("MQTT WebSocket upgrade refused, the broker did not start: {0}", error.getMessage());
            ctx.response().setStatusCode(503).end();
        });
    }

    private static void rejectUpgrade(RoutingContext ctx, Throwable error) {
        LOG.debugv("MQTT WebSocket upgrade failed: {0}", error.getMessage());
        if (!ctx.response().ended()) {
            ctx.response().setStatusCode(400).end();
        }
    }

    private void pipeToBroker(ServerWebSocket ws) {
        ws.pause();
        int port = config.services().iot().mqtt().port();
        String host = brokerHost();
        client.connect(port, host)
                .onSuccess(socket -> {
                    if (ws.isClosed()) {
                        socket.close();
                        return;
                    }
                    // Registered before start() lets the first frame through, so it is there when the CONNECT
                    // reaches the broker; removed only if still this very instance, as the address can be reused.
                    SocketAddress bridgeAddress = socket.localAddress();
                    IotCustomAuthorizer.WebSocketUpgrade upgrade = upgradeOf(ws);
                    broker.webSocketUpgrades().put(bridgeAddress, upgrade);
                    new BridgeSession(ws, socket, forgetting(broker.webSocketUpgrades(), bridgeAddress, upgrade)).start();
                })
                .onFailure(error -> {
                    LOG.warnv("MQTT WebSocket bridge could not reach the broker on {0}:{1}: {2}",
                            host, port, error.getMessage());
                    ws.close(CLOSE_INTERNAL_ERROR, "MQTT broker unavailable");
                });
    }

    /** Drops the session's upgrade unless the address already carries a newer session's. */
    static Runnable forgetting(Map<SocketAddress, IotCustomAuthorizer.WebSocketUpgrade> upgrades, SocketAddress address,
                               IotCustomAuthorizer.WebSocketUpgrade upgrade) {
        return () -> upgrades.computeIfPresent(address, (key, current) -> current == upgrade ? null : current);
    }

    /**
     * The upgrade as AWS hands it to a custom authorizer; the server name only over TLS, from SNI or
     * else the Host header. The client's address rides along for the broker's policy evaluation.
     */
    private static IotCustomAuthorizer.WebSocketUpgrade upgradeOf(ServerWebSocket ws) {
        Map<String, String> headers = new LinkedHashMap<>();
        // ponytail: a repeated header keeps its last value.
        ws.headers().forEach(header -> headers.put(header.getKey().toLowerCase(Locale.ROOT), header.getValue()));
        String serverName = null;
        if (ws.isSsl()) {
            serverName = IotMqttBrokerService.requestedServerName(ws.sslSession());
            if (serverName == null && ws.authority() != null && !IotMqttBrokerService.isAddressLiteral(ws.authority().host())) {
                serverName = ws.authority().host();
            }
        }
        SocketAddress client = ws.remoteAddress();
        return new IotCustomAuthorizer.WebSocketUpgrade(headers, ws.query() == null ? "" : ws.query(), serverName,
                client == null ? null : client.host());
    }

    static final class BridgeSession {

        private final ServerWebSocket webSocket;
        private final NetSocket socket;
        private final Runnable onClose;
        private final AtomicBoolean closed = new AtomicBoolean();

        BridgeSession(ServerWebSocket webSocket, NetSocket socket, Runnable onClose) {
            this.webSocket = webSocket;
            this.socket = socket;
            this.onClose = onClose;
        }

        void start() {
            webSocket.frameHandler(frame -> {
                if (closed.get() || webSocket.isClosed()) {
                    return;
                }
                if (frame.isText()) {
                    close(CLOSE_UNSUPPORTED_DATA, "MQTT over WebSocket is binary");
                    return;
                }
                if (!frame.isBinary() && !frame.isContinuation()) {
                    return;
                }
                socket.write(frame.binaryData());
                if (socket.writeQueueFull()) {
                    webSocket.pause();
                    socket.drainHandler(ignored -> {
                        if (!closed.get()) {
                            webSocket.resume();
                        }
                    });
                }
            });
            webSocket.closeHandler(ignored -> closeSocket());
            webSocket.exceptionHandler(error -> close(CLOSE_INTERNAL_ERROR, "MQTT WebSocket failed"));

            socket.handler(bytes -> {
                if (closed.get() || webSocket.isClosed()) {
                    return;
                }
                webSocket.writeBinaryMessage(bytes);
                if (webSocket.writeQueueFull()) {
                    socket.pause();
                    webSocket.drainHandler(ignored -> {
                        if (!closed.get()) {
                            socket.resume();
                        }
                    });
                }
            });
            socket.closeHandler(ignored -> closeWebSocket());
            socket.exceptionHandler(error -> close(CLOSE_INTERNAL_ERROR, "MQTT broker connection failed"));

            if (webSocket.isClosed()) {
                closeSocket();
            } else {
                webSocket.resume();
            }
        }

        private void close(short code, String reason) {
            if (markClosed()) {
                socket.close();
                if (!webSocket.isClosed()) {
                    webSocket.close(code, reason);
                }
            }
        }

        private void closeSocket() {
            if (markClosed()) {
                socket.close();
            }
        }

        private void closeWebSocket() {
            if (markClosed() && !webSocket.isClosed()) {
                // ponytail: on a custom-auth refusal AWS sends an empty close frame (MQTT 3.1.1) or no close frame (MQTT 5); Vert.x has no public API for either, so this sends status 1000.
                webSocket.close();
            }
        }

        private boolean markClosed() {
            if (!closed.compareAndSet(false, true)) {
                return false;
            }
            onClose.run();
            return true;
        }
    }

    /** The broker binds {@code 0.0.0.0} by default, which is not a connect target; loopback is. */
    private String brokerHost() {
        String host = config.services().iot().mqtt().host();
        return "0.0.0.0".equals(host) ? "127.0.0.1" : host;
    }
}
