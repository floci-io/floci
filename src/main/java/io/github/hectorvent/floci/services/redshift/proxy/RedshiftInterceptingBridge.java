package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.services.s3.S3Service;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Replaces the transparent {@code bridge()} for Redshift connections so Simple
 * Query ({@code 'Q'}) traffic can be inspected before it reaches the backing
 * PostgreSQL container.
 *
 * <p><b>Model.</b> Backend&rarr;client is a verbatim byte pump on a virtual
 * thread, exactly as a plain proxy — extended-protocol pipelines, a real
 * {@code COPY … FROM STDIN}, asynchronous {@code NotificationResponse}, and
 * backend EOF all flow through untouched. Client&rarr;backend is a framed loop:
 * every frontend message is forwarded opaque <em>except</em> a {@code 'Q'},
 * which is parsed — an S3 {@code COPY}/{@code UNLOAD} is executed by
 * {@link S3CopySimulator}, anything else is run through
 * {@link RedshiftSqlInterceptor#rewrite} and forwarded.
 *
 * <p><b>Backend ownership.</b> {@link S3CopySimulator} drives a fabricated
 * request/response exchange and reads the backend socket directly, so it must
 * not run while the pump is also reading. Both guard every backend read with
 * {@link #backendLock}: the pump takes and releases it once per {@code read()}
 * (bounded by {@link #PUMP_READ_TIMEOUT_MS}), and the client loop takes it for
 * the whole COPY/UNLOAD exchange. The client loop only proceeds once
 * {@link #pumpBetweenMessages} shows the pump last stopped on a PostgreSQL
 * message boundary, so the simulator's decoder never starts mid-frame — even if
 * a client pipelines a {@code 'Q'} behind an unfinished response. If a boundary
 * is not reached within {@link #PUMP_PARK_WAIT_MS}, the COPY/UNLOAD is
 * <em>not</em> intercepted: the original {@code 'Q'} is forwarded and PostgreSQL
 * returns its own error rather than the session hanging.
 */
public class RedshiftInterceptingBridge {

    private static final Logger LOG = Logger.getLogger(RedshiftInterceptingBridge.class);

    /** How long a pump {@code read()} blocks (holding {@link #backendLock}) before looping. */
    private static final int PUMP_READ_TIMEOUT_MS = 200;
    /** Upper bound on how long the client loop waits to take the backend stream at a boundary. */
    private static final long PUMP_PARK_WAIT_MS = 2_000L;

    private final Socket client;
    private final Socket backend;
    private final S3Service s3Service;

    /** Held around every backend read, by the pump per-iteration and by the client loop per exchange. */
    private final ReentrantLock backendLock = new ReentrantLock(true);
    /** True when the pump's last read left the stream on a wire-message boundary. */
    private volatile boolean pumpBetweenMessages = true;
    private volatile boolean pumpFinished = false;

    public RedshiftInterceptingBridge(Socket client, Socket backend, S3Service s3Service) {
        this.client = client;
        this.backend = backend;
        this.s3Service = s3Service;
    }

    public void run() {
        try {
            // Set the pump's read timeout BEFORE the pump can enter read() — a setSoTimeout
            // that races an in-flight blocking read does not take effect.
            backend.setSoTimeout(PUMP_READ_TIMEOUT_MS);
            Thread.ofVirtual().name("redshift-pump-backend-to-client").start(this::pumpBackendToClient);

            InputStream clientIn = client.getInputStream();
            OutputStream backendOut = backend.getOutputStream();
            PostgresWireDecoder decoder = new PostgresWireDecoder(clientIn);

            PostgresWireDecoder.FrontendMessage msg;
            while ((msg = decoder.nextMessage()) != null) {
                if (!msg.isQuery()) {
                    backendOut.write(msg.toPacketBytes());
                    backendOut.flush();
                    if (msg.type() == 'X') { // Terminate
                        break;
                    }
                    continue;
                }

                String sql = msg.getSql();
                CopyStatementParser.S3Statement stmt = null;
                boolean handledByParser = true;
                try {
                    stmt = CopyStatementParser.parse(sql);
                } catch (RuntimeException e) {
                    // Fail-open: a parser hiccup must never swallow the user's statement.
                    LOG.warnv("CopyStatementParser failed, forwarding original query: {0}", e.getMessage());
                    handledByParser = false;
                }

                boolean intercepted = false;
                if (handledByParser && stmt instanceof CopyStatementParser.S3CopyFrom copyFrom) {
                    intercepted = runWithBackendOwned(() -> S3CopySimulator.runCopyFrom(client, backend, copyFrom, s3Service));
                } else if (handledByParser && stmt instanceof CopyStatementParser.S3Unload unload) {
                    intercepted = runWithBackendOwned(() -> S3CopySimulator.runUnload(client, backend, unload, s3Service));
                }

                if (intercepted) {
                    continue;
                }

                byte[] toBackend = msg.toPacketBytes();
                if (handledByParser && stmt == null) {
                    try {
                        String rewritten = RedshiftSqlInterceptor.rewrite(sql);
                        if (rewritten != sql) {
                            toBackend = PostgresWireDecoder.encodeQuery(rewritten);
                        }
                    } catch (RuntimeException e) {
                        // Fail-open: forward the untouched statement to PostgreSQL.
                        LOG.warnv("RedshiftSqlInterceptor failed, forwarding original query: {0}", e.getMessage());
                        toBackend = msg.toPacketBytes();
                    }
                }
                backendOut.write(toBackend);
                backendOut.flush();
                // Response flows back over the untouched pump.
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

    @FunctionalInterface
    private interface CopyExchange {
        void run() throws IOException;
    }

    /**
     * Take exclusive ownership of the backend stream at a wire-message boundary, run
     * {@code exchange}, then release it.
     *
     * @return {@code true} if ownership was taken and {@code exchange} ran; {@code false} if the
     *         pump could not be caught at a boundary in time — the caller must then forward the
     *         original {@code 'Q'} unmodified (fail-open).
     */
    private boolean runWithBackendOwned(CopyExchange exchange) throws IOException {
        long deadlineNanos = System.nanoTime() + PUMP_PARK_WAIT_MS * 1_000_000L;
        while (true) {
            if (pumpFinished) {
                return false; // pump (and almost certainly the backend) is gone
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
                    LOG.warn("could not take the backend stream in time; forwarding the COPY/UNLOAD unintercepted");
                    return false;
                }
                continue;
            }
            try {
                if (!pumpBetweenMessages && !pumpFinished) {
                    // The pump stopped mid-message; release so it can finish, then retry.
                    if (System.nanoTime() >= deadlineNanos) {
                        LOG.warn("backend never reached a message boundary in time; forwarding the COPY/UNLOAD unintercepted");
                        return false;
                    }
                    continue;
                }
                backend.setSoTimeout(0); // the simulator wants blocking reads
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
        WireFrameTracker tracker = new WireFrameTracker();
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
                        continue; // release the lock (finally), let a waiting exchange in, retry
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

    private void closeQuietly(Socket s, String which) {
        try {
            if (s != null && !s.isClosed()) {
                s.close();
            }
        } catch (IOException e) {
            LOG.debugv(e, "error closing {0} socket", which);
        }
    }

    /**
     * Tracks PostgreSQL wire-message boundaries in a byte stream without buffering it.
     * Every backend message is {@code type(1) · length(int32, includes itself) · body}.
     * {@link #betweenMessages()} is true exactly when the next byte would begin a new message.
     */
    private static final class WireFrameTracker {
        private int headerBytesSeen = 0;
        private final byte[] header = new byte[5];
        private long bodyRemaining = 0;

        boolean betweenMessages() {
            return headerBytesSeen == 0 && bodyRemaining == 0;
        }

        void consume(byte[] buf, int off, int len) {
            for (int i = off; i < off + len; i++) {
                if (bodyRemaining > 0) {
                    bodyRemaining--;
                    continue;
                }
                header[headerBytesSeen++] = buf[i];
                if (headerBytesSeen == 5) {
                    long msgLen = ((header[1] & 0xFFL) << 24) | ((header[2] & 0xFFL) << 16)
                            | ((header[3] & 0xFFL) << 8) | (header[4] & 0xFFL);
                    bodyRemaining = Math.max(0, msgLen - 4);
                    headerBytesSeen = 0;
                }
            }
        }
    }
}
