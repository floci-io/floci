package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PostgresWireTest {

    @Test
    void quoteLiteralUsesEscapeStringsAndPreservesText() {
        assertEquals("E''", PostgresWire.quoteLiteral(""));
        assertEquals("E'plain'", PostgresWire.quoteLiteral("plain"));
        assertEquals("E'it''s a \\\\ path'", PostgresWire.quoteLiteral("it's a \\ path"));
        assertEquals("E'\\\\n'", PostgresWire.quoteLiteral("\\n"));
        assertEquals("E'line\nnext'", PostgresWire.quoteLiteral("line\nnext"));
    }

    @Test
    void aMessageIsFramedWithALengthThatCountsItself() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PostgresWire.write(out, 'Q', PostgresWire.cStringBytes("SELECT 1"));

        byte[] framed = out.toByteArray();
        assertArrayEquals(PostgresWire.frame('Q', PostgresWire.cStringBytes("SELECT 1")), framed);
        assertEquals('Q', framed[0]);
        assertEquals(4 + 9, PostgresWire.int32(framed, 1));

        PostgresWire.Message read = PostgresWire.read(new ByteArrayInputStream(framed), 1024, "message");
        assertEquals('Q', read.type());
        assertEquals("SELECT 1", PostgresWire.cString(read.body(), 0).value());
    }

    @Test
    void aMessageIsWrittenFromItsPartsInOrder() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PostgresWire.write(out, 'p', "secret".getBytes(), new byte[]{0});

        assertArrayEquals(PostgresWire.frame('p', PostgresWire.cStringBytes("secret")), out.toByteArray());
    }

    @Test
    void aLengthOutsideItsBoundsIsRefusedNamingThePacket() {
        IOException low = assertThrows(IOException.class, () -> PostgresWire.checkedLength(3, 4, 10, "backend message"));
        assertEquals("PostgreSQL backend message length is below the 4 byte minimum: 3", low.getMessage());
        IOException high = assertThrows(IOException.class, () -> PostgresWire.checkedLength(11, 4, 10, "backend message"));
        assertEquals("PostgreSQL backend message length exceeds the 10 byte limit: 11", high.getMessage());
    }

    @Test
    void aStreamThatEndsMidMessageIsAnEof() {
        byte[] truncated = {'Q', 0, 0, 0, 9, 'S'};

        assertThrows(EOFException.class, () ->
                PostgresWire.read(new ByteArrayInputStream(truncated), 1024, "message"));
        assertThrows(EOFException.class, () ->
                PostgresWire.read(new ByteArrayInputStream(new byte[0]), 1024, "message"));
    }

    @Test
    void aStartupMessageCarriesItsParametersInOrderAndReadsBack() throws IOException {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("user", "admin");
        parameters.put("database", "postgres");
        parameters.put("options", "-c search_path=public");

        byte[] packet = PostgresWire.startupMessage(parameters);
        PostgresWire.Startup startup = PostgresWire.readStartup(new ByteArrayInputStream(packet), 10_000,
                "startup message");

        assertEquals(PostgresWire.PROTOCOL_3, startup.code());
        Map<String, String> read = PostgresWire.startupParameters(startup.body());
        assertEquals(parameters, read);
        assertEquals(List.of("user", "database", "options"), List.copyOf(read.keySet()));
    }

    @Test
    void anSslRequestIsAStartupPacketWithNoBody() throws IOException {
        byte[] request = {0, 0, 0, 8, 4, (byte) 0xD2, 0x16, 0x2F};

        PostgresWire.Startup startup = PostgresWire.readStartup(new ByteArrayInputStream(request), 10_000,
                "startup message");

        assertEquals(PostgresWire.SSL_REQUEST, startup.code());
        assertEquals(0, startup.body().length);
    }

    @Test
    void startupParametersStopAtTheEmptyNameOrAnUnterminatedOne() {
        byte[] body = {'u', 's', 'e', 'r', 0, 'a', 0, 0, 'x', 0, 'y', 0};
        byte[] unterminated = {'u', 's', 'e', 'r', 0, 'a', 0, 'd', 'b'};

        assertEquals(Map.of("user", "a"), PostgresWire.startupParameters(body));
        assertEquals(Map.of("user", "a"), PostgresWire.startupParameters(unterminated));
    }

    @Test
    void anErrorResponseKeepsItsFieldsInOrder() {
        Map<Character, String> fields = new LinkedHashMap<>();
        fields.put('S', "FATAL");
        fields.put('C', "28000");
        fields.put('M', "access denied");

        byte[] framed = PostgresWire.frame('E', PostgresWire.fields(fields));

        assertEquals(fields, PostgresWire.parseFields(framed, 5));
        assertEquals(List.of('S', 'C', 'M'), List.copyOf(PostgresWire.parseFields(framed, 5).keySet()));
    }
}
