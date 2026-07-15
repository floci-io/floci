package io.github.hectorvent.floci.services.lightsail;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class LightsailIntegrationTest {
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "Lightsail_20161128.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void instanceDiskAndStaticIpLifecycleRoundTripsThroughJsonHandler() {
        given()
                .header("X-Amz-Target", TARGET_PREFIX + "GetBlueprints")
                .contentType(CONTENT_TYPE)
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("blueprints", hasSize(4))
                .body("blueprints[0].blueprintId", equalTo("ubuntu_22_04"));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "CreateInstances")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                          "instanceNames": ["web-a"],
                          "availabilityZone": "us-east-1a",
                          "blueprintId": "ubuntu_22_04",
                          "bundleId": "nano_3_0",
                          "tags": [{"key": "component", "value": "web"}]
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("operations", hasSize(1))
                .body("operations[0].operationType", equalTo("CreateInstance"))
                .body("operations[0].status", equalTo("Succeeded"));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "GetInstance")
                .contentType(CONTENT_TYPE)
                .body("""
                        {"instanceName": "web-a"}
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("instance.name", equalTo("web-a"))
                .body("instance.arn", startsWith("arn:aws:lightsail:"))
                .body("instance.state.name", equalTo("running"))
                .body("instance.tags[0].key", equalTo("component"));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "StopInstance")
                .contentType(CONTENT_TYPE)
                .body("""
                        {"instanceName": "web-a"}
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("operations[0].operationType", equalTo("StopInstance"));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "GetInstanceState")
                .contentType(CONTENT_TYPE)
                .body("""
                        {"instanceName": "web-a"}
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("state.name", equalTo("stopped"))
                .body("state.code", equalTo(80));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "CreateDisk")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                          "diskName": "web-data",
                          "availabilityZone": "us-east-1a",
                          "sizeInGb": 8
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("operations[0].operationType", equalTo("CreateDisk"));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "AttachDisk")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                          "diskName": "web-data",
                          "instanceName": "web-a",
                          "diskPath": "/dev/xvdf"
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("operations[0].operationType", equalTo("AttachDisk"));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "GetDisk")
                .contentType(CONTENT_TYPE)
                .body("""
                        {"diskName": "web-data"}
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("disk.name", equalTo("web-data"))
                .body("disk.attachedTo", equalTo("web-a"))
                .body("disk.isAttached", equalTo(true));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "AllocateStaticIp")
                .contentType(CONTENT_TYPE)
                .body("""
                        {"staticIpName": "web-ip"}
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("operations[0].operationType", equalTo("AllocateStaticIp"));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "AttachStaticIp")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                          "staticIpName": "web-ip",
                          "instanceName": "web-a"
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("operations[0].operationType", equalTo("AttachStaticIp"));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "GetStaticIp")
                .contentType(CONTENT_TYPE)
                .body("""
                        {"staticIpName": "web-ip"}
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("staticIp.name", equalTo("web-ip"))
                .body("staticIp.attachedTo", equalTo("web-a"))
                .body("staticIp.isAttached", equalTo(true));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "GetOperationsForResource")
                .contentType(CONTENT_TYPE)
                .body("""
                        {"resourceName": "web-a"}
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("operations", hasSize(2))
                .body("operations[0].id", notNullValue());
    }

    @Test
    void keyPairsTagsRegionsAndUnsupportedOperationsUseAwsJsonShape() {
        given()
                .header("X-Amz-Target", TARGET_PREFIX + "CreateKeyPair")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                          "keyPairName": "deployer",
                          "tags": [{"key": "owner", "value": "platform"}]
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("keyPair.name", equalTo("deployer"))
                .body("publicKeyBase64", notNullValue())
                .body("privateKeyBase64", notNullValue())
                .body("operation.operationType", equalTo("CreateKeyPair"));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "TagResource")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                          "resourceName": "deployer",
                          "tags": [{"key": "env", "value": "test"}]
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("operations[0].operationType", equalTo("TagResource"));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "GetKeyPair")
                .contentType(CONTENT_TYPE)
                .body("""
                        {"keyPairName": "deployer"}
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("keyPair.name", equalTo("deployer"))
                .body("keyPair.tags", hasSize(2));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "GetRegions")
                .contentType(CONTENT_TYPE)
                .body("""
                        {"includeAvailabilityZones": true}
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("regions", hasSize(4))
                .body("regions[0].availabilityZones", hasSize(2));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "GetBuckets")
                .contentType(CONTENT_TYPE)
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("buckets", hasSize(0));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "CreateContainerService")
                .contentType(CONTENT_TYPE)
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("UnsupportedOperation"))
                .body("message", equalTo("Operation CreateContainerService is recognized by Amazon Lightsail but is not implemented in Floci."));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "NotARealAction")
                .contentType(CONTENT_TYPE)
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(404)
                .body("__type", equalTo("UnknownOperationException"))
                .body("message", equalTo("Unknown operation NotARealAction"));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "GetKeyPair")
                .contentType(CONTENT_TYPE)
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidInputException"))
                .body("message", equalTo("keyPairName is required"));
    }

    @Test
    void diskStaticIpAndHardwareEdgeCasesMatchLightsailBehavior() {
        given()
                .header("X-Amz-Target", TARGET_PREFIX + "CreateInstances")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                          "instanceNames": ["review-web"],
                          "availabilityZone": "us-east-1a",
                          "blueprintId": "ubuntu_22_04",
                          "bundleId": "medium_3_0"
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "GetInstance")
                .contentType(CONTENT_TYPE)
                .body("""
                        {"instanceName": "review-web"}
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("instance.hardware.cpuCount", equalTo(2))
                .body("instance.hardware.ramSizeInGb", equalTo(4.0f));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "CreateInstances")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                          "instanceNames": ["review-web-b"],
                          "availabilityZone": "us-east-1a",
                          "blueprintId": "ubuntu_22_04",
                          "bundleId": "micro_3_0"
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "CreateDisk")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                          "diskName": "review-data",
                          "availabilityZone": "us-east-1a",
                          "sizeInGb": 8
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "AttachDisk")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                          "diskName": "review-data",
                          "instanceName": "review-web"
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "AttachDisk")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                          "diskName": "review-data",
                          "instanceName": "review-web-b"
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidInputException"));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "DeleteDisk")
                .contentType(CONTENT_TYPE)
                .body("""
                        {"diskName": "review-data"}
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidInputException"));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "DetachDisk")
                .contentType(CONTENT_TYPE)
                .body("""
                        {"diskName": "review-data"}
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "DetachDisk")
                .contentType(CONTENT_TYPE)
                .body("""
                        {"diskName": "review-data"}
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidInputException"));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "AllocateStaticIp")
                .contentType(CONTENT_TYPE)
                .body("""
                        {"staticIpName": "review-ip"}
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "AttachStaticIp")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                          "staticIpName": "review-ip",
                          "instanceName": "review-web"
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "AttachStaticIp")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                          "staticIpName": "review-ip",
                          "instanceName": "review-web-b"
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidInputException"));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "ReleaseStaticIp")
                .contentType(CONTENT_TYPE)
                .body("""
                        {"staticIpName": "review-ip"}
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("operations[0].operationType", equalTo("ReleaseStaticIp"));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "GetOperationsForResource")
                .contentType(CONTENT_TYPE)
                .body("""
                        {"resourceName": "review-ip"}
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("operations.operationType", hasItems("AllocateStaticIp", "AttachStaticIp", "ReleaseStaticIp"))
                .body("operations.operationType", not(hasItem("DetachStaticIp")));
    }
}
