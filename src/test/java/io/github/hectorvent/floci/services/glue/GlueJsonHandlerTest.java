package io.github.hectorvent.floci.services.glue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.glue.schemaregistry.GlueSchemaRegistryService;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.resourcegroupstagging.ResourceGroupsTaggingService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                storageFactory, schemaRegistryService, regionResolver, new ResourceGroupsTaggingService(storageFactory),
                new KmsService(storageFactory, regionResolver));
        GlueJobRunService jobRunService =
                new GlueJobRunService(new InMemoryStorage<>(), new InMemoryStorage<>(), glueService, 0, Clock.systemUTC());
        GlueCrawlerRunService crawlerRunService =
                new GlueCrawlerRunService(new InMemoryStorage<>(), new InMemoryStorage<>(), glueService, 0, Clock.systemUTC());
        handler = new GlueJsonHandler(glueService, jobRunService, crawlerRunService,
                new GlueTriggerService(new InMemoryStorage<>(), new InMemoryStorage<>(), glueService,
                        jobRunService, crawlerRunService),
                schemaRegistryService, mapper);
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
        assertTrue(((List<?>) body.get("PartitionIndexDescriptorList")).isEmpty());
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
        ArrayNode keys = tableInput.putArray("PartitionKeys");
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
        ArrayNode keyArray = index.putArray("Keys");
        for (String key : keys) {
            keyArray.add(key);
        }
        return handler.handle("CreatePartitionIndex", request, REGION);
    }

    private Response deleteIndex(String dbName, String tableName, String indexName) throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("DatabaseName", dbName);
        request.put("TableName", tableName);
        request.put("IndexName", indexName);
        return handler.handle("DeletePartitionIndex", request, REGION);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listIndexes(String dbName, String tableName) throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("DatabaseName", dbName);
        request.put("TableName", tableName);
        Response response = handler.handle("GetPartitionIndexes", request, REGION);
        assertEquals(200, response.getStatus());
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        return mapper.convertValue(
                body.get("PartitionIndexDescriptorList"), new TypeReference<List<Map<String, Object>>>() {});
    }

    /** Reads once to settle any in-progress index, mirroring a client that polls to ACTIVE. */
    private void settleIndexes(String dbName, String tableName) throws Exception {
        listIndexes(dbName, tableName);
    }

    private static String statusOf(List<Map<String, Object>> indexes, String indexName) {
        return indexes.stream()
                .filter(index -> indexName.equals(index.get("IndexName")))
                .map(index -> (String) index.get("IndexStatus"))
                .findFirst()
                .orElse(null);
    }

    // ── Partition indexes ──────────────────────────────────────────
    //
    // Behaviour below was captured against real Glue in us-west-2 on a table partitioned by
    // tenant/year/month/day, so the codes, messages and lifecycle states are AWS's own.

    @Test
    void aNewIndexReportsCreatingAndThenBecomesActive() throws Exception {
        createPartitionedTable("idx_db", "events", "tenant", "year", "month", "day");
        assertEquals(200, createIndex("idx_db", "events", "by_tenant", "tenant").getStatus());

        // Real Glue reports CREATING until the backfill finishes, then ACTIVE.
        List<Map<String, Object>> whileCreating = listIndexes("idx_db", "events");
        assertEquals("CREATING", statusOf(whileCreating, "by_tenant"));

        List<Map<String, Object>> settled = listIndexes("idx_db", "events");
        assertEquals("ACTIVE", statusOf(settled, "by_tenant"));
    }

    @Test
    void aReadResolvesEachKeyToItsNameAndType() throws Exception {
        createPartitionedTable("idx_db_keys_shape", "events", "tenant", "year");
        createIndex("idx_db_keys_shape", "events", "by_tenant", "tenant");

        List<Map<String, Object>> indexes = listIndexes("idx_db_keys_shape", "events");
        assertEquals(1, indexes.size());
        assertEquals("by_tenant", indexes.get(0).get("IndexName"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> keys = (List<Map<String, Object>>) indexes.get(0).get("Keys");
        // A request carries key names only; the read resolves each one's type from the table.
        assertEquals("tenant", keys.get(0).get("Name"));
        assertEquals("string", keys.get(0).get("Type"));
    }

    @Test
    void aDeletedIndexReportsDeletingAndThenDisappears() throws Exception {
        createPartitionedTable("idx_db_del", "events", "tenant", "year");
        createIndex("idx_db_del", "events", "by_tenant", "tenant");
        settleIndexes("idx_db_del", "events");

        assertEquals(200, deleteIndex("idx_db_del", "events", "by_tenant").getStatus());

        List<Map<String, Object>> whileDeleting = listIndexes("idx_db_del", "events");
        assertEquals("DELETING", statusOf(whileDeleting, "by_tenant"));

        assertTrue(listIndexes("idx_db_del", "events").isEmpty());
    }

    @Test
    void aSecondIndexCannotBeCreatedWhileOneIsCreating() throws Exception {
        createPartitionedTable("idx_db_busy", "events", "tenant", "year");
        createIndex("idx_db_busy", "events", "first", "tenant");

        AwsException ex = assertThrows(AwsException.class,
                () -> createIndex("idx_db_busy", "events", "second", "year"));
        assertEquals("ResourceNumberLimitExceededException", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("is in CREATING state"));
    }

    @Test
    void anIndexCannotBeCreatedWhileAnotherIsDeleting() throws Exception {
        createPartitionedTable("idx_db_busy2", "events", "tenant", "year");
        createIndex("idx_db_busy2", "events", "first", "tenant");
        settleIndexes("idx_db_busy2", "events");
        deleteIndex("idx_db_busy2", "events", "first");

        AwsException ex = assertThrows(AwsException.class,
                () -> createIndex("idx_db_busy2", "events", "second", "year"));
        assertEquals("ResourceNumberLimitExceededException", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("is in DELETING state"));
    }

    @Test
    void anIndexStillCreatingCannotBeDeleted() throws Exception {
        createPartitionedTable("idx_db_earlydel", "events", "tenant");
        createIndex("idx_db_earlydel", "events", "by_tenant", "tenant");

        // Measured in isolation: Glue reports it absent even while a read lists it as CREATING.
        AwsException ex = assertThrows(AwsException.class,
                () -> deleteIndex("idx_db_earlydel", "events", "by_tenant"));
        assertEquals("EntityNotFoundException", ex.getErrorCode());
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
        settleIndexes("idx_db_dup", "events");

        AwsException ex = assertThrows(AwsException.class,
                () -> createIndex("idx_db_dup", "events", "dup", "year"));
        assertEquals("AlreadyExistsException", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("same name"));
    }

    @Test
    void aSecondIndexOverTheSameKeysIsRefusedEvenUnderANewName() throws Exception {
        createPartitionedTable("idx_db_keys", "events", "tenant", "year");
        createIndex("idx_db_keys", "events", "first", "tenant");
        settleIndexes("idx_db_keys", "events");

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
        settleIndexes("idx_db_order", "events");
        assertEquals(200, createIndex("idx_db_order", "events", "ord2", "month", "year").getStatus());
        settleIndexes("idx_db_order", "events");
        assertEquals(2, listIndexes("idx_db_order", "events").size());
    }

    @Test
    void aFourthIndexExceedsTheLimit() throws Exception {
        createPartitionedTable("idx_db_cap", "events", "tenant", "year", "month", "day");
        createIndex("idx_db_cap", "events", "i1", "tenant");
        settleIndexes("idx_db_cap", "events");
        createIndex("idx_db_cap", "events", "i2", "year");
        settleIndexes("idx_db_cap", "events");
        createIndex("idx_db_cap", "events", "i3", "month");
        settleIndexes("idx_db_cap", "events");

        AwsException ex = assertThrows(AwsException.class,
                () -> createIndex("idx_db_cap", "events", "i4", "day"));
        assertEquals("ResourceNumberLimitExceededException", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("Maximum: 3"));
    }

    @Test
    void deletingAnUnknownIndexFails() throws Exception {
        createPartitionedTable("idx_db_missing", "events", "tenant");

        AwsException ex = assertThrows(AwsException.class,
                () -> deleteIndex("idx_db_missing", "events", "absent"));
        assertEquals("EntityNotFoundException", ex.getErrorCode());
    }

    @Test
    void droppingATableDropsItsIndexes() throws Exception {
        createPartitionedTable("idx_db_cascade", "events", "tenant");
        createIndex("idx_db_cascade", "events", "by_tenant", "tenant");
        settleIndexes("idx_db_cascade", "events");

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
    void classifierCrudPreservesPartialUpdatesAndMetadata() throws Exception {
        ObjectNode create = mapper.createObjectNode();
        create.putObject("GrokClassifier")
                .put("Name", "access-logs")
                .put("Classification", "apache")
                .put("GrokPattern", "%{COMMONAPACHELOG}")
                .put("CustomPatterns", "ORIGINAL value");
        assertEquals(200, handler.handle("CreateClassifier", create, REGION).getStatus());

        JsonNode created = mapper.valueToTree(body(handler.handle(
                "GetClassifier", mapper.createObjectNode().put("Name", "access-logs"), REGION)))
                .get("Classifier").get("GrokClassifier");
        assertEquals(1, created.get("Version").asLong());
        assertTrue(created.has("CreationTime"));
        assertTrue(created.has("LastUpdated"));

        ObjectNode update = mapper.createObjectNode();
        update.putObject("GrokClassifier")
                .put("Name", "access-logs")
                .put("Classification", "apache-updated");
        assertEquals(200, handler.handle("UpdateClassifier", update, REGION).getStatus());

        JsonNode updated = mapper.valueToTree(body(handler.handle(
                "GetClassifier", mapper.createObjectNode().put("Name", "access-logs"), REGION)))
                .get("Classifier").get("GrokClassifier");
        assertEquals("apache-updated", updated.get("Classification").asText());
        assertEquals("%{COMMONAPACHELOG}", updated.get("GrokPattern").asText());
        assertEquals("ORIGINAL value", updated.get("CustomPatterns").asText());
        assertEquals(created.get("CreationTime"), updated.get("CreationTime"));
        assertEquals(2, updated.get("Version").asLong());

        assertEquals(200, handler.handle(
                "DeleteClassifier", mapper.createObjectNode().put("Name", "access-logs"), REGION).getStatus());
        AwsException missing = assertThrows(AwsException.class, () -> handler.handle(
                "GetClassifier", mapper.createObjectNode().put("Name", "access-logs"), REGION));
        assertEquals("EntityNotFoundException", missing.getErrorCode());
    }

    @Test
    void allClassifierKindsRoundTripAndListsArePaginatedByName() throws Exception {
        ObjectNode grok = mapper.createObjectNode();
        grok.putObject("GrokClassifier")
                .put("Name", "d-grok")
                .put("Classification", "logs")
                .put("GrokPattern", "%{GREEDYDATA:message}");
        ObjectNode json = mapper.createObjectNode();
        json.putObject("JsonClassifier").put("Name", "b-json").put("JsonPath", "$.records[*]");
        ObjectNode xml = mapper.createObjectNode();
        xml.putObject("XMLClassifier")
                .put("Name", "a-xml")
                .put("Classification", "xml")
                .put("RowTag", "record");
        ObjectNode csv = mapper.createObjectNode();
        csv.putObject("CsvClassifier")
                .put("Name", "c-csv")
                .put("Delimiter", ",")
                .put("QuoteSymbol", "\"")
                .put("ContainsHeader", "PRESENT")
                .put("Serde", "OpenCSVSerDe")
                .putArray("CustomDatatypes").add("STRING").add("TIMESTAMP");
        assertEquals(200, handler.handle("CreateClassifier", grok, REGION).getStatus());
        assertEquals(200, handler.handle("CreateClassifier", json, REGION).getStatus());
        assertEquals(200, handler.handle("CreateClassifier", xml, REGION).getStatus());
        assertEquals(200, handler.handle("CreateClassifier", csv, REGION).getStatus());

        ObjectNode firstRequest = mapper.createObjectNode().put("MaxResults", 2);
        JsonNode first = mapper.valueToTree(body(handler.handle("GetClassifiers", firstRequest, REGION)));
        assertEquals(2, first.get("Classifiers").size());
        assertEquals("a-xml", first.get("Classifiers").get(0).get("XMLClassifier").get("Name").asText());
        assertEquals("b-json", first.get("Classifiers").get(1).get("JsonClassifier").get("Name").asText());
        assertEquals("2", first.get("NextToken").asText());

        ObjectNode secondRequest = mapper.createObjectNode().put("MaxResults", 2).put("NextToken", "2");
        JsonNode second = mapper.valueToTree(body(handler.handle("GetClassifiers", secondRequest, REGION)));
        assertEquals(2, second.get("Classifiers").size());
        assertEquals("c-csv", second.get("Classifiers").get(0).get("CsvClassifier").get("Name").asText());
        assertEquals("d-grok", second.get("Classifiers").get(1).get("GrokClassifier").get("Name").asText());
        assertTrue(!second.has("NextToken"));
    }

    @Test
    void classifierValidationRejectsAmbiguousAndInvalidRequests() throws Exception {
        ObjectNode ambiguous = mapper.createObjectNode();
        ambiguous.putObject("JsonClassifier").put("Name", "ambiguous").put("JsonPath", "$");
        ambiguous.putObject("XMLClassifier").put("Name", "ambiguous").put("Classification", "xml");
        assertClassifierError("InvalidInputException", "CreateClassifier", ambiguous);

        ObjectNode invalidCsv = mapper.createObjectNode();
        invalidCsv.putObject("CsvClassifier")
                .put("Name", "invalid-csv")
                .put("Delimiter", ",")
                .put("QuoteSymbol", ",")
                .put("ContainsHeader", "MAYBE");
        assertClassifierError("InvalidInputException", "CreateClassifier", invalidCsv);

        ObjectNode invalidGrok = mapper.createObjectNode();
        invalidGrok.putObject("GrokClassifier")
                .put("Name", "invalid-grok")
                .put("Classification", "logs");
        assertClassifierError("InvalidInputException", "CreateClassifier", invalidGrok);
    }

    @Test
    void classifierCreateUpdateAndDeleteUseAwsErrors() throws Exception {
        ObjectNode create = mapper.createObjectNode();
        create.putObject("JsonClassifier").put("Name", "events").put("JsonPath", "$.events[*]");
        assertEquals(200, handler.handle("CreateClassifier", create, REGION).getStatus());
        assertClassifierError("AlreadyExistsException", "CreateClassifier", create);

        ObjectNode wrongKind = mapper.createObjectNode();
        wrongKind.putObject("XMLClassifier").put("Name", "events").put("Classification", "xml");
        assertClassifierError("InvalidInputException", "UpdateClassifier", wrongKind);

        ObjectNode missing = mapper.createObjectNode();
        missing.putObject("JsonClassifier").put("Name", "missing").put("JsonPath", "$");
        assertClassifierError("EntityNotFoundException", "UpdateClassifier", missing);
        assertClassifierError("EntityNotFoundException", "DeleteClassifier",
                mapper.createObjectNode().put("Name", "missing"));
    }

    private void assertClassifierError(String errorCode, String action, JsonNode request) {
        AwsException exception = assertThrows(AwsException.class,
                () -> handler.handle(action, request, REGION));
        assertEquals(errorCode, exception.getErrorCode());
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(Response response) {
        assertEquals(200, response.getStatus());
        return (Map<String, Object>) response.getEntity();
    }

    /** The version calls answer with the reference's shapes: a TableVersion wrapper, per-version Errors. */
    @Test
    void tableVersionCallsUseTheReferenceShapes() throws Exception {
        createDatabaseAndTable("versions_db", "plain");
        ObjectNode update = mapper.createObjectNode();
        update.put("DatabaseName", "versions_db");
        update.putObject("TableInput").put("Name", "plain").put("Description", "second");
        assertEquals(200, handler.handle("UpdateTable", update, REGION).getStatus());

        ObjectNode get = mapper.createObjectNode();
        get.put("DatabaseName", "versions_db").put("TableName", "plain").put("VersionId", "0");
        Map<String, Object> version = (Map<String, Object>) body(handler.handle("GetTableVersion", get, REGION))
                .get("TableVersion");
        assertEquals("0", version.get("VersionId"));
        assertNotNull(version.get("Table"));

        ObjectNode batch = mapper.createObjectNode();
        batch.put("DatabaseName", "versions_db").put("TableName", "plain");
        batch.putArray("VersionIds").add("0").add("5");
        List<GlueService.TableVersionError> errors = (List<GlueService.TableVersionError>) body(
                handler.handle("BatchDeleteTableVersion", batch, REGION)).get("Errors");
        assertEquals(1, errors.size());
        assertEquals("5", errors.get(0).versionId());
        assertEquals("plain", errors.get(0).tableName());
        assertEquals("{\"TableName\":\"plain\",\"VersionId\":\"5\",\"ErrorDetail\":{\"ErrorCode\":\"EntityNotFoundException\",\"ErrorMessage\":\"Version not found.\"}}",
                mapper.writeValueAsString(errors.get(0)));

        ObjectNode delete = mapper.createObjectNode();
        delete.put("DatabaseName", "versions_db").put("TableName", "plain").put("VersionId", "0");
        AwsException gone = assertThrows(AwsException.class, () -> handler.handle("DeleteTableVersion", delete, REGION));
        assertEquals("EntityNotFoundException", gone.getErrorCode());
    }

    @Test
    void batchDeletePartitionReportsMissingPartitionsInErrors() throws Exception {
        createPartitionedTable("bdp_db", "events", "dt");
        ObjectNode create = mapper.createObjectNode();
        create.put("DatabaseName", "bdp_db").put("TableName", "events");
        create.putObject("PartitionInput").putArray("Values").add("2026-01-01");
        assertEquals(200, handler.handle("CreatePartition", create, REGION).getStatus());

        ObjectNode batch = mapper.createObjectNode();
        batch.put("DatabaseName", "bdp_db").put("TableName", "events");
        ArrayNode toDelete = batch.putArray("PartitionsToDelete");
        toDelete.addObject().putArray("Values").add("2026-01-01");
        toDelete.addObject().putArray("Values").add("2026-01-02");
        List<GlueService.BatchCreatePartitionError> errors = (List<GlueService.BatchCreatePartitionError>) body(
                handler.handle("BatchDeletePartition", batch, REGION)).get("Errors");
        assertEquals(1, errors.size());
        assertEquals(List.of("2026-01-02"), errors.get(0).partitionValues());
        assertTrue(mapper.writeValueAsString(errors.get(0)).startsWith("{\"PartitionValues\":[\"2026-01-02\"],\"ErrorDetail\":"));

        ObjectNode list = mapper.createObjectNode();
        list.put("DatabaseName", "bdp_db").put("TableName", "events");
        assertTrue(((List<?>) body(handler.handle("GetPartitions", list, REGION)).get("Partitions")).isEmpty());
    }

    @Test
    void searchTablesReturnsTableListWithPaging() throws Exception {
        createDatabaseAndTable("search_db", "alpha");
        createDatabaseAndTable("search_db2", "beta");

        ObjectNode search = mapper.createObjectNode();
        search.put("MaxResults", 1);
        Map<String, Object> first = body(handler.handle("SearchTables", search, REGION));
        assertEquals(1, ((List<?>) first.get("TableList")).size());
        assertNotNull(first.get("NextToken"));

        ObjectNode filtered = mapper.createObjectNode();
        filtered.putArray("Filters").addObject().put("Key", "DatabaseName").put("Value", "search_db2");
        filtered.putArray("SortCriteria").addObject().put("FieldName", "Name").put("Sort", "ASC");
        Map<String, Object> page = body(handler.handle("SearchTables", filtered, REGION));
        List<?> tables = (List<?>) page.get("TableList");
        assertEquals(1, tables.size());
        assertEquals("beta", ((io.github.hectorvent.floci.services.glue.model.Table) tables.get(0)).getName());
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

    private ObjectNode connectionInput(String name) {
        ObjectNode input = mapper.createObjectNode();
        input.put("Name", name);
        input.put("ConnectionType", "JDBC");
        input.put("Description", "orders db");
        input.putArray("MatchCriteria").add("orders");
        ObjectNode properties = input.putObject("ConnectionProperties");
        properties.put("JDBC_CONNECTION_URL", "jdbc:postgresql://db.internal:5432/orders");
        properties.put("USERNAME", "app");
        properties.put("PASSWORD", "s3cret");
        ObjectNode placement = input.putObject("PhysicalConnectionRequirements");
        placement.put("SubnetId", "subnet-0123456789abcdef0");
        placement.putArray("SecurityGroupIdList").add("sg-0123456789abcdef0");
        placement.put("AvailabilityZone", "us-east-1a");
        return input;
    }

    /**
     * The wire shape a Glue client reads back: CreateConnection answers with its status, and
     * GetConnection returns the definition under "Connection" with numeric timestamps and no
     * null-valued members, which is how the AWS SDK and the Terraform provider expect it.
     */
    @Test
    void createAndGetConnectionRoundTripTheDefinitionOnTheWire() throws Exception {
        ObjectNode create = mapper.createObjectNode();
        create.set("ConnectionInput", connectionInput("orders"));
        create.putObject("Tags").put("env", "dev");

        Response created = handler.handle("CreateConnection", create, REGION);
        assertEquals(200, created.getStatus());
        assertEquals("READY", mapper.valueToTree(created.getEntity()).get("CreateConnectionStatus").asText());

        Response got = handler.handle("GetConnection", mapper.createObjectNode().put("Name", "orders"), REGION);
        assertEquals(200, got.getStatus());
        JsonNode connection = mapper.valueToTree(got.getEntity()).get("Connection");
        assertEquals("orders", connection.get("Name").asText());
        assertEquals("JDBC", connection.get("ConnectionType").asText());
        assertEquals("s3cret", connection.get("ConnectionProperties").get("PASSWORD").asText());
        assertEquals("subnet-0123456789abcdef0", connection.get("PhysicalConnectionRequirements").get("SubnetId").asText());
        assertEquals("READY", connection.get("Status").asText());
        assertEquals(1, connection.get("ConnectionSchemaVersion").asInt());
        assertTrue(connection.get("CreationTime").isNumber());
        assertTrue(connection.get("LastUpdatedTime").isNumber());
        assertFalse(connection.has("LastUpdatedBy"));
        assertFalse(connection.has("AuthenticationConfiguration"));

        ObjectNode tags = mapper.createObjectNode();
        tags.put("ResourceArn", "arn:aws:glue:" + REGION + ":" + ACCOUNT_ID + ":connection/orders");
        JsonNode tagBody = mapper.valueToTree(handler.handle("GetTags", tags, REGION).getEntity());
        assertEquals("dev", tagBody.get("Tags").get("env").asText());
    }

    @Test
    void getConnectionsHonoursHidePasswordAndTheFilter() throws Exception {
        ObjectNode create = mapper.createObjectNode();
        create.set("ConnectionInput", connectionInput("orders"));
        handler.handle("CreateConnection", create, REGION);
        ObjectNode kafkaInput = connectionInput("events");
        kafkaInput.put("ConnectionType", "KAFKA");
        kafkaInput.putObject("ConnectionProperties").put("KAFKA_BOOTSTRAP_SERVERS", "broker:9092");
        ObjectNode createKafka = mapper.createObjectNode();
        createKafka.set("ConnectionInput", kafkaInput);
        handler.handle("CreateConnection", createKafka, REGION);

        ObjectNode list = mapper.createObjectNode();
        list.put("HidePassword", true);
        list.putObject("Filter").put("ConnectionType", "JDBC");
        JsonNode body = mapper.valueToTree(handler.handle("GetConnections", list, REGION).getEntity());
        assertEquals(1, body.get("ConnectionList").size());
        JsonNode orders = body.get("ConnectionList").get(0);
        assertEquals("orders", orders.get("Name").asText());
        assertFalse(orders.get("ConnectionProperties").has("PASSWORD"));
        assertEquals("app", orders.get("ConnectionProperties").get("USERNAME").asText());
        assertFalse(body.has("NextToken"));
    }

    @Test
    void updateDeleteAndBatchDeleteConnectionAnswerWithTheDocumentedBodies() throws Exception {
        ObjectNode create = mapper.createObjectNode();
        create.set("ConnectionInput", connectionInput("orders"));
        handler.handle("CreateConnection", create, REGION);

        ObjectNode update = mapper.createObjectNode();
        update.put("Name", "orders");
        ObjectNode redefinition = connectionInput("orders");
        redefinition.remove("Description");
        update.set("ConnectionInput", redefinition);
        Response updated = handler.handle("UpdateConnection", update, REGION);
        assertEquals(200, updated.getStatus());
        assertEquals(0, mapper.valueToTree(updated.getEntity()).size());
        JsonNode afterUpdate = mapper.valueToTree(
                handler.handle("GetConnection", mapper.createObjectNode().put("Name", "orders"), REGION).getEntity())
                .get("Connection");
        assertFalse(afterUpdate.has("Description"));

        ObjectNode batch = mapper.createObjectNode();
        batch.putArray("ConnectionNameList").add("orders").add("absent");
        JsonNode batchBody = mapper.valueToTree(handler.handle("BatchDeleteConnection", batch, REGION).getEntity());
        assertEquals("orders", batchBody.get("Succeeded").get(0).asText());
        assertEquals("EntityNotFoundException", batchBody.get("Errors").get("absent").get("ErrorCode").asText());

        AwsException gone = assertThrows(AwsException.class, () -> handler.handle(
                "DeleteConnection", mapper.createObjectNode().put("ConnectionName", "orders"), REGION));
        assertEquals("EntityNotFoundException", gone.getErrorCode());

        ObjectNode test = mapper.createObjectNode();
        ObjectNode inline = test.putObject("TestConnectionInput");
        inline.put("ConnectionType", "JDBC");
        inline.putObject("ConnectionProperties").put("JDBC_CONNECTION_URL", "jdbc:mysql://h:3306/d");
        assertEquals(200, handler.handle("TestConnection", test, REGION).getStatus());
    }

    @Test
    void resourcePolicyOperationsAnswerWithTheDocumentedBodies() throws Exception {
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        ObjectNode put = mapper.createObjectNode();
        put.put("PolicyInJson", policy);
        put.put("PolicyExistsCondition", "NOT_EXIST");
        JsonNode putBody = mapper.valueToTree(handler.handle("PutResourcePolicy", put, REGION).getEntity());
        String hash = putBody.get("PolicyHash").asText();
        assertFalse(hash.isBlank());

        JsonNode got = mapper.valueToTree(handler.handle("GetResourcePolicy", mapper.createObjectNode(), REGION).getEntity());
        assertEquals(policy, got.get("PolicyInJson").asText());
        assertEquals(hash, got.get("PolicyHash").asText());
        assertTrue(got.get("CreateTime").isNumber());
        assertTrue(got.get("UpdateTime").isNumber());

        JsonNode list = mapper.valueToTree(handler.handle("GetResourcePolicies", mapper.createObjectNode(), REGION).getEntity());
        assertEquals(1, list.get("GetResourcePoliciesResponseList").size());
        assertEquals(hash, list.get("GetResourcePoliciesResponseList").get(0).get("PolicyHash").asText());
        assertFalse(list.has("NextToken"));

        AwsException conflict = assertThrows(AwsException.class, () -> handler.handle("PutResourcePolicy", put, REGION));
        assertEquals("ConditionCheckFailureException", conflict.getErrorCode());

        Response deleted = handler.handle("DeleteResourcePolicy", mapper.createObjectNode(), REGION);
        assertEquals(0, mapper.valueToTree(deleted.getEntity()).size());
        AwsException gone = assertThrows(AwsException.class,
                () -> handler.handle("GetResourcePolicy", mapper.createObjectNode(), REGION));
        assertEquals("EntityNotFoundException", gone.getErrorCode());
    }

    @Test
    void encryptionSettingsReportBothBlocksBeforeAndAfterAPut() throws Exception {
        JsonNode defaults = mapper.valueToTree(
                handler.handle("GetDataCatalogEncryptionSettings", mapper.createObjectNode(), REGION).getEntity());
        JsonNode settings = defaults.get("DataCatalogEncryptionSettings");
        assertEquals("DISABLED", settings.get("EncryptionAtRest").get("CatalogEncryptionMode").asText());
        assertFalse(settings.get("EncryptionAtRest").has("SseAwsKmsKeyId"));
        assertFalse(settings.get("ConnectionPasswordEncryption").get("ReturnConnectionPasswordEncrypted").asBoolean());

        ObjectNode put = mapper.createObjectNode();
        ObjectNode block = put.putObject("DataCatalogEncryptionSettings").putObject("ConnectionPasswordEncryption");
        block.put("ReturnConnectionPasswordEncrypted", true);
        block.put("AwsKmsKeyId", "alias/glue");
        assertEquals(0, mapper.valueToTree(handler.handle("PutDataCatalogEncryptionSettings", put, REGION).getEntity()).size());

        JsonNode after = mapper.valueToTree(
                handler.handle("GetDataCatalogEncryptionSettings", mapper.createObjectNode(), REGION).getEntity())
                .get("DataCatalogEncryptionSettings");
        assertTrue(after.get("ConnectionPasswordEncryption").get("ReturnConnectionPasswordEncrypted").asBoolean());
        assertEquals("alias/glue", after.get("ConnectionPasswordEncryption").get("AwsKmsKeyId").asText());
        assertEquals("DISABLED", after.get("EncryptionAtRest").get("CatalogEncryptionMode").asText());
    }

    private void createJobWithTags(String name, Map<String, String> tags) throws Exception {
        ObjectNode create = mapper.createObjectNode();
        create.put("Name", name);
        create.put("Role", "arn:aws:iam::000000000000:role/glue");
        create.putObject("Command").put("Name", "glueetl");
        ObjectNode tagNode = create.putObject("Tags");
        tags.forEach(tagNode::put);
        assertEquals(200, handler.handle("CreateJob", create, REGION).getStatus());
    }

    /**
     * The wire shape a job run client reads: StartJobRun answers with the run id alone, GetJobRun
     * nests the run under "JobRun" with numeric timestamps, and stopping a finished run is an entry
     * in Errors rather than a failed request.
     */
    @Test
    void jobRunOperationsAnswerWithTheDocumentedBodies() throws Exception {
        createJobWithTags("nightly", Map.of());

        ObjectNode start = mapper.createObjectNode();
        start.put("JobName", "nightly");
        start.putObject("Arguments").put("--day", "2026-09-25");
        JsonNode started = mapper.valueToTree(handler.handle("StartJobRun", start, REGION).getEntity());
        assertEquals(1, started.size());
        String runId = started.get("JobRunId").asText();

        ObjectNode get = mapper.createObjectNode().put("JobName", "nightly").put("RunId", runId);
        JsonNode run = mapper.valueToTree(handler.handle("GetJobRun", get, REGION).getEntity()).get("JobRun");
        assertEquals(runId, run.get("Id").asText());
        assertEquals("nightly", run.get("JobName").asText());
        assertEquals("SUCCEEDED", run.get("JobRunState").asText());
        assertEquals("2026-09-25", run.get("Arguments").get("--day").asText());
        assertTrue(run.get("StartedOn").isNumber());
        assertTrue(run.get("CompletedOn").isNumber());
        assertFalse(run.has("ErrorMessage"));

        JsonNode runs = mapper.valueToTree(handler.handle(
                "GetJobRuns", mapper.createObjectNode().put("JobName", "nightly"), REGION).getEntity());
        assertEquals(1, runs.get("JobRuns").size());
        assertFalse(runs.has("NextToken"));

        ObjectNode stop = mapper.createObjectNode().put("JobName", "nightly");
        stop.putArray("JobRunIds").add(runId);
        JsonNode stopped = mapper.valueToTree(handler.handle("BatchStopJobRun", stop, REGION).getEntity());
        assertEquals(0, stopped.get("SuccessfulSubmissions").size());
        JsonNode error = stopped.get("Errors").get(0);
        assertEquals("nightly", error.get("JobName").asText());
        assertEquals(runId, error.get("JobRunId").asText());
        assertEquals("InvalidInputException", error.get("ErrorDetail").get("ErrorCode").asText());

        handler.handle("DeleteJob", mapper.createObjectNode().put("JobName", "nightly"), REGION);
        AwsException gone = assertThrows(AwsException.class, () -> handler.handle(
                "GetJobRuns", mapper.createObjectNode().put("JobName", "nightly"), REGION));
        assertEquals("EntityNotFoundException", gone.getErrorCode());
    }

    @Test
    void listJobsFiltersOnTagsAndBatchGetJobsReportsMissingNames() throws Exception {
        createJobWithTags("tagged", Map.of("team", "data"));
        createJobWithTags("plain", Map.of());

        JsonNode all = mapper.valueToTree(handler.handle("ListJobs", mapper.createObjectNode(), REGION).getEntity());
        assertEquals(List.of("plain", "tagged"), mapper.convertValue(all.get("JobNames"), List.class));
        assertFalse(all.has("NextToken"));

        ObjectNode filtered = mapper.createObjectNode();
        filtered.putObject("Tags").put("team", "data");
        JsonNode byTag = mapper.valueToTree(handler.handle("ListJobs", filtered, REGION).getEntity());
        assertEquals(List.of("tagged"), mapper.convertValue(byTag.get("JobNames"), List.class));

        ObjectNode batch = mapper.createObjectNode();
        batch.putArray("JobNames").add("tagged").add("absent");
        JsonNode got = mapper.valueToTree(handler.handle("BatchGetJobs", batch, REGION).getEntity());
        assertEquals(1, got.get("Jobs").size());
        assertEquals("tagged", got.get("Jobs").get(0).get("Name").asText());
        assertEquals("absent", got.get("JobsNotFound").get(0).asText());
    }

    private void createCrawlerWithTags(String name, Map<String, String> tags) throws Exception {
        ObjectNode create = mapper.createObjectNode();
        create.put("Name", name);
        create.put("Role", "arn:aws:iam::000000000000:role/glue");
        create.putObject("Targets").putArray("S3Targets").addObject().put("Path", "s3://raw/" + name);
        create.put("Schedule", "cron(0 2 * * ? *)");
        ObjectNode tagNode = create.putObject("Tags");
        tags.forEach(tagNode::put);
        assertEquals(200, handler.handle("CreateCrawler", create, REGION).getStatus());
    }

    /**
     * The crawl lifecycle as a client polls it: StartCrawler and StopCrawler answer with empty
     * bodies, GetCrawler carries State and a LastCrawl with a numeric StartTime, and
     * GetCrawlerMetrics reports numbers, never nulls.
     */
    @Test
    void crawlerRunOperationsAnswerWithTheDocumentedBodies() throws Exception {
        createCrawlerWithTags("raw", Map.of());
        ObjectNode byName = mapper.createObjectNode().put("Name", "raw");

        JsonNode before = mapper.valueToTree(handler.handle("GetCrawler", byName, REGION).getEntity()).get("Crawler");
        assertEquals("READY", before.get("State").asText());

        Response started = handler.handle("StartCrawler", byName, REGION);
        assertEquals(0, mapper.valueToTree(started.getEntity()).size());

        JsonNode crawler = mapper.valueToTree(handler.handle("GetCrawler", byName, REGION).getEntity()).get("Crawler");
        assertEquals("READY", crawler.get("State").asText());
        assertEquals("SUCCEEDED", crawler.get("LastCrawl").get("Status").asText());
        assertTrue(crawler.get("LastCrawl").get("StartTime").isNumber());

        AwsException idle = assertThrows(AwsException.class, () -> handler.handle("StopCrawler", byName, REGION));
        assertEquals("CrawlerNotRunningException", idle.getErrorCode());

        ObjectNode metricsRequest = mapper.createObjectNode();
        metricsRequest.putArray("CrawlerNameList").add("raw");
        JsonNode metrics = mapper.valueToTree(handler.handle("GetCrawlerMetrics", metricsRequest, REGION).getEntity())
                .get("CrawlerMetricsList").get(0);
        assertEquals("raw", metrics.get("CrawlerName").asText());
        assertTrue(metrics.get("TimeLeftSeconds").isNumber());
        assertTrue(metrics.get("MedianRuntimeSeconds").isNumber());
        assertFalse(metrics.get("StillEstimating").asBoolean());

        ObjectNode schedule = mapper.createObjectNode().put("CrawlerName", "raw");
        assertEquals(0, mapper.valueToTree(handler.handle("StopCrawlerSchedule", schedule, REGION).getEntity()).size());
        JsonNode stopped = mapper.valueToTree(handler.handle("GetCrawler", byName, REGION).getEntity()).get("Crawler");
        assertEquals("NOT_SCHEDULED", stopped.get("Schedule").get("State").asText());
    }

    @Test
    void listCrawlersFiltersOnTagsAndBatchGetCrawlersReportsMissingNames() throws Exception {
        createCrawlerWithTags("tagged", Map.of("team", "data"));
        createCrawlerWithTags("plain", Map.of());

        JsonNode all = mapper.valueToTree(handler.handle("ListCrawlers", mapper.createObjectNode(), REGION).getEntity());
        assertEquals(List.of("plain", "tagged"), mapper.convertValue(all.get("CrawlerNames"), List.class));

        ObjectNode filtered = mapper.createObjectNode();
        filtered.putObject("Tags").put("team", "data");
        JsonNode byTag = mapper.valueToTree(handler.handle("ListCrawlers", filtered, REGION).getEntity());
        assertEquals(List.of("tagged"), mapper.convertValue(byTag.get("CrawlerNames"), List.class));

        ObjectNode batch = mapper.createObjectNode();
        batch.putArray("CrawlerNames").add("tagged").add("absent");
        JsonNode got = mapper.valueToTree(handler.handle("BatchGetCrawlers", batch, REGION).getEntity());
        assertEquals("tagged", got.get("Crawlers").get(0).get("Name").asText());
        assertEquals("READY", got.get("Crawlers").get(0).get("State").asText());
        assertEquals("absent", got.get("CrawlersNotFound").get(0).asText());
    }

    /**
     * Trigger CRUD on the wire: Create, Start, Stop and Delete answer with the name, Get and
     * UpdateTrigger with the trigger, and the definition round trips with its predicate.
     */
    @Test
    void triggerOperationsAnswerWithTheDocumentedBodies() throws Exception {
        createJobWithTags("extract", Map.of());
        createJobWithTags("load", Map.of());
        ObjectNode create = mapper.createObjectNode();
        create.put("Name", "after-extract");
        create.put("Type", "CONDITIONAL");
        create.put("StartOnCreation", true);
        create.putArray("Actions").addObject().put("JobName", "load");
        ObjectNode predicate = create.putObject("Predicate");
        predicate.put("Logical", "AND");
        predicate.putArray("Conditions").addObject()
                .put("LogicalOperator", "EQUALS").put("JobName", "extract").put("State", "SUCCEEDED");
        create.putObject("Tags").put("team", "data");

        JsonNode created = mapper.valueToTree(handler.handle("CreateTrigger", create, REGION).getEntity());
        assertEquals("after-extract", created.get("Name").asText());

        ObjectNode byName = mapper.createObjectNode().put("Name", "after-extract");
        JsonNode trigger = mapper.valueToTree(handler.handle("GetTrigger", byName, REGION).getEntity()).get("Trigger");
        assertEquals("CONDITIONAL", trigger.get("Type").asText());
        assertEquals("ACTIVATED", trigger.get("State").asText());
        assertEquals("load", trigger.get("Actions").get(0).get("JobName").asText());
        assertEquals("SUCCEEDED", trigger.get("Predicate").get("Conditions").get(0).get("State").asText());
        assertFalse(trigger.has("Schedule"));

        ObjectNode tags = mapper.createObjectNode();
        tags.put("ResourceArn", "arn:aws:glue:" + REGION + ":" + ACCOUNT_ID + ":trigger/after-extract");
        assertEquals("data", mapper.valueToTree(handler.handle("GetTags", tags, REGION).getEntity())
                .get("Tags").get("team").asText());

        ObjectNode update = mapper.createObjectNode().put("Name", "after-extract");
        update.putObject("TriggerUpdate").put("Description", "load after extract");
        JsonNode updated = mapper.valueToTree(handler.handle("UpdateTrigger", update, REGION).getEntity());
        assertEquals("load after extract", updated.get("Trigger").get("Description").asText());

        assertEquals("after-extract", mapper.valueToTree(
                handler.handle("StopTrigger", byName, REGION).getEntity()).get("Name").asText());
        assertEquals("after-extract", mapper.valueToTree(
                handler.handle("DeleteTrigger", byName, REGION).getEntity()).get("Name").asText());
        JsonNode names = mapper.valueToTree(handler.handle("ListTriggers", mapper.createObjectNode(), REGION).getEntity());
        assertEquals(0, names.get("TriggerNames").size());
    }

    /** A conditional trigger fires within the request that finishes the run it watches. */
    @Test
    void conditionalTriggerFiresAfterTheRequestThatFinishesTheWatchedRun() throws Exception {
        createJobWithTags("extract", Map.of());
        createJobWithTags("load", Map.of());
        ObjectNode create = mapper.createObjectNode();
        create.put("Name", "after-extract");
        create.put("Type", "CONDITIONAL");
        create.put("StartOnCreation", true);
        create.putArray("Actions").addObject().put("JobName", "load");
        create.putObject("Predicate").putArray("Conditions").addObject()
                .put("LogicalOperator", "EQUALS").put("JobName", "extract").put("State", "SUCCEEDED");
        handler.handle("CreateTrigger", create, REGION);

        handler.handle("StartJobRun", mapper.createObjectNode().put("JobName", "extract"), REGION);

        JsonNode runs = mapper.valueToTree(handler.handle(
                "GetJobRuns", mapper.createObjectNode().put("JobName", "load"), REGION).getEntity()).get("JobRuns");
        assertEquals(1, runs.size());
        assertEquals("after-extract", runs.get(0).get("TriggerName").asText());
    }

    /**
     * Workflow operations on the wire: CreateWorkflow answers with the name, StartWorkflowRun with the
     * run id, GetWorkflowRun nests the run under "Run" with numeric timestamps and Statistics, and
     * GetWorkflow with IncludeGraph carries Nodes and Edges.
     */
    @Test
    void workflowOperationsAnswerWithTheDocumentedBodies() throws Exception {
        createJobWithTags("extract", Map.of());
        createJobWithTags("load", Map.of());
        ObjectNode create = mapper.createObjectNode();
        create.put("Name", "etl");
        create.putObject("DefaultRunProperties").put("env", "dev");
        create.putObject("Tags").put("team", "data");
        assertEquals("etl", mapper.valueToTree(handler.handle("CreateWorkflow", create, REGION).getEntity())
                .get("Name").asText());
        ObjectNode start = mapper.createObjectNode();
        start.put("Name", "start");
        start.put("Type", "ON_DEMAND");
        start.put("WorkflowName", "etl");
        start.putArray("Actions").addObject().put("JobName", "extract");
        handler.handle("CreateTrigger", start, REGION);
        ObjectNode then = mapper.createObjectNode();
        then.put("Name", "then-load");
        then.put("Type", "CONDITIONAL");
        then.put("WorkflowName", "etl");
        then.put("StartOnCreation", true);
        then.putArray("Actions").addObject().put("JobName", "load");
        then.putObject("Predicate").putArray("Conditions").addObject()
                .put("LogicalOperator", "EQUALS").put("JobName", "extract").put("State", "SUCCEEDED");
        handler.handle("CreateTrigger", then, REGION);

        ObjectNode startRun = mapper.createObjectNode().put("Name", "etl");
        String runId = mapper.valueToTree(handler.handle("StartWorkflowRun", startRun, REGION).getEntity())
                .get("RunId").asText();

        ObjectNode getRun = mapper.createObjectNode().put("Name", "etl").put("RunId", runId);
        JsonNode run = mapper.valueToTree(handler.handle("GetWorkflowRun", getRun, REGION).getEntity()).get("Run");
        assertEquals("COMPLETED", run.get("Status").asText());
        assertEquals("etl", run.get("Name").asText());
        assertTrue(run.get("StartedOn").isNumber());
        assertTrue(run.get("CompletedOn").isNumber());
        assertEquals(2, run.get("Statistics").get("SucceededActions").asInt());
        assertEquals("dev", run.get("WorkflowRunProperties").get("env").asText());
        assertFalse(run.has("Graph"));

        ObjectNode getWorkflow = mapper.createObjectNode().put("Name", "etl").put("IncludeGraph", true);
        JsonNode workflow = mapper.valueToTree(handler.handle("GetWorkflow", getWorkflow, REGION).getEntity())
                .get("Workflow");
        assertEquals(runId, workflow.get("LastRun").get("WorkflowRunId").asText());
        assertEquals(4, workflow.get("Graph").get("Nodes").size());
        assertEquals(3, workflow.get("Graph").get("Edges").size());
        assertTrue(workflow.get("CreatedOn").isNumber());

        JsonNode runs = mapper.valueToTree(handler.handle(
                "GetWorkflowRuns", mapper.createObjectNode().put("Name", "etl"), REGION).getEntity());
        assertEquals(1, runs.get("Runs").size());
        JsonNode names = mapper.valueToTree(handler.handle("ListWorkflows", mapper.createObjectNode(), REGION).getEntity());
        assertEquals("etl", names.get("Workflows").get(0).asText());

        ObjectNode put = mapper.createObjectNode().put("Name", "etl").put("RunId", runId);
        put.putObject("RunProperties").put("rows", "42");
        assertEquals(0, mapper.valueToTree(handler.handle("PutWorkflowRunProperties", put, REGION).getEntity()).size());
        JsonNode properties = mapper.valueToTree(handler.handle("GetWorkflowRunProperties", getRun, REGION).getEntity());
        assertEquals("42", properties.get("RunProperties").get("rows").asText());

        ObjectNode tags = mapper.createObjectNode();
        tags.put("ResourceArn", "arn:aws:glue:" + REGION + ":" + ACCOUNT_ID + ":workflow/etl");
        assertEquals("data", mapper.valueToTree(handler.handle("GetTags", tags, REGION).getEntity())
                .get("Tags").get("team").asText());

        ObjectNode batch = mapper.createObjectNode();
        batch.putArray("Names").add("etl").add("absent");
        JsonNode got = mapper.valueToTree(handler.handle("BatchGetWorkflows", batch, REGION).getEntity());
        assertEquals("etl", got.get("Workflows").get(0).get("Name").asText());
        assertEquals("absent", got.get("MissingWorkflows").get(0).asText());

        assertEquals("etl", mapper.valueToTree(handler.handle(
                "DeleteWorkflow", mapper.createObjectNode().put("Name", "etl"), REGION).getEntity()).get("Name").asText());
    }
}
