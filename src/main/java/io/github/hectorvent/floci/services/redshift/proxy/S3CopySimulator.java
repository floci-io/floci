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
        try {
            s3.authorizeAnonymousListBucket(spec.bucket());
        } catch (AwsException e) {
            LOG.debugv(e, "ListBucket denied for COPY from s3://{0}/{1}", spec.bucket(), spec.keyOrPrefix());
            sendAccessDenied(client, backend, spec, txStatus, onStatusChange);
            return true;
        }

        List<String> keys;
        try {
            keys = resolveKeys(spec, s3);
        } catch (AwsException e) {
            LOG.warnv(e, "failed to list s3://{0}/{1} for COPY", spec.bucket(), spec.keyOrPrefix());
            sendError(client, backend, SQLSTATE_INTERNAL,
                    "S3 COPY could not list s3://" + spec.bucket() + "/" + spec.keyOrPrefix(), txStatus, onStatusChange);
            return true;
        }

        try {
            for (String key : keys) {
                s3.authorizeAnonymousGetObject(spec.bucket(), key);
            }
        } catch (AwsException e) {
            LOG.debugv(e, "GetObject denied for COPY from s3://{0}/{1}", spec.bucket(), spec.keyOrPrefix());
            sendAccessDenied(client, backend, spec, txStatus, onStatusChange);
            return true;
        }

        if (keys.isEmpty()) {
            sendError(client, backend, SQLSTATE_INTERNAL,
                    "S3 object s3://" + spec.bucket() + "/" + spec.keyOrPrefix() + " not found", txStatus, onStatusChange);
            return true;
        }

        OutputStream backendOut = backend.getOutputStream();
        backendOut.write(PostgresWireDecoder.encodeQuery(fabricateCopy(spec)));
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
            streamObjects(spec, s3, keys, backendOut);
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

    private static void sendAccessDenied(Socket client, Socket backend, CopyStatementParser.S3CopyFrom spec,
                                         char txStatus, IntConsumer onStatusChange) throws IOException {
        sendError(client, backend, SQLSTATE_INSUFFICIENT_PRIVILEGE,
                "S3 access denied for s3://" + spec.bucket() + "/" + spec.keyOrPrefix(), txStatus, onStatusChange);
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

        String probeKey = unloadDataKey(spec, 0);
        String manifestKey = spec.prefix() + "manifest";
        try {
            s3.authorizeAnonymousPutObject(spec.bucket(), probeKey);
            if (spec.manifest()) {
                s3.authorizeAnonymousPutObject(spec.bucket(), manifestKey);
            }
        } catch (AwsException e) {
            LOG.debugv(e, "PutObject denied for UNLOAD to s3://{0}/{1}", spec.bucket(), spec.prefix());
            sendError(client, backend, unloadWriteSqlState(e), unloadWriteMessage(e, spec), txStatus, onStatusChange);
            return true;
        }

        try {
            if (!spec.allowOverwrite()) {
                s3.authorizeAnonymousListBucket(spec.bucket());
                if (targetPrefixHasObjects(spec, s3)) {
                    sendError(client, backend, SQLSTATE_INTERNAL,
                            "S3 prefix s3://" + spec.bucket() + "/" + spec.prefix()
                                    + " is not empty; specify ALLOWOVERWRITE to overwrite", txStatus, onStatusChange);
                    return true;
                }
            }
        } catch (AwsException e) {
            LOG.debugv(e, "bucket check failed for UNLOAD to s3://{0}/{1}", spec.bucket(), spec.prefix());
            sendError(client, backend, unloadWriteSqlState(e), unloadWriteMessage(e, spec), txStatus, onStatusChange);
            return true;
        }

        if (!UNLOAD_HEAP_MIB.tryAcquire(UNLOAD_INITIAL_MIB)) {
            sendError(client, backend, SQLSTATE_CONFIGURATION_LIMIT_EXCEEDED,
                    "UNLOAD memory budget exhausted; retry shortly", txStatus, onStatusChange);
            return true;
        }
        int[] heldMib = {UNLOAD_INITIAL_MIB};
        try {
            return runUnloadStreaming(client, backend, spec, s3, txStatus, onStatusChange, heldMib);
        } finally {
            UNLOAD_HEAP_MIB.release(heldMib[0]);
        }
    }

    private static boolean runUnloadStreaming(Socket client, Socket backend,
            CopyStatementParser.S3Unload spec, S3Service s3, char txStatus,
            IntConsumer onStatusChange, int[] heldMib) throws IOException {

        PostgresWireDecoder.FrontendMessage cmdComplete = null;
        PostgresWireDecoder.FrontendMessage readyForQuery = null;

        OutputStream backendOut = backend.getOutputStream();
        backendOut.write(PostgresWireDecoder.encodeQuery(fabricateUnloadCopy(spec)));
        backendOut.flush();

        PostgresWireDecoder backendDecoder = new PostgresWireDecoder(backend.getInputStream());
        PostgresWireDecoder.FrontendMessage first;
        try {
            first = nextNonAsync(backendDecoder, client);
        } catch (IOException e) {
            LOG.warnv(e, "backend read failed while awaiting CopyOutResponse");
            closeQuietly(backend);
            sendError(client, null, SQLSTATE_INTERNAL,
                    "UNLOAD failed: backend closed or timed out", txStatus, onStatusChange);
            closeQuietly(client);
            return true;
        }
        if (first == null) {
            closeQuietly(backend);
            sendError(client, null, SQLSTATE_INTERNAL,
                    "UNLOAD failed: backend closed before COPY started", txStatus, onStatusChange);
            closeQuietly(client);
            return true;
        }
        if (first.type() != 'H') {
            // Backend rejected the SELECT itself. Its ErrorResponse + ReadyForQuery are the client's one response.
            forward(client, first);
            drainToReadyForQuery(backendDecoder, client, onStatusChange);
            return true;
        }

        long threshold = spec.maxFileSizeBytes() > 0 ? spec.maxFileSizeBytes() : UNLOAD_TARGET_FILE_BYTES;
        String contentType = spec.gzip() ? "application/gzip" : "text/plain";
        List<String> writtenKeys = new ArrayList<>();
        List<Integer> writtenLengths = new ArrayList<>();

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        OutputStream acc = spec.gzip() ? new GZIPOutputStream(sink) : sink;
        long rawSlice = 0;
        // Data-row bytes in the current slice, excluding a repeated header. Drives the size split so a
        // header-only slice is never cut off on its own.
        long slicePayload = 0;
        long rawTotal = 0;
        boolean lastByteNewline = true;
        boolean warned = false;
        int sliceIndex = 0;

        // With HEADER the backend emits the header row once, at the top of the stream. Capture it so
        // it can be repeated at the start of every later slice, the way real Redshift writes one
        // header per output file.
        ByteArrayOutputStream headerBuf = spec.header() ? new ByteArrayOutputStream() : null;
        byte[] headerBytes = null;
        boolean capturingHeader = spec.header();

        try {
            while (true) {
                PostgresWireDecoder.FrontendMessage m = backendDecoder.nextMessage();
                if (m == null) {
                    closeAcc(acc, sink);
                    closeQuietly(backend);
                    if (spec.manifest()) {
                        deleteWritten(s3, spec, writtenKeys);
                    }
                    sendError(client, null, SQLSTATE_INTERNAL,
                            "UNLOAD failed: backend closed mid-stream", txStatus, onStatusChange);
                    closeQuietly(client);
                    return true;
                }
                char t = m.type();
                if (t == 'd') {
                    byte[] body = m.body();
                    int dataStart = 0;
                    if (capturingHeader) {
                        int nl = -1;
                        for (int i = 0; i < body.length; i++) {
                            if (body[i] == '\n') {
                                nl = i;
                                break;
                            }
                        }
                        int end = nl >= 0 ? nl + 1 : body.length;
                        headerBuf.write(body, 0, end);
                        if (nl >= 0) {
                            headerBytes = headerBuf.toByteArray();
                            headerBuf = null;
                            capturingHeader = false;
                        }
                        dataStart = end;
                    } else if (headerBytes != null && sliceIndex > 0 && rawSlice == 0) {
                        // First data of a later slice: repeat the captured header row.
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
                    int wantMib = (int) ((rawSlice * 3) / (1024 * 1024)) + 1;
                    while (heldMib[0] < wantMib) {
                        if (!UNLOAD_HEAP_MIB.tryAcquire(1)) {
                            return abortUnload(client, backend, backendDecoder, acc, sink,
                                    SQLSTATE_CONFIGURATION_LIMIT_EXCEEDED,
                                    "UNLOAD memory budget exhausted; retry with a smaller result",
                                    txStatus, onStatusChange);
                        }
                        heldMib[0]++;
                    }
                    if (rawTotal > UNLOAD_MAX_TOTAL_BYTES) {
                        closeAcc(acc, sink);
                        closeQuietly(backend);
                        if (spec.manifest()) {
                            deleteWritten(s3, spec, writtenKeys);
                        }
                        sendError(client, null,
                                SQLSTATE_PROGRAM_LIMIT_EXCEEDED,
                                "UNLOAD result exceeds the " + UNLOAD_MAX_TOTAL_BYTES + "-byte limit",
                                txStatus, onStatusChange);
                        closeQuietly(client);
                        return true;
                    }
                    if (slicePayload >= threshold && lastByteNewline) {
                        byte[] payload = finishSlice(acc, sink);
                        String key = unloadDataKey(spec, sliceIndex);
                        try {
                            s3.authorizeAnonymousPutObject(spec.bucket(), key);
                            s3.putObject(spec.bucket(), key, payload, contentType, Map.of());
                        } catch (AwsException e) {
                            LOG.debugv(e, "UNLOAD slice write to s3://{0}/{1} failed", spec.bucket(), key);
                            if (spec.manifest()) {
                                deleteWritten(s3, spec, writtenKeys);
                            }
                            return abortUnload(client, backend, backendDecoder, sink, sink,
                                    unloadWriteSqlState(e), unloadWriteMessage(e, spec), txStatus, onStatusChange);
                        }
                        writtenKeys.add(key);
                        writtenLengths.add(payload.length);
                        sliceIndex++;
                        int release = heldMib[0] - UNLOAD_INITIAL_MIB;
                        if (release > 0) {
                            UNLOAD_HEAP_MIB.release(release);
                            heldMib[0] = UNLOAD_INITIAL_MIB;
                        }
                        rawSlice = 0;
                        slicePayload = 0;
                        lastByteNewline = true;
                        sink = new ByteArrayOutputStream();
                        acc = spec.gzip() ? new GZIPOutputStream(sink) : sink;
                    }
                } else if (t == 'c') {
                    // CopyDone, ignore
                } else if (t == 'C') {
                    // CommandComplete, hold until we see 'Z'
                    cmdComplete = m;
                } else if (t == 'Z') {
                    readyForQuery = m;
                    break;
                } else if (t == 'E') {
                    closeAcc(acc, sink);
                    if (spec.manifest()) {
                        deleteWritten(s3, spec, writtenKeys);
                    }
                    forward(client, m);
                    drainToReadyForQuery(backendDecoder, client, onStatusChange);
                    return true;
                } else if (t == 'N' || t == 'A' || t == 'S') {
                    forward(client, m);
                }
            }
        } catch (AwsException e) {
            LOG.debugv(e, "UNLOAD S3 operation failed during streaming");
            if (spec.manifest()) {
                deleteWritten(s3, spec, writtenKeys);
            }
            return abortUnload(client, backend, backendDecoder, acc, sink,
                    unloadWriteSqlState(e), unloadWriteMessage(e, spec), txStatus, onStatusChange);
        } catch (RuntimeException | IOException e) {
            LOG.warnv(e, "UNLOAD streaming failed");
            if (spec.manifest()) {
                deleteWritten(s3, spec, writtenKeys);
            }
            String detail = e.getMessage() != null ? e.getMessage() : e.toString();
            return abortUnload(client, backend, backendDecoder, acc, sink,
                    SQLSTATE_INTERNAL, "UNLOAD failed: " + detail, txStatus, onStatusChange);
        }

        // Flush the final slice. Write it when it carries rows, or when nothing has been written yet
        // (a zero-row UNLOAD still emits one object, header only when HEADER was asked for). A result
        // that ended exactly on a slice boundary leaves slicePayload == 0 with slices already written,
        // so neither an empty nor a header-only trailer is produced.
        byte[] payload = finishSlice(acc, sink);
        if (slicePayload > 0 || writtenKeys.isEmpty()) {
            String key = unloadDataKey(spec, sliceIndex);
            try {
                s3.authorizeAnonymousPutObject(spec.bucket(), key);
                s3.putObject(spec.bucket(), key, payload, contentType, Map.of());
            } catch (AwsException e) {
                LOG.debugv(e, "UNLOAD final slice write to s3://{0}/{1} failed", spec.bucket(), key);
                if (spec.manifest()) {
                    deleteWritten(s3, spec, writtenKeys);
                }
                sendError(client, backend, unloadWriteSqlState(e), unloadWriteMessage(e, spec),
                        txStatus, onStatusChange);
                return true;
            } catch (RuntimeException e) {
                LOG.warnv(e, "UNLOAD final slice write failed");
                if (spec.manifest()) {
                    deleteWritten(s3, spec, writtenKeys);
                }
                String detail = e.getMessage() != null ? e.getMessage() : e.toString();
                sendError(client, backend, SQLSTATE_INTERNAL, "UNLOAD failed: " + detail, txStatus, onStatusChange);
                return true;
            }
            writtenKeys.add(key);
            writtenLengths.add(payload.length);
        }

        if (spec.manifest()) {
            try {
                s3.authorizeAnonymousPutObject(spec.bucket(), spec.prefix() + "manifest");
                s3.putObject(spec.bucket(), spec.prefix() + "manifest",
                        manifestJson(spec.bucket(), writtenKeys, writtenLengths).getBytes(StandardCharsets.UTF_8),
                        "application/json", Map.of());
            } catch (RuntimeException e) {
                LOG.warnv(e, "UNLOAD manifest write failed; removing partial data objects");
                deleteWritten(s3, spec, writtenKeys);
                sendError(client, backend, SQLSTATE_INTERNAL, "UNLOAD manifest write failed", txStatus, onStatusChange);
                return true;
            }
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
            OutputStream acc, ByteArrayOutputStream sink, String sqlState, String message,
            char txStatus, IntConsumer onStatusChange) throws IOException {
        closeAcc(acc, sink);
        try {
            drainBackendDiscarding(backendDecoder);
        } catch (IOException backendGone) {
            // The backend is unreachable, so it cannot be resynchronized: close it and hand the
            // client its one response synthesized, exactly as the backend-EOF branch does.
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
