package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class CloudWatchLogsCrossAccountIntegrationTest {
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/logs/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void destinationAndAccountPolicyLifecycle() {
        json("Logs_20140328.PutDestination",
                "{\"destinationName\":\"cross-account\",\"targetArn\":\"arn:aws:kinesis:us-east-1:000000000000:stream/logs\",\"roleArn\":\"arn:aws:iam::000000000000:role/logs\"}")
                .then().statusCode(200).body("destination.destinationName", equalTo("cross-account"));
        json("Logs_20140328.PutDestinationPolicy",
                "{\"destinationName\":\"cross-account\",\"accessPolicy\":\"{}\"}")
                .then().statusCode(200);
        json("Logs_20140328.PutAccountPolicy",
                "{\"policyName\":\"central-subscription\",\"policyDocument\":\"{}\",\"policyType\":\"SUBSCRIPTION_FILTER_POLICY\",\"scope\":\"ALL\"}")
                .then().statusCode(200).body("accountPolicy.policyName", equalTo("central-subscription"));
        json("Logs_20140328.DescribeAccountPolicies",
                "{\"policyType\":\"SUBSCRIPTION_FILTER_POLICY\"}")
                .then().statusCode(200).body("accountPolicies", hasSize(1));
    }

    @Test
    void transformerPolicyAcceptsProcessorArrayDocument() {
        json("Logs_20140328.PutAccountPolicy",
                "{\"policyName\":\"account-transformer\",\"policyDocument\":\"[{\\\"parseJSON\\\":{}}]\","
                        + "\"policyType\":\"TRANSFORMER_POLICY\",\"scope\":\"ALL\"}")
                .then().statusCode(200)
                .body("accountPolicy.policyType", equalTo("TRANSFORMER_POLICY"))
                .body("accountPolicy.policyDocument", equalTo("[{\"parseJSON\":{}}]"));
    }

    @Test
    void destinationPolicyRequiresExistingDestination() {
        json("Logs_20140328.PutDestinationPolicy",
                "{\"destinationName\":\"missing\",\"accessPolicy\":\"{}\"}")
                .then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
    }

    private static io.restassured.response.Response json(String target, String body) {
        return given().contentType(CONTENT_TYPE).header("Authorization", AUTH).header("X-Amz-Target", target)
                .body(body).post("/");
    }
}
