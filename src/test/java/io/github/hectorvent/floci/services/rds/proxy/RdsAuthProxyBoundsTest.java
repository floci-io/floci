package io.github.hectorvent.floci.services.rds.proxy;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the connection bounds (handshake timeout, backend-connect deferral, permit pool) added
 * to {@link RdsAuthProxy} for the POSTGRES engine, mirroring the equivalent coverage in
 * {@code RedshiftAuthProxyTest}.
 */
class RdsAuthProxyBoundsTest {

    @TempDir
    Path tempDir;

    private RdsAuthProxy proxy;
    private ServerSocket fakeBackend;

    @AfterEach
    void tearDown() throws IOException {
        if (proxy != null) {
            proxy.stop();
        }
        if (fakeBackend != null && !fakeBackend.isClosed()) {
            fakeBackend.close();
        }
    }

    @Test
    void neverConnectsToTheBackendWhenTheClientDropsBeforeSendingAStartupPacket() throws Exception {
        // Postgres startup is client-first: the backend connection is opened only after the
        // client's startup message has been validated, so a client that vanishes beforehand
        // must never cause a backend connection.
        fakeBackend = new ServerSocket(0);
        fakeBackend.setSoTimeout(500);

        int proxyPort = freePort();
        proxy = newProxy(5000, 5000, 100);
        proxy.start(proxyPort);

        new Socket("localhost", proxyPort).close();

        assertThrows(SocketTimeoutException.class, fakeBackend::accept,
                "proxy connected to the backend before validating the client's startup message");
    }

    @Test
    void idleClientIsDroppedAfterTheConfiguredHandshakeTimeout() throws Exception {
        fakeBackend = new ServerSocket(0);
        int proxyPort = freePort();
        proxy = newProxy(200, 5000, 100);
        proxy.start(proxyPort);

        try (Socket client = new Socket("localhost", proxyPort)) {
            client.setSoTimeout(5000);
            // Send nothing; the proxy must drop the connection once the handshake timeout
            // elapses. The client observes this as EOF once the proxy closes its end.
            int result = client.getInputStream().read();
            assertEquals(-1, result, "proxy did not drop an idle client after the handshake timeout");
        }
    }

    @Test
    void refusesAConnectionBeyondTheConfiguredLimit() throws Exception {
        fakeBackend = new ServerSocket(0);
        int proxyPort = freePort();
        proxy = newProxy(5000, 5000, 1);
        proxy.start(proxyPort);

        // Hold the proxy's single connection permit from the test thread itself, so the
        // refusal below is deterministic rather than a race against a real first connection.
        Field permitsField = RdsAuthProxy.class.getDeclaredField("connectionPermits");
        permitsField.setAccessible(true);
        Semaphore permits = (Semaphore) permitsField.get(proxy);
        assertTrue(permits.tryAcquire(), "expected the single configured permit to be available");

        try (Socket client = new Socket("localhost", proxyPort)) {
            int result = client.getInputStream().read();
            assertEquals(-1, result, "connection was not refused once the connection limit was reached");
        } finally {
            permits.release();
        }
    }

    private RdsAuthProxy newProxy(int handshakeTimeoutMillis, int backendConnectTimeoutMillis,
                                  int maxConnections) {
        return new RdsAuthProxy("db-1", "localhost", fakeBackend.getLocalPort(),
                DatabaseEngine.POSTGRES, false,
                "admin", "Secret123", "dev",
                mock(RdsSigV4Validator.class), realTls(), (user, pw) -> true,
                handshakeTimeoutMillis, backendConnectTimeoutMillis, maxConnections);
    }

    private RdsProxyTlsCertificates realTls() {
        // The real bean generates a self-signed cert on demand; no Docker or network needed.
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);
        when(storage.persistentPath()).thenReturn(tempDir.toString());
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.storage()).thenReturn(storage);
        return new RdsProxyTlsCertificates(config, new CertificateGenerator());
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
