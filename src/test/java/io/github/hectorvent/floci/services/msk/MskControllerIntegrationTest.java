package io.github.hectorvent.floci.services.msk;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
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
    void createClusterV1EchoesBrokerNodeGroupInfoNumberOfBrokerNodesAndTags() {
        String clusterArn = given()
            .contentType("application/json")
            .body("""
                {
                  "clusterName": "v1-metadata-test",
                  "kafkaVersion": "3.6.0",
                  "numberOfBrokerNodes": 3,
                  "brokerNodeGroupInfo": {
                    "instanceType": "kafka.m5.large",
                    "clientSubnets": ["subnet-aaa", "subnet-bbb"],
                    "securityGroups": ["sg-111"],
                    "storageInfo": {"ebsStorageInfo": {"volumeSize": 100}}
                  },
                  "tags": {"Environment": "example"}
                }
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
            .body("clusterInfo.numberOfBrokerNodes", equalTo(3))
            .body("clusterInfo.brokerNodeGroupInfo.instanceType", equalTo("kafka.m5.large"))
            .body("clusterInfo.brokerNodeGroupInfo.clientSubnets", hasItem("subnet-aaa"))
            .body("clusterInfo.brokerNodeGroupInfo.securityGroups", hasItem("sg-111"))
            .body("clusterInfo.brokerNodeGroupInfo.storageInfo.ebsStorageInfo.volumeSize", equalTo(100))
            .body("clusterInfo.tags.Environment", equalTo("example"));
    }

    @Test
    void createClusterV1EchoesEncryptionClientAuthenticationLoggingAndConfiguration() {
        String clusterArn = given()
            .contentType("application/json")
            .body("""
                {
                  "clusterName": "v1-security-test",
                  "kafkaVersion": "3.6.0",
                  "numberOfBrokerNodes": 2,
                  "brokerNodeGroupInfo": {
                    "instanceType": "kafka.m5.large",
                    "clientSubnets": ["subnet-aaa"]
                  },
                  "encryptionInfo": {
                    "encryptionInTransit": {"clientBroker": "TLS", "inCluster": true},
                    "encryptionAtRest": {"dataVolumeKMSKeyId": "arn:aws:kms:us-east-1:123456789012:key/abc"}
                  },
                  "clientAuthentication": {
                    "sasl": {"scram": {"enabled": true}, "iam": {"enabled": false}},
                    "tls": {"certificateAuthorityArnList": ["arn:aws:acm-pca:us-east-1:123456789012:certificate-authority/ca-1"], "enabled": true}
                  },
                  "enhancedMonitoring": "PER_BROKER",
                  "loggingInfo": {"brokerLogs": {"s3": {"bucket": "msk-logs", "enabled": true, "prefix": "kafka"}}},
                  "configurationInfo": {"arn": "arn:aws:kafka:us-east-1:123456789012:configuration/conf/1", "revision": 3}
                }
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
            .body("clusterInfo.encryptionInfo.encryptionInTransit.clientBroker", equalTo("TLS"))
            .body("clusterInfo.encryptionInfo.encryptionInTransit.inCluster", equalTo(true))
            .body("clusterInfo.encryptionInfo.encryptionAtRest.dataVolumeKMSKeyId", equalTo("arn:aws:kms:us-east-1:123456789012:key/abc"))
            .body("clusterInfo.clientAuthentication.sasl.scram.enabled", equalTo(true))
            .body("clusterInfo.clientAuthentication.sasl.iam.enabled", equalTo(false))
            .body("clusterInfo.clientAuthentication.tls.enabled", equalTo(true))
            .body("clusterInfo.enhancedMonitoring", equalTo("PER_BROKER"))
            .body("clusterInfo.loggingInfo.brokerLogs.s3.bucket", equalTo("msk-logs"))
            .body("clusterInfo.loggingInfo.brokerLogs.s3.prefix", equalTo("kafka"))
            .body("clusterInfo.configurationInfo.arn", equalTo("arn:aws:kafka:us-east-1:123456789012:configuration/conf/1"))
            .body("clusterInfo.configurationInfo.revision", equalTo(3));
    }

    @Test
    void createClusterV2EchoesProvisionedMetadataAndTopLevelTags() {
        String clusterArn = given()
            .contentType("application/json")
            .body("""
                {
                  "clusterName": "v2-metadata-test",
                  "tags": {"Environment": "prod"},
                  "provisioned": {
                    "kafkaVersion": "3.5.1",
                    "numberOfBrokerNodes": 3,
                    "brokerNodeGroupInfo": {
                      "instanceType": "kafka.t3.small",
                      "clientSubnets": ["subnet-ccc"]
                    },
                    "clientAuthentication": {"unauthenticated": {"enabled": true}},
                    "loggingInfo": {"brokerLogs": {"cloudWatchLogs": {"enabled": true, "logGroup": "msk-logs"}}}
                  }
                }
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
            .body("clusterInfo.currentBrokerSoftwareInfo.kafkaVersion", equalTo("3.5.1"))
            .body("clusterInfo.numberOfBrokerNodes", equalTo(3))
            .body("clusterInfo.brokerNodeGroupInfo.instanceType", equalTo("kafka.t3.small"))
            .body("clusterInfo.brokerNodeGroupInfo.clientSubnets", hasItem("subnet-ccc"))
            .body("clusterInfo.clientAuthentication.unauthenticated.enabled", equalTo(true))
            .body("clusterInfo.loggingInfo.brokerLogs.cloudWatchLogs.logGroup", equalTo("msk-logs"))
            .body("clusterInfo.tags.Environment", equalTo("prod"));
    }

    @Test
    void describeClusterDoesNotLeakInternalFields() {
        String clusterArn = given()
            .contentType("application/json")
            .body("""
                {"clusterName": "v1-no-internal-fields-test", "kafkaVersion": "3.6.0"}
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
            .body("clusterInfo", notNullValue())
            .body("clusterInfo.clusterArn", equalTo(clusterArn))
            .body("clusterInfo.containsKey('bootstrapBrokers')", equalTo(false))
            .body("clusterInfo.containsKey('containerId')", equalTo(false))
            .body("clusterInfo.containsKey('volumeId')", equalTo(false));
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

        // Real MSK signals a deleted configuration as BadRequestException, and the terraform/pulumi
        // provider's delete waiter only recognizes that code plus this exact message substring as
        // "gone" - assert the full wire contract, not just a status code.
        given()
        .when()
            .get("/v1/configurations/{arn}", arn)
        .then()
            .statusCode(400)
            .header("X-Amzn-Errortype", equalTo("BadRequestException"))
            .body("message", containsString("Configuration ARN does not exist"));
    }

    // An empty base64 blob decodes to "" and means "no property overrides". Absent and
    // present-but-empty stay distinguishable at the REST layer: a missing member arrives as
    // null, an empty one as a zero-length String, so only the former is rejected.
    @Test
    void createAndUpdateConfigurationAcceptEmptyServerProperties() {
        String arn = given()
            .contentType("application/json")
            .body("""
                {"name": "empty-props-%s", "kafkaVersions": ["3.6.0"], "serverProperties": ""}
                """.formatted(UUID.randomUUID().toString().substring(0, 8)))
        .when()
            .post("/v1/configurations")
        .then()
            .statusCode(200)
            .body("state", equalTo("ACTIVE"))
            .body("latestRevision.revision", equalTo(1))
            .extract().path("arn");

        given()
            .contentType("application/json")
            .body("""
                {"description": "still empty", "serverProperties": ""}
                """)
        .when()
            .put("/v1/configurations/{arn}", arn)
        .then()
            .statusCode(200)
            .body("latestRevision.revision", equalTo(2));

        // Both revisions, not just the one create wrote: an empty update has to store ""
        // rather than silently carry the previous revision's properties forward.
        for (int revision : new int[] { 1, 2 }) {
            given()
            .when()
                .get("/v1/configurations/{arn}/revisions/{revision}", arn, revision)
            .then()
                .statusCode(200)
                .body("serverProperties", equalTo(""));
        }
    }

    @Test
    void createConfigurationRejectsMissingServerProperties() {
        given()
            .contentType("application/json")
            .body("""
                {"name": "no-props-config", "kafkaVersions": ["3.6.0"]}
                """)
        .when()
            .post("/v1/configurations")
        .then()
            .statusCode(400);
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
    void describeConfigurationReturnsBadRequestForUnknownArn() {
        given()
        .when()
            .get("/v1/configurations/{arn}", "arn:aws:kafka:us-east-1:000000000000:configuration/missing/id")
        .then()
            .statusCode(400)
            .header("X-Amzn-Errortype", equalTo("BadRequestException"))
            .body("message", containsString("Configuration ARN does not exist"));
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
    void updateConfigurationReturnsBadRequestForUnknownArn() {
        given()
            .contentType("application/json")
            .body("{\"serverProperties\": \"cHJvcHM=\"}")
        .when()
            .put("/v1/configurations/{arn}", "arn:aws:kafka:us-east-1:000000000000:configuration/missing/id")
        .then()
            .statusCode(400)
            .header("X-Amzn-Errortype", equalTo("BadRequestException"))
            .body("message", containsString("Configuration ARN does not exist"));
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
