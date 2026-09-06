package io.github.hectorvent.floci.services.controltower;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class ControlTowerControlControllerIntegrationTest {
    private static final String TARGET = "arn:aws:organizations::000000000000:ou/o-example/ou-example-12345678";

    @BeforeAll
    static void configureRestAssured() { RestAssuredJsonUtils.configureAwsContentTypes(); }

    @Test
    void enableListGetUpdateResetAndPoll() {
        String control = "arn:aws:controlcatalog:::control/7mo7a2h2ebsq71l8k6uzr96ou";
        String enabledArn = post("/enable-control", "{\"controlIdentifier\":\"" + control
                + "\",\"targetIdentifier\":\"" + TARGET + "\",\"tags\":{\"managed_by\":\"cloud-launchpad\"}}")
                .then().statusCode(200).body("operationIdentifier", notNullValue()).extract().path("arn");

        post("/list-enabled-controls", "{\"targetIdentifier\":\"" + TARGET + "\"}")
                .then().statusCode(200).body("enabledControls", hasSize(1))
                .body("enabledControls[0].controlIdentifier", equalTo(control));
        post("/get-enabled-control", "{\"enabledControlIdentifier\":\"" + enabledArn + "\"}")
                .then().statusCode(200).body("enabledControlDetails.statusSummary.status", equalTo("SUCCEEDED"));

        String update = post("/update-enabled-control", "{\"enabledControlIdentifier\":\"" + enabledArn
                + "\",\"parameters\":[{\"key\":\"ExemptedPrincipalArns\",\"value\":[\"arn:aws:iam::000000000000:role/Admin\"]}]}")
                .then().statusCode(200).extract().path("operationIdentifier");
        post("/get-control-operation", "{\"operationIdentifier\":\"" + update + "\"}")
                .then().statusCode(200).body("controlOperation.operationType", equalTo("UPDATE_ENABLED_CONTROL"))
                .body("controlOperation.status", equalTo("SUCCEEDED"));

        String reset = post("/reset-enabled-control", "{\"enabledControlIdentifier\":\"" + enabledArn + "\"}")
                .then().statusCode(200).extract().path("operationIdentifier");
        post("/get-control-operation", "{\"operationIdentifier\":\"" + reset + "\"}")
                .then().statusCode(200).body("controlOperation.operationType", equalTo("RESET_ENABLED_CONTROL"));
    }

    @Test
    void duplicateEnableAndMissingResourcesReturnAwsErrors() {
        String control = "arn:aws:controltower:us-east-1::control/AWS-GR_RDS_STORAGE_ENCRYPTED";
        String body = "{\"controlIdentifier\":\"" + control + "\",\"targetIdentifier\":\"" + TARGET + "\"}";
        post("/enable-control", body).then().statusCode(200);
        post("/enable-control", body).then().statusCode(409).body("__type", containsString("ConflictException"));
        post("/get-enabled-control", "{\"enabledControlIdentifier\":\"arn:aws:controltower:us-east-1:000000000000:enabledcontrol/missing\"}")
                .then().statusCode(404).body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void updateRequiresDifferentParametersAndResetRejectsScp() {
        String control = "arn:aws:controltower:us-east-1::control/AWS-GR_RESTRICT_ROOT_USER";
        String enabledArn = post("/enable-control", "{\"controlIdentifier\":\"" + control
                + "\",\"targetIdentifier\":\"" + TARGET + "\",\"parameters\":[{\"key\":\"ExemptAssumeRoot\",\"value\":false}]}")
                .then().statusCode(200).extract().path("arn");
        post("/update-enabled-control", "{\"enabledControlIdentifier\":\"" + enabledArn
                + "\",\"parameters\":[{\"key\":\"ExemptAssumeRoot\",\"value\":false}]}")
                .then().statusCode(400).body("__type", containsString("ValidationException"));
        post("/reset-enabled-control", "{\"enabledControlIdentifier\":\"" + enabledArn + "\"}")
                .then().statusCode(400).body("__type", containsString("ValidationException"));
    }

    @Test
    void listEnabledControlsRejectsNegativeNextToken() {
        post("/list-enabled-controls", "{\"nextToken\":\"-1\"}")
                .then().statusCode(400).body("__type", containsString("ValidationException"));
    }

    @Test
    void validationRejectsMalformedIdentifiersAndOperationIds() {
        post("/enable-control", "{\"controlIdentifier\":\"bad\",\"targetIdentifier\":\"" + TARGET + "\"}")
                .then().statusCode(400).body("__type", containsString("ValidationException"));
        post("/get-control-operation", "{\"operationIdentifier\":\"bad\"}")
                .then().statusCode(400).body("__type", containsString("ValidationException"));
    }

    private static io.restassured.response.Response post(String path, String body) {
        return given().contentType("application/json").header("Authorization", auth()).body(body).post(path);
    }

    private static String auth() {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260904/us-east-1/controltower/aws4_request";
    }
}
