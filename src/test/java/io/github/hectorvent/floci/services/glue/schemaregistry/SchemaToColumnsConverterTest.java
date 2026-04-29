package io.github.hectorvent.floci.services.glue.schemaregistry;

import io.github.hectorvent.floci.services.glue.model.Column;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaToColumnsConverterTest {

    // ---- Avro ----

    @Test
    void avroPrimitives() {
        String def = "{\"type\":\"record\",\"name\":\"R\",\"fields\":["
                + "{\"name\":\"id\",\"type\":\"long\"},"
                + "{\"name\":\"name\",\"type\":\"string\"},"
                + "{\"name\":\"flag\",\"type\":\"boolean\"},"
                + "{\"name\":\"ratio\",\"type\":\"double\"},"
                + "{\"name\":\"count\",\"type\":\"int\"}"
                + "]}";

        List<Column> cols = SchemaToColumnsConverter.toColumns("AVRO", def);

        assertEquals(5, cols.size());
        assertEquals("bigint", cols.get(0).getType());
        assertEquals("string", cols.get(1).getType());
        assertEquals("boolean", cols.get(2).getType());
        assertEquals("double", cols.get(3).getType());
        assertEquals("int", cols.get(4).getType());
    }

    @Test
    void avroNullableUnionUnwrapsToInner() {
        String def = "{\"type\":\"record\",\"name\":\"R\",\"fields\":["
                + "{\"name\":\"email\",\"type\":[\"null\",\"string\"],\"default\":null}]}";

        List<Column> cols = SchemaToColumnsConverter.toColumns("AVRO", def);

        assertEquals("string", cols.get(0).getType());
    }

    @Test
    void avroNestedRecordBecomesStruct() {
        String def = "{\"type\":\"record\",\"name\":\"R\",\"fields\":["
                + "{\"name\":\"addr\",\"type\":{\"type\":\"record\",\"name\":\"Addr\",\"fields\":["
                + "{\"name\":\"city\",\"type\":\"string\"},"
                + "{\"name\":\"zip\",\"type\":\"int\"}]}}]}";

        List<Column> cols = SchemaToColumnsConverter.toColumns("AVRO", def);

        assertEquals("struct<city:string,zip:int>", cols.get(0).getType());
    }

    @Test
    void avroArrayBecomesArray() {
        String def = "{\"type\":\"record\",\"name\":\"R\",\"fields\":["
                + "{\"name\":\"tags\",\"type\":{\"type\":\"array\",\"items\":\"string\"}}]}";

        List<Column> cols = SchemaToColumnsConverter.toColumns("AVRO", def);

        assertEquals("array<string>", cols.get(0).getType());
    }

    @Test
    void avroMapBecomesMap() {
        String def = "{\"type\":\"record\",\"name\":\"R\",\"fields\":["
                + "{\"name\":\"meta\",\"type\":{\"type\":\"map\",\"values\":\"long\"}}]}";

        List<Column> cols = SchemaToColumnsConverter.toColumns("AVRO", def);

        assertEquals("map<string,bigint>", cols.get(0).getType());
    }

    @Test
    void avroEnumFallsBackToString() {
        String def = "{\"type\":\"record\",\"name\":\"R\",\"fields\":["
                + "{\"name\":\"status\",\"type\":{\"type\":\"enum\",\"name\":\"S\",\"symbols\":[\"A\",\"B\"]}}]}";

        List<Column> cols = SchemaToColumnsConverter.toColumns("AVRO", def);

        assertEquals("string", cols.get(0).getType());
    }

    @Test
    void avroBytesBecomesBinary() {
        String def = "{\"type\":\"record\",\"name\":\"R\",\"fields\":["
                + "{\"name\":\"blob\",\"type\":\"bytes\"}]}";

        assertEquals("binary",
                SchemaToColumnsConverter.toColumns("AVRO", def).get(0).getType());
    }

    @Test
    void avroLogicalTypesMapToHiveSemanticTypes() {
        String def = "{\"type\":\"record\",\"name\":\"R\",\"fields\":["
                + "{\"name\":\"created\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}},"
                + "{\"name\":\"created_micros\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-micros\"}},"
                + "{\"name\":\"local_created\",\"type\":{\"type\":\"long\",\"logicalType\":\"local-timestamp-millis\"}},"
                + "{\"name\":\"birth\",\"type\":{\"type\":\"int\",\"logicalType\":\"date\"}},"
                + "{\"name\":\"start\",\"type\":{\"type\":\"int\",\"logicalType\":\"time-millis\"}},"
                + "{\"name\":\"id\",\"type\":{\"type\":\"string\",\"logicalType\":\"uuid\"}},"
                + "{\"name\":\"price\",\"type\":{\"type\":\"bytes\",\"logicalType\":\"decimal\","
                + "\"precision\":12,\"scale\":4}}"
                + "]}";

        List<Column> cols = SchemaToColumnsConverter.toColumns("AVRO", def);

        Map<String, String> byName = cols.stream()
                .collect(java.util.stream.Collectors.toMap(Column::getName, Column::getType));
        assertEquals("timestamp", byName.get("created"));
        assertEquals("timestamp", byName.get("created_micros"));
        assertEquals("timestamp", byName.get("local_created"));
        assertEquals("date", byName.get("birth"));
        assertEquals("string", byName.get("start"));
        assertEquals("string", byName.get("id"));
        assertEquals("decimal(12,4)", byName.get("price"));
    }

    @Test
    void avroNullableLogicalTypeStillUnwrapsToHiveType() {
        String def = "{\"type\":\"record\",\"name\":\"R\",\"fields\":["
                + "{\"name\":\"created\",\"type\":[\"null\","
                + "{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}],\"default\":null}]}";

        List<Column> cols = SchemaToColumnsConverter.toColumns("AVRO", def);

        assertEquals("timestamp", cols.get(0).getType());
    }

    @Test
    void avroUnknownLogicalTypeFallsBackToBaseType() {
        // duration is a fixed(12) logical type with no Hive equivalent; falls through to base "binary".
        String def = "{\"type\":\"record\",\"name\":\"R\",\"fields\":["
                + "{\"name\":\"d\",\"type\":{\"type\":\"fixed\",\"name\":\"D\",\"size\":12,"
                + "\"logicalType\":\"duration\"}}]}";

        List<Column> cols = SchemaToColumnsConverter.toColumns("AVRO", def);

        assertEquals("binary", cols.get(0).getType());
    }

    @Test
    void avroNonRecordRootReturnsEmpty() {
        String def = "\"string\"";
        assertTrue(SchemaToColumnsConverter.toColumns("AVRO", def).isEmpty());
    }

    @Test
    void avroMalformedReturnsEmpty() {
        assertTrue(SchemaToColumnsConverter.toColumns("AVRO", "{garbage").isEmpty());
    }

    // ---- JSON Schema ----

    @Test
    void jsonObjectWithMixedProperties() {
        String def = "{\"type\":\"object\",\"properties\":{"
                + "\"id\":{\"type\":\"integer\"},"
                + "\"name\":{\"type\":\"string\"},"
                + "\"flag\":{\"type\":\"boolean\"},"
                + "\"ratio\":{\"type\":\"number\"}"
                + "}}";

        List<Column> cols = SchemaToColumnsConverter.toColumns("JSON", def);

        Map<String, String> byName = cols.stream()
                .collect(java.util.stream.Collectors.toMap(Column::getName, Column::getType));
        assertEquals("bigint", byName.get("id"));
        assertEquals("string", byName.get("name"));
        assertEquals("boolean", byName.get("flag"));
        assertEquals("double", byName.get("ratio"));
    }

    @Test
    void jsonNestedObjectBecomesStruct() {
        String def = "{\"type\":\"object\",\"properties\":{"
                + "\"addr\":{\"type\":\"object\",\"properties\":{"
                + "\"city\":{\"type\":\"string\"},\"zip\":{\"type\":\"integer\"}}}}}";

        List<Column> cols = SchemaToColumnsConverter.toColumns("JSON", def);

        assertEquals("struct<city:string,zip:bigint>", cols.get(0).getType());
    }

    @Test
    void jsonArrayBecomesArray() {
        String def = "{\"type\":\"object\",\"properties\":{"
                + "\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}";

        List<Column> cols = SchemaToColumnsConverter.toColumns("JSON", def);

        assertEquals("array<string>", cols.get(0).getType());
    }

    @Test
    void jsonNonObjectRootReturnsEmpty() {
        String def = "{\"type\":\"string\"}";
        assertTrue(SchemaToColumnsConverter.toColumns("JSON", def).isEmpty());
    }

    @Test
    void jsonMalformedReturnsEmpty() {
        assertTrue(SchemaToColumnsConverter.toColumns("JSON", "{not-json").isEmpty());
    }

    // ---- Protobuf ----

    @Test
    void protobufScalarFields() {
        String def = "syntax = \"proto3\";\n"
                + "message User {\n"
                + "  int64 id = 1;\n"
                + "  string name = 2;\n"
                + "  bool active = 3;\n"
                + "}";

        List<Column> cols = SchemaToColumnsConverter.toColumns("PROTOBUF", def);

        java.util.Map<String, String> byName = cols.stream()
                .collect(java.util.stream.Collectors.toMap(Column::getName, Column::getType));
        assertEquals(3, cols.size(), () -> "got cols: " + cols.stream().map(c -> c.getName() + ":" + c.getType()).toList());
        assertEquals("bigint", byName.get("id"));
        assertEquals("string", byName.get("name"));
        assertEquals("boolean", byName.get("active"));
    }

    @Test
    void protobufRepeatedBecomesArray() {
        String def = "syntax = \"proto3\";\n"
                + "message User {\n"
                + "  repeated string tags = 1;\n"
                + "}";

        List<Column> cols = SchemaToColumnsConverter.toColumns("PROTOBUF", def);

        assertEquals("array<string>", cols.get(0).getType());
    }

    @Test
    void protobufNestedMessageBecomesStruct() {
        String def = "syntax = \"proto3\";\n"
                + "message User {\n"
                + "  message Addr { string city = 1; int32 zip = 2; }\n"
                + "  Addr addr = 1;\n"
                + "}";

        List<Column> cols = SchemaToColumnsConverter.toColumns("PROTOBUF", def);

        assertEquals("struct<city:string,zip:int>", cols.get(0).getType());
    }

    @Test
    void protobufMalformedReturnsEmpty() {
        assertTrue(SchemaToColumnsConverter.toColumns("PROTOBUF", "not a proto file").isEmpty());
    }

    // ---- Generic ----

    @Test
    void unknownDataFormatReturnsEmpty() {
        assertTrue(SchemaToColumnsConverter.toColumns("BOGUS", "anything").isEmpty());
    }

    @Test
    void nullDefinitionReturnsEmpty() {
        assertTrue(SchemaToColumnsConverter.toColumns("AVRO", null).isEmpty());
    }

    @Test
    void blankDefinitionReturnsEmpty() {
        assertTrue(SchemaToColumnsConverter.toColumns("AVRO", "  ").isEmpty());
    }
}
