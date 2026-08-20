package io.github.hectorvent.floci.services.msk;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class MskControllerIntegrationTest {

    @Test
    void createClusterV1EchoesRequestedKafkaVersion() {
        String clusterArn = given()
            .contentType("application/json")
            .body("""
                {"clusterName": "v1-version-test", "kafkaVersion": "3.5.1"}
                """)
        .when()
            .post("/v1/clusters")
        .then()
            .statusCode(200)
            .extract().path("clusterArn");

        given()
        .when()
            .get("/v1/clusters/{clusterArn}", clusterArn)
        .then()
            .statusCode(200)
            .body("clusterInfo.currentBrokerSoftwareInfo.kafkaVersion", equalTo("3.5.1"));
    }

    @Test
    void createClusterV2EchoesRequestedKafkaVersionFromProvisioned() {
        String clusterArn = given()
            .contentType("application/json")
            .body("""
                {"clusterName": "v2-version-test", "provisioned": {"kafkaVersion": "3.5.1"}}
                """)
        .when()
            .post("/api/v2/clusters")
        .then()
            .statusCode(200)
            .extract().path("clusterArn");

        given()
        .when()
            .get("/api/v2/clusters/{clusterArn}", clusterArn)
        .then()
            .statusCode(200)
            .body("clusterInfo.currentBrokerSoftwareInfo.kafkaVersion", equalTo("3.5.1"));
    }

    @Test
    void createClusterV2WithoutProvisionedFallsBackToDefaultKafkaVersion() {
        String clusterArn = given()
            .contentType("application/json")
            .body("""
                {"clusterName": "v2-default-version-test"}
                """)
        .when()
            .post("/api/v2/clusters")
        .then()
            .statusCode(200)
            .extract().path("clusterArn");

        given()
        .when()
            .get("/api/v2/clusters/{clusterArn}", clusterArn)
        .then()
            .statusCode(200)
            .body("clusterInfo.currentBrokerSoftwareInfo.kafkaVersion", equalTo("3.6.0"));
    }

    @Test
    void configurationCrudRoundTrip() {
        String properties = "auto.create.topics.enable=true\nlog.retention.hours=168";
        String propertiesB64 = Base64.getEncoder().encodeToString(properties.getBytes(StandardCharsets.UTF_8));

        String arn = given()
            .contentType("application/json")
            .body("""
                {"name": "test-config", "description": "a test config", "kafkaVersions": ["3.6.0"], "serverProperties": "%s"}
                """.formatted(propertiesB64))
        .when()
            .post("/v1/configurations")
        .then()
            .statusCode(200)
            .body("name", equalTo("test-config"))
            .body("state", equalTo("ACTIVE"))
            .body("latestRevision.revision", equalTo(1))
            .extract().path("arn");

        given()
        .when()
            .get("/v1/configurations/{arn}", arn)
        .then()
            .statusCode(200)
            .body("name", equalTo("test-config"))
            .body("description", equalTo("a test config"))
            .body("kafkaVersions", hasSize(1))
            .body("arn", equalTo(arn));

        given()
        .when()
            .get("/v1/configurations")
        .then()
            .statusCode(200)
            .body("configurations.name", hasItem("test-config"));

        given()
        .when()
            .delete("/v1/configurations/{arn}", arn)
        .then()
            .statusCode(200)
            .body("arn", equalTo(arn))
            .body("state", equalTo("DELETING"));

        given()
        .when()
            .get("/v1/configurations/{arn}", arn)
        .then()
            .statusCode(404);
    }

    @Test
    void createConfigurationRejectsNonBase64ServerProperties() {
        given()
            .contentType("application/json")
            .body("""
                {"name": "bad-config", "kafkaVersions": ["3.6.0"], "serverProperties": "not-valid-base64!!"}
                """)
        .when()
            .post("/v1/configurations")
        .then()
            .statusCode(400);
    }

    // A wrong-typed field must fail with an AWS-shaped 400, not an unhandled
    // ClassCastException surfacing as a 500.
    @Test
    void createConfigurationRejectsNonStringName() {
        given()
            .contentType("application/json")
            .body("""
                {"name": 123, "kafkaVersions": ["3.6.0"], "serverProperties": "cHJvcHM="}
                """)
        .when()
            .post("/v1/configurations")
        .then()
            .statusCode(400);
    }

    @Test
    void createConfigurationRejectsNonArrayKafkaVersions() {
        given()
            .contentType("application/json")
            .body("""
                {"name": "bad-config", "kafkaVersions": "3.6.0", "serverProperties": "cHJvcHM="}
                """)
        .when()
            .post("/v1/configurations")
        .then()
            .statusCode(400);
    }

    @Test
    void createConfigurationRejectsKafkaVersionsWithNonStringElements() {
        given()
            .contentType("application/json")
            .body("""
                {"name": "bad-config", "kafkaVersions": [3.6], "serverProperties": "cHJvcHM="}
                """)
        .when()
            .post("/v1/configurations")
        .then()
            .statusCode(400);
    }

    @Test
    void describeConfigurationReturnsNotFoundForUnknownArn() {
        given()
        .when()
            .get("/v1/configurations/{arn}", "arn:aws:kafka:us-east-1:000000000000:configuration/missing/id")
        .then()
            .statusCode(404);
    }

    // kafkaVersions is optional on CreateConfigurationRequest. Omitting it must not leak a
    // null into the "kafkaVersions" field of the Configuration shape returned by
    // DescribeConfiguration/ListConfigurations, which AWS always populates as an array.
    @Test
    void configurationWithoutKafkaVersionsReturnsEmptyArrayNotNull() {
        String properties = Base64.getEncoder().encodeToString("props".getBytes(StandardCharsets.UTF_8));
        String arn = given()
            .contentType("application/json")
            .body("""
                {"name": "no-versions-config", "serverProperties": "%s"}
                """.formatted(properties))
        .when()
            .post("/v1/configurations")
        .then()
            .statusCode(200)
            .extract().path("arn");

        given()
        .when()
            .get("/v1/configurations/{arn}", arn)
        .then()
            .statusCode(200)
            .body("kafkaVersions", empty());

        given()
        .when()
            .get("/v1/configurations")
        .then()
            .statusCode(200)
            .body("configurations.find { it.arn == '" + arn + "' }.kafkaVersions", empty());
    }

    @Test
    void listConfigurationsPaginatesWithMaxResultsAndNextToken() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String properties = Base64.getEncoder().encodeToString("props".getBytes(StandardCharsets.UTF_8));

        given().contentType("application/json")
            .body("""
                {"name": "page-a-%s", "serverProperties": "%s"}
                """.formatted(suffix, properties))
            .when().post("/v1/configurations")
            .then().statusCode(200);

        given().contentType("application/json")
            .body("""
                {"name": "page-b-%s", "serverProperties": "%s"}
                """.formatted(suffix, properties))
            .when().post("/v1/configurations")
            .then().statusCode(200);

        var page1 = given()
            .when().get("/v1/configurations?maxResults=1")
            .then().statusCode(200)
            .body("configurations", hasSize(1))
            .body("nextToken", notNullValue())
            .extract().jsonPath();

        String page1Arn = page1.getString("configurations[0].arn");
        String token = page1.getString("nextToken");

        given()
            .when().get("/v1/configurations?maxResults=1&nextToken=" + token)
            .then().statusCode(200)
            .body("configurations", hasSize(1))
            .body("configurations[0].arn", not(equalTo(page1Arn)));
    }

    @Test
    void listConfigurationsRejectsMaxResultsAboveLimit() {
        given()
            .when().get("/v1/configurations?maxResults=101")
            .then().statusCode(400);
    }

    // AWS declares MaxResults with a minimum of 1; 0 is real out-of-range input, not a
    // synonym for "omitted" (that's an absent query param instead).
    @Test
    void listConfigurationsRejectsZeroMaxResults() {
        given()
            .when().get("/v1/configurations?maxResults=0")
            .then().statusCode(400);
    }

    // maxResults is bound as a raw String and parsed by hand rather than @QueryParam
    // Integer specifically because a non-numeric value for an Integer-typed @QueryParam
    // fails RESTEasy Reactive's own conversion before the method body runs, and its
    // default handling for that is a 404, not an AWS-shaped 400.
    @Test
    void listConfigurationsRejectsNonNumericMaxResults() {
        given()
            .when().get("/v1/configurations?maxResults=abc")
            .then().statusCode(400);
    }

    @Test
    void listConfigurationsRejectsInvalidNextToken() {
        given()
            .when().get("/v1/configurations?nextToken=not-a-valid-token!!")
            .then().statusCode(400);
    }

    @Test
    void updateConfigurationAndRevisionRoundTrip() {
        String propsV1 = Base64.getEncoder().encodeToString("auto.create.topics.enable=true".getBytes(StandardCharsets.UTF_8));
        String propsV2 = Base64.getEncoder().encodeToString("auto.create.topics.enable=false".getBytes(StandardCharsets.UTF_8));

        String arn = given()
            .contentType("application/json")
            .body("""
                {"name": "revision-test", "description": "v1", "kafkaVersions": ["3.6.0"], "serverProperties": "%s"}
                """.formatted(propsV1))
        .when()
            .post("/v1/configurations")
        .then()
            .statusCode(200)
            .extract().path("arn");

        given()
            .contentType("application/json")
            .body("""
                {"description": "v2", "serverProperties": "%s"}
                """.formatted(propsV2))
        .when()
            .put("/v1/configurations/{arn}", arn)
        .then()
            .statusCode(200)
            .body("arn", equalTo(arn))
            .body("latestRevision.revision", equalTo(2));

        given()
        .when()
            .get("/v1/configurations/{arn}/revisions", arn)
        .then()
            .statusCode(200)
            .body("revisions", hasSize(2))
            .body("revisions[0].revision", equalTo(1))
            .body("revisions[1].revision", equalTo(2))
            // AWS's ConfigurationRevision shape never includes serverProperties.
            .body("revisions[0]", not(hasKey("serverProperties")));

        given()
        .when()
            .get("/v1/configurations/{arn}/revisions/1", arn)
        .then()
            .statusCode(200)
            .body("arn", equalTo(arn))
            .body("revision", equalTo(1))
            .body("description", equalTo("v1"))
            .body("serverProperties", equalTo(propsV1));

        given()
        .when()
            .get("/v1/configurations/{arn}/revisions/2", arn)
        .then()
            .statusCode(200)
            .body("revision", equalTo(2))
            .body("description", equalTo("v2"))
            .body("serverProperties", equalTo(propsV2));

        // DescribeConfiguration/ListConfigurations still never leak serverProperties.
        given()
        .when()
            .get("/v1/configurations/{arn}", arn)
        .then()
            .statusCode(200)
            .body("latestRevision.revision", equalTo(2))
            .body("$", not(hasKey("serverProperties")));
    }

    @Test
    void updateConfigurationReturnsNotFoundForUnknownArn() {
        given()
            .contentType("application/json")
            .body("{\"serverProperties\": \"cHJvcHM=\"}")
        .when()
            .put("/v1/configurations/{arn}", "arn:aws:kafka:us-east-1:000000000000:configuration/missing/id")
        .then()
            .statusCode(404);
    }

    @Test
    void updateConfigurationRejectsMissingServerProperties() {
        String propsV1 = Base64.getEncoder().encodeToString("props".getBytes(StandardCharsets.UTF_8));
        String arn = given()
            .contentType("application/json")
            .body("""
                {"name": "update-missing-props", "serverProperties": "%s"}
                """.formatted(propsV1))
        .when()
            .post("/v1/configurations")
        .then()
            .statusCode(200)
            .extract().path("arn");

        given()
            .contentType("application/json")
            .body("{\"description\": \"v2\"}")
        .when()
            .put("/v1/configurations/{arn}", arn)
        .then()
            .statusCode(400);
    }

    @Test
    void describeConfigurationRevisionReturnsNotFoundForUnknownRevision() {
        String propsV1 = Base64.getEncoder().encodeToString("props".getBytes(StandardCharsets.UTF_8));
        String arn = given()
            .contentType("application/json")
            .body("""
                {"name": "revision-not-found-test", "serverProperties": "%s"}
                """.formatted(propsV1))
        .when()
            .post("/v1/configurations")
        .then()
            .statusCode(200)
            .extract().path("arn");

        given()
        .when()
            .get("/v1/configurations/{arn}/revisions/99", arn)
        .then()
            .statusCode(404);
    }

    @Test
    void describeConfigurationRevisionRejectsNonNumericRevision() {
        String propsV1 = Base64.getEncoder().encodeToString("props".getBytes(StandardCharsets.UTF_8));
        String arn = given()
            .contentType("application/json")
            .body("""
                {"name": "revision-bad-path-test", "serverProperties": "%s"}
                """.formatted(propsV1))
        .when()
            .post("/v1/configurations")
        .then()
            .statusCode(200)
            .extract().path("arn");

        given()
        .when()
            .get("/v1/configurations/{arn}/revisions/abc", arn)
        .then()
            .statusCode(400);
    }
}
