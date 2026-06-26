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
class SsmIntegrationTest {

    private static final String SSM_CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void putParameter() {
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "/app/db/host",
                    "Value": "localhost",
                    "Type": "String"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Version", equalTo(1));
    }

    @Test
    @Order(2)
    void getParameter() {
        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "/app/db/host",
                    "WithDecryption": true
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameter.Name", equalTo("/app/db/host"))
            .body("Parameter.Value", equalTo("localhost"))
            .body("Parameter.Type", equalTo("String"))
            .body("Parameter.Version", equalTo(1));
    }

    @Test
    @Order(3)
    void putParameterOverwrite() {
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "/app/db/host",
                    "Value": "db.example.com",
                    "Type": "String",
                    "Overwrite": true
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Version", equalTo(2));
    }

    @Test
    @Order(4)
    void putParameterWithoutOverwriteFails() {
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "/app/db/host",
                    "Value": "other",
                    "Type": "String"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ParameterAlreadyExists"));
    }

    @Test
    @Order(5)
    void getParameterNotFound() {
        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "/nonexistent" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ParameterNotFound"));
    }

    @Test
    @Order(6)
    void getParametersByPath() {
        // Add more parameters
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "/app/db/port", "Value": "5432", "Type": "String" }
                """)
        .when()
            .post("/");

        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "/app/cache/host", "Value": "redis", "Type": "String" }
                """)
        .when()
            .post("/");

        // Query by path
        given()
            .header("X-Amz-Target", "AmazonSSM.GetParametersByPath")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Path": "/app/db", "Recursive": true }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameters.size()", equalTo(2));
    }

    @Test
    @Order(7)
    void getParameters() {
        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameters")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Names": ["/app/db/host", "/app/db/port", "/missing"] }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameters.size()", equalTo(2));
    }

    @Test
    @Order(8)
    void getParameterHistory() {
        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameterHistory")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "/app/db/host" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameters.size()", greaterThanOrEqualTo(2));
    }

    @Test
    @Order(9)
    void deleteParameter() {
        given()
            .header("X-Amz-Target", "AmazonSSM.DeleteParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "/app/cache/host" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify it's gone
        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "/app/cache/host" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ParameterNotFound"));
    }

    @Test
    @Order(10)
    void deleteParameters() {
        given()
            .header("X-Amz-Target", "AmazonSSM.DeleteParameters")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Names": ["/app/db/host", "/app/db/port", "/missing"] }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeletedParameters.size()", equalTo(2));
    }

    // ── Issue #956: DescribePatchBaselines / GetDefaultPatchBaseline (AWS-owned predefined) ──

    @Test
    void describePatchBaselines_filteredByOwnerAndOperatingSystem() {
        given()
            .header("X-Amz-Target", "AmazonSSM.DescribePatchBaselines")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Filters": [
                        {"Key": "OWNER", "Values": ["AWS"]},
                        {"Key": "OPERATING_SYSTEM", "Values": ["AMAZON_LINUX_2"]}
                    ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("BaselineIdentities.size()", equalTo(1))
            .body("BaselineIdentities[0].BaselineName", equalTo("AWS-AmazonLinux2DefaultPatchBaseline"))
            .body("BaselineIdentities[0].OperatingSystem", equalTo("AMAZON_LINUX_2"))
            .body("BaselineIdentities[0].DefaultBaseline", equalTo(true))
            .body("BaselineIdentities[0].BaselineId", startsWith("pb-"));
    }

    @Test
    void describePatchBaselines_byNamePrefixReturnsPredefined() {
        given()
            .header("X-Amz-Target", "AmazonSSM.DescribePatchBaselines")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Filters": [
                        {"Key": "NAME_PREFIX", "Values": ["AWS-"]}
                    ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("BaselineIdentities.size()", greaterThan(1))
            .body("BaselineIdentities.BaselineName", everyItem(startsWith("AWS-")));
    }

    @Test
    void describePatchBaselines_ownerSelfReturnsEmpty() {
        given()
            .header("X-Amz-Target", "AmazonSSM.DescribePatchBaselines")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Filters": [
                        {"Key": "OWNER", "Values": ["Self"]}
                    ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("BaselineIdentities.size()", equalTo(0));
    }

    @Test
    void getDefaultPatchBaseline_windows() {
        given()
            .header("X-Amz-Target", "AmazonSSM.GetDefaultPatchBaseline")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "OperatingSystem": "WINDOWS"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("OperatingSystem", equalTo("WINDOWS"))
            .body("BaselineId", startsWith("pb-"));
    }

    @Test
    void unsupportedOperation() {
        given()
            .header("X-Amz-Target", "AmazonSSM.UnsupportedAction")
            .contentType(SSM_CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnsupportedOperation"));
    }
}
