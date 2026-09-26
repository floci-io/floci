package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.PartitionMatrix;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/** {@code floci.partitions.allow-unknown-regions=true}: the old behaviour, any label is a region. */
@QuarkusTest
@TestProfile(PartitionUnknownRegionAllowedIntegrationTest.AllowUnknownRegionsProfile.class)
class PartitionUnknownRegionAllowedIntegrationTest {

    private static final String UNKNOWN = PartitionUnknownRegionIntegrationTest.UNKNOWN;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void theUnknownLabelIsServedWithItsOwnNamespace() {
        String name = "unknown-" + Long.toString(System.nanoTime(), 36);
        cleanup.register(() -> PartitionUnknownRegionIntegrationTest.deleteQueue(UNKNOWN, name));
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth(UNKNOWN, "sqs"))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", name)
        .when().post("/").then().statusCode(200);
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth(UNKNOWN, "sqs"))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListQueues")
        .when().post("/").then().statusCode(200)
            .body(containsString(name));
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth("us-east-1", "sqs"))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListQueues")
            .formParam("QueueNamePrefix", name)
        .when().post("/").then().statusCode(200)
            .body(not(containsString(name)));
    }

    public static final class AllowUnknownRegionsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.partitions.allow-unknown-regions", "true");
        }
    }
}
