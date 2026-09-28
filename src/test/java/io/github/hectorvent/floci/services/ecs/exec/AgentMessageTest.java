package io.github.hectorvent.floci.services.ecs.exec;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The Session Manager frame layout. {@code session-manager-plugin} parses these bytes by fixed
 * offset, so the field order, the padding, the digest and the swapped halves of the message id are
 * the contract: get one wrong and the plugin drops the channel without saying why.
 */
class AgentMessageTest {

    private static final int PAYLOAD_OFFSET = 120;

    @Test
    void encodeWritesTheHeaderTheAgentWrites() throws Exception {
        UUID messageId = UUID.fromString("2f1b6a4e-9c3d-4f7a-8b2e-1d0c9a8b7c6d");
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        AgentMessage message = new AgentMessage(AgentMessage.OUTPUT_STREAM_DATA, 1, 1700000000000L,
                7L, 0L, messageId, AgentMessage.PAYLOAD_OUTPUT, payload);

        byte[] encoded = message.encode();
        ByteBuffer buffer = ByteBuffer.wrap(encoded);

        assertEquals(AgentMessage.HEADER_LENGTH, buffer.getInt(), "the header length comes first");
        byte[] type = new byte[32];
        buffer.get(type);
        assertEquals(AgentMessage.OUTPUT_STREAM_DATA,
                new String(type, StandardCharsets.UTF_8).trim());
        assertEquals(' ', (char) type[type.length - 1], "the message type is padded with spaces");
        assertEquals(1, buffer.getInt(), "schema version");
        assertEquals(1700000000000L, buffer.getLong(), "created date");
        assertEquals(7L, buffer.getLong(), "sequence number");
        assertEquals(0L, buffer.getLong(), "flags");
        // The low half of the id is written before the high half.
        assertEquals(messageId.getLeastSignificantBits(), buffer.getLong());
        assertEquals(messageId.getMostSignificantBits(), buffer.getLong());
        byte[] digest = new byte[32];
        buffer.get(digest);
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(payload), digest,
                "the digest covers the payload");
        assertEquals(AgentMessage.PAYLOAD_OUTPUT, buffer.getInt(), "payload type");
        assertEquals(payload.length, buffer.getInt(), "payload length");
        assertEquals(PAYLOAD_OFFSET + payload.length, encoded.length);
    }

    @Test
    void decodeReadsBackWhatEncodeWrote() {
        UUID messageId = UUID.randomUUID();
        byte[] payload = "{\"cols\":120,\"rows\":40}".getBytes(StandardCharsets.UTF_8);
        AgentMessage original = new AgentMessage(AgentMessage.INPUT_STREAM_DATA, 1, 42L, 3L, 1L,
                messageId, AgentMessage.PAYLOAD_SIZE, payload);

        AgentMessage decoded = AgentMessage.decode(original.encode());

        assertEquals(AgentMessage.INPUT_STREAM_DATA, decoded.messageType());
        assertEquals(1, decoded.schemaVersion());
        assertEquals(42L, decoded.createdDate());
        assertEquals(3L, decoded.sequenceNumber());
        assertEquals(1L, decoded.flags());
        assertEquals(messageId, decoded.messageId());
        assertEquals(AgentMessage.PAYLOAD_SIZE, decoded.payloadType());
        assertEquals("{\"cols\":120,\"rows\":40}", decoded.payloadAsString());
    }

    @Test
    void anEmptyPayloadRoundTrips() {
        AgentMessage decoded = AgentMessage.decode(
                AgentMessage.outbound(AgentMessage.CHANNEL_CLOSED, 0, AgentMessage.PAYLOAD_OUTPUT,
                        new byte[0]).encode());

        assertEquals(AgentMessage.CHANNEL_CLOSED, decoded.messageType());
        assertEquals(0, decoded.payload().length);
    }

    @Test
    void aTruncatedFrameIsRejectedRatherThanMisread() {
        byte[] tooShort = new byte[64];
        assertThrows(IllegalArgumentException.class, () -> AgentMessage.decode(tooShort));
    }
}
