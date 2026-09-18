package io.github.hectorvent.floci.services.ses.imports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import io.github.hectorvent.floci.services.ses.model.ImportJob;
import io.github.hectorvent.floci.services.ses.model.TopicPreference;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Reads newline-delimited JSON: one complete object per line. */
final class JsonLineReader implements RecordReader {
    private final BufferedReader source;
    private final ImportJob job;
    private final ObjectReader lineReader;
    private int lineNumber;

    JsonLineReader(BufferedReader source, ImportJob job, ObjectReader lineReader) {
        this.source = source;
        this.job = job;
        this.lineReader = lineReader;
    }

    @Override
    public ImportRecord next() throws IOException {
        String read;
        while ((read = source.readLine()) != null) {
            lineNumber++;
            String line = (lineNumber == 1 ? stripBom(read) : read).trim();
            if (line.isEmpty()) {
                continue;
            }
            JsonNode node;
            try {
                node = lineReader.readTree(line);
            } catch (JsonProcessingException e) {
                return ImportRecord.invalid(lineNumber, "Malformed JSON: " + e.getOriginalMessage());
            }
            try {
                return jsonRecord(lineNumber, node, job);
            } catch (InvalidRecordException e) {
                return ImportRecord.invalid(lineNumber, e.getMessage());
            }
        }
        return null;
    }

    private static ImportRecord jsonRecord(int line, JsonNode node, ImportJob job) {
        if (!node.isObject()) {
            throw new InvalidRecordException("Each line must be a JSON object.");
        }
        String email = textMember(node, "emailAddress");
        if (ImportJob.DESTINATION_SUPPRESSION_LIST.equals(job.getDestinationType())) {
            return new ImportRecord(line, email, textMember(node, "reason"), null, null, null, null);
        }
        Boolean unsubscribeAll = null;
        JsonNode unsubscribeNode = node.get("unsubscribeAll");
        if (unsubscribeNode != null && !unsubscribeNode.isNull()) {
            if (!unsubscribeNode.isBoolean()) {
                throw new InvalidRecordException("unsubscribeAll must be a boolean.");
            }
            unsubscribeAll = unsubscribeNode.booleanValue();
        }
        List<TopicPreference> prefs = null;
        JsonNode prefsNode = node.get("topicPreferences");
        if (prefsNode != null && !prefsNode.isNull()) {
            if (!prefsNode.isArray()) {
                throw new InvalidRecordException("topicPreferences must be an array.");
            }
            prefs = new ArrayList<>();
            for (JsonNode p : prefsNode) {
                if (!p.isObject()) {
                    throw new InvalidRecordException("Each topicPreferences element must be a JSON object.");
                }
                prefs.add(new TopicPreference(textMember(p, "topicName"), textMember(p, "subscriptionStatus")));
            }
        }
        return new ImportRecord(line, email, null, unsubscribeAll, textMember(node, "attributesData"), prefs, null);
    }

    // A present member of the wrong JSON type is an invalid record, not coerced text, matching the
    // SerializationException the CreateContact / PutSuppressedDestination bodies get.
    private static String textMember(JsonNode node, String field) {
        JsonNode member = node.get(field);
        if (member == null || member.isNull()) {
            return null;
        }
        if (!member.isTextual()) {
            throw new InvalidRecordException(field + " must be a JSON string.");
        }
        return member.textValue();
    }

    private static String stripBom(String text) {
        return text.startsWith("﻿") ? text.substring(1) : text;
    }
}
