package io.github.hectorvent.floci.services.redshift.proxy;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Decoder for PostgreSQL wire-protocol messages sent by the frontend (client).
 */
public class PostgresWireDecoder implements AutoCloseable {

    /**
     * Refuse a single message whose declared length exceeds this. The length field is
     * attacker-controlled (up to ~2 GiB). {@link InputStream#readNBytes(int)} allocates in
     * proportion to bytes actually received (not the declared length), so a client that stalls
     * mid-body holds only a small buffer; this cap bounds a client that genuinely floods.
     * 16 MiB is well above any realistic single SQL statement or backend row.
     */
    private static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;

    /**
     * Shared budget across all connections for message body allocations while active,
     * preventing multiple concurrent clients from exhausting the shared JVM heap with large
     * or stalled bodies.
     */
    private static final Semaphore DECODER_HEAP_BUDGET = new Semaphore(64 * 1024 * 1024);

    private final InputStream in;
    private int heldBodyBytes = 0;
    private volatile boolean betweenMessages = true;

    public PostgresWireDecoder(InputStream in) {
        this.in = Objects.requireNonNull(in, "in must not be null");
    }

    /**
     * True when the decoder is currently at a clean boundary between wire messages (not in the middle
     * of reading a message header or body).
     */
    public boolean isBetweenMessages() {
        return betweenMessages;
    }

    /**
     * Read the next message from the stream.
     *
     * @return the {@link FrontendMessage}, or {@code null} on a clean end-of-stream (EOF).
     * @throws EOFException if EOF is hit unexpectedly in the middle of a message.
     * @throws IOException on an I/O error or an invalid packet length.
     */
    public FrontendMessage nextMessage() throws IOException {
        // Release the budget held for the previous message now that the caller is done with it.
        releaseHeldBudget();
        betweenMessages = true;

        int typeByte = in.read();
        if (typeByte == -1) {
            return null; // clean EOF between messages
        }
        betweenMessages = false;
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
        byte[] body = readBodyBounded(bodyLength);
        betweenMessages = true;

        return new FrontendMessage(type, body);
    }

    /**
     * Read {@code bodyLength} bytes in chunks, acquiring decoder budget proportionally as bytes actually
     * arrive. A client that declares a near-limit message but stalls mid-stream retains budget only for
     * the chunks already received, preventing stalled sessions from pinning the entire budget up front.
     */
    private byte[] readBodyBounded(int bodyLength) throws IOException {
        if (bodyLength == 0) {
            return new byte[0];
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(bodyLength, 8192));
        byte[] chunk = new byte[Math.min(bodyLength, 8192)];
        int remaining = bodyLength;
        int acquired = 0;

        try {
            while (remaining > 0) {
                int toRead = Math.min(remaining, chunk.length);
                int read = in.read(chunk, 0, toRead);
                if (read == -1) {
                    throw new EOFException("Unexpected EOF while reading message body (expected "
                            + bodyLength + " bytes, got " + buffer.size() + ")");
                }
                boolean permitOk;
                try {
                    permitOk = DECODER_HEAP_BUDGET.tryAcquire(read, 5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while acquiring decoder memory budget", e);
                }
                if (!permitOk) {
                    throw new IOException("Decoder memory budget exhausted (" + read + " bytes requested)");
                }
                acquired += read;
                buffer.write(chunk, 0, read);
                remaining -= read;
            }
            heldBodyBytes = acquired;
            return buffer.toByteArray();
        } catch (Throwable t) {
            if (acquired > 0) {
                DECODER_HEAP_BUDGET.release(acquired);
            }
            throw t;
        }
    }

    private void releaseHeldBudget() {
        if (heldBodyBytes > 0) {
            DECODER_HEAP_BUDGET.release(heldBodyBytes);
            heldBodyBytes = 0;
        }
    }

    @Override
    public void close() {
        releaseHeldBudget();
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
