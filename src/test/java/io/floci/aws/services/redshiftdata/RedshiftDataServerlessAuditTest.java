package io.floci.aws.services.redshiftdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.floci.aws.services.redshift.RedshiftAuditLogPublisher;
import io.floci.aws.services.redshift.RedshiftAuditTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedshiftDataServerlessAuditTest {
    private static final String ACCOUNT_ID = "000000000000";
    private static final String BACKEND_ID = "serverless_us-east-1_wg";

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> groups = new CopyOnWriteArrayList<>();
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private final RedshiftDataExecutor executor = new RedshiftDataExecutor(2, 8, 1);
    private RedshiftAuditLogPublisher publisher;
    private RedshiftDataService service;

    @BeforeEach
    void setUp() throws Exception {
        CloudWatchLogsService logs = mock(CloudWatchLogsService.class);
        doAnswer(invocation -> {
            List<Map<String, Object>> records = invocation.getArgument(3);
            for (Map<String, Object> record : records) {
                groups.add(invocation.getArgument(1));
                messages.add(record.get("message").toString());
            }
            return null;
        }).when(logs).putLogEventsForAccount(anyString(), anyString(), anyString(), anyList(), anyString());
        publisher = new RedshiftAuditLogPublisher(logs);
        RedshiftDataConnectionFactory factory = mock(RedshiftDataConnectionFactory.class);
        Connection connection = mock(Connection.class);
        when(connection.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
        when(factory.open(any())).thenReturn(connection);
        RedshiftDataResourceResolver resolver = mock(RedshiftDataResourceResolver.class);
        when(resolver.resolve(any(), any())).thenReturn(new RedshiftDataResourceResolver.DatabaseTarget(
                "arn:aws:redshift-serverless:us-east-1:" + ACCOUNT_ID + ":workgroup/abc", "localhost", 5439,
                "dev", "admin", "password", null, null, "analyst", "wg", null, ACCOUNT_ID + ":" + BACKEND_ID));
        service = new RedshiftDataService(resolver, factory, new RedshiftDataStatementStore(24, Clock.systemUTC()),
                mapper, null, executor, null, null, null,
                new RedshiftDataIdempotencyStore(AccountAwareStorageBackend.inMemory(ACCOUNT_ID), Clock.systemUTC()),
                new RedshiftDataSessions(), publisher);
    }

    @AfterEach
    void tearDown() {
        service.clear();
        executor.close();
    }

    private void execute(String sql) {
        ObjectNode request = mapper.createObjectNode().put("Sql", sql).put("WaitTimeSeconds", 5)
                .put("WorkgroupName", "wg").put("Database", "dev").put("DbUser", "analyst");
        service.executeStatement(request, "us-east-1");
    }

    private static RedshiftAuditTarget namespaceTarget(List<String> exports) {
        return new RedshiftAuditTarget(BACKEND_ID, "serverless", "/aws/redshift/analytics",
                "namespace/analytics", !exports.isEmpty(), "cloudwatch", null, null, exports, true, 0L);
    }

    @Test
    void aWorkgroupStatementIsAuditedUnderItsNamespacesLogGroups() {
        publisher.register(ACCOUNT_ID, "us-east-1", namespaceTarget(List.of("connectionlog", "useractivitylog")));

        execute("SELECT 1");

        assertTrue(groups.contains("/aws/redshift/analytics/connectionlog"), groups.toString());
        assertTrue(groups.contains("/aws/redshift/analytics/useractivitylog"), groups.toString());
        assertTrue(messages.stream().anyMatch(line -> line.contains("SELECT 1")), messages.toString());
    }

    @Test
    void onlyTheSelectedLogTypesAreWritten() {
        publisher.register(ACCOUNT_ID, "us-east-1", namespaceTarget(List.of("userlog")));

        execute("SELECT 1");

        assertEquals(List.of(), groups);
    }

    @Test
    void aWorkgroupWithoutARegisteredTargetWritesNothing() {
        execute("SELECT 1");
        publisher.register(ACCOUNT_ID, "us-east-1", namespaceTarget(List.of("useractivitylog")));
        publisher.unregister(ACCOUNT_ID, BACKEND_ID);
        execute("SELECT 2");

        assertFalse(messages.stream().anyMatch(line -> line.contains("SELECT")), messages.toString());
    }
}
