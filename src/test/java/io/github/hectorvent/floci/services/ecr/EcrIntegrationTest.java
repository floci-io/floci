package io.github.hectorvent.floci.services.ecr;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * In-tree control-plane integration test for ECR. Does not require Docker —
 * the registry container is started lazily and these tests never trigger
 * ensureStarted().
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EcrIntegrationTest {

    private static final String CT = "application/x-amz-json-1.1";
    private static final String PREFIX = "AmazonEC2ContainerRegistry_V20150921.";
    private static final String REPO = "floci-it/integration";
    private static final String WEST_REGION = "us-west-2";
    private static final String WEST_CREDENTIAL =
            "AWS4-HMAC-SHA256 Credential=000000000000/20260923/" + WEST_REGION + "/ecr/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createRepository() {
        given()
            .header("X-Amz-Target", PREFIX + "CreateRepository")
            .contentType(CT)
            .body("""
                { "repositoryName": "%s" }
                """.formatted(REPO))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("repository.repositoryName", equalTo(REPO))
            .body("repository.repositoryArn", startsWith("arn:aws:ecr:"))
            .body("repository.repositoryArn", endsWith(":repository/" + REPO))
            .body("repository.repositoryUri", containsString("/" + REPO))
            .body("repository.repositoryUri", containsString("localhost:"))
            .body("repository.imageTagMutability", equalTo("MUTABLE"))
            .body("repository.imageScanningConfiguration.scanOnPush", equalTo(false));
    }

    @Test
    @Order(2)
    void createRepositoryDuplicateFails() {
        given()
            .header("X-Amz-Target", PREFIX + "CreateRepository")
            .contentType(CT)
            .body("""
                { "repositoryName": "%s" }
                """.formatted(REPO))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("RepositoryAlreadyExistsException"));
    }

    @Test
    @Order(3)
    void describeRepositoriesByName() {
        given()
            .header("X-Amz-Target", PREFIX + "DescribeRepositories")
            .contentType(CT)
            .body("""
                { "repositoryNames": ["%s"] }
                """.formatted(REPO))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("repositories[0].repositoryName", equalTo(REPO));
    }

    @Test
    @Order(4)
    void describeRepositoriesAll() {
        given()
            .header("X-Amz-Target", PREFIX + "DescribeRepositories")
            .contentType(CT)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("repositories", not(empty()));
    }

    @Test
    @Order(5)
    void describeMissingFails() {
        given()
            .header("X-Amz-Target", PREFIX + "DescribeRepositories")
            .contentType(CT)
            .body("""
                { "repositoryNames": ["does-not-exist-int"] }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("RepositoryNotFoundException"));
    }

    @Test
    @Order(6)
    void invalidRepoNameFails() {
        given()
            .header("X-Amz-Target", PREFIX + "CreateRepository")
            .contentType(CT)
            .body("""
                { "repositoryName": "Invalid_Caps" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    @Order(7)
    void getAuthorizationToken() {
        String token = given()
            .header("X-Amz-Target", PREFIX + "GetAuthorizationToken")
            .contentType(CT)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("authorizationData[0].authorizationToken", not(emptyString()))
            .body("authorizationData[0].proxyEndpoint", startsWith("http"))
            .body("authorizationData[0].expiresAt", notNullValue())
            .extract().jsonPath().getString("authorizationData[0].authorizationToken");

        String decoded = new String(Base64.getDecoder().decode(token));
        org.junit.jupiter.api.Assertions.assertTrue(decoded.startsWith("AWS:"),
                "Decoded auth token must start with 'AWS:' but was: " + decoded);
    }

    @Test
    @Order(8)
    void batchGetRepositoryScanningConfiguration() {
        given()
            .header("X-Amz-Target", PREFIX + "BatchGetRepositoryScanningConfiguration")
            .contentType(CT)
            .body("""
                { "repositoryNames": ["%s"] }
                """.formatted(REPO))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("scanningConfigurations[0].repositoryName", equalTo(REPO))
            .body("scanningConfigurations[0].repositoryArn", startsWith("arn:aws:ecr:"))
            .body("scanningConfigurations[0].scanOnPush", equalTo(false))
            .body("scanningConfigurations[0].scanFrequency", equalTo("MANUAL"))
            .body("scanningConfigurations[0].appliedScanFilters", empty())
            .body("failures", empty());
    }

    @Test
    @Order(9)
    void deleteRepositoryForce() {
        given()
            .header("X-Amz-Target", PREFIX + "DeleteRepository")
            .contentType(CT)
            .body("""
                { "repositoryName": "%s", "force": true }
                """.formatted(REPO))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("repository.repositoryName", equalTo(REPO));

        given()
            .header("X-Amz-Target", PREFIX + "DescribeRepositories")
            .contentType(CT)
            .body("""
                { "repositoryNames": ["%s"] }
                """.formatted(REPO))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("RepositoryNotFoundException"));
    }

    /**
     * An ECR registry is regional: the proxyEndpoint a client is told to docker login to must
     * name the same host the repositoryUri pushes to, so both have to carry the region of the
     * call rather than the emulator's configured default.
     */
    @Test
    @Order(10)
    void getAuthorizationToken_nonDefaultRegionRequest_returnsProxyEndpointForThatRegion() {
        String repository = "floci-it/region-scoped";
        String repositoryUri = given()
            .header("X-Amz-Target", PREFIX + "CreateRepository")
            .header("Authorization", WEST_CREDENTIAL)
            .contentType(CT)
            .body("""
                { "repositoryName": "%s" }
                """.formatted(repository))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("repository.repositoryUri", containsString(".dkr.ecr." + WEST_REGION + "."))
            .extract().jsonPath().getString("repository.repositoryUri");

        String proxyEndpoint = given()
            .header("X-Amz-Target", PREFIX + "GetAuthorizationToken")
            .header("Authorization", WEST_CREDENTIAL)
            .contentType(CT)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("authorizationData[0].proxyEndpoint", containsString(".dkr.ecr." + WEST_REGION + "."))
            .extract().jsonPath().getString("authorizationData[0].proxyEndpoint");

        assertEquals(
                repositoryUri.substring(0, repositoryUri.indexOf('/')),
                URI.create(proxyEndpoint).getAuthority(),
                "docker login target must be the registry host the repository URI pushes to");

        given()
            .header("X-Amz-Target", PREFIX + "DeleteRepository")
            .header("Authorization", WEST_CREDENTIAL)
            .contentType(CT)
            .body("""
                { "repositoryName": "%s", "force": true }
                """.formatted(repository))
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
