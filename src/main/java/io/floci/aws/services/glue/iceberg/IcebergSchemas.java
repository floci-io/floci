package io.floci.aws.services.glue.iceberg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.services.glue.model.Column;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Iceberg {@code CreateTableRequest} an Athena {@code CREATE TABLE ... TBLPROPERTIES ('table_type'='ICEBERG')}
 * describes: its columns' Hive types as an Iceberg schema, field ids assigned top-level first and then nested in
 * order, and its {@code PARTITIONED BY} entries as a partition spec of identity, {@code bucket}, {@code truncate},
 * {@code year}, {@code month}, {@code day} and {@code hour} transforms. As in Athena, {@code tinyint},
 * {@code smallint} and {@code char} columns are refused.
 */
public final class IcebergSchemas {

    /** The first partition field id Iceberg assigns. */
    private static final int FIRST_PARTITION_FIELD_ID = 1000;

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /** One {@code PARTITIONED BY} entry: a transform ({@code identity}, {@code bucket}, ...), its argument, a column. */
    public record PartitionField(String transform, Integer argument, String sourceColumn) {
    }

    private IcebergSchemas() {
    }

    public static ObjectNode createTableRequest(String name, List<Column> columns, List<PartitionField> partitioning,
                                                String location, Map<String, String> properties) {
        ObjectNode request = JSON.objectNode();
        request.put("name", name);
        if (location != null) {
            request.put("location", location);
        }
        ObjectNode schema = request.putObject("schema");
        schema.put("type", "struct");
        schema.put("schema-id", 0);
        ArrayNode fields = schema.putArray("fields");
        int[] nextId = {columns.size() + 1};
        for (int i = 0; i < columns.size(); i++) {
            Column column = columns.get(i);
            ObjectNode field = fields.addObject();
            field.put("id", i + 1);
            field.put("name", column.getName());
            field.put("required", false);
            field.set("type", icebergType(new TypeReader(column.getType()), nextId));
            if (column.getComment() != null) {
                field.put("doc", column.getComment());
            }
        }
        ObjectNode spec = request.putObject("partition-spec");
        spec.put("spec-id", 0);
        ArrayNode specFields = spec.putArray("fields");
        int partitionId = FIRST_PARTITION_FIELD_ID;
        for (PartitionField partition : partitioning) {
            JsonNode source = null;
            for (JsonNode field : fields) {
                if (field.path("name").asText().equalsIgnoreCase(partition.sourceColumn())) {
                    source = field;
                }
            }
            if (source == null) {
                throw new IllegalArgumentException("PARTITIONED BY names no column: " + partition.sourceColumn());
            }
            String transform = partition.transform().toLowerCase(Locale.ROOT);
            String sourceName = source.path("name").asText();
            ObjectNode specField = specFields.addObject();
            specField.put("name", switch (transform) {
                case "identity" -> sourceName;
                case "truncate" -> sourceName + "_trunc";
                default -> sourceName + "_" + transform;
            });
            specField.put("transform", partitionTransform(transform, partition.argument(), sourceName,
                    source.path("type")));
            specField.put("source-id", source.path("id").asInt());
            specField.put("field-id", partitionId++);
        }
        ObjectNode requestProperties = request.putObject("properties");
        requestProperties.put("format-version", String.valueOf(IcebergTableMetadata.DEFAULT_FORMAT_VERSION));
        properties.forEach(requestProperties::put);
        return request;
    }

    /**
     * The Iceberg transform a {@code PARTITIONED BY} entry names, refused when its width or its column's type is not
     * one Athena takes: {@code bucket(N, col)} and {@code truncate(L, col)} need a positive width, the others none;
     * {@code year}, {@code month} and {@code day} take a date or timestamp, {@code hour} a timestamp, {@code bucket}
     * an int, long, decimal, date, timestamp, string or binary, and {@code truncate} an int, long, decimal or string.
     */
    private static String partitionTransform(String transform, Integer width, String column, JsonNode type) {
        boolean widthed = transform.equals("bucket") || transform.equals("truncate");
        if (!Set.of("identity", "bucket", "truncate", "year", "month", "day", "hour").contains(transform)) {
            throw new IllegalArgumentException("Unsupported partition transform: " + transform);
        }
        if (widthed && (width == null || width <= 0)) {
            throw new IllegalArgumentException("Partition transform " + transform + " needs a positive width: "
                    + transform + "(N, " + column + ")");
        }
        if (!widthed && width != null) {
            throw new IllegalArgumentException("Partition transform " + transform + " takes no width: " + transform
                    + "(" + column + ")");
        }
        String typeName = type.isTextual() ? type.asText().replaceFirst("\\(.*", "") : type.path("type").asText();
        Set<String> accepted = switch (transform) {
            case "year", "month", "day" -> Set.of("date", "timestamp");
            case "hour" -> Set.of("timestamp");
            case "bucket" -> Set.of("int", "long", "decimal", "date", "timestamp", "string", "binary");
            case "truncate" -> Set.of("int", "long", "decimal", "string");
            default -> Set.of("boolean", "int", "long", "float", "double", "decimal", "date", "timestamp", "string",
                    "binary");
        };
        if (!accepted.contains(typeName)) {
            throw new IllegalArgumentException("Partition transform " + transform + " does not take column " + column
                    + " of type " + typeName);
        }
        return widthed ? transform + "[" + width + "]" : transform;
    }

