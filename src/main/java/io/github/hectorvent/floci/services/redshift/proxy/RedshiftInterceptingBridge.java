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
 * simulator the loop therefore parks the pump ({@link #pausePump}) and takes
 * exclusive ownership of the backend stream; it resumes the pump afterwards.
 * This is safe because Simple Query is strictly serial: when a {@code 'Q'}
 * arrives the previous response (through its {@code ReadyForQuery}) has already
 * been relayed, so the backend is idle and the pump is parked on a clean
 * message boundary. The pump reads with a short socket timeout purely so it can
 * observe the park request while otherwise blocked in {@code read()}.
 */
public class RedshiftInterceptingBridge {

    private static final Logger LOG = Logger.getLogger(RedshiftInterceptingBridge.class);

    /** How long a pump {@code read()} blocks before looping to check for a park request. */
    private static final int PUMP_READ_TIMEOUT_MS = 200;
    /** Upper bound on how long the client loop waits for the pump to park before giving up. */
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

                if (handledByParser && stmt instanceof CopyStatementParser.S3CopyFrom copyFrom) {
                    runWithPumpPaused(() -> S3CopySimulator.runCopyFrom(client, backend, copyFrom, s3Service));
                } else if (handledByParser && stmt instanceof CopyStatementParser.S3Unload unload) {
                    runWithPumpPaused(() -> S3CopySimulator.runUnload(client, backend, unload, s3Service));
                } else {
                    byte[] toBackend = msg.toPacketBytes();
                    if (handledByParser) {
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
            }
        } catch (IOException e) {
            LOG.debugv("RedshiftInterceptingBridge closing: {0}", e.getMessage());
        } catch (Exception e) {
            LOG.warnv("Unexpected error in RedshiftInterceptingBridge: {0}", e.getMessage());
        } finally {
            closeQuietly(client);
            closeQuietly(backend);
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
     * Park the backend&rarr;client pump, hand the backend stream to {@code exchange},
     * then resume the pump. The pump is guaranteed not to be mid-message: it only
     * ever reads while the backend is actively responding, and a client {@code 'Q'}
     * cannot arrive until the previous response finished.
     */
    private void runWithPumpPaused(CopyExchange exchange) throws IOException {
        pausePump = true;
        boolean parked = false;
        try {
            parked = pumpParkedAck.tryAcquire(1, PUMP_PARK_WAIT_MS, TimeUnit.MILLISECONDS);
            if (!parked && !pumpFinished) {
                throw new IOException("backend pump did not park for COPY/UNLOAD interception");
            }
            backend.setSoTimeout(0); // the simulator wants blocking reads
            exchange.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while pausing backend pump", e);
        } finally {
            try {
                backend.setSoTimeout(PUMP_READ_TIMEOUT_MS);
            } catch (IOException ignored) {
                // socket already closing
            }
            pausePump = false;
            if (parked) {
                pumpResume.release();
            }
        }
    }

    private void pumpBackendToClient() {
        try {
            InputStream backendIn = backend.getInputStream();
            OutputStream clientOut = client.getOutputStream();
            byte[] buffer = new byte[8192];
            while (true) {
                int read;
                try {
                    read = backendIn.read(buffer);
                } catch (SocketTimeoutException e) {
                    if (pausePump) {
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
            }
        } catch (IOException e) {
            // Expected when sockets close.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pumpFinished = true;
            pumpParkedAck.release(); // unblock a waiter that is expecting a park that will never come
            closeQuietly(client);
            closeQuietly(backend);
        }
    }

    private void closeQuietly(Socket s) {
        try {
            if (s != null && !s.isClosed()) {
                s.close();
            }
        } catch (IOException e) {
            // ignore
        }
    }
}
