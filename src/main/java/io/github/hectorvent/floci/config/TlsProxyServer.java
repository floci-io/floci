package io.github.hectorvent.floci.config;

import io.quarkus.runtime.Startup;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * A TCP proxy server that enables HTTP and HTTPS on the same port (LocalStack parity).
 *
 * <p>When TLS is enabled, Quarkus serves HTTP on an internal port (4510) and HTTPS on
 * another internal port (4511). This proxy listens on the public Floci port (4566) and
 * inspects the first byte of each incoming connection:
 * <ul>
 *   <li>{@code 0x16} (TLS ClientHello) → proxy to HTTPS backend (port 4511)</li>
 *   <li>Anything else → proxy to HTTP backend (port 4510)</li>
 * </ul>
 *
 * <p>This achieves true dual-protocol support on a single port, matching LocalStack's
 * behavior where both {@code http://localhost:4566} and {@code https://localhost:4566}
 * work simultaneously.
 *
 * <p>This bean is only active when {@code floci.tls.enabled=true}. When TLS is disabled,
 * Quarkus serves HTTP directly on port 4566 and this proxy is not started.
 */
@ApplicationScoped
@Startup
public class TlsProxyServer {

    private static final Logger LOG = Logger.getLogger(TlsProxyServer.class);

    /** TLS record content type for Handshake (ClientHello). */
    private static final byte TLS_HANDSHAKE = 0x16;

    private static final int HTTP_BACKEND_PORT = 4510;
    private static final int HTTPS_BACKEND_PORT = 4511;

    private final Vertx vertx;
    private final EmulatorConfig config;
    private NetServer proxyServer;
    private NetClient client;

    @Inject
    public TlsProxyServer(Vertx vertx, EmulatorConfig config) {
        this.vertx = vertx;
        this.config = config;
        startIfTlsEnabled();
    }

    private void startIfTlsEnabled() {
        if (!config.tls().enabled()) {
            return;
        }

        int publicPort = config.port();
        NetServerOptions options = new NetServerOptions()
                .setHost("0.0.0.0")
                .setPort(publicPort);

        proxyServer = vertx.createNetServer(options);
        client = vertx.createNetClient();

        proxyServer.connectHandler(frontSocket -> {
            // Pause incoming data until we've peeked at the first byte
            frontSocket.pause();

            frontSocket.handler(buffer -> {
                // Remove handler and keep socket paused to prevent data loss
                // while we establish the backend connection.
                frontSocket.handler(null);
                frontSocket.pause();

                // Inspect first byte to determine protocol
                int backendPort;
                if (buffer.length() > 0 && buffer.getByte(0) == TLS_HANDSHAKE) {
                    backendPort = HTTPS_BACKEND_PORT;
                } else {
                    backendPort = HTTP_BACKEND_PORT;
                }

                // Connect to the appropriate backend
                client.connect(backendPort, "127.0.0.1").onComplete(ar -> {
                    if (ar.succeeded()) {
                        NetSocket backSocket = ar.result();

                        // Send the initial buffer that we already read
                        backSocket.write(buffer);

                        // Bi-directional pipe — pipeTo handles end-of-stream
                        // propagation and will resume the paused frontSocket.
                        frontSocket.pipeTo(backSocket).onFailure(err ->
                                LOG.debugv("TLS proxy: pipe front→back failed: {0}", err.getMessage()));
                        backSocket.pipeTo(frontSocket).onFailure(err ->
                                LOG.debugv("TLS proxy: pipe back→front failed: {0}", err.getMessage()));
                    } else {
                        LOG.warnv("TLS proxy: failed to connect to backend port {0}: {1}",
                                String.valueOf(backendPort), ar.cause().getMessage());
                        frontSocket.close();
                    }
                });
            });

            // Resume to receive the first buffer
            frontSocket.resume();
        });

        proxyServer.listen().onComplete(ar -> {
            if (ar.succeeded()) {
                LOG.infov("TLS proxy: listening on port {0} (HTTP→{1}, HTTPS→{2})",
                        String.valueOf(publicPort), String.valueOf(HTTP_BACKEND_PORT), String.valueOf(HTTPS_BACKEND_PORT));
            } else {
                LOG.errorv("TLS proxy: failed to start on port {0}: {1}",
                        String.valueOf(publicPort), ar.cause().getMessage());
            }
        });
    }

    @PreDestroy
    void stop() {
        if (proxyServer != null) {
            proxyServer.close();
        }
        if (client != null) {
            client.close();
        }
        LOG.info("TLS proxy: stopped");
    }
}
