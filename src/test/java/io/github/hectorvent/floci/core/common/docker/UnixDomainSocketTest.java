package io.github.hectorvent.floci.core.common.docker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnixDomainSocketTest {

    @TempDir
    Path dir;

    private ServerSocketChannel server;
    private Path socketPath;

    @BeforeEach
    void startServer() throws IOException {
        socketPath = dir.resolve("docker.sock");
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socketPath));
    }

    @AfterEach
    void stopServer() throws IOException {
        server.close();
    }

    // Catches: a read that ignores SO_TIMEOUT and blocks forever on a silent peer, as docker-java's
    // UnixSocket does.
    @Test
    void aReadWithATimeoutFailsWhenThePeerStaysSilent() throws Exception {
        try (UnixDomainSocket socket = UnixDomainSocket.connect(socketPath.toString());
             SocketChannel ignored = server.accept()) {
            socket.setSoTimeout(200);
            long start = System.nanoTime();

            assertThrows(SocketTimeoutException.class, () -> socket.getInputStream().read(new byte[16]));

            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsedMillis >= 150 && elapsedMillis < 5_000, "timed out after " + elapsedMillis + " ms");
        }
    }

    @Test
    void aReadWithoutATimeoutWaitsForDataAndWritesReachThePeer() throws Exception {
        try (UnixDomainSocket socket = UnixDomainSocket.connect(socketPath.toString());
             SocketChannel peer = server.accept()) {
            socket.getOutputStream().write("ping".getBytes(StandardCharsets.UTF_8));
            ByteBuffer received = ByteBuffer.allocate(4);
            while (received.hasRemaining()) {
                peer.read(received);
            }
            assertEquals("ping", new String(received.array(), StandardCharsets.UTF_8));

            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(300);
                    peer.write(ByteBuffer.wrap("pong".getBytes(StandardCharsets.UTF_8)));
                } catch (IOException | InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            });
            InputStream input = socket.getInputStream();
            byte[] reply = new byte[4];
            int read = 0;
            while (read < reply.length) {
                read += input.read(reply, read, reply.length - read);
            }
            assertEquals("pong", new String(reply, StandardCharsets.UTF_8));
        }
    }

    @Test
    void readingAfterThePeerClosesReturnsEndOfStream() throws Exception {
        try (UnixDomainSocket socket = UnixDomainSocket.connect(socketPath.toString())) {
            server.accept().close();
            socket.setSoTimeout(2_000);

            assertEquals(-1, socket.getInputStream().read());
        }
    }
}
