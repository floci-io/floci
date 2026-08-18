package io.github.hectorvent.floci.services.msk;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
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
}
