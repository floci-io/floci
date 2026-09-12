package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.IntConsumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Emulates {@code COPY <table> FROM 's3://bucket/keyOrPrefix'} and
 * {@code UNLOAD ('<select>') TO 's3://bucket/prefix'} by streaming data between {@link S3Service}
 * and the backing PostgreSQL container over a fabricated {@code COPY ... FROM STDIN} or
 * {@code COPY (...) TO STDOUT} exchange.
 *
 * <p>The caller must own the backend socket exclusively for the whole call: this class writes a
 * query and reads its response frames directly. Every exit path leaves the client with exactly one
 * response for its original {@code 'Q'} (a {@code CommandComplete}/{@code ReadyForQuery} on success,
 * a single {@code ErrorResponse}/{@code ReadyForQuery} on failure) and never closes the connection.
 */
public final class S3CopySimulator {

    private static final Logger LOG = Logger.getLogger(S3CopySimulator.class);

    private static final int LIST_PAGE_SIZE = 1000;
    private static final int CHUNK = 8192;

    // The two limits below are package-private and non-final only so unit tests can shrink them to
    // force multi-slice and over-limit paths on small inputs. Surefire runs a test class serially, and
    // each test restores the previous value in a finally block, so there is no cross-test interference.
    /** Default per-object size when the statement gives no MAXFILESIZE. Small because each slice is buffered in heap. */
    static long UNLOAD_TARGET_FILE_BYTES = 6L * 1024 * 1024;
    /** Whole-result ceiling; a larger UNLOAD is aborted rather than filling the in-memory S3 store. */
    static long UNLOAD_MAX_TOTAL_BYTES = 256L * 1024 * 1024;
    private static final long UNLOAD_WARN_BYTES = 32L * 1024 * 1024;
    /** Shared across every connection so concurrent UNLOADs cannot multiply the per-slice heap cost without bound. */
    static final Semaphore UNLOAD_HEAP_MIB = new Semaphore(192);
    private static final int UNLOAD_INITIAL_MIB = 12;

    private static final String SQLSTATE_PROGRAM_LIMIT_EXCEEDED = "54000";
    private static final String SQLSTATE_CONFIGURATION_LIMIT_EXCEEDED = "53400";
    private static final String SQLSTATE_INSUFFICIENT_PRIVILEGE = "42501";
    private static final String SQLSTATE_INTERNAL = "XX000";

    private S3CopySimulator() {
    }

    record CopyInput(CopyStatementParser.S3CopyFrom spec, List<String> keys, S3Service s3) {
    }

    interface UnloadCollector extends AutoCloseable {
        void accept(byte[] body) throws IOException;

        void complete() throws IOException;

        void abort();

        @Override
        void close();
    }

    static final class S3TransferException extends RuntimeException {
        private final String sqlState;

        S3TransferException(String sqlState, String message, Throwable cause) {
            super(message, cause);
            this.sqlState = sqlState;
        }

        String sqlState() {
            return sqlState;
        }
    }

    static CopyInput prepareCopy(CopyStatementParser.S3CopyFrom spec, S3Service s3) {
        try {
            s3.authorizeAnonymousListBucket(spec.bucket());
        } catch (AwsException e) {
            throw new S3TransferException(SQLSTATE_INSUFFICIENT_PRIVILEGE,
                    "S3 access denied for s3://" + spec.bucket() + "/" + spec.keyOrPrefix(), e);
        }

        List<String> keys;
        try {
            keys = resolveKeys(spec, s3);
        } catch (AwsException e) {
            throw new S3TransferException(SQLSTATE_INTERNAL,
                    "S3 COPY could not list s3://" + spec.bucket() + "/" + spec.keyOrPrefix(), e);
        }
        if (keys.isEmpty()) {
            throw new S3TransferException(SQLSTATE_INTERNAL,
                    "S3 object s3://" + spec.bucket() + "/" + spec.keyOrPrefix() + " not found", null);
        }

        try {
            for (String key : keys) {
                s3.authorizeAnonymousGetObject(spec.bucket(), key);
            }
        } catch (AwsException e) {
            throw new S3TransferException(SQLSTATE_INSUFFICIENT_PRIVILEGE,
                    "S3 access denied for s3://" + spec.bucket() + "/" + spec.keyOrPrefix(), e);
        }
        return new CopyInput(spec, List.copyOf(keys), s3);
    }

    static String copyBackendSql(CopyStatementParser.S3CopyFrom spec) {
        return fabricateCopy(spec);
    }

