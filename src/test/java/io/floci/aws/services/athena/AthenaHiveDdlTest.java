package io.floci.aws.services.athena;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.glue.iceberg.IcebergSchemas;
import io.floci.aws.services.glue.model.Column;
import io.floci.aws.services.glue.model.StorageDescriptor;
import io.floci.aws.services.glue.model.Table;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AthenaHiveDdlTest {

    private static AthenaHiveDdl.CreateTable create(String sql) {
        return assertInstanceOf(AthenaHiveDdl.CreateTable.class, AthenaHiveDdl.parse(sql));
    }

    @Test
    void createExternalTableWithDelimitedTextBecomesTheGlueTableHiveStores() {
        AthenaHiveDdl.CreateTable create = create("""
                -- a headerless TSV table
                CREATE EXTERNAL TABLE IF NOT EXISTS Lake.`Events` (
                  `Id` BIGINT COMMENT 'the id',
                  payload STRUCT<a:INT, b:ARRAY<STRING>>,
                  amount DECIMAL(10, 2)
                )
                COMMENT 'raw events'
                PARTITIONED BY (dt STRING, region STRING)
                ROW FORMAT DELIMITED FIELDS TERMINATED BY '\\t' ESCAPED BY '\\\\' LINES TERMINATED BY '\\n'
                  NULL DEFINED AS ''
                STORED AS TEXTFILE
                LOCATION 's3://lake/events/'
                TBLPROPERTIES ('classification'='csv', 'skip.header.line.count'='0');
                """);

        assertEquals(new AthenaHiveDdl.TableRef("lake", "events"), create.ref());
        assertTrue(create.ifNotExists());
        Table table = create.table();
        assertEquals("events", table.getName());
        assertEquals("EXTERNAL_TABLE", table.getTableType());
        assertEquals("raw events", table.getDescription());
        assertEquals(Map.of("classification", "csv", "skip.header.line.count", "0", "EXTERNAL", "TRUE"),
                table.getParameters());
        StorageDescriptor descriptor = table.getStorageDescriptor();
        assertEquals(List.of("id:bigint:the id", "payload:struct<a:int,b:array<string>>:null", "amount:decimal(10,2):null"),
                descriptor.getColumns().stream().map(c -> c.getName() + ":" + c.getType() + ":" + c.getComment()).toList());
        assertEquals(List.of("dt", "region"), table.getPartitionKeys().stream().map(Column::getName).toList());
        assertEquals("s3://lake/events/", descriptor.getLocation());
        assertEquals("org.apache.hadoop.mapred.TextInputFormat", descriptor.getInputFormat());
        assertEquals("org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat", descriptor.getOutputFormat());
        assertEquals("org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe",
                descriptor.getSerdeInfo().getSerializationLibrary());
        assertEquals(Map.of("field.delim", "\t", "serialization.format", "\t", "escape.delim", "\\",
                "line.delim", "\n", "serialization.null.format", ""), descriptor.getSerdeInfo().getParameters());
    }

    @Test
    void clusteredAndSortedByKeepTheirColumnsAndDirections() {
        StorageDescriptor descriptor = create("""
                CREATE EXTERNAL TABLE t (a int, b string) CLUSTERED BY (a) SORTED BY (a DESC, b) INTO 4 BUCKETS
                STORED AS PARQUET LOCATION 's3://b/t/'
                """).table().getStorageDescriptor();

        assertEquals(List.of("a"), descriptor.getBucketColumns());
        assertEquals(4, descriptor.getNumberOfBuckets());
        assertEquals(List.of("a:0", "b:1"),
                descriptor.getSortColumns().stream().map(o -> o.getColumn() + ":" + o.getSortOrder()).toList());
    }

    @Test
    void storedAsPicksTheFormatsInputOutputAndSerde() {
        StorageDescriptor parquet = create("CREATE EXTERNAL TABLE t (a int) STORED AS PARQUET LOCATION 's3://b/t/'")
                .table().getStorageDescriptor();

        assertEquals("org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat", parquet.getInputFormat());
        assertEquals("org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe",
                parquet.getSerdeInfo().getSerializationLibrary());
        assertEquals(Map.of(), parquet.getSerdeInfo().getParameters());
    }

    @Test
    void anExplicitSerdeAndInputFormatAreKeptAsWritten() {
        StorageDescriptor json = create("""
                CREATE EXTERNAL TABLE t (a int)
                ROW FORMAT SERDE 'org.openx.data.jsonserde.JsonSerDe' WITH SERDEPROPERTIES ("ignore.malformed.json"="true")
                STORED AS INPUTFORMAT 'org.apache.hadoop.mapred.TextInputFormat'
                  OUTPUTFORMAT 'org.apache.hadoop.hive.ql.io.IgnoreKeyTextOutputFormat'
                LOCATION 's3://b/t/'
                """).table().getStorageDescriptor();

        assertEquals("org.openx.data.jsonserde.JsonSerDe", json.getSerdeInfo().getSerializationLibrary());
        assertEquals(Map.of("ignore.malformed.json", "true"), json.getSerdeInfo().getParameters());
        assertEquals("org.apache.hadoop.hive.ql.io.IgnoreKeyTextOutputFormat", json.getOutputFormat());
    }

    @Test
    void withoutRowFormatOrStoredAsATableIsTextWithTheDefaultSerde() {
        StorageDescriptor text = create("CREATE EXTERNAL TABLE t (a int) LOCATION 's3://b/t/'").table().getStorageDescriptor();

        assertEquals("org.apache.hadoop.mapred.TextInputFormat", text.getInputFormat());
        assertEquals(Map.of("serialization.format", "1"), text.getSerdeInfo().getParameters());
    }

    @Test
    void createTableWithoutExternalIsIcebergOrLeftToCtasOrRefused() {
        assertInstanceOf(AthenaHiveDdl.CreateIcebergTable.class, AthenaHiveDdl.parse(
                "CREATE TABLE t (a int) LOCATION 's3://b/t/' TBLPROPERTIES ('table_type'='ICEBERG')"));
        assertNull(AthenaHiveDdl.parse("CREATE TABLE t WITH (format = 'PARQUET') AS SELECT 1 AS a"));
        assertNull(AthenaHiveDdl.parse("CREATE TABLE t WITH (table_type = 'ICEBERG') AS SELECT 1 AS a"));
        assertNull(AthenaHiveDdl.parse("CREATE TABLE t AS SELECT 1 AS a"));
        AwsException hive = assertThrows(AwsException.class,
                () -> AthenaHiveDdl.parse("CREATE TABLE t (a int) STORED AS PARQUET LOCATION 's3://b/t/'"));
        assertTrue(hive.getMessage().contains("EXTERNAL"), hive.getMessage());
    }

    @Test
    void anIcebergCreateTableKeepsItsColumnsPartitionTransformsLocationAndProperties() {
        AthenaHiveDdl.CreateIcebergTable create = assertInstanceOf(AthenaHiveDdl.CreateIcebergTable.class,
                AthenaHiveDdl.parse("""
                        CREATE TABLE IF NOT EXISTS Lake.events (
                          id bigint COMMENT 'the id',
                          category string,
                          ts timestamp,
                          name string)
                        PARTITIONED BY (category, bucket(16, id), day(ts), truncate(4, name))
                        LOCATION 's3://lake/events/'
                        TBLPROPERTIES ('table_type'='ICEBERG', 'format'='parquet', 'vacuum_min_snapshots_to_keep'='3');
                        """));

        assertTrue(create.ifNotExists());
        assertEquals(new AthenaHiveDdl.TableRef("lake", "events"), create.ref());
        assertEquals(List.of("id:bigint", "category:string", "ts:timestamp", "name:string"), create.columns().stream()
                .map(column -> column.getName() + ":" + column.getType()).toList());
        assertEquals("the id", create.columns().getFirst().getComment());
        assertEquals(List.of(new IcebergSchemas.PartitionField("identity", null, "category"),
                new IcebergSchemas.PartitionField("bucket", 16, "id"),
                new IcebergSchemas.PartitionField("day", null, "ts"),
                new IcebergSchemas.PartitionField("truncate", 4, "name")), create.partitioning());
        assertEquals("s3://lake/events/", create.location());
        assertEquals(Map.of("format", "parquet", "vacuum_min_snapshots_to_keep", "3"), create.properties());
    }

    @Test
    void aMalformedIcebergPartitionTransformSaysWhatIsWrong() {
        AwsException e = assertThrows(AwsException.class, () -> AthenaHiveDdl.parse(
                "CREATE TABLE t (id int) PARTITIONED BY (bucket(id, 16)) TBLPROPERTIES ('table_type'='ICEBERG')"));

        assertEquals("InvalidRequestException", e.getErrorCode());
    }

    @Test
    void vacuumNamesItsTable() {
        assertEquals(new AthenaHiveDdl.TableRef("lake", "events"), assertInstanceOf(AthenaHiveDdl.Vacuum.class,
                AthenaHiveDdl.parse("VACUUM lake.events;")).ref());
        assertEquals(new AthenaHiveDdl.TableRef(null, "_events"), assertInstanceOf(AthenaHiveDdl.Vacuum.class,
                AthenaHiveDdl.parse("vacuum `_events`")).ref());
    }

    @Test
    void optimizeNamesItsTableWhateverItsClauses() {
        assertEquals(new AthenaHiveDdl.TableRef("lake", "events"), assertInstanceOf(AthenaHiveDdl.Optimize.class,
                AthenaHiveDdl.parse("OPTIMIZE lake.events REWRITE DATA USING BIN_PACK WHERE id > 5")).ref());
        assertEquals(new AthenaHiveDdl.TableRef(null, "events"), assertInstanceOf(AthenaHiveDdl.Optimize.class,
                AthenaHiveDdl.parse("optimize events rewrite data using bin_pack;")).ref());
    }

    @Test
    void dropRepairAndShowNameTheirTable() {
        AthenaHiveDdl.DropTable drop = assertInstanceOf(AthenaHiveDdl.DropTable.class,
                AthenaHiveDdl.parse("DROP TABLE IF EXISTS lake.events;"));
        assertTrue(drop.ifExists());
        assertEquals(new AthenaHiveDdl.TableRef("lake", "events"), drop.ref());
        assertFalse(assertInstanceOf(AthenaHiveDdl.DropTable.class, AthenaHiveDdl.parse("drop table t")).ifExists());
        assertEquals(new AthenaHiveDdl.TableRef(null, "events"),
                AthenaHiveDdl.parse("MSCK REPAIR TABLE `Events`").ref());
        assertInstanceOf(AthenaHiveDdl.ShowPartitions.class, AthenaHiveDdl.parse("SHOW PARTITIONS lake.events"));
    }

    @Test
    void alterTableAddAndDropPartitionsKeepEachSpecAndLocation() {
        AthenaHiveDdl.AddPartitions add = assertInstanceOf(AthenaHiveDdl.AddPartitions.class, AthenaHiveDdl.parse("""
                ALTER TABLE events ADD IF NOT EXISTS
                  PARTITION (dt = '2026-11-14', region = 'eu')
                  PARTITION (dt = '2026-11-15', region = 'us') LOCATION 's3://lake/custom/'
                """));
        assertTrue(add.ifNotExists());
        assertEquals(List.of(new AthenaHiveDdl.PartitionSpec(Map.of("dt", "2026-11-14", "region", "eu"), null),
                new AthenaHiveDdl.PartitionSpec(Map.of("dt", "2026-11-15", "region", "us"), "s3://lake/custom/")),
                add.partitions());

        AthenaHiveDdl.DropPartitions drop = assertInstanceOf(AthenaHiveDdl.DropPartitions.class,
                AthenaHiveDdl.parse("ALTER TABLE events DROP PARTITION (year = 2026), PARTITION (year = 2025)"));
        assertFalse(drop.ifExists());
        assertEquals(List.of(Map.of("year", "2026"), Map.of("year", "2025")),
                drop.partitions().stream().map(AthenaHiveDdl.PartitionSpec::values).toList());
    }

    @Test
    void otherAlterTableStatementsAreRefusedClearly() {
        AwsException e = assertThrows(AwsException.class,
                () -> AthenaHiveDdl.parse("ALTER TABLE events ADD COLUMNS (b string)"));

        assertTrue(e.getMessage().contains("ADD PARTITION"), e.getMessage());
    }

    @Test
    void queriesAndOtherStatementsAreNotHiveDdl() {
        assertNull(AthenaHiveDdl.parse("SELECT a || b, 'x' FROM t WHERE c ~ 'y'"));
        assertNull(AthenaHiveDdl.parse("SHOW TABLES IN lake"));
        assertNull(AthenaHiveDdl.parse("CREATE DATABASE lake"));
        assertNull(AthenaHiveDdl.parse("DROP DATABASE lake"));
        assertNull(AthenaHiveDdl.parse("INSERT INTO t VALUES (1)"));
        assertNull(AthenaHiveDdl.parse(""));
    }

    @Test
    void aMalformedHiveStatementSaysWhatIsWrong() {
        AwsException e = assertThrows(AwsException.class,
                () -> AthenaHiveDdl.parse("CREATE EXTERNAL TABLE t (a int LOCATION 's3://b/'"));

        assertEquals("InvalidRequestException", e.getErrorCode());
    }
}
