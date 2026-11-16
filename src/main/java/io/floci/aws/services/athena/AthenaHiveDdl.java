package io.floci.aws.services.athena;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.glue.iceberg.IcebergSchemas;
import io.floci.aws.services.glue.model.Column;
import io.floci.aws.services.glue.model.StorageDescriptor;
import io.floci.aws.services.glue.model.Table;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses the Hive DDL statements Athena runs against the Glue Data Catalog rather than against
 * data: {@code CREATE EXTERNAL TABLE}, {@code DROP TABLE}, {@code ALTER TABLE ... ADD|DROP
 * PARTITION}, {@code MSCK REPAIR TABLE} and {@code SHOW PARTITIONS}, and the Iceberg {@code CREATE TABLE ...
 * TBLPROPERTIES ('table_type'='ICEBERG')}, {@code VACUUM} and {@code OPTIMIZE}, which Floci runs against Glue's Iceberg
 * catalog. A table is named {@code t} or {@code db.t}, its catalog being the query's context catalog.
 * {@link #parse} answers null for every other statement, {@code CREATE TABLE AS} included, so those keep going to
 * the query engine.
 *
 * <p>Names are lower-cased, as Athena stores them in Glue. String literals take Hive's backslash
 * escapes, so {@code FIELDS TERMINATED BY '\t'} stores a tab.
 */
final class AthenaHiveDdl {

    sealed interface Statement permits CreateTable, DropTable, AddPartitions, DropPartitions, RepairTable,
            ShowPartitions, CreateIcebergTable, Vacuum, Optimize {
        TableRef ref();
    }

    /** A table name; {@code database} is null when the statement leaves it to the query context. */
    record TableRef(String database, String table) {
    }

    record CreateTable(TableRef ref, boolean ifNotExists, Table table) implements Statement {
    }

    record DropTable(TableRef ref, boolean ifExists) implements Statement {
    }

    /** One partition: its key values in the order written, and its location when one is given. */
    record PartitionSpec(Map<String, String> values, String location) {
    }

    record AddPartitions(TableRef ref, boolean ifNotExists, List<PartitionSpec> partitions) implements Statement {
    }

    record DropPartitions(TableRef ref, boolean ifExists, List<PartitionSpec> partitions) implements Statement {
    }

    record RepairTable(TableRef ref) implements Statement {
    }

    record ShowPartitions(TableRef ref) implements Statement {
    }

    /** An Iceberg {@code CREATE TABLE}: its columns, partition transforms, location and other table properties. */
    record CreateIcebergTable(TableRef ref, boolean ifNotExists, List<Column> columns,
                              List<IcebergSchemas.PartitionField> partitioning, String location,
                              Map<String, String> properties) implements Statement {
    }

    /** {@code VACUUM}: expires an Iceberg table's old snapshots. */
    record Vacuum(TableRef ref) implements Statement {
    }

    /** {@code OPTIMIZE ... REWRITE DATA USING BIN_PACK}: compacts an Iceberg table's data files. */
    record Optimize(TableRef ref) implements Statement {
    }

    private record Format(String inputFormat, String outputFormat, String serde) {
    }

    private static final String LAZY_SIMPLE_SERDE = "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe";
    private static final Map<String, Format> FORMATS = Map.of(
            "TEXTFILE", new Format("org.apache.hadoop.mapred.TextInputFormat",
                    "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat", LAZY_SIMPLE_SERDE),
            "SEQUENCEFILE", new Format("org.apache.hadoop.mapred.SequenceFileInputFormat",
                    "org.apache.hadoop.hive.ql.io.HiveSequenceFileOutputFormat", LAZY_SIMPLE_SERDE),
            "RCFILE", new Format("org.apache.hadoop.hive.ql.io.RCFileInputFormat",
                    "org.apache.hadoop.hive.ql.io.RCFileOutputFormat",
                    "org.apache.hadoop.hive.serde2.columnar.LazyBinaryColumnarSerDe"),
            "ORC", new Format("org.apache.hadoop.hive.ql.io.orc.OrcInputFormat",
                    "org.apache.hadoop.hive.ql.io.orc.OrcOutputFormat", "org.apache.hadoop.hive.ql.io.orc.OrcSerde"),
            "PARQUET", new Format("org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat",
                    "org.apache.hadoop.hive.ql.io.parquet.MapredParquetOutputFormat",
                    "org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe"),
            "AVRO", new Format("org.apache.hadoop.hive.ql.io.avro.AvroContainerInputFormat",
                    "org.apache.hadoop.hive.ql.io.avro.AvroContainerOutputFormat",
                    "org.apache.hadoop.hive.serde2.avro.AvroSerDe"),
            "ION", new Format("com.amazon.ionhiveserde.formats.IonInputFormat",
                    "com.amazon.ionhiveserde.formats.IonOutputFormat", "com.amazon.ionhiveserde.IonHiveSerDe"));

    /** Words that end a column type when they follow it at the top level. */
    private static final Set<String> TYPE_TERMINATORS = Set.of("COMMENT");

    private final List<Token> tokens;
    private int position;

    private AthenaHiveDdl(List<Token> tokens) {
        this.tokens = tokens;
    }

    /** The statement {@code sql} holds, or null when it is not one of the statements this class runs. */
    static Statement parse(String sql) {
        List<Token> tokens = tokenize(sql);
        if (tokens.isEmpty()) {
            return null;
        }
        return new AthenaHiveDdl(tokens).statement();
    }

    private Statement statement() {
        if (acceptWords("CREATE", "EXTERNAL", "TABLE")) {
            return finish(createTable());
        }
        if (peekWords("CREATE", "TABLE")) {
            if (isCtas()) {
                return null;
            }
            if (isIceberg()) {
                return finish(createIcebergTable());
            }
            throw invalid("Athena creates a table without EXTERNAL only as an Iceberg table "
                    + "(TBLPROPERTIES ('table_type'='ICEBERG')); use CREATE EXTERNAL TABLE for a Hive table");
        }
        if (acceptWords("DROP", "TABLE")) {
            boolean ifExists = acceptWords("IF", "EXISTS");
            return finish(new DropTable(tableRef(), ifExists));
        }
        if (acceptWords("VACUUM")) {
            return finish(new Vacuum(tableRef()));
        }
        if (acceptWords("OPTIMIZE")) {
            // Its REWRITE DATA USING BIN_PACK clause and filter are not read: no table this runs on compacts.
            Optimize optimize = new Optimize(tableRef());
            position = tokens.size();
            return optimize;
        }
        if (acceptWords("MSCK", "REPAIR", "TABLE")) {
            return finish(new RepairTable(tableRef()));
        }
        if (acceptWords("SHOW", "PARTITIONS")) {
            return finish(new ShowPartitions(tableRef()));
        }
        if (acceptWords("ALTER", "TABLE")) {
            TableRef ref = tableRef();
            if (acceptWords("ADD")) {
                boolean ifNotExists = acceptWords("IF", "NOT", "EXISTS");
                if (peekWords("PARTITION")) {
                    return finish(new AddPartitions(ref, ifNotExists, partitionSpecs(true)));
                }
            } else if (acceptWords("DROP")) {
                boolean ifExists = acceptWords("IF", "EXISTS");
                if (peekWords("PARTITION")) {
                    return finish(new DropPartitions(ref, ifExists, partitionSpecs(false)));
                }
            }
            throw invalid("Floci runs ALTER TABLE ADD PARTITION and ALTER TABLE DROP PARTITION; "
                    + "other ALTER TABLE statements are not supported");
        }
        return null;
    }

    private Statement finish(Statement statement) {
        accept(";");
        if (position < tokens.size()) {
            throw invalid("Unexpected '" + tokens.get(position).text() + "' in the statement");
        }
        return statement;
    }

    /** A {@code CREATE TABLE AS} (with a {@code WITH (...)} property list or not), which the query engine runs. */
    private boolean isCtas() {
        for (int i = position; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            Token following = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
            boolean query = following != null && (following.isWord("SELECT") || following.isWord("WITH")
                    || following.isWord("VALUES") || following.is("("));
            if (token.isWord("AS") && query || token.isWord("WITH") && following != null && following.is("(")) {
                return true;
            }
        }
        return false;
    }

    /** A {@code CREATE TABLE} whose table properties make it an Iceberg table. */
    private boolean isIceberg() {
        for (int i = position; i + 2 < tokens.size(); i++) {
            if (tokens.get(i).kind() == Kind.STRING && "table_type".equalsIgnoreCase(tokens.get(i).text())
                    && tokens.get(i + 1).is("=") && "ICEBERG".equalsIgnoreCase(tokens.get(i + 2).text())) {
                return true;
            }
        }
        return false;
    }

    private CreateIcebergTable createIcebergTable() {
        expectWords("CREATE", "TABLE");
        boolean ifNotExists = acceptWords("IF", "NOT", "EXISTS");
        TableRef ref = tableRef();
        expect("(");
        List<Column> columns = columns();
        String location = null;
        List<IcebergSchemas.PartitionField> partitioning = new ArrayList<>();
        Map<String, String> properties = new LinkedHashMap<>();
        while (position < tokens.size() && !tokens.get(position).is(";")) {
            if (acceptWords("PARTITIONED", "BY")) {
                expect("(");
                partitioning = partitionTransforms();
            } else if (acceptWords("LOCATION")) {
                location = string();
            } else if (acceptWords("TBLPROPERTIES")) {
                properties.putAll(properties());
            } else {
                throw invalid("Unexpected '" + tokens.get(position).text() + "' in CREATE TABLE");
            }
        }
        properties.keySet().removeIf(key -> key.equalsIgnoreCase("table_type"));
        return new CreateIcebergTable(ref, ifNotExists, columns, partitioning, location, properties);
    }

    /**
     * An Iceberg table's {@code PARTITIONED BY} entries, after the opening parenthesis: a column, or a transform of
     * one ({@code day(ts)}, {@code bucket(16, id)}, {@code truncate(4, name)}).
     */
    private List<IcebergSchemas.PartitionField> partitionTransforms() {
        List<IcebergSchemas.PartitionField> fields = new ArrayList<>();
        do {
            String name = identifier();
            if (accept("(")) {
                Integer width = null;
                if (position < tokens.size() && tokens.get(position).kind() == Kind.NUMBER) {
                    String argument = next().text();
                    try {
                        width = Integer.parseInt(argument);
                    } catch (NumberFormatException e) {
                        throw invalid("The width of partition transform " + name + " must be a whole number: "
                                + argument);
                    }
                    expect(",");
                }
                fields.add(new IcebergSchemas.PartitionField(name, width, identifier()));
                expect(")");
            } else {
                fields.add(new IcebergSchemas.PartitionField("identity", null, name));
            }
        } while (accept(","));
        expect(")");
        return fields;
    }

    private CreateTable createTable() {
        boolean ifNotExists = acceptWords("IF", "NOT", "EXISTS");
        TableRef ref = tableRef();
        Table table = new Table();
        table.setName(ref.table());
        table.setTableType("EXTERNAL_TABLE");
        StorageDescriptor descriptor = new StorageDescriptor();
        descriptor.setColumns(accept("(") ? columns() : new ArrayList<>());
        descriptor.setCompressed(false);
        descriptor.setNumberOfBuckets(-1);
        descriptor.setStoredAsSubDirectories(false);
        StorageDescriptor.SerDeInfo serde = new StorageDescriptor.SerDeInfo();
        Map<String, String> serdeParameters = new LinkedHashMap<>();
        Map<String, String> tableParameters = new LinkedHashMap<>();
        List<Column> partitionKeys = new ArrayList<>();
        String format = null;
        boolean delimited = false;
        while (position < tokens.size() && !tokens.get(position).is(";")) {
            if (acceptWords("COMMENT")) {
                table.setDescription(string());
            } else if (acceptWords("PARTITIONED", "BY")) {
                expect("(");
                partitionKeys.addAll(columns());
            } else if (acceptWords("CLUSTERED", "BY")) {
                descriptor.setBucketColumns(identifierList());
                if (acceptWords("SORTED", "BY")) {
                    descriptor.setSortColumns(sortColumns());
                }
                expectWords("INTO");
                descriptor.setNumberOfBuckets(Integer.parseInt(next().text()));
                expectWords("BUCKETS");
            } else if (acceptWords("ROW", "FORMAT", "DELIMITED")) {
                delimited = true;
                delimiters(serdeParameters);
            } else if (acceptWords("ROW", "FORMAT", "SERDE")) {
                serde.setSerializationLibrary(string());
                if (acceptWords("WITH", "SERDEPROPERTIES")) {
                    serdeParameters.putAll(properties());
                }
            } else if (acceptWords("STORED", "AS", "INPUTFORMAT")) {
                descriptor.setInputFormat(string());
                expectWords("OUTPUTFORMAT");
                descriptor.setOutputFormat(string());
            } else if (acceptWords("STORED", "AS")) {
                format = next().text().toUpperCase(Locale.ROOT);
                if (!FORMATS.containsKey(format)) {
                    throw invalid("Unsupported STORED AS format: " + format);
                }
            } else if (acceptWords("WITH", "SERDEPROPERTIES")) {
                serdeParameters.putAll(properties());
            } else if (acceptWords("LOCATION")) {
                descriptor.setLocation(string());
            } else if (acceptWords("TBLPROPERTIES")) {
                tableParameters.putAll(properties());
            } else {
                throw invalid("Unexpected '" + tokens.get(position).text() + "' in CREATE EXTERNAL TABLE");
            }
        }
        Format storage = FORMATS.get(format == null ? "TEXTFILE" : format);
        if (descriptor.getInputFormat() == null) {
            descriptor.setInputFormat(storage.inputFormat());
            descriptor.setOutputFormat(storage.outputFormat());
        }
        if (serde.getSerializationLibrary() == null) {
            serde.setSerializationLibrary(delimited ? LAZY_SIMPLE_SERDE : storage.serde());
        }
        if (LAZY_SIMPLE_SERDE.equals(serde.getSerializationLibrary())) {
            serdeParameters.putIfAbsent("serialization.format", "1");
        }
        serde.setParameters(serdeParameters);
        descriptor.setSerdeInfo(serde);
        table.setStorageDescriptor(descriptor);
        table.setPartitionKeys(partitionKeys);
        tableParameters.put("EXTERNAL", "TRUE");
        table.setParameters(tableParameters);
        return new CreateTable(ref, ifNotExists, table);
    }

    /** The {@code ROW FORMAT DELIMITED} clauses, as the SerDe parameters Hive stores for them. */
    private void delimiters(Map<String, String> parameters) {
        while (true) {
            if (acceptWords("FIELDS", "TERMINATED", "BY")) {
                String delimiter = string();
                parameters.put("field.delim", delimiter);
                parameters.put("serialization.format", delimiter);
                if (acceptWords("ESCAPED", "BY")) {
                    parameters.put("escape.delim", string());
                }
            } else if (acceptWords("COLLECTION", "ITEMS", "TERMINATED", "BY")) {
                // Hive's own spelling of the key.
                parameters.put("colelction.delim", string());
            } else if (acceptWords("MAP", "KEYS", "TERMINATED", "BY")) {
                parameters.put("mapkey.delim", string());
            } else if (acceptWords("LINES", "TERMINATED", "BY")) {
                parameters.put("line.delim", string());
            } else if (acceptWords("NULL", "DEFINED", "AS")) {
                parameters.put("serialization.null.format", string());
            } else {
                return;
            }
        }
    }

    /** Column definitions up to and including the closing parenthesis. */
    private List<Column> columns() {
        List<Column> columns = new ArrayList<>();
        do {
            Column column = new Column();
            column.setName(identifier());
            column.setType(type());
            if (acceptWords("COMMENT")) {
                column.setComment(string());
            }
            columns.add(column);
        } while (accept(","));
        expect(")");
        return columns;
    }

    /** A column type, written back lower-cased without spaces, as Glue stores it. */
    private String type() {
        StringBuilder type = new StringBuilder();
        int depth = 0;
        while (position < tokens.size()) {
            Token token = tokens.get(position);
            if (depth == 0 && (token.is(",") || token.is(")") || TYPE_TERMINATORS.contains(token.upper()))) {
                break;
            }
            if (token.is("<") || token.is("(")) {
                depth++;
            } else if (token.is(">") || token.is(")")) {
                depth--;
            }
            if (!type.isEmpty() && token.kind() == Kind.WORD && Character.isLetterOrDigit(type.charAt(type.length() - 1))) {
                type.append(' ');
            }
            type.append(token.kind() == Kind.WORD ? token.text().toLowerCase(Locale.ROOT) : token.text());
            position++;
        }
        if (type.isEmpty()) {
            throw invalid("A column is missing its type");
        }
        return type.toString();
    }

    private List<PartitionSpec> partitionSpecs(boolean withLocation) {
        List<PartitionSpec> specs = new ArrayList<>();
        do {
            expectWords("PARTITION");
            expect("(");
            Map<String, String> values = new LinkedHashMap<>();
            do {
                String key = identifier();
                expect("=");
                Token value = next();
                if (value.kind() != Kind.STRING && value.kind() != Kind.NUMBER) {
                    throw invalid("A partition value must be a literal, not '" + value.text() + "'");
                }
                values.put(key, value.text());
            } while (accept(","));
            expect(")");
            String location = withLocation && acceptWords("LOCATION") ? string() : null;
            specs.add(new PartitionSpec(values, location));
        } while (accept(",") || peekWords("PARTITION"));
        return specs;
    }

    private Map<String, String> properties() {
        expect("(");
        Map<String, String> properties = new LinkedHashMap<>();
        do {
            String key = string();
            expect("=");
            properties.put(key, string());
        } while (accept(","));
        expect(")");
        return properties;
    }

    private List<String> identifierList() {
        expect("(");
        List<String> names = new ArrayList<>();
        do {
            names.add(identifier());
        } while (accept(","));
        expect(")");
        return names;
    }

    /** {@code SORTED BY} columns as Glue stores them: 1 for ascending, the default, 0 for descending. */
    private List<StorageDescriptor.Order> sortColumns() {
        expect("(");
        List<StorageDescriptor.Order> orders = new ArrayList<>();
        do {
            StorageDescriptor.Order order = new StorageDescriptor.Order();
            order.setColumn(identifier());
            order.setSortOrder(acceptWords("DESC") ? 0 : 1);
            acceptWords("ASC");
            orders.add(order);
        } while (accept(","));
        expect(")");
        return orders;
    }

    private TableRef tableRef() {
        String first = identifier();
        if (accept(".")) {
            return new TableRef(first, identifier());
        }
        return new TableRef(null, first);
    }

    private String identifier() {
        Token token = next();
        if (token.kind() != Kind.WORD && token.kind() != Kind.QUOTED) {
            throw invalid("Expected a name but found '" + token.text() + "'");
        }
        return token.text().toLowerCase(Locale.ROOT);
    }

    private String string() {
        Token token = next();
        if (token.kind() != Kind.STRING) {
            throw invalid("Expected a string literal but found '" + token.text() + "'");
        }
        return token.text();
    }

    private Token next() {
        if (position >= tokens.size()) {
            throw invalid("The statement ends early");
        }
        return tokens.get(position++);
    }

    private boolean accept(String symbol) {
        if (position < tokens.size() && tokens.get(position).is(symbol)) {
            position++;
            return true;
        }
        return false;
    }

    private void expect(String symbol) {
        if (!accept(symbol)) {
            throw invalid("Expected '" + symbol + "'"
                    + (position < tokens.size() ? " but found '" + tokens.get(position).text() + "'" : " at the end"));
        }
    }

    private boolean peekWords(String... words) {
        if (position + words.length > tokens.size()) {
            return false;
        }
        for (int i = 0; i < words.length; i++) {
            if (!tokens.get(position + i).isWord(words[i])) {
                return false;
            }
        }
        return true;
    }

    private boolean acceptWords(String... words) {
        if (!peekWords(words)) {
            return false;
        }
        position += words.length;
        return true;
    }

    private void expectWords(String... words) {
        if (!acceptWords(words)) {
            throw invalid("Expected " + String.join(" ", words)
                    + (position < tokens.size() ? " but found '" + tokens.get(position).text() + "'" : " at the end"));
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }

    // ---- tokenizer ----

    private enum Kind { WORD, QUOTED, STRING, NUMBER, SYMBOL }

    private record Token(Kind kind, String text) {
        boolean is(String symbol) {
            return kind == Kind.SYMBOL && text.equals(symbol);
        }

        boolean isWord(String word) {
            return kind == Kind.WORD && text.equalsIgnoreCase(word);
        }

        String upper() {
            return kind == Kind.WORD ? text.toUpperCase(Locale.ROOT) : "";
        }
    }

    private static List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int length = sql == null ? 0 : sql.length();
        while (i < length) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (sql.startsWith("--", i)) {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? length : end + 1;
            } else if (sql.startsWith("/*", i)) {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < length && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_'
                        || sql.charAt(i) == '$')) {
                    i++;
                }
                tokens.add(new Token(Kind.WORD, sql.substring(start, i)));
            } else if (Character.isDigit(c)) {
                int start = i;
                while (i < length && (Character.isDigit(sql.charAt(i)) || sql.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new Token(Kind.NUMBER, sql.substring(start, i)));
            } else if (c == '`') {
                StringBuilder name = new StringBuilder();
                i++;
                while (i < length) {
                    if (sql.charAt(i) == '`') {
                        if (i + 1 < length && sql.charAt(i + 1) == '`') {
                            name.append('`');
                            i += 2;
                            continue;
                        }
                        break;
                    }
                    name.append(sql.charAt(i++));
                }
                i = Math.min(i + 1, length);
                tokens.add(new Token(Kind.QUOTED, name.toString()));
            } else if (c == '\'' || c == '"') {
                StringBuilder value = new StringBuilder();
                i = readString(sql, i, value);
                tokens.add(new Token(Kind.STRING, value.toString()));
            } else {
                // Any other character is a symbol of its own: tokenizing never fails, so a statement this
                // class does not run reaches the query engine with the engine's own error, not this one's.
                tokens.add(new Token(Kind.SYMBOL, String.valueOf(c)));
                i++;
            }
        }
        return tokens;
    }

    /** Reads the Hive string literal at {@code start} into {@code value}, returning the index after it. */
    private static int readString(String sql, int start, StringBuilder value) {
        char quote = sql.charAt(start);
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    value.append(quote);
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            if (c == '\\' && i + 1 < sql.length()) {
                i = readEscape(sql, i + 1, value);
                continue;
            }
            value.append(c);
            i++;
        }
        return i;
    }

    private static int readEscape(String sql, int i, StringBuilder value) {
        char c = sql.charAt(i);
        switch (c) {
            case 't' -> value.append('\t');
            case 'n' -> value.append('\n');
            case 'r' -> value.append('\r');
            case 'b' -> value.append('\b');
            case 'f' -> value.append('\f');
            case 'u' -> {
                if (i + 4 < sql.length()) {
                    value.append((char) Integer.parseInt(sql.substring(i + 1, i + 5), 16));
                    return i + 5;
                }
                value.append(c);
            }
            default -> {
                if (c >= '0' && c <= '7') {
                    int end = i;
                    while (end < sql.length() && end < i + 3 && sql.charAt(end) >= '0' && sql.charAt(end) <= '7') {
                        end++;
                    }
                    value.append((char) Integer.parseInt(sql.substring(i, end), 8));
                    return end;
                }
                value.append(c);
            }
        }
        return i + 1;
    }
}
