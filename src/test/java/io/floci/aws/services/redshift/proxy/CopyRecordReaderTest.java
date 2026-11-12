package io.floci.aws.services.redshift.proxy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CopyRecordReaderTest {
    @Test
    void physicalLineNumbersIncludeQuotedAndEscapedNewlines() throws IOException {
        CopyRecordReader reader = reader("1,\"a\n\nb\"\r\n\r\n2,last\n", "CSV");
        assertEquals("1,\"a\n\nb\"\r\n", next(reader));
        assertEquals(1, reader.recordLine());
        assertEquals("\r\n", next(reader));
        assertEquals(4, reader.recordLine());
        assertEquals("2,last\n", next(reader));
        assertEquals(5, reader.recordLine());
    }

    @Test
    void wholeJsonModeRetainsPrettyPrintedObjectsAndBracesInsideStrings() throws IOException {
        CopyStatementParser.S3CopyFrom spec = (CopyStatementParser.S3CopyFrom) CopyStatementParser.parse(
                "COPY t FROM 's3://b/k' JSON 'auto'");
        CopyRecordReader reader = new CopyRecordReader(new ByteArrayInputStream(
                "{\n\"name\":\"a}b\"\n}\n{\"name\":\"last\"}".getBytes(StandardCharsets.UTF_8)), spec, true);
        assertEquals("{\n\"name\":\"a}b\"\n}\n", next(reader));
        assertEquals("{\"name\":\"last\"}\n", next(reader));
        assertEquals(4, reader.recordLine());
        assertNull(next(reader));
    }

    private static CopyRecordReader reader(String content, String options) {
        CopyStatementParser.S3CopyFrom spec = (CopyStatementParser.S3CopyFrom) CopyStatementParser.parse(
                "COPY events FROM 's3://bucket/data' " + options);
        return new CopyRecordReader(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), spec);
    }

    private static String next(CopyRecordReader reader) throws IOException {
        byte[] record = reader.next();
        return record == null ? null : new String(record, StandardCharsets.UTF_8);
    }

    @Test
    void csvQuotedNewlinesStayInTheSameRecord() throws IOException {
        CopyRecordReader reader = reader("1,\"hello\nworld\"\r\n2,last", "CSV");
        assertEquals("1,\"hello\nworld\"\r\n", next(reader));
        assertEquals("2,last\n", next(reader));
        assertNull(next(reader));
    }

    @Test
    void csvDoubledQuotesDoNotEndAField() throws IOException {
        CopyRecordReader reader = reader("1,\"a\"\"b\nnext\"\n2,c\n", "CSV");
        assertEquals("1,\"a\"\"b\nnext\"\n", next(reader));
        assertEquals("2,c\n", next(reader));
    }

    @Test
    void csvQuotesCanStartWithinAField() throws IOException {
        CopyRecordReader reader = reader("1,pre\"line\nbreak\"suffix\n2,x\n", "CSV");
        assertEquals("1,pre\"line\nbreak\"suffix\n", next(reader));
        assertEquals("2,x\n", next(reader));
    }

    @Test
    void bareCarriageReturnsSeparateRecords() throws IOException {
        CopyRecordReader reader = reader("1|a\r2|b\r", "DELIMITER '|'");
        assertEquals("1|a\r", next(reader));
        assertEquals("2|b\r", next(reader));
        assertNull(next(reader));
    }

    @Test
    void escapedTextNewlinesStayInTheSameRecord() throws IOException {
        CopyRecordReader reader = reader("1|a\\\nb\n2|c\n", "DELIMITER '|'");
        assertEquals("1|a\\\nb\n", next(reader));
        assertEquals("2|c\n", next(reader));
    }

    @Test
    void removeQuotesDoesNotCloseOnAnInteriorQuote() throws IOException {
        CopyRecordReader reader = reader("1|'ab'cd\nef'\n2|g\n", "DELIMITER '|' REMOVEQUOTES");
        assertEquals("1|'ab'cd\nef'\n", next(reader));
        assertEquals("2|g\n", next(reader));
    }

    @Test
    void unterminatedCsvQuoteAbortsInsteadOfGuessingTheNextRecord() {
        CopyRecordReader reader = reader("1,\"bad\n2,still_bad\n", "CSV");
        S3CopySimulator.S3TransferException error = assertThrows(
                S3CopySimulator.S3TransferException.class, reader::next);
        assertEquals("22P04", error.sqlState());
    }

    @Test
    void jsonQuotesDoNotHideLineBoundaries() throws IOException {
        CopyRecordReader reader = reader("{\"name\":\"bad}\n{\"name\":\"good\"}\n", "FORMAT JSON 'auto'");
        assertEquals("{\"name\":\"bad}\n", next(reader));
        assertEquals("{\"name\":\"good\"}\n", next(reader));
    }
}