    static void streamCopyInput(CopyInput input, OutputStream backendOut) throws IOException {
        streamObjects(input.spec(), input.s3(), input.keys(), backendOut);
    }

    static UnloadCollector prepareUnload(CopyStatementParser.S3Unload spec, S3Service s3) {
        String probeKey = unloadDataKey(spec, 0);
        try {
            s3.authorizeAnonymousPutObject(spec.bucket(), probeKey);
            if (spec.manifest()) {
                s3.authorizeAnonymousPutObject(spec.bucket(), spec.prefix() + "manifest");
            }
            if (!spec.allowOverwrite()) {
                s3.authorizeAnonymousListBucket(spec.bucket());
                if (targetPrefixHasObjects(spec, s3)) {
                    throw new S3TransferException(SQLSTATE_INTERNAL,
                            "S3 prefix s3://" + spec.bucket() + "/" + spec.prefix()
                                    + " is not empty; specify ALLOWOVERWRITE to overwrite", null);
                }
            }
        } catch (AwsException e) {
            throw new S3TransferException(unloadWriteSqlState(e), unloadWriteMessage(e, spec), e);
        }

        if (!UNLOAD_HEAP_MIB.tryAcquire(UNLOAD_INITIAL_MIB)) {
            throw new S3TransferException(SQLSTATE_CONFIGURATION_LIMIT_EXCEEDED,
                    "UNLOAD memory budget exhausted; retry shortly", null);
        }
        try {
            return new S3UnloadCollector(spec, s3);
        } catch (IOException e) {
            UNLOAD_HEAP_MIB.release(UNLOAD_INITIAL_MIB);
            throw new S3TransferException(SQLSTATE_INTERNAL, "UNLOAD failed: " + e.getMessage(), e);
        }
    }

    static String unloadBackendSql(CopyStatementParser.S3Unload spec) {
        return fabricateUnloadCopy(spec);
    }

    /**
     * @param txStatus the transaction-status byte from the client's last {@code ReadyForQuery}
     *                 ({@code 'I'} idle, {@code 'T'} in a block, {@code 'E'} failed block); a
     *                 synthesized error reports {@code 'I'} outside a block and {@code 'E'} inside.
     * @return {@code true} when the exchange was handled (success or a clean error sent to the
     *         client). Part 4 always handles; the value exists so a later interceptor can decline.
     */
    public static boolean runCopyFrom(Socket client, Socket backend,
                                      CopyStatementParser.S3CopyFrom spec, S3Service s3,
                                      char txStatus) throws IOException {
        return runCopyFrom(client, backend, spec, s3, txStatus, null);
    }

    public static boolean runCopyFrom(Socket client, Socket backend,
                                      CopyStatementParser.S3CopyFrom spec, S3Service s3,
                                      char txStatus, IntConsumer onStatusChange) throws IOException {
        CopyInput input;
        try {
            input = prepareCopy(spec, s3);
        } catch (S3TransferException e) {
            LOG.debugv(e, "COPY preparation failed for s3://{0}/{1}", spec.bucket(), spec.keyOrPrefix());
            sendError(client, backend, e.sqlState(), e.getMessage(), txStatus, onStatusChange);
            return true;
        }

        OutputStream backendOut = backend.getOutputStream();
        backendOut.write(PostgresWireDecoder.encodeQuery(copyBackendSql(spec)));
        backendOut.flush();

        PostgresWireDecoder backendDecoder = new PostgresWireDecoder(backend.getInputStream());
        PostgresWireDecoder.FrontendMessage first;
        try {
            first = nextNonAsync(backendDecoder, client);
        } catch (IOException e) {
            LOG.warnv(e, "backend read failed while awaiting CopyInResponse");
            closeQuietly(backend);
            sendError(client, null, SQLSTATE_INTERNAL, "S3 COPY failed: backend closed or timed out", txStatus, onStatusChange);
            closeQuietly(client);
            return true;
        }
        if (first == null) {
            LOG.warn("backend closed before answering the fabricated COPY");
            closeQuietly(backend);
            sendError(client, null, SQLSTATE_INTERNAL, "S3 COPY failed: backend closed before COPY started", txStatus, onStatusChange);
            closeQuietly(client);
            return true;
        }
        if (first.type() != 'G') {
            // Backend rejected the COPY itself (e.g. no such table). Its ErrorResponse and the
            // ReadyForQuery that follows are the client's one response.
            forward(client, first);
            drainToReadyForQuery(backendDecoder, client, onStatusChange);
            return true;
        }

        // The CopyIn stream is open. Any failure from here is resolved with exactly one response:
        // a CopyFail to the backend, whose ErrorResponse/ReadyForQuery is relayed to the client;
        // or, if the backend is unreachable, one synthesized ErrorResponse/ReadyForQuery.
        try {
            streamCopyInput(input, backendOut);
            writeCopyDone(backendOut);
            drainToReadyForQuery(backendDecoder, client, onStatusChange);
        } catch (RuntimeException | IOException e) {
            LOG.warnv(e, "S3 COPY streaming failed; aborting the open CopyIn");
            abortOpenCopyIn(client, backend, backendOut, backendDecoder, e, txStatus, onStatusChange);
        }
        return true;
    }

