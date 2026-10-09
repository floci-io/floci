package io.github.hectorvent.floci.services.redshift.proxy;

import java.nio.charset.StandardCharsets;
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

    /** COPY options that change field content; {@link #NONE} keeps the plain byte-copy path. */
    public record CopyTransforms(
            boolean blanksAsNull,
            boolean emptyAsNull,
            boolean removeQuotes,
            boolean truncateColumns,
            Character invalidCharReplacement) {

        public static final CopyTransforms NONE = new CopyTransforms(false, false, false, false, null);

        public boolean fieldLevel() {
            return blanksAsNull || emptyAsNull || removeQuotes || truncateColumns;
        }

        public boolean any() {
            return fieldLevel() || invalidCharReplacement != null;
        }
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
            String nullAs,
            String iamRoleArn,
            boolean jsonAuto,
            boolean jsonAutoIgnoreCase,
            boolean manifest,
            CopyTransforms transforms) implements S3Statement {

        public S3CopyFrom {
            if (transforms == null) {
                transforms = CopyTransforms.NONE;
            }
        }

        public S3CopyFrom(
                String targetTable,
                List<String> columns,
                String bucket,
                String keyOrPrefix,
                String delimiter,
                int headerLines,
                boolean gzip,
                boolean csv,
                String nullAs,
                String iamRoleArn,
                boolean jsonAuto,
                boolean jsonAutoIgnoreCase,
                boolean manifest) {
            this(targetTable, columns, bucket, keyOrPrefix, delimiter, headerLines, gzip, csv,
                    nullAs, iamRoleArn, jsonAuto, jsonAutoIgnoreCase, manifest, CopyTransforms.NONE);
        }

        public S3CopyFrom(
                String targetTable,
                List<String> columns,
                String bucket,
                String keyOrPrefix,
                String delimiter,
                int headerLines,
                boolean gzip,
                boolean csv,
                String nullAs,
                String iamRoleArn,
                boolean jsonAuto,
                boolean manifest) {
            this(targetTable, columns, bucket, keyOrPrefix, delimiter, headerLines, gzip, csv,
                    nullAs, iamRoleArn, jsonAuto, false, manifest);
        }

        public S3CopyFrom(
                String targetTable,
                List<String> columns,
                String bucket,
                String keyOrPrefix,
                String delimiter,
                int headerLines,
                boolean gzip,
                boolean csv,
                String nullAs,
                String iamRoleArn) {
            this(targetTable, columns, bucket, keyOrPrefix, delimiter, headerLines, gzip, csv,
                    nullAs, iamRoleArn, false, false, false);
        }
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
            long maxFileSizeBytes,
            String iamRoleArn,
            String extension,
            boolean escape,
            boolean cleanPath,
            boolean encrypted,
            String sseKmsKeyId) implements S3Statement {

        public S3Unload(
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
                long maxFileSizeBytes,
                String iamRoleArn,
                String extension) {
            this(selectQuery, bucket, prefix, delimiter, header, gzip, csv, addQuotes, nullAs, manifest,
                    allowOverwrite, parallel, maxFileSizeBytes, iamRoleArn, extension,
                    false, false, false, null);
        }

        public S3Unload(
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
                long maxFileSizeBytes,
                String iamRoleArn) {
            this(selectQuery, bucket, prefix, delimiter, header, gzip, csv, addQuotes, nullAs, manifest,
                    allowOverwrite, parallel, maxFileSizeBytes, iamRoleArn, null);
        }
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
    private static final Pattern ESCAPE_PATTERN = Pattern.compile("(?i)\\bESCAPE\\b");
    private static final Pattern CLEANPATH_PATTERN = Pattern.compile("(?i)\\bCLEANPATH\\b");
    private static final Pattern ENCRYPTED_PATTERN = Pattern.compile("(?i)\\bENCRYPTED(?:\\s+(AUTO)\\b)?");
    private static final Pattern KMS_KEY_ID_PATTERN = Pattern.compile(
            "(?i)\\bKMS_KEY_ID\\s+(?:'((?:[^']|'')*)'|\"([^\"]*)\")");
    private static final Pattern EXTENSION_PATTERN = Pattern.compile(
            "(?i)\\bEXTENSION\\s+(?:'((?:[^']|'')*)'|\"([^\"]*)\")");

    /**
     * UNLOAD options this simulator cannot honour. Distinct from the COPY set:
     * MANIFEST / ALLOWOVERWRITE / PARALLEL / MAXFILESIZE / EXTENSION / CLEANPATH are
     * UNLOAD-supported here, while PARTITION is UNLOAD-only and unsupported.
     */
    private static final Pattern UNLOAD_UNSUPPORTED_CLAUSE = Pattern.compile(
            "(?i)\\b(FIXEDWIDTH|PARQUET|AVRO|ORC|JSON|SHAPEFILE|BZIP2|LZOP|ZSTD"
                    + "|ENCODING|MASTER_SYMMETRIC_KEY"
                    + "|PARTITION|MAXFILESIZE\\s+\\d+\\s*(?:TB|PB))\\b");

    private static final Pattern QUALIFIED_NAME = Pattern.compile(
            "(?:[A-Za-z_][A-Za-z0-9_$]*|\"[^\"]+\")(?:\\.(?:[A-Za-z_][A-Za-z0-9_$]*|\"[^\"]+\"))?");
    private static final Pattern SIMPLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*|\"[^\"]+\"");

    private static final Pattern DELIMITER_PATTERN = Pattern.compile(
            "(?i)\\bDELIMITER\\s+(?:AS\\s+)?(?:'((?:[^']|'')*)'|\"([^\"]*)\"|([^\\s;]+))");
    private static final Pattern NULL_AS_PATTERN = Pattern.compile(
            "(?i)\\bNULL\\s+(?:AS\\s+)?(?:'((?:[^']|'')*)'|\"([^\"]*)\")");
    private static final Pattern IAM_ROLE_PATTERN = Pattern.compile(
            "(?i)\\bIAM_ROLE\\s+(?:'((?:[^']|'')*)'|\"([^\"]*)\")");
    private static final Pattern BLANKSASNULL_PATTERN = Pattern.compile("(?i)\\bBLANKSASNULL\\b");
    private static final Pattern EMPTYASNULL_PATTERN = Pattern.compile("(?i)\\bEMPTYASNULL\\b");
    private static final Pattern REMOVEQUOTES_PATTERN = Pattern.compile("(?i)\\bREMOVEQUOTES\\b");
    private static final Pattern TRUNCATECOLUMNS_PATTERN = Pattern.compile("(?i)\\bTRUNCATECOLUMNS\\b");
    private static final Pattern ACCEPTINVCHARS_PATTERN = Pattern.compile(
            "(?i)\\bACCEPTINVCHARS(?:\\s+(?:AS\\s+)?'([ -&(-~])')?");
    private static final Pattern CREDENTIALS_PATTERN = Pattern.compile(
            "(?i)\\bCREDENTIALS\\s+(?:AS\\s+)?(?:'((?:[^']|'')*)'|\"([^\"]*)\")");
    private static final Pattern ACCESS_KEY_ID_PATTERN = Pattern.compile(
            "(?i)\\bACCESS_KEY_ID\\s+(?:AS\\s+)?(?:'((?:[^']|'')*)'|\"([^\"]*)\")");
    private static final Pattern SECRET_ACCESS_KEY_PATTERN = Pattern.compile(
            "(?i)\\bSECRET_ACCESS_KEY\\s+(?:AS\\s+)?(?:'((?:[^']|'')*)'|\"([^\"]*)\")");
    private static final Pattern SESSION_TOKEN_PATTERN = Pattern.compile(
            "(?i)\\bSESSION_TOKEN\\s+(?:AS\\s+)?(?:'((?:[^']|'')*)'|\"([^\"]*)\")");
    private static final Pattern REGION_PATTERN = Pattern.compile(
            "(?i)\\bREGION\\s+(?:AS\\s+)?(?:'((?:[^']|'')*)'|\"([^\"]*)\")");
    private static final Pattern CREDENTIALS_ROLE = Pattern.compile(
            "(?i)(?:^|;)\\s*aws_iam_role\\s*=\\s*([^;\\s]+)");
    private static final Pattern CREDENTIALS_KEY_ID = Pattern.compile(
            "(?i)(?:^|;)\\s*aws_access_key_id\\s*=\\s*[^;\\s]+");
    private static final Pattern CREDENTIALS_SECRET = Pattern.compile(
            "(?i)(?:^|;)\\s*aws_secret_access_key\\s*=\\s*[^;\\s]+");
    private static final Pattern IGNOREHEADER_PATTERN = Pattern.compile(
            "(?i)\\bIGNOREHEADER\\s+(?:AS\\s+)?(\\d+)\\b");
    private static final Pattern HEADER_PATTERN = Pattern.compile("(?i)\\bHEADER\\b");
    private static final Pattern GZIP_PATTERN = Pattern.compile("(?i)\\bGZIP\\b");
    private static final Pattern CSV_PATTERN = Pattern.compile("(?i)\\b(?:FORMAT\\s+(?:AS\\s+)?)?CSV\\b");
    private static final Pattern JSON_AUTO_PATTERN = Pattern.compile(
            "(?i)\\b(?:FORMAT\\s+(?:AS\\s+)?)?JSON(?:\\s+AS)?\\s+['\"]auto(?:\\s+(ignorecase))?['\"]");

    /**
     * Options this simulator does not implement. A COPY carrying any of these is not intercepted:
     * the original statement is forwarded so PostgreSQL rejects it, rather than the simulator
     * silently loading the data with the wrong framing.
     */
    private static final Pattern UNSUPPORTED_CLAUSE = Pattern.compile(
            "(?i)\\b(FIXEDWIDTH|PARQUET|AVRO|ORC|SHAPEFILE|BZIP2|LZOP|ZSTD|MAXERROR"
                    + "|DATEFORMAT|TIMEFORMAT|ENCRYPTED|ENCODING"
                    + "|MASTER_SYMMETRIC_KEY|KMS_KEY_ID"
                    + "|ACCEPTANYDATE|FILLRECORD|TRIMBLANKS"
                    + "|IGNOREBLANKLINES|ESCAPE|EXPLICIT_IDS|COMPUPDATE"
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
        String extension = null;
        boolean escape = false;
        boolean cleanPath = false;
        boolean encrypted = false;
        boolean encryptedAuto = false;
        String kmsKeyId = null;

        boolean seenEscape = false;
        boolean seenCleanPath = false;
        boolean seenEncrypted = false;
        boolean seenKmsKeyId = false;
        boolean seenExtension = false;
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
        Matcher extensionM = EXTENSION_PATTERN.matcher(options);
        Matcher escapeM = ESCAPE_PATTERN.matcher(options);
        Matcher cleanPathM = CLEANPATH_PATTERN.matcher(options);
        Matcher encryptedM = ENCRYPTED_PATTERN.matcher(options);
        Matcher kmsKeyIdM = KMS_KEY_ID_PATTERN.matcher(options);
        AuthClauses auth = new AuthClauses(options);

        int next;
        int offset = 0;
        int len = options.length();
        while (offset < len) {
            while (offset < len && Character.isWhitespace(options.charAt(offset))) {
                offset++;
            }
            if (offset >= len) {
                break;
            }
            if (matchClause(extensionM, offset, len)) {
                if (seenExtension) {
                    return null;
                }
                seenExtension = true;
                extension = extractNullValue(extensionM);
                if (extension.isEmpty() || extension.indexOf('/') >= 0) {
                    return null;
                }
                offset = extensionM.end();
            } else if (matchClause(escapeM, offset, len)) {
                if (seenEscape) {
                    return null;
                }
                seenEscape = true;
                escape = true;
                offset = escapeM.end();
            } else if (matchClause(cleanPathM, offset, len)) {
                if (seenCleanPath) {
                    return null;
                }
                seenCleanPath = true;
                cleanPath = true;
                offset = cleanPathM.end();
            } else if (matchClause(encryptedM, offset, len)) {
                if (seenEncrypted) {
                    return null;
                }
                seenEncrypted = true;
                encrypted = true;
                encryptedAuto = encryptedM.group(1) != null;
                offset = encryptedM.end();
            } else if (matchClause(kmsKeyIdM, offset, len)) {
                if (seenKmsKeyId) {
                    return null;
                }
                seenKmsKeyId = true;
                kmsKeyId = extractNullValue(kmsKeyIdM);
                offset = kmsKeyIdM.end();
            } else if (matchClause(maxFileSizeM, offset, len)) {
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
            } else if ((next = auth.consume(offset, len)) != AuthClauses.NO_MATCH) {
                if (next == AuthClauses.REJECT) {
                    return null;
                }
                offset = next;
            } else {
                return null;
            }
        }

        if (!auth.consistent()) {
            return null;
        }
        String iamRoleArn = auth.iamRoleArn();
        if (delimiter == null) {
            delimiter = csv ? "," : "|";
        }
        // ESCAPE relies on PostgreSQL text framing, which CSV, ADDQUOTES and HEADER all replace here.
        if (escape && (csv || addQuotes || header)) {
            return null;
        }
        // An empty prefix would make CLEANPATH delete the whole bucket.
        if (cleanPath && (allowOverwrite || prefix.isEmpty())) {
            return null;
        }
        // Only ENCRYPTED AUTO and ENCRYPTED KMS_KEY_ID are emulated. Bare ENCRYPTED and MASTER_SYMMETRIC_KEY are not.
        if (kmsKeyId != null && (kmsKeyId.isBlank() || !encrypted || encryptedAuto)) {
            return null;
        }
        if (encrypted && !encryptedAuto && kmsKeyId == null) {
            return null;
        }
        return new S3Unload(select, bucket, prefix, delimiter, header, gzip, csv,
                addQuotes, nullAs, manifest, allowOverwrite, parallel, maxFileSizeBytes, iamRoleArn, extension,
                escape, cleanPath, encrypted, kmsKeyId);
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
        boolean jsonAuto = false;
        boolean jsonAutoIgnoreCase = false;
        boolean manifest = false;
        String nullAs = null;
        String delimiter = null;
        int headerLines = 0;
        boolean blanksAsNull = false;
        boolean emptyAsNull = false;
        boolean removeQuotes = false;
        boolean truncateColumns = false;
        Character invalidChar = null;

        boolean seenTruncateColumns = false;
        boolean seenBlanksAsNull = false;
        boolean seenEmptyAsNull = false;
        boolean seenRemoveQuotes = false;
        boolean seenAcceptInvChars = false;
        boolean seenCsv = false;
        boolean seenGzip = false;
        boolean seenJson = false;
        boolean seenManifest = false;
        boolean seenNull = false;
        boolean seenDelimiter = false;
        boolean seenHeader = false;

        Matcher csvMatcher = CSV_PATTERN.matcher(options);
        Matcher gzipMatcher = GZIP_PATTERN.matcher(options);
        Matcher jsonAutoMatcher = JSON_AUTO_PATTERN.matcher(options);
        Matcher manifestMatcher = MANIFEST_PATTERN.matcher(options);
        Matcher ignoreHeaderMatcher = IGNOREHEADER_PATTERN.matcher(options);
        Matcher headerMatcher = HEADER_PATTERN.matcher(options);
        Matcher delimiterMatcher = DELIMITER_PATTERN.matcher(options);
        Matcher nullMatcher = NULL_AS_PATTERN.matcher(options);
        Matcher blanksAsNullMatcher = BLANKSASNULL_PATTERN.matcher(options);
        Matcher emptyAsNullMatcher = EMPTYASNULL_PATTERN.matcher(options);
        Matcher removeQuotesMatcher = REMOVEQUOTES_PATTERN.matcher(options);
        Matcher truncateColumnsMatcher = TRUNCATECOLUMNS_PATTERN.matcher(options);
        Matcher acceptInvCharsMatcher = ACCEPTINVCHARS_PATTERN.matcher(options);
        AuthClauses auth = new AuthClauses(options);

        int next;
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
                if (seenCsv || seenJson) {
                    return null;
                }
                seenCsv = true;
                csv = true;
                offset = csvMatcher.end();
            } else if (matchClause(jsonAutoMatcher, offset, len)) {
                if (seenJson || seenCsv) {
                    return null;
                }
                seenJson = true;
                jsonAuto = true;
                jsonAutoIgnoreCase = jsonAutoMatcher.group(1) != null;
                offset = jsonAutoMatcher.end();
            } else if (matchClause(manifestMatcher, offset, len)) {
                if (seenManifest) {
                    return null;
                }
                seenManifest = true;
                manifest = true;
                offset = manifestMatcher.end();
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
            } else if (matchClause(blanksAsNullMatcher, offset, len)) {
                if (seenBlanksAsNull) {
                    return null;
                }
                seenBlanksAsNull = true;
                blanksAsNull = true;
                offset = blanksAsNullMatcher.end();
            } else if (matchClause(emptyAsNullMatcher, offset, len)) {
                if (seenEmptyAsNull) {
                    return null;
                }
                seenEmptyAsNull = true;
                emptyAsNull = true;
                offset = emptyAsNullMatcher.end();
            } else if (matchClause(removeQuotesMatcher, offset, len)) {
                if (seenRemoveQuotes) {
                    return null;
                }
                seenRemoveQuotes = true;
                removeQuotes = true;
                offset = removeQuotesMatcher.end();
            } else if (matchClause(truncateColumnsMatcher, offset, len)) {
                if (seenTruncateColumns) {
                    return null;
                }
                seenTruncateColumns = true;
                truncateColumns = true;
                offset = truncateColumnsMatcher.end();
            } else if (matchClause(acceptInvCharsMatcher, offset, len)) {
                if (seenAcceptInvChars) {
                    return null;
                }
                seenAcceptInvChars = true;
                invalidChar = acceptInvCharsMatcher.group(1) != null
                        ? Character.valueOf(acceptInvCharsMatcher.group(1).charAt(0))
                        : Character.valueOf('?');
                offset = acceptInvCharsMatcher.end();
            } else if ((next = auth.consume(offset, len)) != AuthClauses.NO_MATCH) {
                if (next == AuthClauses.REJECT) {
                    return null;
                }
                offset = next;
            } else {
                return null;
            }
        }

        if (!auth.consistent()) {
            return null;
        }
        String iamRoleArn = auth.iamRoleArn();
        if (delimiter == null) {
            delimiter = csv ? "," : "|";
        }

        CopyTransforms transforms = new CopyTransforms(
                blanksAsNull, emptyAsNull, removeQuotes, truncateColumns, invalidChar);
        if (transforms.any() && jsonAuto) {
            return null;
        }
        if (transforms.removeQuotes() && csv) {
            return null;
        }
        if (transforms.fieldLevel() && delimiter.getBytes(StandardCharsets.UTF_8).length != 1) {
            return null;
        }
        if (invalidChar != null && replacementIsFramingSyntax(invalidChar, delimiter, csv)) {
            return null;
        }

        return new S3CopyFrom(table, columns, bucket, keyOrPrefix, delimiter, headerLines, gzip, csv,
                nullAs, iamRoleArn, jsonAuto, jsonAutoIgnoreCase, manifest, transforms);
    }

    /**
     * Collects the S3 authorization and region clauses shared by COPY and UNLOAD. Key material is
     * checked for shape only and never stored: the simulator reads S3 as the cluster role or as an
     * unsigned request.
     */
    private static final class AuthClauses {
        static final int NO_MATCH = -1;
        static final int REJECT = -2;

        private final Matcher iamRole;
        private final Matcher credentials;
        private final Matcher accessKeyId;
        private final Matcher secretAccessKey;
        private final Matcher sessionToken;
        private final Matcher region;

        private String iamRoleArn;
        private String credentialsRoleArn;
        private boolean seenIamRole;
        private boolean seenCredentials;
        private boolean seenAccessKeyId;
        private boolean seenSecretAccessKey;
        private boolean seenSessionToken;
        private boolean seenRegion;
        private boolean credentialsHaveKeys;
        private boolean credentialsMalformed;

        AuthClauses(String options) {
            iamRole = IAM_ROLE_PATTERN.matcher(options);
            credentials = CREDENTIALS_PATTERN.matcher(options);
            accessKeyId = ACCESS_KEY_ID_PATTERN.matcher(options);
            secretAccessKey = SECRET_ACCESS_KEY_PATTERN.matcher(options);
            sessionToken = SESSION_TOKEN_PATTERN.matcher(options);
            region = REGION_PATTERN.matcher(options);
        }

        int consume(int offset, int end) {
            if (matchClause(iamRole, offset, end)) {
                if (seenIamRole) {
                    return REJECT;
                }
                seenIamRole = true;
                iamRoleArn = extractNullValue(iamRole);
                return isBlank(iamRoleArn) ? REJECT : iamRole.end();
            }
            if (matchClause(credentials, offset, end)) {
                if (seenCredentials) {
                    return REJECT;
                }
                seenCredentials = true;
                readCredentials(extractNullValue(credentials));
                return credentials.end();
            }
            if (matchClause(accessKeyId, offset, end)) {
                if (seenAccessKeyId) {
                    return REJECT;
                }
                seenAccessKeyId = true;
                return isBlank(extractNullValue(accessKeyId)) ? REJECT : accessKeyId.end();
            }
            if (matchClause(secretAccessKey, offset, end)) {
                if (seenSecretAccessKey) {
                    return REJECT;
                }
                seenSecretAccessKey = true;
                return isBlank(extractNullValue(secretAccessKey)) ? REJECT : secretAccessKey.end();
            }
            if (matchClause(sessionToken, offset, end)) {
                if (seenSessionToken) {
                    return REJECT;
                }
                seenSessionToken = true;
                return isBlank(extractNullValue(sessionToken)) ? REJECT : sessionToken.end();
            }
            if (matchClause(region, offset, end)) {
                if (seenRegion) {
                    return REJECT;
                }
                seenRegion = true;
                return region.end();
            }
            return NO_MATCH;
        }

        private void readCredentials(String value) {
            Matcher role = CREDENTIALS_ROLE.matcher(value);
            if (role.find()) {
                credentialsRoleArn = role.group(1);
            }
            boolean hasId = CREDENTIALS_KEY_ID.matcher(value).find();
            boolean hasSecret = CREDENTIALS_SECRET.matcher(value).find();
            credentialsHaveKeys = hasId && hasSecret;
            credentialsMalformed = hasId != hasSecret;
        }

        boolean consistent() {
            if (credentialsMalformed || seenAccessKeyId != seenSecretAccessKey) {
                return false;
            }
            if (seenSessionToken && !seenAccessKeyId) {
                return false;
            }
            if (seenCredentials && seenAccessKeyId) {
                return false;
            }
            if (seenCredentials && credentialsRoleArn == null && !credentialsHaveKeys) {
                return false;
            }
            int roles = (seenIamRole ? 1 : 0) + (credentialsRoleArn != null ? 1 : 0);
            boolean keys = seenAccessKeyId || credentialsHaveKeys;
            return roles <= 1 && !(roles == 1 && keys);
        }

        String iamRoleArn() {
            return iamRoleArn != null ? iamRoleArn : credentialsRoleArn;
        }
    }

    /** The replacement is applied before records are scanned, so it must not change the framing. */
    private static boolean replacementIsFramingSyntax(char replacement, String delimiter, boolean csv) {
        return String.valueOf(replacement).equals(delimiter)
                || (!csv && replacement == '\\')
                || (csv && replacement == '"');
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
