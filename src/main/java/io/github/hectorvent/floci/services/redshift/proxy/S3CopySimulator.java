package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class S3CopySimulator {

    private static final Logger LOG = Logger.getLogger(S3CopySimulator.class);

    private static final long UNLOAD_BUFFER_WARN_BYTES = 256L * 1024 * 1024;

    public static void runCopyFrom(Socket client, Socket backend, CopyStatementParser.S3CopyFrom spec, S3Service s3) throws IOException {
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
        PostgresWireDecoder.FrontendMessage copyInResp = backendDecoder.nextMessage();
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

        sendQuery(backend, sql.toString());

        PostgresWireDecoder backendDecoder = new PostgresWireDecoder(backend.getInputStream());
        PostgresWireDecoder.FrontendMessage copyOutResp = backendDecoder.nextMessage();
        if (copyOutResp == null) {
            client.close();
            return;
        }
        if (copyOutResp.type() != 'H') {
            forwardMessage(client, copyOutResp);
            drainToReadyForQuery(backendDecoder, client);
            return;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PostgresWireDecoder.FrontendMessage msg;
        PostgresWireDecoder.FrontendMessage cmdCompleteMsg = null;
        PostgresWireDecoder.FrontendMessage readyForQueryMsg = null;
        boolean warnedOversize = false;
        while ((msg = backendDecoder.nextMessage()) != null) {
            if (msg.type() == 'd') {
                baos.write(msg.body(), 0, msg.body().length);
                if (!warnedOversize && baos.size() > UNLOAD_BUFFER_WARN_BYTES) {
                    warnedOversize = true;
                    LOG.warnv("UNLOAD result set exceeded {0} bytes and is buffered fully in memory "
                            + "(bucket={1}, prefix={2})", UNLOAD_BUFFER_WARN_BYTES, spec.bucket(), spec.prefix());
                }
            } else if (msg.type() == 'c') {
                // CopyDone
            } else if (msg.type() == 'C') {
                cmdCompleteMsg = msg;
            } else if (msg.type() == 'Z') {
                readyForQueryMsg = msg;
                break;
            } else if (msg.type() == 'E') {
                forwardMessage(client, msg);
                drainToReadyForQuery(backendDecoder, client);
                return;
            }
        }

        byte[] payload = baos.toByteArray();
        if (spec.gzip()) {
            ByteArrayOutputStream gzOs = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(gzOs)) {
                gzip.write(payload);
            }
            payload = gzOs.toByteArray();
        }

        String key = spec.prefix() + "000";
        s3.putObject(spec.bucket(), key, payload, "application/octet-stream", null);

        if (spec.manifest()) {
            String manifestJson = "{\"entries\":[{\"url\":\"s3://" + spec.bucket() + "/" + key + "\",\"meta\":{\"content_length\":" + payload.length + "}}]}";
            s3.putObject(spec.bucket(), spec.prefix() + "manifest", manifestJson.getBytes(StandardCharsets.UTF_8), "application/json", null);
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

    private static String escapeSqlString(String s) {
        return s.replace("'", "''");
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
