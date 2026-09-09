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
            .body("clusterInfo.provisioned.currentBrokerSoftwareInfo.kafkaVersion", equalTo("3.5.1"));
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
            .body("clusterInfo.provisioned.currentBrokerSoftwareInfo.kafkaVersion", equalTo("3.6.0"));
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
    void createClusterV1EchoesEncryptionClientAuthenticationAndLogging() {
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
                  "loggingInfo": {"brokerLogs": {"s3": {"bucket": "msk-logs", "enabled": true, "prefix": "kafka"}}}
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
            .body("clusterInfo.loggingInfo.brokerLogs.s3.prefix", equalTo("kafka"));
    }

    // configurationInfo is a CreateCluster request member with no matching response member:
    // AWS reports the cluster's configuration on currentBrokerSoftwareInfo instead, which is
    // where terraform-provider-aws reads it back from.
    @Test
    void createClusterV1ReportsConfigurationOnCurrentBrokerSoftwareInfo() {
        String clusterArn = given()
            .contentType("application/json")
            .body("""
                {
                  "clusterName": "v1-configuration-test",
                  "kafkaVersion": "3.6.0",
                  "numberOfBrokerNodes": 1,
                  "configurationInfo": {
                    "arn": "arn:aws:kafka:us-east-1:123456789012:configuration/conf/1",
                    "revision": 3
                  }
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
            .body("clusterInfo.currentBrokerSoftwareInfo.configurationArn",
                    equalTo("arn:aws:kafka:us-east-1:123456789012:configuration/conf/1"))
            .body("clusterInfo.currentBrokerSoftwareInfo.configurationRevision", equalTo(3))
            .body("clusterInfo.currentBrokerSoftwareInfo.kafkaVersion", equalTo("3.6.0"))
            .body("clusterInfo", not(hasKey("configurationInfo")));
    }

    // AWS resolves these server-side when CreateCluster omits them and echoes the resolved
    // value back; returning null instead leaves a terraform plan permanently dirty.
    @Test
    void createClusterV1AppliesServerSideDefaultsForMonitoringAndEncryptionInTransit() {
        String clusterArn = given()
            .contentType("application/json")
            .body("""
                {"clusterName": "v1-defaults-test", "kafkaVersion": "3.6.0", "numberOfBrokerNodes": 1}
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
            .body("clusterInfo.enhancedMonitoring", equalTo("DEFAULT"))
            .body("clusterInfo.encryptionInfo.encryptionInTransit.clientBroker", equalTo("TLS_PLAINTEXT"))
            .body("clusterInfo.encryptionInfo.encryptionInTransit.inCluster", equalTo(true));
    }

    // The v2 ClusterInfo is not the flat v1 shape: everything provisioned-specific nests under
    // "provisioned", which is the only place an AWS SDK v2 client looks for it.
    @Test
    void describeClusterV2NestsProvisionedMetadataAndKeepsTagsTopLevel() {
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
                    "loggingInfo": {"brokerLogs": {"cloudWatchLogs": {"enabled": true, "logGroup": "msk-logs"}}},
                    "configurationInfo": {
                      "arn": "arn:aws:kafka:us-east-1:123456789012:configuration/conf/2",
                      "revision": 7
                    }
                  }
                }
                """)
        .when()
            .post("/api/v2/clusters")
        .then()
            .statusCode(200)
            .body("clusterType", equalTo("PROVISIONED"))
            .extract().path("clusterArn");

        given()
        .when()
            .get("/api/v2/clusters/{clusterArn}", clusterArn)
        .then()
            .statusCode(200)
            .body("clusterInfo.clusterType", equalTo("PROVISIONED"))
            .body("clusterInfo.clusterArn", equalTo(clusterArn))
            .body("clusterInfo.tags.Environment", equalTo("prod"))
            .body("clusterInfo.provisioned.numberOfBrokerNodes", equalTo(3))
            .body("clusterInfo.provisioned.currentBrokerSoftwareInfo.kafkaVersion", equalTo("3.5.1"))
            .body("clusterInfo.provisioned.currentBrokerSoftwareInfo.configurationArn",
                    equalTo("arn:aws:kafka:us-east-1:123456789012:configuration/conf/2"))
            .body("clusterInfo.provisioned.currentBrokerSoftwareInfo.configurationRevision", equalTo(7))
            .body("clusterInfo.provisioned.brokerNodeGroupInfo.instanceType", equalTo("kafka.t3.small"))
            .body("clusterInfo.provisioned.brokerNodeGroupInfo.clientSubnets", hasItem("subnet-ccc"))
            .body("clusterInfo.provisioned.clientAuthentication.unauthenticated.enabled", equalTo(true))
            .body("clusterInfo.provisioned.loggingInfo.brokerLogs.cloudWatchLogs.logGroup", equalTo("msk-logs"))
            .body("clusterInfo.provisioned.zookeeperConnectString", notNullValue())
            // the same members must NOT also appear flat, where a v2 client would not read them
            .body("clusterInfo", not(hasKey("numberOfBrokerNodes")))
            .body("clusterInfo", not(hasKey("brokerNodeGroupInfo")))
            .body("clusterInfo", not(hasKey("currentBrokerSoftwareInfo")))
            .body("clusterInfo", not(hasKey("clientAuthentication")))
            .body("clusterInfo", not(hasKey("loggingInfo")));
    }

    @Test
    void createClusterEchoesOpenMonitoringStorageModeAndRebalancing() {
        String clusterArn = given()
            .contentType("application/json")
            .body("""
                {
                  "clusterName": "v1-open-monitoring-test",
                  "kafkaVersion": "3.6.0",
                  "numberOfBrokerNodes": 1,
                  "storageMode": "TIERED",
                  "rebalancing": {"status": "ACTIVE"},
                  "openMonitoring": {
                    "prometheus": {
                      "jmxExporter": {"enabledInBroker": true},
                      "nodeExporter": {"enabledInBroker": false}
                    }
                  }
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
            .body("clusterInfo.storageMode", equalTo("TIERED"))
            .body("clusterInfo.rebalancing.status", equalTo("ACTIVE"))
            .body("clusterInfo.openMonitoring.prometheus.jmxExporter.enabledInBroker", equalTo(true))
            .body("clusterInfo.openMonitoring.prometheus.nodeExporter.enabledInBroker", equalTo(false));

        // and the v2 view nests them under provisioned, like every other provisioned member
        given()
        .when()
            .get("/api/v2/clusters/{clusterArn}", clusterArn)
        .then()
            .statusCode(200)
            .body("clusterInfo.provisioned.storageMode", equalTo("TIERED"))
            .body("clusterInfo.provisioned.openMonitoring.prometheus.jmxExporter.enabledInBroker", equalTo(true))
            .body("clusterInfo", not(hasKey("storageMode")))
            .body("clusterInfo", not(hasKey("openMonitoring")));
    }

    @Test
    void listClustersV2NestsProvisionedMetadataTheSameWayDescribeDoes() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "clusterName": "v2-list-test",
                  "provisioned": {"kafkaVersion": "3.6.0", "numberOfBrokerNodes": 2}
                }
                """)
        .when()
            .post("/api/v2/clusters")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/api/v2/clusters")
        .then()
            .statusCode(200)
            .body("clusterInfoList.find { it.clusterName == 'v2-list-test' }.clusterType",
                    equalTo("PROVISIONED"))
            .body("clusterInfoList.find { it.clusterName == 'v2-list-test' }.provisioned.numberOfBrokerNodes",
                    equalTo(2))
            .body("clusterInfoList.find { it.clusterName == 'v2-list-test' }.provisioned.currentBrokerSoftwareInfo.kafkaVersion",
                    equalTo("3.6.0"));
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
            .body("clusterInfo", not(hasKey("bootstrapBrokers")))
            .body("clusterInfo", not(hasKey("containerId")))
            .body("clusterInfo", not(hasKey("accountId")))
            .body("clusterInfo", not(hasKey("volumeId")));

        given()
        .when()
            .get("/api/v2/clusters/{clusterArn}", clusterArn)
        .then()
            .statusCode(200)
            .body("clusterInfo", not(hasKey("bootstrapBrokers")))
            .body("clusterInfo", not(hasKey("containerId")))
            .body("clusterInfo", not(hasKey("accountId")))
            .body("clusterInfo", not(hasKey("volumeId")))
            .body("clusterInfo.provisioned", not(hasKey("bootstrapBrokers")))
            .body("clusterInfo.provisioned", not(hasKey("containerId")))
            .body("clusterInfo.provisioned", not(hasKey("accountId")))
            .body("clusterInfo.provisioned", not(hasKey("volumeId")));
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

    // ── Tags (/v1/tags/{arn}, shared with AppSync behind V1TagsController) ────────────────

    @Test
    void clusterTagsRoundTripThroughTheTagEndpoints() {
        String clusterArn = given()
            .contentType("application/json")
            .body("""
                {"clusterName": "tag-cluster", "kafkaVersion": "3.6.0", "tags": {"Environment": "example"}}
                """)
        .when()
            .post("/v1/clusters")
        .then()
            .statusCode(200)
            .extract().path("clusterArn");

        // tags set at create time are visible to ListTagsForResource
        given()
            .urlEncodingEnabled(false)
        .when()
            .get("/v1/tags/" + clusterArn)
        .then()
            .statusCode(200)
            .body("tags.Environment", equalTo("example"));

        given()
            .contentType("application/json")
            .body("""
                {"tags": {"Team": "data", "Environment": "prod"}}
                """)
            .urlEncodingEnabled(false)
        .when()
            .post("/v1/tags/" + clusterArn)
        .then()
            .statusCode(204);

        given()
            .urlEncodingEnabled(false)
        .when()
            .get("/v1/tags/" + clusterArn)
        .then()
            .statusCode(200)
            .body("tags.Team", equalTo("data"))
            .body("tags.Environment", equalTo("prod"));

        // and DescribeCluster echoes the same set, so a tag update is not invisible to a refresh
        given()
        .when()
            .get("/v1/clusters/{clusterArn}", clusterArn)
        .then()
            .statusCode(200)
            .body("clusterInfo.tags.Team", equalTo("data"))
            .body("clusterInfo.tags.Environment", equalTo("prod"));

        given()
            .queryParam("tagKeys", "Team")
            .urlEncodingEnabled(false)
        .when()
            .delete("/v1/tags/" + clusterArn)
        .then()
            .statusCode(204);

        given()
            .urlEncodingEnabled(false)
        .when()
            .get("/v1/tags/" + clusterArn)
        .then()
            .statusCode(200)
            .body("tags", not(hasKey("Team")))
            .body("tags.Environment", equalTo("prod"));
    }

    @Test
    void configurationTagsRoundTripThroughTheTagEndpoints() {
        String properties = Base64.getEncoder()
                .encodeToString("auto.create.topics.enable=true".getBytes(StandardCharsets.UTF_8));
        String arn = given()
            .contentType("application/json")
            .body("""
                {"name": "tagged-config", "description": "d", "kafkaVersions": ["3.6.0"], "serverProperties": "%s"}
                """.formatted(properties))
        .when()
            .post("/v1/configurations")
        .then()
            .statusCode(200)
            .extract().path("arn");

        given()
            .contentType("application/json")
            .body("""
                {"tags": {"Owner": "platform"}}
                """)
            .urlEncodingEnabled(false)
        .when()
            .post("/v1/tags/" + arn)
        .then()
            .statusCode(204);

        given()
            .urlEncodingEnabled(false)
        .when()
            .get("/v1/tags/" + arn)
        .then()
            .statusCode(200)
            .body("tags.Owner", equalTo("platform"));

        // AWS's DescribeConfiguration shape has no tags member, so they stay out of that view
        given()
        .when()
            .get("/v1/configurations/{arn}", arn)
        .then()
            .statusCode(200)
            .body("$", not(hasKey("tags")));
    }

    @Test
    void tagEndpointsReturnNotFoundForAnUnknownKafkaArn() {
        String missing = "arn:aws:kafka:us-east-1:000000000000:cluster/nope/00000000-0000-0000-0000-000000000000";

        given()
            .urlEncodingEnabled(false)
        .when()
            .get("/v1/tags/" + missing)
        .then()
            .statusCode(404);
    }

    // ── CreateCluster validation ─────────────────────────────────────────────────────────

    @Test
    void createClusterRejectsAMissingClusterName() {
        given()
            .contentType("application/json")
            .body("""
                {"kafkaVersion": "3.6.0"}
                """)
        .when()
            .post("/v1/clusters")
        .then()
            .statusCode(400)
            .body("message", containsString("clusterName"));
    }

    @Test
    void createClusterRejectsOutOfRangeAndUnknownEnumValues() {
        given()
            .contentType("application/json")
            .body("""
                {"clusterName": "zero-brokers", "kafkaVersion": "3.6.0", "numberOfBrokerNodes": 0}
                """)
        .when()
            .post("/v1/clusters")
        .then()
            .statusCode(400)
            .body("message", containsString("numberOfBrokerNodes"))
            // MSK's Error schema names the offending member alongside the message
            .body("invalidParameter", equalTo("numberOfBrokerNodes"));

        given()
            .contentType("application/json")
            .body("""
                {
                  "clusterName": "bad-volume-size",
                  "kafkaVersion": "3.6.0",
                  "brokerNodeGroupInfo": {"storageInfo": {"ebsStorageInfo": {"volumeSize": 0}}}
                }
                """)
        .when()
            .post("/v1/clusters")
        .then()
            .statusCode(400)
            .body("message", containsString("volumeSize"));

        given()
            .contentType("application/json")
            .body("""
                {"clusterName": "bad-monitoring", "kafkaVersion": "3.6.0", "enhancedMonitoring": "SOMETIMES"}
                """)
        .when()
            .post("/v1/clusters")
        .then()
            .statusCode(400)
            .body("message", containsString("enhancedMonitoring"));

        given()
            .contentType("application/json")
            .body("""
                {
                  "clusterName": "bad-client-broker",
                  "kafkaVersion": "3.6.0",
                  "encryptionInfo": {"encryptionInTransit": {"clientBroker": "MAYBE"}}
                }
                """)
        .when()
            .post("/v1/clusters")
        .then()
            .statusCode(400)
            .body("message", containsString("clientBroker"));
    }

    // The SDK model caps numberOfBrokerNodes at 15, but the REST API reference documents no
    // maximum and the quota page allows 30 per ZooKeeper cluster and 60 per KRaft cluster, both
    // adjustable. Rejecting these would break clusters real MSK creates happily.
    @Test
    void createClusterAcceptsBrokerCountsAboveTheSdkModelCap() {
        String clusterArn = given()
            .contentType("application/json")
            .body("""
                {"clusterName": "thirty-brokers", "kafkaVersion": "3.6.0", "numberOfBrokerNodes": 30}
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
            .body("clusterInfo.numberOfBrokerNodes", equalTo(30));
    }

    /**
     * A fractional count must not be narrowed into a plausible one: Jackson's default
     * float-to-int coercion would otherwise turn 2.7 into 2 and report a cluster the caller
     * never asked for.
     */
    @Test
    void createClusterRejectsAFractionalBrokerCountInsteadOfTruncatingIt() {
        given()
            .contentType("application/json")
            .body("""
                {"clusterName": "v1-brokers-fractional-test", "kafkaVersion": "3.6.0",
                 "numberOfBrokerNodes": 2.7}
                """)
        .when()
            .post("/v1/clusters")
        .then()
            .statusCode(400)
            .body("invalidParameter", equalTo("numberOfBrokerNodes"));

        // ...and nothing was created under that name.
        given()
        .when()
            .get("/v1/clusters")
        .then()
            .statusCode(200)
            .body("clusterInfoList.clusterName", not(hasItem("v1-brokers-fractional-test")));
    }

    /**
     * A literal like 1.0000000000000001 has no exact double representation - parsing it as a
     * double collapses it to precisely 1.0, so a d == Math.rint(d) check performed after that
     * conversion would wrongly accept it as whole. This value must still be rejected: reading
     * the token as a BigDecimal (see BrokerCountDeserializer) catches the fractional part a
     * double comparison already lost.
     */
    @Test
    void createClusterRejectsABrokerCountThatOnlyLooksWholeAsADouble() {
        given()
            .contentType("application/json")
            .body("""
                {"clusterName": "v1-brokers-precision-test", "kafkaVersion": "3.6.0",
                 "numberOfBrokerNodes": 1.0000000000000001}
                """)
        .when()
            .post("/v1/clusters")
        .then()
            .statusCode(400)
            .body("invalidParameter", equalTo("numberOfBrokerNodes"));

        given()
        .when()
            .get("/v1/clusters")
        .then()
            .statusCode(200)
            .body("clusterInfoList.clusterName", not(hasItem("v1-brokers-precision-test")));
    }

    /** A whole number written with a decimal point (e.g. 3.0) is not fractional and is accepted. */
    @Test
    void createClusterAcceptsAWholeNumberBrokerCountWrittenAsADecimal() {
        String clusterArn = given()
            .contentType("application/json")
            .body("""
                {"clusterName": "v1-brokers-whole-decimal-test", "kafkaVersion": "3.6.0",
                 "numberOfBrokerNodes": 3.0}
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
            .body("clusterInfo.numberOfBrokerNodes", equalTo(3));
    }

    /** The V2 path nests the count under provisioned and must reject a fractional value too. */
    @Test
    void createClusterV2RejectsAFractionalBrokerCount() {
        given()
            .contentType("application/json")
            .body("""
                {"clusterName": "v2-brokers-fractional-test",
                 "provisioned": {"kafkaVersion": "3.6.0", "numberOfBrokerNodes": 2.7}}
                """)
        .when()
            .post("/api/v2/clusters")
        .then()
            .statusCode(400)
            .body("invalidParameter", equalTo("numberOfBrokerNodes"));
    }

    // ── Serverless clusters ──────────────────────────────────────────────────────────────

    @Test
    void createClusterV2SupportsServerlessAndKeepsItOutOfTheV1Api() {
        String clusterArn = given()
            .contentType("application/json")
            .body("""
                {
                  "clusterName": "serverless-cluster",
                  "tags": {"Environment": "prod"},
                  "serverless": {
                    "vpcConfigs": [{"subnetIds": ["subnet-aaa", "subnet-bbb"], "securityGroupIds": ["sg-111"]}],
                    "clientAuthentication": {"sasl": {"iam": {"enabled": true}}}
                  }
                }
                """)
        .when()
            .post("/api/v2/clusters")
        .then()
            .statusCode(200)
            .body("clusterType", equalTo("SERVERLESS"))
            .extract().path("clusterArn");

        given()
        .when()
            .get("/api/v2/clusters/{clusterArn}", clusterArn)
        .then()
            .statusCode(200)
            .body("clusterInfo.clusterType", equalTo("SERVERLESS"))
            .body("clusterInfo.tags.Environment", equalTo("prod"))
            .body("clusterInfo.serverless.vpcConfigs[0].subnetIds", hasItem("subnet-aaa"))
            .body("clusterInfo.serverless.vpcConfigs[0].securityGroupIds", hasItem("sg-111"))
            .body("clusterInfo.serverless.clientAuthentication.sasl.iam.enabled", equalTo(true))
            // a serverless cluster has no provisioned envelope at all
            .body("clusterInfo", not(hasKey("provisioned")));

        // the v1 API predates serverless and cannot describe one
        given()
        .when()
            .get("/v1/clusters/{clusterArn}", clusterArn)
        .then()
            .statusCode(400)
            .body("message", containsString("DescribeClusterV2"));

        given()
        .when()
            .get("/v1/clusters")
        .then()
            .statusCode(200)
            .body("clusterInfoList.findAll { it.clusterName == 'serverless-cluster' }", hasSize(0));

        given()
        .when()
            .get("/api/v2/clusters")
        .then()
            .statusCode(200)
            .body("clusterInfoList.findAll { it.clusterName == 'serverless-cluster' }", hasSize(1));
    }

    @Test
    void createClusterV2RejectsBothProvisionedAndServerless() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "clusterName": "both-shapes",
                  "provisioned": {"kafkaVersion": "3.6.0"},
                  "serverless": {"vpcConfigs": [{"subnetIds": ["subnet-aaa"]}]}
                }
                """)
        .when()
            .post("/api/v2/clusters")
        .then()
            .statusCode(400)
            .body("message", containsString("Exactly one"));
    }
}
