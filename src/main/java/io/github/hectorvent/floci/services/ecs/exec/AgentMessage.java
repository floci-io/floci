package io.github.hectorvent.floci.services.ecs.exec;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;

/**
 * One frame of the Session Manager data channel, the binary envelope
 * {@code session-manager-plugin} exchanges with the SSM agent over the WebSocket.
 *
 * <p>The layout is fixed: a 116 byte header (whose own length is the first field, excluding the
 * trailing payload length) followed by the payload.
 *
 * <pre>
 *   0   header length      int32   (116)
 *   4   message type       32 bytes, space padded
 *  36   schema version     uint32
 *  40   created date       uint64, epoch millis
 *  48   sequence number    int64
 *  56   flags              uint64
 *  64   message id         16 bytes, least significant half first
 *  80   payload digest     32 bytes, SHA-256 of the payload
 * 112   payload type       uint32
 * 116   payload length     uint32
 * 120   payload
 * </pre>
 *
 * <p>The message id's halves are swapped relative to a textual UUID because the agent writes the
 * low 64 bits before the high ones; a client that reassembles the id the same way sees the UUID it
 * expects.
 */
public record AgentMessage(
        String messageType,
        int schemaVersion,
        long createdDate,
        long sequenceNumber,
        long flags,
        UUID messageId,
        int payloadType,
        byte[] payload) {

    public static final String OUTPUT_STREAM_DATA = "output_stream_data";
    public static final String INPUT_STREAM_DATA = "input_stream_data";
    public static final String ACKNOWLEDGE = "acknowledge";
    public static final String CHANNEL_CLOSED = "channel_closed";

    public static final int PAYLOAD_OUTPUT = 1;
    public static final int PAYLOAD_ERROR = 2;
    public static final int PAYLOAD_SIZE = 3;
    public static final int PAYLOAD_HANDSHAKE_REQUEST = 5;
    public static final int PAYLOAD_HANDSHAKE_RESPONSE = 6;
    public static final int PAYLOAD_HANDSHAKE_COMPLETE = 7;

    /** The header length the agent writes, which stops before the payload length field. */
    public static final int HEADER_LENGTH = 116;
    private static final int MESSAGE_TYPE_LENGTH = 32;
    private static final int DIGEST_LENGTH = 32;
    private static final int PAYLOAD_OFFSET = 120;
    private static final int SCHEMA_VERSION = 1;

    /** A message the agent side sends: a fresh id, the current time, and the given sequence. */
    public static AgentMessage outbound(String messageType, long sequenceNumber, int payloadType,
                                        byte[] payload) {
        return new AgentMessage(messageType, SCHEMA_VERSION, System.currentTimeMillis(),
                sequenceNumber, 0L, UUID.randomUUID(), payloadType, payload);
    }

    public byte[] encode() {
        byte[] body = payload != null ? payload : new byte[0];
        ByteBuffer buffer = ByteBuffer.allocate(PAYLOAD_OFFSET + body.length);
        buffer.putInt(HEADER_LENGTH);
        buffer.put(paddedMessageType());
        buffer.putInt(schemaVersion);
        buffer.putLong(createdDate);
        buffer.putLong(sequenceNumber);
        buffer.putLong(flags);
        buffer.putLong(messageId.getLeastSignificantBits());
        buffer.putLong(messageId.getMostSignificantBits());
        buffer.put(sha256(body));
        buffer.putInt(payloadType);
        buffer.putInt(body.length);
        buffer.put(body);
        return buffer.array();
    }

    public static AgentMessage decode(byte[] frame) {
        if (frame == null || frame.length < PAYLOAD_OFFSET) {
            throw new IllegalArgumentException("An agent message is at least " + PAYLOAD_OFFSET
                    + " bytes, got " + (frame == null ? 0 : frame.length));
        }
        ByteBuffer buffer = ByteBuffer.wrap(frame);
        buffer.getInt();
        byte[] typeBytes = new byte[MESSAGE_TYPE_LENGTH];
        buffer.get(typeBytes);
        String messageType = new String(typeBytes, StandardCharsets.UTF_8).trim();
        int schemaVersion = buffer.getInt();
        long createdDate = buffer.getLong();
        long sequenceNumber = buffer.getLong();
        long flags = buffer.getLong();
        long leastSignificant = buffer.getLong();
        long mostSignificant = buffer.getLong();
        buffer.position(buffer.position() + DIGEST_LENGTH);
        int payloadType = buffer.getInt();
        int payloadLength = buffer.getInt();
        int available = Math.min(payloadLength, frame.length - PAYLOAD_OFFSET);
        byte[] payload = new byte[Math.max(available, 0)];
        if (payload.length > 0) {
            buffer.get(payload);
        }
        return new AgentMessage(messageType, schemaVersion, createdDate, sequenceNumber, flags,
                new UUID(mostSignificant, leastSignificant), payloadType, payload);
    }

    public String payloadAsString() {
        return payload == null ? "" : new String(payload, StandardCharsets.UTF_8);
    }

    private byte[] paddedMessageType() {
        byte[] padded = new byte[MESSAGE_TYPE_LENGTH];
        Arrays.fill(padded, (byte) ' ');
        byte[] raw = messageType.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(raw, 0, padded, 0, Math.min(raw.length, MESSAGE_TYPE_LENGTH));
        return padded;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to frame a Session Manager message", e);
        }
    }
}
