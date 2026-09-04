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
    void listDocuments() {
        given()
            .header("X-Amz-Target", "AmazonSSM.ListDocuments")
            .contentType(SSM_CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DocumentIdentifiers.find { it.Name == 'floci-test-doc' }.Name", equalTo("floci-test-doc"))
            .body("DocumentIdentifiers.find { it.Name == 'floci-test-doc' }.DocumentType", equalTo("Command"))
            .body("DocumentIdentifiers.find { it.Name == 'floci-test-doc' }.DocumentVersion", equalTo("1"))
            .body("DocumentIdentifiers.find { it.Name == 'floci-test-doc' }.Owner", notNullValue())
            .body("DocumentIdentifiers.find { it.Name == 'floci-test-doc' }.PlatformTypes", hasItems("Windows", "Linux", "MacOS"));

        given()
            .header("X-Amz-Target", "AmazonSSM.ListDocuments")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Filters": [
                        {
                            "Key": "Name",
                            "Values": ["floci-test-doc"]
                        }
                    ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DocumentIdentifiers.Name", hasItem("floci-test-doc"))
            .body("DocumentIdentifiers.find { it.Name == 'floci-test-doc' }.DocumentType", equalTo("Command"))
            .body("DocumentIdentifiers.find { it.Name == 'floci-test-doc' }.DocumentVersion", equalTo("1"))
            .body("DocumentIdentifiers.find { it.Name == 'floci-test-doc' }.PlatformTypes", hasItems("Windows", "Linux", "MacOS"));

        given()
            .header("X-Amz-Target", "AmazonSSM.ListDocuments")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Filters": [
                        {
                            "Key": "Name",
                            "Values": ["non-matching-doc"]
                        }
                    ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DocumentIdentifiers", empty());
    }

    @Test
    @Order(3)
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

        given()
            .header("X-Amz-Target", "AmazonSSM.ListDocuments")
            .contentType(SSM_CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DocumentIdentifiers.findAll { it.Name == 'floci-test-doc' }", empty());
    }

    @Test
    @Order(4)
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
    @Order(5)
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

    @Test
    @Order(6)
    void updateAndResolveDocumentVersions() {
        String docName = "doc-version-test";
        given()
            .header("X-Amz-Target", "AmazonSSM.CreateDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body(String.format("""
                {
                    "Name": "%s",
                    "DocumentType": "Command",
                    "Content": "{\\"schemaVersion\\":\\"1.0\\"}"
                }
                """, docName))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DocumentDescription.DocumentVersion", equalTo("1"));

        // Update document to version 2
        given()
            .header("X-Amz-Target", "AmazonSSM.UpdateDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body(String.format("""
                {
                    "Name": "%s",
                    "Content": "{\\"schemaVersion\\":\\"2.0\\"}"
                }
                """, docName))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DocumentDescription.DocumentVersion", equalTo("2"));

        // Get historical version 1
        given()
            .header("X-Amz-Target", "AmazonSSM.GetDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body(String.format("""
                {
                    "Name": "%s",
                    "DocumentVersion": "1"
                }
                """, docName))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DocumentVersion", equalTo("1"))
            .body("Content", equalTo("{\"schemaVersion\":\"1.0\"}"));

        // Get latest version 2
        given()
            .header("X-Amz-Target", "AmazonSSM.GetDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body(String.format("""
                {
                    "Name": "%s",
                    "DocumentVersion": "2"
                }
                """, docName))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DocumentVersion", equalTo("2"))
            .body("Content", equalTo("{\"schemaVersion\":\"2.0\"}"));

        // Describe historical version 1
        given()
            .header("X-Amz-Target", "AmazonSSM.DescribeDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body(String.format("""
                {
                    "Name": "%s",
                    "DocumentVersion": "1"
                }
                """, docName))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Document.DocumentVersion", equalTo("1"))
            .body("Document.Content", equalTo("{\"schemaVersion\":\"1.0\"}"));

        // Non-existent version throws InvalidDocumentVersion
        given()
            .header("X-Amz-Target", "AmazonSSM.GetDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body(String.format("""
                {
                    "Name": "%s",
                    "DocumentVersion": "99"
                }
                """, docName))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidDocumentVersion"));

        // Cleanup
        given()
            .header("X-Amz-Target", "AmazonSSM.DeleteDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body(String.format("""
                {
                    "Name": "%s"
                }
                """, docName))
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
