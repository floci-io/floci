package io.github.hectorvent.floci.services.iot;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.PfxOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Terminates MQTT-over-WebSocket (ws:// and wss://) in Vert.x and pipes the raw MQTT byte
 * stream to the Moquette broker's plain TCP listener.
 *
 * <p>Moquette's own WebSocket transport mis-frames packets from common clients (mqtt.js, the
 * AWS IoT Device SDK); Vert.x WebSocket support interoperates with them correctly. Each
 * WebSocket connection is backed by a dedicated TCP socket to {@code 127.0.0.1:<brokerPort>},
 * so all authentication and authorization still happen inside Moquette via the custom authorizer.
 */
@ApplicationScoped
public class IotWebSocketBridge {

    private static final Logger LOG = Logger.getLogger(IotWebSocketBridge.class);
    private static final long CLOSE_GRACE_MS = 200;

    private final Vertx vertx;

    private NetClient netClient;
    private HttpServer plainServer;
    private HttpServer tlsServer;

    @Inject
    public IotWebSocketBridge(Vertx vertx) {
        this.vertx = vertx;
    }

    void start(String host, int wsPort, int wssPort, int targetTcpPort,
               String keystorePath, String keystorePassword) {
        this.netClient = vertx.createNetClient();
        plainServer = listen(host, wsPort, targetTcpPort, null, null);
        if (wssPort > 0 && keystorePath != null) {
            tlsServer = listen(host, wssPort, targetTcpPort, keystorePath, keystorePassword);
        }
    }

    void stop() {
        if (plainServer != null) {
            plainServer.close();
            plainServer = null;
        }
        if (tlsServer != null) {
            tlsServer.close();
            tlsServer = null;
        }
        if (netClient != null) {
            netClient.close();
            netClient = null;
        }
    }

    private HttpServer listen(String host, int port, int targetTcpPort,
                              String keystorePath, String keystorePassword) {
        HttpServerOptions options = new HttpServerOptions()
                .addWebSocketSubProtocol("mqtt")
                .addWebSocketSubProtocol("mqttv3.1")
                .setPerMessageWebSocketCompressionSupported(false)
                .setPerFrameWebSocketCompressionSupported(false);
        if (keystorePath != null) {
            options.setSsl(true).setPfxKeyCertOptions(
                    new PfxOptions().setPath(keystorePath).setPassword(keystorePassword));
        }
        HttpServer server = vertx.createHttpServer(options)
                .webSocketHandler(ws -> bridge(ws, targetTcpPort));
        try {
            server.listen(port, host).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warnv("IoT WebSocket bridge failed to bind {0}:{1}: {2}", host, port, e.getMessage());
        }
        return server;
    }

    private void bridge(ServerWebSocket ws, int targetTcpPort) {
        ws.pause();
        netClient.connect(targetTcpPort, "127.0.0.1").onComplete(ar -> {
            if (ar.failed()) {
                LOG.debugv("IoT WS bridge could not reach broker TCP port {0}: {1}",
                        targetTcpPort, ar.cause().getMessage());
                ws.close();
                return;
            }
            NetSocket socket = ar.result();
            // Track the last outbound write so a backend close (e.g. CONNACK-then-disconnect on
            // an auth failure) flushes the final frame to the client before the WebSocket closes.
            AtomicReference<Future<Void>> lastWrite = new AtomicReference<>(Future.succeededFuture());
            // Clients (mqtt.js, the AWS IoT Device SDK) fragment the MQTT byte stream across many
            // small WebSocket frames. Reassemble whole MQTT packets before writing to Moquette's
            // TCP listener so the broker always decodes well-formed packets. The accumulator is
            // only touched from the WebSocket's event loop, where frames arrive in order.
            MqttPacketAssembler assembler = new MqttPacketAssembler();
            ws.frameHandler(frame -> {
                Buffer data = frame.binaryData();
                if (data != null && data.length() > 0) {
                    assembler.append(data).forEach(packet -> socket.write(packet));
                }
            });
            socket.handler(buf -> lastWrite.set(ws.writeBinaryMessage(buf.copy())));
            // When the broker closes (e.g. CONNACK-refused then disconnect on an auth failure),
            // flush the final frame and give the client a brief grace window to read it before
            // tearing down the WebSocket — strict clients drop unread data on an abrupt close.
            Runnable closeWebSocket = () -> lastWrite.get().onComplete(
                    ignored -> vertx.setTimer(CLOSE_GRACE_MS, t -> ws.close()));
            ws.closeHandler(v -> socket.close());
            socket.closeHandler(v -> closeWebSocket.run());
            ws.exceptionHandler(t -> socket.close());
            socket.exceptionHandler(t -> closeWebSocket.run());
            ws.resume();
        });
    }
}
