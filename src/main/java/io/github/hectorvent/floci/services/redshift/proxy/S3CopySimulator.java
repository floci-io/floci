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
 * Mô phỏng {@code COPY <table> FROM 's3://bucket/keyOrPrefix'} bằng cách đọc đối tượng S3
 * qua {@link S3Service} và stream sang PostgreSQL backend qua luồng {@code COPY <table> FROM STDIN}.
 */
public final class S3CopySimulator {

    private static final Logger LOG = Logger.getLogger(S3CopySimulator.class);

    private static final int MAX_KEYS = 10_000;
    private static final int CHUNK = 8192;

    private static final String SQLSTATE_INSUFFICIENT_PRIVILEGE = "42501";
    private static final String SQLSTATE_INTERNAL = "XX000";

    private S3CopySimulator() {
    }

    public static boolean runCopyFrom(Socket client, Socket backend,
                                      CopyStatementParser.S3CopyFrom spec, S3Service s3) throws IOException {
        try {
            s3.authorizeAnonymousListBucket(spec.bucket());
        } catch (AwsException e) {
            sendAccessDenied(client, spec);
            return true;
        }

        List<String> keys;
        try {
            keys = resolveKeys(spec, s3);
        } catch (AwsException e) {
            sendError(client, SQLSTATE_INTERNAL, "S3 error: " + e.getMessage());
            return true;
        }

        try {
            for (String key : keys) {
                s3.authorizeAnonymousGetObject(spec.bucket(), key);
            }
        } catch (AwsException e) {
            sendAccessDenied(client, spec);
            return true;
        }

        if (keys.isEmpty()) {
            sendError(client, SQLSTATE_INTERNAL,
                    "S3 object s3://" + spec.bucket() + "/" + spec.keyOrPrefix() + " not found");
            return true;
        }

        OutputStream backendOut = backend.getOutputStream();
        backendOut.write(PostgresWireDecoder.encodeQuery(fabricateCopy(spec)));
        backendOut.flush();

        PostgresWireDecoder backendDecoder = new PostgresWireDecoder(backend.getInputStream());
        PostgresWireDecoder.FrontendMessage first = nextNonAsync(backendDecoder);
        if (first == null) {
            LOG.warn("backend closed before answering the fabricated COPY");
            return true;
        }
        if (first.type() != 'G') {
            forward(client, first);
            drainToReadyForQuery(backendDecoder, client);
            return true;
        }

        try {
            streamObjects(spec, s3, keys, backendOut);
        } catch (IOException e) {
            LOG.warnv(e, "failed while streaming S3 objects into COPY; aborting");
            writeCopyFail(backendOut, e.getMessage());
            drainToReadyForQuery(backendDecoder, client);
            sendError(client, SQLSTATE_INTERNAL, "S3 COPY failed: " + e.getMessage());
            return true;
        }

        writeCopyDone(backendOut);
        drainToReadyForQuery(backendDecoder, client);
        return true;
    }

    private static List<String> resolveKeys(CopyStatementParser.S3CopyFrom spec, S3Service s3) {
        List<String> keys = new ArrayList<>();
        if (s3.objectExists(spec.bucket(), spec.keyOrPrefix())) {
            keys.add(spec.keyOrPrefix());
            return keys;
        }
        List<S3Object> objects = s3.listObjects(spec.bucket(), spec.keyOrPrefix(), null, MAX_KEYS);
        if (objects != null) {
            for (S3Object object : objects) {
                keys.add(object.getKey());
            }
            keys.sort(String::compareTo);
        }
        return keys;
    }

    private static String fabricateCopy(CopyStatementParser.S3CopyFrom spec) {
        StringBuilder sql = new StringBuilder("COPY ").append(spec.targetTable());
        if (spec.columns() != null && !spec.columns().isEmpty()) {
            sql.append(" (").append(String.join(", ", spec.columns())).append(")");
        }
        sql.append(" FROM STDIN WITH (FORMAT ").append(spec.csv() ? "csv" : "text");
        String delimiter = spec.delimiter() != null ? spec.delimiter() : (spec.csv() ? "," : "|");
        sql.append(", DELIMITER '").append(escape(delimiter)).append("'");
        if (spec.nullAs() != null) {
            sql.append(", NULL '").append(escape(spec.nullAs())).append("'");
        }
        sql.append(")");
        return sql.toString();
    }

    private static String escape(String value) {
        return value.replace("'", "''").replace("\\", "\\\\");
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

    private static PostgresWireDecoder.FrontendMessage nextNonAsync(PostgresWireDecoder decoder) throws IOException {
        while (true) {
            PostgresWireDecoder.FrontendMessage message = decoder.nextMessage();
            if (message == null) {
                return null;
            }
            char type = message.type();
            if (type == 'N' || type == 'A' || type == 'S') {
                continue;
            }
            return message;
        }
    }

    private static void drainToReadyForQuery(PostgresWireDecoder decoder, Socket client) throws IOException {
        OutputStream clientOut = client.getOutputStream();
        while (true) {
            PostgresWireDecoder.FrontendMessage message = decoder.nextMessage();
            if (message == null) {
                return;
            }
            clientOut.write(message.toPacketBytes());
            clientOut.flush();
            if (message.type() == 'Z') {
                return;
            }
        }
    }

    private static void forward(Socket client, PostgresWireDecoder.FrontendMessage message) throws IOException {
        client.getOutputStream().write(message.toPacketBytes());
        client.getOutputStream().flush();
    }

    private static void sendAccessDenied(Socket client, CopyStatementParser.S3CopyFrom spec) throws IOException {
        sendError(client, SQLSTATE_INSUFFICIENT_PRIVILEGE,
                "S3 access denied for s3://" + spec.bucket() + "/" + spec.keyOrPrefix());
    }

    private static void sendError(Socket client, String sqlState, String message) throws IOException {
        OutputStream out = client.getOutputStream();
        byte[] body = errorBody(sqlState, message);
        out.write('E');
        out.write(intBytes(4 + body.length));
        out.write(body);
        out.write(new byte[]{'Z', 0, 0, 0, 5, 'I'});
        out.flush();
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
