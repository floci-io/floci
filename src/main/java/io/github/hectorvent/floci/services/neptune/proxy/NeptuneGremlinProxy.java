package io.github.hectorvent.floci.services.neptune.proxy;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Transparent TCP proxy for a single Neptune DB cluster's Gremlin endpoint.
 *
 * <p>Neptune uses WebSocket over port 8182. The client (botocore) embeds SigV4 credentials
 * in the HTTP Upgrade headers. The backend TinkerPop Gremlin Server accepts plain WebSocket
 * without authentication, so we relay all bytes transparently without inspecting the payload.
 *
 * <p>Uses Java virtual threads for non-blocking I/O.
 */
public class NeptuneGremlinProxy {

    private static final Logger LOG = Logger.getLogger(NeptuneGremlinProxy.class);

    private final String clusterId;
    private final String backendHost;
    private final int backendPort;

    private volatile boolean running;
    private ServerSocket serverSocket;

    public NeptuneGremlinProxy(String clusterId, String backendHost, int backendPort) {
        this.clusterId = clusterId;
        this.backendHost = backendHost;
        this.backendPort = backendPort;
    }

    public void start(int proxyPort) throws IOException {
        serverSocket = new ServerSocket(proxyPort);
        running = true;
        Thread.ofVirtual().name("neptune-proxy-accept-" + clusterId).start(this::acceptLoop);
        LOG.infov("Neptune Gremlin proxy started for cluster {0} on port {1} → {2}:{3}",
                clusterId, String.valueOf(proxyPort), backendHost, String.valueOf(backendPort));
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            LOG.warnv("Error closing proxy server socket for cluster {0}: {1}", clusterId, e.getMessage());
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                Thread.ofVirtual().name("neptune-proxy-conn-" + clusterId).start(() -> relay(client));
            } catch (IOException e) {
                if (running) {
                    LOG.warnv("Accept error for cluster {0}: {1}", clusterId, e.getMessage());
                }
            }
        }
    }

    private void relay(Socket client) {
        try {
            client.setTcpNoDelay(true);
            Socket backend = new Socket(backendHost, backendPort);
            backend.setTcpNoDelay(true);
            bridge(client, backend);
        } catch (IOException e) {
            LOG.debugv("Failed to connect to Gremlin backend for cluster {0}: {1}",
                    clusterId, e.getMessage());
            closeQuietly(client);
        }
    }

    /**
     * Bidirectional byte relay. Relay threads are platform daemon threads; virtual threads
     * for I/O-bound work can stall WebSocket frame delivery under high concurrency.
     */
    private void bridge(Socket client, Socket backend) {
        Thread t1 = Thread.ofPlatform().daemon(true).name("neptune-relay-c2b-" + clusterId)
                .start(() -> pipe(client, backend));
        Thread t2 = Thread.ofPlatform().daemon(true).name("neptune-relay-b2c-" + clusterId)
                .start(() -> pipe(backend, client));
        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeQuietly(client);
            closeQuietly(backend);
        }
    }

    private static void pipe(Socket from, Socket to) {
        byte[] buf = new byte[8192];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
            // Normal when either side closes the connection
        }
    }

    private static void closeQuietly(Socket s) {
        try { s.close(); } catch (IOException ignored) {}
    }
}
