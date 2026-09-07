package io.github.hectorvent.floci.services.redshift.proxy;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

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
        return nextMessage(null);
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
        betweenMessages = true;

        int typeByte = in.read();
        if (typeByte == -1) {
            return null; // clean EOF between messages
        }
        betweenMessages = false;
        char type = (char) typeByte;

        byte[] lengthBytes = readFully(4);
        int length = ((lengthBytes[0] & 0xFF) << 24)
                | ((lengthBytes[1] & 0xFF) << 16)
                | ((lengthBytes[2] & 0xFF) << 8)
                | (lengthBytes[3] & 0xFF);

        if (length < 4) {
            throw new IOException("Invalid message length: " + length);
        }

        if (passthroughOut != null && (type != 'Q' || length > MAX_MESSAGE_BYTES)) {
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

        if (length > MAX_MESSAGE_BYTES) {
            throw new IOException("Refusing PostgreSQL message of " + length
                    + " bytes (limit " + MAX_MESSAGE_BYTES + ")");
        }

        byte[] body = (length == 4) ? new byte[0] : readFully(length - 4);
        betweenMessages = true;
        return new FrontendMessage(type, body);
    }

    /** Read exactly {@code n} bytes or throw {@link EOFException}. */
    private byte[] readFully(int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int read = in.read(buf, off, n - off);
            if (read == -1) {
                throw new EOFException("Unexpected EOF (expected " + n + " bytes, got " + off + ")");
            }
            off += read;
        }
        return buf;
    }

    /** Encode an SQL string as a Simple Query ({@code 'Q'}) packet with a NUL-terminated body. */
    public static byte[] encodeQuery(String sql) {
        if (sql == null) {
            sql = "";
        }
        byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
        int bodyLength = sqlBytes.length + 1;
        int totalLength = 4 + bodyLength;

        byte[] packet = new byte[1 + 4 + bodyLength];
        packet[0] = 'Q';
        packet[1] = (byte) ((totalLength >> 24) & 0xFF);
        packet[2] = (byte) ((totalLength >> 16) & 0xFF);
        packet[3] = (byte) ((totalLength >> 8) & 0xFF);
        packet[4] = (byte) (totalLength & 0xFF);
        System.arraycopy(sqlBytes, 0, packet, 5, sqlBytes.length);
        packet[packet.length - 1] = 0x00;
        return packet;
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
            int bodyLen = body.length;
            int lengthField = 4 + bodyLen;
            byte[] packet = new byte[1 + 4 + bodyLen];
            packet[0] = (byte) type;
            packet[1] = (byte) ((lengthField >> 24) & 0xFF);
            packet[2] = (byte) ((lengthField >> 16) & 0xFF);
            packet[3] = (byte) ((lengthField >> 8) & 0xFF);
            packet[4] = (byte) (lengthField & 0xFF);
            if (bodyLen > 0) {
                System.arraycopy(body, 0, packet, 5, bodyLen);
            }
            return packet;
        }
    }
}
