package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.h2.Driver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Under IAM enforcement, a Data API statement naming its session alone, by {@code SessionId}, is checked on the
 * cluster or serverless workgroup the session runs on, as the statement that opened it was.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class RedshiftDataSessionIamEnforcementIntegrationTest {

    private static final String ACCOUNT = "111111111111";
    private static final String CLUSTER = "arn:aws:redshift:us-east-1:" + ACCOUNT + ":cluster:warehouse";
    private static final String WORKGROUP = "arn:aws:redshift-serverless:us-east-1:" + ACCOUNT
            + ":workgroup/12345678-1234-1234-1234-123456789012";

    @TestHTTPResource("/")
    URI endpoint;
    @InjectMock
    RedshiftDataResourceResolver resolver;
    @InjectMock
    RedshiftDataConnectionFactory factory;
    @Inject
    RedshiftDataService service;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void setup() throws Exception {
        Driver.load();
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        when(factory.open(any())).thenAnswer(invocation -> DriverManager.getConnection(url));
        when(resolver.find(eq("workgroup"), eq(List.of("analytics")), eq("us-east-1"))).thenReturn(Optional.of(WORKGROUP));
        when(resolver.resolve(any(), any())).thenAnswer(invocation -> {
            JsonNode request = invocation.getArgument(0);
            return new RedshiftDataResourceResolver.DatabaseTarget(request.has("WorkgroupName") ? WORKGROUP : CLUSTER,
                    "localhost", 5439, "dev", "admin", "password");
        });
    }

    @AfterEach
    void cleanup() {
        service.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aStatementNamingItsSessionIsCheckedOnTheTargetTheSessionRunsOn(boolean serverless) {
        String target = serverless ? WORKGROUP : CLUSTER;
        String user = "rsd-session-" + UUID.randomUUID().toString().substring(0, 8);
        String key = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), auth(ACCOUNT, "iam"), user)
                .accessKeyId();
        policy(user, allow("redshift-data:ExecuteStatement", target) + ","
                + allow("redshift-data:BatchExecuteStatement", target));
        String named = serverless ? "\"WorkgroupName\":\"analytics\""
                : "\"ClusterIdentifier\":\"warehouse\",\"DbUser\":\"admin\"";
        String session = data(key, "ExecuteStatement", "{" + named + ",\"Database\":\"dev\",\"Sql\":\"SELECT 1\","
                + "\"SessionKeepAliveSeconds\":60,\"WaitTimeSeconds\":30}").statusCode(200).extract().path("SessionId");

        Map<String, String> statements = Map.of("ExecuteStatement", "\"Sql\":\"SELECT 2\"",
                "BatchExecuteStatement", "\"Sqls\":[\"SELECT 3\"]");
        // Allowed on the target alone, the statements naming the session run there; checked on * before, they were not.
        statements.forEach((operation, sql) -> data(key, operation, "{\"SessionId\":\"" + session + "\"," + sql
                + ",\"WaitTimeSeconds\":30}").statusCode(200).body("SessionId", equalTo(session)));

        // A deny on the target holds for them too, under an allow on everything.
        policy(user, allow("redshift-data:*", "*") + ",{\"Effect\":\"Deny\",\"Action\":\"redshift-data:*\","
                + "\"Resource\":\"" + target + "\"}");
        statements.forEach((operation, sql) -> data(key, operation, "{\"SessionId\":\"" + session + "\"," + sql + "}")
                .statusCode(400).body(containsString("AccessDeniedException"))
                .body(containsString("not authorized to perform: redshift-data:" + operation)));
    }

    private void policy(String user, String statements) {
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth(ACCOUNT, "iam"), user, "data",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
    }

    private static String allow(String action, String resource) {
        return "{\"Effect\":\"Allow\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }

    private static ValidatableResponse data(String accessKeyId, String operation, String body) {
        return given().contentType("application/x-amz-json-1.1").header("X-Amz-Target", "RedshiftData." + operation)
                .header("Authorization", auth(accessKeyId, "redshift-data")).body(body).post("/").then();
    }
}
