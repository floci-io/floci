package io.github.hectorvent.floci.services.rdsdata;

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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void commitsRollsBackAndRejectsInvalidTransactionRequests() throws Exception {
        TestHarness harness = new TestHarness();
        harness.createTables();

        String committedTx = harness.service.beginTransaction(harness.beginRequest(), REGION).get("transactionId").asText();
        ObjectNode insertCommitted = harness.request("insert into data_api_items(id, title, score) values ('commit', 'Commit', 1)");
        insertCommitted.put("transactionId", committedTx);
        harness.service.executeStatement(insertCommitted, REGION);
        ObjectNode commitResponse = harness.service.commitTransaction(harness.transactionRequest(committedTx));
        assertEquals("Transaction Committed", commitResponse.get("transactionStatus").asText());
        assertEquals(1L, harness.countById("commit"));

        String rolledBackTx = harness.service.beginTransaction(harness.beginRequest(), REGION).get("transactionId").asText();
        ObjectNode insertRolledBack = harness.request("insert into data_api_items(id, title, score) values ('rollback', 'Rollback', 1)");
        insertRolledBack.put("transactionId", rolledBackTx);
        harness.service.executeStatement(insertRolledBack, REGION);
        ObjectNode rollbackResponse = harness.service.rollbackTransaction(harness.transactionRequest(rolledBackTx));
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

        harness.service.rollbackTransaction(harness.transactionRequest(mismatchTx));
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

        ObjectNode commitResponse = harness.service.commitTransaction(harness.transactionRequest(FALLBACK_RESOURCE_ARN, tx));

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
                .when(harness.resolver).resolve(RESOURCE_ARN);

        ObjectNode commitResponse = harness.service.commitTransaction(harness.transactionRequest(tx));

        assertEquals("Transaction Committed", commitResponse.get("transactionStatus").asText());
        doReturn(harness.target).when(harness.resolver).resolve(RESOURCE_ARN);
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
                () -> harness.service.commitTransaction(commitMissingResource));
        assertEquals("BadRequestException", missingCommitResource.getErrorCode());
        assertEquals("resourceArn is required.", missingCommitResource.getMessage());

        ObjectNode rollbackMismatchedResource = harness.transactionRequest(tx);
        rollbackMismatchedResource.put("resourceArn", OTHER_RESOURCE_ARN);
        AwsException rollbackMismatch = assertThrows(AwsException.class,
                () -> harness.service.rollbackTransaction(rollbackMismatchedResource));
        assertEquals("TransactionNotFoundException", rollbackMismatch.getErrorCode());

        harness.service.rollbackTransaction(harness.transactionRequest(tx));
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
        when(resolver.resolve(RESOURCE_ARN)).thenReturn(target);
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
        return new RdsDataResourceResolver.DatabaseTarget(resourceArn, DatabaseEngine.MYSQL,
                "127.0.0.1", 3306, "sa", "", "app");
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
        private final String jdbcUrl = "jdbc:h2:mem:rdsdata_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        private final RdsDataResourceResolver resolver;
        private final RdsDataResourceResolver.DatabaseTarget target;
        private final RdsDataService service;

        private TestHarness() {
            this(Duration.ofSeconds(60));
        }

        private TestHarness(Duration transactionTtl) {
            resolver = mock(RdsDataResourceResolver.class);
            SecretsManagerService secrets = fallbackSecrets();
            target = target();
            when(resolver.resolve(RESOURCE_ARN)).thenReturn(target);
            when(resolver.resolve(FALLBACK_RESOURCE_ARN)).thenReturn(target);
            when(resolver.resolve(OTHER_RESOURCE_ARN)).thenReturn(target(OTHER_RESOURCE_ARN));
            when(resolver.resolve(UNKNOWN_RESOURCE_ARN))
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

        private long countById(String id) {
            ObjectNode request = request("select count(*) as count from data_api_items where id = '" + id + "'");
            ObjectNode response = service.executeStatement(request, REGION);
            return response.get("records").get(0).get(0).get("longValue").asLong();
        }
    }
}
