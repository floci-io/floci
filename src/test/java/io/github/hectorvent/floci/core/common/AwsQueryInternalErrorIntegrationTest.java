package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.services.iam.IamService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A handler bug on the Query protocol must render the XML InternalFailure contract, never
 * Quarkus's default JSON error page — SDK XML parsers fail on JSON before surfacing anything
 * (issue #1753 hit exactly this via an NPE in the IAM handler).
 */
@QuarkusTest
class AwsQueryInternalErrorIntegrationTest {

    @InjectMock
    IamService iamService;

    @Test
    void unhandledHandlerExceptionRendersXmlInternalFailure() {
        when(iamService.listUsers(any())).thenThrow(new RuntimeException("simulated handler bug"));

        given()
            .formParam("Action", "ListUsers")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(500)
            .contentType(containsString("xml"))
            .body("ErrorResponse.Error.Type", equalTo("Receiver"))
            .body("ErrorResponse.Error.Code", equalTo("InternalFailure"))
            .body("ErrorResponse.Error.Message", containsString("simulated handler bug"));
    }

    /**
     * The catch-all must not swallow AwsException. CloudWatch Metrics is the Query handler that
     * renders no error XML of its own, so its AwsException reaches the controller: it has to keep
     * its declared code and 4xx status and be encoded as XML, not collapse to a 500 InternalFailure
     * (nor reach AwsExceptionMapper, which would emit JSON onto the XML wire).
     */
    @Test
    void awsExceptionFromAHandlerKeepsItsCodeAndStatusAsXml() {
        given()
            .formParam("Action", "SetAlarmState")
            .formParam("AlarmName", "no-such-alarm")
            .formParam("StateValue", "ALARM")
            .formParam("StateReason", "reason")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/monitoring/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .contentType(containsString("xml"))
            .body("ErrorResponse.Error.Type", equalTo("Sender"))
            .body("ErrorResponse.Error.Code", equalTo("ResourceNotFound"));
    }
}
