package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.floci.duck.FlociDuckClient;
import io.github.hectorvent.floci.services.s3.EventStreamMessages.Message;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SelectObjectContent over CSV and JSON read a record at a time, with the result written as it is
 * produced, so neither the object nor the result is held in memory.
 */
class S3SelectStreamingTest {

    private static final String CSV_USE = "<CSV><FileHeaderInfo>USE</FileHeaderInfo></CSV>";
    private static final String CSV_NONE = "<CSV><FileHeaderInfo>NONE</FileHeaderInfo></CSV>";
    private static final String JSON_IN = "<JSON/>";
    private static final String CSV_OUT = "<CSV/>";

    private S3SelectService service;

    @BeforeEach
    void setUp() {
        FlociDuckClient duck = mock(FlociDuckClient.class);
        when(duck.isAvailable()).thenReturn(false);
        service = new S3SelectService(new ObjectMapper(), duck);
    }

    @Test
    void aCsvSelectStopsReadingAnEndlessObjectAtItsLimit() {
        InputStream body = endless(i -> i == 0 ? "name,age\n" : "name" + i + "," + i + "\n");

        List<Message> events = assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> select(body, "SELECT name FROM S3Object WHERE age > 5 LIMIT 3", CSV_USE, CSV_OUT));

        assertEquals("name6\nname7\nname8\n", records(events));
    }

    @Test
    void aJsonLinesSelectStopsReadingAnEndlessObjectAtItsLimit() {
        InputStream body = endless(i -> "{\"id\":" + i + "}\n");

        List<Message> events = assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> select(body, "SELECT id FROM S3Object WHERE id >= 10 LIMIT 2", JSON_IN, CSV_OUT));

        assertEquals("10\n11\n", records(events));
    }

    @Test
    void aJsonArrayIsReadAnElementAtATime() {
        InputStream body = endless(i -> (i == 0 ? "[" : ",") + "{\"id\":" + i + "}");

        List<Message> events = assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> select(body, "SELECT * FROM S3Object LIMIT 2", JSON_IN, "<JSON/>"));

        assertEquals("{\"id\":0}\n{\"id\":1}\n", records(events));
    }

    @Test
    void aMalformedJsonArrayReturnsTheRecordsBeforeIt() throws IOException {
        List<Message> events = select(bytes("[{\"id\":1},{\"id\":2},{\"id\":"), "SELECT id FROM S3Object",
                JSON_IN, CSV_OUT);

        assertEquals("1\n2\n", records(events));
    }

    @Test
    void aLargeResultIsWrittenAsSeveralRecordsEventsOfWholeRecords() throws IOException {
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            csv.append("row").append(i).append(',').append(i).append('\n');
        }

        List<Message> events = select(bytes(csv.toString()), "SELECT * FROM S3Object", CSV_NONE, CSV_OUT);

        List<Message> recordEvents = events.stream().filter(event -> "Records".equals(event.type())).toList();
        assertTrue(recordEvents.size() > 1, "a result of " + csv.length() + " bytes in one event");
        for (Message event : recordEvents) {
            assertTrue(new String(event.payload(), StandardCharsets.UTF_8).endsWith("\n"), "a record split across events");
        }
        assertEquals(csv.toString(), records(events));
        assertEquals(List.of("Stats", "End"), events.subList(events.size() - 2, events.size()).stream()
                .map(Message::type).toList());
        String stats = new String(events.get(events.size() - 2).payload(), StandardCharsets.UTF_8);
        assertTrue(stats.contains("<BytesReturned>" + csv.length() + "</BytesReturned>"), stats);
        assertTrue(stats.contains("<BytesScanned>" + csv.length() + "</BytesScanned>"), stats);
    }

    @Test
    void aResultWithNoRecordsStillHasARecordsEvent() throws IOException {
        List<Message> events = select(bytes("Alice,30\nBob,25\n"), "SELECT * FROM S3Object WHERE _2 > 99",
                CSV_NONE, CSV_OUT);

        assertEquals(List.of("Records", "Stats", "End"), events.stream().map(Message::type).toList());
        assertEquals(0, events.get(0).payload().length);
    }

    @Test
    void aStatementTheEvaluatorDoesNotReadReturnsTheObjectAsItIs() throws IOException {
        byte[] object = new byte[200_000];
        for (int i = 0; i < object.length; i++) {
            object[i] = (byte) ('a' + i % 26);
        }

        List<Message> events = select(object, "DESCRIBE S3Object", CSV_NONE, CSV_OUT);

        ByteArrayOutputStream returned = new ByteArrayOutputStream();
        events.stream().filter(event -> "Records".equals(event.type()))
                .forEach(event -> returned.writeBytes(event.payload()));
        assertArrayEquals(object, returned.toByteArray());
    }

    @Test
    void aStatementThatCannotBeEvaluatedFailsBeforeTheObjectIsReadAndClosesIt() {
        TrackedStream body = new TrackedStream(bytes("Alice,30\n"));

        AwsException error = assertThrows(AwsException.class, () -> service.select(read(body, 9),
                request("SELECT * FROM S3Object s WHERE CAST(s._2 AS INTEGER) > 25", CSV_NONE, CSV_OUT)));

        assertEquals("ExternalEvalException", error.getErrorCode());
        assertFalse(body.read, "the object was read");
        assertTrue(body.closed, "the object was left open");
    }

    @Test
    void theObjectIsClosedOnceTheResponseIsWritten() throws IOException {
        TrackedStream body = new TrackedStream(bytes("Alice,30\n"));

        S3SelectService.EventStream events = service.select(read(body, 9),
                request("SELECT * FROM S3Object", CSV_NONE, CSV_OUT));
        assertFalse(body.closed, "closed before the response was written");
        events.writeTo(new ByteArrayOutputStream());

        assertTrue(body.closed);
    }

    @Test
    void aLimitThatStopsEarlyReportsTheBytesItRead() throws IOException {
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < 100_000; i++) {
            csv.append("row").append(i).append(',').append(i).append('\n');
        }

        List<Message> events = select(bytes(csv.toString()), "SELECT * FROM S3Object LIMIT 1", CSV_NONE, CSV_OUT);

        assertEquals("row0,0\n", records(events));
        long scanned = stat(events, "BytesScanned");
        assertTrue(scanned > 0 && scanned < csv.length(), "scanned " + scanned + " of " + csv.length());
        assertEquals(scanned, stat(events, "BytesProcessed"));
    }

    @Test
    void aLineOfExactlyTheRecordLimitIsARecord() throws IOException {
        String line = "x".repeat(S3SelectEvaluator.MAX_RECORD_CHARS);

        List<Message> events = select(bytes(line + "\r\nend\n"), "SELECT * FROM S3Object", CSV_NONE, CSV_OUT);

        assertEquals(line + "\nend\n", records(events));
    }

    @Test
    void aCsvLineOverTheRecordLimitEndsTheStreamWithOverMaxRecordSize() throws IOException {
        String tooLong = "x".repeat(S3SelectEvaluator.MAX_RECORD_CHARS + 1);

        List<Message> events = select(bytes("a,1\n" + tooLong + "\nb,2\n"), "SELECT * FROM S3Object",
                CSV_NONE, CSV_OUT);

        assertOverMaxRecordSizeAfter("a,1\n", events);
    }

    @Test
    void anEndlessLineEndsTheStreamWithoutBeingHeld() {
        List<Message> events = assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> select(endless(i -> "xxxxxxxxxxxxxxxx"), "SELECT * FROM S3Object", CSV_NONE, CSV_OUT));

        assertOverMaxRecordSizeAfter("", events);
    }

    @Test
    void aJsonArrayElementOverTheRecordLimitEndsTheStream() throws IOException {
        String element = "{\"id\":\"" + "x".repeat(S3SelectEvaluator.MAX_RECORD_CHARS) + "\"}";

        List<Message> events = select(bytes("[{\"id\":1}," + element + ",{\"id\":3}]"),
                "SELECT id FROM S3Object", JSON_IN, CSV_OUT);

        assertOverMaxRecordSizeAfter("1\n", events);
    }

    @Test
    void whitespaceBetweenJsonArrayElementsIsNotPartOfARecord() throws IOException {
        String gap = " \n".repeat(S3SelectEvaluator.MAX_RECORD_CHARS);

        List<Message> events = select(bytes("[{\"id\":1}," + gap + "{\"id\":2}" + gap + "]"),
                "SELECT id FROM S3Object", JSON_IN, CSV_OUT);

        assertEquals("1\n2\n", records(events));
        assertEquals("End", events.get(events.size() - 1).type());
    }

    @Test
    void aNumericJsonArrayElementOverTheRecordLimitEndsTheStream() throws IOException {
        // The parser reads a number whole and then gives up on its own length limit. The element is
        // counted only to within what the parser has buffered ahead, so this one is over the record
        // limit by more than that.
        String number = "9".repeat(S3SelectEvaluator.MAX_RECORD_CHARS + 32 * 1024);

        List<Message> events = select(bytes("[1," + number + ",3]"), "SELECT * FROM S3Object", JSON_IN, "<JSON/>");

        assertOverMaxRecordSizeAfter("1\n", events);
    }

    @Test
    void anEndlessNumericJsonArrayElementEndsTheStreamWithoutBeingHeld() {
        InputStream body = endless(i -> i == 0 ? "[1,9" : "9999999999999999");

        List<Message> events = assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> select(body, "SELECT * FROM S3Object", JSON_IN, "<JSON/>"));

        assertOverMaxRecordSizeAfter("1\n", events);
    }

    @Test
    void anEndlessJsonArrayElementEndsTheStreamWithoutBeingHeld() {
        InputStream body = endless(i -> i == 0 ? "[{\"id\":\"" : "xxxxxxxxxxxxxxxx");

        List<Message> events = assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> select(body, "SELECT * FROM S3Object", JSON_IN, "<JSON/>"));

        assertOverMaxRecordSizeAfter("", events);
    }

    @Test
    void aResultRecordOverTheRecordLimitEndsTheStream() throws IOException {
        // Each input line is under the limit; the JSON record made of the header and the value is not.
        String column = "c".repeat(600_000);
        String value = "v".repeat(500_000);

        List<Message> events = select(bytes(column + "\n" + value + "\n"), "SELECT * FROM S3Object",
                CSV_USE, "<JSON/>");

        assertOverMaxRecordSizeAfter("", events);
    }

    private List<Message> select(byte[] object, String expression, String input, String output) throws IOException {
        return select(new ByteArrayInputStream(object), object.length, expression, input, output);
    }

    private List<Message> select(InputStream object, String expression, String input, String output)
            throws IOException {
        return select(object, Long.MAX_VALUE, expression, input, output);
    }

    private List<Message> select(InputStream object, long size, String expression, String input, String output)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.select(read(object, size), request(expression, input, output)).writeTo(out);
        return EventStreamMessages.decode(out.toByteArray());
    }

    private static S3Service.ObjectRead read(InputStream body, long size) {
        return new S3Service.ObjectRead(new S3Object("bucket", "data", size, "text/plain", "\"etag\""), body);
    }

    private static String request(String expression, String input, String output) {
        return """
                <SelectObjectContentRequest>
                    <Expression>%s</Expression>
                    <ExpressionType>SQL</ExpressionType>
                    <InputSerialization>%s</InputSerialization>
                    <OutputSerialization>%s</OutputSerialization>
                </SelectObjectContentRequest>
                """.formatted(expression.replace("&", "&amp;").replace("<", "&lt;"), input, output);
    }

    private static void assertOverMaxRecordSizeAfter(String records, List<Message> events) {
        Message last = events.get(events.size() - 1);
        assertEquals("error", last.type(), "the stream does not end with an error message");
        assertEquals("OverMaxRecordSize", last.headers().get(":error-code"));
        assertEquals("The length of a record in the input or result is greater than the maxCharsPerRecord limit of 1 MB.",
                last.headers().get(":error-message"));
        assertEquals(0, last.payload().length);
        assertEquals(records, records(events));
        assertTrue(events.stream().noneMatch(event -> "Stats".equals(event.type()) || "End".equals(event.type())),
                "an errored stream has no Stats or End");
    }

    private static long stat(List<Message> events, String name) {
        String stats = events.stream().filter(event -> "Stats".equals(event.type())).findFirst().orElseThrow().text();
        int start = stats.indexOf("<" + name + ">") + name.length() + 2;
        return Long.parseLong(stats.substring(start, stats.indexOf("</" + name + ">")));
    }

    private static String records(List<Message> events) {
        StringBuilder records = new StringBuilder();
        for (Message event : events) {
            if ("Records".equals(event.type())) {
                records.append(new String(event.payload(), StandardCharsets.UTF_8));
            }
        }
        return records.toString();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** {@code next.apply(0)}, {@code next.apply(1)}, ... encoded as UTF-8, without end. */
    private static InputStream endless(LongFunction<String> next) {
        return new InputStream() {
            private long index;
            private byte[] current = new byte[0];
            private int position;

            @Override
            public int read() {
                if (position == current.length) {
                    current = next.apply(index++).getBytes(StandardCharsets.UTF_8);
                    position = 0;
                }
                return current[position++] & 0xFF;
            }
        };
    }

    /** A body that records whether it was read or closed. */
    private static final class TrackedStream extends ByteArrayInputStream {
        private boolean read;
        private boolean closed;

        TrackedStream(byte[] data) {
            super(data);
        }

        @Override
        public synchronized int read() {
            read = true;
            return super.read();
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            read = true;
            return super.read(buffer, offset, length);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
