package io.github.hectorvent.floci.services.redshift.proxy;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Decoder for PostgreSQL wire-protocol messages sent by the frontend (client).
 */
public class PostgresWireDecoder {

    /**
     * Refuse a single message whose declared length exceeds this. The length field is
     * attacker-controlled (up to ~2 GiB). {@link InputStream#readNBytes(int)} allocates in
     * proportion to bytes actually received (not the declared length), so a client that stalls
     * mid-body holds only a small buffer; this cap bounds a client that genuinely floods.
     * 16 MiB is well above any realistic single SQL statement or backend row.
     */
    private static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;

    private final InputStream in;

    public PostgresWireDecoder(InputStream in) {
        this.in = Objects.requireNonNull(in, "in must not be null");
    }

    /**
     * Read the next message from the stream.
     *
     * @return the {@link FrontendMessage}, or {@code null} on a clean end-of-stream (EOF).
     * @throws EOFException if EOF is hit unexpectedly in the middle of a message.
     * @throws IOException on an I/O error or an invalid packet length.
     */
    public FrontendMessage nextMessage() throws IOException {
        int typeByte = in.read();
        if (typeByte == -1) {
            return null; // clean EOF between messages
        }
        char type = (char) typeByte;

        byte[] lengthBytes = in.readNBytes(4);
        if (lengthBytes.length < 4) {
            throw new EOFException("Unexpected EOF while reading message length");
        }

        int length = ((lengthBytes[0] & 0xFF) << 24)
                | ((lengthBytes[1] & 0xFF) << 16)
                | ((lengthBytes[2] & 0xFF) << 8)
                | (lengthBytes[3] & 0xFF);

        if (length < 4) {
            throw new IOException("Invalid message length: " + length);
        }
        if (length > MAX_MESSAGE_BYTES) {
            throw new IOException("Refusing PostgreSQL message of " + length
                    + " bytes (limit " + MAX_MESSAGE_BYTES + ")");
        }

        int bodyLength = length - 4;
        byte[] body = in.readNBytes(bodyLength);
        if (body.length < bodyLength) {
            throw new EOFException("Unexpected EOF while reading message body (expected "
                    + bodyLength + " bytes, got " + body.length + ")");
        }

        return new FrontendMessage(type, body);
    }

    /**
     * Encode an SQL string as a Simple Query ('Q') wire-protocol packet.
     */
    public static byte[] encodeQuery(String sql) {
        if (sql == null) {
            sql = "";
        }
        byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
        int bodyLength = sqlBytes.length + 1; // null-terminated
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

    /**
     * A single PostgreSQL wire message from the client.
     */
    public record FrontendMessage(char type, byte[] body) {

        public boolean isQuery() {
            return type == 'Q';
        }

        public String getSql() {
            if (type != 'Q' || body == null || body.length == 0) {
                return null;
            }
            int len = body.length;
            if (body[len - 1] == 0) {
                len--; // drop the trailing null terminator
            }
            return new String(body, 0, len, StandardCharsets.UTF_8);
        }

        public byte[] toPacketBytes() {
            int bodyLen = (body != null) ? body.length : 0;
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
