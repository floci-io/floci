package io.floci.aws.services.redshift.proxy;

import io.floci.aws.core.common.PostgresWire;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * Frames the PostgreSQL wire-protocol byte stream sent by the frontend (client).
 *
 * <p>Every frontend message is {@code type(1 byte) · length(int32, includes itself) · body(length-4)}.
 * This decoder turns that stream into {@link FrontendMessage} records for
 * {@link RedshiftInterceptingBridge}, which forwards each message opaque except a Simple Query
 * ({@code 'Q'}): that one is rewritten via {@link RedshiftSqlInterceptor} and re-encoded with
 * {@link #encodeQuery(String)}.
 *
 * <p>The DDL path never reads the backend socket directly, so this decoder does no shared-heap
 * accounting: {@link #MAX_MESSAGE_BYTES} is the only guard, refusing an oversized declared length
 * before any body is read (the length field is attacker-controlled, up to ~2 GiB).
 */
public class PostgresWireDecoder {

    /** Largest single frontend message accepted; 16 MiB is far above any real SQL statement. */
    static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;

    private final InputStream in;
    private boolean betweenMessages = true;

    public PostgresWireDecoder(InputStream in) {
        this.in = Objects.requireNonNull(in, "in must not be null");
    }

    /**
     * {@code true} when the decoder is at a clean boundary between messages: before the first
     * byte of a message and after a full message has been returned; {@code false} once a type
     * byte has been consumed. Lets the bridge tell "client idle between queries" from "client
     * stalled mid-message" when a read times out.
     */
    public boolean isBetweenMessages() {
        return betweenMessages;
    }

    /**
     * Read the next frontend message.
     *
     * @return the message, or {@code null} on a clean end-of-stream between messages.
     * @throws EOFException if EOF is hit inside the length field or body.
     * @throws IOException  on an I/O error, a length below 4, or a length above {@link #MAX_MESSAGE_BYTES}.
     */
    public FrontendMessage nextMessage() throws IOException {
        return nextMessage(null, ignored -> true);
    }

    /**
     * Read the next frontend message.
     *
     * <p>When {@code passthroughOut} is non-null and the incoming message is not a Simple Query
     * ({@code 'Q'}), its frame is streamed directly to {@code passthroughOut} without heap buffering
     * or size caps, preserving transparent passthrough for large messages such as {@code CopyData}.
     *
     * @param passthroughOut destination for opaque non-query frames, or {@code null} to buffer
     * @return the message, or {@code null} on a clean end-of-stream between messages.
     * @throws EOFException if EOF is hit inside the length field or body.
     * @throws IOException  on an I/O error, a length below 4, or a length above {@link #MAX_MESSAGE_BYTES}.
     */
    public FrontendMessage nextMessage(OutputStream passthroughOut) throws IOException {
        return nextMessage(passthroughOut, type -> type == 'Q');
    }

    /**
     * Read the next frontend message, selecting which message types should be retained for
     * inspection. Unselected messages are streamed to {@code passthroughOut} when it is non-null.
     *
     * @param passthroughOut destination for opaque or oversized frames, or {@code null} to buffer
     * @param inspectType predicate identifying message types whose bodies should be retained
     * @return the message, or {@code null} on a clean end-of-stream between messages.
     * @throws EOFException if EOF is hit inside the length field or body.
     * @throws IOException on an I/O error, a length below 4, or a length above {@link #MAX_MESSAGE_BYTES}
     */
    public FrontendMessage nextMessage(OutputStream passthroughOut, IntPredicate inspectType) throws IOException {
        Objects.requireNonNull(inspectType, "inspectType must not be null");
        betweenMessages = true;

        int typeByte = in.read();
        if (typeByte == -1) {
            return null; // clean EOF between messages
        }
        betweenMessages = false;
        char type = (char) typeByte;

        byte[] lengthBytes = readFully(4);
        int length = PostgresWire.checkedLength(PostgresWire.int32(lengthBytes, 0), 4, Integer.MAX_VALUE,
                "frontend message");

        if (passthroughOut != null && (!inspectType.test(type) || length > MAX_MESSAGE_BYTES)) {
            passthroughOut.write(typeByte);
            passthroughOut.write(lengthBytes);
            int remaining = length - 4;
            if (remaining > 0) {
                byte[] buf = new byte[Math.min(remaining, 8192)];
                while (remaining > 0) {
                    int toRead = Math.min(remaining, buf.length);
                    int read = in.read(buf, 0, toRead);
                    if (read == -1) {
                        throw new EOFException("Unexpected EOF (expected " + (length - 4)
                                + " bytes, got " + (length - 4 - remaining) + ")");
                    }
                    passthroughOut.write(buf, 0, read);
                    remaining -= read;
                }
            }
            passthroughOut.flush();
            betweenMessages = true;
            return new FrontendMessage(type, null);
        }

        PostgresWire.checkedLength(length, 4, MAX_MESSAGE_BYTES, "frontend message");

        byte[] body = (length == 4) ? new byte[0] : readFully(length - 4);
        betweenMessages = true;
        return new FrontendMessage(type, body);
    }

    /** Decode a Parse ({@code 'P'}) message body. */
    public ParseMessage decodeParse(FrontendMessage message) throws IOException {
        BodyCursor cursor = cursorFor(message, 'P');
        String statementName = cursor.readCString();
        String sql = cursor.readCString();
        int parameterCount = cursor.readInt16();
        List<Integer> parameterTypeOids = new ArrayList<>(parameterCount);
        for (int i = 0; i < parameterCount; i++) {
            parameterTypeOids.add(cursor.readInt32());
        }
        cursor.requireEnd();
        return new ParseMessage(statementName, sql, List.copyOf(parameterTypeOids));
    }

    /** Decode the leading names of a Bind ({@code 'B'}) message body. */
    public BindMessage decodeBind(FrontendMessage message) throws IOException {
        BodyCursor cursor = cursorFor(message, 'B');
        return new BindMessage(cursor.readCString(), cursor.readCString());
    }

    /**
     * Values of a Bind's parameters without known type OIDs. Binary values and NULL remain unknown.
     * Only a statement that prunes needs them, so they are read apart from
     * {@link #decodeBind}. A malformed section gives an empty list: the backend still gets the original frame
     * and reports a malformed Bind itself, so it must not fail here.
     */
    public List<String> decodeBindParameters(FrontendMessage message) throws IOException {
        return decodeBindParameters(message, List.of());
    }

    /** Binary int2, int4 and int8 values need the types declared by Parse or inferred by the backend. */
    public List<String> decodeBindParameters(FrontendMessage message, List<Integer> parameterTypeOids) throws IOException {
        BodyCursor cursor = cursorFor(message, 'B');
        cursor.readCString();
        cursor.readCString();
        try {
            return readBindParameters(cursor, parameterTypeOids);
        } catch (IOException expected) {
            return List.of();
        }
    }

    private static List<String> readBindParameters(BodyCursor cursor, List<Integer> parameterTypeOids) throws IOException {
        int formatCount = cursor.readInt16();
        int[] formats = new int[formatCount];
        for (int i = 0; i < formatCount; i++) {
            formats[i] = cursor.readInt16();
        }
        int parameterCount = cursor.readInt16();
        if (formatCount > 1 && formatCount != parameterCount) {
            throw new IOException("Bind has " + formatCount + " parameter format codes for " + parameterCount
                    + " parameters");
        }
        List<String> values = new ArrayList<>(parameterCount);
        for (int i = 0; i < parameterCount; i++) {
            int length = cursor.readInt32();
            if (length < 0) {
                values.add(null);
                continue;
            }
            // zero format codes mean every parameter is text, one applies to all, otherwise one per parameter
            int format = formatCount == 0 ? 0 : formatCount == 1 ? formats[0] : formats[i];
            if (format == 0) {
                values.add(new String(cursor.readBytes(length), StandardCharsets.UTF_8));
            } else if (format == 1) {
                int oid = i < parameterTypeOids.size() ? parameterTypeOids.get(i) : 0;
                values.add(readBinaryInteger(cursor, oid, length));
            } else {
                cursor.skip(length);
                values.add(null);
            }
        }
        return values;
    }

    private static String readBinaryInteger(BodyCursor cursor, int oid, int length) throws IOException {
        int width = switch (oid) {
            case 21 -> Short.BYTES;
            case 23 -> Integer.BYTES;
            case 20 -> Long.BYTES;
            default -> 0;
        };
        if (width == 0 || length != width) {
            cursor.skip(length);
            return null;
        }
        ByteBuffer bytes = ByteBuffer.wrap(cursor.readBytes(length));
        long value = switch (width) {
            case Short.BYTES -> bytes.getShort();
            case Integer.BYTES -> bytes.getInt();
            case Long.BYTES -> bytes.getLong();
            default -> throw new IOException("Unsupported binary integer width");
        };
        return Long.toString(value);
    }

    static List<Integer> decodeParameterDescription(byte[] body) throws IOException {
        BodyCursor cursor = new BodyCursor(body);
        int count = cursor.readInt16();
        List<Integer> types = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            types.add(cursor.readInt32());
        }
        cursor.requireEnd();
        return List.copyOf(types);
    }

    /** Decode an Execute ({@code 'E'}) message body. */
    public ExecuteMessage decodeExecute(FrontendMessage message) throws IOException {
        BodyCursor cursor = cursorFor(message, 'E');
        String portalName = cursor.readCString();
        int maxRows = cursor.readInt32();
        cursor.requireEnd();
        return new ExecuteMessage(portalName, maxRows);
    }

    /** Decode a Describe ({@code 'D'}) message body. */
    public TargetMessage decodeDescribe(FrontendMessage message) throws IOException {
        return decodeTarget(message, 'D');
    }

    /** Decode a Close ({@code 'C'}) message body. */
    public TargetMessage decodeClose(FrontendMessage message) throws IOException {
        return decodeTarget(message, 'C');
    }

    /** Encode a rewritten Parse ({@code 'P'}) message, retaining its name and parameter OIDs. */
    public static byte[] encodeParse(ParseMessage parse, String sql) {
        Objects.requireNonNull(parse, "parse must not be null");
        String statementName = Objects.requireNonNull(parse.statementName(), "statementName must not be null");
        String rewrittenSql = Objects.requireNonNull(sql, "sql must not be null");
        List<Integer> parameterTypeOids = Objects.requireNonNull(
                parse.parameterTypeOids(), "parameterTypeOids must not be null");
        if (parameterTypeOids.size() > 0xFFFF) {
            throw new IllegalArgumentException("Too many PostgreSQL Parse parameter type OIDs: "
                    + parameterTypeOids.size());
        }

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(PostgresWire.cStringBytes(statementName));
        body.writeBytes(PostgresWire.cStringBytes(rewrittenSql));
        writeInt16(body, parameterTypeOids.size());
        for (Integer oid : parameterTypeOids) {
            body.writeBytes(PostgresWire.int32(Objects.requireNonNull(oid, "parameter type OID must not be null")));
        }
        return PostgresWire.frame('P', body.toByteArray());
    }

    private TargetMessage decodeTarget(FrontendMessage message, char expectedType) throws IOException {
        BodyCursor cursor = cursorFor(message, expectedType);
        int targetByte = cursor.readByte();
        if (targetByte != 'S' && targetByte != 'P') {
            throw new IOException("Invalid PostgreSQL " + expectedType + " target type: " + targetByte);
        }
        String name = cursor.readCString();
        cursor.requireEnd();
        return new TargetMessage((char) targetByte, name);
    }

    private static BodyCursor cursorFor(FrontendMessage message, char expectedType) throws IOException {
        if (message == null) {
            throw new IOException("PostgreSQL message is missing");
        }
        if (message.type() != expectedType) {
            throw new IOException("Expected PostgreSQL message type " + expectedType
                    + " but received " + message.type());
        }
        if (message.body() == null) {
            throw new IOException("PostgreSQL message body was streamed and is unavailable");
        }
        return new BodyCursor(message.body());
    }

    private static void writeInt16(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    /** Read exactly {@code n} bytes or throw {@link EOFException}. */
    private byte[] readFully(int n) throws IOException {
        byte[] buf = new byte[n];
        PostgresWire.readFully(in, buf);
        return buf;
    }

    private static final class BodyCursor {
        private final byte[] body;
        private int offset;

        private BodyCursor(byte[] body) {
            this.body = Objects.requireNonNull(body, "body must not be null");
        }

        private String readCString() throws IOException {
            int end = offset;
            while (end < body.length && body[end] != 0) {
                end++;
            }
            if (end == body.length) {
                throw new IOException("PostgreSQL message contains an unterminated string");
            }
            String value = new String(body, offset, end - offset, StandardCharsets.UTF_8);
            offset = end + 1;
            return value;
        }

        private int readByte() throws IOException {
            requireRemaining(1);
            return body[offset++] & 0xFF;
        }

        private int readInt16() throws IOException {
            requireRemaining(2);
            int value = ((body[offset] & 0xFF) << 8) | (body[offset + 1] & 0xFF);
            offset += 2;
            return value;
        }

        private void skip(int count) throws IOException {
            requireRemaining(count);
            offset += count;
        }

        private byte[] readBytes(int count) throws IOException {
            requireRemaining(count);
            byte[] value = Arrays.copyOfRange(body, offset, offset + count);
            offset += count;
            return value;
        }

        private int readInt32() throws IOException {
            requireRemaining(4);
            int value = ((body[offset] & 0xFF) << 24)
                    | ((body[offset + 1] & 0xFF) << 16)
                    | ((body[offset + 2] & 0xFF) << 8)
                    | (body[offset + 3] & 0xFF);
            offset += 4;
            return value;
        }

        private void requireEnd() throws IOException {
            if (offset != body.length) {
                throw new IOException("PostgreSQL message has " + (body.length - offset) + " trailing bytes");
            }
        }

        private void requireRemaining(int count) throws IOException {
            if (body.length - offset < count) {
                throw new EOFException("PostgreSQL message body ended mid-field");
            }
        }
    }

    /** Encode an SQL string as a Simple Query ({@code 'Q'}) packet with a NUL-terminated body. */
    public static byte[] encodeQuery(String sql) {
        return PostgresWire.frame('Q', PostgresWire.cStringBytes(sql == null ? "" : sql));
    }

    /** A single PostgreSQL wire message from the client. */
    public record FrontendMessage(char type, byte[] body) {

        public boolean isQuery() {
            return type == 'Q' && body != null;
        }

        /** SQL text of a {@code 'Q'} (trailing NUL stripped), or {@code null} if this is not a {@code 'Q'}. */
        public String getSql() {
            if (!isQuery() || body == null || body.length == 0) {
                return null;
            }
            int len = body.length;
            if (body[len - 1] == 0) {
                len--;
            }
            return new String(body, 0, len, StandardCharsets.UTF_8);
        }

        /** {@code type · length · body}: a byte-exact round-trip of the original frame. */
        public byte[] toPacketBytes() {
            if (body == null) {
                throw new IllegalStateException("Message body was streamed to passthrough output and not retained");
            }
            return PostgresWire.frame(type, body);
        }
    }

    public record ParseMessage(String statementName, String sql, List<Integer> parameterTypeOids) {
    }

    public record BindMessage(String portalName, String statementName) {
    }

    public record ExecuteMessage(String portalName, int maxRows) {
    }

    public record TargetMessage(char targetType, String name) {
    }
}
