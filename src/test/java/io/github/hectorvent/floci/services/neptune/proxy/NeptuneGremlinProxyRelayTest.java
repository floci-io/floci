package io.github.hectorvent.floci.services.neptune.proxy;

import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NeptuneGremlinProxyRelayTest {

    @Test
    void closingBackendReleasesClientConnection() throws Exception {
        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket freePort = new ServerSocket(0)) {
            int proxyPort = freePort.getLocalPort();
            NeptuneGremlinProxy proxy = new NeptuneGremlinProxy(
                    "cluster", "127.0.0.1", backendServer.getLocalPort());
            freePort.close();
            proxy.start(proxyPort);

            try (Socket client = new Socket("127.0.0.1", proxyPort)) {
                try (Socket backend = backendServer.accept()) {
                    backend.close();
                }

                client.setSoTimeout(2_000);
                assertEquals(-1, client.getInputStream().read());
            } finally {
                proxy.stop();
            }
        }
    }

    @Test
    void clientHalfCloseStillAllowsBackendResponse() throws Exception {
        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket freePort = new ServerSocket(0)) {
            int proxyPort = freePort.getLocalPort();
            NeptuneGremlinProxy proxy = new NeptuneGremlinProxy(
                    "cluster", "127.0.0.1", backendServer.getLocalPort());
            freePort.close();
            proxy.start(proxyPort);

            try (Socket client = new Socket("127.0.0.1", proxyPort);
                 Socket backend = backendServer.accept()) {
                client.setSoTimeout(2_000);
                backend.setSoTimeout(2_000);
                client.getOutputStream().write("request".getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
                client.shutdownOutput();

                while (backend.getInputStream().read() != -1) {
                    // Drain the request before asserting that the half-close reached the backend.
                }
                backend.getOutputStream().write("response".getBytes(StandardCharsets.US_ASCII));
                backend.getOutputStream().flush();

                byte[] response = client.getInputStream().readNBytes(8);
                assertEquals("response", new String(response, StandardCharsets.US_ASCII));
            } finally {
                proxy.stop();
            }
        }
    }

    @Test
    void clientHalfCloseClosesRelayWhenBackendStaysOpenAfterResponse() throws Exception {
        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket freePort = new ServerSocket(0)) {
            int proxyPort = freePort.getLocalPort();
            NeptuneGremlinProxy proxy = new NeptuneGremlinProxy(
                    "cluster", "127.0.0.1", backendServer.getLocalPort());
            freePort.close();
            proxy.start(proxyPort);

            try (Socket client = new Socket("127.0.0.1", proxyPort);
                 Socket backend = backendServer.accept()) {
                client.setSoTimeout(3_000);
                client.shutdownOutput();
                while (backend.getInputStream().read() != -1) {
                    // Drain until the client half-close reaches the backend.
                }
                backend.getOutputStream().write("response".getBytes(StandardCharsets.US_ASCII));
                backend.getOutputStream().flush();

                assertEquals("response", new String(client.getInputStream().readNBytes(8),
                        StandardCharsets.US_ASCII));
                assertEquals(-1, client.getInputStream().read());
            } finally {
                proxy.stop();
            }
        }
    }
}
