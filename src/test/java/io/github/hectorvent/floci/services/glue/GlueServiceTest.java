package io.github.hectorvent.floci.services.glue;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.Database;
import io.github.hectorvent.floci.services.glue.model.Partition;
import io.github.hectorvent.floci.services.glue.model.SchemaReference;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;
import io.github.hectorvent.floci.services.glue.model.Table;
import io.github.hectorvent.floci.services.glue.model.UserDefinedFunction;
import io.github.hectorvent.floci.services.glue.schemaregistry.GlueSchemaRegistryService;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.RegistryId;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.SchemaId;
import io.github.hectorvent.floci.services.resourcegroupstagging.ResourceGroupsTaggingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlueServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";

    private static final String AVRO_V1 =
            "{\"type\":\"record\",\"name\":\"User\",\"namespace\":\"x\","
                    + "\"fields\":[{\"name\":\"id\",\"type\":\"long\"}]}";

    private static final String AVRO_V2 =
            "{\"type\":\"record\",\"name\":\"User\",\"namespace\":\"x\","
                    + "\"fields\":[{\"name\":\"id\",\"type\":\"long\"},"
                    + "{\"name\":\"email\",\"type\":[\"null\",\"string\"],\"default\":null}]}";

    private GlueService glueService;
    private GlueSchemaRegistryService schemaRegistryService;
    private StorageBackend<String, Table> tableStore;
    private StorageBackend<String, Table> tableVersionStore;
    private StorageBackend<String, Map<String, Object>> columnStatisticsStore;
    private StorageBackend<String, Partition> partitionStore;

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT_ID);
        StorageFactory storageFactory = new InMemoryStorageFactory();
        schemaRegistryService = new GlueSchemaRegistryService(storageFactory, regionResolver);
        tableStore = new InMemoryStorage<>();
        tableVersionStore = new InMemoryStorage<>();
        columnStatisticsStore = new InMemoryStorage<>();
        partitionStore = new InMemoryStorage<>();
        glueService = new GlueService(
                new InMemoryStorage<String, Database>(),
                tableStore,
                tableVersionStore,
                columnStatisticsStore,
                partitionStore,
                new InMemoryStorage<String, Map<String, Object>>(),
                new InMemoryStorage<String, UserDefinedFunction>(),
                schemaRegistryService, regionResolver, new ResourceGroupsTaggingService());
        glueService.createDatabase(new Database("db1"));
    }

    @Test
    void getTableWithoutSchemaReferenceReturnsColumnsUnchanged() {
        Table table = new Table();
        table.setName("plain");
        StorageDescriptor sd = new StorageDescriptor();
        Column column = new Column("a", "string");
        column.setParameters(Map.of("trino_type_id", "varchar"));
        sd.setColumns(java.util.List.of(column));
        table.setStorageDescriptor(sd);
        glueService.createTable("db1", table);

        Table fetched = glueService.getTable("db1", "plain");

        assertEquals(1, fetched.getStorageDescriptor().getColumns().size());
        assertEquals("a", fetched.getStorageDescriptor().getColumns().get(0).getName());
        assertEquals("0", fetched.getVersionId());
        assertEquals("varchar", fetched.getStorageDescriptor().getColumns().get(0).getParameters().get("trino_type_id"));
        assertNull(fetched.getStorageDescriptor().getSchemaReference());
    }

    @Test
    void updateDatabaseUpdatesMetadata() {
        assertNotNull(glueService.getDatabase("db1"));

        Database database = new Database("db1");
        database.setDescription("updated");
        database.setLocationUri("s3://bucket/database/");
        database.setParameters(Map.of("owner", "test"));

        glueService.updateDatabase("db1", database);

        Database updated = glueService.getDatabase("db1");
        assertEquals("db1", updated.getName());
        assertEquals("updated", updated.getDescription());
        assertEquals("s3://bucket/database/", updated.getLocationUri());
        assertEquals("test", updated.getParameters().get("owner"));
    }

    @Test
    void updateDatabaseRejectsRename() {
        assertNotNull(glueService.getDatabase("db1"));

        Database database = new Database("renamed");

        AwsException exception = assertThrows(AwsException.class, () -> glueService.updateDatabase("db1", database));

        assertEquals("InvalidInputException", exception.getErrorCode());
        assertEquals("Database cannot be renamed", exception.getMessage());
    }

    @Test
    void getTableWithValidSchemaReferenceReturnsDerivedColumns() {
        schemaRegistryService.createRegistry("r1", null, null, REGION);
        schemaRegistryService.createSchema(new RegistryId("r1", null),
                "users", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);

        Table table = tableReferencing("r1", "users", null, null);
        glueService.createTable("db1", table);

        Table fetched = glueService.getTable("db1", "withref");

        assertEquals(1, fetched.getStorageDescriptor().getColumns().size());
        assertEquals("id", fetched.getStorageDescriptor().getColumns().get(0).getName());
        assertEquals("bigint", fetched.getStorageDescriptor().getColumns().get(0).getType());
        assertNotNull(fetched.getStorageDescriptor().getSchemaReference());
    }

    @Test
    void getTablePicksUpNewVersionWhenPinnedToLatest() {
        schemaRegistryService.createRegistry("r1", null, null, REGION);
        schemaRegistryService.createSchema(new RegistryId("r1", null),
                "users", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);
        Table storedTable = tableReferencing("r1", "users", null, null);
        glueService.createTable("db1", storedTable);

        Table firstFetch = glueService.getTable("db1", "withref");

        assertEquals(1, firstFetch.getStorageDescriptor().getColumns().size());
        assertTrue(storedTable.getStorageDescriptor().getColumns() == null
                || storedTable.getStorageDescriptor().getColumns().isEmpty());

        // Register v2 — adds optional email field.
        schemaRegistryService.registerSchemaVersion(
                new SchemaId("r1", "users", null), AVRO_V2, REGION);

        Table fetched = glueService.getTable("db1", "withref");

        assertEquals(2, fetched.getStorageDescriptor().getColumns().size());
        assertEquals("email", fetched.getStorageDescriptor().getColumns().get(1).getName());
    }

    @Test
    void getTablePinnedToVersionNumberStaysOnThatVersion() {
        schemaRegistryService.createRegistry("r1", null, null, REGION);
        schemaRegistryService.createSchema(new RegistryId("r1", null),
                "users", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);
        glueService.createTable("db1", tableReferencing("r1", "users", 1L, null));
        schemaRegistryService.registerSchemaVersion(
                new SchemaId("r1", "users", null), AVRO_V2, REGION);

        Table fetched = glueService.getTable("db1", "withref");

        assertEquals(1, fetched.getStorageDescriptor().getColumns().size(), "should still see v1");
    }

    @Test
    void createTableWithBrokenSchemaReferenceThrows() {
        Table table = tableReferencing("does-not-exist", "users", null, null);

        AwsException ex = assertThrows(AwsException.class,
                () -> glueService.createTable("db1", table));
        assertEquals("EntityNotFoundException", ex.getErrorCode());
    }

    @Test
    void getTableWithStaleSchemaReferenceReturnsTableTolerantly() {
        schemaRegistryService.createRegistry("r1", null, null, REGION);
        schemaRegistryService.createSchema(new RegistryId("r1", null),
                "users", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);
        glueService.createTable("db1", tableReferencing("r1", "users", null, null));

        // Delete the underlying schema after the table was created.
        schemaRegistryService.deleteSchema(new SchemaId("r1", "users", null), REGION);

        Table fetched = glueService.getTable("db1", "withref");

        // Tolerant path: table is returned, columns are whatever was stored at create
        // time (in our case nothing — we never wrote columns explicitly).
        assertNotNull(fetched);
        assertNotNull(fetched.getStorageDescriptor().getSchemaReference());
        assertTrue(fetched.getStorageDescriptor().getColumns() == null
                || fetched.getStorageDescriptor().getColumns().isEmpty());
    }

    @Test
    void getTablesAppliesResolutionToEachTable() {
        schemaRegistryService.createRegistry("r1", null, null, REGION);
        schemaRegistryService.createSchema(new RegistryId("r1", null),
                "users", "AVRO", "BACKWARD", null, AVRO_V1, null, REGION);
        glueService.createTable("db1", tableReferencing("r1", "users", null, null));
        Table plain = new Table();
        plain.setName("plain");
        StorageDescriptor sd = new StorageDescriptor();
        plain.setStorageDescriptor(sd);
        glueService.createTable("db1", plain);

        var tables = glueService.getTables("db1");

        assertEquals(2, tables.size());
        for (Table t : tables) {
            if ("withref".equals(t.getName())) {
                assertEquals(1, t.getStorageDescriptor().getColumns().size());
            }
        }
    }

    @Test
    void updateTableReplacesExistingDefinitionAndPreservesCreateTime() {
        Table table = new Table();
        table.setName("plain");
        StorageDescriptor sd = new StorageDescriptor();
        sd.setColumns(java.util.List.of(new Column("a", "string")));
        table.setStorageDescriptor(sd);
        glueService.createTable("db1", table);

        Table created = glueService.getTable("db1", "plain");
        Table replacement = new Table();
        replacement.setName("plain");
        StorageDescriptor replacementSd = new StorageDescriptor();
        replacementSd.setColumns(java.util.List.of(new Column("b", "bigint")));
        replacement.setStorageDescriptor(replacementSd);

        glueService.updateTable("db1", replacement, null, false);

        Table fetched = glueService.getTable("db1", "plain");
        assertEquals(created.getCreateTime(), fetched.getCreateTime());
        assertNotNull(fetched.getUpdateTime());
        assertEquals("1", fetched.getVersionId());
        assertEquals(1, fetched.getStorageDescriptor().getColumns().size());
        assertEquals("b", fetched.getStorageDescriptor().getColumns().get(0).getName());
    }

    @Test
    void updateTableChecksVersionId() {
        Table table = new Table();
        table.setName("plain");
        glueService.createTable("db1", table);
        assertEquals("0", glueService.getTable("db1", "plain").getVersionId());

        Table nonCanonicalVersionReplacement = new Table();
        nonCanonicalVersionReplacement.setName("plain");
        AwsException nonCanonicalVersionEx = assertThrows(AwsException.class,
                () -> glueService.updateTable("db1", nonCanonicalVersionReplacement, "00", false));
        assertEquals("ConcurrentModificationException", nonCanonicalVersionEx.getErrorCode());
        assertEquals("0", glueService.getTable("db1", "plain").getVersionId());

        Table nonNumericVersionReplacement = new Table();
        nonNumericVersionReplacement.setName("plain");
        AwsException nonNumericVersionEx = assertThrows(AwsException.class,
                () -> glueService.updateTable("db1", nonNumericVersionReplacement, "invalid", false));
        assertEquals("ConcurrentModificationException", nonNumericVersionEx.getErrorCode());
        assertEquals("0", glueService.getTable("db1", "plain").getVersionId());

        Table firstReplacement = new Table();
        firstReplacement.setName("plain");
        firstReplacement.setDescription("first");
        glueService.updateTable("db1", firstReplacement, "0", false);
        assertEquals("1", glueService.getTable("db1", "plain").getVersionId());

        Table staleReplacement = new Table();
        staleReplacement.setName("plain");
        AwsException ex = assertThrows(AwsException.class,
                () -> glueService.updateTable("db1", staleReplacement, "0", false));

        assertEquals("ConcurrentModificationException", ex.getErrorCode());
        assertEquals("Update table failed due to concurrent modifications.", ex.getMessage());
    }

    @Test
    void updateTableIncrementsMissingVersionId() {
        Table existing = new Table();
        existing.setName("plain");
        existing.setDatabaseName("db1");
        tableStore.put("db1:plain", existing);

        Table replacement = new Table();
        replacement.setName("plain");
        glueService.updateTable("db1", replacement, null, false);

        assertEquals("1", glueService.getTable("db1", "plain").getVersionId());
    }

    @Test
    void getTableReturnsViewFieldsUnchanged() {
        Table table = new Table();
        table.setName("view");
        table.setOwner("test-owner");
        table.setTableType("VIRTUAL_VIEW");
        table.setViewOriginalText("SELECT 1 AS x");
        table.setViewExpandedText("SELECT 1 AS x");
        table.setParameters(Map.of("presto_view", "true"));
        StorageDescriptor storageDescriptor = new StorageDescriptor();
        storageDescriptor.setColumns(java.util.List.of(new Column("x", "int")));
        table.setStorageDescriptor(storageDescriptor);

        glueService.createTable("db1", table);

        Table fetched = glueService.getTable("db1", "view");

        assertEquals("test-owner", fetched.getOwner());
        assertEquals("VIRTUAL_VIEW", fetched.getTableType());
        assertEquals("SELECT 1 AS x", fetched.getViewOriginalText());
        assertEquals("SELECT 1 AS x", fetched.getViewExpandedText());
        assertEquals("true", fetched.getParameters().get("presto_view"));
    }

    @Test
    void deleteDatabaseDeletesDatabaseTablesAndPartitions() {
        Table table = new Table();
        table.setName("plain");
        glueService.createTable("db1", table);

        Partition partition = new Partition();
        partition.setValues(java.util.List.of("2026"));
        glueService.createPartition("db1", "plain", partition);

        glueService.deleteDatabase("db1");

        assertThrows(AwsException.class, () -> glueService.getDatabase("db1"));
        assertThrows(AwsException.class, () -> glueService.getTable("db1", "plain"));
        assertTrue(tableStore.scan(k -> true).isEmpty());
        assertTrue(partitionStore.scan(k -> true).isEmpty());
    }

    @Test
    void partitionsCanBeFetchedFilteredAndDeleted() {
        Table table = new Table();
        table.setName("plain");
        table.setPartitionKeys(List.of(new Column("part", "int")));
        glueService.createTable("db1", table);
        Partition first = new Partition();
        first.setValues(List.of("1"));
        glueService.createPartition("db1", "plain", first);
        Partition second = new Partition();
        second.setValues(List.of("2"));
        glueService.createPartition("db1", "plain", second);

        assertEquals(List.of("1"), glueService.getPartition("db1", "plain", List.of("1")).getValues());
        assertEquals(List.of(List.of("1")), glueService.batchGetPartitions(
                        "db1", "plain", List.of(List.of("1"), List.of("missing"))).stream()
                .map(Partition::getValues)
                .toList());
        assertEquals(List.of(List.of("1")), glueService.getPartitions("db1", "plain", "part = 1").stream()
                .map(Partition::getValues)
                .toList());
        assertEquals(List.of(List.of("1"), List.of("2")), glueService.getPartitions("db1", "plain", "part >= 1 AND part <= 2").stream()
                .map(Partition::getValues)
                .sorted((left, right) -> left.get(0).compareTo(right.get(0)))
                .toList());
        assertEquals(List.of(List.of("1")), glueService.getPartitions("db1", "plain", "part in (1, 3)").stream()
                .map(Partition::getValues)
                .toList());
        AwsException invalidExpression = assertThrows(AwsException.class,
                () -> glueService.getPartitions("db1", "plain", "part between 1 and 2"));
        assertEquals("InvalidInputException", invalidExpression.getErrorCode());

        glueService.deletePartition("db1", "plain", List.of("2"));

        AwsException ex = assertThrows(AwsException.class,
                () -> glueService.getPartition("db1", "plain", List.of("2")));
        assertEquals("EntityNotFoundException", ex.getErrorCode());
    }

    @Test
    void partitionKeysDoNotCollideForCommaSeparatedValues() {
        Table table = new Table();
        table.setName("plain");
        glueService.createTable("db1", table);
        Partition first = new Partition();
        first.setValues(List.of("a,b"));
        Partition second = new Partition();
        second.setValues(List.of("a", "b"));

        glueService.createPartition("db1", "plain", first);
        glueService.createPartition("db1", "plain", second);

        assertEquals(List.of("a,b"), glueService.getPartition("db1", "plain", List.of("a,b")).getValues());
        assertEquals(List.of("a", "b"), glueService.getPartition("db1", "plain", List.of("a", "b")).getValues());
    }

    @Test
    void batchCreatePartitionCreatesNewPartitionsAndReportsDuplicates() {
        Table table = new Table();
        table.setName("plain");
        glueService.createTable("db1", table);
        Partition first = new Partition();
        first.setValues(List.of("2026"));
        Partition duplicate = new Partition();
        duplicate.setValues(List.of("2026"));
        Partition second = new Partition();
        second.setValues(List.of("2027"));

        List<GlueService.BatchCreatePartitionError> firstResult =
                glueService.batchCreatePartitions("db1", "plain", List.of(first, second));
        List<GlueService.BatchCreatePartitionError> secondResult =
                glueService.batchCreatePartitions("db1", "plain", List.of(duplicate));

        assertTrue(firstResult.isEmpty());
        assertEquals(1, secondResult.size());
        assertEquals(List.of("2026"), secondResult.getFirst().partitionValues());
        assertEquals("AlreadyExistsException", secondResult.getFirst().errorDetail().errorCode());
        assertEquals(2, glueService.getPartitions("db1", "plain").size());
    }

    @Test
    void batchUpdatePartitionUpdatesPartitionsAndReportsMissingPartitions() {
        Table table = new Table();
        table.setName("plain");
        glueService.createTable("db1", table);
        Partition existing = new Partition();
        existing.setValues(List.of("2026"));
        existing.setParameters(Map.of("before", "yes"));
        glueService.createPartition("db1", "plain", existing);
        Partition updated = new Partition();
        updated.setValues(List.of("2026"));
        updated.setParameters(Map.of("after", "yes"));
        Partition missing = new Partition();
        missing.setValues(List.of("missing"));

        List<GlueService.BatchUpdatePartitionError> result = glueService.batchUpdatePartitions("db1", "plain", List.of(
                new GlueService.BatchUpdatePartitionEntry(List.of("2026"), updated),
                new GlueService.BatchUpdatePartitionEntry(List.of("missing"), missing)));

        assertEquals(Map.of("after", "yes"), glueService.getPartition("db1", "plain", List.of("2026")).getParameters());
        assertEquals(1, result.size());
        assertEquals(List.of("missing"), result.getFirst().partitionValueList());
        assertEquals("EntityNotFoundException", result.getFirst().errorDetail().errorCode());
        assertEquals("Partition [missing] not found", result.getFirst().errorDetail().errorMessage());
    }

    @Test
    void updatePartitionUpdatesPartitionAndThrowsForMissingPartition() {
        Table table = new Table();
        table.setName("plain");
        glueService.createTable("db1", table);
        Partition existing = new Partition();
        existing.setValues(List.of("2026"));
        existing.setParameters(Map.of("before", "yes"));
        glueService.createPartition("db1", "plain", existing);
        Partition updated = new Partition();
        updated.setValues(List.of("2026"));
        updated.setParameters(Map.of("after", "yes"));

        glueService.updatePartition("db1", "plain", List.of("2026"), updated);

        assertEquals(Map.of("after", "yes"), glueService.getPartition("db1", "plain", List.of("2026")).getParameters());
        Partition missing = new Partition();
        missing.setValues(List.of("missing"));
        AwsException ex = assertThrows(AwsException.class,
                () -> glueService.updatePartition("db1", "plain", List.of("missing"), missing));
        assertEquals("EntityNotFoundException", ex.getErrorCode());
        assertEquals("Partition not found.", ex.getMessage());
    }

    @Test
    void deletePartitionForMissingPartitionThrows() {
        Table table = new Table();
        table.setName("plain");
        glueService.createTable("db1", table);

        AwsException ex = assertThrows(AwsException.class,
                () -> glueService.deletePartition("db1", "plain", List.of("missing")));

        assertEquals("EntityNotFoundException", ex.getErrorCode());
        assertEquals("Cannot find partition.", ex.getMessage());
    }

    @Test
    void partitionColumnStatisticsCanBeUpdatedFetchedAndDeleted() {
        Table table = new Table();
        table.setName("plain");
        glueService.createTable("db1", table);
        Partition partition = new Partition();
        partition.setValues(List.of("1"));
        glueService.createPartition("db1", "plain", partition);
        Map<String, Object> statistics = Map.of(
                "ColumnName", "id",
                "ColumnType", "int",
                "AnalyzedTime", Instant.EPOCH,
                "StatisticsData", Map.of(
                        "Type", "LONG",
                        "LongColumnStatisticsData", Map.of(
                                "MinimumValue", 1,
                                "MaximumValue", 10,
                                "NumberOfNulls", 0,
                                "NumberOfDistinctValues", 10)));

        glueService.updateColumnStatisticsForPartition("db1", "plain", List.of("1"), List.of(statistics));
        GlueService.ColumnStatisticsResult result = glueService.getColumnStatisticsForPartition(
                "db1", "plain", List.of("1"), List.of("id", "missing"));

        assertEquals(List.of(statistics), result.columnStatisticsList());
        assertEquals(1, result.errors().size());
        assertEquals("missing", result.errors().get(0).columnName());
        assertEquals(
                new GlueService.ErrorDetail("EntityNotFoundException", "Statistics do not exist for this column"),
                result.errors().get(0).error());

        glueService.deleteColumnStatisticsForPartition("db1", "plain", List.of("1"), "id");
        GlueService.ColumnStatisticsResult afterDelete = glueService.getColumnStatisticsForPartition(
                "db1", "plain", List.of("1"), List.of("id"));

        assertTrue(afterDelete.columnStatisticsList().isEmpty());
        assertEquals(1, afterDelete.errors().size());
    }

    @Test
    void updatePartitionColumnStatisticsRejectsMissingRequiredFields() {
        Table table = new Table();
        table.setName("plain");
        glueService.createTable("db1", table);
        Partition partition = new Partition();
        partition.setValues(List.of("1"));
        glueService.createPartition("db1", "plain", partition);

        for (String field : List.of(
                GlueService.COLUMN_NAME,
                GlueService.COLUMN_TYPE,
                GlueService.ANALYZED_TIME,
                GlueService.STATISTICS_DATA)) {
            Map<String, Object> statistics = new LinkedHashMap<>(columnStatistics("id"));
            statistics.remove(field);

            AwsException exception = assertThrows(AwsException.class,
                    () -> glueService.updateColumnStatisticsForPartition("db1", "plain", List.of("1"), List.of(statistics)));

            assertEquals("InvalidInputException", exception.getErrorCode());
            assertEquals(field + " is required", exception.getMessage());
        }
    }

    @Test
    void deleteDatabaseDoesNotDeleteSimilarDatabaseNames() {
        glueService.createDatabase(new Database("a"));
        glueService.createDatabase(new Database("a:b"));
        Table similarDatabaseTable = new Table();
        similarDatabaseTable.setName("t");
        glueService.createTable("a:b", similarDatabaseTable);

        glueService.deleteDatabase("a");

        assertEquals("t", glueService.getTable("a:b", "t").getName());
    }

    @Test
    void deleteDatabaseForMissingDatabaseThrows() {
        AwsException ex = assertThrows(AwsException.class,
                () -> glueService.deleteDatabase("missing"));

        assertEquals("EntityNotFoundException", ex.getErrorCode());
    }

    @Test
    void deleteTableForMissingTableThrows() {
        AwsException ex = assertThrows(AwsException.class,
                () -> glueService.deleteTable("db1", "missing_table"));

        assertEquals("EntityNotFoundException", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("db1.missing_table"));
    }

    @Test
    void updateTableWithCurrentVersionIdSucceedsAndIncrementsVersionId() {
        Table table = new Table();
        table.setName("plain");
        table.setParameters(Map.of("metadata_location", "s3://bucket/v1.metadata.json"));
        glueService.createTable("db1", table);

        Table created = glueService.getTable("db1", "plain");
        Table replacement = new Table();
        replacement.setName("plain");
        replacement.setParameters(Map.of("metadata_location", "s3://bucket/v2.metadata.json"));

        glueService.updateTable("db1", replacement, created.getVersionId(), false);

        Table fetched = glueService.getTable("db1", "plain");
        assertEquals("1", fetched.getVersionId());
        assertEquals("s3://bucket/v2.metadata.json", fetched.getParameters().get("metadata_location"));
    }

    @Test
    void updateTableWithStaleVersionIdThrowsAndDoesNotOverwrite() {
        Table table = new Table();
        table.setName("plain");
        table.setParameters(Map.of("metadata_location", "s3://bucket/v1.metadata.json"));
        glueService.createTable("db1", table);

        Table replacement = new Table();
        replacement.setName("plain");
        replacement.setParameters(Map.of("metadata_location", "s3://bucket/v2.metadata.json"));

        AwsException ex = assertThrows(AwsException.class,
                () -> glueService.updateTable("db1", replacement, "stale-version", false));

        assertEquals("ConcurrentModificationException", ex.getErrorCode());
        Table fetched = glueService.getTable("db1", "plain");
        assertEquals("0", fetched.getVersionId());
        assertEquals("s3://bucket/v1.metadata.json", fetched.getParameters().get("metadata_location"));
    }

    @Test
    void createTableIgnoresProvidedVersionId() {
        Table table = new Table();
        table.setName("plain");
        table.setVersionId("7");

        glueService.createTable("db1", table);

        assertEquals("0", glueService.getTable("db1", "plain").getVersionId());
    }

    @Test
    void updateTableWithSameVersionIdRejectsSecondUpdate() {
        Table table = new Table();
        table.setName("plain");
        table.setParameters(Map.of("metadata_location", "s3://bucket/v1.metadata.json"));
        glueService.createTable("db1", table);
        String versionId = glueService.getTable("db1", "plain").getVersionId();

        Table firstReplacement = new Table();
        firstReplacement.setName("plain");
        firstReplacement.setParameters(Map.of("metadata_location", "s3://bucket/v2a.metadata.json"));
        glueService.updateTable("db1", firstReplacement, versionId, false);

        Table secondReplacement = new Table();
        secondReplacement.setName("plain");
        secondReplacement.setParameters(Map.of("metadata_location", "s3://bucket/v2b.metadata.json"));
        AwsException ex = assertThrows(AwsException.class,
                () -> glueService.updateTable("db1", secondReplacement, versionId, false));

        assertEquals("ConcurrentModificationException", ex.getErrorCode());
        Table fetched = glueService.getTable("db1", "plain");
        assertEquals("1", fetched.getVersionId());
        assertEquals("s3://bucket/v2a.metadata.json", fetched.getParameters().get("metadata_location"));
    }

    @Test
    void catalogNamesAreCaseInsensitiveAcrossApis() {
        glueService.createDatabase(new Database("MixedCaseDatabase"));

        assertEquals("mixedcasedatabase", glueService.getDatabase("MixedCaseDatabase").getName());
        assertEquals("mixedcasedatabase", glueService.getDatabase("mixedcasedatabase").getName());
        assertTrue(glueService.getDatabases().stream()
                .map(Database::getName)
                .toList()
                .contains("mixedcasedatabase"));
        AwsException databaseExists = assertThrows(AwsException.class,
                () -> glueService.createDatabase(new Database("MIXEDCASEDATABASE")));
        assertEquals("AlreadyExistsException", databaseExists.getErrorCode());

        Table table = new Table();
        table.setName("MixedCaseTable");
        glueService.createTable("MixedCaseDatabase", table);
        Table duplicateTable = new Table();
        duplicateTable.setName("MIXEDCASETABLE");
        AwsException tableExists = assertThrows(AwsException.class,
                () -> glueService.createTable("MIXEDCASEDATABASE", duplicateTable));
        assertEquals("AlreadyExistsException", tableExists.getErrorCode());
        Table fetchedTable = glueService.getTable("mixedcasedatabase", "MIXEDCASETABLE");
        assertEquals("mixedcasedatabase", fetchedTable.getDatabaseName());
        assertEquals("mixedcasetable", fetchedTable.getName());
        assertEquals(List.of("mixedcasetable"), glueService.getTables("MIXEDCASEDATABASE").stream()
                .map(Table::getName)
                .toList());

        Table replacement = new Table();
        replacement.setName("MIXEDCASETABLE");
        replacement.setDescription("updated");
        glueService.updateTable("MIXEDCASEDATABASE", replacement, fetchedTable.getVersionId(), false);
        assertEquals("updated", glueService.getTable("mixedcasedatabase", "mixedcasetable").getDescription());

        Partition partition = new Partition();
        partition.setValues(List.of("2026"));
        glueService.createPartition("MIXEDCASEDATABASE", "MIXEDCASETABLE", partition);
        Partition fetchedPartition = glueService.getPartitions("mixedcasedatabase", "mixedcasetable").get(0);
        assertEquals("mixedcasedatabase", fetchedPartition.getDatabaseName());
        assertEquals("mixedcasetable", fetchedPartition.getTableName());

        UserDefinedFunction function = new UserDefinedFunction();
        function.setFunctionName("MixedCaseFunction");
        glueService.createUserDefinedFunction("mixedcasedatabase", function);
        UserDefinedFunction duplicateFunction = new UserDefinedFunction();
        duplicateFunction.setFunctionName("MIXEDCASEFUNCTION");
        AwsException functionExists = assertThrows(AwsException.class,
                () -> glueService.createUserDefinedFunction("MIXEDCASEDATABASE", duplicateFunction));
        assertEquals("AlreadyExistsException", functionExists.getErrorCode());
        UserDefinedFunction fetchedFunction = glueService.getUserDefinedFunction("MixedCaseDatabase", "MIXEDCASEFUNCTION");
        assertEquals("mixedcasedatabase", fetchedFunction.getDatabaseName());
        assertEquals("mixedcasefunction", fetchedFunction.getFunctionName());
        GlueService.UserDefinedFunctionPage functions = glueService.getUserDefinedFunctions(
                "MIXEDCASEDATABASE", "mixedcase.*", null, 1, null);
        assertEquals(1, functions.functions().size());
        assertNull(functions.nextToken());

        UserDefinedFunction replacementFunction = new UserDefinedFunction();
        replacementFunction.setFunctionName("ignored-name");
        replacementFunction.setOwnerName("new-owner");
        glueService.updateUserDefinedFunction("MIXEDCASEDATABASE", "MIXEDCASEFUNCTION", replacementFunction);
        assertEquals("new-owner", glueService.getUserDefinedFunction("mixedcasedatabase", "mixedcasefunction").getOwnerName());

        glueService.deleteUserDefinedFunction("MIXEDCASEDATABASE", "MIXEDCASEFUNCTION");
        assertThrows(AwsException.class,
                () -> glueService.getUserDefinedFunction("mixedcasedatabase", "mixedcasefunction"));

        glueService.deleteTable("MIXEDCASEDATABASE", "MIXEDCASETABLE");
        assertThrows(AwsException.class,
                () -> glueService.getTable("mixedcasedatabase", "mixedcasetable"));

        Table tableDeletedWithDatabase = new Table();
        tableDeletedWithDatabase.setName("MixedCaseTableDeletedWithDatabase");
        glueService.createTable("mixedcasedatabase", tableDeletedWithDatabase);
        glueService.deleteDatabase("MIXEDCASEDATABASE");
        assertThrows(AwsException.class, () -> glueService.getDatabase("mixedcasedatabase"));
        assertThrows(AwsException.class,
                () -> glueService.getTable("mixedcasedatabase", "mixedcasetabledeletedwithdatabase"));
    }

    @Test
    void batchDeleteTablesDeletesExistingTablesAndReportsMissingTables() {
        Table table = new Table();
        table.setName("existing");
        glueService.createTable("db1", table);

        List<GlueService.BatchDeleteTableError> errors =
                glueService.batchDeleteTables("db1", List.of("existing", "missing"));

        assertEquals(1, errors.size());
        assertEquals("missing", errors.get(0).tableName());
        GlueService.ErrorDetail errorDetail = errors.get(0).errorDetail();
        assertEquals("EntityNotFoundException", errorDetail.errorCode());
        assertEquals("Table missing not found", errorDetail.errorMessage());
        AwsException ex = assertThrows(AwsException.class,
                () -> glueService.getTable("db1", "existing"));
        assertEquals("EntityNotFoundException", ex.getErrorCode());
    }

    @Test
    void getTableVersionsReturnsCurrentAndArchivedVersions() {
        Table table = new Table();
        table.setName("plain");
        table.setDescription("created");
        glueService.createTable("db1", table);

        Table replacement = new Table();
        replacement.setName("plain");
        replacement.setDescription("updated");
        glueService.updateTable("db1", replacement, "0", false);

        List<Map<String, Object>> tableVersions = glueService.getTableVersions("db1", "plain");

        assertEquals(2, tableVersions.size());
        assertEquals("1", tableVersions.get(0).get("VersionId"));
        assertEquals("updated", ((Table) tableVersions.get(0).get("Table")).getDescription());
        assertEquals("0", tableVersions.get(1).get("VersionId"));
        assertEquals("created", ((Table) tableVersions.get(1).get("Table")).getDescription());
    }

    @Test
    void updateTableSkipsArchiveWhenSkipArchiveIsTrue() {
        Table table = new Table();
        table.setName("plain");
        glueService.createTable("db1", table);

        Table replacement = new Table();
        replacement.setName("plain");
        glueService.updateTable("db1", replacement, "0", true);

        List<Map<String, Object>> tableVersions = glueService.getTableVersions("db1", "plain");

        assertEquals(1, tableVersions.size());
        assertEquals("1", tableVersions.get(0).get("VersionId"));
    }

    @Test
    void columnStatisticsCanBeUpdatedAndRetrieved() {
        Table table = new Table();
        table.setName("plain");
        glueService.createTable("db1", table);
        Map<String, Object> statistics = columnStatistics("id");

        glueService.updateColumnStatisticsForTable("db1", "plain", List.of(statistics));

        GlueService.ColumnStatisticsResult fetched =
                glueService.getColumnStatisticsForTable("db1", "plain", List.of("id"));
        assertEquals(1, fetched.columnStatisticsList().size());
        assertTrue(fetched.errors().isEmpty());
        assertEquals("id", fetched.columnStatisticsList().get(0).get(GlueService.COLUMN_NAME));
        assertEquals("LONG", ((Map<?, ?>) fetched.columnStatisticsList().get(0).get("StatisticsData")).get("Type"));
    }

    @Test
    void columnStatisticsCanBeDeleted() {
        Table table = new Table();
        table.setName("plain");
        glueService.createTable("db1", table);
        glueService.updateColumnStatisticsForTable("db1", "plain", List.of(columnStatistics("id")));

        glueService.deleteColumnStatisticsForTable("db1", "plain", "id");
        glueService.deleteColumnStatisticsForTable("db1", "plain", "id");

        GlueService.ColumnStatisticsResult fetched =
                glueService.getColumnStatisticsForTable("db1", "plain", List.of("id"));
        assertTrue(fetched.columnStatisticsList().isEmpty());
        assertEquals(1, fetched.errors().size());
        assertEquals("EntityNotFoundException", fetched.errors().getFirst().error().errorCode());
    }

    @Test
    void getColumnStatisticsReportsMissingColumns() {
        Table table = new Table();
        table.setName("plain");
        glueService.createTable("db1", table);

        GlueService.ColumnStatisticsResult fetched =
                glueService.getColumnStatisticsForTable("db1", "plain", List.of("missing"));

        assertTrue(fetched.columnStatisticsList().isEmpty());
        assertEquals(1, fetched.errors().size());
        assertEquals("missing", fetched.errors().getFirst().columnName());
        assertEquals("EntityNotFoundException", fetched.errors().getFirst().error().errorCode());
        assertEquals("Statistics do not exist for this column", fetched.errors().getFirst().error().errorMessage());
    }

    @Test
    void updateColumnStatisticsRejectsMissingRequiredFields() {
        Table table = new Table();
        table.setName("plain");
        glueService.createTable("db1", table);

        for (String field : List.of(
                GlueService.COLUMN_NAME,
                GlueService.COLUMN_TYPE,
                GlueService.ANALYZED_TIME,
                GlueService.STATISTICS_DATA)) {
            Map<String, Object> statistics = new LinkedHashMap<>(columnStatistics("id"));
            statistics.remove(field);

            AwsException exception = assertThrows(AwsException.class,
                    () -> glueService.updateColumnStatisticsForTable("db1", "plain", List.of(statistics)));

            assertEquals("InvalidInputException", exception.getErrorCode());
            assertEquals(field + " is required", exception.getMessage());
        }
    }

    @Test
    void deleteTableDeletesColumnStatistics() {
        Table table = new Table();
        table.setName("plain");
        glueService.createTable("db1", table);
        glueService.updateColumnStatisticsForTable("db1", "plain", List.of(columnStatistics("id")));

        glueService.deleteTable("db1", "plain");

        assertTrue(columnStatisticsStore.scan(k -> true).isEmpty());
    }

    @Test
    void userDefinedFunctionsCanBeCreatedListedUpdatedAndDeleted() {
        UserDefinedFunction function = new UserDefinedFunction();
        function.setFunctionName("udf__test__integer");
        function.setClassName("ExampleFunction");
        function.setFunctionType("REGULAR_FUNCTION");
        function.setOwnerType("USER");
        function.setOwnerName("owner");
        function.setCreateTime(Instant.EPOCH);

        glueService.createUserDefinedFunction("db1", function);

        UserDefinedFunction fetched = glueService.getUserDefinedFunction("db1", "udf__test__integer");
        assertEquals("db1", fetched.getDatabaseName());
        assertEquals("ExampleFunction", fetched.getClassName());
        assertEquals("REGULAR_FUNCTION", fetched.getFunctionType());
        assertEquals("owner", fetched.getOwnerName());
        assertNotNull(fetched.getCreateTime());
        assertTrue(fetched.getCreateTime().isAfter(Instant.EPOCH));
        assertEquals(1, glueService.getUserDefinedFunctions("db1", "udf__test__.*").size());
        assertEquals(1, glueService.getUserDefinedFunctions("db1", "udf__\\Qtest\\E__.*").size());
        assertEquals(0, glueService.getUserDefinedFunctions("db1", "other__.*").size());

        UserDefinedFunction replacement = new UserDefinedFunction();
        replacement.setFunctionName("ignored-name");
        replacement.setClassName("ExampleFunction");
        replacement.setFunctionType("REGULAR_FUNCTION");
        replacement.setOwnerType("USER");
        replacement.setOwnerName("new-owner");
        glueService.updateUserDefinedFunction("db1", "udf__test__integer", replacement);

        UserDefinedFunction updated = glueService.getUserDefinedFunction("db1", "udf__test__integer");
        assertEquals("udf__test__integer", updated.getFunctionName());
        assertEquals(fetched.getCreateTime(), updated.getCreateTime());
        assertEquals("new-owner", updated.getOwnerName());

        glueService.deleteUserDefinedFunction("db1", "udf__test__integer");

        AwsException ex = assertThrows(AwsException.class,
                () -> glueService.getUserDefinedFunction("db1", "udf__test__integer"));
        assertEquals("EntityNotFoundException", ex.getErrorCode());
    }

    @Test
    void getUserDefinedFunctionsPaginatesFiltersAndScansAllDatabases() {
        UserDefinedFunction db1Function = new UserDefinedFunction();
        db1Function.setFunctionName("udf__test__integer");
        db1Function.setFunctionType("REGULAR_FUNCTION");
        glueService.createUserDefinedFunction("db1", db1Function);

        UserDefinedFunction storedProcedure = new UserDefinedFunction();
        storedProcedure.setFunctionName("udf__test__procedure");
        storedProcedure.setFunctionType("STORED_PROCEDURE");
        glueService.createUserDefinedFunction("db1", storedProcedure);

        glueService.createDatabase(new Database("db2"));
        UserDefinedFunction db2Function = new UserDefinedFunction();
        db2Function.setFunctionName("udf__test__varchar");
        db2Function.setFunctionType("REGULAR_FUNCTION");
        glueService.createUserDefinedFunction("db2", db2Function);

        GlueService.UserDefinedFunctionPage firstPage =
                glueService.getUserDefinedFunctions(null, "udf__test__.*", "REGULAR_FUNCTION", 1, null);

        assertEquals(1, firstPage.functions().size());
        assertEquals("db1", firstPage.functions().getFirst().getDatabaseName());
        assertEquals("udf__test__integer", firstPage.functions().getFirst().getFunctionName());
        assertNotNull(firstPage.nextToken());

        GlueService.UserDefinedFunctionPage secondPage =
                glueService.getUserDefinedFunctions(
                        null, "udf__test__.*", "REGULAR_FUNCTION", 1, firstPage.nextToken());

        assertEquals(1, secondPage.functions().size());
        assertEquals("db2", secondPage.functions().getFirst().getDatabaseName());
        assertEquals("udf__test__varchar", secondPage.functions().getFirst().getFunctionName());
        assertNull(secondPage.nextToken());
    }

    @Test
    void getUserDefinedFunctionsRejectsInvalidPagingInput() {
        AwsException maxResultsEx = assertThrows(AwsException.class,
                () -> glueService.getUserDefinedFunctions("db1", ".*", null, 101, null));
        assertEquals("InvalidInputException", maxResultsEx.getErrorCode());

        AwsException nextTokenEx = assertThrows(AwsException.class,
                () -> glueService.getUserDefinedFunctions("db1", ".*", null, 1, "invalid"));
        assertEquals("InvalidInputException", nextTokenEx.getErrorCode());
    }

    @Test
    void getUserDefinedFunctionsWithInvalidPatternThrows() {
        AwsException ex = assertThrows(AwsException.class,
                () -> glueService.getUserDefinedFunctions("db1", "udf__("));

        assertEquals("InvalidInputException", ex.getErrorCode());
    }

    private Table tableReferencing(String registryName, String schemaName, Long versionNumber, String versionId) {
        Table table = new Table();
        table.setName("withref");
        StorageDescriptor sd = new StorageDescriptor();
        SchemaReference ref = new SchemaReference();
        SchemaId schemaId = new SchemaId(registryName, schemaName, null);
        ref.setSchemaId(schemaId);
        ref.setSchemaVersionNumber(versionNumber);
        ref.setSchemaVersionId(versionId);
        sd.setSchemaReference(ref);
        table.setStorageDescriptor(sd);
        return table;
    }

    private static Map<String, Object> columnStatistics(String columnName) {
        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put(GlueService.COLUMN_NAME, columnName);
        statistics.put(GlueService.COLUMN_TYPE, "int");
        statistics.put(GlueService.ANALYZED_TIME, Instant.parse("2026-06-08T00:00:00Z"));
        statistics.put(GlueService.STATISTICS_DATA, Map.of(
                "Type", "LONG",
                "LongColumnStatisticsData", Map.of(
                        "MinimumValue", 1,
                        "MaximumValue", 10,
                        "NumberOfNulls", 0,
                        "NumberOfDistinctValues", 10)));
        return statistics;
    }

    private static final class InMemoryStorageFactory extends StorageFactory {
        private InMemoryStorageFactory() {
            super(null, null);
        }

        @Override
        public <V> StorageBackend<String, V> create(String serviceName,
                                                     String fileName,
                                                     TypeReference<Map<String, V>> typeReference) {
            return new InMemoryStorage<>();
        }
    }
}
