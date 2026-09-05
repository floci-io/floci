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
import java.util.zip.GZIPInputStream;

/**
 * Emulates {@code COPY <table> FROM 's3://bucket/keyOrPrefix'} by reading the S3 object (or every
 * object under the prefix, in key order) through {@link S3Service} and streaming it into the
 * backing PostgreSQL container over a fabricated {@code COPY <table> FROM STDIN} exchange.
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
                                      char txStatus, java.util.function.IntConsumer onStatusChange) throws IOException {
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
                                        char txStatus, java.util.function.IntConsumer onStatusChange) throws IOException {
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
                                            java.util.function.IntConsumer onStatusChange) throws IOException {
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
                                         char txStatus, java.util.function.IntConsumer onStatusChange) throws IOException {
        sendError(client, backend, SQLSTATE_INSUFFICIENT_PRIVILEGE,
                "S3 access denied for s3://" + spec.bucket() + "/" + spec.keyOrPrefix(), txStatus, onStatusChange);
    }

    private static void sendError(Socket client, Socket backend, String sqlState, String message,
                                  char txStatus, java.util.function.IntConsumer onStatusChange) throws IOException {
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

    private static boolean failBackendTransaction(Socket backend, java.util.function.IntConsumer onStatusChange) {
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
}
