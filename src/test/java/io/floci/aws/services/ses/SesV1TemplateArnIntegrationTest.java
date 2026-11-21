package io.floci.aws.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * How the v1 templated sends answer their Template and TemplateArn members. The checks and
 * messages match the v2 sends' and are probe-confirmed on both operations; the sender is never
 * verified, so a request whose template members pass reaches the sender check.
 */
@QuarkusTest
class SesV1TemplateArnIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request";
    private static final String UNVERIFIED = "sender@unverified-v1-template-arn.floci.test";
    private static final String NAME = "v1-arn-ghost";
    private static final String TEMPLATE_REQUIRED =
            "1 validation error detected: Value at 'template' failed to satisfy constraint: Member must not be null";

    private static String arn(String partition, String service, String region, String account, String resource) {
        return "arn:" + partition + ":" + service + ":" + region + ":" + account + ":" + resource;
    }

    private static String arn(String partition, String service, String region, String resource) {
        return arn(partition, service, region, "000000000000", resource);
    }

    private static ValidatableResponse send(String operation, String template, String templateArn) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", operation)
            .formParam("Source", UNVERIFIED);
        if ("SendTemplatedEmail".equals(operation)) {
            request.formParam("Destination.ToAddresses.member.1", "success@simulator.amazonses.com")
                .formParam("TemplateData", "{}");
        } else {
            request.formParam("Destinations.member.1.Destination.ToAddresses.member.1",
                    "success@simulator.amazonses.com")
                .formParam("DefaultTemplateData", "{}");
        }
        if (template != null) {
            request.formParam("Template", template);
        }
        if (templateArn != null) {
            request.formParam("TemplateArn", templateArn);
        }
        return request.when().post("/").then();
    }

    private static void assertError(ValidatableResponse response, String code, String message) {
        response
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo(code))
            .body("ErrorResponse.Error.Message", equalTo(message));
    }

    static Stream<Arguments> invalidTemplateArns() {
        String otherRegion = arn("aws", "ses", "eu-west-1", "template/" + NAME);
        String otherPartition = arn("aws-cn", "ses", "us-east-1", "template/" + NAME);
        String otherService = arn("aws", "s3", "us-east-1", "template/" + NAME);
        String otherResource = arn("aws", "ses", "us-east-1", "configuration-set/" + NAME);
        String otherTemplate = arn("aws", "ses", "us-east-1", "template/v1-arn-other");
        return Stream.of("SendTemplatedEmail", "SendBulkTemplatedEmail").flatMap(operation -> Stream.of(
                Arguments.of(operation, otherRegion,
                        "Invalid template ARN <" + otherRegion + ">. Template must be in region <us-east-1>"),
                Arguments.of(operation, otherPartition,
                        "Invalid template ARN <" + otherPartition + ">. Partition should be <aws>"),
                Arguments.of(operation, otherService,
                        "Invalid template ARN <" + otherService + ">. Vendor should be <ses>"),
                Arguments.of(operation, otherResource,
                        "Invalid template ARN <" + otherResource + ">. Relative ID should start with <template/>"),
                Arguments.of(operation, "not-an-arn", "ARNs must start with 'arn:': not-an-arn"),
                Arguments.of(operation, arn("aws", "ses", "us-east-1", "template/"),
                        "The template name must be specified."),
                Arguments.of(operation, otherTemplate,
                        "The SES resource '" + NAME + "' does not align with the given ARN '" + otherTemplate + "'.")));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("invalidTemplateArns")
    void invalidTemplateArn_isRejectedBeforeTheSenderCheck(String operation, String templateArn, String message) {
        assertError(send(operation, NAME, templateArn), "InvalidParameterValue", message);
    }

    static Stream<Arguments> acceptedTemplateArns() {
        return Stream.of("SendTemplatedEmail", "SendBulkTemplatedEmail").flatMap(operation -> Stream.of(
                Arguments.of(operation, arn("aws", "ses", "us-east-1", "template/" + NAME)),
                Arguments.of(operation, arn("aws", "ses", "us-east-1", "999999999999", "template/" + NAME))));
    }

    // SES does not check the account of a template ARN.
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("acceptedTemplateArns")
    void templateArnNamingTheTemplate_reachesTheSenderCheck(String operation, String templateArn) {
        send(operation, NAME, templateArn)
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("MessageRejected"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SendTemplatedEmail", "SendBulkTemplatedEmail"})
    void templateArnWithoutTemplate_isModelViolation(String operation) {
        assertError(send(operation, null, arn("aws", "ses", "eu-west-1", "template/" + NAME)),
                "ValidationError", TEMPLATE_REQUIRED);
    }

    // An empty Template is reported ahead of the TemplateArn checks (probed on SendTemplatedEmail).
    @ParameterizedTest
    @ValueSource(strings = {"SendTemplatedEmail", "SendBulkTemplatedEmail"})
    void emptyTemplate_isRejectedBeforeTheTemplateArn(String operation) {
        assertError(send(operation, "", "not-an-arn"), "InvalidParameterValue", "Template cannot be empty");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-an-arn"})
    void sendBulkTemplatedEmail_whitespaceTemplate_isEmpty(String templateArn) {
        assertError(send("SendBulkTemplatedEmail", "  ", templateArn.isEmpty() ? null : templateArn),
                "InvalidParameterValue", "Template cannot be empty");
    }

    // SendTemplatedEmail does not count a whitespace-only Template as empty.
    @Test
    void sendTemplatedEmail_whitespaceTemplate_mustAlignWithTheTemplateArn() {
        String templateArn = arn("aws", "ses", "us-east-1", "template/" + NAME);
        assertError(send("SendTemplatedEmail", "  ", templateArn), "InvalidParameterValue",
                "The SES resource '  ' does not align with the given ARN '" + templateArn + "'.");
    }
}
