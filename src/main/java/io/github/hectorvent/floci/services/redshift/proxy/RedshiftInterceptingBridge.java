package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.services.s3.S3Service;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Replaces the transparent {@code bridge()} for Redshift connections so SQL in Simple and Extended
 * Query traffic can be inspected before it reaches the backing PostgreSQL container.
 *
 * <p><b>Model.</b> Backend&rarr;client is a byte pump on a virtual thread. Client&rarr;backend is a
 * framed loop: SQL-bearing Query and Parse frames can be rewritten, while other frontend messages
 * are forwarded with their original bytes.
 *
 * <p><b>Backend ownership.</b> A future interceptor that injects its own query and reads the reply
 * (the S3 COPY simulator) must own the backend exclusively and only start when no earlier request
 * is still being answered. {@link #backendLock} serialises pump reads against such an exchange, and
 * the response coordinator plus {@link #pumpBetweenMessages} gate it to a safe boundary. If the
 * backend cannot be caught idle within {@link #PUMP_PARK_WAIT_MS}, a Simple Query exchange is
 * skipped and the original {@code 'Q'} is forwarded (fail-open).
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
    private final ExtendedQuerySession session = new ExtendedQuerySession();
    private final BackendResponseCoordinator coordinator = new BackendResponseCoordinator(session);

    private final ReentrantLock backendLock = new ReentrantLock(true);
    private volatile boolean pumpBetweenMessages = true;
    private volatile boolean pumpFinished = false;

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
                    msg = decoder.nextMessage(backendOut, type -> type == 'Q' || type == 'P'
                            || type == 'B' || type == 'D' || type == 'E' || type == 'C' || type == 'S');
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

                if (msg.body() == null) {
                    if (msg.type() == 'P') {
                        session.clear();
                        coordinator.register(BackendResponseCoordinator.Operation.PARSE, null);
                    } else if (msg.type() == 'B') {
                        session.clearPortals();
                        coordinator.register(BackendResponseCoordinator.Operation.BIND, null);
                    } else if (msg.type() == 'D') {
                        coordinator.register(BackendResponseCoordinator.Operation.DESCRIBE_PORTAL, null);
                    } else if (msg.type() == 'E') {
                        session.clearPortals();
                        coordinator.register(BackendResponseCoordinator.Operation.EXECUTE, null);
                    } else if (msg.type() == 'C') {
                        coordinator.register(BackendResponseCoordinator.Operation.CLOSE, null);
                    } else if (msg.type() == 'X') {
                        break;
                    }
                    continue;
                }
                switch (msg.type()) {
                    case 'Q' -> handleSimpleQuery(msg, backendOut);
                    case 'P' -> handleParse(decoder, msg, backendOut);
                    case 'B' -> handleBind(decoder, msg, backendOut);
                    case 'D' -> handleDescribe(decoder, msg, backendOut);
                    case 'E' -> handleExecute(decoder, msg, backendOut);
                    case 'C' -> handleClose(decoder, msg, backendOut);
                    case 'S' -> {
                        coordinator.register(BackendResponseCoordinator.Operation.SYNC, null);
                        write(backendOut, msg.toPacketBytes());
                    }
                    default -> write(backendOut, msg.toPacketBytes());
                }
            }
        } catch (IOException e) {
            LOG.debugv(e, "RedshiftInterceptingBridge client loop ended");
        } catch (Exception e) {
            LOG.warnv(e, "Unexpected error in RedshiftInterceptingBridge");
        } finally {
            coordinator.close();
            closeQuietly(client, "client");
            closeQuietly(backend, "backend");
        }
    }

    private void handleSimpleQuery(PostgresWireDecoder.FrontendMessage message, OutputStream backendOut)
            throws IOException {
        String sql = message.getSql();
        CopyStatementParser.S3Statement parsed = parseS3Statement(sql);
        if (parsed != null) {
            CopyStatementParser.S3Statement statement = parsed;
            boolean intercepted = runWithBackendOwned(() -> switch (statement) {
                case CopyStatementParser.S3CopyFrom copy -> S3CopySimulator.runCopyFrom(
                        client, backend, copy, s3Service, coordinator.lastReadyStatus(),
                        status -> coordinator.onBackendFrame('Z', new byte[]{(byte) status}));
                case CopyStatementParser.S3Unload unload -> S3CopySimulator.runUnload(
                        client, backend, unload, s3Service, coordinator.lastReadyStatus(),
                        status -> coordinator.onBackendFrame('Z', new byte[]{(byte) status}));
            });
            if (intercepted) {
                return;
            }
        }

        byte[] packet = message.toPacketBytes();
        try {
            String rewritten = RedshiftSqlInterceptor.rewrite(sql);
            if (!rewritten.equals(sql)) {
                packet = PostgresWireDecoder.encodeQuery(rewritten);
            }
        } catch (RuntimeException e) {
            LOG.warnv("RedshiftSqlInterceptor failed, forwarding original query: {0}", e.getMessage());
        }
        coordinator.register(BackendResponseCoordinator.Operation.SIMPLE_QUERY, null);
        write(backendOut, packet);
    }

    private void handleParse(PostgresWireDecoder decoder, PostgresWireDecoder.FrontendMessage message,
            OutputStream backendOut) throws IOException {
        PostgresWireDecoder.ParseMessage parse = decoder.decodeParse(message);
        CopyStatementParser.S3Statement statement = parse.parameterTypeOids().isEmpty()
                ? parseS3Statement(parse.sql()) : null;
        ExtendedQuerySession.Mutation mutation = session.stageParse(parse.statementName(), statement);
        coordinator.register(BackendResponseCoordinator.Operation.PARSE, mutation);

        String rewritten = parse.sql();
        if (statement instanceof CopyStatementParser.S3CopyFrom copy) {
            rewritten = S3CopySimulator.copyBackendSql(copy);
        } else if (statement instanceof CopyStatementParser.S3Unload unload) {
            rewritten = S3CopySimulator.unloadBackendSql(unload);
        } else {
            try {
                rewritten = RedshiftSqlInterceptor.rewrite(parse.sql());
            } catch (RuntimeException e) {
                LOG.warnv("RedshiftSqlInterceptor failed, forwarding original Parse: {0}", e.getMessage());
            }
        }
        byte[] packet = rewritten.equals(parse.sql())
                ? message.toPacketBytes() : PostgresWireDecoder.encodeParse(parse, rewritten);
        write(backendOut, packet);
    }

    private void handleBind(PostgresWireDecoder decoder, PostgresWireDecoder.FrontendMessage message,
            OutputStream backendOut) throws IOException {
        PostgresWireDecoder.BindMessage bind = decoder.decodeBind(message);
        ExtendedQuerySession.Mutation mutation = session.stageBind(bind.portalName(), bind.statementName());
        coordinator.register(BackendResponseCoordinator.Operation.BIND, mutation);
        write(backendOut, message.toPacketBytes());
    }

    private void handleDescribe(PostgresWireDecoder decoder, PostgresWireDecoder.FrontendMessage message,
            OutputStream backendOut) throws IOException {
        PostgresWireDecoder.TargetMessage describe = decoder.decodeDescribe(message);
        BackendResponseCoordinator.Operation operation = describe.targetType() == 'S'
                ? BackendResponseCoordinator.Operation.DESCRIBE_STATEMENT
                : BackendResponseCoordinator.Operation.DESCRIBE_PORTAL;
        coordinator.register(operation, null);
        write(backendOut, message.toPacketBytes());
    }

    private void handleExecute(PostgresWireDecoder decoder, PostgresWireDecoder.FrontendMessage message,
            OutputStream backendOut) throws IOException {
        PostgresWireDecoder.ExecuteMessage execute = decoder.decodeExecute(message);
        BackendResponseCoordinator.Ticket ticket = coordinator.register(
                BackendResponseCoordinator.Operation.EXECUTE, null);
        CopyStatementParser.S3Statement statement = session.portal(execute.portalName()).orElse(null);
        if (statement == null) {
            write(backendOut, message.toPacketBytes());
            return;
        }
        runExtendedWithBackendOwned(message, statement, ticket);
    }

    private void handleClose(PostgresWireDecoder decoder, PostgresWireDecoder.FrontendMessage message,
            OutputStream backendOut) throws IOException {
        PostgresWireDecoder.TargetMessage close = decoder.decodeClose(message);
        ExtendedQuerySession.Mutation mutation = session.stageClose(close.targetType(), close.name());
        coordinator.register(BackendResponseCoordinator.Operation.CLOSE, mutation);
        write(backendOut, message.toPacketBytes());
    }

    private CopyStatementParser.S3Statement parseS3Statement(String sql) {
        try {
            return CopyStatementParser.parse(sql);
        } catch (RuntimeException e) {
            LOG.warnv("CopyStatementParser failed, forwarding original statement: {0}", e.getMessage());
            return null;
        }
    }

    private void runExtendedWithBackendOwned(PostgresWireDecoder.FrontendMessage executeFrame,
            CopyStatementParser.S3Statement statement, BackendResponseCoordinator.Ticket ticket) throws IOException {
        BackendResponseCoordinator.GateResult gate;
        try {
            gate = coordinator.awaitTurn(ticket);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while awaiting Extended Query backend ownership", e);
        }
        if (gate != BackendResponseCoordinator.GateResult.READY) {
            return;
        }

        boolean locked;
        try {
            locked = backendLock.tryLock(PUMP_PARK_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while acquiring Extended Query backend ownership", e);
        }
        if (!locked || !pumpBetweenMessages || pumpFinished) {
            if (locked) {
                backendLock.unlock();
            }
            throw new IOException("Could not take backend ownership for Extended Query Execute");
        }
        try {
            backend.setSoTimeout(EXCHANGE_READ_TIMEOUT_MS);
            ExtendedS3Exchange.execute(client, backend, executeFrame, statement, s3Service, coordinator, ticket);
        } finally {
            try {
                backend.setSoTimeout(PUMP_READ_TIMEOUT_MS);
            } catch (IOException e) {
                LOG.debugv(e, "could not restore backend read timeout (socket closing)");
            }
            backendLock.unlock();
        }
    }

    private static void write(OutputStream out, byte[] packet) throws IOException {
        out.write(packet);
        out.flush();
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
            try {
                if (!coordinator.awaitIdle(PUMP_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    if (System.nanoTime() >= deadlineNanos) {
                        LOG.warn("backend did not go idle in time; forwarding the COPY unintercepted");
                        return false;
                    }
                    continue;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for backend responses", e);
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
                backendBusy = !pumpBetweenMessages;
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
        WireFrameTracker tracker = new WireFrameTracker(coordinator::onBackendFrame);
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
     * is true exactly when the next byte would begin a new message. The callback fires after every
     * complete frame and retains only the one-byte {@code ReadyForQuery} status payload.
     */
    static final class WireFrameTracker {
        private final FrameCompletion onFrame;
        private int headerBytesSeen = 0;
        private final byte[] header = new byte[5];
        private long bodyRemaining = 0;
        private long declaredBodyLength = 0;
        private char pendingType = 0;
        private byte readyStatus;

        WireFrameTracker(FrameCompletion onFrame) {
            this.onFrame = onFrame;
        }

        boolean betweenMessages() {
            return headerBytesSeen == 0 && bodyRemaining == 0;
        }

        void consume(byte[] buf, int off, int len) {
            for (int i = off; i < off + len; i++) {
                if (bodyRemaining > 0) {
                    int current = buf[i] & 0xFF;
                    if (pendingType == 'Z' && declaredBodyLength == 1) {
                        readyStatus = (byte) current;
                    }
                    bodyRemaining--;
                    if (bodyRemaining == 0) {
                        publishCompletion();
                    }
                    continue;
                }
                header[headerBytesSeen++] = buf[i];
                if (headerBytesSeen == 5) {
                    long msgLen = ((header[1] & 0xFFL) << 24) | ((header[2] & 0xFFL) << 16)
                            | ((header[3] & 0xFFL) << 8) | (header[4] & 0xFFL);
                    pendingType = (char) (header[0] & 0xFF);
                    bodyRemaining = Math.max(0, msgLen - 4);
                    declaredBodyLength = bodyRemaining;
                    headerBytesSeen = 0;
                    if (bodyRemaining == 0) {
                        publishCompletion();
                    }
                }
            }
        }

        private void publishCompletion() {
            byte[] body = pendingType == 'Z' && declaredBodyLength == 1
                    ? new byte[]{readyStatus} : new byte[0];
            onFrame.accept(pendingType, body);
        }
    }

    @FunctionalInterface
    interface FrameCompletion {
        void accept(char type, byte[] body);
    }
}
