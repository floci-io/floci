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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
    private StorageBackend<String, Partition> partitionStore;

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT_ID);
        StorageFactory storageFactory = new InMemoryStorageFactory();
        schemaRegistryService = new GlueSchemaRegistryService(storageFactory, regionResolver);
        tableStore = new InMemoryStorage<>();
        partitionStore = new InMemoryStorage<>();
        glueService = new GlueService(
                new InMemoryStorage<String, Database>(),
                tableStore,
                partitionStore,
                new InMemoryStorage<String, UserDefinedFunction>(),
                schemaRegistryService, regionResolver);
        glueService.createDatabase(new Database("db1"));
    }

    @Test
    void getTableWithoutSchemaReferenceReturnsColumnsUnchanged() {
        Table table = new Table();
        table.setName("plain");
        StorageDescriptor sd = new StorageDescriptor();
        sd.setColumns(java.util.List.of(new Column("a", "string")));
        table.setStorageDescriptor(sd);
        glueService.createTable("db1", table);

        Table fetched = glueService.getTable("db1", "plain");

        assertEquals(1, fetched.getStorageDescriptor().getColumns().size());
        assertEquals("a", fetched.getStorageDescriptor().getColumns().get(0).getName());
        assertNull(fetched.getStorageDescriptor().getSchemaReference());
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

        glueService.updateTable("db1", replacement);

        Table fetched = glueService.getTable("db1", "plain");
        assertEquals(created.getCreateTime(), fetched.getCreateTime());
        assertNotNull(fetched.getUpdateTime());
        assertEquals(1, fetched.getStorageDescriptor().getColumns().size());
        assertEquals("b", fetched.getStorageDescriptor().getColumns().get(0).getName());
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
    void getTableVersionsReturnsEmptyListForAthenaCompatibility() {
        assertTrue(glueService.getTableVersions().isEmpty());
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
