package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end: a non-master DbUser works for the Redshift Data API once
 * GetClusterCredentials has minted a credential for it. The wire proxy and the
 * Data API both connect to the container as the cluster master.
 */
@QuarkusTest
class RedshiftGetClusterCredentialsIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260822/us-east-1/redshift/aws4_request";

    @Inject
    RedshiftService redshift;

    private String clusterId;

    @BeforeAll
    static void configure() {
        Assumptions.assumeTrue(dockerAvailable(), "Docker is required for this integration test");
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static boolean dockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            redshift.deleteCluster(clusterId);
            clusterId = null;
        }
    }

    private void awaitFinished(String id) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).until(() -> {
            String status = RestAssuredJsonUtils.awsAction("RedshiftData", "DescribeStatement",
                    "{\"Id\":\"" + id + "\"}").then().statusCode(200).extract().path("Status");
            assertTrue(!"FAILED".equals(status) && !"ABORTED".equals(status),
                    () -> "statement " + id + " ended " + status);
            return "FINISHED".equals(status);
        });
    }

    @Test
    void nonMasterDbUserWorksAfterGetClusterCredentials() {
        clusterId = "it-gcc-" + System.currentTimeMillis();
        redshift.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "GetClusterCredentials")
            .formParam("ClusterIdentifier", clusterId)
            .formParam("DbUser", "analyst")
            .formParam("DurationSeconds", "900")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<DbUser>IAM:analyst</DbUser>"))
            .body(containsString("<DbPassword>"))
            .body(containsString("<Expiration>"));

        // A client passes back the exact DbUser that GetClusterCredentials returned.
        String id = RestAssuredJsonUtils.awsAction("RedshiftData", "ExecuteStatement", """
                {"Sql": "SELECT 1", "ClusterIdentifier": "%s", "DbUser": "IAM:analyst", "Database": "dev"}
                """.formatted(clusterId)).then().statusCode(200).extract().path("Id");
        awaitFinished(id);

        Object value = RestAssuredJsonUtils.awsAction("RedshiftData", "GetStatementResult",
                "{\"Id\":\"" + id + "\"}").then().statusCode(200).extract().path("Records[0][0].longValue");
        assertEquals(1, ((Number) value).intValue());
    }
}
