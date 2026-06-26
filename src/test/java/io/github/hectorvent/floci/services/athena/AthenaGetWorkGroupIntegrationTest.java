package io.github.hectorvent.floci.services.athena;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class AthenaGetWorkGroupIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getWorkGroupReturnsPrimaryDefaults() {
        given()
            .header("X-Amz-Target", "AmazonAthena.GetWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("{ \"WorkGroup\": \"primary\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("WorkGroup.Name", equalTo("primary"))
            .body("WorkGroup.State", equalTo("ENABLED"))
            .body("WorkGroup.Configuration.EngineVersion.EffectiveEngineVersion",
                    equalTo("Athena engine version 3"));
    }

    @Test
    void getWorkGroupReturnsCreatedWorkGroupDetails() {
        given()
            .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "Name": "analytics-get",
                  "Description": "analytics workgroup for reporting queries",
                  "Configuration": {
                    "ResultConfiguration": {
                      "OutputLocation": "s3://test-bucket/athena-results/"
                    }
                  }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "AmazonAthena.GetWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("{ \"WorkGroup\": \"analytics-get\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("WorkGroup.Name", equalTo("analytics-get"))
            .body("WorkGroup.State", equalTo("ENABLED"))
            .body("WorkGroup.Description", equalTo("analytics workgroup for reporting queries"))
            .body("WorkGroup.CreationTime", notNullValue())
            .body("WorkGroup.Configuration.ResultConfiguration.OutputLocation",
                    equalTo("s3://test-bucket/athena-results/"))
            .body("WorkGroup.Configuration.EngineVersion.EffectiveEngineVersion",
                    equalTo("Athena engine version 3"));
    }

    /**
     * Reproduces issue #1498: the AthenaClient fails to unmarshal the {@code CreationTime} field returned by Floci's
     * {@code GetWorkGroup} response.
     */
    @Test
    void getWorkGroupCreationTimeIsSerializedAsEpochSecondsNumber() {
        given()
                .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                          "Name": "timestamp-format-bug-wg"
                        }
                        """)
                .when()
                .post("/")
                .then()
                .statusCode(200);

        var response = given()
                .header("X-Amz-Target", "AmazonAthena.GetWorkGroup")
                .contentType(CONTENT_TYPE)
                .body("{ \"WorkGroup\": \"timestamp-format-bug-wg\" }")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("WorkGroup.Name", equalTo("timestamp-format-bug-wg"))
                .body("WorkGroup.CreationTime", notNullValue())
                .body("WorkGroup.CreationTime", instanceOf(Number.class));
    }

    @Test
    void getWorkGroupNotFoundReturnsInvalidRequestException() {
        given()
            .header("X-Amz-Target", "AmazonAthena.GetWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("{ \"WorkGroup\": \"athena-workgroup-missing\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"))
            .body("message", equalTo("WorkGroup athena-workgroup-missing is not found."));
    }

    @Test
    void deletedWorkGroupIsNoLongerReturnedAndNameCanBeReused() {
        String createRequest = """
            {
              "Name": "analytics-delete-get"
            }
            """;

        given()
            .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body(createRequest)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "AmazonAthena.DeleteWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("{ \"WorkGroup\": \"analytics-delete-get\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "AmazonAthena.GetWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("{ \"WorkGroup\": \"analytics-delete-get\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));

        given()
            .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body(createRequest)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
