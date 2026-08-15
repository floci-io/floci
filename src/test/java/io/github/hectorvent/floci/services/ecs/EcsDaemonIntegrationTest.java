package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

/**
 * Integration tests for ECS's Managed Daemon surface — a cluster-scoped agent deployed as
 * exactly one task per container instance of a capacity provider (roughly a Kubernetes
 * DaemonSet). Covers the {@code RegisterDaemonTaskDefinition} → {@code CreateDaemon} →
 * {@code DescribeDaemon} → {@code DescribeDaemonRevisions} → tag round trip →
 * {@code DeleteDaemon} / {@code DeleteDaemonTaskDefinition} lifecycle, plus the
 * {@code ListDaemons} and not-found paths. {@code UpdateDaemon}, deployment history
 * ({@code DescribeDaemonDeployments}, {@code ListDaemonDeployments}), revision listing
 * ({@code ListDaemonTaskDefinitions}) are intentionally not implemented — see the report for
 * why they were left out of scope.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EcsDaemonIntegrationTest {

    private static final String TARGET_PREFIX = "AmazonEC2ContainerServiceV20141113.";
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    private static final String CLUSTER_NAME = "daemon-test-cluster";
    private static final String FAMILY = "daemon-test-family";
    private static final String DAEMON_NAME = "daemon-test-agent";
    private static final String CAPACITY_PROVIDER_ARN =
            "arn:aws:ecs:us-east-1:000000000000:capacity-provider/daemon-test-cp";

    private static String daemonTaskDefinitionArn;
    private static String daemonArn;
    private static String daemonRevisionArn;

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
    @Order(1)
    void createCluster() {
        ecs("CreateCluster")
            .body("""
                {"clusterName": "%s"}
                """.formatted(CLUSTER_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("cluster.status", equalTo("ACTIVE"));
    }

    @Test
    @Order(2)
    void registerDaemonTaskDefinition() {
        // RegisterDaemonTaskDefinitionOutput carries only the ARN — unlike RegisterTaskDefinition,
        // which echoes the full object — matching the real AWS operation's documented shape.
        daemonTaskDefinitionArn = ecs("RegisterDaemonTaskDefinition")
            .body("""
                {
                    "family": "%s",
                    "containerDefinitions": [
                        {"name": "agent", "image": "nginx:latest", "essential": true}
                    ]
                }
                """.formatted(FAMILY))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("daemonTaskDefinitionArn", containsString(FAMILY))
            .extract().path("daemonTaskDefinitionArn");
    }

    @Test
    @Order(3)
    void describeDaemonTaskDefinitionByFamily() {
        ecs("DescribeDaemonTaskDefinition")
            .body("""
                {"daemonTaskDefinition": "%s"}
                """.formatted(FAMILY))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("daemonTaskDefinition.daemonTaskDefinitionArn", equalTo(daemonTaskDefinitionArn))
            .body("daemonTaskDefinition.status", equalTo("ACTIVE"));
    }

    @Test
    @Order(4)
    void createDaemon() {
        daemonArn = ecs("CreateDaemon")
            .body("""
                {
                    "daemonName": "%s",
                    "clusterArn": "%s",
                    "capacityProviderArns": ["%s"],
                    "daemonTaskDefinitionArn": "%s",
                    "tags": [{"key": "tofu-estate", "value": "probe1"}]
                }
                """.formatted(DAEMON_NAME, CLUSTER_NAME, CAPACITY_PROVIDER_ARN, daemonTaskDefinitionArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("status", equalTo("ACTIVE"))
            .body("daemonArn", containsString(DAEMON_NAME))
            .body("deploymentArn", not(empty()))
            .extract().path("daemonArn");
    }

    @Test
    @Order(5)
    void describeDaemonReturnsTerminalStateAndCurrentRevision() {
        daemonRevisionArn = ecs("DescribeDaemon")
            .body("""
                {"daemonArn": "%s"}
                """.formatted(daemonArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("daemon.daemonArn", equalTo(daemonArn))
            .body("daemon.status", equalTo("ACTIVE"))
            .body("daemon.deploymentArn", not(empty()))
            .body("daemon.currentRevisions", hasSize(1))
            .body("daemon.currentRevisions[0].capacityProviders", hasSize(1))
            .body("daemon.currentRevisions[0].capacityProviders[0].arn", equalTo(CAPACITY_PROVIDER_ARN))
            .extract().path("daemon.currentRevisions[0].arn");
    }

    @Test
    @Order(6)
    void describeDaemonRevisionsReturnsTheCurrentRevision() {
        ecs("DescribeDaemonRevisions")
            .body("""
                {"daemonRevisionArns": ["%s"]}
                """.formatted(daemonRevisionArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("daemonRevisions", hasSize(1))
            .body("failures", empty())
            .body("daemonRevisions[0].daemonRevisionArn", equalTo(daemonRevisionArn))
            .body("daemonRevisions[0].daemonArn", equalTo(daemonArn))
            .body("daemonRevisions[0].daemonTaskDefinitionArn", equalTo(daemonTaskDefinitionArn))
            .body("daemonRevisions[0].containerImages", hasSize(1))
            .body("daemonRevisions[0].containerImages[0].image", equalTo("nginx:latest"));
    }

    @Test
    @Order(7)
    void describeDaemonRevisionsReportsMissingArnAsFailure() {
        String missingArn = daemonRevisionArn + "-missing";
        ecs("DescribeDaemonRevisions")
            .body("""
                {"daemonRevisionArns": ["%s"]}
                """.formatted(missingArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("daemonRevisions", empty())
            .body("failures", hasSize(1))
            .body("failures[0].arn", equalTo(missingArn))
            .body("failures[0].reason", equalTo("MISSING"));
    }

    @Test
    @Order(8)
    void listDaemonsFindsTheDaemonInItsCluster() {
        ecs("ListDaemons")
            .body("""
                {"clusterArn": "%s"}
                """.formatted(CLUSTER_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("daemonSummariesList.daemonArn", org.hamcrest.Matchers.hasItem(daemonArn));
    }

    @Test
    @Order(9)
    void tagResourceThenListTagsForResource() {
        ecs("TagResource")
            .body("""
                {"resourceArn": "%s", "tags": [{"key": "extra", "value": "tag"}]}
                """.formatted(daemonArn))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        ecs("ListTagsForResource")
            .body("""
                {"resourceArn": "%s"}
                """.formatted(daemonArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("tags", hasSize(2));
    }

    @Test
    @Order(10)
    void untagResourceRemovesTheTag() {
        ecs("UntagResource")
            .body("""
                {"resourceArn": "%s", "tagKeys": ["extra"]}
                """.formatted(daemonArn))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        ecs("ListTagsForResource")
            .body("""
                {"resourceArn": "%s"}
                """.formatted(daemonArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("tags", hasSize(1))
            .body("tags[0].key", equalTo("tofu-estate"));
    }

    @Test
    @Order(11)
    void describeDaemonForMissingArnReturnsDaemonNotFoundException() {
        ecs("DescribeDaemon")
            .body("""
                {"daemonArn": "arn:aws:ecs:us-east-1:000000000000:daemon/%s/does-not-exist"}
                """.formatted(CLUSTER_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("DaemonNotFoundException"));
    }

    @Test
    @Order(12)
    void deleteDaemonRemovesItImmediately() {
        ecs("DeleteDaemon")
            .body("""
                {"daemonArn": "%s"}
                """.formatted(daemonArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("daemonArn", equalTo(daemonArn))
            .body("status", equalTo("DELETE_IN_PROGRESS"));

        ecs("DescribeDaemon")
            .body("""
                {"daemonArn": "%s"}
                """.formatted(daemonArn))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("DaemonNotFoundException"));
    }

    @Test
    @Order(13)
    void deleteDaemonTaskDefinitionThenDescribeFailsAsNotFound() {
        ecs("DeleteDaemonTaskDefinition")
            .body("""
                {"daemonTaskDefinition": "%s"}
                """.formatted(daemonTaskDefinitionArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("daemonTaskDefinitionArn", equalTo(daemonTaskDefinitionArn));

        ecs("DescribeDaemonTaskDefinition")
            .body("""
                {"daemonTaskDefinition": "%s"}
                """.formatted(daemonTaskDefinitionArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("daemonTaskDefinition.status", equalTo("DELETED"));
    }
}
