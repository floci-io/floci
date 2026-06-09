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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RdsDataServiceTest {

    private static final String RESOURCE_ARN = "arn:aws:rds:us-east-1:000000000000:cluster:test";
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
        harness.service.commitTransaction(harness.transactionRequest(committedTx));
        assertEquals(1L, harness.countById("commit"));

        String rolledBackTx = harness.service.beginTransaction(harness.beginRequest(), REGION).get("transactionId").asText();
        ObjectNode insertRolledBack = harness.request("insert into data_api_items(id, title, score) values ('rollback', 'Rollback', 1)");
        insertRolledBack.put("transactionId", rolledBackTx);
        harness.service.executeStatement(insertRolledBack, REGION);
        harness.service.rollbackTransaction(harness.transactionRequest(rolledBackTx));
        assertEquals(0L, harness.countById("rollback"));

        ObjectNode unknownTxRequest = harness.request("select 1");
        unknownTxRequest.put("transactionId", "missing");
        AwsException unknownTx = assertThrows(AwsException.class,
                () -> harness.service.executeStatement(unknownTxRequest, REGION));
        assertEquals("TransactionNotFoundException", unknownTx.getErrorCode());

        String mismatchTx = harness.service.beginTransaction(harness.beginRequest(), REGION).get("transactionId").asText();
        ObjectNode mismatchedResource = harness.request("select 1");
        mismatchedResource.put("transactionId", mismatchTx);
        mismatchedResource.put("resourceArn", RESOURCE_ARN + "-other");
        AwsException resourceMismatch = assertThrows(AwsException.class,
                () -> harness.service.executeStatement(mismatchedResource, REGION));
        assertEquals("BadRequestException", resourceMismatch.getErrorCode());

        ObjectNode mismatchedDatabase = harness.request("select 1");
        mismatchedDatabase.put("transactionId", mismatchTx);
        mismatchedDatabase.put("database", "other");
        AwsException databaseMismatch = assertThrows(AwsException.class,
                () -> harness.service.executeStatement(mismatchedDatabase, REGION));
        assertEquals("BadRequestException", databaseMismatch.getErrorCode());

        harness.service.rollbackTransaction(harness.transactionRequest(mismatchTx));
    }

    @Test
    void closesConnectionWhenTransactionSetupFails() {
        RdsDataResourceResolver resolver = mock(RdsDataResourceResolver.class);
        SecretsManagerService secrets = mock(SecretsManagerService.class);
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
        request.put("database", "app");
        return request;
    }

    private static RdsDataResourceResolver.DatabaseTarget target() {
        return new RdsDataResourceResolver.DatabaseTarget(RESOURCE_ARN, DatabaseEngine.MYSQL,
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
        private final RdsDataService service;

        private TestHarness() {
            RdsDataResourceResolver resolver = mock(RdsDataResourceResolver.class);
            SecretsManagerService secrets = mock(SecretsManagerService.class);
            RdsDataResourceResolver.DatabaseTarget target = target();
            when(resolver.resolve(RESOURCE_ARN)).thenReturn(target);
            RdsDataConnectionFactory connectionFactory = new RdsDataConnectionFactory() {
                @Override
                Connection open(RdsDataResourceResolver.DatabaseTarget target,
                                String username,
                                String password,
                                String database) throws SQLException {
                    return DriverManager.getConnection(jdbcUrl, "sa", "");
                }
            };
            service = new RdsDataService(resolver, secrets, objectMapper, connectionFactory, Duration.ofSeconds(60));
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
            ObjectNode request = beginRequest();
            request.put("sql", sql);
            return request;
        }

        private ObjectNode beginRequest() {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("resourceArn", RESOURCE_ARN);
            request.put("database", "app");
            return request;
        }

        private ObjectNode transactionRequest(String transactionId) {
            ObjectNode request = objectMapper.createObjectNode();
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
