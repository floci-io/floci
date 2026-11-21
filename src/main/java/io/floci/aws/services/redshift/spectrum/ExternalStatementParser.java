package io.floci.aws.services.redshift.spectrum;

import io.floci.aws.core.common.SqlParameterParser;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class ExternalStatementParser {
    private static final Pattern CREATE_SCHEMA = Pattern.compile("(?is)^\\s*CREATE\\s+EXTERNAL\\s+SCHEMA\\s+(IF\\s+NOT\\s+EXISTS\\s+)?(.+?)\\s+FROM\\s+DATA\\s+CATALOG\\s+DATABASE\\s+'((?:''|[^'])*)'(?:\\s+REGION\\s+'(?:''|[^'])*')?\\s+IAM_ROLE\\s+(.+?)(\\s+CREATE\\s+EXTERNAL\\s+DATABASE\\s+IF\\s+NOT\\s+EXISTS)?\\s*;?\\s*$");
    private static final Pattern CREATE_KINESIS_SCHEMA = Pattern.compile("(?is)^\\s*CREATE\\s+EXTERNAL\\s+SCHEMA\\s+(IF\\s+NOT\\s+EXISTS\\s+)?(.+?)\\s+FROM\\s+KINESIS(?:\\s+REGION\\s+'((?:''|[^'])*)')?\\s+IAM_ROLE\\s+(.+?)\\s*;?\\s*$");
    private static final Pattern CREATE_EXTERNAL_TABLE = Pattern.compile("(?is)^\\s*CREATE\\s+EXTERNAL\\s+TABLE\\b.*");
    private static final Pattern CREATE_EXTERNAL = Pattern.compile("(?is)^\\s*CREATE\\s+EXTERNAL\\b.*");
    private static final Pattern SERDE_PROPERTIES = Pattern.compile("(?is)WITH\\s+SERDEPROPERTIES\\s*\\(");
    private static final String NAME = "(\"(?:\"\"|[^\"])+\"|[^.;,\\s\"()]+)";
    private static final Pattern CREATE_MSK_SCHEMA_HEAD = Pattern.compile(
            "(?is)^\\s*CREATE\\s+EXTERNAL\\s+SCHEMA\\s+(IF\\s+NOT\\s+EXISTS\\s+)?" + NAME + "\\s+FROM\\s+MSK\\b(.*?)\\s*;?\\s*$");
    private static final Pattern CREATE_TABLE_HEAD = Pattern.compile("(?is)^\\s*CREATE\\s+EXTERNAL\\s+TABLE\\s+" + NAME + "\\s*\\.\\s*" + NAME + "\\s*\\(");
    private static final Pattern CREATE_VIEW = Pattern.compile("(?is)^\\s*CREATE\\s+MATERIALIZED\\s+VIEW\\s+" + NAME + "(?:\\s*\\.\\s*" + NAME + ")?\\s+(?:BACKUP\\s+(?:YES|NO)\\s+)?(?:AUTO\\s+REFRESH\\s+(YES|NO)\\s+)?AS\\s+((?:SELECT|WITH)\\b.*?)\\s*;?\\s*$");
    private static final Pattern REFRESH_VIEW = Pattern.compile("(?is)^\\s*REFRESH\\s+MATERIALIZED\\s+VIEW\\s+" + NAME + "(?:\\s*\\.\\s*" + NAME + ")?\\s*;?\\s*$");
    private static final Pattern DROP_VIEW = Pattern.compile("(?is)^\\s*DROP\\s+MATERIALIZED\\s+VIEW\\s+(IF\\s+EXISTS\\s+)?" + NAME + "(?:\\s*\\.\\s*" + NAME + ")?(?:\\s+(CASCADE|RESTRICT))?\\s*;?\\s*$");
    private static final Pattern DROP_TABLE = Pattern.compile("(?is)^\\s*DROP\\s+TABLE\\s+(IF\\s+EXISTS\\s+)?" + NAME + "\\.\\s*" + NAME + "(?:\\s+(CASCADE|RESTRICT))?\\s*;?\\s*$");
    private static final Pattern DROP_SCHEMA = Pattern.compile("(?is)^\\s*DROP\\s+SCHEMA\\s+(IF\\s+EXISTS\\s+)?" + NAME + "(?:\\s+(CASCADE|RESTRICT))?\\s*;?\\s*$");
    private static final Pattern TABLE_PROPERTIES = Pattern.compile("(?is)(?:TBLPROPERTIES|TABLE\\s+PROPERTIES)\\s*\\(");
    private static final Pattern PARTITION_VALUE = Pattern.compile("(?is)([^,=]+)=\\s*'((?:''|[^'])*)'\\s*(?:,|$)");
    private static final Pattern LOCATION_VALUE = Pattern.compile("(?is)LOCATION\\s+'((?:''|[^'])*)'");
    private static final Pattern SERDE_VALUE = Pattern.compile("(?is)SERDE\\s+'((?:''|[^'])*)'");
    private static final Pattern TERMINATED_BY_VALUE = Pattern.compile("(?is)TERMINATED BY\\s+'((?:''|[^'])*)'");
    private static final Pattern STORED_AS_VALUE = Pattern.compile("(?is)STORED AS\\s+([A-Za-z]+)");
    private static final Pattern QUOTED_LITERAL = Pattern.compile("'(?:''|[^'])*'");
    private static final Pattern QUOTED_PAIR = Pattern.compile("'((?:''|[^'])*)'\\s*=\\s*'((?:''|[^'])*)'");
    private static final Pattern PARTITION_HEAD = Pattern.compile("(?is)PARTITION\\s*\\(");
    private static final Pattern PARTITION_LOCATION = Pattern.compile("(?is)\\s*LOCATION\\s+'((?:''|[^'])*)'");
    private static final Pattern PARTITIONED_BY = Pattern.compile("(?is)PARTITIONED\\s+BY\\s*\\(");
    private static final Pattern ADD_PARTITIONS = Pattern.compile("(?is)^\\s*ALTER\\s+TABLE\\s+" + NAME + "\\.\\s*" + NAME + "\\s+ADD\\s+(IF\\s+NOT\\s+EXISTS\\s+)?(.+?)\\s*;?\\s*$");

    public Optional<ExternalStatement> parse(String sql) {
        if (sql == null) {
            return Optional.empty();
        }
        String text = stripLeadingComments(sql);
        Matcher schema = CREATE_SCHEMA.matcher(text);
        if (schema.matches()) {
            return Optional.of(new ExternalStatement.CreateSchema(identifier(schema.group(2)),
                    schema.group(3).replace("''", "'"), roleArn(schema.group(4)), schema.group(5) != null,
                    schema.group(1) != null));
        }
        Matcher kinesis = CREATE_KINESIS_SCHEMA.matcher(text);
        if (kinesis.matches()) {
            String region = kinesis.group(3);
            return Optional.of(new ExternalStatement.CreateKinesisSchema(identifier(kinesis.group(2)),
                    region == null ? null : region.replace("''", "'"), roleArn(kinesis.group(4)),
                    kinesis.group(1) != null));
        }
        // comments are separators here; the view's query is stored and sent again on every refresh
        String viewText = withoutComments(text);
        Matcher msk = CREATE_MSK_SCHEMA_HEAD.matcher(viewText);
        if (msk.matches()) {
            return Optional.of(parseMskSchema(identifier(msk.group(2)), msk.group(1) != null, msk.group(3)));
        }
        Matcher createView = CREATE_VIEW.matcher(viewText);
        if (createView.matches()) {
            if (hasStatementSeparator(createView.group(4))) {
                throw new SpectrumSqlException("42601",
                        "A materialized view query must be a single statement");
            }
            return Optional.of(new ExternalStatement.CreateStreamingView(qualifier(createView.group(1), createView.group(2)),
                    objectName(createView.group(1), createView.group(2)), "YES".equalsIgnoreCase(createView.group(3)),
                    createView.group(4).replaceFirst("[\\s;]+$", "")));
        }
        Matcher refreshView = REFRESH_VIEW.matcher(viewText);
        if (refreshView.matches()) {
            return Optional.of(new ExternalStatement.RefreshView(qualifier(refreshView.group(1), refreshView.group(2)),
                    objectName(refreshView.group(1), refreshView.group(2))));
        }
        Matcher dropView = DROP_VIEW.matcher(viewText);
        if (dropView.matches()) {
            return Optional.of(new ExternalStatement.DropView(qualifier(dropView.group(2), dropView.group(3)),
                    objectName(dropView.group(2), dropView.group(3)), dropView.group(1) != null,
                    "CASCADE".equalsIgnoreCase(dropView.group(4))));
        }
        if (CREATE_EXTERNAL_TABLE.matcher(text).matches()) {
            return Optional.of(parseTable(text));
        }
        if (CREATE_EXTERNAL.matcher(text).matches()) {
            throw new SpectrumSqlException("0A000", "unsupported CREATE EXTERNAL statement");
        }
        Matcher dropTable = DROP_TABLE.matcher(text);
        if (dropTable.matches()) {
            return Optional.of(new ExternalStatement.DropTable(identifier(dropTable.group(2)), identifier(dropTable.group(3)),
                    dropTable.group(1) != null, "CASCADE".equalsIgnoreCase(dropTable.group(4))));
        }
        Matcher dropSchema = DROP_SCHEMA.matcher(text);
        if (dropSchema.matches()) {
            return Optional.of(new ExternalStatement.DropSchema(identifier(dropSchema.group(2)),
                    dropSchema.group(1) != null, "CASCADE".equalsIgnoreCase(dropSchema.group(3))));
        }
        Matcher addPartitions = ADD_PARTITIONS.matcher(text);
        if (addPartitions.matches()) {
            List<ExternalStatement.PartitionSpec> partitions = parsePartitions(addPartitions.group(4));
            return partitions.isEmpty() ? Optional.empty() : Optional.of(new ExternalStatement.AddPartitions(
                    identifier(addPartitions.group(1)), identifier(addPartitions.group(2)),
                    addPartitions.group(3) != null, partitions));
        }
        return Optional.empty();
    }

    private ExternalStatement.CreateTable parseTable(String text) {
        Matcher matcher = CREATE_TABLE_HEAD.matcher(text);
        if (!matcher.find()) {
            throw new SpectrumSqlException("0A000", "malformed CREATE EXTERNAL TABLE");
        }
        int open = matcher.end() - 1;
        int close = matchingParen(text, open);
        if (close < 0) {
            throw new SpectrumSqlException("0A000", "malformed CREATE EXTERNAL TABLE column list");
        }
        String tail = text.substring(close + 1);
        List<ExternalStatement.ColumnDefinition> columns = columns(text.substring(open + 1, close));
        List<ExternalStatement.ColumnDefinition> partitions = List.of();
        Matcher partition = PARTITIONED_BY.matcher(tail);
        if (partition.find()) {
            // Partition columns may have types such as decimal(10,2), so match nested parentheses.
            int partitionOpen = partition.end() - 1;
            int partitionClose = matchingParen(tail, partitionOpen);
            if (partitionClose < 0) {
                throw new SpectrumSqlException("0A000", "malformed CREATE EXTERNAL TABLE partition list");
            }
            partitions = columns(tail.substring(partitionOpen + 1, partitionClose));
        }
        String location = value(tail, LOCATION_VALUE);
        if (location == null || !location.startsWith("s3://")) {
            throw new SpectrumSqlException("22023", "external table location must be an s3:// URI");
        }
        String serde = value(tail, SERDE_VALUE);
        String delimiter = value(tail, TERMINATED_BY_VALUE);
        String stored = keywordValue(tail, STORED_AS_VALUE);
        if (stored == null) {
            throw new SpectrumSqlException("0A000", "external table requires a STORED AS clause");
        }
        if (!(stored.equalsIgnoreCase("PARQUET") || stored.equalsIgnoreCase("TEXTFILE"))) {
            throw new SpectrumSqlException("0A000", "unsupported external table format: " + stored);
        }
        ExternalStatement.TableFormat format = stored.equalsIgnoreCase("PARQUET")
                ? ExternalStatement.TableFormat.PARQUET
                : serde != null && serde.toLowerCase(Locale.ROOT).contains("json")
                ? ExternalStatement.TableFormat.JSON : ExternalStatement.TableFormat.TEXTFILE;
        Map<String, String> properties = properties(tail);
        return new ExternalStatement.CreateTable(identifier(matcher.group(1)), identifier(matcher.group(2)), columns, partitions,
                format, location, delimiter, serde, properties, serdeProperties(tail));
    }

    /** The SQL with each comment outside a quoted string or identifier replaced by a space. */
    static String withoutComments(String sql) {
        return SqlParameterParser.stripComments(sql, SqlParameterParser.Options.REDSHIFT);
    }

    /** True when a statement follows a {@code ;} that sits outside a quoted string or identifier. */
    static boolean hasStatementSeparator(String sql) {
        return SqlParameterParser.isMultiStatement(sql, SqlParameterParser.Options.REDSHIFT);
    }

    /** The schema of {@code first.second}, null for a bare name. */
    private static String qualifier(String first, String second) {
        return second == null ? null : identifier(first);
    }

    private static String objectName(String first, String second) {
        return identifier(second == null ? first : second);
    }

    private static String roleArn(String clause) {
        String role = clause.trim();
        if (role.equalsIgnoreCase("default")) {
            throw new SpectrumSqlException("0A000", "IAM_ROLE DEFAULT is not supported");
        }
        if (!QUOTED_LITERAL.matcher(role).matches()) {
            throw new SpectrumSqlException("42601", "IAM_ROLE must be a single quoted role ARN");
        }
        String arn = unquote(role);
        if (arn.isBlank()) {
            throw new SpectrumSqlException("42601", "IAM_ROLE must be a single quoted role ARN");
        }
        return arn;
    }

    private static ExternalStatement.CreateMskSchema parseMskSchema(String schemaName, boolean ifNotExists,
                                                                    String tail) {
        String iamRoleArn = null;
        String authentication = null;
        String clusterArn = null;
        int pos = 0;
        int length = tail.length();
        while (true) {
            pos = skipWhitespace(tail, pos);
            if (pos >= length) {
                break;
            }
            int keyEnd = readKeywordEnd(tail, pos);
            String keyword = tail.substring(pos, keyEnd).toUpperCase(Locale.ROOT);
            pos = skipWhitespace(tail, keyEnd);
            switch (keyword) {
                case "IAM_ROLE" -> {
                    if (iamRoleArn != null) {
                        throw new SpectrumSqlException("42601", "Duplicate IAM_ROLE clause");
                    }
                    TokenSlice slice = readValueToken(tail, pos, "IAM_ROLE");
                    pos = slice.end();
                    iamRoleArn = roleArn(slice.text());
                }
                case "AUTHENTICATION" -> {
                    if (authentication != null) {
                        throw new SpectrumSqlException("42601", "Duplicate AUTHENTICATION clause");
                    }
                    TokenSlice slice = readValueToken(tail, pos, "AUTHENTICATION");
                    pos = slice.end();
                    String mode = slice.text().trim();
                    if (mode.equalsIgnoreCase("iam")) {
                        authentication = "IAM";
                    } else if (mode.equalsIgnoreCase("none")) {
                        authentication = "NONE";
                    } else if (mode.equalsIgnoreCase("mtls")) {
                        throw new SpectrumSqlException("0A000", "AUTHENTICATION mtls is not supported");
                    } else {
                        throw new SpectrumSqlException("42601", "AUTHENTICATION must be iam, none, or mtls");
                    }
                }
                case "CLUSTER_ARN" -> {
                    if (clusterArn != null) {
                        throw new SpectrumSqlException("42601", "Duplicate CLUSTER_ARN clause");
                    }
                    TokenSlice slice = readValueToken(tail, pos, "CLUSTER_ARN");
                    pos = slice.end();
                    clusterArn = requireQuotedLiteral(slice.text(), "CLUSTER_ARN must be a single quoted cluster ARN");
                }
                case "URI", "AUTHENTICATION_ARN", "SECRET_ARN" ->
                        throw new SpectrumSqlException("0A000", keyword + " is not supported for MSK external schemas");
                default -> throw new SpectrumSqlException("42601",
                        "Unexpected clause in CREATE EXTERNAL SCHEMA FROM MSK: " + keyword);
            }
        }
        if (iamRoleArn == null) {
            throw new SpectrumSqlException("42601", "CREATE EXTERNAL SCHEMA FROM MSK requires IAM_ROLE");
        }
        if (authentication == null) {
            throw new SpectrumSqlException("42601", "CREATE EXTERNAL SCHEMA FROM MSK requires AUTHENTICATION");
        }
        if (clusterArn == null) {
            throw new SpectrumSqlException("42601", "CREATE EXTERNAL SCHEMA FROM MSK requires CLUSTER_ARN");
        }
        return new ExternalStatement.CreateMskSchema(schemaName, iamRoleArn, authentication,
                clusterArn, ifNotExists);
    }

    private record TokenSlice(String text, int end) { }

    private static int skipWhitespace(String text, int pos) {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
        return pos;
    }

    private static int readKeywordEnd(String text, int pos) {
        int index = pos;
        while (index < text.length() && !Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    private static TokenSlice readValueToken(String text, int pos, String clause) {
        if (pos >= text.length()) {
            throw new SpectrumSqlException("42601", "Missing value for " + clause);
        }
        if (text.charAt(pos) == '\'') {
            int index = pos + 1;
            while (index < text.length()) {
                if (text.charAt(index) == '\'') {
                    if (index + 1 < text.length() && text.charAt(index + 1) == '\'') {
                        index += 2;
                    } else {
                        return new TokenSlice(text.substring(pos, index + 1), index + 1);
                    }
                } else {
                    index++;
                }
            }
            throw new SpectrumSqlException("42601", "Unterminated string literal for " + clause);
        }
        int end = pos;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        return new TokenSlice(text.substring(pos, end), end);
    }

    private static String requireQuotedLiteral(String token, String message) {
        String trimmed = token.trim();
        if (!QUOTED_LITERAL.matcher(trimmed).matches()) {
            throw new SpectrumSqlException("42601", message);
        }
        String value = unquote(trimmed);
        if (value.isBlank()) {
            throw new SpectrumSqlException("42601", message);
        }
        return value;
    }

    private static String stripLeadingComments(String sql) {
        String text = sql.trim();
        while (true) {
            int end;
            if (text.startsWith("--")) {
                end = text.indexOf('\n');
                end = end < 0 ? text.length() : end;
            } else if (text.startsWith("/*")) {
                end = text.indexOf("*/", 2);
                end = end < 0 ? text.length() : end + 2;
            } else {
                return text;
            }
            text = text.substring(end).trim();
        }
    }

    private static int matchingParen(String text, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    if (i + 1 < text.length() && text.charAt(i + 1) == quote) {
                        i++;
                    } else {
                        quote = 0;
                    }
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static List<ExternalStatement.ColumnDefinition> columns(String text) {
        List<ExternalStatement.ColumnDefinition> result = new ArrayList<>();
        for (String item : split(text)) {
            String definition = item.trim();
            int nameEnd = columnNameEnd(definition);
            if (nameEnd < 0 || nameEnd == definition.length() || definition.substring(nameEnd).isBlank()) {
                throw new SpectrumSqlException("0A000", "malformed column definition");
            }
            result.add(new ExternalStatement.ColumnDefinition(identifier(definition.substring(0, nameEnd)),
                    GlueTypeMapper.canonicalGlueType(definition.substring(nameEnd).trim())));
        }
        return result;
    }

    private static int columnNameEnd(String definition) {
        if (!definition.startsWith("\"")) {
            for (int index = 0; index < definition.length(); index++) {
                if (Character.isWhitespace(definition.charAt(index))) {
                    return index;
                }
            }
            return -1;
        }
        for (int index = 1; index < definition.length(); index++) {
            if (definition.charAt(index) == '"') {
                if (index + 1 < definition.length() && definition.charAt(index + 1) == '"') {
                    index++;
                } else {
                    return index + 1;
                }
            }
        }
        return -1;
    }

    private static List<String> split(String text) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    if (i + 1 < text.length() && text.charAt(i + 1) == quote) {
                        i++;
                    } else {
                        quote = 0;
                    }
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(' || c == '<') {
                depth++;
            } else if (c == ')' || c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                result.add(text.substring(start, i));
                start = i + 1;
            }
        }
        result.add(text.substring(start));
        return result;
    }

    private static String value(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).replace("''", "'") : null;
    }

    private static String keywordValue(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static Map<String, String> serdeProperties(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = SERDE_PROPERTIES.matcher(text);
        if (matcher.find()) {
            int open = matcher.end() - 1;
            int close = matchingParen(text, open);
            if (close < 0) {
                throw new SpectrumSqlException("0A000", "malformed SERDEPROPERTIES clause");
            }
            result.putAll(quotedPairs(text.substring(open + 1, close)));
        }
        return result;
    }

    private static Map<String, String> properties(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = TABLE_PROPERTIES.matcher(text);
        if (matcher.find()) {
            int open = matcher.end() - 1;
            int close = matchingParen(text, open);
            if (close < 0) {
                throw new SpectrumSqlException("0A000", "malformed TABLE PROPERTIES clause");
            }
            result.putAll(quotedPairs(text.substring(open + 1, close)));
        }
        return result;
    }

    private static Map<String, String> quotedPairs(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher pair = QUOTED_PAIR.matcher(text);
        while (pair.find()) {
            result.put(pair.group(1).replace("''", "'"), pair.group(2).replace("''", "'"));
        }
        return result;
    }

    private static List<ExternalStatement.PartitionSpec> parsePartitions(String text) {
        List<ExternalStatement.PartitionSpec> result = new ArrayList<>();
        Matcher head = PARTITION_HEAD.matcher(text);
        int end = 0;
        while (head.find(end)) {
            if (!text.substring(end, head.start()).isBlank()) {
                return List.of();
            }
            int open = head.end() - 1;
            int close = matchingParen(text, open);
            if (close < 0) {
                return List.of();
            }
            Matcher location = PARTITION_LOCATION.matcher(text).region(close + 1, text.length());
            if (!location.lookingAt()) {
                return List.of();
            }
            Map<String, String> values = new LinkedHashMap<>();
            String keys = text.substring(open + 1, close).trim();
            Matcher value = PARTITION_VALUE.matcher(keys);
            int position = 0;
            while (position < keys.length()) {
                if (!value.region(position, keys.length()).lookingAt()) {
                    throw new SpectrumSqlException("42601", "Malformed partition key list");
                }
                String name = identifier(value.group(1));
                if (values.keySet().stream().anyMatch(existing -> existing.equalsIgnoreCase(name))) {
                    throw new SpectrumSqlException("42601", "Duplicate partition key: " + name);
                }
                values.put(name, value.group(2).replace("''", "'"));
                position = value.end();
                if (position == keys.length() && keys.endsWith(",")) {
                    throw new SpectrumSqlException("42601", "Malformed partition key list");
                }
            }
            if (values.isEmpty()) {
                return List.of();
            }
            result.add(new ExternalStatement.PartitionSpec(values, location.group(1).replace("''", "'")));
            end = location.end();
        }
        return end == text.length() || text.substring(end).isBlank() ? result : List.of();
    }

    private static String identifier(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).replace("\"\"", "\"");
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("'") && trimmed.endsWith("'")
                ? trimmed.substring(1, trimmed.length() - 1).replace("''", "'") : trimmed;
    }
}
