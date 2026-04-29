package io.github.hectorvent.floci.services.glue.schemaregistry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.squareup.wire.schema.Field;
import com.squareup.wire.schema.Location;
import com.squareup.wire.schema.internal.parser.FieldElement;
import com.squareup.wire.schema.internal.parser.MessageElement;
import com.squareup.wire.schema.internal.parser.ProtoFileElement;
import com.squareup.wire.schema.internal.parser.ProtoParser;
import com.squareup.wire.schema.internal.parser.TypeElement;
import io.github.hectorvent.floci.services.glue.model.Column;
import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Converts a registered schema definition (Avro / JSON Schema / Protobuf source) into a list
 * of Glue Catalog {@link Column} objects with Hive-style type strings.
 *
 * <p>Top-level fields only — no flattening of nested records. Unknown / unsupported types
 * fall back to {@code string}. Parse failures log a warning and return an empty list (the
 * Catalog read should still succeed).
 */
public final class SchemaToColumnsConverter {

    private static final Logger LOG = Logger.getLogger(SchemaToColumnsConverter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SchemaToColumnsConverter() {}

    public static List<Column> toColumns(String dataFormat, String definition) {
        if (dataFormat == null || definition == null || definition.isBlank()) {
            return List.of();
        }
        try {
            return switch (dataFormat) {
                case "AVRO" -> avroColumns(definition);
                case "JSON" -> jsonColumns(definition);
                case "PROTOBUF" -> protobufColumns(definition);
                default -> List.of();
            };
        } catch (RuntimeException e) {
            LOG.warnv("Failed to convert {0} schema to columns: {1}", dataFormat, e.getMessage());
            return List.of();
        }
    }

    // ---- Avro -----------------------------------------------------------

    private static List<Column> avroColumns(String definition) {
        Schema schema = new Schema.Parser().parse(definition);
        if (schema.getType() != Schema.Type.RECORD) {
            return List.of();
        }
        List<Column> out = new ArrayList<>(schema.getFields().size());
        for (Schema.Field field : schema.getFields()) {
            out.add(new Column(field.name(), avroToHive(field.schema())));
        }
        return out;
    }

    private static String avroToHive(Schema s) {
        String logical = avroLogicalTypeToHive(s);
        if (logical != null) {
            return logical;
        }
        return switch (s.getType()) {
            case LONG -> "bigint";
            case INT -> "int";
            case STRING, ENUM -> "string";
            case BOOLEAN -> "boolean";
            case DOUBLE -> "double";
            case FLOAT -> "float";
            case BYTES, FIXED -> "binary";
            case NULL -> "string";
            case UNION -> avroUnionToHive(s);
            case RECORD -> avroRecordToHive(s);
            case ARRAY -> "array<" + avroToHive(s.getElementType()) + ">";
            case MAP -> "map<string," + avroToHive(s.getValueType()) + ">";
        };
    }

    private static String avroLogicalTypeToHive(Schema s) {
        LogicalType logical = s.getLogicalType();
        if (logical == null) {
            return null;
        }
        if (logical instanceof LogicalTypes.Decimal d) {
            return "decimal(" + d.getPrecision() + "," + d.getScale() + ")";
        }
        return switch (logical.getName()) {
            case "date" -> "date";
            case "timestamp-millis", "timestamp-micros",
                 "local-timestamp-millis", "local-timestamp-micros" -> "timestamp";
            // Hive has no TIME type — surface as string so downstream readers see the raw value.
            case "time-millis", "time-micros", "uuid" -> "string";
            default -> null;
        };
    }

    private static String avroUnionToHive(Schema s) {
        List<Schema> types = s.getTypes();
        if (types.size() == 2) {
            if (types.get(0).getType() == Schema.Type.NULL) return avroToHive(types.get(1));
            if (types.get(1).getType() == Schema.Type.NULL) return avroToHive(types.get(0));
        }
        return "string";
    }

    private static String avroRecordToHive(Schema s) {
        StringBuilder sb = new StringBuilder("struct<");
        boolean first = true;
        for (Schema.Field f : s.getFields()) {
            if (!first) sb.append(",");
            sb.append(f.name()).append(":").append(avroToHive(f.schema()));
            first = false;
        }
        return sb.append(">").toString();
    }

    // ---- JSON Schema ----------------------------------------------------

    private static List<Column> jsonColumns(String definition) {
        JsonNode root;
        try {
            root = MAPPER.readTree(definition);
        } catch (Exception e) {
            LOG.warnv("Invalid JSON Schema: {0}", e.getMessage());
            return List.of();
        }
        if (!"object".equals(root.path("type").asText())) {
            return List.of();
        }
        JsonNode properties = root.get("properties");
        if (properties == null || !properties.isObject()) {
            return List.of();
        }
        List<Column> out = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = properties.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            out.add(new Column(e.getKey(), jsonToHive(e.getValue())));
        }
        return out;
    }

