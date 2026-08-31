package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Issue #2806: CreateCluster dropped the request's {@code settings}, so a cluster configured at
 * creation came back from DescribeClusters without them. Clients that set settings on create, such
 * as terraform-provider-aws's {@code setting} block, then proposed the same change on every plan
 * after apply, because the state they read never matched the configuration they sent.
 *
 * <p>Real AWS accepts settings on CreateCluster itself, not only through UpdateClusterSettings.
 */
@QuarkusTest
class EcsCreateClusterSettingsIntegrationTest {

    private static final String TARGET_PREFIX = "AmazonEC2ContainerServiceV20141113.";
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static io.restassured.specification.RequestSpecification ecs(String action) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET_PREFIX + action);
    }

    @Test
    void settingsGivenOnCreateAreReturnedByCreateAndSurviveToDescribe() {
        String cluster = "create-settings-" + Long.toString(System.nanoTime(), 36);

        ecs("CreateCluster")
            .body("""
                {"clusterName": "%s",
                 "settings": [{"name": "containerInsights", "value": "enabled"}]}
                """.formatted(cluster))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("cluster.clusterName", equalTo(cluster))
            .body("cluster.settings", hasSize(1))
            .body("cluster.settings[0].name", equalTo("containerInsights"))
            .body("cluster.settings[0].value", equalTo("enabled"));

        // The drift the issue describes is not in the CreateCluster response but in what a later
        // read reports, which is what a client compares its configuration against.
        ecs("DescribeClusters")
            .body("""
                {"clusters": ["%s"]}
                """.formatted(cluster))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("clusters[0].settings", hasSize(1))
            .body("clusters[0].settings[0].name", equalTo("containerInsights"))
            .body("clusters[0].settings[0].value", equalTo("enabled"));
    }

    @Test
    void aClusterCreatedWithoutSettingsReportsNone() {
        // The absent case must stay absent rather than gaining an empty array, since a client
        // diffing its configuration would see that as a change too.
        String cluster = "create-nosettings-" + Long.toString(System.nanoTime(), 36);

        ecs("CreateCluster")
            .body("""
                {"clusterName": "%s"}
                """.formatted(cluster))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("cluster.clusterName", equalTo(cluster))
            .body("cluster.settings", org.hamcrest.Matchers.nullValue());
    }
}
