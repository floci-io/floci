package io.github.hectorvent.floci.services.emrserverless;

import io.github.hectorvent.floci.testing.EmrServerlessRequests;
import io.github.hectorvent.floci.testing.MutableClock;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.config.JsonConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.path.json.config.JsonPathConfig;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class EmrServerlessIntegrationTest {

    private static final RestAssuredConfig DECIMAL_NUMBERS = RestAssuredConfig.config()
            .jsonConfig(JsonConfig.jsonConfig().numberReturnType(JsonPathConfig.NumberReturnType.BIG_DECIMAL));
    private static String applicationId;

    @Inject
    MutableClock clock;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private RequestSpecification givenReq() {
        return EmrServerlessRequests.emrServerless("us-east-1");
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
            
        Assertions.assertEquals(applicationId, retryApplicationId, "Idempotent create should return the same application ID");
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
            .body("application.state", equalTo("CREATED"))
            .body("application", not(hasKey("clientToken")));
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
                    },
                    "runtimeConfiguration": [
                        {"classification": "spark-defaults", "properties": {"spark.executor.memory": "4g"}}
                    ]
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
            .body("application.interactiveConfiguration", notNullValue())
            .body("application.runtimeConfiguration[0].classification", equalTo("spark-defaults"))
            .body("application.runtimeConfiguration[0].properties.'spark.executor.memory'", equalTo("4g"));
    }

    @Test
    @Order(8)
    void deleteApplication() {
        givenReq()
        .when()
            .delete("/applications/" + applicationId)
        .then()
            .statusCode(200);

        givenReq()
        .when()
            .get("/applications/" + applicationId)
        .then()
            .statusCode(200)
            .body("application.state", equalTo("TERMINATED"));

        givenReq()
        .when()
            .get("/applications")
        .then()
            .statusCode(200)
            .body("applications.find { it.id == '" + applicationId + "' }", nullValue());

        givenReq()
        .when()
            .post("/applications/" + applicationId + "/start")
        .then()
            .statusCode(400);

        givenReq()
        .when()
            .post("/applications/" + applicationId + "/stop")
        .then()
            .statusCode(400);

        givenReq()
        .when()
            .get("/applications/" + applicationId)
        .then()
            .statusCode(200)
            .body("application.state", equalTo("TERMINATED"));
    }

    @Test
    @Order(9)
    void applicationTimestampsAreEpochSecondsWithMilliseconds() {
        // A Region of its own, so ListApplications holds this application and no other test's.
        String region = "ap-southeast-4";
        clock.advance(Duration.ofMillis(250));
        BigDecimal before = BigDecimal.valueOf(clock.instant().toEpochMilli(), 3);
        String id = EmrServerlessRequests.emrServerless(region)
            .body("""
                {"name": "stamped-app", "releaseLabel": "emr-6.6.0", "type": "SPARK", "clientToken": "%s"}
                """.formatted(UUID.randomUUID()))
        .when()
            .post("/applications")
        .then()
            .statusCode(200)
            .extract().path("applicationId");

        BigDecimal createdAt = EmrServerlessRequests.emrServerless(region).config(DECIMAL_NUMBERS)
        .when()
            .get("/applications/" + id)
        .then()
            .statusCode(200)
            .extract().path("application.createdAt");
        BigDecimal updatedAt = EmrServerlessRequests.emrServerless(region).config(DECIMAL_NUMBERS)
            .get("/applications/" + id)
            .then().statusCode(200).extract().path("application.updatedAt");
        BigDecimal listed = EmrServerlessRequests.emrServerless(region).config(DECIMAL_NUMBERS)
            .get("/applications")
            .then().statusCode(200).extract().path("applications[0].createdAt");

        // The test clock moves a millisecond on every read, so the application was created a little later.
        assertTrue(createdAt.compareTo(before) > 0 && createdAt.compareTo(before.add(BigDecimal.ONE)) < 0,
                "createdAt " + createdAt + " is not just after " + before);
        assertTrue(createdAt.stripTrailingZeros().scale() <= 3, "createdAt " + createdAt + " is finer than milliseconds");
        assertEquals(0, createdAt.compareTo(updatedAt), "updatedAt " + updatedAt + " differs from createdAt " + createdAt);
        assertEquals(0, createdAt.compareTo(listed), "ListApplications reports createdAt " + listed);
    }

    @Test
    @Order(10)
    void listApplicationsRefusesStatesThatDoNotExistAndPagesOutsideOneToFifty() {
        givenReq()
            .queryParam("states", "RUNNING")
            .queryParam("maxResults", 51)
        .when()
            .get("/applications")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("2 validation errors detected: "
                    + "Value '51' at 'maxResults' failed to satisfy constraint: "
                    + "Member must have value less than or equal to 50; "
                    + "Value '[RUNNING]' at 'states' failed to satisfy constraint: Member must satisfy constraint: "
                    + "[Member must satisfy enum value set: "
                    + "[CREATING, CREATED, STARTING, STARTED, STOPPING, STOPPED, TERMINATED]]"));

        givenReq()
            .queryParam("states", List.of("CREATING", "CREATED", "STARTING", "STARTED", "STOPPING", "STOPPED",
                    "TERMINATED", "CREATED"))
        .when()
            .get("/applications")
        .then()
            .statusCode(400)
            .body("message", equalTo("1 validation error detected: "
                    + "Value '[CREATING, CREATED, STARTING, STARTED, STOPPING, STOPPED, TERMINATED, CREATED]' "
                    + "at 'states' failed to satisfy constraint: Member must have length less than or equal to 7"));
    }

    @Test
    @Order(11)
    void createApplicationKeepsEveryConfigurationItTakes() {
        String id = givenReq()
            .body("""
                {
                    "name": "configured-app",
                    "releaseLabel": "emr-7.5.0",
                    "type": "SPARK",
                    "clientToken": "%s",
                    "imageConfiguration": {
                        "imageUri": "public.ecr.aws/emr-serverless/spark/emr-7.5.0:latest",
                        "applicationLevelDigestResolution": true
                    },
                    "runtimeConfiguration": [
                        {"classification": "spark-defaults", "properties": {"spark.driver.cores": "2"}}
                    ],
                    "monitoringConfiguration": {"s3MonitoringConfiguration": {"logUri": "s3://bucket/logs/"}},
                    "schedulerConfiguration": {"queueTimeoutMinutes": 30, "maxConcurrentRuns": 5},
                    "diskEncryptionConfiguration": {
                        "encryptionKeyArn": "arn:aws:kms:us-east-1:000000000000:key/1234abcd-12ab-34cd-56ef-1234567890ab"
                    },
                    "interactiveConfiguration": {"studioEnabled": true, "livyEndpointEnabled": false},
                    "identityCenterConfiguration": {
                        "identityCenterInstanceArn": "arn:aws:sso:::instance/ssoins-1234567890abcdef"
                    },
                    "jobLevelCostAllocationConfiguration": {"enabled": true}
                }
                """.formatted(UUID.randomUUID()))
        .when()
            .post("/applications")
        .then()
            .statusCode(200)
            .extract().path("applicationId");

        givenReq()
        .when()
            .get("/applications/" + id)
        .then()
            .statusCode(200)
            .body("application.imageConfiguration.applicationLevelDigestResolution", equalTo(true))
            .body("application.runtimeConfiguration[0].classification", equalTo("spark-defaults"))
            .body("application.runtimeConfiguration[0].properties.'spark.driver.cores'", equalTo("2"))
            .body("application.monitoringConfiguration.s3MonitoringConfiguration.logUri", equalTo("s3://bucket/logs/"))
            .body("application.schedulerConfiguration.maxConcurrentRuns", equalTo(5))
            .body("application.diskEncryptionConfiguration.encryptionKeyArn",
                    equalTo("arn:aws:kms:us-east-1:000000000000:key/1234abcd-12ab-34cd-56ef-1234567890ab"))
            .body("application.interactiveConfiguration.studioEnabled", equalTo(true))
            .body("application.identityCenterConfiguration.identityCenterInstanceArn",
                    equalTo("arn:aws:sso:::instance/ssoins-1234567890abcdef"))
            .body("application.jobLevelCostAllocationConfiguration.enabled", equalTo(true));
    }

    @Test
    @Order(12)
    void createApplicationRefusesACustomImageInAnotherRegion() {
        givenReq()
            .body("""
                {
                    "releaseLabel": "emr-7.5.0",
                    "type": "SPARK",
                    "clientToken": "%s",
                    "workerTypeSpecifications": {
                        "Driver": {
                            "imageConfiguration": {
                                "imageUri": "000000000000.dkr.ecr.eu-west-1.amazonaws.com/spark/custom:7.5.0"
                            }
                        }
                    }
                }
                """.formatted(UUID.randomUUID()))
        .when()
            .post("/applications")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("Image 000000000000.dkr.ecr.eu-west-1.amazonaws.com/spark/custom:7.5.0 is in "
                    + "an Amazon ECR repository in eu-west-1; it must be in the application's Region, us-east-1."));
    }
}
