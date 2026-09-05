package io.github.hectorvent.floci.services.redshift.proxy;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises a Simple Query statement that is an S3 {@code COPY <table> FROM 's3://...'}.
 * Any other statement, or a COPY that uses an option this simulator cannot honour, returns
 * {@code null} so the bridge falls back to DDL rewriting and PostgreSQL reports its own error.
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

    /**
     * Options this simulator does not implement. A COPY carrying any of these is not intercepted:
     * the original statement is forwarded so PostgreSQL rejects it, rather than the simulator
     * silently loading the data with the wrong framing.
     */
    private static final Pattern UNSUPPORTED_CLAUSE = Pattern.compile(
            "(?i)\\b(FIXEDWIDTH|PARQUET|AVRO|ORC|JSON|SHAPEFILE|BZIP2|LZOP|ZSTD|MANIFEST|MAXERROR"
                    + "|DATEFORMAT|TIMEFORMAT|ENCRYPTED|ENCODING|REGION|CREDENTIALS|IAM_ROLE"
                    + "|ACCESS_KEY_ID|SECRET_ACCESS_KEY|SESSION_TOKEN|MASTER_SYMMETRIC_KEY|KMS_KEY_ID"
                    + "|ACCEPTINVCHARS|ACCEPTANYDATE|BLANKSASNULL|EMPTYASNULL|FILLRECORD|TRIMBLANKS"
                    + "|TRUNCATECOLUMNS|IGNOREBLANKLINES|ESCAPE|REMOVEQUOTES|EXPLICIT_IDS|COMPUPDATE"
                    + "|STATUPDATE|NOLOAD|ROUNDEC|QUOTE|SSH|READRATIO|COMPROWS|DIMENSION)\\b");

    /** A {@code ;} followed by another statement: only a lone trailing {@code ;} is tolerated. */
    private static final Pattern TRAILING_STATEMENT = Pattern.compile(";\\s*\\S");

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

        // Scan for keywords and separators on a copy with string literals blanked out, so a
        // value like NULL AS 'json' or DELIMITER ';' cannot trip a keyword or the trailing check.
        String flagScan = blankQuoted(options);
        if (UNSUPPORTED_CLAUSE.matcher(flagScan).find() || TRAILING_STATEMENT.matcher(flagScan).find()) {
            return null;
        }

        boolean csv = CSV_PATTERN.matcher(flagScan).find();
        boolean gzip = GZIP_PATTERN.matcher(flagScan).find();
        String nullAs = firstGroup(NULL_AS_PATTERN.matcher(options));
        String delimiter = extractDelimiter(options, csv);
        int headerLines = extractHeaderLines(flagScan);

        return new S3CopyFrom(table, columns, bucket, keyOrPrefix, delimiter, headerLines, gzip, csv, nullAs);
    }

    private static int extractHeaderLines(String flagScan) {
        Matcher ignoreHeader = IGNOREHEADER_PATTERN.matcher(flagScan);
        if (ignoreHeader.find()) {
            return Math.max(0, Integer.parseInt(ignoreHeader.group(1)));
        }
        return HEADER_PATTERN.matcher(flagScan).find() ? 1 : 0;
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

    /** Replace every character inside a single- or double-quoted run with a space. */
    private static String blankQuoted(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                }
                out.append(' ');
            } else if (inDouble) {
                if (c == '"') {
                    inDouble = false;
                }
                out.append(' ');
            } else if (c == '\'') {
                inSingle = true;
                out.append(' ');
            } else if (c == '"') {
                inDouble = true;
                out.append(' ');
            } else {
                out.append(c);
            }
        }
        return out.toString();
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
