package io.github.hectorvent.floci.services.redshift.proxy;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises a Simple Query statement that is an S3 {@code COPY <table> FROM 's3://...'}.
 * Any other statement returns {@code null}, so the bridge falls back to DDL rewriting.
 */
public final class CopyStatementParser {

    public record S3CopyFrom(
            String targetTable,
            List<String> columns,
            String bucket,
            String keyOrPrefix,
            String delimiter,
            int headerLines,
            boolean gzip,
            boolean csv,
            String nullAs) {
    }

    private static final Pattern COPY_PATTERN = Pattern.compile(
            "(?is)^\\s*COPY\\s+((?:\"[^\"]*\"|[^\\s(])+)\\s*(?:\\(([^)]*)\\))?\\s+FROM\\s*"
                    + "['\"]s3://([^/'\"\\s]+)(?:/([^'\"\\s]*))?['\"]\\s*(.*)$");

    private static final Pattern QUALIFIED_NAME = Pattern.compile(
            "(?:[A-Za-z_][A-Za-z0-9_$]*|\"[^\"]+\")(?:\\.(?:[A-Za-z_][A-Za-z0-9_$]*|\"[^\"]+\"))?");
    private static final Pattern SIMPLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*|\"[^\"]+\"");

    private static final Pattern DELIMITER_PATTERN = Pattern.compile(
            "(?i)\\bDELIMITER\\s+(?:AS\\s+)?(?:'([^']*)'|\"([^\"]*)\"|([^\\s;]+))");
    private static final Pattern NULL_AS_PATTERN = Pattern.compile(
            "(?i)\\bNULL\\s+(?:AS\\s+)?(?:'([^']*)'|\"([^\"]*)\")");
    private static final Pattern IGNOREHEADER_PATTERN = Pattern.compile(
            "(?i)\\bIGNOREHEADER\\s+(?:AS\\s+)?(\\d+)\\b");
    private static final Pattern HEADER_PATTERN = Pattern.compile("(?i)\\bHEADER\\b");
    private static final Pattern GZIP_PATTERN = Pattern.compile("(?i)\\bGZIP\\b");
    private static final Pattern CSV_PATTERN = Pattern.compile("(?i)\\b(?:FORMAT\\s+(?:AS\\s+)?)?CSV\\b");

    private CopyStatementParser() {
    }

    public static S3CopyFrom parse(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        String cleaned = stripLeadingComments(sql.trim());
        Matcher matcher = COPY_PATTERN.matcher(cleaned);
        if (!matcher.matches()) {
            return null;
        }

        String table = matcher.group(1).trim();
        if (!QUALIFIED_NAME.matcher(table).matches()) {
            return null;
        }

        List<String> columns = List.of();
        String columnsGroup = matcher.group(2);
        if (columnsGroup != null && !columnsGroup.isBlank()) {
            columns = Arrays.stream(columnsGroup.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (columns.stream().anyMatch(c -> !SIMPLE_NAME.matcher(c).matches())) {
                return null;
            }
        }

        String bucket = matcher.group(3);
        String keyOrPrefix = matcher.group(4) != null ? matcher.group(4) : "";
        String options = matcher.group(5) != null ? matcher.group(5) : "";

        boolean csv = CSV_PATTERN.matcher(options).find();
        boolean gzip = GZIP_PATTERN.matcher(options).find();
        String nullAs = firstGroup(NULL_AS_PATTERN.matcher(options));
        String delimiter = extractDelimiter(options, csv);
        int headerLines = extractHeaderLines(options);

        return new S3CopyFrom(table, columns, bucket, keyOrPrefix, delimiter, headerLines, gzip, csv, nullAs);
    }

    private static int extractHeaderLines(String options) {
        Matcher ignoreHeader = IGNOREHEADER_PATTERN.matcher(options);
        if (ignoreHeader.find()) {
            return Math.max(0, Integer.parseInt(ignoreHeader.group(1)));
        }
        return HEADER_PATTERN.matcher(options).find() ? 1 : 0;
    }

    private static String extractDelimiter(String options, boolean csv) {
        Matcher delimiter = DELIMITER_PATTERN.matcher(options);
        if (delimiter.find()) {
            String value = delimiter.group(1) != null ? delimiter.group(1)
                    : delimiter.group(2) != null ? delimiter.group(2)
                    : delimiter.group(3);
            if (value != null) {
                value = unescape(value);
            }
            return "\\t".equals(value) || "\t".equals(value) ? "\t" : value;
        }
        return csv ? "," : "|";
    }

    private static String firstGroup(Matcher matcher) {
        if (matcher.find()) {
            String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            return unescape(value);
        }
        return null;
    }

    private static String unescape(String s) {
        if (s == null) {
            return null;
        }
        return s.replace("\\\\", "\\");
    }

    private static String stripLeadingComments(String sql) {
        String current = sql;
        while (true) {
            if (current.startsWith("--")) {
                int newline = current.indexOf('\n');
                if (newline < 0) {
                    return "";
                }
                current = current.substring(newline + 1).stripLeading();
            } else if (current.startsWith("/*")) {
                int close = current.indexOf("*/");
                if (close < 0) {
                    return "";
                }
                current = current.substring(close + 2).stripLeading();
            } else {
                return current;
            }
        }
    }
}