    private static JsonNode icebergType(TypeReader reader, int[] nextId) {
        String word = reader.word().toLowerCase(Locale.ROOT);
        switch (word) {
            case "array" -> {
                reader.expect('<');
                ObjectNode list = JSON.objectNode();
                list.put("type", "list");
                list.put("element-id", nextId[0]++);
                list.set("element", icebergType(reader, nextId));
                list.put("element-required", false);
                reader.expect('>');
                return list;
            }
            case "map" -> {
                reader.expect('<');
                ObjectNode map = JSON.objectNode();
                map.put("type", "map");
                map.put("key-id", nextId[0]++);
                map.put("value-id", nextId[0]++);
                map.set("key", icebergType(reader, nextId));
                reader.expect(',');
                map.set("value", icebergType(reader, nextId));
                map.put("value-required", false);
                reader.expect('>');
                return map;
            }
            case "struct" -> {
                reader.expect('<');
                ObjectNode struct = JSON.objectNode();
                struct.put("type", "struct");
                ArrayNode fields = struct.putArray("fields");
                List<String> names = new ArrayList<>();
                List<TypeReader.Mark> types = new ArrayList<>();
                do {
                    names.add(reader.word());
                    reader.expect(':');
                    types.add(reader.mark());
                    reader.skipType();
                } while (reader.accept(','));
                reader.expect('>');
                // Fields of one struct take their ids before any type nested in them, as Iceberg assigns them.
                int first = nextId[0];
                nextId[0] += names.size();
                for (int i = 0; i < names.size(); i++) {
                    ObjectNode field = fields.addObject();
                    field.put("id", first + i);
                    field.put("name", names.get(i));
                    field.put("required", false);
                    field.set("type", icebergType(reader.at(types.get(i)), nextId));
                }
                return struct;
            }
            case "decimal" -> {
                reader.expect('(');
                String precision = reader.word();
                reader.expect(',');
                String scale = reader.word();
                reader.expect(')');
                return JSON.textNode("decimal(" + precision + ", " + scale + ")");
            }
            case "tinyint", "smallint", "char" ->
                    throw new IllegalArgumentException(word + " is not supported for Iceberg tables in Athena");
            case "varchar" -> {
                if (reader.accept('(')) {
                    reader.word();
                    reader.expect(')');
                }
                return JSON.textNode("string");
            }
            case "string" -> {
                return JSON.textNode("string");
            }
            case "int", "integer" -> {
                return JSON.textNode("int");
            }
            case "bigint" -> {
                return JSON.textNode("long");
            }
            case "float", "real" -> {
                return JSON.textNode("float");
            }
            case "double" -> {
                return JSON.textNode("double");
            }
            case "boolean" -> {
                return JSON.textNode("boolean");
            }
            case "date" -> {
                return JSON.textNode("date");
            }
            case "timestamp" -> {
                if (reader.accept('(')) {
                    reader.word();
                    reader.expect(')');
                }
                return JSON.textNode("timestamp");
            }
            case "binary", "varbinary" -> {
                return JSON.textNode("binary");
            }
            default -> throw new IllegalArgumentException("Unsupported Iceberg column type: " + word);
        }
    }

    /** Reads a Hive type string a token at a time. */
    private static final class TypeReader {

        private final String text;
        private int position;

        TypeReader(String text) {
            this.text = text;
        }

        record Mark(int position) {
        }

        Mark mark() {
            skipSpaces();
            return new Mark(position);
        }

        TypeReader at(Mark mark) {
            TypeReader reader = new TypeReader(text);
            reader.position = mark.position();
            return reader;
        }

        String word() {
            skipSpaces();
            int start = position;
            while (position < text.length()
                    && (Character.isLetterOrDigit(text.charAt(position)) || text.charAt(position) == '_')) {
                position++;
            }
            if (start == position) {
                throw new IllegalArgumentException("Cannot read type " + text + " at " + position);
            }
            return text.substring(start, position);
        }

        boolean accept(char c) {
            skipSpaces();
            if (position < text.length() && text.charAt(position) == c) {
                position++;
                return true;
            }
            return false;
        }

        void expect(char c) {
            if (!accept(c)) {
                throw new IllegalArgumentException("Cannot read type " + text + ": expected '" + c + "' at "
                        + position);
            }
        }

        /** Moves past one type, nested brackets included. */
        void skipType() {
            word();
            skipSpaces();
            if (position < text.length() && (text.charAt(position) == '<' || text.charAt(position) == '(')) {
                int depth = 0;
                do {
                    char c = text.charAt(position++);
                    if (c == '<' || c == '(') {
                        depth++;
                    } else if (c == '>' || c == ')') {
                        depth--;
                    }
                } while (depth > 0 && position < text.length());
            }
        }

        private void skipSpaces() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }
    }
}
