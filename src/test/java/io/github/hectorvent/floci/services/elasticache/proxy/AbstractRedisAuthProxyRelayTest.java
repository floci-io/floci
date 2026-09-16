package io.github.hectorvent.floci.services.elasticache.proxy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AbstractRedisAuthProxyRelayTest {

    @Test
    void closingBackendReleasesClientConnection() throws Exception {
        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket freePort = new ServerSocket(0)) {
            int proxyPort = freePort.getLocalPort();
            TestProxy proxy = new TestProxy("127.0.0.1", backendServer.getLocalPort());
            freePort.close();
            proxy.start(proxyPort);

            try (Socket client = new Socket("127.0.0.1", proxyPort)) {
                client.getOutputStream().write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();

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
            TestProxy proxy = new TestProxy("127.0.0.1", backendServer.getLocalPort());
            freePort.close();
            proxy.start(proxyPort);

            try (Socket client = new Socket("127.0.0.1", proxyPort)) {
                client.setSoTimeout(2_000);
                client.getOutputStream().write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
                client.shutdownOutput();

                try (Socket backend = backendServer.accept()) {
                    backend.setSoTimeout(2_000);
                    while (backend.getInputStream().read() != -1) {
                        // Drain the request before asserting that the half-close reached the backend.
                    }
                    backend.getOutputStream().write("response".getBytes(StandardCharsets.US_ASCII));
                    backend.getOutputStream().flush();

                    byte[] response = client.getInputStream().readNBytes(8);
                    assertEquals("response", new String(response, StandardCharsets.US_ASCII));
                }
            } finally {
                proxy.stop();
            }
        }
    }

    private static final class TestProxy extends AbstractRedisAuthProxy {
        private TestProxy(String backendHost, int backendPort) {
            super(org.jboss.logging.Logger.getLogger(TestProxy.class), "test", "test", "relay",
                    backendHost, backendPort);
        }

        @Override
        protected boolean authRequired() {
            return false;
        }

        @Override
        protected boolean authenticate(String username, String password) {
            return true;
        }

        @Override
        protected void closeQuietly(Socket socket) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // The peer may already be closed.
            }
        }
    }
}
