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

    /** A schema-qualified SQL identifier: unquoted {@code foo} / {@code foo.bar}, or {@code "quoted"} parts. */
    private static final Pattern QUALIFIED_NAME = Pattern.compile(
            "(?:[A-Za-z_][A-Za-z0-9_$]*|\"[^\"]+\")(?:\\.(?:[A-Za-z_][A-Za-z0-9_$]*|\"[^\"]+\"))?");
    private static final Pattern SIMPLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*|\"[^\"]+\"");

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

    private static S3Statement parseUnload(Matcher matcher) {
        String selectQuery = matcher.group(1).trim();
        // The select is spliced verbatim into `COPY (<select>) TO STDOUT`. Only intercept when it
        // provably cannot break out of that wrapper; otherwise fall through to PostgreSQL (errors).
        if (!isSafeUnloadSubquery(selectQuery)) {
            return null;
        }
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

    private static S3Statement parseCopy(Matcher matcher) {
        String targetTable = matcher.group(1).trim();
        String columnsStr = matcher.group(2);
        String bucket = matcher.group(3);
        String keyOrPrefix = matcher.group(4) != null ? matcher.group(4) : "";
        String options = matcher.group(5);

        // targetTable and column names are spliced into the fabricated `COPY <t> (<cols>) FROM
        // STDIN`; only accept plain / quoted identifiers so nothing else can be injected there.
        if (!QUALIFIED_NAME.matcher(targetTable).matches()) {
            return null;
        }

        List<String> columns = List.of();
        if (columnsStr != null && !columnsStr.isBlank()) {
            columns = Arrays.stream(columnsStr.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (columns.stream().anyMatch(c -> !SIMPLE_NAME.matcher(c).matches())) {
                return null;
            }
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

    /**
     * True only if the UNLOAD subquery cannot escape the {@code COPY (<q>) TO STDOUT} wrapper it is
     * spliced into: it must start with {@code SELECT}/{@code WITH}, keep parentheses balanced (and
     * never dip below zero), and contain no statement separator, SQL comment, dollar-quote or
     * positional-parameter marker outside a string literal. A crafted fragment like
     * {@code SELECT 1) TO PROGRAM $$…$$ --} fails the paren-balance and dollar checks.
     */
    private static boolean isSafeUnloadSubquery(String q) {
        if (!(startsWithKeyword(q, "SELECT") || startsWithKeyword(q, "WITH"))) {
            return false;
        }
        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < q.length(); i++) {
            char c = q.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    if (i + 1 < q.length() && q.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        inSingle = false;
                    }
                }
                continue;
            }
            if (inDouble) {
                if (c == '"') {
                    if (i + 1 < q.length() && q.charAt(i + 1) == '"') {
                        i++;
                    } else {
                        inDouble = false;
                    }
                }
                continue;
            }
            switch (c) {
                case '\'' -> inSingle = true;
                case '"' -> inDouble = true;
                case '(' -> depth++;
                case ')' -> {
                    if (--depth < 0) {
                        return false;
                    }
                }
                case ';', '\0', '$' -> {
                    return false;
                }
                case '-' -> {
                    if (i + 1 < q.length() && q.charAt(i + 1) == '-') {
                        return false;
                    }
                }
                case '/' -> {
                    if (i + 1 < q.length() && q.charAt(i + 1) == '*') {
                        return false;
                    }
                }
                default -> { }
            }
        }
        return depth == 0 && !inSingle && !inDouble;
    }

    /** True if {@code s}, after leading whitespace, begins with {@code keyword} followed by a non-identifier char. */
    private static boolean startsWithKeyword(String s, String keyword) {
        String t = s.stripLeading();
        if (!t.regionMatches(true, 0, keyword, 0, keyword.length())) {
            return false;
        }
        if (t.length() == keyword.length()) {
            return true;
        }
        char next = t.charAt(keyword.length());
        return !(Character.isLetterOrDigit(next) || next == '_' || next == '$');
    }
}
