package io.github.hectorvent.floci.services.redshift.proxy;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises a Simple Query statement that is an S3 {@code COPY <table> FROM 's3://...'}
 * or {@code UNLOAD ('<select>') TO 's3://...'}. Any other statement, or a statement that uses
 * an option this simulator cannot honour, returns {@code null} so the bridge falls back to DDL
 * rewriting and PostgreSQL reports its own error.
 */
public final class CopyStatementParser {

    public sealed interface S3Statement permits S3CopyFrom, S3Unload {
    }

    public record S3CopyFrom(
            String targetTable,
            List<String> columns,
            String bucket,
            String keyOrPrefix,
            String delimiter,
            int headerLines,
            boolean gzip,
            boolean csv,
            String nullAs) implements S3Statement {
    }

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
            boolean manifest,
            boolean allowOverwrite,
            boolean parallel,
            long maxFileSizeBytes) implements S3Statement {
    }

    private static final Pattern COPY_PATTERN = Pattern.compile(
            "(?is)^\\s*COPY\\s+((?:\"[^\"]*\"|[^\\s(])+)\\s*(?:\\(([^)]*)\\))?\\s+FROM\\s*"
                    + "['\"]s3://([^/'\"\\s]+)(?:/([^'\"\\s]*))?['\"]\\s*(.*)$");

    private static final Pattern UNLOAD_PATTERN = Pattern.compile(
            "(?is)^\\s*UNLOAD\\s*\\(\\s*'(.*)'\\s*\\)\\s*TO\\s*"
                    + "['\"]s3://([^/'\"\\s]+)(?:/([^'\"\\s]*))?['\"]\\s*(.*)$");

    private static final Pattern PARALLEL_PATTERN = Pattern.compile(
            "(?i)\\bPARALLEL\\b(?:\\s+(ON|OFF|TRUE|FALSE))?");
    private static final Pattern MAXFILESIZE_PATTERN = Pattern.compile(
            "(?i)\\bMAXFILESIZE\\s+(?:AS\\s+)?(\\d+(?:\\.\\d+)?)\\s*(MB|GB)?\\b");
    private static final Pattern ADDQUOTES_PATTERN = Pattern.compile("(?i)\\bADDQUOTES\\b");
    private static final Pattern MANIFEST_PATTERN = Pattern.compile("(?i)\\bMANIFEST\\b");
    private static final Pattern ALLOWOVERWRITE_PATTERN = Pattern.compile("(?i)\\bALLOWOVERWRITE\\b");

    /**
     * UNLOAD options this simulator cannot honour. Distinct from the COPY set:
     * MANIFEST / ALLOWOVERWRITE / PARALLEL / MAXFILESIZE are UNLOAD-supported here,
     * while EXTENSION / CLEANPATH / PARTITION are UNLOAD-only and unsupported.
     */
    private static final Pattern UNLOAD_UNSUPPORTED_CLAUSE = Pattern.compile(
            "(?i)\\b(FIXEDWIDTH|PARQUET|AVRO|ORC|JSON|SHAPEFILE|BZIP2|LZOP|ZSTD"
                    + "|ENCRYPTED|ENCODING|REGION|CREDENTIALS|IAM_ROLE|ACCESS_KEY_ID"
                    + "|SECRET_ACCESS_KEY|SESSION_TOKEN|MASTER_SYMMETRIC_KEY|KMS_KEY_ID"
                    + "|EXTENSION|CLEANPATH|PARTITION|MAXFILESIZE\\s+\\d+\\s*(?:TB|PB))\\b");

    private static final Pattern QUALIFIED_NAME = Pattern.compile(
            "(?:[A-Za-z_][A-Za-z0-9_$]*|\"[^\"]+\")(?:\\.(?:[A-Za-z_][A-Za-z0-9_$]*|\"[^\"]+\"))?");
    private static final Pattern SIMPLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*|\"[^\"]+\"");

    private static final Pattern DELIMITER_PATTERN = Pattern.compile(
            "(?i)\\bDELIMITER\\s+(?:AS\\s+)?(?:'((?:[^']|'')*)'|\"([^\"]*)\"|([^\\s;]+))");
    private static final Pattern NULL_AS_PATTERN = Pattern.compile(
            "(?i)\\bNULL\\s+(?:AS\\s+)?(?:'((?:[^']|'')*)'|\"([^\"]*)\")");
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

    public static S3Statement parse(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        String cleaned = stripLeadingComments(sql.trim());
        if (cleaned.isEmpty()) {
            return null;
        }
        Matcher unload = UNLOAD_PATTERN.matcher(cleaned);
        if (unload.matches()) {
            return parseUnload(unload);
        }
        return parseCopy(cleaned);
    }

    private static S3Unload parseUnload(Matcher matcher) {
        String select = unescapeSingleQuotes(matcher.group(1).trim());
        if (!isSafeUnloadSubquery(select)) {
            return null;
        }
        String bucket = matcher.group(2);
        String prefix = matcher.group(3) != null ? matcher.group(3) : "";
        String options = matcher.group(4) != null ? matcher.group(4) : "";

        String flagScan = blankQuoted(options);
        if (UNLOAD_UNSUPPORTED_CLAUSE.matcher(flagScan).find()
                || TRAILING_STATEMENT.matcher(flagScan).find()) {
            return null;
        }
        int semi = flagScan.indexOf(';');
        if (semi >= 0) {
            if (!flagScan.substring(semi + 1).isBlank()) {
                return null;
            }
            options = options.substring(0, semi);
        }

        boolean csv = false;
        boolean gzip = false;
        boolean header = false;
        boolean addQuotes = false;
        boolean manifest = false;
        boolean allowOverwrite = false;
        boolean parallel = true;
        String nullAs = null;
        String delimiter = null;
        long maxFileSizeBytes = 0L;

        boolean seenCsv = false;
        boolean seenGzip = false;
        boolean seenHeader = false;
        boolean seenAddQuotes = false;
        boolean seenManifest = false;
        boolean seenAllowOverwrite = false;
        boolean seenParallel = false;
        boolean seenNull = false;
        boolean seenDelimiter = false;
        boolean seenMaxFileSize = false;

        Matcher csvM = CSV_PATTERN.matcher(options);
        Matcher gzipM = GZIP_PATTERN.matcher(options);
        Matcher headerM = HEADER_PATTERN.matcher(options);
        Matcher addQuotesM = ADDQUOTES_PATTERN.matcher(options);
        Matcher manifestM = MANIFEST_PATTERN.matcher(options);
        Matcher allowOverwriteM = ALLOWOVERWRITE_PATTERN.matcher(options);
        Matcher parallelM = PARALLEL_PATTERN.matcher(options);
        Matcher delimiterM = DELIMITER_PATTERN.matcher(options);
        Matcher nullM = NULL_AS_PATTERN.matcher(options);
        Matcher maxFileSizeM = MAXFILESIZE_PATTERN.matcher(options);

        int offset = 0;
        int len = options.length();
        while (offset < len) {
            while (offset < len && Character.isWhitespace(options.charAt(offset))) {
                offset++;
            }
            if (offset >= len) {
                break;
            }
            if (matchClause(maxFileSizeM, offset, len)) {
                if (seenMaxFileSize) {
                    return null;
                }
                seenMaxFileSize = true;
                maxFileSizeBytes = maxFileSizeToBytes(maxFileSizeM.group(1), maxFileSizeM.group(2));
                if (maxFileSizeBytes > S3CopySimulator.UNLOAD_MAX_TOTAL_BYTES) {
                    // A per-file size the simulator cannot buffer: fail open so PostgreSQL,
                    // rather than an unrelated late "result too large" error, reports it.
                    return null;
                }
                offset = maxFileSizeM.end();
            } else if (matchClause(csvM, offset, len)) {
                if (seenCsv) {
                    return null;
                }
                seenCsv = true;
                csv = true;
                offset = csvM.end();
            } else if (matchClause(gzipM, offset, len)) {
                if (seenGzip) {
                    return null;
                }
                seenGzip = true;
                gzip = true;
                offset = gzipM.end();
            } else if (matchClause(headerM, offset, len)) {
                if (seenHeader) {
                    return null;
                }
                seenHeader = true;
                header = true;
                offset = headerM.end();
            } else if (matchClause(addQuotesM, offset, len)) {
                if (seenAddQuotes) {
                    return null;
                }
                seenAddQuotes = true;
                addQuotes = true;
                offset = addQuotesM.end();
            } else if (matchClause(manifestM, offset, len)) {
                if (seenManifest) {
                    return null;
                }
                seenManifest = true;
                manifest = true;
                offset = manifestM.end();
            } else if (matchClause(allowOverwriteM, offset, len)) {
                if (seenAllowOverwrite) {
                    return null;
                }
                seenAllowOverwrite = true;
                allowOverwrite = true;
                offset = allowOverwriteM.end();
            } else if (matchClause(parallelM, offset, len)) {
                if (seenParallel) {
                    return null;
                }
                seenParallel = true;
                String v = parallelM.group(1);
                parallel = v == null || v.equalsIgnoreCase("ON") || v.equalsIgnoreCase("TRUE");
                offset = parallelM.end();
            } else if (matchClause(delimiterM, offset, len)) {
                if (seenDelimiter) {
                    return null;
                }
                seenDelimiter = true;
                delimiter = extractDelimiterValue(delimiterM);
                offset = delimiterM.end();
            } else if (matchClause(nullM, offset, len)) {
                if (seenNull) {
                    return null;
                }
                seenNull = true;
                nullAs = extractNullValue(nullM);
                offset = nullM.end();
            } else {
                return null;
            }
        }

        if (delimiter == null) {
            delimiter = csv ? "," : "|";
        }
        return new S3Unload(select, bucket, prefix, delimiter, header, gzip, csv,
                addQuotes, nullAs, manifest, allowOverwrite, parallel, maxFileSizeBytes);
    }

    private static S3CopyFrom parseCopy(String cleaned) {
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

        int semiIdx = flagScan.indexOf(';');
        if (semiIdx >= 0) {
            if (!flagScan.substring(semiIdx + 1).isBlank()) {
                return null;
            }
            options = options.substring(0, semiIdx);
        }

        boolean csv = false;
        boolean gzip = false;
        String nullAs = null;
        String delimiter = null;
        int headerLines = 0;

        boolean seenCsv = false;
        boolean seenGzip = false;
        boolean seenNull = false;
        boolean seenDelimiter = false;
        boolean seenHeader = false;

        Matcher csvMatcher = CSV_PATTERN.matcher(options);
        Matcher gzipMatcher = GZIP_PATTERN.matcher(options);
        Matcher ignoreHeaderMatcher = IGNOREHEADER_PATTERN.matcher(options);
        Matcher headerMatcher = HEADER_PATTERN.matcher(options);
        Matcher delimiterMatcher = DELIMITER_PATTERN.matcher(options);
        Matcher nullMatcher = NULL_AS_PATTERN.matcher(options);

        int offset = 0;
        int len = options.length();
        while (offset < len) {
            while (offset < len && Character.isWhitespace(options.charAt(offset))) {
                offset++;
            }
            if (offset >= len) {
                break;
            }

            if (matchClause(csvMatcher, offset, len)) {
                if (seenCsv) {
                    return null;
                }
                seenCsv = true;
                csv = true;
                offset = csvMatcher.end();
            } else if (matchClause(gzipMatcher, offset, len)) {
                if (seenGzip) {
                    return null;
                }
                seenGzip = true;
                gzip = true;
                offset = gzipMatcher.end();
            } else if (matchClause(ignoreHeaderMatcher, offset, len)) {
                if (seenHeader) {
                    return null;
                }
                seenHeader = true;
                headerLines = Math.max(0, Integer.parseInt(ignoreHeaderMatcher.group(1)));
                offset = ignoreHeaderMatcher.end();
            } else if (matchClause(headerMatcher, offset, len)) {
                if (seenHeader) {
                    return null;
                }
                seenHeader = true;
                headerLines = 1;
                offset = headerMatcher.end();
            } else if (matchClause(delimiterMatcher, offset, len)) {
                if (seenDelimiter) {
                    return null;
                }
                seenDelimiter = true;
                delimiter = extractDelimiterValue(delimiterMatcher);
                offset = delimiterMatcher.end();
            } else if (matchClause(nullMatcher, offset, len)) {
                if (seenNull) {
                    return null;
                }
                seenNull = true;
                nullAs = extractNullValue(nullMatcher);
                offset = nullMatcher.end();
            } else {
                return null;
            }
        }

        if (delimiter == null) {
            delimiter = csv ? "," : "|";
        }

        return new S3CopyFrom(table, columns, bucket, keyOrPrefix, delimiter, headerLines, gzip, csv, nullAs);
    }

    private static boolean matchClause(Matcher m, int start, int end) {
        m.region(start, end);
        return m.lookingAt();
    }

    private static String extractDelimiterValue(Matcher matcher) {
        String value = matcher.group(1) != null ? matcher.group(1)
                : matcher.group(2) != null ? matcher.group(2)
                : matcher.group(3);
        if (value != null) {
            value = unescape(value);
        }
        return "\\t".equals(value) || "\t".equals(value) ? "\t" : value;
    }

    private static String extractNullValue(Matcher matcher) {
        String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        return unescape(value);
    }

    private static String unescape(String s) {
        if (s == null) {
            return null;
        }
        return s.replace("''", "'").replace("\\\\", "\\");
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
                    if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                        out.append("  ");
                        i++;
                        continue;
                    }
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

    private static long maxFileSizeToBytes(String number, String unit) {
        double value = Double.parseDouble(number);
        long scale = 1L;
        if (unit != null && unit.equalsIgnoreCase("MB")) {
            scale = 1024L * 1024;
        } else if (unit != null && unit.equalsIgnoreCase("GB")) {
            scale = 1024L * 1024 * 1024;
        }
        long bytes = (long) (value * scale);
        return bytes > 0 ? bytes : 0L;
    }

    private static String unescapeSingleQuotes(String s) {
        return s.replace("''", "'");
    }

    /**
     * True only if the UNLOAD subquery cannot escape the {@code COPY (<q>) TO STDOUT}
     * wrapper it is spliced into: it must start with {@code SELECT} or {@code WITH},
     * keep parentheses balanced (never dipping below zero), and contain no {@code ;},
     * {@code $}, {@code --}, or {@code /*} outside a single- or double-quoted run.
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
                    inDouble = false;
                }
                continue;
            }
            switch (c) {
                case '\'' -> {
                    if (i > 0) {
                        char prev = q.charAt(i - 1);
                        if (prev == 'E' || prev == 'e') {
                            return false; // C-style escape string literal
                        }
                        if (prev == '&' && i > 1) {
                            char prev2 = q.charAt(i - 2);
                            if (prev2 == 'U' || prev2 == 'u') {
                                return false; // Unicode escape string literal
                            }
                        }
                    }
                    inSingle = true;
                }
                case '"' -> {
                    if (i > 1 && q.charAt(i - 1) == '&') {
                        char prev2 = q.charAt(i - 2);
                        if (prev2 == 'U' || prev2 == 'u') {
                            return false; // Unicode escape identifier
                        }
                    }
                    inDouble = true;
                }
                case '\\' -> {
                    return false; // backslash outside quotes
                }
                case '(' -> depth++;
                case ')' -> {
                    if (--depth < 0) {
                        return false;
                    }
                }
                case ';', '$' -> {
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
                default -> {
                }
            }
        }
        return depth == 0 && !inSingle && !inDouble;
    }

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
