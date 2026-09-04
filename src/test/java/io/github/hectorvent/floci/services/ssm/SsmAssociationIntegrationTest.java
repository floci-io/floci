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
class SsmAssociationIntegrationTest {

    private static final String SSM_CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createAssociation_DocumentNotFound() {
        given()
            .header("X-Amz-Target", "AmazonSSM.CreateAssociation")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "no-such-doc",
                    "InstanceId": "i-1234567890abcdef0"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidDocument"));
    }

    @Test
    @Order(2)
    void createAssociation_MissingName() {
        given()
            .header("X-Amz-Target", "AmazonSSM.CreateAssociation")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "InstanceId": "i-1234567890abcdef0"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(3)
    void associationLifecycle() {
        // First create a document: assoc-test-doc (DocumentType: "Command", valid content)
        given()
            .header("X-Amz-Target", "AmazonSSM.CreateDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "assoc-test-doc",
                    "DocumentType": "Command",
                    "Content": "{\\"schemaVersion\\":\\"2.2\\",\\"mainSteps\\":[]}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DocumentDescription.Name", equalTo("assoc-test-doc"));

        // Create association
        String associationId = given()
            .header("X-Amz-Target", "AmazonSSM.CreateAssociation")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "assoc-test-doc",
                    "AssociationName": "my-assoc",
                    "InstanceId": "i-1234567890abcdef0",
                    "Parameters": {
                        "commands": ["echo hello"]
                    },
                    "ScheduleExpression": "rate(30 minutes)"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AssociationDescription.Name", equalTo("assoc-test-doc"))
            .body("AssociationDescription.AssociationName", equalTo("my-assoc"))
            .body("AssociationDescription.Status.Name", equalTo("Success"))
            .extract().path("AssociationDescription.AssociationId");

        // List associations
        given()
            .header("X-Amz-Target", "AmazonSSM.ListAssociations")
            .contentType(SSM_CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Associations.AssociationId", hasItem(associationId))
            .body("Associations.Name", hasItem("assoc-test-doc"));

        // Describe association by AssociationId
        given()
            .header("X-Amz-Target", "AmazonSSM.DescribeAssociation")
            .contentType(SSM_CONTENT_TYPE)
            .body(String.format("""
                {
                    "AssociationId": "%s"
                }
                """, associationId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AssociationDescription.AssociationId", equalTo(associationId));

        // Update association
        given()
            .header("X-Amz-Target", "AmazonSSM.UpdateAssociation")
            .contentType(SSM_CONTENT_TYPE)
            .body(String.format("""
                {
                    "AssociationId": "%s",
                    "ScheduleExpression": "rate(1 hour)"
                }
                """, associationId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AssociationDescription.AssociationId", equalTo(associationId))
            .body("AssociationDescription.ScheduleExpression", equalTo("rate(1 hour)"))
            .body("AssociationDescription.AssociationVersion", equalTo("2"));

        // Describe association by Name and InstanceId
        given()
            .header("X-Amz-Target", "AmazonSSM.DescribeAssociation")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "assoc-test-doc",
                    "InstanceId": "i-1234567890abcdef0"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AssociationDescription.AssociationId", equalTo(associationId));

        // Delete association
        given()
            .header("X-Amz-Target", "AmazonSSM.DeleteAssociation")
            .contentType(SSM_CONTENT_TYPE)
            .body(String.format("""
                {
                    "AssociationId": "%s"
                }
                """, associationId))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Subsequent describe association returns 400
        given()
            .header("X-Amz-Target", "AmazonSSM.DescribeAssociation")
            .contentType(SSM_CONTENT_TYPE)
            .body(String.format("""
                {
                    "AssociationId": "%s"
                }
                """, associationId))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("AssociationDoesNotExist"));

        // Delete already deleted association returns 400
        given()
            .header("X-Amz-Target", "AmazonSSM.DeleteAssociation")
            .contentType(SSM_CONTENT_TYPE)
            .body(String.format("""
                {
                    "AssociationId": "%s"
                }
                """, associationId))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("AssociationDoesNotExist"));
    }

    @Test
    @Order(4)
    void createAssociation_Duplicate() {
        given()
            .header("X-Amz-Target", "AmazonSSM.CreateDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "dup-assoc-doc",
                    "DocumentType": "Command",
                    "Content": "{\\"schemaVersion\\":\\"2.2\\",\\"mainSteps\\":[]}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "AmazonSSM.CreateAssociation")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "dup-assoc-doc",
                    "InstanceId": "i-dupdupdupdupdup1"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "AmazonSSM.CreateAssociation")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "dup-assoc-doc",
                    "InstanceId": "i-dupdupdupdupdup1"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("AssociationAlreadyExists"));
    }

    @Test
    @Order(5)
    void createAssociation_DocumentVersionTargeting() {
        String docName = "assoc-ver-doc";
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
            .statusCode(200);

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
            .statusCode(200);

        // Target historical version 1: content was retained so association succeeds
        given()
            .header("X-Amz-Target", "AmazonSSM.CreateAssociation")
            .contentType(SSM_CONTENT_TYPE)
            .body(String.format("""
                {
                    "Name": "%s",
                    "AssociationName": "historical-assoc",
                    "DocumentVersion": "1",
                    "InstanceId": "i-hist1111111111111"
                }
                """, docName))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AssociationDescription.DocumentVersion", equalTo("1"));

        // Target version 2: succeeds
        given()
            .header("X-Amz-Target", "AmazonSSM.CreateAssociation")
            .contentType(SSM_CONTENT_TYPE)
            .body(String.format("""
                {
                    "Name": "%s",
                    "AssociationName": "v2-assoc",
                    "DocumentVersion": "2",
                    "InstanceId": "i-hist2222222222222"
                }
                """, docName))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AssociationDescription.DocumentVersion", equalTo("2"));

        // Non-existent version 99: throws InvalidDocumentVersion
        given()
            .header("X-Amz-Target", "AmazonSSM.CreateAssociation")
            .contentType(SSM_CONTENT_TYPE)
            .body(String.format("""
                {
                    "Name": "%s",
                    "AssociationName": "invalid-assoc",
                    "DocumentVersion": "99",
                    "InstanceId": "i-hist9999999999999"
                }
                """, docName))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidDocumentVersion"));
    }
}
