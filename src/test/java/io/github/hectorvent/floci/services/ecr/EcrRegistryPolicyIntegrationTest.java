package io.github.hectorvent.floci.services.ecr;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;

/**
 * The registry permission policy is registry-scoped, not repository-scoped: one document
 * per account per region. {@code aws_ecr_registry_policy} drives these three operations.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EcrRegistryPolicyIntegrationTest {

    private static final String CT = "application/x-amz-json-1.1";
    private static final String PREFIX = "AmazonEC2ContainerRegistry_V20150921.";
    private static final String POLICY = """
            {"Version":"2012-10-17","Statement":[{"Sid":"replication","Effect":"Allow",\
            "Principal":{"AWS":"arn:aws:iam::210987654321:root"},\
            "Action":["ecr:CreateRepository","ecr:ReplicateImage"],"Resource":"*"}]}""";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void getRegistryPolicyBeforeOneIsSetIsNotFound() {
        given()
            .header("X-Amz-Target", PREFIX + "GetRegistryPolicy")
            .contentType(CT)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("RegistryPolicyNotFoundException"));
    }

    @Test
    @Order(2)
    void putRegistryPolicyEchoesTheDocumentAndRegistryId() {
        given()
            .header("X-Amz-Target", PREFIX + "PutRegistryPolicy")
            .contentType(CT)
            .body(objectWithPolicy())
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("registryId", matchesPattern("\\d{12}"))
            .body("policyText", containsString("ecr:ReplicateImage"));
    }

    @Test
    @Order(3)
    void getRegistryPolicyReturnsTheStoredDocument() {
        given()
            .header("X-Amz-Target", PREFIX + "GetRegistryPolicy")
            .contentType(CT)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("registryId", matchesPattern("\\d{12}"))
            .body("policyText", equalTo(POLICY));
    }

    @Test
    @Order(4)
    void putRegistryPolicyReplacesTheDocument() {
        String replacement = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        given()
            .header("X-Amz-Target", PREFIX + "PutRegistryPolicy")
            .contentType(CT)
            .body("{\"policyText\": " + quote(replacement) + "}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("policyText", equalTo(replacement));

        given()
            .header("X-Amz-Target", PREFIX + "GetRegistryPolicy")
            .contentType(CT)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("policyText", equalTo(replacement));
    }

    @Test
    @Order(5)
    void putRegistryPolicyWithoutPolicyTextIsRejected() {
        given()
            .header("X-Amz-Target", PREFIX + "PutRegistryPolicy")
            .contentType(CT)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    @Order(6)
    void deleteRegistryPolicyRemovesItAndTheNextGetIsNotFound() {
        given()
            .header("X-Amz-Target", PREFIX + "DeleteRegistryPolicy")
            .contentType(CT)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("registryId", matchesPattern("\\d{12}"))
            .body("policyText", containsString("Statement"));

        given()
            .header("X-Amz-Target", PREFIX + "GetRegistryPolicy")
            .contentType(CT)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("RegistryPolicyNotFoundException"));
    }

    @Test
    @Order(7)
    void deleteRegistryPolicyWhenNoneIsSetIsNotFound() {
        given()
            .header("X-Amz-Target", PREFIX + "DeleteRegistryPolicy")
            .contentType(CT)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("RegistryPolicyNotFoundException"));
    }

    private static String objectWithPolicy() {
        return "{\"policyText\": " + quote(POLICY) + "}";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
