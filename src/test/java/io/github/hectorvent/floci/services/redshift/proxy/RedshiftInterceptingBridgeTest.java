package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the bridge over real loopback sockets against a scripted fake backend.
 * The decisive case is {@link #copyInterceptionDoesNotLeakBackendCopyInResponseToTheClient()}:
 * before the single-reader fix, the always-on pump forwarded the backend's
 * {@code CopyInResponse} ('G') straight to the JDBC client mid-interception.
 */
class RedshiftInterceptingBridgeTest {

    private final AutoCloseableBundle open = new AutoCloseableBundle();

    @AfterEach
    void tearDown() {
        open.closeAll();
    }

    @Test
    void plainQueryAndItsResponsePassThroughUntouched() throws Exception {
        SocketPair clientPair = SocketPair.create(open);
        SocketPair backendPair = SocketPair.create(open);
        S3Service s3 = mock(S3Service.class);

        Thread bridge = Thread.ofVirtual().start(() ->
                new RedshiftInterceptingBridge(clientPair.near, backendPair.near, s3).run());

        // Fake backend: expect the verbatim query, then answer CommandComplete + ReadyForQuery.
        AtomicReference<String> seen = new AtomicReference<>();
        Thread fakeBackend = Thread.ofVirtual().start(() -> {
            try {
                Frame q = Frame.read(backendPair.far.getInputStream());
                seen.set(new String(q.body, 0, q.body.length - 1, StandardCharsets.UTF_8));
                OutputStream out = backendPair.far.getOutputStream();
                out.write(Frame.of('C', "SELECT 1\0".getBytes(StandardCharsets.UTF_8)));
                out.write(Frame.of('Z', new byte[]{'I'}));
                out.flush();
            } catch (IOException ignored) {
            }
        });

        OutputStream toClient = clientPair.far.getOutputStream();
        toClient.write(PostgresWireDecoder.encodeQuery("SELECT 1"));
        toClient.flush();

        Frame c = Frame.read(clientPair.far.getInputStream());
        Frame z = Frame.read(clientPair.far.getInputStream());
        assertEquals('C', c.type);
        assertEquals('Z', z.type);
        fakeBackend.join(TimeUnit.SECONDS.toMillis(2));
        assertEquals("SELECT 1", seen.get());

        clientPair.far.close();
        bridge.join(TimeUnit.SECONDS.toMillis(2));
    }

    @Test
    void copyInterceptionDoesNotLeakBackendCopyInResponseToTheClient() throws Exception {
        SocketPair clientPair = SocketPair.create(open);
        SocketPair backendPair = SocketPair.create(open);

        S3Service s3 = mock(S3Service.class);
        when(s3.objectExists("bkt", "k.csv")).thenReturn(true);
        when(s3.openObjectStream(eq("bkt"), eq("k.csv"), any())).thenReturn(
                new ByteArrayInputStream("1,2\n".getBytes(StandardCharsets.UTF_8)));

        Thread bridge = Thread.ofVirtual().start(() ->
                new RedshiftInterceptingBridge(clientPair.near, backendPair.near, s3).run());

        // Fake backend: receive the fabricated COPY ... FROM STDIN, answer 'G',
        // consume the CopyData/CopyDone, then answer CommandComplete + ReadyForQuery.
        Thread fakeBackend = Thread.ofVirtual().start(() -> {
            try {
                InputStream in = backendPair.far.getInputStream();
                OutputStream out = backendPair.far.getOutputStream();
                Frame injected = Frame.read(in);
                String sql = new String(injected.body, 0, injected.body.length - 1, StandardCharsets.UTF_8);
                assertTrue(sql.contains("FROM STDIN"), "expected a synthesized STDIN copy, got: " + sql);
                out.write(Frame.of('G', new byte[]{0, 0, 0})); // CopyInResponse
                out.flush();
                Frame f;
                do {
                    f = Frame.read(in);
                } while (f.type != 'c'); // drain CopyData until CopyDone
                out.write(Frame.of('C', "COPY 1\0".getBytes(StandardCharsets.UTF_8)));
                out.write(Frame.of('Z', new byte[]{'I'}));
                out.flush();
            } catch (IOException ignored) {
            }
        });

        OutputStream toClient = clientPair.far.getOutputStream();
        toClient.write(PostgresWireDecoder.encodeQuery("COPY t FROM 's3://bkt/k.csv' CSV"));
        toClient.flush();

        Frame first = Frame.read(clientPair.far.getInputStream());
        Frame second = Frame.read(clientPair.far.getInputStream());
        // The client issued a plain 'Q' COPY: it must see CommandComplete then ReadyForQuery,
        // never the backend's 'G' (which would desync pgjdbc).
        assertEquals('C', first.type, "client must not receive CopyInResponse 'G'");
        assertEquals('Z', second.type);

        fakeBackend.join(TimeUnit.SECONDS.toMillis(2));
        clientPair.far.close();
        bridge.join(TimeUnit.SECONDS.toMillis(2));
    }

    // --- tiny wire-frame helpers -------------------------------------------------

    private record Frame(char type, byte[] body) {
        static Frame read(InputStream in) throws IOException {
            DataInputStream d = new DataInputStream(in);
            int t = d.read();
            if (t == -1) {
                throw new IOException("EOF");
            }
            int len = d.readInt();
            byte[] body = new byte[len - 4];
            d.readFully(body);
            return new Frame((char) t, body);
        }

        static byte[] of(char type, byte[] body) {
            int len = 4 + body.length;
            byte[] p = new byte[1 + len];
            p[0] = (byte) type;
            p[1] = (byte) (len >>> 24);
            p[2] = (byte) (len >>> 16);
            p[3] = (byte) (len >>> 8);
            p[4] = (byte) len;
            System.arraycopy(body, 0, p, 5, body.length);
            return p;
        }
    }

    private record SocketPair(Socket near, Socket far) {
        static SocketPair create(AutoCloseableBundle bundle) throws IOException {
            try (ServerSocket server = new ServerSocket(0)) {
                Socket far = new Socket("localhost", server.getLocalPort());
                Socket near = server.accept();
                near.setTcpNoDelay(true);
                far.setTcpNoDelay(true);
                bundle.add(near);
                bundle.add(far);
                return new SocketPair(near, far);
            }
        }
    }

    private static final class AutoCloseableBundle {
        private final java.util.List<Socket> sockets = new java.util.ArrayList<>();

        void add(Socket s) {
            sockets.add(s);
        }

        void closeAll() {
            for (Socket s : sockets) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
