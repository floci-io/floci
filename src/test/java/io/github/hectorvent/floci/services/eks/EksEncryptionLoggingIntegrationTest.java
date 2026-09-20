package io.github.hectorvent.floci.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EksEncryptionLoggingIntegrationTest {

    private static final String JSON = "application/json";
    private static final String CLUSTER_WITH_ENC_LOG = "it-enc-log-cluster";
    private static final String CLUSTER_DEFAULT = "it-default-enc-log-cluster";
    private static final String KMS_KEY_ARN =
            "arn:aws:kms:us-east-1:000000000000:key/12345678-1234-1234-1234-123456789012";

    @Test
    @Order(1)
    void createClusterWithEncryptionConfigAndLoggingReturnsBothInWireResponse() {
        String payload = "{"
                + "\"name\":\"" + CLUSTER_WITH_ENC_LOG + "\","
                + "\"roleArn\":\"arn:aws:iam::000000000000:role/eks-role\","
                + "\"encryptionConfig\":[{"
                + "  \"resources\":[\"secrets\"],"
                + "  \"provider\":{\"keyArn\":\"" + KMS_KEY_ARN + "\"}"
                + "}],"
                + "\"logging\":{"
                + "  \"clusterLogging\":[{"
                + "    \"types\":[\"api\",\"audit\"],"
                + "    \"enabled\":true"
                + "  }]"
                + "}"
                + "}";

        given().contentType(JSON)
                .body(payload)
                .when().post("/clusters")
                .then().statusCode(200)
                .body("cluster.name", equalTo(CLUSTER_WITH_ENC_LOG))
                .body("cluster.encryptionConfig", hasSize(1))
                .body("cluster.encryptionConfig[0].resources", hasSize(1))
                .body("cluster.encryptionConfig[0].resources[0]", equalTo("secrets"))
                .body("cluster.encryptionConfig[0].provider.keyArn", equalTo(KMS_KEY_ARN))
                .body("cluster.logging.clusterLogging", hasSize(2))
                .body("cluster.logging.clusterLogging[0].enabled", equalTo(true))
                .body("cluster.logging.clusterLogging[0].types", containsInAnyOrder("api", "audit"))
                .body("cluster.logging.clusterLogging[1].enabled", equalTo(false))
                .body("cluster.logging.clusterLogging[1].types", containsInAnyOrder("authenticator", "controllerManager", "scheduler"));
    }

    @Test
    @Order(2)
    void describeClusterReturnsEncryptionConfigAndLoggingInWireResponse() {
        given().contentType(JSON)
                .when().get("/clusters/" + CLUSTER_WITH_ENC_LOG)
                .then().statusCode(200)
                .body("cluster.name", equalTo(CLUSTER_WITH_ENC_LOG))
                .body("cluster.encryptionConfig", hasSize(1))
                .body("cluster.encryptionConfig[0].resources", hasSize(1))
                .body("cluster.encryptionConfig[0].resources[0]", equalTo("secrets"))
                .body("cluster.encryptionConfig[0].provider.keyArn", equalTo(KMS_KEY_ARN))
                .body("cluster.logging.clusterLogging", hasSize(2))
                .body("cluster.logging.clusterLogging[0].enabled", equalTo(true))
                .body("cluster.logging.clusterLogging[0].types", containsInAnyOrder("api", "audit"))
                .body("cluster.logging.clusterLogging[1].enabled", equalTo(false))
                .body("cluster.logging.clusterLogging[1].types", containsInAnyOrder("authenticator", "controllerManager", "scheduler"));
    }

    @Test
    @Order(3)
    void deleteClusterWithEncryptionAndLoggingCleansUp() {
        given().contentType(JSON)
                .when().delete("/clusters/" + CLUSTER_WITH_ENC_LOG)
                .then().statusCode(200);
    }

    @Test
    @Order(4)
    void createClusterWithoutEncryptionConfigOrLoggingOmitsEncryptionConfigAndPopulatesDefaultLogging() {
        String payload = "{"
                + "\"name\":\"" + CLUSTER_DEFAULT + "\","
                + "\"roleArn\":\"arn:aws:iam::000000000000:role/eks-role\""
                + "}";

        given().contentType(JSON)
                .body(payload)
                .when().post("/clusters")
                .then().statusCode(200)
                .body("cluster.name", equalTo(CLUSTER_DEFAULT))
                .body("cluster.encryptionConfig", nullValue())
                .body("cluster.logging.clusterLogging", hasSize(1))
                .body("cluster.logging.clusterLogging[0].enabled", equalTo(false))
                .body("cluster.logging.clusterLogging[0].types",
                        containsInAnyOrder("api", "audit", "authenticator", "controllerManager", "scheduler"));
    }

    @Test
    @Order(5)
    void describeClusterWithoutEncryptionConfigOmitsEncryptionConfigAndReturnsDefaultLogging() {
        given().contentType(JSON)
                .when().get("/clusters/" + CLUSTER_DEFAULT)
                .then().statusCode(200)
                .body("cluster.name", equalTo(CLUSTER_DEFAULT))
                .body("cluster.encryptionConfig", nullValue())
                .body("cluster.logging.clusterLogging", hasSize(1))
                .body("cluster.logging.clusterLogging[0].enabled", equalTo(false))
                .body("cluster.logging.clusterLogging[0].types",
                        containsInAnyOrder("api", "audit", "authenticator", "controllerManager", "scheduler"));
    }

    @Test
    @Order(6)
    void deleteClusterDefaultCleansUp() {
        given().contentType(JSON)
                .when().delete("/clusters/" + CLUSTER_DEFAULT)
                .then().statusCode(200);
    }

    @Test
    @Order(7)
    void createClusterWithInvalidLogTypeReturns400() {
        String payload = "{"
                + "\"name\":\"it-invalid-log\","
                + "\"roleArn\":\"arn:aws:iam::000000000000:role/eks-role\","
                + "\"logging\":{"
                + "  \"clusterLogging\":[{"
                + "    \"types\":[\"invalidLogType\"],"
                + "    \"enabled\":true"
                + "  }]"
                + "}"
                + "}";

        given().contentType(JSON)
                .body(payload)
                .when().post("/clusters")
                .then().statusCode(400)
                .body("message", equalTo("'invalidLogType' is not a valid log type"));
    }

    @Test
    @Order(8)
    void createClusterWithInvalidEncryptionResourcesReturns400() {
        String payload = "{"
                + "\"name\":\"it-invalid-enc-res\","
                + "\"roleArn\":\"arn:aws:iam::000000000000:role/eks-role\","
                + "\"encryptionConfig\":[{"
                + "  \"resources\":[\"configmaps\"],"
                + "  \"provider\":{\"keyArn\":\"" + KMS_KEY_ARN + "\"}"
                + "}]"
                + "}";

        given().contentType(JSON)
                .body(payload)
                .when().post("/clusters")
                .then().statusCode(400)
                .body("message", equalTo("Invalid k8s resource and provider for encryption"));
    }

    @Test
    @Order(9)
    void createClusterWithMissingEncryptionKeyArnReturns400() {
        String payload = "{"
                + "\"name\":\"it-invalid-enc-key\","
                + "\"roleArn\":\"arn:aws:iam::000000000000:role/eks-role\","
                + "\"encryptionConfig\":[{"
                + "  \"resources\":[\"secrets\"],"
                + "  \"provider\":{\"keyArn\":\"\"}"
                + "}]"
                + "}";

        given().contentType(JSON)
                .body(payload)
                .when().post("/clusters")
                .then().statusCode(400)
                .body("message", equalTo("Invalid k8s resource and provider for encryption"));
    }
}
