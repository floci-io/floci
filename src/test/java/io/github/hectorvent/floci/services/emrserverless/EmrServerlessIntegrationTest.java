package io.github.hectorvent.floci.services.emrserverless;

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
public class EmrServerlessIntegrationTest {

    private static final String JSON_CONTENT_TYPE = "application/json";
    private static String applicationId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private io.restassured.specification.RequestSpecification givenReq() {
        return given()
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=TEST/20260819/us-east-1/emr-serverless/aws4_request, SignedHeaders=host;x-amz-date, Signature=test")
            .contentType(JSON_CONTENT_TYPE);
    }

    @Test
    @Order(1)
    void createApplicationValidation() {
        // Missing releaseLabel
        givenReq()
            .body("""
                {
                    "name": "my-test-app",
                    "type": "SPARK",
                    "clientToken": "test-token"
                }
                """)
        .when()
            .post("/applications")
        .then()
            .statusCode(400);

        // Missing type
        givenReq()
            .body("""
                {
                    "name": "my-test-app",
                    "releaseLabel": "emr-6.6.0",
                    "clientToken": "test-token"
                }
                """)
        .when()
            .post("/applications")
        .then()
            .statusCode(400);

        // Missing clientToken
        givenReq()
            .body("""
                {
                    "name": "my-test-app",
                    "releaseLabel": "emr-6.6.0",
                    "type": "SPARK"
                }
                """)
        .when()
            .post("/applications")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(2)
    void createApplication() {
        applicationId = givenReq()
            .body("""
                {
                    "name": "my-test-app",
                    "releaseLabel": "emr-6.6.0",
                    "type": "SPARK",
                    "clientToken": "test-token"
                }
                """)
        .when()
            .post("/applications")
        .then()
            .statusCode(200)
            .body("applicationId", notNullValue())
            .body("arn", startsWith("arn:aws:emr-serverless:us-east-1:000000000000:/applications/"))
            .body("name", equalTo("my-test-app"))
            .extract().path("applicationId");
            
        // Test Idempotency
        String retryApplicationId = givenReq()
            .body("""
                {
                    "name": "my-test-app",
                    "releaseLabel": "emr-6.6.0",
                    "type": "SPARK",
                    "clientToken": "test-token"
                }
                """)
        .when()
            .post("/applications")
        .then()
            .statusCode(200)
            .extract().path("applicationId");
            
        org.junit.jupiter.api.Assertions.assertEquals(applicationId, retryApplicationId, "Idempotent create should return the same application ID");
    }

    @Test
    @Order(3)
    void getApplication() {
        givenReq()
        .when()
            .get("/applications/" + applicationId)
        .then()
            .statusCode(200)
            .body("application.applicationId", equalTo(applicationId))
            .body("application.name", equalTo("my-test-app"))
            .body("application.state", equalTo("CREATED"));
    }

    @Test
    @Order(4)
    void listApplications() {
        givenReq()
        .when()
            .get("/applications")
        .then()
            .statusCode(200)
            .body("applications.size()", greaterThanOrEqualTo(1))
            .body("applications.find { it.id == '" + applicationId + "' }.name", equalTo("my-test-app"));
            
        // Create a second app to test pagination
        givenReq()
            .body("""
                {
                    "name": "my-second-app",
                    "releaseLabel": "emr-6.6.0",
                    "type": "SPARK",
                    "clientToken": "test-token-2"
                }
                """)
        .when()
            .post("/applications")
        .then()
            .statusCode(200);

        // Test pagination and state filtering
        givenReq()
            .queryParam("states", "CREATED")
            .queryParam("maxResults", 1)
        .when()
            .get("/applications")
        .then()
            .statusCode(200)
            .body("applications.size()", equalTo(1))
            .body("applications[0].state", equalTo("CREATED"))
            .body("nextToken", notNullValue());
    }

    @Test
    @Order(5)
    void startApplication() {
        givenReq()
        .when()
            .post("/applications/" + applicationId + "/start")
        .then()
            .statusCode(200);

        givenReq()
        .when()
            .get("/applications/" + applicationId)
        .then()
            .statusCode(200)
            .body("application.state", equalTo("STARTED"));
    }

    @Test
    @Order(6)
    void stopApplication() {
        givenReq()
        .when()
            .post("/applications/" + applicationId + "/stop")
        .then()
            .statusCode(200);

        givenReq()
        .when()
            .get("/applications/" + applicationId)
        .then()
            .statusCode(200)
            .body("application.state", equalTo("STOPPED"));
    }

    @Test
    @Order(7)
    void updateApplication() {
        givenReq()
            .body("""
                {
                    "releaseLabel": "emr-6.7.0",
                    "initialCapacity": {
                        "DRIVER": {
                            "workerCount": 2,
                            "workerConfiguration": {
                                "cpu": "4 vCPU",
                                "memory": "16 GB"
                            }
                        }
                    },
                    "monitoringConfiguration": {
                        "s3MonitoringConfiguration": {
                            "logUri": "s3://my-bucket/logs"
                        }
                    },
                    "interactiveConfiguration": {
                        "studioEnabled": true
                    }
                }
                """)
        .when()
            .patch("/applications/" + applicationId)
        .then()
            .statusCode(200)
            .body("applicationId", equalTo(applicationId));
            
        // Verify releaseLabel and new configurations updated
        givenReq()
        .when()
            .get("/applications/" + applicationId)
        .then()
            .statusCode(200)
            .body("application.releaseLabel", equalTo("emr-6.7.0"))
            .body("application.monitoringConfiguration", notNullValue())
            .body("application.interactiveConfiguration", notNullValue());
    }

    @Test
    @Order(8)
    void deleteApplication() {
        givenReq()
        .when()
            .delete("/applications/" + applicationId)
        .then()
            .statusCode(200);

        // Verify it's gone
        givenReq()
        .when()
            .get("/applications/" + applicationId)
        .then()
            .statusCode(404);
    }
}
