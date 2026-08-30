package io.github.hectorvent.floci.services.rdsdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RdsDataServiceTest {

    private static final String RESOURCE_ARN = "arn:aws:rds:us-east-1:000000000000:cluster:test";
    private static final String FALLBACK_RESOURCE_ARN = "arn:aws:rds:us-west-2:111111111111:cluster:test";
    private static final String OTHER_RESOURCE_ARN = "arn:aws:rds:us-east-1:000000000000:cluster:other";
    private static final String UNKNOWN_RESOURCE_ARN = "arn:aws:rds:us-east-1:000000000000:cluster:missing";
    private static final String SECRET_ARN = "arn:aws:secretsmanager:us-east-1:000000000000:secret:local/rds-data";
    private static final String REGION = "us-east-1";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void executesSqlAndMapsDataApiResultShape() throws Exception {
        TestHarness harness = new TestHarness();
        harness.createTables();

        ObjectNode insert = harness.request("""
                insert into data_api_items(id, title, score, payload, active, created_at)
                values ('s1', 'First', 42, X'010203', true, timestamp '2026-06-09 12:34:56.123456789')
                """);
        ObjectNode insertResponse = harness.service.executeStatement(insert, REGION);
        assertEquals(1L, insertResponse.get("numberOfRecordsUpdated").asLong());

        ObjectNode select = harness.request("""
                select title as title, score as score, payload as payload, null as nothing,
                       active as active, created_at as created_at
                from data_api_items where id = 's1'
                """);
        select.put("includeResultMetadata", true);
        ObjectNode selectResponse = harness.service.executeStatement(select, REGION);

        ArrayNode metadata = (ArrayNode) selectResponse.get("columnMetadata");
        assertEquals("title", metadata.get(0).get("name").asText().toLowerCase());
        assertEquals("score", metadata.get(1).get("name").asText().toLowerCase());

        ArrayNode row = (ArrayNode) selectResponse.get("records").get(0);
        assertEquals("First", row.get(0).get("stringValue").asText());
        assertEquals(42L, row.get(1).get("longValue").asLong());
        assertArrayEquals(new byte[] {1, 2, 3}, row.get(2).get("blobValue").binaryValue());
        assertTrue(row.get(3).get("isNull").asBoolean());
        assertTrue(row.get(4).get("booleanValue").asBoolean());
        assertEquals("2026-06-09 12:34:56.123456789", row.get(5).get("stringValue").asText());
        assertEquals(0L, selectResponse.get("numberOfRecordsUpdated").asLong());
    }

    @Test
    void emitsEveryColumnMetadataFieldAwsClientsRead() throws Exception {
        TestHarness harness = new TestHarness();
        harness.createTables();
        harness.service.executeStatement(harness.request(
                "insert into data_api_items(id, title, score) values ('meta', 'Meta', 3)"), REGION);

        ObjectNode select = harness.request(
                "select title as aliased_title, score from data_api_items where id = 'meta'");
        select.put("includeResultMetadata", true);
        ObjectNode response = harness.service.executeStatement(select, REGION);

        ArrayNode metadata = (ArrayNode) response.get("columnMetadata");
        JsonNode aliased = metadata.get(0);
        assertEquals("aliased_title", aliased.get("label").asText().toLowerCase());
        assertEquals("title", aliased.get("name").asText().toLowerCase());
        assertEquals(Types.VARCHAR, aliased.get("type").asInt());
        assertFalse(aliased.get("typeName").asText().isBlank());
        assertEquals("data_api_items", aliased.get("tableName").asText().toLowerCase());
        assertEquals(ResultSetMetaData.columnNullable, aliased.get("nullable").asInt());
        assertEquals(255, aliased.get("precision").asInt());
        assertEquals(0, aliased.get("scale").asInt());
        assertEquals(0, aliased.get("arrayBaseColumnType").asInt());
        assertFalse(aliased.get("isAutoIncrement").asBoolean());
        assertFalse(aliased.get("isCurrency").asBoolean());
        assertTrue(aliased.has("isCaseSensitive"));
        assertTrue(aliased.has("schemaName"));

        JsonNode score = metadata.get(1);
        assertEquals("score", score.get("label").asText().toLowerCase());
        assertEquals("score", score.get("name").asText().toLowerCase());
        assertEquals(Types.BIGINT, score.get("type").asInt());
        assertTrue(score.get("isSigned").asBoolean());

        assertFalse(response.has("generatedFields"));
    }

    @Test
    void returnsGeneratedFieldsForAutoIncrementInserts() throws Exception {
        TestHarness harness = new TestHarness();
        harness.createTables();

        ObjectNode response = harness.service.executeStatement(
                harness.request("insert into data_api_events(name) values ('created')"), REGION);

        assertEquals(1L, response.get("numberOfRecordsUpdated").asLong());
        ArrayNode generatedFields = (ArrayNode) response.get("generatedFields");
        assertEquals(1, generatedFields.size());
        assertTrue(generatedFields.get(0).get("longValue").asLong() > 0);
    }

    @Test
    void batchExecuteStatementRunsEveryParameterSet() throws Exception {
        TestHarness harness = new TestHarness();
        harness.createTables();

        ObjectNode batch = harness.request("insert into data_api_events(name) values (:name)");
        batch.set("parameterSets", parameterSets("first", "second"));

        ObjectNode response = harness.service.batchExecuteStatement(batch, REGION);

        ArrayNode updateResults = (ArrayNode) response.get("updateResults");
        assertEquals(2, updateResults.size());
        long firstId = updateResults.get(0).get("generatedFields").get(0).get("longValue").asLong();
        long secondId = updateResults.get(1).get("generatedFields").get(0).get("longValue").asLong();
        assertTrue(secondId > firstId);
        assertFalse(response.has("records"));
        assertEquals(2L, harness.countEvents());
    }

    @Test
    void batchExecuteStatementKeepsGeneratedKeysWithTheirParameterSet() throws Exception {
        TestHarness harness = new TestHarness();
        harness.createTables();

        ObjectNode batch = harness.request(
                "insert into data_api_events(name) values (:first), (:second)");
        ArrayNode sets = objectMapper.createArrayNode();
        sets.add(objectMapper.createArrayNode()
                .add(stringParam("first", "one-a"))
                .add(stringParam("second", "one-b")));
        sets.add(objectMapper.createArrayNode()
                .add(stringParam("first", "two-a"))
                .add(stringParam("second", "two-b")));
        batch.set("parameterSets", sets);

        ObjectNode response = harness.service.batchExecuteStatement(batch, REGION);

        ArrayNode updateResults = (ArrayNode) response.get("updateResults");
        assertEquals(2, updateResults.size());
        ArrayNode firstKeys = (ArrayNode) updateResults.get(0).get("generatedFields");
        ArrayNode secondKeys = (ArrayNode) updateResults.get(1).get("generatedFields");
        assertEquals(2, firstKeys.size());
        assertEquals(2, secondKeys.size());
        assertTrue(secondKeys.get(0).get("longValue").asLong()
                > firstKeys.get(1).get("longValue").asLong());
        assertEquals(4L, harness.countEvents());
    }

    @Test
    void batchExecuteStatementHonorsAnOpenTransaction() throws Exception {
        TestHarness harness = new TestHarness();
        harness.createTables();

        String transactionId = harness.service.beginTransaction(harness.beginRequest(), REGION)
                .get("transactionId").asText();
        ObjectNode batch = harness.request("insert into data_api_events(name) values (:name)");
        batch.set("parameterSets", parameterSets("rolled", "back"));
        batch.put("transactionId", transactionId);
        harness.service.batchExecuteStatement(batch, REGION);

        harness.service.rollbackTransaction(harness.transactionRequest(transactionId), REGION);

        assertEquals(0L, harness.countEvents());
    }

    @Test
    void batchExecuteStatementIgnoresExecuteStatementOnlyResultOptions() throws Exception {
        TestHarness harness = new TestHarness();
        harness.createTables();

        ObjectNode batch = harness.request("insert into data_api_events(name) values (:name)");
        batch.set("parameterSets", parameterSets("ignored-options"));
        batch.put("formatRecordsAs", "JSON");
        batch.set("resultSetOptions", objectMapper.createObjectNode().put("decimalReturnType", "STRING"));

        ObjectNode response = harness.service.batchExecuteStatement(batch, REGION);

        assertEquals(1, response.get("updateResults").size());
        assertEquals(1L, harness.countEvents());
    }

    @Test
    void batchExecuteStatementRunsNothingWithoutParameterSets() throws Exception {
        TestHarness harness = new TestHarness();
        harness.createTables();

        ObjectNode absent = harness.request("insert into data_api_events(name) values ('no-sets')");
        ObjectNode absentResponse = harness.service.batchExecuteStatement(absent, REGION);

        ObjectNode empty = harness.request("insert into data_api_events(name) values ('no-sets')");
        empty.set("parameterSets", objectMapper.createArrayNode());
        ObjectNode emptyResponse = harness.service.batchExecuteStatement(empty, REGION);

        assertEquals(0, absentResponse.get("updateResults").size());
        assertEquals(0, emptyResponse.get("updateResults").size());
        assertEquals(0L, harness.countEvents());
    }

    @Test
    void batchExecuteStatementRunsOnceForOneEmptyParameterSet() throws Exception {
        TestHarness harness = new TestHarness();
        harness.createTables();

        ObjectNode batch = harness.request("insert into data_api_events(name) values ('empty-set')");
        batch.set("parameterSets", objectMapper.createArrayNode().add(objectMapper.createArrayNode()));

        ObjectNode response = harness.service.batchExecuteStatement(batch, REGION);

        assertEquals(1, response.get("updateResults").size());
        assertEquals(1L, harness.countEvents());
    }

    @Test
    void batchExecuteStatementRejectsAStatementThatReturnsRows() throws Exception {
        TestHarness harness = new TestHarness();
        harness.createTables();
        harness.service.executeStatement(
                harness.request("insert into data_api_events(name) values ('kept')"), REGION);

        ObjectNode select = harness.request("select id from data_api_events where name = :name");
        select.set("parameterSets", parameterSets("kept", "kept"));
        AwsException error = assertThrows(AwsException.class,
                () -> harness.service.batchExecuteStatement(select, REGION));

        assertEquals("BadRequestException", error.getErrorCode());
        assertTrue(error.getMessage().contains("returns a result set"));
        assertEquals(1L, harness.countEvents());
    }

    @Test
    void batchExecuteStatementRunsDmlHiddenBehindALeadingComment() throws Exception {
        TestHarness harness = new TestHarness();
        harness.createTables();

        ObjectNode batch = harness.request("""
                -- seeds the events table
                /* two sets */ insert into data_api_events(name) values (:name)
                """);
        batch.set("parameterSets", parameterSets("commented-one", "commented-two"));

        ObjectNode response = harness.service.batchExecuteStatement(batch, REGION);

        assertEquals(2, response.get("updateResults").size());
        assertEquals(2L, harness.countEvents());
    }

    @Test
    void batchExecuteStatementReportsOneResultPerSetOnPostgres() throws Exception {
        TestHarness harness = new TestHarness(DatabaseEngine.POSTGRES);
        harness.createEventsTable();

        ObjectNode batch = harness.request("insert into data_api_events(name) values (:name)");
        batch.set("parameterSets", parameterSets("first", "second"));

        ObjectNode response = harness.service.batchExecuteStatement(batch, REGION);

        ArrayNode updateResults = (ArrayNode) response.get("updateResults");
        assertEquals(2, updateResults.size());
        assertEquals(0, updateResults.get(0).get("generatedFields").size());
        assertEquals(0, updateResults.get(1).get("generatedFields").size());
        assertEquals(2L, harness.countEvents());
    }

    @Test
    void batchExecuteStatementRejectsAStatementThatReturnsRowsOnPostgres() throws Exception {
        TestHarness harness = new TestHarness(DatabaseEngine.POSTGRES);
        harness.createEventsTable();

        ObjectNode select = harness.request("select id from data_api_events where name = :name");
        select.set("parameterSets", parameterSets("first", "second"));
        AwsException error = assertThrows(AwsException.class,
                () -> harness.service.batchExecuteStatement(select, REGION));

        assertEquals("BadRequestException", error.getErrorCode());
        assertTrue(error.getMessage().contains("returns a result set"));
    }

    @Test
    void batchExecuteStatementValidatesRequestShape() throws Exception {
        TestHarness harness = new TestHarness();
        harness.createTables();

        ObjectNode missingSql = harness.beginRequest();
        AwsException missingSqlError = assertThrows(AwsException.class,
                () -> harness.service.batchExecuteStatement(missingSql, REGION));
        assertEquals("BadRequestException", missingSqlError.getErrorCode());
        assertEquals("sql is required.", missingSqlError.getMessage());

        ObjectNode notAnArray = harness.request("insert into data_api_events(name) values (:name)");
        notAnArray.set("parameterSets", objectMapper.createObjectNode());
        AwsException notAnArrayError = assertThrows(AwsException.class,
                () -> harness.service.batchExecuteStatement(notAnArray, REGION));
        assertEquals("BadRequestException", notAnArrayError.getErrorCode());

        ObjectNode setNotAnArray = harness.request("insert into data_api_events(name) values (:name)");
        setNotAnArray.set("parameterSets",
                objectMapper.createArrayNode().add(objectMapper.createObjectNode()));
        AwsException setNotAnArrayError = assertThrows(AwsException.class,
                () -> harness.service.batchExecuteStatement(setNotAnArray, REGION));
        assertEquals("BadRequestException", setNotAnArrayError.getErrorCode());
    }

    @Test
    void bindsSqlParametersThroughPreparedStatements() throws Exception {
        TestHarness harness = new TestHarness();
        harness.createTables();

        ObjectNode insert = harness.request("""
                insert into data_api_items(id, title, score, payload, active, created_at)
                values (:id, :title, :score, :payload, :active, :created_at)
                """);
        ArrayNode insertParams = objectMapper.createArrayNode();
        insertParams.add(stringParam("id", "p1"));
        insertParams.add(stringParam("title", "Param"));
        insertParams.add(longParam("score", 7));
        insertParams.add(blobParam("payload", new byte[] {9, 8, 7}));
        insertParams.add(booleanParam("active", true));
        insertParams.add(hintedStringParam("created_at", "2026-06-09 12:34:56", "TIMESTAMP"));
        insert.set("parameters", insertParams);
        ObjectNode insertResponse = harness.service.executeStatement(insert, REGION);
        assertEquals(1L, insertResponse.get("numberOfRecordsUpdated").asLong());

        ObjectNode select = harness.request(
                "select title, score, payload, active from data_api_items where id = :id");
        ArrayNode selectParams = objectMapper.createArrayNode();
        selectParams.add(stringParam("id", "p1"));
        select.set("parameters", selectParams);
        ObjectNode selectResponse = harness.service.executeStatement(select, REGION);

        ArrayNode row = (ArrayNode) selectResponse.get("records").get(0);
        assertEquals("Param", row.get(0).get("stringValue").asText());
        assertEquals(7L, row.get(1).get("longValue").asLong());
        assertArrayEquals(new byte[] {9, 8, 7}, row.get(2).get("blobValue").binaryValue());
        assertTrue(row.get(3).get("booleanValue").asBoolean());
    }

    @Test
    void rejectsSqlParameterWithoutMatchingValue() throws Exception {
        TestHarness harness = new TestHarness();
        harness.createTables();

        ObjectNode select = harness.request("select 1 from data_api_items where id = :id");
        ArrayNode params = objectMapper.createArrayNode();
        params.add(stringParam("other", "value"));
        select.set("parameters", params);

        AwsException error = assertThrows(AwsException.class,
                () -> harness.service.executeStatement(select, REGION));
        assertEquals("BadRequestException", error.getErrorCode());
    }

    @Test
    void rejectsMissingParameterEvenWhenParametersOmitted() throws Exception {
        TestHarness harness = new TestHarness();
        harness.createTables();

        ObjectNode select = harness.request("select 1 from data_api_items where id = :id");

        AwsException error = assertThrows(AwsException.class,
                () -> harness.service.executeStatement(select, REGION));
        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertTrue(error.getMessage().contains(":id"));
    }

    @Test
    void bindsTimeTypeHintWithOptionalFractionalSeconds() throws Exception {
        TestHarness harness = new TestHarness();
        harness.createTables();

        ObjectNode withFraction = harness.request("select cast(:t as time) as t");
        ArrayNode fractionParams = objectMapper.createArrayNode();
        fractionParams.add(hintedStringParam("t", "14:30:15.123", "TIME"));
        withFraction.set("parameters", fractionParams);
        ObjectNode fractionResponse = harness.service.executeStatement(withFraction, REGION);
        assertEquals("14:30:15",
                ((ArrayNode) fractionResponse.get("records").get(0)).get(0).get("stringValue").asText());

        ObjectNode withoutFraction = harness.request("select cast(:t as time) as t");
        ArrayNode plainParams = objectMapper.createArrayNode();
        plainParams.add(hintedStringParam("t", "09:05:07", "TIME"));
        withoutFraction.set("parameters", plainParams);
        ObjectNode plainResponse = harness.service.executeStatement(withoutFraction, REGION);
        assertEquals("09:05:07",
                ((ArrayNode) plainResponse.get("records").get(0)).get(0).get("stringValue").asText());
    }

    @Test
    void rejectsMalformedTypeHintValueWithBadRequest() throws Exception {
        TestHarness harness = new TestHarness();
        harness.createTables();

        ObjectNode select = harness.request(
                "select id from data_api_items where created_at = :ts");
        ArrayNode params = objectMapper.createArrayNode();
        params.add(hintedStringParam("ts", "not-a-timestamp", "TIMESTAMP"));
        select.set("parameters", params);

        AwsException error = assertThrows(AwsException.class,
                () -> harness.service.executeStatement(select, REGION));
        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertTrue(error.getMessage().contains(":ts"));
    }

    @Test
    void rejectsMalformedUuidTypeHintValueWithBadRequest() throws Exception {
        TestHarness harness = new TestHarness();
        harness.createTables();

        ObjectNode select = harness.request("select id from data_api_items where id = :id");
        ArrayNode params = objectMapper.createArrayNode();
        params.add(hintedStringParam("id", "not-a-uuid", "UUID"));
        select.set("parameters", params);

        AwsException error = assertThrows(AwsException.class,
                () -> harness.service.executeStatement(select, REGION));
        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void rejectsDuplicateParameterNames() throws Exception {
        TestHarness harness = new TestHarness();
        harness.createTables();

        ObjectNode select = harness.request("select 1 from data_api_items where id = :id");
        ArrayNode params = objectMapper.createArrayNode();
        params.add(stringParam("id", "first"));
        params.add(stringParam("id", "second"));
        select.set("parameters", params);

        AwsException error = assertThrows(AwsException.class,
                () -> harness.service.executeStatement(select, REGION));
        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertTrue(error.getMessage().contains(":id"));
    }

    @Test
    void commitsRollsBackAndRejectsInvalidTransactionRequests() throws Exception {
        TestHarness harness = new TestHarness();
        harness.createTables();

        String committedTx = harness.service.beginTransaction(harness.beginRequest(), REGION).get("transactionId").asText();
        ObjectNode insertCommitted = harness.request("insert into data_api_items(id, title, score) values ('commit', 'Commit', 1)");
        insertCommitted.put("transactionId", committedTx);
        harness.service.executeStatement(insertCommitted, REGION);
        ObjectNode commitResponse = harness.service.commitTransaction(
                harness.transactionRequest(committedTx), REGION);
        assertEquals("Transaction Committed", commitResponse.get("transactionStatus").asText());
        assertEquals(1L, harness.countById("commit"));

        String rolledBackTx = harness.service.beginTransaction(harness.beginRequest(), REGION).get("transactionId").asText();
        ObjectNode insertRolledBack = harness.request("insert into data_api_items(id, title, score) values ('rollback', 'Rollback', 1)");
        insertRolledBack.put("transactionId", rolledBackTx);
        harness.service.executeStatement(insertRolledBack, REGION);
        ObjectNode rollbackResponse = harness.service.rollbackTransaction(
                harness.transactionRequest(rolledBackTx), REGION);
        assertEquals("Rollback Complete", rollbackResponse.get("transactionStatus").asText());
        assertEquals(0L, harness.countById("rollback"));

        ObjectNode unknownTxRequest = harness.request("select 1");
        unknownTxRequest.put("transactionId", "missing");
        AwsException unknownTx = assertThrows(AwsException.class,
                () -> harness.service.executeStatement(unknownTxRequest, REGION));
        assertEquals("TransactionNotFoundException", unknownTx.getErrorCode());

        String mismatchTx = harness.service.beginTransaction(harness.beginRequest(), REGION).get("transactionId").asText();
        ObjectNode mismatchedResource = harness.request("select 1");
        mismatchedResource.put("transactionId", mismatchTx);
        mismatchedResource.put("resourceArn", OTHER_RESOURCE_ARN);
        AwsException resourceMismatch = assertThrows(AwsException.class,
                () -> harness.service.executeStatement(mismatchedResource, REGION));
        assertEquals("TransactionNotFoundException", resourceMismatch.getErrorCode());

        ObjectNode unresolvableResource = harness.request("select 1");
        unresolvableResource.put("transactionId", mismatchTx);
        unresolvableResource.put("resourceArn", UNKNOWN_RESOURCE_ARN);
        AwsException unresolvableResourceMismatch = assertThrows(AwsException.class,
                () -> harness.service.executeStatement(unresolvableResource, REGION));
        assertEquals("TransactionNotFoundException", unresolvableResourceMismatch.getErrorCode());

        ObjectNode mismatchedDatabase = harness.request("select 1");
        mismatchedDatabase.put("transactionId", mismatchTx);
        mismatchedDatabase.put("database", "other");
        AwsException databaseMismatch = assertThrows(AwsException.class,
                () -> harness.service.executeStatement(mismatchedDatabase, REGION));
        assertEquals("TransactionNotFoundException", databaseMismatch.getErrorCode());

        AwsException signedRegionMismatch = assertThrows(AwsException.class,
                () -> harness.service.commitTransaction(
                        harness.transactionRequest(mismatchTx), "us-west-2"));
        assertEquals("TransactionNotFoundException", signedRegionMismatch.getErrorCode());

        harness.service.rollbackTransaction(harness.transactionRequest(mismatchTx), REGION);
    }

    @Test
    void normalizesFallbackResourceArnForTransactionIdentity() throws Exception {
        TestHarness harness = new TestHarness();
        harness.createTables();

        String tx = harness.service.beginTransaction(harness.beginRequest(FALLBACK_RESOURCE_ARN), REGION)
                .get("transactionId").asText();
        ObjectNode insert = harness.request(FALLBACK_RESOURCE_ARN,
                "insert into data_api_items(id, title, score) values ('fallback', 'Fallback', 1)");
        insert.put("transactionId", tx);
        harness.service.executeStatement(insert, REGION);

        ObjectNode commitResponse = harness.service.commitTransaction(
                harness.transactionRequest(FALLBACK_RESOURCE_ARN, tx), REGION);

        assertEquals("Transaction Committed", commitResponse.get("transactionStatus").asText());
        assertEquals(1L, harness.countById("fallback"));
    }

    @Test
    void commitsWithOriginalTransactionArnAfterResourceLookupFails() throws Exception {
        TestHarness harness = new TestHarness();
        harness.createTables();

        String tx = harness.service.beginTransaction(harness.beginRequest(), REGION).get("transactionId").asText();
        ObjectNode insert = harness.request("""
                insert into data_api_items(id, title, score)
                values ('deleted-resource', 'Deleted', 1)
                """);
        insert.put("transactionId", tx);
        harness.service.executeStatement(insert, REGION);
        doThrow(new AwsException("BadRequestException", "resource is gone", 400))
                .when(harness.resolver).resolve(RESOURCE_ARN, REGION);

        ObjectNode commitResponse = harness.service.commitTransaction(
                harness.transactionRequest(tx), REGION);

        assertEquals("Transaction Committed", commitResponse.get("transactionStatus").asText());
        doReturn(harness.target).when(harness.resolver).resolve(RESOURCE_ARN, REGION);
        assertEquals(1L, harness.countById("deleted-resource"));
    }

    @Test
    void validatesRequiredAwsFieldsForDataApiRequests() throws Exception {
        TestHarness harness = new TestHarness();
        harness.createTables();

        ObjectNode executeMissingSecret = harness.request("select 1");
        executeMissingSecret.remove("secretArn");
        AwsException missingExecuteSecret = assertThrows(AwsException.class,
                () -> harness.service.executeStatement(executeMissingSecret, REGION));
        assertEquals("BadRequestException", missingExecuteSecret.getErrorCode());
        assertEquals("secretArn is required.", missingExecuteSecret.getMessage());

        ObjectNode beginMissingSecret = harness.beginRequest();
        beginMissingSecret.remove("secretArn");
        AwsException missingBeginSecret = assertThrows(AwsException.class,
                () -> harness.service.beginTransaction(beginMissingSecret, REGION));
        assertEquals("BadRequestException", missingBeginSecret.getErrorCode());
        assertEquals("secretArn is required.", missingBeginSecret.getMessage());

        String tx = harness.service.beginTransaction(harness.beginRequest(), REGION).get("transactionId").asText();
        ObjectNode commitMissingResource = harness.transactionRequest(tx);
        commitMissingResource.remove("resourceArn");
        AwsException missingCommitResource = assertThrows(AwsException.class,
                () -> harness.service.commitTransaction(commitMissingResource, REGION));
        assertEquals("BadRequestException", missingCommitResource.getErrorCode());
        assertEquals("resourceArn is required.", missingCommitResource.getMessage());

        ObjectNode rollbackMismatchedResource = harness.transactionRequest(tx);
        rollbackMismatchedResource.put("resourceArn", OTHER_RESOURCE_ARN);
        AwsException rollbackMismatch = assertThrows(AwsException.class,
                () -> harness.service.rollbackTransaction(rollbackMismatchedResource, REGION));
        assertEquals("TransactionNotFoundException", rollbackMismatch.getErrorCode());

        harness.service.rollbackTransaction(harness.transactionRequest(tx), REGION);
    }

    @Test
    void rejectsUnsupportedExecuteOptions() throws Exception {
        TestHarness harness = new TestHarness();
        harness.createTables();

        ObjectNode formattedRecords = harness.request("select 1");
        formattedRecords.put("formatRecordsAs", "JSON");
        AwsException formattedRecordsError = assertThrows(AwsException.class,
                () -> harness.service.executeStatement(formattedRecords, REGION));
        assertEquals("BadRequestException", formattedRecordsError.getErrorCode());

        ObjectNode malformedParameters = harness.request("select 1");
        malformedParameters.set("parameters", objectMapper.createObjectNode());
        AwsException malformedParametersError = assertThrows(AwsException.class,
                () -> harness.service.executeStatement(malformedParameters, REGION));
        assertEquals("BadRequestException", malformedParametersError.getErrorCode());

        ObjectNode resultSetOptions = harness.request("select 1");
        ObjectNode options = objectMapper.createObjectNode();
        options.put("decimalReturnType", "STRING");
        resultSetOptions.set("resultSetOptions", options);
        AwsException resultSetOptionsError = assertThrows(AwsException.class,
                () -> harness.service.executeStatement(resultSetOptions, REGION));
        assertEquals("BadRequestException", resultSetOptionsError.getErrorCode());
    }

    @Test
    void rollsBackExpiredTransactionsDuringCleanup() throws Exception {
        TestHarness harness = new TestHarness(Duration.ofMillis(50));
        harness.createTables();

        String tx = harness.service.beginTransaction(harness.beginRequest(), REGION).get("transactionId").asText();
        ObjectNode insert = harness.request("insert into data_api_items(id, title, score) values ('expired', 'Expired', 1)");
        insert.put("transactionId", tx);
        harness.service.executeStatement(insert, REGION);
        Thread.sleep(500);

        ObjectNode nextTxRequest = harness.request("select 1");
        nextTxRequest.put("transactionId", tx);
        AwsException expired = assertThrows(AwsException.class,
                () -> harness.service.executeStatement(nextTxRequest, REGION));

        assertEquals("TransactionNotFoundException", expired.getErrorCode());
        assertEquals(0L, harness.countById("expired"));
    }

    @Test
    void shutdownRollsBackOpenTransactions() throws Exception {
        TestHarness harness = new TestHarness();
        harness.createTables();

        String tx = harness.service.beginTransaction(harness.beginRequest(), REGION).get("transactionId").asText();
        ObjectNode insert = harness.request("insert into data_api_items(id, title, score) values ('shutdown', 'Shutdown', 1)");
        insert.put("transactionId", tx);
        harness.service.executeStatement(insert, REGION);

        harness.service.shutdown();

        assertEquals(0L, harness.countById("shutdown"));
    }

    @Test
    void closesConnectionWhenTransactionSetupFails() {
        RdsDataResourceResolver resolver = mock(RdsDataResourceResolver.class);
        SecretsManagerService secrets = fallbackSecrets();
        RdsDataResourceResolver.DatabaseTarget target = target();
        when(resolver.resolve(RESOURCE_ARN, REGION)).thenReturn(target);
        AtomicBoolean closed = new AtomicBoolean(false);
        RdsDataConnectionFactory failingFactory = new RdsDataConnectionFactory() {
            @Override
            Connection open(RdsDataResourceResolver.DatabaseTarget target,
                            String username,
                            String password,
                            String database) {
                return throwingSetAutoCommitConnection(closed);
            }
        };
        RdsDataService service = new RdsDataService(resolver, secrets, objectMapper, failingFactory, Duration.ofSeconds(60));

        AwsException error = assertThrows(AwsException.class, () -> service.beginTransaction(beginRequest(), REGION));

        assertEquals("DatabaseErrorException", error.getErrorCode());
        assertTrue(closed.get());
    }

    private ObjectNode beginRequest() {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("resourceArn", RESOURCE_ARN);
        request.put("secretArn", SECRET_ARN);
        request.put("database", "app");
        return request;
    }

    private ArrayNode parameterSets(String... names) {
        ArrayNode sets = objectMapper.createArrayNode();
        for (String name : names) {
            sets.add(objectMapper.createArrayNode().add(stringParam("name", name)));
        }
        return sets;
    }

    private ObjectNode stringParam(String name, String value) {
        ObjectNode param = objectMapper.createObjectNode();
        param.put("name", name);
        param.set("value", objectMapper.createObjectNode().put("stringValue", value));
        return param;
    }

    private ObjectNode hintedStringParam(String name, String value, String typeHint) {
        ObjectNode param = stringParam(name, value);
        param.put("typeHint", typeHint);
        return param;
    }

    private ObjectNode longParam(String name, long value) {
        ObjectNode param = objectMapper.createObjectNode();
        param.put("name", name);
        param.set("value", objectMapper.createObjectNode().put("longValue", value));
        return param;
    }

    private ObjectNode booleanParam(String name, boolean value) {
        ObjectNode param = objectMapper.createObjectNode();
        param.put("name", name);
        param.set("value", objectMapper.createObjectNode().put("booleanValue", value));
        return param;
    }

    private ObjectNode blobParam(String name, byte[] value) {
        ObjectNode param = objectMapper.createObjectNode();
        param.put("name", name);
        param.set("value", objectMapper.createObjectNode().put("blobValue", value));
        return param;
    }

    private static SecretsManagerService fallbackSecrets() {
        SecretsManagerService secrets = mock(SecretsManagerService.class);
        when(secrets.getSecretValue(any(), any(), any(), any()))
                .thenThrow(new AwsException("ResourceNotFoundException",
                        "Secrets Manager can't find the specified secret.", 400));
        return secrets;
    }

    private static RdsDataResourceResolver.DatabaseTarget target() {
        return target(RESOURCE_ARN);
    }

    private static RdsDataResourceResolver.DatabaseTarget target(String resourceArn) {
        return target(resourceArn, DatabaseEngine.MYSQL);
    }

    private static RdsDataResourceResolver.DatabaseTarget target(String resourceArn, DatabaseEngine engine) {
        return new RdsDataResourceResolver.DatabaseTarget(resourceArn, engine,
                "127.0.0.1", 3306, "sa", "", "app");
    }

    /** The H2 compatibility mode standing in for {@code engine}. */
    private static String h2Mode(DatabaseEngine engine) {
        return switch (engine) {
            case MYSQL, MARIADB -> "MySQL";
            case POSTGRES -> "PostgreSQL";
        };
    }

    private static Connection throwingSetAutoCommitConnection(AtomicBoolean closed) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if ("setAutoCommit".equals(method.getName())) {
                        throw new SQLException("setAutoCommit failed");
                    }
                    if ("close".equals(method.getName())) {
                        closed.set(true);
                        return null;
                    }
                    if ("isClosed".equals(method.getName())) {
                        return closed.get();
                    }
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) {
                        return false;
                    }
                    if (returnType == int.class) {
                        return 0;
                    }
                    if (returnType == long.class) {
                        return 0L;
                    }
                    return null;
                });
    }

    private final class TestHarness {
        private final String jdbcUrl;
        private final RdsDataResourceResolver resolver;
        private final RdsDataResourceResolver.DatabaseTarget target;
        private final RdsDataService service;

        private TestHarness() {
            this(DatabaseEngine.MYSQL, Duration.ofSeconds(60));
        }

        private TestHarness(DatabaseEngine engine) {
            this(engine, Duration.ofSeconds(60));
        }

        private TestHarness(Duration transactionTtl) {
            this(DatabaseEngine.MYSQL, transactionTtl);
        }

        private TestHarness(DatabaseEngine engine, Duration transactionTtl) {
            jdbcUrl = "jdbc:h2:mem:rdsdata_" + UUID.randomUUID() + ";MODE=" + h2Mode(engine)
                    + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
            resolver = mock(RdsDataResourceResolver.class);
            SecretsManagerService secrets = fallbackSecrets();
            target = target(RESOURCE_ARN, engine);
            when(resolver.resolve(RESOURCE_ARN, REGION)).thenReturn(target);
            when(resolver.resolve(FALLBACK_RESOURCE_ARN, REGION)).thenReturn(target);
            when(resolver.resolve(OTHER_RESOURCE_ARN, REGION)).thenReturn(target(OTHER_RESOURCE_ARN));
            when(resolver.resolve(UNKNOWN_RESOURCE_ARN, REGION))
                    .thenThrow(new AwsException("BadRequestException", "resource is missing", 400));
            RdsDataConnectionFactory connectionFactory = new RdsDataConnectionFactory() {
                @Override
                Connection open(RdsDataResourceResolver.DatabaseTarget target,
                                String username,
                                String password,
                                String database) throws SQLException {
                    return DriverManager.getConnection(jdbcUrl, "sa", "");
                }
            };
            service = new RdsDataService(resolver, secrets, objectMapper, connectionFactory, transactionTtl);
        }

        private void createTables() throws SQLException {
            try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        create table data_api_items(
                            id varchar(64) primary key,
                            title varchar(255),
                            score bigint,
                            payload blob,
                            active boolean,
                            created_at timestamp(9)
                        )
                        """);
                statement.execute("""
                        create table data_api_events(
                            id bigint auto_increment primary key,
                            name varchar(64)
                        )
                        """);
            }
        }

        /**
         * The events table alone, in DDL both H2 compatibility modes accept, for
         * harnesses standing in for PostgreSQL.
         */
        private void createEventsTable() throws SQLException {
            try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        create table data_api_events(
                            id bigint generated by default as identity primary key,
                            name varchar(64)
                        )
                        """);
            }
        }

        private ObjectNode request(String sql) {
            return request(RESOURCE_ARN, sql);
        }

        private ObjectNode request(String resourceArn, String sql) {
            ObjectNode request = beginRequest(resourceArn);
            request.put("sql", sql);
            return request;
        }

        private ObjectNode beginRequest() {
            return beginRequest(RESOURCE_ARN);
        }

        private ObjectNode beginRequest(String resourceArn) {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("resourceArn", resourceArn);
            request.put("secretArn", SECRET_ARN);
            request.put("database", "app");
            return request;
        }

        private ObjectNode transactionRequest(String transactionId) {
            return transactionRequest(RESOURCE_ARN, transactionId);
        }

        private ObjectNode transactionRequest(String resourceArn, String transactionId) {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("resourceArn", resourceArn);
            request.put("secretArn", SECRET_ARN);
            request.put("transactionId", transactionId);
            return request;
        }

        private long countEvents() {
            ObjectNode request = request("select count(*) as count from data_api_events");
            ObjectNode response = service.executeStatement(request, REGION);
            return response.get("records").get(0).get(0).get("longValue").asLong();
        }

        private long countById(String id) {
            ObjectNode request = request("select count(*) as count from data_api_items where id = '" + id + "'");
            ObjectNode response = service.executeStatement(request, REGION);
            return response.get("records").get(0).get(0).get("longValue").asLong();
        }
    }
}
