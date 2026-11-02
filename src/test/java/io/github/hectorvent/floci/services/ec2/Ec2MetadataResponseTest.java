package io.github.hectorvent.floci.services.ec2;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class Ec2MetadataResponseTest {

    @Test
    void completeUserDataBodyIsReadBeforeReturning() throws Exception {
        try (BodyServer server = new BodyServer(false, false); HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<byte[]> response = Ec2MetadataTestFixture.sendResponse(client, server.request(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            assertEquals(16384, response.body().length);
            server.completed.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void stalledBodyTimesOutAndCancelsTheExchange() throws Exception {
        try (BodyServer server = new BodyServer(true, false); HttpClient client = HttpClient.newHttpClient()) {
            assertTimeoutPreemptively(Duration.ofSeconds(8), () ->
                    assertThrows(HttpTimeoutException.class, () -> Ec2MetadataTestFixture.sendResponse(
                            client, server.request(), HttpResponse.BodyHandlers.ofString())));
            server.headersSent.get(3, TimeUnit.SECONDS);
            server.completed.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void interruptedWaitCancelsTheExchange() throws Exception {
        try (BodyServer server = new BodyServer(true, false); HttpClient client = HttpClient.newHttpClient()) {
            CompletableFuture<Throwable> result = new CompletableFuture<>();
            Thread sender = Thread.ofPlatform().start(() -> {
                try {
                    Ec2MetadataTestFixture.sendResponse(client, server.request(Duration.ofSeconds(30)),
                            HttpResponse.BodyHandlers.ofString());
                    result.complete(new AssertionError("Interrupted response unexpectedly completed"));
                } catch (Exception failure) {
                    result.complete(failure);
                }
            });
            try {
                server.headersSent.get(3, TimeUnit.SECONDS);
                sender.interrupt();
                assertInstanceOf(InterruptedException.class, result.get(3, TimeUnit.SECONDS));
                server.completed.get(3, TimeUnit.SECONDS);
            } finally {
                sender.interrupt();
                sender.join(3000);
                assertFalse(sender.isAlive(), "Response sender must stop");
            }
        }
    }

    @Test
    void truncatedBodyRetainsTheTransportFailure() throws Exception {
        try (BodyServer server = new BodyServer(true, true); HttpClient client = HttpClient.newHttpClient()) {
            IOException failure = assertThrows(IOException.class, () -> Ec2MetadataTestFixture.sendResponse(
                    client, server.request(), HttpResponse.BodyHandlers.ofString()));
            assertFalse(failure instanceof HttpTimeoutException);
            server.completed.get(3, TimeUnit.SECONDS);
        }
    }

    private static final class BodyServer implements AutoCloseable {
        private final ServerSocket listener;
        private final AtomicReference<Socket> accepted = new AtomicReference<>();
        private final CompletableFuture<Void> headersSent = new CompletableFuture<>();
        private final CompletableFuture<Void> completed = new CompletableFuture<>();
        private final Thread thread;

        BodyServer(boolean partial, boolean closeEarly) throws IOException {
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            listener.setSoTimeout(5000);
            thread = Thread.ofPlatform().start(() -> serve(partial, closeEarly));
        }

        HttpRequest request() {
            return request(Duration.ofSeconds(2));
        }

        HttpRequest request(Duration timeout) {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + listener.getLocalPort() + "/latest/user-data"))
                    .timeout(timeout).GET().build();
        }

        private void serve(boolean partial, boolean closeEarly) {
            try (Socket socket = listener.accept()) {
                accepted.set(socket);
                socket.setSoTimeout(10000);
                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                String line;
                do {
                    line = input.readLine();
                } while (line != null && !line.isEmpty());
                OutputStream output = socket.getOutputStream();
                output.write("HTTP/1.1 200 OK\r\nContent-Length: 16384\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                output.write(new byte[partial ? 1 : 16384]);
                output.flush();
                headersSent.complete(null);
                if (partial && !closeEarly) {
                    try {
                        assertEquals(-1, input.read(), "Cancellation must close the client exchange");
                    } catch (SocketException expected) {
                        // Cancellation may reset the connection instead of sending EOF.
                    }
                }
                completed.complete(null);
            } catch (Throwable failure) {
                headersSent.completeExceptionally(failure);
                completed.completeExceptionally(failure);
            }
        }

        @Override
        public void close() throws Exception {
            listener.close();
            Socket socket = accepted.get();
            if (socket != null) {
                socket.close();
            }
            thread.join(3000);
            assertFalse(thread.isAlive(), "Response server must stop");
        }
    }
}
