package io.github.hectorvent.floci.core.common;

import java.util.ArrayList;
import java.util.List;

public class CsvParser {

    private CsvParser() {}

    /**
     * Parses one comma separated line. A field that opens with a quote may hold commas, and a
     * doubled quote inside it stands for a single quote. Backslashes stay literal, which is what
     * S3 Select and Athena expect of their input.
     */
    public static List<String> parseLine(String line) {
        return scan(line, ',', false).get(0).fields();
    }

    public static List<List<String>> parseAll(String content) {
        return parseAll(content, ',');
    }

    /**
     * Parses a whole text delimited document the way Step Functions reads an ItemReader dataset.
     * On top of the quoting above, a quoted field may hold line breaks, a doubled quote in an
     * unquoted field stands for a single quote, and a backslash escapes another backslash, a
     * quote or the delimiter. A backslash before anything else is dropped, as AWS documents. A
     * line carrying no fields at all is skipped, as a trailing newline is normal.
     */
    public static List<List<String>> parseAll(String content, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        for (Record record : scan(content, delimiter, true)) {
            if (!record.blank()) {
                rows.add(record.fields());
            }
        }
        return rows;
    }

    private record Record(List<String> fields, boolean quoted) {
        boolean blank() {
            return !quoted && fields.size() == 1 && fields.get(0).isBlank();
        }
    }

    /**
     * The single quote tracking loop behind both entry points, so the escaping rules live in one
     * place. Only a quote opening a field starts a quoted run: a quote further in is literal, which
     * is what lets an unquoted field carry a doubled quote. Always returns at least one record.
     */
    private static List<Record> scan(String content, char delimiter, boolean backslashEscapes) {
        List<Record> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean quoted = false;
        boolean fieldStart = true;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            char next = i + 1 < content.length() ? content.charAt(i + 1) : '\0';

            if (backslashEscapes && c == '\\' && i + 1 < content.length()) {
                if (next == '\\' || next == '"' || next == delimiter) {
                    field.append(next);
                    i++;
                }
                fieldStart = false;
                continue;
            }

            if (c == '"') {
                if (inQuotes) {
                    if (next == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else if (fieldStart) {
                    inQuotes = true;
                    quoted = true;
                } else {
                    field.append('"');
                    if (next == '"') {
                        i++;
                    }
                }
                fieldStart = false;
                continue;
            }

            if (inQuotes) {
                field.append(c);
                continue;
            }

            if (c == delimiter) {
                fields.add(field.toString());
                field.setLength(0);
                fieldStart = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && next == '\n') {
                    i++;
                }
                fields.add(field.toString());
                field.setLength(0);
                records.add(new Record(fields, quoted));
                fields = new ArrayList<>();
                quoted = false;
                fieldStart = true;
            } else {
                field.append(c);
                fieldStart = false;
            }
        }

        fields.add(field.toString());
        records.add(new Record(fields, quoted));
        return records;
    }
}
