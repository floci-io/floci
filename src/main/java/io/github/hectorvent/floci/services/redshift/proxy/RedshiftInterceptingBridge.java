package io.github.hectorvent.floci.services.redshift.proxy;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Replaces the transparent {@code bridge()} for Redshift connections so Simple Query ({@code 'Q'})
 * traffic can be inspected before it reaches the backing PostgreSQL container.
 *
 * <p><b>Model.</b> Backend&rarr;client is a byte pump on a virtual thread. Client&rarr;backend is a
 * framed loop: every frontend message is forwarded opaque except a {@code 'Q'}, whose SQL is run
 * through {@link RedshiftSqlInterceptor#rewrite} and re-encoded only if it changed.
 *
 * <p><b>Backend ownership.</b> A future interceptor that injects its own query and reads the reply
 * (the S3 COPY simulator) must own the backend exclusively and only start when no earlier request
 * is still being answered. {@link #backendLock} serialises pump reads against such an exchange, and
 * {@link #outstandingResponses} plus {@link #pumpBetweenMessages} gate it to a safe boundary. If the
 * backend cannot be caught idle within {@link #PUMP_PARK_WAIT_MS}, the exchange is skipped and the
 * original {@code 'Q'} is forwarded (fail-open).
 */
public class RedshiftInterceptingBridge {

    private static final Logger LOG = Logger.getLogger(RedshiftInterceptingBridge.class);

    private static final int PUMP_READ_TIMEOUT_MS = 200;
    private static final int CLIENT_READ_TIMEOUT_MS = 10_000;
    private static final long PUMP_PARK_WAIT_MS = 2_000L;

    private final Socket client;
    private final Socket backend;

    private final ReentrantLock backendLock = new ReentrantLock(true);
    private volatile boolean pumpBetweenMessages = true;
    private final AtomicInteger outstandingResponses = new AtomicInteger(0);
    private volatile boolean pumpFinished = false;

    public RedshiftInterceptingBridge(Socket client, Socket backend) {
        this.client = client;
        this.backend = backend;
    }

    @FunctionalInterface
    interface BackendExchange {
        void run() throws IOException;
    }

    public void run() {
        try {
            backend.setSoTimeout(PUMP_READ_TIMEOUT_MS);
            client.setSoTimeout(CLIENT_READ_TIMEOUT_MS);
            Thread.ofVirtual().name("redshift-pump-backend-to-client").start(this::pumpBackendToClient);

            InputStream clientIn = client.getInputStream();
            OutputStream backendOut = backend.getOutputStream();
            PostgresWireDecoder decoder = new PostgresWireDecoder(clientIn);

            while (true) {
                PostgresWireDecoder.FrontendMessage msg;
                try {
                    msg = decoder.nextMessage();
                } catch (SocketTimeoutException e) {
                    if (decoder.isBetweenMessages()) {
                        continue;
                    }
                    LOG.warnv("Client socket timed out mid-message: {0}", e.getMessage());
                    break;
                }
                if (msg == null) {
                    break;
                }

                if (!msg.isQuery()) {
                    if (msg.type() == 'S') {
                        outstandingResponses.incrementAndGet();
                    }
                    backendOut.write(msg.toPacketBytes());
                    backendOut.flush();
                    if (msg.type() == 'X') {
                        break;
                    }
                    continue;
                }

                String sql = msg.getSql();
                byte[] toBackend = msg.toPacketBytes();
                try {
                    String rewritten = RedshiftSqlInterceptor.rewrite(sql);
                    if (rewritten != sql) {
                        toBackend = PostgresWireDecoder.encodeQuery(rewritten);
                    }
                } catch (RuntimeException e) {
                    LOG.warnv("RedshiftSqlInterceptor failed, forwarding original query: {0}", e.getMessage());
                    toBackend = msg.toPacketBytes();
                }
                outstandingResponses.incrementAndGet();
                backendOut.write(toBackend);
                backendOut.flush();
            }
        } catch (IOException e) {
            LOG.debugv(e, "RedshiftInterceptingBridge client loop ended");
        } catch (Exception e) {
            LOG.warnv(e, "Unexpected error in RedshiftInterceptingBridge");
        } finally {
            closeQuietly(client, "client");
            closeQuietly(backend, "backend");
        }
    }

    /**
     * Take exclusive ownership of an idle backend at a wire-message boundary, run {@code exchange},
     * then release it. Returns {@code false} if the backend could not be caught idle within
     * {@link #PUMP_PARK_WAIT_MS}; the caller must then forward the original {@code 'Q'} unmodified.
     */
    boolean runWithBackendOwned(BackendExchange exchange) throws IOException {
        long deadlineNanos = System.nanoTime() + PUMP_PARK_WAIT_MS * 1_000_000L;
        while (true) {
            if (pumpFinished) {
                return false;
            }
            boolean locked;
            try {
                locked = backendLock.tryLock(PUMP_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while acquiring the backend lock", e);
            }
            if (!locked) {
                if (System.nanoTime() >= deadlineNanos) {
                    LOG.warn("could not take the backend stream in time; forwarding the COPY unintercepted");
                    return false;
                }
                continue;
            }
            try {
                boolean backendBusy = !pumpBetweenMessages || outstandingResponses.get() > 0;
                if (backendBusy && !pumpFinished) {
                    if (System.nanoTime() >= deadlineNanos) {
                        LOG.warn("backend did not go idle in time; forwarding the COPY unintercepted");
                        return false;
                    }
                    continue;
                }
                backend.setSoTimeout(0);
                try {
                    exchange.run();
                    return true;
                } finally {
                    try {
                        backend.setSoTimeout(PUMP_READ_TIMEOUT_MS);
                    } catch (IOException e) {
                        LOG.debugv(e, "could not restore backend read timeout (socket closing)");
                    }
                }
            } finally {
                backendLock.unlock();
            }
        }
    }

    private void pumpBackendToClient() {
        WireFrameTracker tracker = new WireFrameTracker(
                () -> outstandingResponses.updateAndGet(v -> Math.max(0, v - 1)));
        try {
            InputStream backendIn = backend.getInputStream();
            OutputStream clientOut = client.getOutputStream();
            byte[] buffer = new byte[8192];
            while (true) {
                backendLock.lockInterruptibly();
                try {
                    int read;
                    try {
                        read = backendIn.read(buffer);
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    if (read == -1) {
                        break;
                    }
                    clientOut.write(buffer, 0, read);
                    clientOut.flush();
                    tracker.consume(buffer, 0, read);
                    pumpBetweenMessages = tracker.betweenMessages();
                } finally {
                    backendLock.unlock();
                }
            }
        } catch (IOException e) {
            LOG.debugv(e, "backend->client pump ended");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pumpFinished = true;
            closeQuietly(client, "client");
            closeQuietly(backend, "backend");
        }
    }

    private void closeQuietly(Socket socket, String which) {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            LOG.debugv(e, "error closing {0} socket", which);
        }
    }

    /**
     * Tracks PostgreSQL wire-message boundaries in a byte stream without buffering it. Each backend
     * message is {@code type(1) . length(int32, includes itself) . body}. {@link #betweenMessages()}
     * is true exactly when the next byte would begin a new message; {@code onReadyForQuery} fires
     * once per completed {@code 'Z'}.
     */
    static final class WireFrameTracker {
        private final Runnable onReadyForQuery;
        private int headerBytesSeen = 0;
        private final byte[] header = new byte[5];
        private long bodyRemaining = 0;
        private char pendingType = 0;

        WireFrameTracker(Runnable onReadyForQuery) {
            this.onReadyForQuery = onReadyForQuery;
        }

        boolean betweenMessages() {
            return headerBytesSeen == 0 && bodyRemaining == 0;
        }

        void consume(byte[] buf, int off, int len) {
            for (int i = off; i < off + len; i++) {
                if (bodyRemaining > 0) {
                    bodyRemaining--;
                    if (bodyRemaining == 0 && pendingType == 'Z') {
                        onReadyForQuery.run();
                    }
                    continue;
                }
                header[headerBytesSeen++] = buf[i];
                if (headerBytesSeen == 5) {
                    long msgLen = ((header[1] & 0xFFL) << 24) | ((header[2] & 0xFFL) << 16)
                            | ((header[3] & 0xFFL) << 8) | (header[4] & 0xFFL);
                    pendingType = (char) (header[0] & 0xFF);
                    bodyRemaining = Math.max(0, msgLen - 4);
                    headerBytesSeen = 0;
                    if (bodyRemaining == 0 && pendingType == 'Z') {
                        onReadyForQuery.run();
                    }
                }
            }
        }
    }
}