    private static void abortOpenCopyIn(Socket client, Socket backend, OutputStream backendOut,
                                        PostgresWireDecoder backendDecoder, Exception cause,
                                        char txStatus, IntConsumer onStatusChange) throws IOException {
        try {
            writeCopyFail(backendOut, cause.getMessage());
            drainToReadyForQuery(backendDecoder, client, onStatusChange);
        } catch (IOException backendGone) {
            LOG.debugv(backendGone, "backend unreachable while aborting CopyIn; synthesizing client error");
            closeQuietly(backend);
            String detail = cause.getMessage() != null ? cause.getMessage() : cause.toString();
            sendError(client, null, SQLSTATE_INTERNAL, "S3 COPY failed: " + detail, txStatus, onStatusChange);
            closeQuietly(client);
        }
    }

    private static List<String> resolveKeys(CopyStatementParser.S3CopyFrom spec, S3Service s3) {
        List<String> keys = new ArrayList<>();
        if (s3.objectExists(spec.bucket(), spec.keyOrPrefix())) {
            keys.add(spec.keyOrPrefix());
            return keys;
        }
        String continuationToken = null;
        do {
            S3Service.ListObjectsResult result = s3.listObjectsWithPrefixes(
                    spec.bucket(), spec.keyOrPrefix(), null, LIST_PAGE_SIZE, continuationToken, null);
            if (result != null && result.objects() != null) {
                for (S3Object object : result.objects()) {
                    keys.add(object.getKey());
                }
            }
            continuationToken = (result != null && result.isTruncated()) ? result.nextContinuationToken() : null;
        } while (continuationToken != null);

        keys.sort(String::compareTo);
        return keys;
    }

    private static String fabricateCopy(CopyStatementParser.S3CopyFrom spec) {
        StringBuilder sql = new StringBuilder("COPY ").append(spec.targetTable());
        if (spec.columns() != null && !spec.columns().isEmpty()) {
            sql.append(" (").append(String.join(", ", spec.columns())).append(")");
        }
        sql.append(" FROM STDIN WITH (FORMAT ").append(spec.csv() ? "csv" : "text");
        String delimiter = spec.delimiter() != null ? spec.delimiter() : (spec.csv() ? "," : "|");
        sql.append(", DELIMITER '").append(quoteLiteral(delimiter)).append("'");
        if (spec.nullAs() != null) {
            sql.append(", NULL '").append(quoteLiteral(spec.nullAs())).append("'");
        }
        sql.append(")");
        return sql.toString();
    }

    /**
     * Quote a value for a single-quoted SQL string literal. PostgreSQL defaults to
     * {@code standard_conforming_strings = on}, where a backslash is an ordinary character, so only
     * the quote itself is doubled: {@code NULL AS '\N'} must reach the backend as {@code '\N'}.
     */
    private static String quoteLiteral(String value) {
        return value.replace("'", "''");
    }

    private static void streamObjects(CopyStatementParser.S3CopyFrom spec, S3Service s3,
                                      List<String> keys, OutputStream backendOut) throws IOException {
        byte[] buffer = new byte[CHUNK];
        for (int i = 0; i < keys.size(); i++) {
            S3Object object = s3.getObject(spec.bucket(), keys.get(i));
            byte[] data = object != null && object.getData() != null ? object.getData() : new byte[0];
            InputStream in = new ByteArrayInputStream(data);
            if (spec.gzip()) {
                in = new GZIPInputStream(in);
            }
            if (i == 0 && spec.headerLines() > 0) {
                skipLines(in, spec.headerLines());
            }
            int read;
            boolean endsWithNewline = false;
            boolean hasData = false;
            while ((read = in.read(buffer)) != -1) {
                if (read > 0) {
                    hasData = true;
                    endsWithNewline = (buffer[read - 1] == '\n');
                    writeCopyData(backendOut, buffer, read);
                }
            }
            in.close();
            if (hasData && !endsWithNewline) {
                writeCopyData(backendOut, new byte[]{'\n'}, 1);
            }
        }
        backendOut.flush();
    }

