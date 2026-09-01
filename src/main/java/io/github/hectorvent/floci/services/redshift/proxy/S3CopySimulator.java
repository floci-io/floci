package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class S3CopySimulator {

    private static final Logger LOG = Logger.getLogger(S3CopySimulator.class);

    private static final long ONE_MIB = 1024L * 1024;
    /** Log a warning once the (uncompressed) UNLOAD result passes this size. */
    private static final long UNLOAD_WARN_BYTES = 32 * ONE_MIB;
    /**
     * Hard cap on a single (uncompressed) UNLOAD result. Past this the transfer is aborted:
     * the whole result is buffered and copied once more for {@code putObject}.
     */
    private static final long UNLOAD_MAX_RESULT_BYTES = 64 * ONE_MIB;
    /**
     * Shared budget, in MiB, for UNLOAD accumulation buffers across <em>all</em> connections, so
     * concurrent operations cannot multiply the per-operation cost without bound. An UNLOAD that
     * cannot claim enough budget is aborted with {@link #SQLSTATE_CONFIGURATION_LIMIT_EXCEEDED}.
     */
    private static final Semaphore UNLOAD_HEAP_MIB = new Semaphore(192);

    /** Postgres SQLSTATE for insufficient_privilege — surfaced to the client when S3 authorization denies. */
    private static final String SQLSTATE_INSUFFICIENT_PRIVILEGE = "42501";
    /** Postgres SQLSTATE program_limit_exceeded — surfaced when the UNLOAD result is too large. */
    private static final String SQLSTATE_PROGRAM_LIMIT_EXCEEDED = "54000";
    /** Postgres SQLSTATE configuration_limit_exceeded — surfaced when too many UNLOADs run at once. */
    private static final String SQLSTATE_CONFIGURATION_LIMIT_EXCEEDED = "53400";

    public static void runCopyFrom(Socket client, Socket backend, CopyStatementParser.S3CopyFrom spec, S3Service s3) throws IOException {
        // Authorize S3 access with an unsigned identity — a Postgres session carries no AWS
        // principal. With enforceAuth off this is a no-op; with it on, bucket policy / public
        // access config decides, exactly as an unauthenticated S3 request would be judged.
        try {
            s3.authorizeAnonymousListBucket(spec.bucket());
        } catch (AwsException e) {
            sendErrorResponse(client, SQLSTATE_INSUFFICIENT_PRIVILEGE,
                    "S3 access denied for s3://" + spec.bucket() + "/" + spec.keyOrPrefix());
            sendReadyForQuery(client);
            return;
        }

        List<String> objectKeys = new ArrayList<>();
        // Exact key first; if it is not an object, treat the value as a prefix and
        // concatenate every object under it in key order. objectExists() never throws
        // for a missing key or bucket, so an unknown bucket falls through to an empty
        // prefix listing and the clean ErrorResponse branch below.
        if (s3.objectExists(spec.bucket(), spec.keyOrPrefix())) {
            objectKeys.add(spec.keyOrPrefix());
        } else {
            List<S3Object> objs = s3.listObjects(spec.bucket(), spec.keyOrPrefix(), null, 10000);
            if (objs != null) {
                for (S3Object obj : objs) {
                    objectKeys.add(obj.getKey());
                }
            }
        }

        try {
            for (String key : objectKeys) {
                s3.authorizeAnonymousGetObject(spec.bucket(), key);
            }
        } catch (AwsException e) {
            sendErrorResponse(client, SQLSTATE_INSUFFICIENT_PRIVILEGE,
                    "S3 access denied for s3://" + spec.bucket() + "/" + spec.keyOrPrefix());
            sendReadyForQuery(client);
            return;
        }

        if (objectKeys.isEmpty()) {
            String msg = "S3 object s3://" + spec.bucket() + "/" + spec.keyOrPrefix() + " not found";
            sendErrorResponse(client, "XX000", msg);
            sendReadyForQuery(client);
            return;
        }

        StringBuilder sql = new StringBuilder("COPY ");
        sql.append(spec.targetTable());
        if (spec.columns() != null && !spec.columns().isEmpty()) {
            sql.append(" (").append(String.join(", ", spec.columns())).append(")");
        }
        sql.append(" FROM STDIN WITH (FORMAT csv");
        if (spec.delimiter() != null) {
            sql.append(", DELIMITER '").append(escapeSqlString(spec.delimiter())).append("'");
        }
        if (spec.header()) {
            sql.append(", HEADER true");
        }
        if (spec.nullAs() != null) {
            sql.append(", NULL '").append(escapeSqlString(spec.nullAs())).append("'");
        }
        sql.append(")");

        sendQuery(backend, sql.toString());

        PostgresWireDecoder backendDecoder = new PostgresWireDecoder(backend.getInputStream());
        PostgresWireDecoder.FrontendMessage copyInResp = nextNonAsync(backendDecoder, client);
        if (copyInResp == null) {
            client.close();
            return;
        }
        if (copyInResp.type() != 'G') {
            forwardMessage(client, copyInResp);
            drainToReadyForQuery(backendDecoder, client);
            return;
        }

        for (String key : objectKeys) {
            try (InputStream s3Stream = s3.openObjectStream(spec.bucket(), key, null);
                 InputStream in = spec.gzip() ? new GZIPInputStream(s3Stream) : s3Stream) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    sendCopyData(backend, buffer, read);
                }
            }
        }
        sendCopyDone(backend);

        while (true) {
            PostgresWireDecoder.FrontendMessage msg = backendDecoder.nextMessage();
            if (msg == null) {
                client.close();
                break;
            }
            forwardMessage(client, msg);
            if (msg.type() == 'Z') {
                break;
            }
        }
    }

    public static void runUnload(Socket client, Socket backend, CopyStatementParser.S3Unload spec, S3Service s3) throws IOException {
        String dataKey = spec.prefix() + "000";
        String manifestKey = spec.prefix() + "manifest";
        // Reject before running the SELECT if any target key is not writable (see runCopyFrom).
        // Every key that will be written is authorized here — including the manifest.
        try {
            s3.authorizeAnonymousPutObject(spec.bucket(), dataKey);
            if (spec.manifest()) {
                s3.authorizeAnonymousPutObject(spec.bucket(), manifestKey);
            }
        } catch (AwsException e) {
            sendErrorResponse(client, SQLSTATE_INSUFFICIENT_PRIVILEGE,
                    "S3 access denied for s3://" + spec.bucket() + "/" + spec.prefix());
            sendReadyForQuery(client);
            return;
        }

        // Claim an initial slice of the shared accumulation budget up front; more is taken as the
        // result grows (see runUnloadBuffered). We account 3x to cover the ByteArrayOutputStream
        // internal buffer growth (which can double capacity) and the toByteArray() payload copy.
        // Everything claimed is released in the finally.
        int initialMib = 12;
        if (!UNLOAD_HEAP_MIB.tryAcquire(initialMib)) {
            sendErrorResponse(client, SQLSTATE_CONFIGURATION_LIMIT_EXCEEDED,
                    "UNLOAD memory budget exhausted; retry shortly");
            sendReadyForQuery(client);
            return;
        }
        int[] heldMib = {initialMib};
        try {
            runUnloadBuffered(client, backend, spec, s3, dataKey, manifestKey, heldMib);
        } finally {
            UNLOAD_HEAP_MIB.release(heldMib[0]);
        }
    }

    private static void runUnloadBuffered(Socket client, Socket backend, CopyStatementParser.S3Unload spec,
                                          S3Service s3, String dataKey, String manifestKey, int[] heldMib) throws IOException {
        StringBuilder sql = new StringBuilder("COPY (");
        sql.append(spec.selectQuery());
        sql.append(") TO STDOUT WITH (FORMAT csv");
        if (spec.delimiter() != null) {
            sql.append(", DELIMITER '").append(escapeSqlString(spec.delimiter())).append("'");
        }
        if (spec.header()) {
            sql.append(", HEADER true");
        }
        if (spec.addQuotes()) {
            sql.append(", FORCE_QUOTE *");
        }
        if (spec.nullAs() != null) {
            sql.append(", NULL '").append(escapeSqlString(spec.nullAs())).append("'");
        }
        sql.append(")");

        LOG.infov("UNLOAD -> backend: {0}", sql);
        sendQuery(backend, sql.toString());

        PostgresWireDecoder backendDecoder = new PostgresWireDecoder(backend.getInputStream());
        PostgresWireDecoder.FrontendMessage copyOutResp = nextNonAsync(backendDecoder, client);
        if (copyOutResp == null) {
            client.close();
            return;
        }
        if (copyOutResp.type() != 'H') {
            LOG.infov("UNLOAD: first backend reply was ''{0}'' (not CopyOutResponse ''H''); forwarding and aborting",
                    copyOutResp.type());
            forwardMessage(client, copyOutResp);
            drainToReadyForQuery(backendDecoder, client);
            return;
        }

        // Accumulate (already GZIP-compressed on the fly when requested) into a single buffer —
        // no second full-size copy for a separate compression pass.
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        OutputStream acc = spec.gzip() ? new GZIPOutputStream(sink) : sink;
        long rawBytes = 0;
        boolean warnedOversize = false;
        PostgresWireDecoder.FrontendMessage msg;
        PostgresWireDecoder.FrontendMessage cmdCompleteMsg = null;
        PostgresWireDecoder.FrontendMessage readyForQueryMsg = null;
        try {
            while ((msg = backendDecoder.nextMessage()) != null) {
                if (msg.type() == 'd') {
                    acc.write(msg.body(), 0, msg.body().length);
                    rawBytes += msg.body().length;
                    if (!warnedOversize && rawBytes > UNLOAD_WARN_BYTES) {
                        warnedOversize = true;
                        LOG.warnv("UNLOAD result passed {0} bytes and is buffered fully in memory "
                                + "(bucket={1}, prefix={2})", UNLOAD_WARN_BYTES, spec.bucket(), spec.prefix());
                    }
                    // Grow the shared budget claim to match accumulator buffer expansion and payload copy (3x);
                    // abort if it (or the per-op cap) is exhausted, so concurrent UNLOADs cannot exhaust heap.
                    int wantMib = (int) ((rawBytes * 3) / ONE_MIB) + 1;
                    boolean overBudget = false;
                    while (heldMib[0] < wantMib) {
                        if (!UNLOAD_HEAP_MIB.tryAcquire(1)) {
                            overBudget = true;
                            break;
                        }
                        heldMib[0]++;
                    }
                    if (overBudget || rawBytes > UNLOAD_MAX_RESULT_BYTES) {
                        LOG.warnv("UNLOAD aborted at {0} bytes ({1}, bucket={2}, prefix={3})", rawBytes,
                                overBudget ? "shared budget exhausted" : "per-operation cap",
                                spec.bucket(), spec.prefix());
                        closeQuietly(acc);
                        sink = null; // release for GC before we block on drain
                        sendErrorResponse(client, SQLSTATE_PROGRAM_LIMIT_EXCEEDED,
                                overBudget ? "UNLOAD memory budget exhausted; retry with a smaller result"
                                        : "UNLOAD result exceeds the " + UNLOAD_MAX_RESULT_BYTES + "-byte limit");
                        drainToReadyForQuery(backendDecoder, client);
                        return;
                    }
                } else if (msg.type() == 'c') {
                    // CopyDone
                } else if (msg.type() == 'C') {
                    cmdCompleteMsg = msg;
                } else if (msg.type() == 'Z') {
                    readyForQueryMsg = msg;
                    break;
                } else if (msg.type() == 'E') {
                    closeQuietly(acc);
                    forwardMessage(client, msg);
                    drainToReadyForQuery(backendDecoder, client);
                    return;
                } else if (isAsync(msg.type())) {
                    // NoticeResponse / NotificationResponse / ParameterStatus can arrive mid-stream.
                    forwardMessage(client, msg);
                }
            }
        } catch (IOException e) {
            closeQuietly(acc);
            throw e;
        }
        if (acc != sink) {
            acc.close(); // flush the GZIP trailer
        }

        byte[] payload = sink.toByteArray();
        sink = null;
        acc = null;

        // Reauthorize all target keys before writing any output, ensuring atomic-like permission
        // validation so a denied manifest key does not leave an unmanifested data object committed in S3.
        try {
            s3.authorizeAnonymousPutObject(spec.bucket(), dataKey);
            if (spec.manifest()) {
                s3.authorizeAnonymousPutObject(spec.bucket(), manifestKey);
            }
        } catch (AwsException e) {
            sendErrorResponse(client, SQLSTATE_INSUFFICIENT_PRIVILEGE,
                    "S3 access denied for s3://" + spec.bucket() + "/" + spec.prefix());
            if (readyForQueryMsg != null) {
                forwardMessage(client, readyForQueryMsg);
            } else {
                sendReadyForQuery(client);
            }
            return;
        }

        LOG.infov("UNLOAD: {0} raw bytes over CopyData -> {1} bytes to s3://{2}/{3}",
                rawBytes, payload.length, spec.bucket(), dataKey);
        s3.putObject(spec.bucket(), dataKey, payload, "application/octet-stream", null);

        if (spec.manifest()) {
            try {
                String manifestJson = "{\"entries\":[{\"url\":\"s3://" + spec.bucket() + "/" + dataKey
                        + "\",\"meta\":{\"content_length\":" + payload.length + "}}]}";
                s3.putObject(spec.bucket(), manifestKey, manifestJson.getBytes(StandardCharsets.UTF_8),
                        "application/json", null);
            } catch (Exception e) {
                // If manifest creation fails, remove the orphaned data object so partial output is not left behind.
                try {
                    s3.deleteObject(spec.bucket(), dataKey);
                } catch (Exception ignored) {
                }
                throw e;
            }
        }

        if (cmdCompleteMsg != null) {
            forwardMessage(client, cmdCompleteMsg);
        }
        // Forward the backend's real ReadyForQuery so its transaction-status byte
        // ('I' idle / 'T' in-block / 'E' failed-block) reaches the driver intact.
        // Only fall back to a synthesised 'I' if the backend never sent one.
        if (readyForQueryMsg != null) {
            forwardMessage(client, readyForQueryMsg);
        } else {
            sendReadyForQuery(client);
        }
    }

    /**
     * After an error/unexpected reply on the injected COPY, forward the rest of the
     * backend's messages through its terminating {@code ReadyForQuery} so the backend
     * socket is left clean for the next client query and the driver sees the real
     * transaction status. Synthesise {@code 'Z' 'I'} only if the backend hung up first.
     */
    private static void drainToReadyForQuery(PostgresWireDecoder backendDecoder, Socket client) throws IOException {
        PostgresWireDecoder.FrontendMessage m;
        while ((m = backendDecoder.nextMessage()) != null) {
            forwardMessage(client, m);
            if (m.type() == 'Z') {
                return;
            }
        }
        sendReadyForQuery(client);
    }

    /**
     * A backend message that PostgreSQL may deliver at any time, unrelated to the injected
     * query: {@code 'N'} NoticeResponse, {@code 'A'} NotificationResponse (LISTEN/NOTIFY),
     * {@code 'S'} ParameterStatus.
     */
    private static boolean isAsync(char type) {
        return type == 'N' || type == 'A' || type == 'S';
    }

    /**
     * Read the next backend message, transparently forwarding any leading async messages to the
     * client so they are not mistaken for the injected query's first response.
     */
    private static PostgresWireDecoder.FrontendMessage nextNonAsync(
            PostgresWireDecoder backendDecoder, Socket client) throws IOException {
        PostgresWireDecoder.FrontendMessage m;
        while ((m = backendDecoder.nextMessage()) != null) {
            if (isAsync(m.type())) {
                forwardMessage(client, m);
                continue;
            }
            return m;
        }
        return null;
    }

    private static String escapeSqlString(String s) {
        return s.replace("'", "''");
    }

    /** Close a stream, swallowing the {@link IOException} — used only on error/abort paths. */
    private static void closeQuietly(OutputStream out) {
        try {
            out.close();
        } catch (IOException e) {
            LOG.debugv(e, "error closing the UNLOAD accumulation stream");
        }
    }

    private static void sendQuery(Socket backend, String sql) throws IOException {
        byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
        int len = 4 + sqlBytes.length + 1;
        byte[] packet = new byte[1 + len];
        packet[0] = 'Q';
        packet[1] = (byte) ((len >>> 24) & 0xFF);
        packet[2] = (byte) ((len >>> 16) & 0xFF);
        packet[3] = (byte) ((len >>> 8) & 0xFF);
        packet[4] = (byte) ((len >>> 0) & 0xFF);
        System.arraycopy(sqlBytes, 0, packet, 5, sqlBytes.length);
        packet[packet.length - 1] = 0;
        backend.getOutputStream().write(packet);
        backend.getOutputStream().flush();
    }

    private static void forwardMessage(Socket socket, PostgresWireDecoder.FrontendMessage msg) throws IOException {
        socket.getOutputStream().write(msg.toPacketBytes());
        socket.getOutputStream().flush();
    }

    private static void sendCopyData(Socket backend, byte[] buffer, int length) throws IOException {
        int len = 4 + length;
        byte[] packet = new byte[1 + len];
        packet[0] = 'd';
        packet[1] = (byte) ((len >>> 24) & 0xFF);
        packet[2] = (byte) ((len >>> 16) & 0xFF);
        packet[3] = (byte) ((len >>> 8) & 0xFF);
        packet[4] = (byte) ((len >>> 0) & 0xFF);
        System.arraycopy(buffer, 0, packet, 5, length);
        backend.getOutputStream().write(packet);
    }

    private static void sendCopyDone(Socket backend) throws IOException {
        int len = 4;
        byte[] packet = new byte[1 + len];
        packet[0] = 'c';
        packet[1] = (byte) ((len >>> 24) & 0xFF);
        packet[2] = (byte) ((len >>> 16) & 0xFF);
        packet[3] = (byte) ((len >>> 8) & 0xFF);
        packet[4] = (byte) ((len >>> 0) & 0xFF);
        backend.getOutputStream().write(packet);
        backend.getOutputStream().flush();
    }

    private static void sendErrorResponse(Socket client, String code, String message) throws IOException {
        byte[] codeBytes = code.getBytes(StandardCharsets.UTF_8);
        byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
        byte[] sBytes = "ERROR".getBytes(StandardCharsets.UTF_8);
        
        int len = 4 + 1 + sBytes.length + 1 + 1 + codeBytes.length + 1 + 1 + msgBytes.length + 1 + 1;
        byte[] packet = new byte[1 + len];
        packet[0] = 'E';
        packet[1] = (byte) ((len >>> 24) & 0xFF);
        packet[2] = (byte) ((len >>> 16) & 0xFF);
        packet[3] = (byte) ((len >>> 8) & 0xFF);
        packet[4] = (byte) ((len >>> 0) & 0xFF);
        
        int offset = 5;
        packet[offset++] = 'S';
        System.arraycopy(sBytes, 0, packet, offset, sBytes.length);
        offset += sBytes.length;
        packet[offset++] = 0;
        
        packet[offset++] = 'C';
        System.arraycopy(codeBytes, 0, packet, offset, codeBytes.length);
        offset += codeBytes.length;
        packet[offset++] = 0;
        
        packet[offset++] = 'M';
        System.arraycopy(msgBytes, 0, packet, offset, msgBytes.length);
        offset += msgBytes.length;
        packet[offset++] = 0;
        
        packet[offset++] = 0;
        
        client.getOutputStream().write(packet);
        client.getOutputStream().flush();
    }

    private static void sendReadyForQuery(Socket client) throws IOException {
        int len = 5;
        byte[] packet = new byte[1 + len];
        packet[0] = 'Z';
        packet[1] = 0;
        packet[2] = 0;
        packet[3] = 0;
        packet[4] = 5;
        packet[5] = 'I';
        client.getOutputStream().write(packet);
        client.getOutputStream().flush();
    }
}
