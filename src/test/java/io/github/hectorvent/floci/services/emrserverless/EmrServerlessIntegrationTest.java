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
            .header("Host", "emr-serverless.us-east-1.amazonaws.com")
            .contentType(JSON_CONTENT_TYPE);
    }

    @Test
    @Order(1)
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
            .body("arn", startsWith("arn:aws:emr-serverless"))
            .body("name", equalTo("my-test-app"))
            .extract().path("applicationId");
    }

    @Test
    @Order(2)
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
    @Order(3)
    void listApplications() {
        givenReq()
        .when()
            .get("/applications")
        .then()
            .statusCode(200)
            .body("applications.size()", greaterThanOrEqualTo(1))
            .body("applications.find { it.id == '" + applicationId + "' }.name", equalTo("my-test-app"));
    }

    @Test
    @Order(4)
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
    @Order(5)
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
    @Order(6)
    void updateApplication() {
        givenReq()
            .body("""
                {
                    "initialCapacity": {
                        "DRIVER": {
                            "workerCount": 2,
                            "workerConfiguration": {
                                "cpu": "4 vCPU",
                                "memory": "16 GB"
                            }
                        }
                    }
                }
                """)
        .when()
            .patch("/applications/" + applicationId)
        .then()
            .statusCode(200)
            .body("applicationId", equalTo(applicationId));
    }

    @Test
    @Order(7)
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
            .statusCode(400);
    }
}
