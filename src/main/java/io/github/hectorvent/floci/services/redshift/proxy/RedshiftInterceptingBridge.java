package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.services.s3.S3Service;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

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
 * <p><b>Pump suspension.</b> {@link S3CopySimulator} drives a fabricated
 * request/response exchange <em>and reads the backend socket directly</em>. That
 * would race the pump, which is also reading the backend. Before calling the
 * simulator the loop parks the pump and takes exclusive ownership of the backend
 * stream, then resumes it. The pump only ever parks <em>between</em> PostgreSQL
 * backend messages — a lightweight frame tracker over the bytes it is already
 * copying tells it where the boundaries are — so the simulator's own decoder
 * always starts on a clean boundary, even if a client pipelines a {@code 'Q'}
 * behind an unfinished response. If the pump cannot reach a boundary within
 * {@link #PUMP_PARK_WAIT_MS} (sustained streaming, or a pipelined COPY behind a
 * long query), the COPY/UNLOAD is <em>not</em> intercepted — the original
 * {@code 'Q'} is forwarded and PostgreSQL returns its own error, rather than the
 * session hanging.
 */
public class RedshiftInterceptingBridge {

    private static final Logger LOG = Logger.getLogger(RedshiftInterceptingBridge.class);

    /** How long a pump {@code read()} blocks before looping to check for a park request. */
    private static final int PUMP_READ_TIMEOUT_MS = 200;
    /** Upper bound on how long the client loop waits for the pump to reach a message boundary. */
    private static final long PUMP_PARK_WAIT_MS = 2_000L;

    private final Socket client;
    private final Socket backend;
    private final S3Service s3Service;

    private volatile boolean pausePump = false;
    private volatile boolean pumpFinished = false;
    private final Semaphore pumpParkedAck = new Semaphore(0);
    private final Semaphore pumpResume = new Semaphore(0);

    public RedshiftInterceptingBridge(Socket client, Socket backend, S3Service s3Service) {
        this.client = client;
        this.backend = backend;
        this.s3Service = s3Service;
    }

    public void run() {
        try {
            // Set the pump's read timeout BEFORE the pump can enter read() — a setSoTimeout
            // that races an in-flight blocking read does not take effect, and if the client's
            // first message is an S3 COPY/UNLOAD the pump would never wake to park itself.
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
                    intercepted = runWithPumpPaused(() -> S3CopySimulator.runCopyFrom(client, backend, copyFrom, s3Service));
                } else if (handledByParser && stmt instanceof CopyStatementParser.S3Unload unload) {
                    intercepted = runWithPumpPaused(() -> S3CopySimulator.runUnload(client, backend, unload, s3Service));
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
            // Wake the pump if it is parked waiting to resume.
            pausePump = false;
            pumpResume.release();
        }
    }

    @FunctionalInterface
    private interface CopyExchange {
        void run() throws IOException;
    }

    /**
     * Park the backend&rarr;client pump at a message boundary, hand the backend stream to
     * {@code exchange}, then resume the pump.
     *
     * @return {@code true} if the pump parked and {@code exchange} ran; {@code false} if the
     *         pump could not reach a boundary in time — the caller must then forward the
     *         original {@code 'Q'} unmodified (fail-open).
     */
    private boolean runWithPumpPaused(CopyExchange exchange) throws IOException {
        pausePump = true;
        boolean parked = false;
        try {
            parked = pumpParkedAck.tryAcquire(1, PUMP_PARK_WAIT_MS, TimeUnit.MILLISECONDS);
            if (!parked) {
                if (!pumpFinished) {
                    LOG.warn("Backend pump did not reach a message boundary in time; "
                            + "forwarding the COPY/UNLOAD to PostgreSQL unintercepted");
                }
                return false;
            }
            backend.setSoTimeout(0); // the simulator wants blocking reads
            exchange.run();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while pausing backend pump", e);
        } finally {
            try {
                backend.setSoTimeout(PUMP_READ_TIMEOUT_MS);
            } catch (IOException e) {
                LOG.debugv(e, "could not restore backend read timeout (socket closing)");
            }
            pausePump = false;
            if (parked) {
                pumpResume.release();
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
                int read;
                try {
                    read = backendIn.read(buffer);
                } catch (SocketTimeoutException e) {
                    // Only hand the backend stream over between whole messages, so the
                    // simulator's decoder never starts mid-frame.
                    if (pausePump && tracker.betweenMessages()) {
                        pumpParkedAck.release();
                        pumpResume.acquire();
                    }
                    continue;
                }
                if (read == -1) {
                    break;
                }
                clientOut.write(buffer, 0, read);
                clientOut.flush();
                tracker.consume(buffer, 0, read);
            }
        } catch (IOException e) {
            LOG.debugv(e, "backend->client pump ended");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pumpFinished = true;
            pumpParkedAck.release(); // unblock a waiter expecting a park that will never come
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
