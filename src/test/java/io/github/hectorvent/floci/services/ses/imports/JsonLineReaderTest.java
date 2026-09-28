package io.github.hectorvent.floci.services.ses.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the newline-delimited JSON reader: one object per line, and which malformed lines
 * become failed records. Reading is driven through the factory, the way the import worker reaches
 * it, so the strict parse the factory configures is exercised too.
 */
class JsonLineReaderTest {

    private static ImportJob job(String destinationType) {
        ImportJob job = new ImportJob();
        job.setDataFormat(ImportJob.FORMAT_JSON);
        job.setDestinationType(destinationType);
        job.setImportAction(ImportJob.ACTION_PUT);
        job.setContactListName("newsletter");
        return job;
    }

    private static List<ImportRecord> readAll(ImportJob job, String content) throws IOException {
        RecordReader reader = new RecordReaderFactory(new ObjectMapper())
                .forJob(job, new BufferedReader(new StringReader(content)));
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
    void suppressionLinesCarryTheAddressAndReason() throws IOException {
        List<ImportRecord> records = readAll(job(ImportJob.DESTINATION_SUPPRESSION_LIST), """
                {"emailAddress":"alice@example.com","reason":"BOUNCE"}
                {"emailAddress":"bob@example.com"}
                """);

        assertEquals(2, records.size());
        assertEquals("alice@example.com", records.get(0).emailAddress());
        assertEquals("BOUNCE", records.get(0).reason());
        assertNull(records.get(1).reason());
    }

    @Test
    void contactLinesCarryPreferencesAttributesAndTheUnsubscribeFlag() throws IOException {
        List<ImportRecord> records = readAll(job(ImportJob.DESTINATION_CONTACT_LIST), """
                {"emailAddress":"alice@example.com","unsubscribeAll":false,"attributesData":"{\\"Name\\":\\"Alice\\"}",\
                "topicPreferences":[{"topicName":"Sports","subscriptionStatus":"OPT_IN"},\
                {"topicName":"Cycling","subscriptionStatus":"OPT_OUT"}]}
                """);

        ImportRecord alice = records.get(0);
        assertEquals(Boolean.FALSE, alice.unsubscribeAll());
        assertEquals("{\"Name\":\"Alice\"}", alice.attributesData());
        assertEquals(Map.of("Sports", "OPT_IN", "Cycling", "OPT_OUT"), preferences(alice));
    }

    @Test
    void aBomAndBlankLinesDoNotBecomeRecords() throws IOException {
        List<ImportRecord> records = readAll(job(ImportJob.DESTINATION_SUPPRESSION_LIST),
                "﻿{\"emailAddress\":\"alice@example.com\"}\n\n  \n{\"emailAddress\":\"bob@example.com\"}\n");

        assertEquals(2, records.size());
        assertEquals("alice@example.com", records.get(0).emailAddress());
        // Blank lines are skipped but still counted, so the reported line matches the file.
        assertEquals(4, records.get(1).line());
    }

    @Test
    void malformedJsonAndTrailingTokensAreFailedRecords() throws IOException {
        List<ImportRecord> records = readAll(job(ImportJob.DESTINATION_SUPPRESSION_LIST), """
                not json at all
                {"emailAddress":"alice@example.com"} garbage
                ["emailAddress"]
                {"emailAddress":"bob@example.com"}
                """);

        assertTrue(records.get(0).error().contains("Malformed JSON"));
        // The factory configures FAIL_ON_TRAILING_TOKENS, so the leading object is not accepted.
        assertTrue(records.get(1).error().contains("Malformed JSON"));
        assertTrue(records.get(2).error().contains("JSON object"));
        assertNull(records.get(3).error());
    }

    @Test
    void membersOfTheWrongTypeAreFailedRecordsRatherThanCoerced() throws IOException {
        List<ImportRecord> records = readAll(job(ImportJob.DESTINATION_CONTACT_LIST), """
                {"emailAddress":"alice@example.com","attributesData":123}
                {"emailAddress":"bob@example.com","unsubscribeAll":"yes"}
                {"emailAddress":"carol@example.com","topicPreferences":"Sports"}
                {"emailAddress":"dave@example.com","topicPreferences":["Sports"]}
                {"emailAddress":"erin@example.com","topicPreferences":[{"topicName":"Sports","subscriptionStatus":true}]}
                """);

        assertTrue(records.get(0).error().contains("attributesData"));
        assertTrue(records.get(1).error().contains("unsubscribeAll"));
        assertTrue(records.get(2).error().contains("topicPreferences"));
        assertTrue(records.get(3).error().contains("JSON object"));
        assertTrue(records.get(4).error().contains("subscriptionStatus"));
    }

    @Test
    void anEmptySourceYieldsNoRecords() throws IOException {
        assertTrue(readAll(job(ImportJob.DESTINATION_SUPPRESSION_LIST), "").isEmpty());
    }
}