    private static void skipLines(InputStream in, int lines) throws IOException {
        int seen = 0;
        int b;
        while (seen < lines && (b = in.read()) != -1) {
            if (b == '\n') {
                seen++;
            }
        }
    }

    private static void writeCopyData(OutputStream out, byte[] buf, int len) throws IOException {
        out.write('d');
        out.write(intBytes(4 + len));
        out.write(buf, 0, len);
    }

    private static void writeCopyDone(OutputStream out) throws IOException {
        out.write('c');
        out.write(intBytes(4));
        out.flush();
    }

    private static void writeCopyFail(OutputStream out, String reason) throws IOException {
        byte[] message = (reason == null ? "S3 COPY aborted" : reason).getBytes(StandardCharsets.UTF_8);
        out.write('f');
        out.write(intBytes(4 + message.length + 1));
        out.write(message);
        out.write(0);
        out.flush();
    }

    /** Read the next backend message, relaying asynchronous messages to the client as it goes. */
    private static PostgresWireDecoder.FrontendMessage nextNonAsync(PostgresWireDecoder decoder, Socket client)
            throws IOException {
        OutputStream clientOut = client.getOutputStream();
        while (true) {
            PostgresWireDecoder.FrontendMessage message = decoder.nextMessage();
            if (message == null) {
                return null;
            }
            char type = message.type();
            if (type == 'N' || type == 'A' || type == 'S') {
                clientOut.write(message.toPacketBytes());
                clientOut.flush();
                continue;
            }
            return message;
        }
    }

    private static void drainToReadyForQuery(PostgresWireDecoder decoder, Socket client,
                                            IntConsumer onStatusChange) throws IOException {
        OutputStream clientOut = client.getOutputStream();
        while (true) {
            PostgresWireDecoder.FrontendMessage message = decoder.nextMessage();
            if (message == null) {
                return;
            }
            clientOut.write(message.toPacketBytes());
            clientOut.flush();
            if (message.type() == 'Z') {
                if (onStatusChange != null && message.body().length > 0) {
                    onStatusChange.accept(message.body()[0]);
                }
                return;
            }
        }
    }

    private static void forward(Socket client, PostgresWireDecoder.FrontendMessage message) throws IOException {
        client.getOutputStream().write(message.toPacketBytes());
        client.getOutputStream().flush();
    }

    private static void sendError(Socket client, Socket backend, String sqlState, String message,
                                  char txStatus, IntConsumer onStatusChange) throws IOException {
        if (txStatus == 'T' && backend != null && !backend.isClosed()) {
            if (!failBackendTransaction(backend, onStatusChange)) {
                closeQuietly(backend);
                // Backend transaction state could not be confirmed; do not report an unconfirmed
                // failed transaction ('E') or leave a desynchronized backend in the pump.
                try {
                    OutputStream out = client.getOutputStream();
                    byte[] body = errorBody(sqlState, message);
                    out.write('E');
                    out.write(intBytes(4 + body.length));
                    out.write(body);
                    out.flush();
                } catch (IOException e) {
                    LOG.debugv(e, "failed to send ErrorResponse to client before closing socket");
                } finally {
                    closeQuietly(client);
                }
                return;
            }
        }
        OutputStream out = client.getOutputStream();
        byte[] body = errorBody(sqlState, message);
        out.write('E');
        out.write(intBytes(4 + body.length));
        out.write(body);
        byte status = (txStatus == 'I' || txStatus == 0) ? (byte) 'I' : (byte) 'E';
        out.write(new byte[]{'Z', 0, 0, 0, 5, status});
        out.flush();
        if (onStatusChange != null) {
            onStatusChange.accept(status);
        }
    }

