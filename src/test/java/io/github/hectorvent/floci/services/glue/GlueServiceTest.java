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
import io.github.hectorvent.floci.services.glue.schemaregistry.GlueSchemaRegistryService;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.RegistryId;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.SchemaId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT_ID);
        StorageFactory storageFactory = new InMemoryStorageFactory();
        schemaRegistryService = new GlueSchemaRegistryService(storageFactory, regionResolver);
        glueService = new GlueService(
                new InMemoryStorage<String, Database>(),
                new InMemoryStorage<String, Table>(),
                new InMemoryStorage<String, Partition>(),
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
    void getTableVersionsReturnsEmptyListForAthenaCompatibility() {
        assertTrue(glueService.getTableVersions().isEmpty());
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
