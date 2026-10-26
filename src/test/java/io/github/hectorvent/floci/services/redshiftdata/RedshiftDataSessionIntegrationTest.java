package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.h2.Driver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.redshiftdata.RedshiftDataClient;
import software.amazon.awssdk.services.redshiftdata.model.DescribeStatementResponse;
import software.amazon.awssdk.services.redshiftdata.model.ExecuteStatementRequest;
import software.amazon.awssdk.services.redshiftdata.model.ExecuteStatementResponse;
import software.amazon.awssdk.services.redshiftdata.model.ResourceNotFoundException;
import software.amazon.awssdk.services.redshiftdata.model.SqlParameter;
import software.amazon.awssdk.services.redshiftdata.model.StatusString;
import software.amazon.awssdk.services.redshiftdata.model.ValidationException;

import java.net.URI;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@QuarkusTest
class RedshiftDataSessionIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;
    @InjectMock
    RedshiftDataResourceResolver resolver;
    @InjectMock
    RedshiftDataConnectionFactory factory;
    @Inject
    RedshiftDataService service;
    @Inject
    RegionResolver regions;

    private RedshiftDataClient data;

    @BeforeEach
    void setup() throws Exception {
        Driver.load();
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        when(factory.open(any())).thenAnswer(invocation -> DriverManager.getConnection(url));
        when(resolver.resolve(any(), any())).thenAnswer(invocation -> {
            JsonNode request = invocation.getArgument(0);
            String region = invocation.getArgument(1);
            boolean serverless = request.has("WorkgroupName");
            String arn = AwsArnUtils.Arn.of(serverless ? "redshift-serverless" : "redshift", region,
                    regions.getAccountId(), serverless ? "workgroup/12345678-1234-1234-1234-123456789012" : "cluster:warehouse").toString();
            return new RedshiftDataResourceResolver.DatabaseTarget(arn, "localhost", 5439, "dev", "admin", "password");
        });
        data = client("test", Region.US_EAST_1);
    }

    @AfterEach
    void cleanup() {
        service.clear();
        if (data != null) {
            data.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sdkReusesSessionForSingleAndBatchStatements(boolean serverless) throws Exception {
        ExecuteStatementRequest.Builder request = ExecuteStatementRequest.builder().waitTimeSeconds(30).database("dev")
                .sessionKeepAliveSeconds(60).sql("CREATE LOCAL TEMPORARY TABLE staging(id integer)");
        if (serverless) {
            request.workgroupName("analytics");
        } else {
            request.clusterIdentifier("warehouse").dbUser("admin");
        }
        ExecuteStatementResponse created = data.executeStatement(request.build());
        assertNotNull(created.sessionId());
        finished(created.id());
        String batch = data.batchExecuteStatement(r -> r.waitTimeSeconds(30).sessionId(created.sessionId())
                .sqls("INSERT INTO staging VALUES (41)", "INSERT INTO staging VALUES (42)")).id();
        finished(batch);
        ExecuteStatementResponse query = data.executeStatement(r -> r.waitTimeSeconds(30).sessionId(created.sessionId()).sql("SELECT id FROM staging ORDER BY id"));
        DescribeStatementResponse described = finished(query.id());

        assertEquals(created.sessionId(), described.sessionId());
        assertEquals(created.sessionId(), query.sessionId());
        assertEquals(41L, data.getStatementResult(r -> r.id(query.id())).records().getFirst().getFirst().longValue());
        assertEquals(2L, data.getStatementResult(r -> r.id(query.id())).totalNumRows());
        assertTrue(data.listStatements(r -> r.maxResults(100)).statements().stream()
                .anyMatch(statement -> created.sessionId().equals(statement.sessionId())));
        verify(factory).open(any());
    }

    @Test
    void sdkSessionIsIsolatedByCallerAccountAndRegion() {
        ExecuteStatementResponse created = data.executeStatement(r -> r.waitTimeSeconds(30).clusterIdentifier("warehouse").dbUser("admin")
                .database("dev").sessionKeepAliveSeconds(60).sql("SELECT 1"));
        finished(created.id());
        for (String key : new String[]{"another-caller", "111111111111"}) {
            try (RedshiftDataClient other = client(key, Region.US_EAST_1)) {
                assertThrows(ResourceNotFoundException.class, () -> other.executeStatement(r -> r.waitTimeSeconds(30).sessionId(created.sessionId()).sql("SELECT 2")));
            }
        }
        try (RedshiftDataClient otherRegion = client("test", Region.US_WEST_2)) {
            assertThrows(ResourceNotFoundException.class, () -> otherRegion.executeStatement(r -> r.waitTimeSeconds(30).sessionId(created.sessionId()).sql("SELECT 2")));
        }
        finished(data.executeStatement(r -> r.waitTimeSeconds(30).sessionId(created.sessionId()).sql("SELECT 3")).id());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sdkBatchBindsSharedParametersAndReturnsTheirMetadata(boolean serverless) throws Exception {
        ExecuteStatementRequest.Builder create = ExecuteStatementRequest.builder().waitTimeSeconds(30).database("dev")
                .sessionKeepAliveSeconds(60).sql("CREATE LOCAL TEMPORARY TABLE batch_params(id integer, payload varchar(100))");
        if (serverless) {
            create.workgroupName("analytics");
        } else {
            create.clusterIdentifier("warehouse").dbUser("admin");
        }
        ExecuteStatementResponse created = data.executeStatement(create.build());
        finished(created.id());
        SqlParameter id = SqlParameter.builder().name("id").value("42").build();
        SqlParameter payload = SqlParameter.builder().name("payload").value("O'Reilly :not_a_parameter").build();
        String batch = data.batchExecuteStatement(r -> r.waitTimeSeconds(30).sessionId(created.sessionId())
                .sqls("INSERT INTO batch_params VALUES (:id, :payload)", "SELECT payload FROM batch_params WHERE id = :id")
                .parameters(id, payload)).id();

        DescribeStatementResponse described = finished(batch);
        assertEquals(List.of(id, payload), described.queryParameters());
        assertEquals(2, described.subStatements().size());
        assertEquals("O'Reilly :not_a_parameter",
                data.getStatementResult(r -> r.id(batch)).records().getFirst().getFirst().stringValue());
        verify(factory).open(any());
    }

    @Test
    void sdkRejectsUnusedBatchParameterBeforeOpeningAConnection() {
        ValidationException error = assertThrows(ValidationException.class, () -> data.batchExecuteStatement(r -> r
                .clusterIdentifier("warehouse").dbUser("admin").database("dev").sqls("SELECT 1", "SELECT ':unused'")
                .parameters(SqlParameter.builder().name("unused").value("42").build())));
        assertEquals(400, error.statusCode());
        verifyNoInteractions(factory);
    }

    @Test
    void sdkValidatesKeepAliveAndReplaysAClosedSessionReceipt() {
        assertThrows(ValidationException.class, () -> data.executeStatement(r -> r.waitTimeSeconds(30).clusterIdentifier("warehouse")
                .dbUser("admin").database("dev").sessionKeepAliveSeconds(86401).sql("SELECT 1")));
        ExecuteStatementResponse created = data.executeStatement(r -> r.waitTimeSeconds(30).clusterIdentifier("warehouse").dbUser("admin")
                .database("dev").sessionKeepAliveSeconds(60).sql("SELECT 1"));
        finished(created.id());
        ExecuteStatementRequest last = ExecuteStatementRequest.builder().waitTimeSeconds(30).sessionId(created.sessionId()).sessionKeepAliveSeconds(0)
                .clientToken(UUID.randomUUID().toString()).sql("SELECT 2").build();
        ExecuteStatementResponse closed = data.executeStatement(last);
        finished(closed.id());
        assertEquals(closed.id(), data.executeStatement(last).id());
        assertEquals(closed.sessionId(), data.executeStatement(last).sessionId());
        try (RedshiftDataClient other = client("another-caller", Region.US_EAST_1)) {
            assertThrows(ResourceNotFoundException.class, () -> other.executeStatement(last));
        }
        assertThrows(ResourceNotFoundException.class, () -> data.executeStatement(r -> r.waitTimeSeconds(30).sessionId(created.sessionId()).sql("SELECT 3")));
    }

    @Test
    void sdkBatchAcceptsSavepointsAndMultipleStatementsInAnEntry() {
        ExecuteStatementResponse created = data.executeStatement(r -> r.waitTimeSeconds(30).clusterIdentifier("warehouse")
                .dbUser("admin").database("dev").sessionKeepAliveSeconds(60)
                .sql("CREATE LOCAL TEMPORARY TABLE batch_changes(id integer)"));
        finished(created.id());
        String batch = data.batchExecuteStatement(r -> r.waitTimeSeconds(30).sessionId(created.sessionId())
                .sqls("SAVEPOINT s", "INSERT INTO batch_changes VALUES (1); ROLLBACK TO SAVEPOINT s",
                        "INSERT INTO batch_changes VALUES (2)")).id();
        finished(batch);
        String query = data.executeStatement(r -> r.waitTimeSeconds(30).sessionId(created.sessionId())
                .sql("SELECT id FROM batch_changes")).id();
        finished(query);
        assertEquals(1L, data.getStatementResult(r -> r.id(query)).totalNumRows());
        assertEquals(2L, data.getStatementResult(r -> r.id(query)).records().getFirst().getFirst().longValue());
        String multiResult = data.batchExecuteStatement(r -> r.waitTimeSeconds(30).sessionId(created.sessionId())
                .sqls("SELECT 11; SELECT 22")).id();
        finished(multiResult);
        assertEquals(11L, data.getStatementResult(r -> r.id(multiResult)).records().getFirst().getFirst().longValue());
    }

    private RedshiftDataClient client(String key, Region region) {
        return RedshiftDataClient.builder().endpointOverride(endpoint).region(region)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key, "test"))).build();
    }

    private DescribeStatementResponse finished(String id) {
        DescribeStatementResponse statement = data.describeStatement(r -> r.id(id));
        assertEquals(StatusString.FINISHED, statement.status(), statement.error());
        return statement;
    }
}
