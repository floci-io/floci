package io.github.hectorvent.floci.services.redshift.spectrum;

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
    private static final Pattern CREATE_EXTERNAL_TABLE = Pattern.compile("(?is)^\\s*CREATE\\s+EXTERNAL\\s+TABLE\\b.*");
    private static final Pattern CREATE_EXTERNAL = Pattern.compile("(?is)^\\s*CREATE\\s+EXTERNAL\\b.*");
    private static final Pattern SERDE_PROPERTIES = Pattern.compile("(?is)WITH\\s+SERDEPROPERTIES\\s*\\(");
    private static final String NAME = "(\"(?:\"\"|[^\"])+\"|[^.;,\\s\"()]+)";
    private static final Pattern CREATE_TABLE_HEAD = Pattern.compile("(?is)^\\s*CREATE\\s+EXTERNAL\\s+TABLE\\s+" + NAME + "\\s*\\.\\s*" + NAME + "\\s*\\(");
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
            String role = schema.group(4).trim();
            if (role.equalsIgnoreCase("default")) {
                throw new SpectrumSqlException("0A000", "IAM_ROLE DEFAULT is not supported");
            }
            if (!QUOTED_LITERAL.matcher(role).matches()) {
                throw new SpectrumSqlException("42601", "IAM_ROLE must be a single quoted role ARN");
            }
            return Optional.of(new ExternalStatement.CreateSchema(identifier(schema.group(2)),
                    schema.group(3).replace("''", "'"), unquote(role), schema.group(5) != null,
                    schema.group(1) != null));
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
            Matcher value = PARTITION_VALUE.matcher(text.substring(open + 1, close).trim() + ",");
            while (value.find()) {
                values.put(identifier(value.group(1)), value.group(2).replace("''", "'"));
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