    private static boolean failBackendTransaction(Socket backend, IntConsumer onStatusChange) {
        try {
            OutputStream out = backend.getOutputStream();
            out.write(PostgresWireDecoder.encodeQuery("(FLOCI_ABORT_TX)"));
            out.flush();
            PostgresWireDecoder decoder = new PostgresWireDecoder(backend.getInputStream());
            while (true) {
                PostgresWireDecoder.FrontendMessage msg = decoder.nextMessage();
                if (msg == null) {
                    LOG.warn("backend closed before transaction abort could be synchronized");
                    return false;
                }
                if (msg.type() == 'Z') {
                    char status = (msg.body().length > 0) ? (char) msg.body()[0] : 0;
                    if (onStatusChange != null && status != 0) {
                        onStatusChange.accept(status);
                    }
                    if (status != 'E') {
                        LOG.warnv("backend returned unexpected transaction status {0} instead of 'E' after abort query", status);
                        return false;
                    }
                    return true;
                }
            }
        } catch (IOException e) {
            LOG.warnv(e, "failed to synchronize backend transaction abort state");
            return false;
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            if (s != null && !s.isClosed()) {
                s.close();
            }
        } catch (IOException e) {
            LOG.debugv(e, "failed to close socket: {0}", s);
        }
    }

    private static byte[] errorBody(String sqlState, String message) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writeField(bytes, 'S', "ERROR");
        writeField(bytes, 'C', sqlState);
        writeField(bytes, 'M', message);
        bytes.write(0);
        return bytes.toByteArray();
    }

    private static void writeField(ByteArrayOutputStream bytes, char tag, String value) {
        bytes.write(tag);
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        bytes.write(raw, 0, raw.length);
        bytes.write(0);
    }

    private static byte[] intBytes(int value) {
        return new byte[]{
                (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
        };
    }

    public static boolean runUnload(Socket client, Socket backend,
            CopyStatementParser.S3Unload spec, S3Service s3, char txStatus) throws IOException {
        return runUnload(client, backend, spec, s3, txStatus, null);
    }

    public static boolean runUnload(Socket client, Socket backend,
            CopyStatementParser.S3Unload spec, S3Service s3, char txStatus,
            IntConsumer onStatusChange) throws IOException {
        UnloadCollector collector;
        try {
            collector = prepareUnload(spec, s3);
        } catch (S3TransferException e) {
            LOG.debugv(e, "UNLOAD preparation failed for s3://{0}/{1}", spec.bucket(), spec.prefix());
            sendError(client, backend, e.sqlState(), e.getMessage(), txStatus, onStatusChange);
            return true;
        }

        try (collector) {
            return runUnloadStreaming(client, backend, spec, collector, txStatus, onStatusChange);
        }
    }

    private static boolean runUnloadStreaming(Socket client, Socket backend,
            CopyStatementParser.S3Unload spec, UnloadCollector collector, char txStatus,
            IntConsumer onStatusChange) throws IOException {

        PostgresWireDecoder.FrontendMessage cmdComplete = null;
        PostgresWireDecoder.FrontendMessage readyForQuery = null;

        OutputStream backendOut = backend.getOutputStream();
        backendOut.write(PostgresWireDecoder.encodeQuery(unloadBackendSql(spec)));
        backendOut.flush();

        PostgresWireDecoder backendDecoder = new PostgresWireDecoder(backend.getInputStream());
        PostgresWireDecoder.FrontendMessage first;
        try {
            first = nextNonAsync(backendDecoder, client);
        } catch (IOException e) {
            LOG.warnv(e, "backend read failed while awaiting CopyOutResponse");
            collector.abort();
            closeQuietly(backend);
            sendError(client, null, SQLSTATE_INTERNAL,
                    "UNLOAD failed: backend closed or timed out", txStatus, onStatusChange);
            closeQuietly(client);
            return true;
        }
        if (first == null) {
            collector.abort();
            closeQuietly(backend);
            sendError(client, null, SQLSTATE_INTERNAL,
                    "UNLOAD failed: backend closed before COPY started", txStatus, onStatusChange);
            closeQuietly(client);
            return true;
        }
        if (first.type() != 'H') {
            collector.abort();
            forward(client, first);
            drainToReadyForQuery(backendDecoder, client, onStatusChange);
            return true;
        }

        try {
            while (true) {
                PostgresWireDecoder.FrontendMessage message = backendDecoder.nextMessage();
                if (message == null) {
                    collector.abort();
                    closeQuietly(backend);
                    sendError(client, null, SQLSTATE_INTERNAL,
                            "UNLOAD failed: backend closed mid-stream", txStatus, onStatusChange);
                    closeQuietly(client);
                    return true;
                }
                char type = message.type();
                if (type == 'd') {
                    collector.accept(message.body());
                } else if (type == 'C') {
                    cmdComplete = message;
                } else if (type == 'Z') {
                    readyForQuery = message;
                    break;
                } else if (type == 'E') {
                    collector.abort();
                    forward(client, message);
                    drainToReadyForQuery(backendDecoder, client, onStatusChange);
                    return true;
                } else if (type == 'N' || type == 'A' || type == 'S') {
                    forward(client, message);
                }
            }
            collector.complete();
        } catch (S3TransferException e) {
            LOG.debugv(e, "UNLOAD S3 operation failed during streaming");
            if (readyForQuery != null) {
                sendError(client, backend, e.sqlState(), e.getMessage(), txStatus, onStatusChange);
                return true;
            }
            if (SQLSTATE_PROGRAM_LIMIT_EXCEEDED.equals(e.sqlState())) {
                closeQuietly(backend);
                sendError(client, null, e.sqlState(), e.getMessage(), txStatus, onStatusChange);
                closeQuietly(client);
                return true;
            }
            return abortUnload(client, backend, backendDecoder, collector,
                    e.sqlState(), e.getMessage(), txStatus, onStatusChange);
        } catch (RuntimeException | IOException e) {
            LOG.warnv(e, "UNLOAD streaming failed");
            String detail = e.getMessage() != null ? e.getMessage() : e.toString();
            if (readyForQuery != null) {
                sendError(client, backend, SQLSTATE_INTERNAL,
                        "UNLOAD failed: " + detail, txStatus, onStatusChange);
                return true;
            }
            return abortUnload(client, backend, backendDecoder, collector,
                    SQLSTATE_INTERNAL, "UNLOAD failed: " + detail, txStatus, onStatusChange);
        }

        if (cmdComplete != null) {
            forward(client, cmdComplete);
        }
        forward(client, readyForQuery);
        if (onStatusChange != null && readyForQuery.body().length > 0) {
            onStatusChange.accept(readyForQuery.body()[0]);
        }
        return true;
    }

    private static boolean targetPrefixHasObjects(CopyStatementParser.S3Unload spec, S3Service s3) {
        S3Service.ListObjectsResult r = s3.listObjectsWithPrefixes(
                spec.bucket(), spec.prefix(), null, 1, null, null);
        return r != null && r.objects() != null && !r.objects().isEmpty();
    }

    private static String fabricateUnloadCopy(CopyStatementParser.S3Unload spec) {
        boolean csvFraming = spec.csv() || spec.addQuotes() || spec.header();
        StringBuilder sql = new StringBuilder("COPY (").append(spec.selectQuery())
                .append(") TO STDOUT WITH (FORMAT ").append(csvFraming ? "csv" : "text");
        String delimiter = spec.delimiter() != null ? spec.delimiter() : (csvFraming ? "," : "|");
        sql.append(", DELIMITER '").append(quoteLiteral(delimiter)).append("'");
        if (spec.header()) {
            sql.append(", HEADER true");
        }
        if (spec.addQuotes()) {
            sql.append(", FORCE_QUOTE *");
        }
        if (spec.nullAs() != null) {
            sql.append(", NULL '").append(quoteLiteral(spec.nullAs())).append("'");
        }
        sql.append(")");
        return sql.toString();
    }

    private static final class S3UnloadCollector implements UnloadCollector {
        private final CopyStatementParser.S3Unload spec;
        private final S3Service s3;
        private final long threshold;
        private final String contentType;
        private final List<String> writtenKeys = new ArrayList<>();
        private final List<Integer> writtenLengths = new ArrayList<>();

        private ByteArrayOutputStream sink = new ByteArrayOutputStream();
        private OutputStream acc;
        private ByteArrayOutputStream headerBuf;
        private byte[] headerBytes;
        private long rawSlice;
        private long slicePayload;
        private long rawTotal;
        private boolean lastByteNewline = true;
        private boolean warned;
        private boolean capturingHeader;
        private boolean finished;
        private boolean aborted;
        private boolean closed;
        private int sliceIndex;
        private int heldMib = UNLOAD_INITIAL_MIB;

        private S3UnloadCollector(CopyStatementParser.S3Unload spec, S3Service s3) throws IOException {
            this.spec = spec;
            this.s3 = s3;
            threshold = spec.maxFileSizeBytes() > 0 ? spec.maxFileSizeBytes() : UNLOAD_TARGET_FILE_BYTES;
            contentType = spec.gzip() ? "application/gzip" : "text/plain";
            acc = newAccumulator();
            headerBuf = spec.header() ? new ByteArrayOutputStream() : null;
            capturingHeader = spec.header();
        }

        @Override
        public void accept(byte[] body) throws IOException {
            ensureOpen();
            int dataStart = 0;
            if (capturingHeader) {
                int newline = -1;
                for (int i = 0; i < body.length; i++) {
                    if (body[i] == '\n') {
                        newline = i;
                        break;
                    }
                }
                int end = newline >= 0 ? newline + 1 : body.length;
                headerBuf.write(body, 0, end);
                if (newline >= 0) {
                    headerBytes = headerBuf.toByteArray();
                    headerBuf = null;
                    capturingHeader = false;
                }
                dataStart = end;
            } else if (headerBytes != null && sliceIndex > 0 && rawSlice == 0) {
                acc.write(headerBytes);
                rawSlice += headerBytes.length;
            }

            acc.write(body, 0, body.length);
            rawSlice += body.length;
            rawTotal += body.length;
            slicePayload += body.length - dataStart;
            if (body.length > 0) {
                lastByteNewline = body[body.length - 1] == '\n';
            }
            if (!warned && rawTotal > UNLOAD_WARN_BYTES) {
                warned = true;
                LOG.warnv("UNLOAD result passed {0} bytes and is buffered a slice at a time "
                        + "(bucket={1}, prefix={2})", UNLOAD_WARN_BYTES, spec.bucket(), spec.prefix());
            }
            acquireForCurrentSlice();
            if (rawTotal > UNLOAD_MAX_TOTAL_BYTES) {
                fail(SQLSTATE_PROGRAM_LIMIT_EXCEEDED,
                        "UNLOAD result exceeds the " + UNLOAD_MAX_TOTAL_BYTES + "-byte limit", null);
            }
            if (slicePayload >= threshold && lastByteNewline) {
                writeCurrentSlice();
                resetSlice();
            }
        }

        @Override
        public void complete() throws IOException {
            ensureOpen();
            byte[] payload = finishSlice(acc, sink);
            if (slicePayload > 0 || writtenKeys.isEmpty()) {
                writePayload(payload, sliceIndex);
            }
            if (spec.manifest()) {
                try {
                    String key = spec.prefix() + "manifest";
                    s3.authorizeAnonymousPutObject(spec.bucket(), key);
                    s3.putObject(spec.bucket(), key,
                            manifestJson(spec.bucket(), writtenKeys, writtenLengths)
                                    .getBytes(StandardCharsets.UTF_8),
                            "application/json", Map.of());
                } catch (RuntimeException e) {
                    fail(SQLSTATE_INTERNAL, "UNLOAD manifest write failed", e);
                }
            }
            finished = true;
        }

        @Override
        public void abort() {
            if (aborted || finished) {
                return;
            }
            closeAcc(acc, sink);
            if (spec.manifest()) {
                deleteWritten(s3, spec, writtenKeys);
            }
            aborted = true;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (!finished && !aborted) {
                abort();
            }
            UNLOAD_HEAP_MIB.release(heldMib);
            heldMib = 0;
            closed = true;
        }

        private void acquireForCurrentSlice() {
            int wantedMib = (int) ((rawSlice * 3) / (1024 * 1024)) + 1;
            while (heldMib < wantedMib) {
                if (!UNLOAD_HEAP_MIB.tryAcquire(1)) {
                    fail(SQLSTATE_CONFIGURATION_LIMIT_EXCEEDED,
                            "UNLOAD memory budget exhausted; retry with a smaller result", null);
                }
                heldMib++;
            }
        }

        private void writeCurrentSlice() throws IOException {
            byte[] payload = finishSlice(acc, sink);
            writePayload(payload, sliceIndex);
            sliceIndex++;
        }

        private void writePayload(byte[] payload, int index) {
            String key = unloadDataKey(spec, index);
            try {
                s3.authorizeAnonymousPutObject(spec.bucket(), key);
                s3.putObject(spec.bucket(), key, payload, contentType, Map.of());
            } catch (AwsException e) {
                fail(unloadWriteSqlState(e), unloadWriteMessage(e, spec), e);
            } catch (RuntimeException e) {
                String detail = e.getMessage() != null ? e.getMessage() : e.toString();
                fail(SQLSTATE_INTERNAL, "UNLOAD failed: " + detail, e);
            }
            writtenKeys.add(key);
            writtenLengths.add(payload.length);
        }

        private void resetSlice() throws IOException {
            int release = heldMib - UNLOAD_INITIAL_MIB;
            if (release > 0) {
                UNLOAD_HEAP_MIB.release(release);
                heldMib = UNLOAD_INITIAL_MIB;
            }
            rawSlice = 0;
            slicePayload = 0;
            lastByteNewline = true;
            sink = new ByteArrayOutputStream();
            acc = newAccumulator();
        }

        private OutputStream newAccumulator() throws IOException {
            return spec.gzip() ? new GZIPOutputStream(sink) : sink;
        }

        private void ensureOpen() {
            if (finished || aborted || closed) {
                throw new IllegalStateException("UNLOAD collector is no longer open");
            }
        }

        private void fail(String sqlState, String message, Throwable cause) {
            abort();
            throw new S3TransferException(sqlState, message, cause);
        }
    }

    /** {@code 404} from the S3 layer means the bucket is absent, not that access was denied. */
    private static String unloadWriteSqlState(AwsException e) {
        return e.getHttpStatus() == 404 ? SQLSTATE_INTERNAL : SQLSTATE_INSUFFICIENT_PRIVILEGE;
    }

    private static String unloadWriteMessage(AwsException e, CopyStatementParser.S3Unload spec) {
        return e.getHttpStatus() == 404
                ? "S3 bucket s3://" + spec.bucket() + " not found"
                : "S3 access denied for s3://" + spec.bucket() + "/" + spec.prefix();
    }

    private static String unloadDataKey(CopyStatementParser.S3Unload spec, int index) {
        String base = spec.parallel()
                ? spec.prefix() + String.format("%04d_part_00", index)
                : spec.prefix() + String.format("%03d", index);
        return spec.gzip() ? base + ".gz" : base;
    }

    private static byte[] finishSlice(OutputStream acc, ByteArrayOutputStream sink) throws IOException {
        if (acc != sink) {
            acc.close(); // flush the GZIP trailer
        }
        return sink.toByteArray();
    }

    private static void closeAcc(OutputStream acc, ByteArrayOutputStream sink) {
        if (acc != sink) {
            try {
                acc.close();
            } catch (IOException e) {
                LOG.debugv(e, "error closing the UNLOAD accumulation stream");
            }
        }
    }

    private static boolean abortUnload(Socket client, Socket backend, PostgresWireDecoder backendDecoder,
            UnloadCollector collector, String sqlState, String message,
            char txStatus, IntConsumer onStatusChange) throws IOException {
        collector.abort();
        try {
            drainBackendDiscarding(backendDecoder);
        } catch (IOException backendGone) {
            LOG.debugv(backendGone, "backend unreachable while draining an aborted UNLOAD; synthesizing client error");
            closeQuietly(backend);
            sendError(client, null, sqlState, message, txStatus, onStatusChange);
            closeQuietly(client);
            return true;
        }
        sendError(client, backend, sqlState, message, txStatus, onStatusChange);
        return true;
    }

    /** Read backend messages, forwarding nothing, until its ReadyForQuery or EOF. Leaves the backend socket clean. */
    private static void drainBackendDiscarding(PostgresWireDecoder decoder) throws IOException {
        PostgresWireDecoder.FrontendMessage m;
        while ((m = decoder.nextMessage()) != null) {
            if (m.type() == 'Z') {
                return;
            }
        }
    }

    private static void deleteWritten(S3Service s3, CopyStatementParser.S3Unload spec, List<String> keys) {
        if (spec.allowOverwrite()) {
            // Under ALLOWOVERWRITE a slice key may have replaced a pre-existing object, and there is no
            // reliable way to tell an overwrite from a fresh write (the check and the write are not
            // atomic, and a concurrent UNLOAD to the same prefix could own the stored object). Rather
            // than risk deleting data this operation did not create, leave every written object in
            // place. A failed MANIFEST + ALLOWOVERWRITE UNLOAD can therefore leave valid data objects
            // without a manifest; rerunning the same statement overwrites them cleanly.
            return;
        }
        // Non-overwrite runs verified the prefix was empty before streaming, so every written key was
        // created by this operation and is safe to remove.
        for (String k : keys) {
            try {
                s3.authorizeAnonymousDeleteObject(spec.bucket(), k);
                s3.deleteObject(spec.bucket(), k);
            } catch (RuntimeException e) {
                LOG.debugv(e, "could not remove partial UNLOAD object s3://{0}/{1}", spec.bucket(), k);
            }
        }
    }

    private static String manifestJson(String bucket, List<String> keys, List<Integer> lengths) {
        StringBuilder json = new StringBuilder("{\"entries\":[");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"url\":\"s3://").append(escapeJson(bucket)).append('/').append(escapeJson(keys.get(i)))
                    .append("\",\"meta\":{\"content_length\":").append(lengths.get(i)).append("}}");
        }
        return json.append("]}").toString();
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
