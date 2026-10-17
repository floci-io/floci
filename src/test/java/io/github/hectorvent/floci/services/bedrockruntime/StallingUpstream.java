package io.github.hectorvent.floci.services.bedrockruntime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A raw-socket stand-in for an OpenAI-compatible backend. It accepts one request, writes a
 * fixed response prefix, then stalls, so a test can observe whether Floci closes the upstream.
 */
final class StallingUpstream implements AutoCloseable {

    private final ServerSocket server;
    private final Thread thread;
    private final CountDownLatch responded = new CountDownLatch(1);
    private final CompletableFuture<Boolean> closed = new CompletableFuture<>();

    StallingUpstream(int port, String responsePrefix) throws IOException {
        server = new ServerSocket(port);
        server.setSoTimeout(10000);
        thread = Thread.ofVirtual().start(() -> serve(responsePrefix));
    }

    private void serve(String responsePrefix) {
        try (Socket socket = server.accept()) {
            socket.setSoTimeout(10000);
            InputStream input = socket.getInputStream();
            StringBuilder headers = new StringBuilder();
            while (!headers.toString().endsWith("\r\n\r\n")) {
                int next = input.read();
                if (next < 0 || headers.length() > 65536) {
                    throw new IOException("Incomplete fixture request headers");
                }
                headers.append((char) next);
            }
            int length = 0;
            for (String header : headers.toString().split("\r\n")) {
                if (header.toLowerCase().startsWith("content-length:")) {
                    length = Integer.parseInt(header.substring(header.indexOf(':') + 1).trim());
                }
            }
            if (input.readNBytes(length).length != length) {
                throw new IOException("Incomplete fixture request body");
            }
            OutputStream output = socket.getOutputStream();
            output.write(responsePrefix.getBytes(StandardCharsets.UTF_8));
            output.flush();
            responded.countDown();
            // Nothing more is sent, so only Floci closing its side ends this read.
            try {
                closed.complete(input.read() == -1);
            } catch (SocketException disconnected) {
                closed.complete(true);
            }
        } catch (Exception failure) {
            closed.completeExceptionally(failure);
        }
    }

    boolean awaitResponded(long timeout, TimeUnit unit) throws InterruptedException {
        return responded.await(timeout, unit);
    }

    boolean awaitClosed(long timeout, TimeUnit unit) throws Exception {
        try {
            return closed.get(timeout, unit);
        } catch (TimeoutException stillOpen) {
            return false;
        }
    }

    @Override
    public void close() throws Exception {
        thread.interrupt();
        thread.join(1000);
        server.close();
    }
}
