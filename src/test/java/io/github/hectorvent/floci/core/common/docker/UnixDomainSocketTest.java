package io.github.hectorvent.floci.core.common.docker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

            InputStream input = socket.getInputStream();
            CompletableFuture<String> reply = CompletableFuture.supplyAsync(() -> {
                try {
                    byte[] bytes = new byte[4];
                    int read = 0;
                    while (read < bytes.length) {
                        read += input.read(bytes, read, bytes.length - read);
                    }
                    return new String(bytes, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            assertThrows(TimeoutException.class, () -> reply.get(300, TimeUnit.MILLISECONDS),
                    "a read without a timeout should wait for the peer");

            peer.write(ByteBuffer.wrap("pong".getBytes(StandardCharsets.UTF_8)));
            assertEquals("pong", reply.get(5, TimeUnit.SECONDS));
        }
    }

    // Catches: a close from another thread surfacing as the selector's unchecked
    // ClosedSelectorException, which callers expecting an IOException do not handle.
    @Test
    void closingTheSocketEndsABlockedReadWithAnIOException() throws Exception {
        try (UnixDomainSocket socket = UnixDomainSocket.connect(socketPath.toString());
             SocketChannel ignored = server.accept()) {
            InputStream input = socket.getInputStream();
            CompletableFuture<Integer> read = CompletableFuture.supplyAsync(() -> {
                try {
                    return input.read(new byte[16]);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            assertThrows(TimeoutException.class, () -> read.get(200, TimeUnit.MILLISECONDS));

            socket.close();

            ExecutionException failure = assertThrows(ExecutionException.class, () -> read.get(5, TimeUnit.SECONDS));
            assertInstanceOf(UncheckedIOException.class, failure.getCause(), "got " + failure.getCause());
        }
    }

    // Catches: an interrupted reader with no timeout spinning on a selector that returns at once
    // while the interrupt flag is set.
    @Test
    void anInterruptedReadClosesTheSocketInsteadOfSpinning() throws Exception {
        try (UnixDomainSocket socket = UnixDomainSocket.connect(socketPath.toString());
             SocketChannel ignored = server.accept()) {
            InputStream input = socket.getInputStream();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread reader = Thread.ofPlatform().start(() -> {
                try {
                    input.read(new byte[16]);
                } catch (IOException e) {
                    failure.set(e);
                }
            });
            reader.join(200);
            assertTrue(reader.isAlive(), "the read should be waiting for the peer");

            reader.interrupt();
            reader.join(5_000);

            assertFalse(reader.isAlive(), "the interrupted read should end");
            assertInstanceOf(ClosedByInterruptException.class, failure.get());
            assertTrue(socket.isClosed());
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
