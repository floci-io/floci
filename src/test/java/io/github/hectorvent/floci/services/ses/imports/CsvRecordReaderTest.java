package io.github.hectorvent.floci.services.ses.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ses.model.ImportJob;
import io.github.hectorvent.floci.services.ses.model.TopicPreference;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the CSV reader: where a record ends, how a field is split, and which malformed
 * input fails one record versus the whole file. Reading is driven through the factory, the way the
 * import worker reaches it.
 */
class CsvRecordReaderTest {

    private static ImportJob job(String destinationType, String action, String contactList) {
        ImportJob job = new ImportJob();
        job.setDataFormat(ImportJob.FORMAT_CSV);
        job.setDestinationType(destinationType);
        job.setImportAction(action);
        job.setContactListName(contactList);
        return job;
    }

    private static ImportJob suppression(String action) {
        return job(ImportJob.DESTINATION_SUPPRESSION_LIST, action, null);
    }

    private static ImportJob contacts() {
        return job(ImportJob.DESTINATION_CONTACT_LIST, ImportJob.ACTION_PUT, "newsletter");
    }

    private static RecordReader reader(ImportJob job, String content) {
        return new RecordReaderFactory(new ObjectMapper())
                .forJob(job, new BufferedReader(new StringReader(content)));
    }

    private static List<ImportRecord> readAll(ImportJob job, String content) throws IOException {
        RecordReader reader = reader(job, content);
        List<ImportRecord> records = new ArrayList<>();
        for (ImportRecord record = reader.next(); record != null; record = reader.next()) {
            records.add(record);
        }
        return records;
    }

    private static Map<String, String> preferences(ImportRecord record) {
        return record.topicPreferences().stream()
                .collect(Collectors.toMap(TopicPreference::getTopicName, TopicPreference::getSubscriptionStatus));
    }

    @Test
    void suppressionRows_carryTheAddressReasonAndLineNumber() throws IOException {
        List<ImportRecord> records = readAll(suppression(ImportJob.ACTION_PUT),
                "alice@example.com,BOUNCE\nbob@example.com,COMPLAINT\n");

        assertEquals(2, records.size());
        assertEquals("alice@example.com", records.get(0).emailAddress());
        assertEquals("BOUNCE", records.get(0).reason());
        assertEquals(1, records.get(0).line());
        assertEquals(2, records.get(1).line());
    }

    @Test
    void deleteRowsCarryNoReason_andAnExtraColumnFailsTheRecord() throws IOException {
        List<ImportRecord> records = readAll(suppression(ImportJob.ACTION_DELETE),
                "alice@example.com\nbob@example.com,BOUNCE\n");

        assertNull(records.get(0).reason());
        assertNull(records.get(0).error());
        assertTrue(records.get(1).error().contains("1 column(s) but found 2"));
    }

    @Test
    void quotedFieldMaySpanLines_andTheNextRecordKeepsCounting() throws IOException {
        List<ImportRecord> records = readAll(contacts(), "emailAddress,attributesData\r\n"
                + "alice@example.com,\"{\r\n  \"\"Name\"\": \"\"Alice\"\"\r\n}\"\r\n"
                + "bob@example.com,\r\n");

        assertEquals(2, records.size());
        assertEquals("{\r\n  \"Name\": \"Alice\"\r\n}", records.get(0).attributesData());
        // The header is record 1 and the multi-line row is record 2, so the row after it is 3:
        // records are numbered by position, not by physical line.
        assertEquals(2, records.get(0).line());
        assertEquals(3, records.get(1).line());
        assertEquals("bob@example.com", records.get(1).emailAddress());
    }

    @Test
    void headerTopicColumnsBecomePreferences_andBlankCellsAreLeftOut() throws IOException {
        List<ImportRecord> records = readAll(contacts(),
                "emailAddress,unsubscribeAll,topicPreferences.Sports,topicPreferences.Cycling\n"
                        + "alice@example.com,true,OPT_IN,\n");

        ImportRecord alice = records.get(0);
        assertEquals(Boolean.TRUE, alice.unsubscribeAll());
        assertEquals(Map.of("Sports", "OPT_IN"), preferences(alice));
    }

    @Test
    void aBomAndBlankLinesDoNotBecomeRecords() throws IOException {
        List<ImportRecord> records = readAll(suppression(ImportJob.ACTION_DELETE),
                "﻿alice@example.com\n\n\nbob@example.com\n");

        assertEquals(2, records.size());
        assertEquals("alice@example.com", records.get(0).emailAddress());
        // Blank lines are skipped but still counted, so the reported line matches the file.
        assertEquals(4, records.get(1).line());
    }

    @Test
    void badQuotingFailsOneRecordAndTheReaderCarriesOn() throws IOException {
        List<ImportRecord> records = readAll(contacts(), "emailAddress,attributesData\n"
                + "alice@example.com,\"x\"junk\n"
                + "bob@example.com,ab\"c\n"
                + "carol@example.com,fine\n");

        assertTrue(records.get(0).error().contains("closing quote"));
        assertTrue(records.get(1).error().contains("unquoted field"));
        assertEquals("carol@example.com", records.get(2).emailAddress());
        assertNull(records.get(2).error());
    }

    @Test
    void anUnterminatedQuoteFailsTheWholeFile() {
        RecordReader reader = reader(contacts(), "emailAddress,attributesData\nalice@example.com,\"{oops\n");

        AwsException e = assertThrows(AwsException.class, () -> {
            for (ImportRecord record = reader.next(); record != null; record = reader.next()) {
                // drain until the scanner reaches the end of the unterminated field
            }
        });
        assertEquals("BadRequestException", e.getErrorCode());
        assertTrue(e.getMessage().contains("unterminated"));
    }

    @Test
    void aContactFileWithoutAnEmailAddressHeaderFailsTheWholeFile() {
        RecordReader reader = reader(contacts(), "email,unsubscribeAll\nalice@example.com,false\n");

        AwsException e = assertThrows(AwsException.class, reader::next);
        assertEquals("BadRequestException", e.getErrorCode());
        assertTrue(e.getMessage().contains("emailAddress"));
    }

    @Test
    void anEmptyContactFileFailsForTheMissingHeader() {
        AwsException e = assertThrows(AwsException.class, () -> reader(contacts(), "").next());
        assertTrue(e.getMessage().contains("header line"));
    }

    @Test
    void anEmptySuppressionFileYieldsNoRecords() throws IOException {
        assertTrue(readAll(suppression(ImportJob.ACTION_PUT), "").isEmpty());
    }

    @Test
    void splitCsvLine_handlesQuotesCommasAndEscapedQuotes() {
        assertEquals(List.of("a", "b,c", "say \"hi\"", ""),
                CsvRecordReader.splitCsvLine("a,\"b,c\",\"say \"\"hi\"\"\","));
        assertEquals(List.of(""), CsvRecordReader.splitCsvLine(""));
    }
}
