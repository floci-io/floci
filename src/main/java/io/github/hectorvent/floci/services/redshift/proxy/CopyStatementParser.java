package io.github.hectorvent.floci.services.redshift.proxy;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CopyStatementParser {

    public sealed interface S3Statement permits S3CopyFrom, S3Unload {}

    public record S3CopyFrom(
            String targetTable,
            List<String> columns,
            String bucket,
            String keyOrPrefix,
            String delimiter,
            boolean header,
            boolean gzip,
            String nullAs
    ) implements S3Statement {}

    public record S3Unload(
            String selectQuery,
            String bucket,
            String prefix,
            String delimiter,
            boolean header,
            boolean gzip,
            boolean csv,
            boolean addQuotes,
            String nullAs,
            boolean manifest
    ) implements S3Statement {}

    private static final Pattern UNLOAD_PATTERN = Pattern.compile(
            "(?si)^\\s*UNLOAD\\s*\\(\\s*'(.*?)'\\s*\\)\\s*TO\\s*['\"]s3://([^/'\"\\s]+)(?:/([^'\"\\s]*))?['\"]\\s*(.*)$"
    );

    private static final Pattern COPY_PATTERN = Pattern.compile(
            "(?si)^\\s*COPY\\s+([^\\s(]+)(?:\\s*\\(([^)]+)\\))?\\s+FROM\\s*['\"]s3://([^/'\"\\s]+)(?:/([^'\"\\s]*))?['\"]\\s*(.*)$"
    );

    private static final Pattern DELIMITER_PATTERN = Pattern.compile(
            "(?i)\\bDELIMITER\\s+(?:AS\\s+)?(?:'([^']*)'|\"([^\"]*)\"|([^\\s;,]+))"
    );

    private static final Pattern NULL_AS_PATTERN = Pattern.compile(
            "(?i)\\bNULL\\s+(?:AS\\s+)?(?:'([^']*)'|\"([^\"]*)\")"
    );

    private static final Pattern IGNOREHEADER_PATTERN = Pattern.compile(
            "(?i)\\bIGNOREHEADER\\s+(?:AS\\s+)?(\\d+)\\b"
    );

    private static final Pattern HEADER_PATTERN = Pattern.compile("(?i)\\bHEADER\\b");
    private static final Pattern GZIP_PATTERN = Pattern.compile("(?i)\\bGZIP\\b");
    private static final Pattern CSV_PATTERN = Pattern.compile("(?i)\\b(?:FORMAT\\s+(?:AS\\s+)?)?CSV\\b");
    private static final Pattern ADDQUOTES_PATTERN = Pattern.compile("(?i)\\bADDQUOTES\\b");
    private static final Pattern MANIFEST_PATTERN = Pattern.compile("(?i)\\bMANIFEST\\b");

    public static S3Statement parse(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }

        String cleaned = stripLeadingComments(sql.trim());
        if (cleaned.isEmpty()) {
            return null;
        }

        Matcher unloadMatcher = UNLOAD_PATTERN.matcher(cleaned);
        if (unloadMatcher.matches()) {
            return parseUnload(unloadMatcher);
        }

        Matcher copyMatcher = COPY_PATTERN.matcher(cleaned);
        if (copyMatcher.matches()) {
            return parseCopy(copyMatcher);
        }

        return null;
    }

    private static S3Unload parseUnload(Matcher matcher) {
        String selectQuery = matcher.group(1).trim();
        String bucket = matcher.group(2);
        String prefix = matcher.group(3) != null ? matcher.group(3) : "";
        String options = matcher.group(4);

        boolean csv = CSV_PATTERN.matcher(options).find();
        boolean header = HEADER_PATTERN.matcher(options).find();
        boolean gzip = GZIP_PATTERN.matcher(options).find();
        boolean addQuotes = ADDQUOTES_PATTERN.matcher(options).find();
        boolean manifest = MANIFEST_PATTERN.matcher(options).find();
        String nullAs = extractNullAs(options);
        String delimiter = extractDelimiter(options, csv);

        return new S3Unload(
                selectQuery,
                bucket,
                prefix,
                delimiter,
                header,
                gzip,
                csv,
                addQuotes,
                nullAs,
                manifest
        );
    }

    private static S3CopyFrom parseCopy(Matcher matcher) {
        String targetTable = matcher.group(1).trim();
        String columnsStr = matcher.group(2);
        String bucket = matcher.group(3);
        String keyOrPrefix = matcher.group(4) != null ? matcher.group(4) : "";
        String options = matcher.group(5);

        List<String> columns = List.of();
        if (columnsStr != null && !columnsStr.isBlank()) {
            columns = Arrays.stream(columnsStr.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }

        boolean csv = CSV_PATTERN.matcher(options).find();
        boolean gzip = GZIP_PATTERN.matcher(options).find();
        boolean header = extractCopyHeader(options);
        String nullAs = extractNullAs(options);
        String delimiter = extractDelimiter(options, csv);

        return new S3CopyFrom(
                targetTable,
                columns,
                bucket,
                keyOrPrefix,
                delimiter,
                header,
                gzip,
                nullAs
        );
    }

    private static boolean extractCopyHeader(String options) {
        Matcher ignoreHeaderMatcher = IGNOREHEADER_PATTERN.matcher(options);
        if (ignoreHeaderMatcher.find()) {
            int count = Integer.parseInt(ignoreHeaderMatcher.group(1));
            return count >= 1;
        }
        return HEADER_PATTERN.matcher(options).find();
    }

    private static String extractDelimiter(String options, boolean csv) {
        Matcher delimMatcher = DELIMITER_PATTERN.matcher(options);
        if (delimMatcher.find()) {
            String val = delimMatcher.group(1) != null
                    ? delimMatcher.group(1)
                    : (delimMatcher.group(2) != null ? delimMatcher.group(2) : delimMatcher.group(3));
            if ("\\t".equals(val)) {
                return "\t";
            }
            return val;
        }
        return csv ? "," : "|";
    }

    private static String extractNullAs(String options) {
        Matcher nullMatcher = NULL_AS_PATTERN.matcher(options);
        if (nullMatcher.find()) {
            return nullMatcher.group(1) != null ? nullMatcher.group(1) : nullMatcher.group(2);
        }
        return null;
    }

    private static String stripLeadingComments(String sql) {
        String current = sql;
        while (current.startsWith("--") || current.startsWith("/*")) {
            if (current.startsWith("--")) {
                int newline = current.indexOf('\n');
                if (newline == -1) {
                    return "";
                }
                current = current.substring(newline + 1).trim();
            } else if (current.startsWith("/*")) {
                int close = current.indexOf("*/");
                if (close == -1) {
                    return "";
                }
                current = current.substring(close + 2).trim();
            }
        }
        return current;
    }
}
