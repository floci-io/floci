package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SsmDocumentLifecycleIntegrationTest {

    private static final String SSM_CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createAndDescribeDocument() {
        given()
            .header("X-Amz-Target", "AmazonSSM.CreateDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "floci-test-doc",
                    "DocumentType": "Command",
                    "Content": "{\\"schemaVersion\\":\\"2.2\\",\\"mainSteps\\":[]}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DocumentDescription.Name", equalTo("floci-test-doc"))
            .body("DocumentDescription.DocumentType", equalTo("Command"));

        given()
            .header("X-Amz-Target", "AmazonSSM.DescribeDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "floci-test-doc"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Document.Name", equalTo("floci-test-doc"))
            .body("Document.DocumentType", equalTo("Command"));
    }

    @Test
    @Order(2)
    void deleteDocument() {
        given()
            .header("X-Amz-Target", "AmazonSSM.DeleteDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "floci-test-doc"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(3)
    void describeMissingDocument() {
        given()
            .header("X-Amz-Target", "AmazonSSM.DescribeDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "floci-nonexistent-doc"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidDocument"));
    }

    @Test
    @Order(4)
    void createDocumentRejectsNonStringName() {
        given()
            .header("X-Amz-Target", "AmazonSSM.CreateDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": 123,
                    "DocumentType": "Command",
                    "Content": "{\\"schemaVersion\\":\\"2.2\\",\\"mainSteps\\":[]}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }
}
