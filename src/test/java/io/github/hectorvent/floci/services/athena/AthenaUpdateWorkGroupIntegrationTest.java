package io.github.hectorvent.floci.services.athena;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class AthenaUpdateWorkGroupIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void updateWorkGroupMergesDescriptionStateAndConfiguration() {
        given()
            .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "Name": "analytics-update",
                  "Description": "before",
                  "Configuration": {
                    "ResultConfiguration": {
                      "OutputLocation": "s3://before/results/"
                    },
                    "EnforceWorkGroupConfiguration": true,
                    "PublishCloudWatchMetricsEnabled": false,
                    "RequesterPaysEnabled": false,
                    "BytesScannedCutoffPerQuery": 10000000
                  }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String responseBody = given()
            .header("X-Amz-Target", "AmazonAthena.UpdateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "WorkGroup": "analytics-update",
                  "Description": "after",
                  "State": "DISABLED",
                  "ConfigurationUpdates": {
                    "ResultConfigurationUpdates": {
                      "OutputLocation": "s3://after/results/"
                    },
                    "EnforceWorkGroupConfiguration": false,
                    "PublishCloudWatchMetricsEnabled": true,
                    "RequesterPaysEnabled": true,
                    "BytesScannedCutoffPerQuery": 20000000,
                    "EngineVersion": {
                      "SelectedEngineVersion": "AUTO"
                    }
                  }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        assertEquals("{}", responseBody);

        given()
            .header("X-Amz-Target", "AmazonAthena.GetWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("{ \"WorkGroup\": \"analytics-update\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("WorkGroup.Description", equalTo("after"))
            .body("WorkGroup.State", equalTo("DISABLED"))
            .body("WorkGroup.Configuration.ResultConfiguration.OutputLocation",
                    equalTo("s3://after/results/"))
            .body("WorkGroup.Configuration.EnforceWorkGroupConfiguration", equalTo(false))
            .body("WorkGroup.Configuration.PublishCloudWatchMetricsEnabled", equalTo(true))
            .body("WorkGroup.Configuration.RequesterPaysEnabled", equalTo(true))
            .body("WorkGroup.Configuration.BytesScannedCutoffPerQuery", equalTo(20000000))
            .body("WorkGroup.Configuration.EngineVersion.SelectedEngineVersion", equalTo("AUTO"))
            .body("WorkGroup.Configuration.EngineVersion.EffectiveEngineVersion",
                    equalTo("Athena engine version 3"));
    }

    @Test
    void updateWorkGroupPreservesOmittedSettingsAndAppliesRemovalFlags() {
        given()
            .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "Name": "analytics-partial-update",
                  "Configuration": {
                    "ResultConfiguration": {
                      "OutputLocation": "s3://before/results/"
                    },
                    "EnforceWorkGroupConfiguration": true,
                    "BytesScannedCutoffPerQuery": 10000000
                  }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "AmazonAthena.UpdateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "WorkGroup": "analytics-partial-update",
                  "ConfigurationUpdates": {
                    "ResultConfigurationUpdates": {
                      "RemoveOutputLocation": true
                    },
                    "RemoveBytesScannedCutoffPerQuery": true
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
            .body("{ \"WorkGroup\": \"analytics-partial-update\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("WorkGroup.State", equalTo("ENABLED"))
            .body("WorkGroup.Configuration.EnforceWorkGroupConfiguration", equalTo(true))
            .body("WorkGroup.Configuration.ResultConfiguration", nullValue())
            .body("WorkGroup.Configuration.BytesScannedCutoffPerQuery", nullValue());
    }

    @Test
    void updateWorkGroupRejectsMissingWorkGroup() {
        given()
            .header("X-Amz-Target", "AmazonAthena.UpdateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("{ \"WorkGroup\": \"missing-update-workgroup\", \"Description\": \"after\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"))
            .body("message", equalTo("WorkGroup missing-update-workgroup is not found."));
    }

    @Test
    void updateWorkGroupRejectsInvalidState() {
        given()
            .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("{ \"Name\": \"analytics-invalid-state\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "AmazonAthena.UpdateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("{ \"WorkGroup\": \"analytics-invalid-state\", \"State\": \"UNKNOWN\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }
}
