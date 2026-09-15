package io.github.hectorvent.floci.services.redshift.proxy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresWireDecoderTest {

    @Test
    void decodesParseNameSqlAndParameterTypeOids() throws IOException {
        byte[] packet = frame('P', concat(
                cString("stmt-1"),
                cString("select $1::int"),
                int16(1),
                int32(23)));

        PostgresWireDecoder decoder = new PostgresWireDecoder(new ByteArrayInputStream(packet));
        PostgresWireDecoder.ParseMessage parse = decoder.decodeParse(decoder.nextMessage());

        assertEquals("stmt-1", parse.statementName());
        assertEquals("select $1::int", parse.sql());
        assertEquals(List.of(23), parse.parameterTypeOids());
    }

    @Test
    void decodesBindNamesWithoutRetainingValueSemantics() throws IOException {
        byte[] body = concat(cString("portal-1"), cString("stmt-1"), int16(0), int16(0), int16(0));
        PostgresWireDecoder decoder = new PostgresWireDecoder(new ByteArrayInputStream(frame('B', body)));

        PostgresWireDecoder.BindMessage bind = decoder.decodeBind(decoder.nextMessage());

        assertEquals("portal-1", bind.portalName());
        assertEquals("stmt-1", bind.statementName());
    }

    @Test
    void decodesExecuteDescribeAndClose() throws IOException {
        PostgresWireDecoder.ExecuteMessage execute = decodeExecute(frame('E', concat(cString("p"), int32(0))));
        PostgresWireDecoder.TargetMessage describe = decodeDescribe(frame('D', concat(new byte[]{'S'}, cString("s"))));
        PostgresWireDecoder.TargetMessage close = decodeClose(frame('C', concat(new byte[]{'P'}, cString("p"))));

        assertEquals(new PostgresWireDecoder.ExecuteMessage("p", 0), execute);
        assertEquals(new PostgresWireDecoder.TargetMessage('S', "s"), describe);
        assertEquals(new PostgresWireDecoder.TargetMessage('P', "p"), close);
    }

    @Test
    void rejectsParseWithMissingCStringTerminator() {
        PostgresWireDecoder decoder = decoderFor('P', "stmt-1".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> decoder.decodeParse(decoder.nextMessage()));
    }

    @Test
    void rejectsParseWithTruncatedInt16() {
        byte[] body = concat(cString("stmt-1"), cString("select 1"), new byte[]{0});
        PostgresWireDecoder decoder = decoderFor('P', body);
        assertThrows(IOException.class, () -> decoder.decodeParse(decoder.nextMessage()));
    }

    @Test
    void rejectsParseWithTruncatedOidList() {
        byte[] body = concat(cString("stmt-1"), cString("select $1"), int16(1), new byte[]{0, 0});
        PostgresWireDecoder decoder = decoderFor('P', body);
        assertThrows(IOException.class, () -> decoder.decodeParse(decoder.nextMessage()));
    }

    @Test
    void rejectsDescribeWithInvalidTargetByte() {
        PostgresWireDecoder decoder = decoderFor('D', concat(new byte[]{'X'}, cString("name")));
        assertThrows(IOException.class, () -> decoder.decodeDescribe(decoder.nextMessage()));
    }

    @Test
    void rejectsCloseWithInvalidTargetByte() {
        PostgresWireDecoder decoder = decoderFor('C', concat(new byte[]{'X'}, cString("name")));
        assertThrows(IOException.class, () -> decoder.decodeClose(decoder.nextMessage()));
    }

    @Test
    void rejectsExecuteWithTrailingBytes() {
        PostgresWireDecoder decoder = decoderFor('E', concat(cString("p"), int32(0), new byte[]{1}));
        assertThrows(IOException.class, () -> decoder.decodeExecute(decoder.nextMessage()));
    }

    @Test
    void encodeParsePreservesNameAndTypeOidsWhileReplacingSql() throws IOException {
        PostgresWireDecoder.ParseMessage original = new PostgresWireDecoder.ParseMessage(
                "ddl", "CREATE TABLE t (id int ENCODE az64)", List.of(23, 25));

        byte[] encoded = PostgresWireDecoder.encodeParse(original, "CREATE TABLE t (id int)");
        PostgresWireDecoder decoder = new PostgresWireDecoder(new ByteArrayInputStream(encoded));
        PostgresWireDecoder.ParseMessage decoded = decoder.decodeParse(decoder.nextMessage());

        assertEquals("ddl", decoded.statementName());
        assertEquals("CREATE TABLE t (id int)", decoded.sql());
        assertEquals(List.of(23, 25), decoded.parameterTypeOids());
    }

    @Test
    void decodesASimpleQueryAndThenReportsEof() throws IOException {
        byte[] packet = PostgresWireDecoder.encodeQuery("SELECT 1");
        PostgresWireDecoder decoder = new PostgresWireDecoder(new ByteArrayInputStream(packet));

        PostgresWireDecoder.FrontendMessage msg = decoder.nextMessage();
        assertNotNull(msg);
        assertEquals('Q', msg.type());
        assertTrue(msg.isQuery());
        assertEquals("SELECT 1", msg.getSql());
        assertArrayEquals(packet, msg.toPacketBytes());

        assertNull(decoder.nextMessage());
    }

    @Test
    void decodesNonQueryMessagesWithoutInterpretingThem() throws IOException {
        byte[] terminate = new byte[]{'X', 0, 0, 0, 4};
        PostgresWireDecoder decoder = new PostgresWireDecoder(new ByteArrayInputStream(terminate));

        PostgresWireDecoder.FrontendMessage msg = decoder.nextMessage();
        assertNotNull(msg);
        assertEquals('X', msg.type());
        assertFalse(msg.isQuery());
        assertNull(msg.getSql());
        assertEquals(0, msg.body().length);
        assertArrayEquals(terminate, msg.toPacketBytes());
        assertNull(decoder.nextMessage());

        byte[] parsePayload = "stmt1\0SELECT $1\0\0\0".getBytes(StandardCharsets.UTF_8);
        int length = 4 + parsePayload.length;
        byte[] parsePacket = new byte[1 + length];
        parsePacket[0] = 'P';
        parsePacket[1] = (byte) ((length >> 24) & 0xFF);
        parsePacket[2] = (byte) ((length >> 16) & 0xFF);
        parsePacket[3] = (byte) ((length >> 8) & 0xFF);
        parsePacket[4] = (byte) (length & 0xFF);
        System.arraycopy(parsePayload, 0, parsePacket, 5, parsePayload.length);

        PostgresWireDecoder.FrontendMessage parse =
                new PostgresWireDecoder(new ByteArrayInputStream(parsePacket)).nextMessage();
        assertNotNull(parse);
        assertEquals('P', parse.type());
        assertFalse(parse.isQuery());
        assertArrayEquals(parsePayload, parse.body());
        assertArrayEquals(parsePacket, parse.toPacketBytes());
    }

    @Test
    void decodesSeveralMessagesFromOneStream() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(PostgresWireDecoder.encodeQuery("SELECT 1"));
        out.write(PostgresWireDecoder.encodeQuery("SELECT 2"));
        out.write(new byte[]{'X', 0, 0, 0, 4});

        PostgresWireDecoder decoder = new PostgresWireDecoder(new ByteArrayInputStream(out.toByteArray()));
        assertEquals("SELECT 1", decoder.nextMessage().getSql());
        assertEquals("SELECT 2", decoder.nextMessage().getSql());
        assertEquals('X', decoder.nextMessage().type());
        assertNull(decoder.nextMessage());
    }

    @Test
    void reassemblesAMessageDeliveredTwoBytesAtATime() throws IOException {
        byte[] packet = PostgresWireDecoder.encodeQuery("SELECT * FROM users WHERE active = true");
        InputStream drip = new InputStream() {
            private int i = 0;
            @Override public int read() {
                if (i >= packet.length) {
                    return -1;
                }
                return packet[i++] & 0xFF;
            }
            @Override public int read(byte[] b, int off, int len) {
                if (i >= packet.length) {
                    return -1;
                }
                int n = Math.min(Math.min(len, 2), packet.length - i);
                System.arraycopy(packet, i, b, off, n);
                i += n;
                return n;
            }
        };
        PostgresWireDecoder.FrontendMessage msg = new PostgresWireDecoder(drip).nextMessage();
        assertEquals("SELECT * FROM users WHERE active = true", msg.getSql());
        assertArrayEquals(packet, msg.toPacketBytes());
    }

    @Test
    void returnsNullOnAnEmptyStream() throws IOException {
        assertNull(new PostgresWireDecoder(new ByteArrayInputStream(new byte[0])).nextMessage());
    }

    @Test
    void throwsEofWhenTheLengthFieldIsTruncated() {
        PostgresWireDecoder decoder = new PostgresWireDecoder(new ByteArrayInputStream(new byte[]{'Q', 0, 0}));
        assertThrows(EOFException.class, decoder::nextMessage);
    }

    @Test
    void throwsEofWhenTheBodyIsTruncated() {
        PostgresWireDecoder decoder =
                new PostgresWireDecoder(new ByteArrayInputStream(new byte[]{'Q', 0, 0, 0, 10, 'S', 'E'}));
        assertThrows(EOFException.class, decoder::nextMessage);
    }

    @Test
    void rejectsALengthBelowFour() {
        PostgresWireDecoder decoder =
                new PostgresWireDecoder(new ByteArrayInputStream(new byte[]{'Q', 0, 0, 0, 2}));
        assertThrows(IOException.class, decoder::nextMessage);
    }

    @Test
    void refusesAnOversizedDeclaredLengthBeforeReadingTheBody() {
        byte[] hostile = new byte[]{'Q', 0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        PostgresWireDecoder decoder = new PostgresWireDecoder(new ByteArrayInputStream(hostile));
        IOException ex = assertThrows(IOException.class, decoder::nextMessage);
        assertTrue(ex.getMessage().contains("Refusing"), ex.getMessage());
    }

    @Test
    void encodeQueryProducesAWellFormedQPacket() {
        byte[] encoded = PostgresWireDecoder.encodeQuery("SHOW search_path");
        assertEquals('Q', (char) encoded[0]);
        int length = ((encoded[1] & 0xFF) << 24) | ((encoded[2] & 0xFF) << 16)
                | ((encoded[3] & 0xFF) << 8) | (encoded[4] & 0xFF);
        assertEquals(21, length); // 4 + 16 chars + 1 NUL
        assertEquals(0x00, encoded[encoded.length - 1]);
    }

    @Test
    void isBetweenMessagesIsTrueAtBoundariesAndFalseMidMessage() throws IOException {
        byte[] two = new ByteArrayOutputStream() {{
            try {
                write(PostgresWireDecoder.encodeQuery("SELECT 1"));
                write(new byte[]{'Q', 0, 0, 0, 50}); // header promises 46 body bytes that never arrive
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }}.toByteArray();

        PostgresWireDecoder decoder = new PostgresWireDecoder(new ByteArrayInputStream(two));
        assertTrue(decoder.isBetweenMessages());
        assertNotNull(decoder.nextMessage());
        assertTrue(decoder.isBetweenMessages());
        assertThrows(EOFException.class, decoder::nextMessage);
        assertFalse(decoder.isBetweenMessages()); // type byte was consumed before the stream ran dry
    }

    @Test
    void streamsOpaqueMessageLargerThanQueryLimitDirectlyToPassthroughOut() throws IOException {
        int payloadSize = 17 * 1024 * 1024; // 17 MiB, exceeds MAX_MESSAGE_BYTES
        int totalLength = 4 + payloadSize;
        byte[] header = new byte[]{
                'd', // CopyData
                (byte) ((totalLength >> 24) & 0xFF),
                (byte) ((totalLength >> 16) & 0xFF),
                (byte) ((totalLength >> 8) & 0xFF),
                (byte) (totalLength & 0xFF)
        };

        InputStream in = new InputStream() {
            private int headerPos = 0;
            private int bodyRemaining = payloadSize;

            @Override
            public int read() {
                if (headerPos < header.length) {
                    return header[headerPos++] & 0xFF;
                }
                if (bodyRemaining > 0) {
                    bodyRemaining--;
                    return 'A';
                }
                return -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (headerPos < header.length) {
                    int toCopy = Math.min(len, header.length - headerPos);
                    System.arraycopy(header, headerPos, b, off, toCopy);
                    headerPos += toCopy;
                    return toCopy;
                }
                if (bodyRemaining > 0) {
                    int toProvide = Math.min(len, bodyRemaining);
                    for (int i = 0; i < toProvide; i++) {
                        b[off + i] = 'A';
                    }
                    bodyRemaining -= toProvide;
                    return toProvide;
                }
                return -1;
            }
        };

        long[] bytesWritten = new long[1];
        OutputStream out = new OutputStream() {
            @Override
            public void write(int b) {
                bytesWritten[0]++;
            }

            @Override
            public void write(byte[] b, int off, int len) {
                bytesWritten[0] += len;
            }
        };

        PostgresWireDecoder decoder = new PostgresWireDecoder(in);
        PostgresWireDecoder.FrontendMessage msg = decoder.nextMessage(out);
        assertNotNull(msg);
        assertEquals('d', msg.type());
        assertNull(msg.body());
        assertEquals(1 + (long) totalLength, bytesWritten[0]);
    }

    @Test
    void streamsSimpleQueryLargerThanLimitDirectlyToPassthroughOut() throws IOException {
        int payloadSize = 17 * 1024 * 1024; // 17 MiB, exceeds MAX_MESSAGE_BYTES
        int totalLength = 4 + payloadSize;
        byte[] header = new byte[]{
                'Q',
                (byte) ((totalLength >> 24) & 0xFF),
                (byte) ((totalLength >> 16) & 0xFF),
                (byte) ((totalLength >> 8) & 0xFF),
                (byte) (totalLength & 0xFF)
        };

        InputStream in = new InputStream() {
            private int headerPos = 0;
            private int bodyRemaining = payloadSize;

            @Override
            public int read() {
                if (headerPos < header.length) {
                    return header[headerPos++] & 0xFF;
                }
                if (bodyRemaining > 0) {
                    bodyRemaining--;
                    return 'A';
                }
                return -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (headerPos < header.length) {
                    int toCopy = Math.min(len, header.length - headerPos);
                    System.arraycopy(header, headerPos, b, off, toCopy);
                    headerPos += toCopy;
                    return toCopy;
                }
                if (bodyRemaining > 0) {
                    int toProvide = Math.min(len, bodyRemaining);
                    for (int i = 0; i < toProvide; i++) {
                        b[off + i] = 'A';
                    }
                    bodyRemaining -= toProvide;
                    return toProvide;
                }
                return -1;
            }
        };

        long[] bytesWritten = new long[1];
        OutputStream out = new OutputStream() {
            @Override
            public void write(int b) {
                bytesWritten[0]++;
            }

            @Override
            public void write(byte[] b, int off, int len) {
                bytesWritten[0] += len;
            }
        };

        PostgresWireDecoder decoder = new PostgresWireDecoder(in);
        PostgresWireDecoder.FrontendMessage msg = decoder.nextMessage(out);
        assertNotNull(msg);
        assertEquals('Q', msg.type());
        assertFalse(msg.isQuery());
        assertNull(msg.body());
        assertEquals(1 + (long) totalLength, bytesWritten[0]);
    }

    private static PostgresWireDecoder decoderFor(char type, byte[] body) {
        return new PostgresWireDecoder(new ByteArrayInputStream(frame(type, body)));
    }

    private static PostgresWireDecoder.ExecuteMessage decodeExecute(byte[] packet) throws IOException {
        PostgresWireDecoder decoder = new PostgresWireDecoder(new ByteArrayInputStream(packet));
        return decoder.decodeExecute(decoder.nextMessage());
    }

    private static PostgresWireDecoder.TargetMessage decodeDescribe(byte[] packet) throws IOException {
        PostgresWireDecoder decoder = new PostgresWireDecoder(new ByteArrayInputStream(packet));
        return decoder.decodeDescribe(decoder.nextMessage());
    }

    private static PostgresWireDecoder.TargetMessage decodeClose(byte[] packet) throws IOException {
        PostgresWireDecoder decoder = new PostgresWireDecoder(new ByteArrayInputStream(packet));
        return decoder.decodeClose(decoder.nextMessage());
    }

    private static byte[] frame(char type, byte[] body) {
        int length = body.length + 4;
        byte[] packet = new byte[body.length + 5];
        packet[0] = (byte) type;
        packet[1] = (byte) ((length >> 24) & 0xFF);
        packet[2] = (byte) ((length >> 16) & 0xFF);
        packet[3] = (byte) ((length >> 8) & 0xFF);
        packet[4] = (byte) (length & 0xFF);
        System.arraycopy(body, 0, packet, 5, body.length);
        return packet;
    }

    private static byte[] cString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, result, 0, bytes.length);
        return result;
    }

    private static byte[] int16(int value) {
        return new byte[]{(byte) ((value >> 8) & 0xFF), (byte) (value & 0xFF)};
    }

    private static byte[] int32(int value) {
        return new byte[]{
                (byte) ((value >> 24) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) (value & 0xFF)
        };
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }
}
