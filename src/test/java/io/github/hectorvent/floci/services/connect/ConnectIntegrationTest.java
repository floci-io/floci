package io.github.hectorvent.floci.services.connect;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConnectIntegrationTest {

    private static String instanceId;
    private static String instanceArn;
    private static String associationId;

    @Test
    @Order(1)
    void createInstance() {
        var response = given()
            .contentType("application/json")
            .body("""
                {
                  "IdentityManagementType": "CONNECT_MANAGED",
                  "InstanceAlias": "floci-integration",
                  "InboundCallsEnabled": true,
                  "OutboundCallsEnabled": false,
                  "Tags": {"team": "cx"}
                }
                """)
        .when()
            .put("/instance")
        .then()
            .statusCode(200)
            .body("Id", notNullValue())
            .body("Arn", containsString(":connect:"))
            .body("Arn", containsString(":instance/"))
            .extract();
        instanceId = response.path("Id");
        instanceArn = response.path("Arn");
    }

    @Test
    @Order(2)
    void describeInstanceReturnsActiveAndEchoesRequest() {
        given()
        .when()
            .get("/instance/" + instanceId)
        .then()
            .statusCode(200)
            .body("Instance.Id", equalTo(instanceId))
            .body("Instance.Arn", equalTo(instanceArn))
            .body("Instance.InstanceStatus", equalTo("ACTIVE"))
            .body("Instance.IdentityManagementType", equalTo("CONNECT_MANAGED"))
            .body("Instance.InstanceAlias", equalTo("floci-integration"))
            .body("Instance.InboundCallsEnabled", equalTo(true))
            .body("Instance.OutboundCallsEnabled", equalTo(false))
            .body("Instance.ServiceRole", containsString(":role/"))
            .body("Instance.InstanceAccessUrl", containsString("floci-integration"))
            .body("Instance.CreatedTime", notNullValue())
            .body("Instance.Tags.team", equalTo("cx"));
    }

    @Test
    @Order(3)
    void listInstancesIncludesTheNewInstance() {
        given()
        .when()
            .get("/instance")
        .then()
            .statusCode(200)
            .body("InstanceSummaryList.Id", hasItem(instanceId))
            .body("InstanceSummaryList.find { it.Id == '" + instanceId + "' }.InstanceStatus",
                    equalTo("ACTIVE"))
            .body("InstanceSummaryList.find { it.Id == '" + instanceId + "' }.InstanceAlias",
                    equalTo("floci-integration"));
    }

    @Test
    @Order(4)
    void describeMissingInstanceReturnsResourceNotFound() {
        given()
        .when()
            .get("/instance/00000000-0000-0000-0000-00000000dead")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(5)
    void duplicateAliasReturnsResourceConflict() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "IdentityManagementType": "CONNECT_MANAGED",
                  "InstanceAlias": "floci-integration",
                  "InboundCallsEnabled": true,
                  "OutboundCallsEnabled": true
                }
                """)
        .when()
            .put("/instance")
        .then()
            .statusCode(409)
            .body("__type", equalTo("ResourceConflictException"));
    }

    @Test
    @Order(6)
    void createWithoutIdentityManagementTypeReturnsInvalidRequest() {
        given()
            .contentType("application/json")
            .body("{\"InboundCallsEnabled\": true, \"OutboundCallsEnabled\": true}")
        .when()
            .put("/instance")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(7)
    void instanceAttributesAreSeededAndUpdatable() {
        given()
        .when()
            .get("/instance/" + instanceId + "/attribute/INBOUND_CALLS")
        .then()
            .statusCode(200)
            .body("Attribute.AttributeType", equalTo("INBOUND_CALLS"))
            .body("Attribute.Value", equalTo("true"));

        given()
        .when()
            .get("/instance/" + instanceId + "/attribute/CONTACTFLOW_LOGS")
        .then()
            .statusCode(200)
            .body("Attribute.Value", equalTo("false"));

        given()
            .contentType("application/json")
            .body("{\"Value\": \"true\"}")
        .when()
            .post("/instance/" + instanceId + "/attribute/CONTACTFLOW_LOGS")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/instance/" + instanceId + "/attribute/CONTACTFLOW_LOGS")
        .then()
            .statusCode(200)
            .body("Attribute.Value", equalTo("true"));
    }

    @Test
    @Order(7)
    void attributeValueIsValidatedAndCanonicalized() {
        given()
            .contentType("application/json")
            .body("{\"Value\": \"yes\"}")
        .when()
            .post("/instance/" + instanceId + "/attribute/AUTO_RESOLVE_BEST_VOICES")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));

        given()
            .contentType("application/json")
            .body("{\"Value\": \"TRUE\"}")
        .when()
            .post("/instance/" + instanceId + "/attribute/AUTO_RESOLVE_BEST_VOICES")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/instance/" + instanceId + "/attribute/AUTO_RESOLVE_BEST_VOICES")
        .then()
            .statusCode(200)
            .body("Attribute.Value", equalTo("true"));
    }

    @Test
    @Order(8)
    void listInstanceAttributes() {
        given()
        .when()
            .get("/instance/" + instanceId + "/attributes")
        .then()
            .statusCode(200)
            .body("Attributes.AttributeType", hasItem("CONTACT_LENS"))
            .body("Attributes.find { it.AttributeType == 'CONTACTFLOW_LOGS' }.Value", equalTo("true"))
            .body("Attributes.find { it.AttributeType == 'OUTBOUND_CALLS' }.Value", equalTo("false"));
    }

    @Test
    @Order(9)
    void unknownAttributeTypeReturnsInvalidParameter() {
        given()
        .when()
            .get("/instance/" + instanceId + "/attribute/NOT_A_REAL_ATTRIBUTE")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    @Order(10)
    void associateAndDescribeInstanceStorageConfig() {
        associationId = given()
            .contentType("application/json")
            .body("""
                {
                  "ResourceType": "CHAT_TRANSCRIPTS",
                  "StorageConfig": {
                    "StorageType": "S3",
                    "S3Config": {
                      "BucketName": "floci-transcripts",
                      "BucketPrefix": "chat/",
                      "EncryptionConfig": {
                        "EncryptionType": "KMS",
                        "KeyId": "arn:aws:kms:us-east-1:000000000000:key/abc"
                      }
                    }
                  }
                }
                """)
        .when()
            .put("/instance/" + instanceId + "/storage-config")
        .then()
            .statusCode(200)
            .body("AssociationId", notNullValue())
            .extract().path("AssociationId");

        given()
            .queryParam("resourceType", "CHAT_TRANSCRIPTS")
        .when()
            .get("/instance/" + instanceId + "/storage-config/" + associationId)
        .then()
            .statusCode(200)
            .body("StorageConfig.AssociationId", equalTo(associationId))
            .body("StorageConfig.StorageType", equalTo("S3"))
            .body("StorageConfig.S3Config.BucketName", equalTo("floci-transcripts"))
            .body("StorageConfig.S3Config.BucketPrefix", equalTo("chat/"))
            .body("StorageConfig.S3Config.EncryptionConfig.EncryptionType", equalTo("KMS"));
    }

    @Test
    @Order(11)
    void listInstanceStorageConfigs() {
        given()
            .queryParam("resourceType", "CHAT_TRANSCRIPTS")
        .when()
            .get("/instance/" + instanceId + "/storage-configs")
        .then()
            .statusCode(200)
            .body("StorageConfigs.AssociationId", hasItem(associationId))
            .body("StorageConfigs[0].S3Config.BucketName", equalTo("floci-transcripts"));

        given()
            .queryParam("resourceType", "CALL_RECORDINGS")
        .when()
            .get("/instance/" + instanceId + "/storage-configs")
        .then()
            .statusCode(200)
            .body("StorageConfigs.size()", equalTo(0));
    }

    @Test
    @Order(12)
    void updateInstanceStorageConfig() {
        given()
            .contentType("application/json")
            .queryParam("resourceType", "CHAT_TRANSCRIPTS")
            .body("""
                {
                  "StorageConfig": {
                    "StorageType": "S3",
                    "S3Config": {"BucketName": "floci-transcripts-v2", "BucketPrefix": "chat/"}
                  }
                }
                """)
        .when()
            .post("/instance/" + instanceId + "/storage-config/" + associationId)
        .then()
            .statusCode(200);

        given()
            .queryParam("resourceType", "CHAT_TRANSCRIPTS")
        .when()
            .get("/instance/" + instanceId + "/storage-config/" + associationId)
        .then()
            .statusCode(200)
            .body("StorageConfig.S3Config.BucketName", equalTo("floci-transcripts-v2"))
            .body("StorageConfig.S3Config.EncryptionConfig", nullValue());
    }

    @Test
    @Order(13)
    void storageConfigWithoutMatchingMemberIsRejected() {
        given()
            .contentType("application/json")
            .body("""
                {"ResourceType": "CALL_RECORDINGS", "StorageConfig": {"StorageType": "S3"}}
                """)
        .when()
            .put("/instance/" + instanceId + "/storage-config")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(14)
    void tagRoundTripOnTheSharedTagsPath() {
        given()
        .when()
            .get("/tags/" + instanceArn)
        .then()
            .statusCode(200)
            .body("tags.team", equalTo("cx"));

        given()
            .contentType("application/json")
            .body("{\"tags\": {\"env\": \"test\"}}")
        .when()
            .post("/tags/" + instanceArn)
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/tags/" + instanceArn)
        .then()
            .statusCode(200)
            .body("tags.env", equalTo("test"))
            .body("tags.team", equalTo("cx"));

        given()
            .queryParam("tagKeys", "env")
        .when()
            .delete("/tags/" + instanceArn)
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/tags/" + instanceArn)
        .then()
            .statusCode(200)
            .body("tags.env", nullValue())
            .body("tags.team", equalTo("cx"));
    }

    @Test
    @Order(15)
    void disassociateInstanceStorageConfig() {
        given()
            .queryParam("resourceType", "CHAT_TRANSCRIPTS")
        .when()
            .delete("/instance/" + instanceId + "/storage-config/" + associationId)
        .then()
            .statusCode(200);

        given()
            .queryParam("resourceType", "CHAT_TRANSCRIPTS")
        .when()
            .get("/instance/" + instanceId + "/storage-config/" + associationId)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(16)
    void deleteInstance() {
        given()
        .when()
            .delete("/instance/" + instanceId)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/instance/" + instanceId)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(17)
    void deleteMissingInstanceReturnsResourceNotFound() {
        given()
        .when()
            .delete("/instance/" + instanceId)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }
}
