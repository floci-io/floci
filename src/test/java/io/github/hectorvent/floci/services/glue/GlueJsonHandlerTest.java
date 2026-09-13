package io.github.hectorvent.floci.services.glue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.glue.schemaregistry.GlueSchemaRegistryService;
import io.github.hectorvent.floci.services.resourcegroupstagging.ResourceGroupsTaggingService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlueJsonHandlerTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";

    private GlueJsonHandler handler;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT_ID);
        StorageFactory storageFactory = new InMemoryStorageFactory();
        GlueSchemaRegistryService schemaRegistryService =
                new GlueSchemaRegistryService(storageFactory, regionResolver);
        GlueService glueService = new GlueService(
                storageFactory, schemaRegistryService, regionResolver, new ResourceGroupsTaggingService(storageFactory));
        handler = new GlueJsonHandler(glueService, schemaRegistryService, mapper);
    }

    private void createDatabaseAndTable(String dbName, String tableName) throws Exception {
        ObjectNode createDb = mapper.createObjectNode();
        createDb.putObject("DatabaseInput").put("Name", dbName);
        assertEquals(200, handler.handle("CreateDatabase", createDb, REGION).getStatus());

        ObjectNode createTable = mapper.createObjectNode();
        createTable.put("DatabaseName", dbName);
        createTable.putObject("TableInput").put("Name", tableName);
        assertEquals(200, handler.handle("CreateTable", createTable, REGION).getStatus());
    }

    /**
     * A client reading a table's partition indexes needs an answer, not an unsupported-action
     * failure: the Terraform AWS provider reads them after creating a table and on every refresh,
     * so without this a Glue table cannot be managed as a resource at all.
     */
    @Test
    void getPartitionIndexesReturnsAnEmptyListForAnExistingTable() throws Exception {
        createDatabaseAndTable("indexes_db", "events");

        ObjectNode request = mapper.createObjectNode();
        request.put("DatabaseName", "indexes_db");
        request.put("TableName", "events");

        Response response = handler.handle("GetPartitionIndexes", request, REGION);

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertTrue(body.containsKey("PartitionIndexDescriptorList"));
        assertTrue(((java.util.List<?>) body.get("PartitionIndexDescriptorList")).isEmpty());
    }

    /** A missing table is reported as missing, not as a table that happens to have no indexes. */
    @Test
    void getPartitionIndexesOnAMissingTableFails() throws Exception {
        createDatabaseAndTable("indexes_db2", "events");

        ObjectNode request = mapper.createObjectNode();
        request.put("DatabaseName", "indexes_db2");
        request.put("TableName", "absent");

        assertThrows(Exception.class, () -> handler.handle("GetPartitionIndexes", request, REGION));
    }

    /** Creates a table whose partition keys are the given names, all typed {@code string}. */
    private void createPartitionedTable(String dbName, String tableName, String... partitionKeys) throws Exception {
        ObjectNode createDb = mapper.createObjectNode();
        createDb.putObject("DatabaseInput").put("Name", dbName);
        try {
            handler.handle("CreateDatabase", createDb, REGION);
        } catch (AwsException alreadyExists) {
            // The helper is called twice in the drop-and-recreate case; the database survives.
        }

        ObjectNode createTable = mapper.createObjectNode();
        createTable.put("DatabaseName", dbName);
        ObjectNode tableInput = createTable.putObject("TableInput");
        tableInput.put("Name", tableName);
        var keys = tableInput.putArray("PartitionKeys");
        for (String partitionKey : partitionKeys) {
            keys.addObject().put("Name", partitionKey).put("Type", "string");
        }
        assertEquals(200, handler.handle("CreateTable", createTable, REGION).getStatus());
    }

    private Response createIndex(String dbName, String tableName, String indexName, String... keys) throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("DatabaseName", dbName);
        request.put("TableName", tableName);
        ObjectNode index = request.putObject("PartitionIndex");
        index.put("IndexName", indexName);
        var keyArray = index.putArray("Keys");
        for (String key : keys) {
            keyArray.add(key);
        }
        return handler.handle("CreatePartitionIndex", request, REGION);
    }

    @SuppressWarnings("unchecked")
    private java.util.List<Map<String, Object>> listIndexes(String dbName, String tableName) throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("DatabaseName", dbName);
        request.put("TableName", tableName);
        Response response = handler.handle("GetPartitionIndexes", request, REGION);
        assertEquals(200, response.getStatus());
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        return (java.util.List<Map<String, Object>>) mapper.convertValue(
                body.get("PartitionIndexDescriptorList"), new TypeReference<java.util.List<Map<String, Object>>>() {});
    }

    // ── Partition indexes ──────────────────────────────────────────
    //
    // Behaviour below was captured against real Glue in us-west-2 on a table partitioned by
    // tenant/year/month/day, so the codes and messages are AWS's own rather than invented.

    @Test
    void createdIndexIsReadBackActiveWithResolvedKeyTypes() throws Exception {
        createPartitionedTable("idx_db", "events", "tenant", "year", "month", "day");

        assertEquals(200, createIndex("idx_db", "events", "by_tenant", "tenant").getStatus());

        var indexes = listIndexes("idx_db", "events");
        assertEquals(1, indexes.size());
        assertEquals("by_tenant", indexes.get(0).get("IndexName"));
        // Real Glue creates asynchronously and reports CREATING first; floci settles immediately.
        assertEquals("ACTIVE", indexes.get(0).get("IndexStatus"));
        @SuppressWarnings("unchecked")
        var keys = (java.util.List<Map<String, Object>>) indexes.get(0).get("Keys");
        // A request carries key names only; the read resolves each one's type from the table.
        assertEquals("tenant", keys.get(0).get("Name"));
        assertEquals("string", keys.get(0).get("Type"));
    }

    @Test
    void deletingAnIndexRemovesItFromTheList() throws Exception {
        createPartitionedTable("idx_db_del", "events", "tenant", "year");
        createIndex("idx_db_del", "events", "by_tenant", "tenant");

        ObjectNode request = mapper.createObjectNode();
        request.put("DatabaseName", "idx_db_del");
        request.put("TableName", "events");
        request.put("IndexName", "by_tenant");
        assertEquals(200, handler.handle("DeletePartitionIndex", request, REGION).getStatus());

        assertTrue(listIndexes("idx_db_del", "events").isEmpty());
    }

    @Test
    void anIndexKeyMustBeOneOfTheTablesPartitionKeys() throws Exception {
        createPartitionedTable("idx_db_key", "events", "tenant", "year");

        AwsException ex = assertThrows(AwsException.class,
                () -> createIndex("idx_db_key", "events", "bad", "nosuchcolumn"));
        assertEquals("InvalidInputException", ex.getErrorCode());
    }

    @Test
    void anIndexNameCannotBeReused() throws Exception {
        createPartitionedTable("idx_db_dup", "events", "tenant", "year");
        createIndex("idx_db_dup", "events", "dup", "tenant");

        AwsException ex = assertThrows(AwsException.class,
                () -> createIndex("idx_db_dup", "events", "dup", "year"));
        assertEquals("AlreadyExistsException", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("same name"));
    }

    @Test
    void aSecondIndexOverTheSameKeysIsRefusedEvenUnderANewName() throws Exception {
        createPartitionedTable("idx_db_keys", "events", "tenant", "year");
        createIndex("idx_db_keys", "events", "first", "tenant");

        AwsException ex = assertThrows(AwsException.class,
                () -> createIndex("idx_db_keys", "events", "second", "tenant"));
        assertEquals("AlreadyExistsException", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("same keys"));
    }

    @Test
    void keyOrderDistinguishesTwoIndexes() throws Exception {
        createPartitionedTable("idx_db_order", "events", "tenant", "year", "month");

        // [year, month] and [month, year] are different indexes on real Glue, not duplicates.
        assertEquals(200, createIndex("idx_db_order", "events", "ord1", "year", "month").getStatus());
        assertEquals(200, createIndex("idx_db_order", "events", "ord2", "month", "year").getStatus());
        assertEquals(2, listIndexes("idx_db_order", "events").size());
    }

    @Test
    void aFourthIndexExceedsTheLimit() throws Exception {
        createPartitionedTable("idx_db_cap", "events", "tenant", "year", "month", "day");
        createIndex("idx_db_cap", "events", "i1", "tenant");
        createIndex("idx_db_cap", "events", "i2", "year");
        createIndex("idx_db_cap", "events", "i3", "month");

        AwsException ex = assertThrows(AwsException.class,
                () -> createIndex("idx_db_cap", "events", "i4", "day"));
        assertEquals("ResourceNumberLimitExceededException", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("Maximum: 3"));
    }

    @Test
    void deletingAnUnknownIndexFails() throws Exception {
        createPartitionedTable("idx_db_missing", "events", "tenant");

        ObjectNode request = mapper.createObjectNode();
        request.put("DatabaseName", "idx_db_missing");
        request.put("TableName", "events");
        request.put("IndexName", "absent");

        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("DeletePartitionIndex", request, REGION));
        assertEquals("EntityNotFoundException", ex.getErrorCode());
    }

    @Test
    void droppingATableDropsItsIndexes() throws Exception {
        createPartitionedTable("idx_db_cascade", "events", "tenant");
        createIndex("idx_db_cascade", "events", "by_tenant", "tenant");

        ObjectNode dropTable = mapper.createObjectNode();
        dropTable.put("DatabaseName", "idx_db_cascade");
        dropTable.put("Name", "events");
        assertEquals(200, handler.handle("DeleteTable", dropTable, REGION).getStatus());

        // Recreating the table must not resurrect the old index.
        createPartitionedTable("idx_db_cascade", "events", "tenant");
        assertTrue(listIndexes("idx_db_cascade", "events").isEmpty());
    }

    @Test
    void createCrawlerWithScheduleSucceeds() throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("Name", "test-crawler");
        request.put("Role", "arn:aws:iam::000000000000:role/role");
        request.put("Schedule", "cron(15 12 * * ? *)");
        
        ObjectNode targets = request.putObject("Targets");
        targets.putArray("S3Targets").addObject().put("Path", "s3://bucket/path/");

        Response response = handler.handle("CreateCrawler", request, REGION);
        assertEquals(200, response.getStatus());

        JsonNode body = mapper.valueToTree(response.getEntity());
        assertNotNull(body);

        Response getResponse = handler.handle("GetCrawler", mapper.createObjectNode().put("Name", "test-crawler"), REGION);
        assertEquals(200, getResponse.getStatus());
        JsonNode getBody = mapper.valueToTree(getResponse.getEntity());
        assertTrue(getBody.has("Crawler"));
        JsonNode crawler = getBody.get("Crawler");
        assertEquals("test-crawler", crawler.get("Name").asText());
        assertEquals("READY", crawler.get("State").asText());
        assertEquals(1, crawler.get("Version").asInt());
        assertTrue(crawler.has("Schedule"));
        assertEquals("cron(15 12 * * ? *)", crawler.get("Schedule").get("ScheduleExpression").asText());
    }

    @Test
    void updateCrawlerWithScheduleSucceeds() throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("Name", "test-crawler-update");
        request.put("Role", "arn:aws:iam::000000000000:role/role");
        
        ObjectNode targets = request.putObject("Targets");
        targets.putArray("S3Targets").addObject().put("Path", "s3://bucket/path/");

        assertEquals(200, handler.handle("CreateCrawler", request, REGION).getStatus());

        ObjectNode updateRequest = mapper.createObjectNode();
        updateRequest.put("Name", "test-crawler-update");
        updateRequest.put("Schedule", "cron(0 0 * * ? *)");

        Response response = handler.handle("UpdateCrawler", updateRequest, REGION);
        assertEquals(200, response.getStatus());

        Response getResponse = handler.handle("GetCrawler", mapper.createObjectNode().put("Name", "test-crawler-update"), REGION);
        assertEquals(200, getResponse.getStatus());
        JsonNode getBody = mapper.valueToTree(getResponse.getEntity());
        JsonNode crawler = getBody.get("Crawler");
        assertEquals("test-crawler-update", crawler.get("Name").asText());
        assertEquals(2, crawler.get("Version").asInt());
        assertTrue(crawler.has("Schedule"));
        assertEquals("cron(0 0 * * ? *)", crawler.get("Schedule").get("ScheduleExpression").asText());
    }

    @Test
    void createJobSucceeds() throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("Name", "test-job");
        request.put("Role", "arn:aws:iam::000000000000:role/role");
        
        ObjectNode command = request.putObject("Command");
        command.put("Name", "glueetl");
        command.put("ScriptLocation", "s3://bucket/script.py");

        Response response = handler.handle("CreateJob", request, REGION);
        assertEquals(200, response.getStatus());

        Response getResponse = handler.handle("GetJob", mapper.createObjectNode().put("JobName", "test-job"), REGION);
        assertEquals(200, getResponse.getStatus());
        JsonNode getBody = mapper.valueToTree(getResponse.getEntity());
        assertTrue(getBody.has("Job"));
        JsonNode job = getBody.get("Job");
        assertEquals("test-job", job.get("Name").asText());
        assertEquals("glueetl", job.get("Command").get("Name").asText());
    }

    private static final class InMemoryStorageFactory extends StorageFactory {
        private InMemoryStorageFactory() {
            super(null, null);
        }

        @Override
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                     String fileName,
                                                     TypeReference<Map<String, V>> typeReference) {
            return AccountAwareStorageBackend.inMemory("000000000000");
        }
    }
}
