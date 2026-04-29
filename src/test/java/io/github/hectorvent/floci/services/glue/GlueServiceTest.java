package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.glue.model.Database;
import io.github.hectorvent.floci.services.glue.model.SchemaReference;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;
import io.github.hectorvent.floci.services.glue.model.Table;
import io.github.hectorvent.floci.services.glue.schemaregistry.GlueSchemaRegistryService;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.RegistryId;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.SchemaId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        schemaRegistryService = new GlueSchemaRegistryService(
                new InMemoryStorage<String,
                        io.github.hectorvent.floci.services.glue.schemaregistry.model.Registry>(),
                regionResolver);
        glueService = new GlueService(
                new InMemoryStorage<String, Database>(),
                new InMemoryStorage<String, Table>(),
                new InMemoryStorage<String, io.github.hectorvent.floci.services.glue.model.Partition>(),
                schemaRegistryService, regionResolver);
        glueService.createDatabase(new Database("db1"));
    }

    @Test
    void getTableWithoutSchemaReferenceReturnsColumnsUnchanged() {
        Table table = new Table();
        table.setName("plain");
        StorageDescriptor sd = new StorageDescriptor();
        sd.setColumns(java.util.List.of(
                new io.github.hectorvent.floci.services.glue.model.Column("a", "string")));
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
        glueService.createTable("db1", tableReferencing("r1", "users", null, null));

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
}
