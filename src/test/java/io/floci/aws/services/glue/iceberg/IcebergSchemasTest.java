package io.floci.aws.services.glue.iceberg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.glue.model.Column;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Iceberg {@code CreateTableRequest} an Athena Iceberg {@code CREATE TABLE} describes. */
class IcebergSchemasTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static Column column(String name, String type) {
        return new Column(name, type);
    }

    @Test
    void hiveTypesBecomeIcebergTypesWithTopLevelIdsFirstThenNestedOnesInOrder() throws Exception {
        Column id = column("id", "bigint");
        id.setComment("the id");
        JsonNode request = IcebergSchemas.createTableRequest("events", List.of(id,
                column("tags", "array<string>"),
                column("attrs", "map<string,int>"),
                column("info", "struct<a:int,b:struct<c:varchar(10)>>"),
                column("price", "decimal(10,2)"),
                column("ts", "timestamp"),
                column("flag", "boolean")), List.of(), "s3://lake/events", Map.of("format-version", "2"));

        assertEquals(mapper.readTree("""
                {"type":"struct","schema-id":0,"fields":[
                 {"id":1,"name":"id","required":false,"type":"long","doc":"the id"},
                 {"id":2,"name":"tags","required":false,"type":{"type":"list","element-id":8,"element":"string",
                   "element-required":false}},
                 {"id":3,"name":"attrs","required":false,"type":{"type":"map","key-id":9,"value-id":10,
                   "key":"string","value":"int","value-required":false}},
                 {"id":4,"name":"info","required":false,"type":{"type":"struct","fields":[
                   {"id":11,"name":"a","required":false,"type":"int"},
                   {"id":12,"name":"b","required":false,"type":{"type":"struct","fields":[
                     {"id":13,"name":"c","required":false,"type":"string"}]}}]}},
                 {"id":5,"name":"price","required":false,"type":"decimal(10, 2)"},
                 {"id":6,"name":"ts","required":false,"type":"timestamp"},
                 {"id":7,"name":"flag","required":false,"type":"boolean"}]}
                """), request.path("schema"));
        assertEquals("events", request.path("name").asText());
        assertEquals("s3://lake/events", request.path("location").asText());
        assertEquals("2", request.path("properties").path("format-version").asText());
    }

    @Test
    void partitionedByEntriesBecomeASpecOfTransformsNamedAsIcebergNamesThem() throws Exception {
        JsonNode request = IcebergSchemas.createTableRequest("events", List.of(column("id", "bigint"),
                column("category", "string"), column("ts", "timestamp"), column("name", "string")), List.of(
                new IcebergSchemas.PartitionField("identity", null, "category"),
                new IcebergSchemas.PartitionField("bucket", 16, "id"),
                new IcebergSchemas.PartitionField("day", null, "ts"),
                new IcebergSchemas.PartitionField("truncate", 4, "NAME")), null, Map.of());

        assertEquals(mapper.readTree("""
                {"spec-id":0,"fields":[
                 {"name":"category","transform":"identity","source-id":2,"field-id":1000},
                 {"name":"id_bucket","transform":"bucket[16]","source-id":1,"field-id":1001},
                 {"name":"ts_day","transform":"day","source-id":3,"field-id":1002},
                 {"name":"name_trunc","transform":"truncate[4]","source-id":4,"field-id":1003}]}
                """), request.path("partition-spec"));
        assertTrue(request.path("location").isMissingNode());
    }

    @Test
    void typesAthenaDoesNotSupportOnIcebergTablesAreRefused() {
        for (String type : List.of("tinyint", "smallint", "char(3)")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> IcebergSchemas.createTableRequest("t", List.of(column("c", type)), List.of(), null,
                            Map.of()));
            assertTrue(e.getMessage().contains("not supported for Iceberg tables"), e.getMessage());
        }
    }

    @Test
    void aPartitionTransformWithoutItsWidthOrOnAColumnTypeItDoesNotTakeIsRefused() {
        List<Column> columns = List.of(column("id", "bigint"), column("name", "string"), column("ts", "timestamp"),
                column("dt", "date"));
        for (IcebergSchemas.PartitionField partition : List.of(
                new IcebergSchemas.PartitionField("bucket", null, "id"),
                new IcebergSchemas.PartitionField("bucket", 0, "id"),
                new IcebergSchemas.PartitionField("truncate", -1, "name"),
                new IcebergSchemas.PartitionField("day", 4, "ts"),
                new IcebergSchemas.PartitionField("identity", 2, "id"),
                new IcebergSchemas.PartitionField("day", null, "name"),
                new IcebergSchemas.PartitionField("hour", null, "dt"),
                new IcebergSchemas.PartitionField("truncate", 4, "ts"))) {
            assertThrows(IllegalArgumentException.class, () -> IcebergSchemas.createTableRequest("t", columns,
                    List.of(partition), null, Map.of()), partition.toString());
        }
        JsonNode spec = IcebergSchemas.createTableRequest("t", columns, List.of(
                new IcebergSchemas.PartitionField("month", null, "dt"),
                new IcebergSchemas.PartitionField("bucket", 8, "name")), null, Map.of()).path("partition-spec");
        assertEquals("month", spec.path("fields").get(0).path("transform").asText());
        assertEquals("bucket[8]", spec.path("fields").get(1).path("transform").asText());
    }

    @Test
    void aPartitionOfAnUnknownColumnOrTransformIsRefused() {
        List<Column> columns = List.of(column("id", "bigint"));

        assertThrows(IllegalArgumentException.class, () -> IcebergSchemas.createTableRequest("t", columns,
                List.of(new IcebergSchemas.PartitionField("identity", null, "missing")), null, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> IcebergSchemas.createTableRequest("t", columns,
                List.of(new IcebergSchemas.PartitionField("week", null, "id")), null, Map.of()));
    }
}
