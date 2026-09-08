package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.services.s3.S3Service;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntConsumer;

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
    /** Bounded read timeout while an exchange owns the backend, so a stalled backend cannot hang the session. */
    private static final int EXCHANGE_READ_TIMEOUT_MS = 60_000;
    private static final long BUSY_BACKOFF_NANOS = 2_000_000L;

    private final Socket client;
    private final Socket backend;
    private final S3Service s3Service;

    private final ReentrantLock backendLock = new ReentrantLock(true);
    private volatile boolean pumpBetweenMessages = true;
    private final AtomicInteger outstandingResponses = new AtomicInteger(0);
    private volatile boolean pumpFinished = false;
    /** Transaction-status byte from the backend's most recent {@code ReadyForQuery}; {@code 'I'} until one is seen. */
    private volatile char lastBackendStatus = 'I';

    public RedshiftInterceptingBridge(Socket client, Socket backend, S3Service s3Service) {
        this.client = client;
        this.backend = backend;
        this.s3Service = s3Service;
    }

    @FunctionalInterface
    interface BackendExchange {
        /** @return {@code true} if the exchange handled the query; {@code false} to forward the original. */
        boolean run() throws IOException;
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

                CopyStatementParser.S3Statement parsed = null;
                try {
                    parsed = CopyStatementParser.parse(sql);
                } catch (RuntimeException e) {
                    LOG.warnv("CopyStatementParser failed, forwarding original query: {0}", e.getMessage());
                }

                if (parsed != null) {
                    CopyStatementParser.S3Statement statement = parsed;
                    boolean intercepted = runWithBackendOwned(() -> switch (statement) {
                        case CopyStatementParser.S3CopyFrom c ->
                                S3CopySimulator.runCopyFrom(client, backend, c, s3Service, lastBackendStatus,
                                        status -> lastBackendStatus = (char) status);
                        case CopyStatementParser.S3Unload u ->
                                S3CopySimulator.runUnload(client, backend, u, s3Service, lastBackendStatus,
                                        status -> lastBackendStatus = (char) status);
                    });
                    if (intercepted) {
                        continue;
                    }
                }

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
            boolean backendBusy;
            try {
                backendBusy = !pumpBetweenMessages || outstandingResponses.get() > 0;
                if (!backendBusy || pumpFinished) {
                    backend.setSoTimeout(EXCHANGE_READ_TIMEOUT_MS);
                    try {
                        return exchange.run();
                    } finally {
                        try {
                            backend.setSoTimeout(PUMP_READ_TIMEOUT_MS);
                        } catch (IOException e) {
                            LOG.debugv(e, "could not restore backend read timeout (socket closing)");
                        }
                    }
                }
            } finally {
                backendLock.unlock();
            }
            if (System.nanoTime() >= deadlineNanos) {
                LOG.warn("backend did not go idle in time; forwarding the COPY unintercepted");
                return false;
            }
            LockSupport.parkNanos(BUSY_BACKOFF_NANOS);
        }
    }

    private void pumpBackendToClient() {
        WireFrameTracker tracker = new WireFrameTracker(status -> {
            lastBackendStatus = (char) status;
            outstandingResponses.updateAndGet(v -> Math.max(0, v - 1));
        });
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
     * once per completed {@code 'Z'} with its one-byte transaction-status payload.
     */
    static final class WireFrameTracker {
        private final IntConsumer onReadyForQuery;
        private int headerBytesSeen = 0;
        private final byte[] header = new byte[5];
        private long bodyRemaining = 0;
        private char pendingType = 0;

        WireFrameTracker(IntConsumer onReadyForQuery) {
            this.onReadyForQuery = onReadyForQuery;
        }

        boolean betweenMessages() {
            return headerBytesSeen == 0 && bodyRemaining == 0;
        }

        void consume(byte[] buf, int off, int len) {
            for (int i = off; i < off + len; i++) {
                if (bodyRemaining > 0) {
                    int current = buf[i] & 0xFF;
                    bodyRemaining--;
                    if (bodyRemaining == 0 && pendingType == 'Z') {
                        onReadyForQuery.accept(current);
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
                        onReadyForQuery.accept('I');
                    }
                }
            }
        }
    }
}
