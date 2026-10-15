package io.github.hectorvent.floci.services.configservice;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ConfigAccountIsolationIntegrationTest {
    private static final String FIRST = "833333333333";
    private static final String SECOND = "844444444444";

    @BeforeAll
    static void setUpJson() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static ValidatableResponse call(String account, String action, String body) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account
                        + "/20261011/us-east-1/config/aws4_request")
                .header("X-Amz-Target", "StarlingDoveService." + action)
                .contentType("application/x-amz-json-1.1").body(body).post("/").then().statusCode(200);
    }

    @Test
    void sameNamedRecorderAndRuleRuntimeStateStaysInEachAccount() {
        for (String account : List.of(FIRST, SECOND)) {
            call(account, "PutConfigurationRecorder", """
                    {"ConfigurationRecorder":{"name":"shared","roleARN":"arn:aws:iam::%s:role/config"}}
                    """.formatted(account));
            call(account, "PutConfigRule", """
                    {"ConfigRule":{"ConfigRuleName":"shared","Source":{"Owner":"CUSTOM_LAMBDA",
                    "SourceIdentifier":"arn:aws:lambda:us-east-1:%s:function:check"}}}
                    """.formatted(account));
        }
        call(FIRST, "StartConfigurationRecorder", "{\"ConfigurationRecorderName\":\"shared\"}");
        call(FIRST, "StartConfigRulesEvaluation", "{\"ConfigRuleNames\":[\"shared\"]}");
        call(SECOND, "DescribeConfigurationRecorderStatus", "{}")
                .body("ConfigurationRecordersStatus[0].recording", equalTo(false))
                .body("ConfigurationRecordersStatus[0].lastStartTime", nullValue());
        call(SECOND, "DescribeConfigRuleEvaluationStatus", "{}")
                .body("ConfigRulesEvaluationStatus[0].FirstEvaluationStarted", equalTo(false));
        call(SECOND, "DeleteConfigRule", "{\"ConfigRuleName\":\"shared\"}");
        call(SECOND, "DeleteConfigurationRecorder", "{\"ConfigurationRecorderName\":\"shared\"}");
        call(FIRST, "DescribeConfigurationRecorderStatus", "{}")
                .body("ConfigurationRecordersStatus[0].recording", equalTo(true));
        call(FIRST, "DescribeConfigRuleEvaluationStatus", "{}")
                .body("ConfigRulesEvaluationStatus[0].FirstEvaluationStarted", equalTo(true))
                .body("ConfigRulesEvaluationStatus[0].FirstActivatedTime", notNullValue());
        call(FIRST, "DeleteConfigRule", "{\"ConfigRuleName\":\"shared\"}");
        call(FIRST, "DeleteConfigurationRecorder", "{\"ConfigurationRecorderName\":\"shared\"}");
    }
}
