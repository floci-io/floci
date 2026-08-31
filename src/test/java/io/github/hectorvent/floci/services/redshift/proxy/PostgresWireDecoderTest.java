package io.github.hectorvent.floci.services.redshift.proxy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresWireDecoderTest {

    @Test
    void testDecodeSimpleQuery() throws IOException {
        String sql = "SELECT 1";
        byte[] packet = PostgresWireDecoder.encodeQuery(sql);

        ByteArrayInputStream in = new ByteArrayInputStream(packet);
        PostgresWireDecoder decoder = new PostgresWireDecoder(in);

        PostgresWireDecoder.FrontendMessage msg = decoder.nextMessage();
        assertNotNull(msg);
        assertEquals('Q', msg.type());
        assertTrue(msg.isQuery());
        assertEquals("SELECT 1", msg.getSql());
        assertArrayEquals(packet, msg.toPacketBytes());

        // EOF sau thong diep dau tien
        assertNull(decoder.nextMessage());
    }

    @Test
    void testDecodeNonQMessages() throws IOException {
        // Thong diep 'X' (Terminate) voi length = 4 (body rong)
        byte[] terminatePacket = new byte[]{'X', 0, 0, 0, 4};
        ByteArrayInputStream in = new ByteArrayInputStream(terminatePacket);
        PostgresWireDecoder decoder = new PostgresWireDecoder(in);

        PostgresWireDecoder.FrontendMessage msg = decoder.nextMessage();
        assertNotNull(msg);
        assertEquals('X', msg.type());
        assertFalse(msg.isQuery());
        assertNull(msg.getSql());
        assertEquals(0, msg.body().length);
        assertArrayEquals(terminatePacket, msg.toPacketBytes());
        assertNull(decoder.nextMessage());

        // Thong diep 'P' (Parse) voi payload
        byte[] parsePayload = "stmt1\0SELECT * FROM test WHERE id = $1\0\0\0".getBytes(StandardCharsets.UTF_8);
        int length = 4 + parsePayload.length;
        byte[] parsePacket = new byte[1 + length];
        parsePacket[0] = 'P';
        parsePacket[1] = (byte) ((length >> 24) & 0xFF);
        parsePacket[2] = (byte) ((length >> 16) & 0xFF);
        parsePacket[3] = (byte) ((length >> 8) & 0xFF);
        parsePacket[4] = (byte) (length & 0xFF);
        System.arraycopy(parsePayload, 0, parsePacket, 5, parsePayload.length);

        decoder = new PostgresWireDecoder(new ByteArrayInputStream(parsePacket));
        PostgresWireDecoder.FrontendMessage parseMsg = decoder.nextMessage();
        assertNotNull(parseMsg);
        assertEquals('P', parseMsg.type());
        assertFalse(parseMsg.isQuery());
        assertNull(parseMsg.getSql());
        assertArrayEquals(parsePayload, parseMsg.body());
        assertArrayEquals(parsePacket, parseMsg.toPacketBytes());
    }

    @Test
    void testMultiMessageStream() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(PostgresWireDecoder.encodeQuery("SELECT 1"));
        out.write(PostgresWireDecoder.encodeQuery("SELECT 2"));
        out.write(new byte[]{'X', 0, 0, 0, 4}); // Terminate

        PostgresWireDecoder decoder = new PostgresWireDecoder(new ByteArrayInputStream(out.toByteArray()));

        PostgresWireDecoder.FrontendMessage msg1 = decoder.nextMessage();
        assertNotNull(msg1);
        assertEquals('Q', msg1.type());
        assertEquals("SELECT 1", msg1.getSql());

        PostgresWireDecoder.FrontendMessage msg2 = decoder.nextMessage();
        assertNotNull(msg2);
        assertEquals('Q', msg2.type());
        assertEquals("SELECT 2", msg2.getSql());

        PostgresWireDecoder.FrontendMessage msg3 = decoder.nextMessage();
        assertNotNull(msg3);
        assertEquals('X', msg3.type());
        assertEquals(0, msg3.body().length);

        assertNull(decoder.nextMessage());
    }

    @Test
    void testChunkedStream() throws IOException {
        byte[] packet = PostgresWireDecoder.encodeQuery("SELECT * FROM users WHERE active = true");

        // InputStream gia lap doc tung byte mot de kiem tra xu ly chunked read
        InputStream chunkedIn = new InputStream() {
            private int index = 0;

            @Override
            public int read() {
                if (index >= packet.length) {
                    return -1;
                }
                return packet[index++] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (index >= packet.length) {
                    return -1;
                }
                // Chi tra ve toi da 2 bytes moi lan goi
                int toRead = Math.min(Math.min(len, 2), packet.length - index);
                System.arraycopy(packet, index, b, off, toRead);
                index += toRead;
                return toRead;
            }
        };

        PostgresWireDecoder decoder = new PostgresWireDecoder(chunkedIn);
        PostgresWireDecoder.FrontendMessage msg = decoder.nextMessage();
        assertNotNull(msg);
        assertEquals('Q', msg.type());
        assertEquals("SELECT * FROM users WHERE active = true", msg.getSql());
        assertArrayEquals(packet, msg.toPacketBytes());
        assertNull(decoder.nextMessage());
    }

    @Test
    void testEmptyStreamEof() throws IOException {
        PostgresWireDecoder decoder = new PostgresWireDecoder(new ByteArrayInputStream(new byte[0]));
        assertNull(decoder.nextMessage());
    }

    @Test
    void testPrematureEofInLength() {
        // Chi co type 'Q' va 2 byte length thay vi 4
        byte[] truncated = new byte[]{'Q', 0, 0};
        PostgresWireDecoder decoder = new PostgresWireDecoder(new ByteArrayInputStream(truncated));
        assertThrows(EOFException.class, decoder::nextMessage);
    }

    @Test
    void testPrematureEofInBody() {
        // Type 'Q', length = 10, nhung chi co 2 byte payload
        byte[] truncated = new byte[]{'Q', 0, 0, 0, 10, 'S', 'E'};
        PostgresWireDecoder decoder = new PostgresWireDecoder(new ByteArrayInputStream(truncated));
        assertThrows(EOFException.class, decoder::nextMessage);
    }

    @Test
    void testInvalidLength() {
        // Length nho hon 4 la khong hop le theo PostgreSQL wire protocol
        byte[] invalid = new byte[]{'Q', 0, 0, 0, 2};
        PostgresWireDecoder decoder = new PostgresWireDecoder(new ByteArrayInputStream(invalid));
        assertThrows(IOException.class, decoder::nextMessage);
    }

    @Test
    void testEncodeQueryFormatting() {
        byte[] encoded = PostgresWireDecoder.encodeQuery("SHOW search_path");
        assertEquals('Q', (char) encoded[0]);
        int length = ((encoded[1] & 0xFF) << 24) | ((encoded[2] & 0xFF) << 16) | ((encoded[3] & 0xFF) << 8) | (encoded[4] & 0xFF);
        // length = 4 + "SHOW search_path".length() (16) + 1 (null byte) = 21
        assertEquals(21, length);
        assertEquals(0x00, encoded[encoded.length - 1]);
    }
}
