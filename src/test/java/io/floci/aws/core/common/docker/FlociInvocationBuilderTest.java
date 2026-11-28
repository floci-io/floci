package io.floci.aws.core.common.docker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** How a stream from {@link FlociInvocationBuilder#get()} gives its connection back when it is closed. */
@ResourceLock(value = ParallelLocks.DOCKER_ACTIVE_STREAMS, mode = ResourceAccessMode.READ)
@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class FlociInvocationBuilderTest {

    // Catches: closing an archive stream after its first entry reading the rest of the response
    // first, which made a Lambda image's working directory check download all of /var on each
    // cold start (#9244). The server here never ends the body, so that close never returns.
    @Test
    @Timeout(60)
    void closingAStreamPartWayThroughDoesNotReadTheRestOfTheResponse(@TempDir Path dir) throws Exception {
        try (DockerStub docker = new DockerStub(dir.resolve("docker.sock"));
             FlociDockerHttpClient client = client(docker)) {
            InputStream archive = get(client, "/containers/abc/archive?path=/var");
            assertNotEquals(-1, archive.read(new byte[512]), "the first tar header should arrive");

            assertTimeoutPreemptively(Duration.ofSeconds(10), archive::close,
                    "closing the stream should not wait for the rest of the archive");
        }
    }

    // Catches: an abort on every close, which would give up a connection the pool could keep: a
    // stream read to its end, and docker-java's ping, which closes the daemon's "OK" unread.
    @Test
    @Timeout(60)
    void aStreamThatEndsSoonAfterItIsClosedKeepsItsPooledConnection(@TempDir Path dir) throws Exception {
        try (DockerStub docker = new DockerStub(dir.resolve("docker.sock"));
             FlociDockerHttpClient client = client(docker)) {
            try (InputStream small = get(client, "/containers/abc/archive?path=/srv")) {
                assertEquals(DockerStub.SMALL_ARCHIVE_BYTES, small.readAllBytes().length);
            }
            get(client, "/containers/abc/archive?path=/srv").close();
            get(client, "/_ping").close();
            get(client, "/_ping").close();

            assertEquals(1, docker.connections.get(), "four calls should share one connection");
        }
    }

    private static FlociDockerHttpClient client(DockerStub docker) {
        return new FlociDockerHttpClient.Builder()
                .dockerHost(URI.create("unix://" + docker.path))
                .responseTimeout(Duration.ofSeconds(5))
                .build();
    }

    private static InputStream get(FlociDockerHttpClient client, String path) {
        return new FlociInvocationBuilder(client, new ObjectMapper(), path).get();
    }

    /**
     * A keep-alive daemon. An archive of {@code /var} is a chunked body that never ends; an archive of
     * {@code /srv} is a short chunked body; anything else is a 200 "OK".
     */
    private static final class DockerStub implements AutoCloseable {

        static final int SMALL_ARCHIVE_BYTES = 4 * 1024;

        private final Path path;
        private final ServerSocketChannel server;
        private final AtomicInteger connections = new AtomicInteger();
        private final List<SocketChannel> channels = new CopyOnWriteArrayList<>();

        DockerStub(Path path) throws IOException {
            this.path = path;
            this.server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(path));
            Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        SocketChannel channel = server.accept();
                        connections.incrementAndGet();
                        channels.add(channel);
                        Thread.ofVirtual().start(() -> serve(channel));
                    }
                } catch (IOException expected) {
                    // The server channel closed at the end of the test.
                }
            });
        }

        private static void serve(SocketChannel channel) {
            try {
                ByteBuffer buffer = ByteBuffer.allocate(4096);
                StringBuilder pending = new StringBuilder();
                while (channel.read(buffer) != -1) {
                    buffer.flip();
                    pending.append(StandardCharsets.US_ASCII.decode(buffer));
                    buffer.clear();
                    int end;
                    while ((end = pending.indexOf("\r\n\r\n")) >= 0) {
                        String requestLine = pending.substring(0, pending.indexOf("\r\n"));
                        pending.delete(0, end + 4);
                        if (requestLine.contains("path=/var")) {
                            writeChunked(channel, Long.MAX_VALUE);
                        } else if (requestLine.contains("path=/srv")) {
                            writeChunked(channel, SMALL_ARCHIVE_BYTES);
                        } else {
                            write(channel, "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\n\r\nOK");
                        }
                    }
                }
            } catch (IOException expected) {
                // The client or the test closed the connection.
            }
        }

        private static void writeChunked(SocketChannel channel, long bytes) throws IOException {
            write(channel, "HTTP/1.1 200 OK\r\nContent-Type: application/x-tar\r\nTransfer-Encoding: chunked\r\n\r\n");
            byte[] chunk = new byte[1024];
            Arrays.fill(chunk, (byte) 'x');
            String size = Integer.toHexString(chunk.length) + "\r\n";
            for (long sent = 0; sent < bytes; sent += chunk.length) {
                write(channel, size);
                ByteBuffer data = ByteBuffer.wrap(chunk);
                while (data.hasRemaining()) {
                    channel.write(data);
                }
                write(channel, "\r\n");
            }
            write(channel, "0\r\n\r\n");
        }

        private static void write(SocketChannel channel, String text) throws IOException {
            ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII));
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (SocketChannel channel : channels) {
                channel.close();
            }
        }
    }
}