    private static String jsonToHive(JsonNode node) {
        String t = node.path("type").asText("string");
        return switch (t) {
            case "string" -> "string";
            case "integer" -> "bigint";
            case "number" -> "double";
            case "boolean" -> "boolean";
            case "array" -> {
                JsonNode items = node.get("items");
                yield "array<" + (items != null && items.isObject() ? jsonToHive(items) : "string") + ">";
            }
            case "object" -> jsonObjectToHive(node);
            default -> "string";
        };
    }

    private static String jsonObjectToHive(JsonNode node) {
        JsonNode properties = node.get("properties");
        if (properties == null || !properties.isObject() || !properties.fields().hasNext()) {
            return "struct<>";
        }
        StringBuilder sb = new StringBuilder("struct<");
        boolean first = true;
        for (Iterator<Map.Entry<String, JsonNode>> it = properties.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!first) sb.append(",");
            sb.append(e.getKey()).append(":").append(jsonToHive(e.getValue()));
            first = false;
        }
        return sb.append(">").toString();
    }

    // ---- Protobuf (parsed via Wire — bypasses Apicurio's well-known-deps issue) -------

    private static List<Column> protobufColumns(String definition) {
        ProtoFileElement file;
        try {
            Location loc = Location.get("schema.proto");
            file = new ProtoParser(loc, definition.toCharArray()).readProtoFile();
        } catch (Exception e) {
            LOG.warnv("Invalid Protobuf schema: {0}", e.getMessage());
            return List.of();
        }
        MessageElement target = firstMessage(file.getTypes());
        if (target == null) {
            return List.of();
        }
        Map<String, MessageElement> nestedByName = collectMessages(file.getTypes(), new HashMap<>());
        List<Column> out = new ArrayList<>(target.getFields().size());
        for (FieldElement f : target.getFields()) {
            out.add(new Column(f.getName(), protobufToHive(f, nestedByName)));
        }
        return out;
    }

    private static MessageElement firstMessage(List<TypeElement> types) {
        for (TypeElement t : types) {
            if (t instanceof MessageElement m) return m;
        }
        return null;
    }

    private static Map<String, MessageElement> collectMessages(List<TypeElement> types, Map<String, MessageElement> acc) {
        for (TypeElement t : types) {
            if (t instanceof MessageElement m) {
                acc.put(m.getName(), m);
                collectMessages(m.getNestedTypes(), acc);
            }
        }
        return acc;
    }

    private static String protobufToHive(FieldElement field, Map<String, MessageElement> messages) {
        String base = protobufBaseTypeToHive(field.getType(), messages);
        return field.getLabel() == Field.Label.REPEATED ? "array<" + base + ">" : base;
    }

    private static String protobufBaseTypeToHive(String type, Map<String, MessageElement> messages) {
        if (type.startsWith("map<") && type.endsWith(">")) {
            // Wire preserves the literal "map<key, value>" syntax in field types.
            String inner = type.substring(4, type.length() - 1);
            int comma = inner.indexOf(',');
            if (comma > 0) {
                String valueType = inner.substring(comma + 1).trim();
                return "map<string," + protobufBaseTypeToHive(valueType, messages) + ">";
            }
            return "map<string,string>";
        }
        return switch (type) {
            case "int32", "uint32", "sint32", "fixed32", "sfixed32" -> "int";
            case "int64", "uint64", "sint64", "fixed64", "sfixed64" -> "bigint";
            case "float" -> "float";
            case "double" -> "double";
            case "bool" -> "boolean";
            case "string" -> "string";
            case "bytes" -> "binary";
            default -> {
                MessageElement nested = messages.get(type);
                yield nested != null ? protobufMessageToHive(nested, messages) : "string";
            }
        };
    }

    private static String protobufMessageToHive(MessageElement msg, Map<String, MessageElement> messages) {
        StringBuilder sb = new StringBuilder("struct<");
        boolean first = true;
        for (FieldElement f : msg.getFields()) {
            if (!first) sb.append(",");
            sb.append(f.getName()).append(":").append(protobufToHive(f, messages));
            first = false;
        }
        return sb.append(">").toString();
    }
}
