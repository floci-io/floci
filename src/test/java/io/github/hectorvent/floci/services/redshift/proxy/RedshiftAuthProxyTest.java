package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.rds.proxy.RdsProxyTlsCertificates;
import io.github.hectorvent.floci.services.rds.proxy.RdsSigV4Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedshiftAuthProxyTest {

    @TempDir
    Path tempDir;

    private RedshiftAuthProxy proxy;
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
    void bridgesClientBytesToTheBackendAfterASuccessfulPasswordAuth() throws Exception {
        // The fake backend accepts one connection and records the first bytes it receives
        // from the proxy (the forwarded PostgreSQL startup packet).
        fakeBackend = new ServerSocket(0);
        AtomicReference<byte[]> seenByBackend = new AtomicReference<>();
        CountDownLatch backendDone = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            try (Socket s = fakeBackend.accept()) {
                InputStream in = s.getInputStream();
                byte[] buf = new byte[256];
                int n = in.read(buf);
                byte[] out = new byte[Math.max(n, 0)];
                System.arraycopy(buf, 0, out, 0, out.length);
                seenByBackend.set(out);
                backendDone.countDown();
            } catch (IOException ignored) {
                backendDone.countDown();
            }
        });

        int proxyPort = freePort();
        proxy = new RedshiftAuthProxy("111111111111:c1", "localhost", fakeBackend.getLocalPort(),
                "admin", "Secret123", "dev",
                mock(RdsSigV4Validator.class), realTls(), (user, pw) -> true);
        proxy.start(proxyPort);

        try (Socket client = new Socket("localhost", proxyPort)) {
            // Minimal PostgreSQL v3 startup: length(4) + protocol(4) + "user\0admin\0\0"
            OutputStream out = client.getOutputStream();
            byte[] params = "user\0admin\0\0".getBytes(StandardCharsets.UTF_8);
            int len = 8 + params.length;
            out.write(new byte[]{(byte) (len >>> 24), (byte) (len >>> 16), (byte) (len >>> 8), (byte) len});
            out.write(new byte[]{0, 3, 0, 0}); // protocol 196608
            out.write(params);
            out.flush();

            // Read the AuthenticationCleartextPassword request ('R') and reply with a password message.
            InputStream in = client.getInputStream();
            assertEquals('R', in.read());
            in.readNBytes(7); // remaining length(4) + auth code(3 of the 4-byte int)

            OutputStream pw = client.getOutputStream();
            byte[] pwBytes = "Secret123\0".getBytes(StandardCharsets.UTF_8);
            pw.write('p');
            int pwLen = 4 + pwBytes.length;
            pw.write(new byte[]{(byte) (pwLen >>> 24), (byte) (pwLen >>> 16), (byte) (pwLen >>> 8), (byte) pwLen});
            pw.write(pwBytes);
            pw.flush();

            assertTrue(backendDone.await(5, TimeUnit.SECONDS), "backend never saw the forwarded startup packet");
            byte[] forwarded = seenByBackend.get();
            assertTrue(forwarded != null && forwarded.length > 0, "proxy forwarded no bytes to the backend");
        }
    }

    @Test
    void closesTheBackendConnectionWhenTheClientDropsMidHandshake() throws Exception {
        fakeBackend = new ServerSocket(0);
        CountDownLatch backendClosed = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            try (Socket s = fakeBackend.accept()) {
                // The proxy opens this before reading the client's startup packet; if the
                // client vanishes, the proxy must close this too — surfaced here as EOF.
                InputStream in = s.getInputStream();
                while (in.read() != -1) {
                    // drain until the proxy closes its end
                }
                backendClosed.countDown();
            } catch (IOException e) {
                backendClosed.countDown();
            }
        });

        int proxyPort = freePort();
        proxy = new RedshiftAuthProxy("111111111111:c1", "localhost", fakeBackend.getLocalPort(),
                "admin", "Secret123", "dev",
                mock(RdsSigV4Validator.class), realTls(), (user, pw) -> true);
        proxy.start(proxyPort);

        // Connect, then drop without ever sending a startup packet.
        new Socket("localhost", proxyPort).close();

        assertTrue(backendClosed.await(5, TimeUnit.SECONDS),
                "proxy leaked the backend connection after the client dropped");
    }

    @Test
    void startRetriesTheBindWhileThePortIsMomentarilyStillInUse() throws Exception {
        fakeBackend = new ServerSocket(0);
        int proxyPort = freePort();

        // Hold the port, mimicking a just-closed predecessor the kernel has not released
        // yet; free it shortly after so the retrying bind can finally take it.
        ServerSocket squatter = new ServerSocket();
        squatter.setReuseAddress(true);
        squatter.bind(new java.net.InetSocketAddress(proxyPort));
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(200);
                squatter.close();
            } catch (Exception ignored) {
            }
        });

        proxy = new RedshiftAuthProxy("111111111111:c1", "localhost", fakeBackend.getLocalPort(),
                "admin", "Secret123", "dev",
                mock(RdsSigV4Validator.class), realTls(), (user, pw) -> true);
        proxy.start(proxyPort); // must not throw despite the port being busy at first

        assertTrue(portAccepts(proxyPort), "proxy never bound the port after the squatter released it");
    }

    @Test
    void updateMasterPasswordSwapsTheSnapshotUsedForNewConnections() throws Exception {
        fakeBackend = new ServerSocket(0);
        int proxyPort = freePort();
        proxy = new RedshiftAuthProxy("111111111111:c1", "localhost", fakeBackend.getLocalPort(),
                "admin", "old", "dev",
                mock(RdsSigV4Validator.class), realTls(), (user, pw) -> true);
        proxy.start(proxyPort);

        proxy.updateMasterPassword("rotated");

        Field f = RedshiftAuthProxy.class.getDeclaredField("masterPassword");
        f.setAccessible(true);
        assertEquals("rotated", f.get(proxy));
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

    private static boolean portAccepts(int port) {
        for (int attempt = 0; attempt < 50; attempt++) {
            try (Socket ignored = new Socket("localhost", port)) {
                return true;
            } catch (IOException e) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }
}
