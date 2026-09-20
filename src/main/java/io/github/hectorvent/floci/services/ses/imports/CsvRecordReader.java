package io.github.hectorvent.floci.services.ses.imports;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ses.model.ImportJob;
import io.github.hectorvent.floci.services.ses.model.TopicPreference;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads RFC 4180 records one at a time: a record ends at an unquoted line break (a quoted
 * attributesData may span lines), the first record of a contact-list file is the header, and
 * a row whose width or quoting is wrong is a failed record. Records are numbered by position.
 *
 * <p>Deliberately not {@link io.github.hectorvent.floci.core.common.CsvParser}, which parses a
 * whole document held as a {@code String} and is permissive by design (a quote inside an
 * unquoted field is literal, an unterminated quoted field simply ends at EOF, and
 * {@code parseAll} applies the backslash escapes S3 Select, Athena, and the Step Functions
 * ItemReader expect). An import reads its source straight off an S3 stream so a large file is
 * never materialized, and it has to report malformed quoting rather than absorb it, since a bad
 * row has to land in {@code FailedRecordsCount} or fail the job. Nothing else in Floci streams
 * CSV out of S3 yet: DynamoDB's {@code ImportTable} sidesteps it by rejecting the CSV
 * {@code InputFormat} outright, so this is the first of its kind, and the place to grow a
 * shared streaming reader from if that changes.
 */
final class CsvRecordReader implements RecordReader {

    private static final String TOPIC_PREFERENCE_COLUMN_PREFIX = "topicPreferences.";
    private final Reader source;
    private final ImportJob job;
    private final boolean contactList;
    private int pending = -1;
    private boolean bomChecked;
    private int recordNumber;
    private Map<String, Integer> columns;

    CsvRecordReader(Reader source, ImportJob job) {
        this.source = source;
        this.job = job;
        this.contactList = ImportJob.DESTINATION_CONTACT_LIST.equals(job.getDestinationType());
    }

    @Override
    public ImportRecord next() throws IOException {
        String raw;
        while ((raw = nextRawRecord()) != null) {
            recordNumber++;
            if (raw.isBlank()) {
                continue;
            }
            List<String> fields;
            try {
                fields = splitCsvLine(raw);
            } catch (InvalidRecordException e) {
                if (contactList && columns == null) {
                    throw new AwsException("BadRequestException", "Malformed CSV header: " + e.getMessage(), 400);
                }
                return ImportRecord.invalid(recordNumber, e.getMessage());
            }
            if (contactList && columns == null) {
                columns = readContactHeader(fields);
                continue;
            }
            if (!contactList) {
                // The documented formats are exactly "email,reason" for PUT and "email" for DELETE.
                int expected = ImportJob.ACTION_PUT.equals(job.getImportAction()) ? 2 : 1;
                if (fields.size() != expected) {
                    return ImportRecord.invalid(recordNumber,
                            "Expected " + expected + " column(s) but found " + fields.size() + ".");
                }
                return new ImportRecord(recordNumber, fields.get(0), expected == 2 ? fields.get(1) : null,
                        null, null, null, null);
            }
            if (fields.size() != columns.size()) {
                return ImportRecord.invalid(recordNumber,
                        "Expected " + columns.size() + " column(s) but found " + fields.size() + ".");
            }
            return contactRecordFromCsv(recordNumber, fields, columns);
        }
        if (contactList && columns == null) {
            throw new AwsException("BadRequestException",
                    "The CSV file must start with a header line naming the emailAddress column.", 400);
        }
        return null;
    }

