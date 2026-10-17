package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricFilter;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class CloudWatchLogsGroupDeletionObserverIntegrationTest {

    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";

    @Inject
    CloudWatchLogsService logs;

    @Inject
    CloudWatchLogsMetricFilterService metrics;

    @TestHTTPResource
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void actualDeletionObserverPreservesHistoricalChildMetricFilters() {
        assertNotNull(metrics);
        String parent = "logs-observer-" + UUID.randomUUID();
        String child = parent + "::child";
        for (String group : List.of(parent, child)) {
            cleanup.register(() -> RequestScopes.runAs(ACCOUNT, REGION, () -> {
                if (logs.describeLogGroups(group, REGION).stream()
                        .anyMatch(stored -> group.equals(stored.getLogGroupName()))) {
                    logs.deleteLogGroup(group, REGION);
                }
            }));
            logs.createLogGroupForAccount(ACCOUNT, group, null, Map.of(), REGION);
            RequestScopes.runAs(ACCOUNT, REGION, () -> {
                MetricFilter filter = new MetricFilter();
                filter.setLogGroupName(group);
                filter.setFilterName("m");
                logs.metricFilterStore().put(REGION + "::" + group + "::m", filter);
                assertNotNull(logs.metricFilterStore().get(REGION + "::" + group + "::m").orElse(null));
            });
        }
        Response response = given().port(endpoint.getPort()).contentType("application/x-amz-json-1.1")
                .header("Authorization", EnforcementFixtures.auth(ACCOUNT, "logs"))
                .header("X-Amz-Target", "Logs_20140328.DeleteLogGroup")
                .body(Map.of("logGroupName", parent)).when().post("/");
        RequestScopes.runAs(ACCOUNT, REGION, () -> assertAll("real CDI observer retains the unrelated child filter",
                () -> response.then().statusCode(200),
                () -> assertNull(logs.metricFilterStore().get(REGION + "::" + parent + "::m").orElse(null)),
                () -> assertNotNull(logs.metricFilterStore().get(REGION + "::" + child + "::m").orElse(null))));
    }
}
