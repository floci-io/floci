package io.github.hectorvent.floci.services.redshiftdata;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.h2.Driver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.redshiftdata.RedshiftDataClient;
import software.amazon.awssdk.services.redshiftdata.model.BatchExecuteStatementRequest;
import software.amazon.awssdk.services.redshiftdata.model.ExecuteStatementRequest;
import software.amazon.awssdk.services.redshiftdata.model.ExecuteStatementResponse;
import software.amazon.awssdk.services.redshiftdata.model.ResourceNotFoundException;
import software.amazon.awssdk.services.redshiftdata.model.StatusString;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@QuarkusTest
class RedshiftDataIdempotencyIntegrationTest {
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
        String jdbcUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(jdbcUrl); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE mutations (id INT)");
        }
        when(factory.open(any())).thenAnswer(invocation -> DriverManager.getConnection(jdbcUrl));
        when(resolver.resolve(any(), any())).thenAnswer(invocation -> {
            String arn = AwsArnUtils.Arn.of("redshift", invocation.getArgument(1), regions.getAccountId(), "cluster:wh").toString();
            return new RedshiftDataResourceResolver.DatabaseTarget(arn, "localhost", 5439, "dev", "admin", "password");
        });
        data = RedshiftDataClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build();
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void cleanup() {
        service.clear();
        if (data != null) {
            data.close();
        }
    }

    @Test
    void sdkRetriesSingleAndWorkgroupStatementsWithoutRepeatingSql() {
        for (boolean serverless : new boolean[] {false, true}) {
            ExecuteStatementRequest.Builder builder = ExecuteStatementRequest.builder().database("dev")
                    .clientToken(UUID.randomUUID().toString()).sql("INSERT INTO mutations VALUES (1)");
            if (serverless) {
                builder.workgroupName("analytics");
            } else {
                builder.clusterIdentifier("wh").dbUser("admin");
            }
            ExecuteStatementRequest request = builder.build();
            ExecuteStatementResponse first = data.executeStatement(request);
            ExecuteStatementResponse retry = data.executeStatement(request);
            assertEquals(first.id(), retry.id());
            assertEquals(first.createdAt(), retry.createdAt());
            finished(first.id());
        }
        assertEquals(2L, count());
    }

    @Test
    void sdkRetriesBatchWithoutRepeatingEitherMutation() {
        BatchExecuteStatementRequest request = BatchExecuteStatementRequest.builder()
                .clusterIdentifier("wh").dbUser("admin").database("dev")
                .clientToken(UUID.randomUUID().toString())
                .sqls("INSERT INTO mutations VALUES (1)", "INSERT INTO mutations VALUES (2)").build();
        String id = data.batchExecuteStatement(request).id();
        assertEquals(id, data.batchExecuteStatement(request).id());
        finished(id);
        assertEquals(2L, count());
    }

    @Test
    void malformedTokenReturnsAnAwsJsonValidationError() {
        RestAssuredJsonUtils.awsAction("RedshiftData", "ExecuteStatement", """
                {"ClusterIdentifier":"wh","DbUser":"admin","Database":"dev","Sql":"SELECT 1","ClientToken":12}
                """).then().statusCode(400).body("__type", containsString("ValidationException"));
    }

    @Test
    void sdkTokenReceiptsAreIsolatedByTheSigningAccount() {
        ExecuteStatementRequest request = ExecuteStatementRequest.builder().clusterIdentifier("wh").dbUser("admin")
                .database("dev").clientToken(UUID.randomUUID().toString()).sql("SELECT 1").build();
        String first = data.executeStatement(request).id();
        try (RedshiftDataClient other = RedshiftDataClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("111111111111", "test"))).build()) {
            String second = other.executeStatement(request).id();
            assertNotEquals(first, second);
            assertEquals(second, other.executeStatement(request).id());
            assertEquals(first, data.executeStatement(request).id());
            assertThrows(ResourceNotFoundException.class, () -> data.describeStatement(r -> r.id(second)));
        }
        finished(first);
    }

    private long count() {
        String id = data.executeStatement(r -> r.clusterIdentifier("wh").dbUser("admin").database("dev")
                .sql("SELECT COUNT(*) FROM mutations")).id();
        finished(id);
        return data.getStatementResult(r -> r.id(id)).records().getFirst().getFirst().longValue();
    }

    private void finished(String id) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(StatusString.FINISHED, data.describeStatement(r -> r.id(id)).status()));
    }
}