    // Only a quote at the start of a field opens a quoted section (a bare quote elsewhere is
    // left for splitCsvLine to reject as a malformed row), so one bad row cannot swallow the
    // rest of the file; a doubled quote inside a quoted field stays inside it.
    private String nextRawRecord() throws IOException {
        int start = read();
        if (start < 0) {
            return null;
        }
        pushBack(start);
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean fieldStart = true;
        int next;
        while ((next = read()) >= 0) {
            char c = (char) next;
            if (quoted) {
                if (c == '"') {
                    int peek = read();
                    if (peek == '"') {
                        current.append(c);
                    } else {
                        quoted = false;
                        pushBack(peek);
                    }
                }
                current.append(c);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r') {
                    int peek = read();
                    if (peek != '\n') {
                        pushBack(peek);
                    }
                }
                return current.toString();
            } else {
                if (c == '"' && fieldStart) {
                    quoted = true;
                }
                fieldStart = c == ',';
                current.append(c);
            }
        }
        if (quoted) {
            throw new AwsException("BadRequestException", "Malformed CSV: unterminated quoted field.", 400);
        }
        return current.toString();
    }

    private int read() throws IOException {
        int c;
        if (pending >= 0) {
            c = pending;
            pending = -1;
        } else {
            c = source.read();
        }
        if (!bomChecked) {
            bomChecked = true;
            if (c == '\uFEFF') {
                return read();
            }
        }
        return c;
    }

    private void pushBack(int c) {
        if (c >= 0) {
            pending = c;
        }
    }

    private static Map<String, Integer> readContactHeader(List<String> header) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            columns.put(header.get(i).trim(), i);
        }
        if (!columns.containsKey("emailAddress")) {
            throw new AwsException("BadRequestException",
                    "The CSV header line must contain an emailAddress column.", 400);
        }
        return columns;
    }

    private static ImportRecord contactRecordFromCsv(int line, List<String> fields, Map<String, Integer> columns) {
        String email = column(fields, columns, "emailAddress");
        Boolean unsubscribeAll = null;
        String unsubscribeText = column(fields, columns, "unsubscribeAll");
        if (unsubscribeText != null && !unsubscribeText.isEmpty()) {
            if (unsubscribeText.equalsIgnoreCase("true")) {
                unsubscribeAll = true;
            } else if (unsubscribeText.equalsIgnoreCase("false")) {
                unsubscribeAll = false;
            } else {
                return ImportRecord.invalid(line, "unsubscribeAll must be true or false.");
            }
        }
        String attributesData = column(fields, columns, "attributesData");
        if (attributesData != null && attributesData.isEmpty()) {
            attributesData = null;
        }
        List<TopicPreference> prefs = null;
        for (Map.Entry<String, Integer> entry : columns.entrySet()) {
            if (!entry.getKey().startsWith(TOPIC_PREFERENCE_COLUMN_PREFIX)) {
                continue;
            }
            String status = fields.get(entry.getValue()).trim();
            if (status.isEmpty()) {
                continue;
            }
            if (prefs == null) {
                prefs = new ArrayList<>();
            }
            prefs.add(new TopicPreference(entry.getKey().substring(TOPIC_PREFERENCE_COLUMN_PREFIX.length()),
                    status));
        }
        return new ImportRecord(line, email, null, unsubscribeAll, attributesData, prefs, null);
    }

    private static String column(List<String> fields, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        return index == null ? null : fields.get(index);
    }

    // RFC 4180 field splitting: a quoted field may contain commas and line breaks, and a doubled
    // quote inside a quoted field is a literal quote. A quote that does not open a field is data,
    // not an error: the developer guide's own contact row writes attributesData unquoted as
    // {"Name": "John"}, and core/common/CsvParser keeps such a quote literal as well. Anything but
    // a delimiter after a closing quote is still malformed, since that row cannot be read back.
    static List<String> splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean afterClosingQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                        afterClosingQuote = true;
                    }
                } else {
                    current.append(c);
                }
            } else if (afterClosingQuote) {
                if (c != ',') {
                    throw new InvalidRecordException("Malformed CSV: unexpected character after a closing quote.");
                }
                fields.add(current.toString());
                current.setLength(0);
                afterClosingQuote = false;
            } else if (c == '"' && current.isEmpty()) {
                quoted = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (quoted) {
            throw new InvalidRecordException("Malformed CSV: unterminated quoted field.");
        }
        fields.add(current.toString());
        return fields;
    }
}
