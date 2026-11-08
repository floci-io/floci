package io.floci.aws.services.cloudformation;

import io.floci.aws.core.common.XmlParser;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.EnforcementFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Launch-configuration MetadataOptions outside the documented values (HttpTokens optional or
 * required, HttpEndpoint enabled or disabled, HttpPutResponseHopLimit an integer from 1 to 64), or
 * of the wrong JSON type, fail the resource and roll the stack back without leaving a launch
 * configuration behind. A wrong type fails CloudFormation's template validation, a hop limit beyond
 * 32 bits the handler's model validation, and a wrong value Auto Scaling's own validation, where a
 * number or a boolean given for a string member arrives as its text.
 */
@QuarkusTest
class CloudFormationLaunchConfigurationMetadataValidationIntegrationTest {
    private static final String ACCOUNT = "222222222222";
    private static final String AUTH = EnforcementFixtures.auth(ACCOUNT, "cloudformation");
    private static final String BEYOND_32_BITS = "Model validation failed (#/MetadataOptions/HttpPutResponseHopLimit:"
            + " expected type: Integer, found: String. This may occur when a numeric value exceeds 32-bit integer max"
            + " (2147483647). Consider using 'number' type or ensure value fits in integer range.)";

    static Stream<Arguments> invalidMetadataOptions() {
        return Stream.of(
                Arguments.of("{\"HttpTokens\":\"invalid\"}", "1 validation error detected: Value 'invalid' at"
                        + " 'metadataOptions.httpTokens' failed to satisfy constraint: Member must satisfy enum value"
                        + " set: [optional, required]"),
                Arguments.of("{\"HttpEndpoint\":\"invalid\"}", "1 validation error detected: Value 'invalid' at"
                        + " 'metadataOptions.httpEndpoint' failed to satisfy constraint: Member must satisfy enum value"
                        + " set: [disabled, enabled]"),
                Arguments.of("{\"HttpPutResponseHopLimit\":0}", "1 validation error detected: Value '0' at"
                        + " 'metadataOptions.httpPutResponseHopLimit' failed to satisfy constraint: Member must have"
                        + " value greater than or equal to 1"),
                Arguments.of("{\"HttpPutResponseHopLimit\":65}", "1 validation error detected: Value '65' at"
                        + " 'metadataOptions.httpPutResponseHopLimit' failed to satisfy constraint: Member must have"
                        + " value less than or equal to 64"),
                Arguments.of("{\"HttpTokens\":true}", "1 validation error detected: Value 'true' at"
                        + " 'metadataOptions.httpTokens' failed to satisfy constraint: Member must satisfy enum value"
                        + " set: [optional, required]"),
                Arguments.of("{\"HttpEndpoint\":1}", "1 validation error detected: Value '1' at"
                        + " 'metadataOptions.httpEndpoint' failed to satisfy constraint: Member must satisfy enum value"
                        + " set: [disabled, enabled]"),
                Arguments.of("\"required\"", "Property [MetadataOptions] expected type: JSONObject, found: String"),
                Arguments.of("{\"HttpTokens\":[\"required\"]}",
                        "Property [HttpTokens] expected type: String, found: JSONArray"),
                Arguments.of("{\"HttpEndpoint\":{\"Value\":\"disabled\"}}",
                        "Property [HttpEndpoint] expected type: String, found: JSONObject"),
                Arguments.of("{\"HttpPutResponseHopLimit\":[2]}",
                        "Property [HttpPutResponseHopLimit] expected type: Integer, found: JSONArray"),
                Arguments.of("{\"HttpPutResponseHopLimit\":\"not-an-integer\"}",
                        "Property [HttpPutResponseHopLimit] expected type: Integer, found: String"),
                Arguments.of("{\"HttpPutResponseHopLimit\":4.5}",
                        "Property [HttpPutResponseHopLimit] expected type: Integer, found: Float"),
                Arguments.of("{\"HttpPutResponseHopLimit\":\"4.5\"}",
                        "Property [HttpPutResponseHopLimit] expected type: Integer, found: Float"),
                Arguments.of("{\"HttpPutResponseHopLimit\":true}",
                        "Property [HttpPutResponseHopLimit] expected type: Integer, found: Boolean"),
                Arguments.of("{\"HttpPutResponseHopLimit\":2147483648}", BEYOND_32_BITS),
                Arguments.of("{\"HttpPutResponseHopLimit\":-2147483649}", BEYOND_32_BITS),
                // Model validation fails before Auto Scaling is called, so its own check never runs.
                Arguments.of("{\"HttpTokens\":\"invalid\",\"HttpPutResponseHopLimit\":2147483648}", BEYOND_32_BITS));
    }

    @ParameterizedTest
    @MethodSource("invalidMetadataOptions")
    void invalidMetadataOptionsFailTheResourceWithoutCreatingIt(String metadataOptions, String message) {
        String name = "cfn-invalid-metadata-" + UUID.randomUUID();
        String template = """
                {"Resources":{"Launch":{"Type":"AWS::AutoScaling::LaunchConfiguration",
                "Properties":{"LaunchConfigurationName":"%s","ImageId":"ami-amazonlinux2023",
                "InstanceType":"t3.micro","MetadataOptions":%s}}}}
                """.formatted(name, metadataOptions);
        try {
            request("cloudformation", "CreateStack").formParam("StackName", name).formParam("TemplateBody", template)
                    .post("/").then().statusCode(200);

            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(name, AUTH);
            assertEquals("ROLLBACK_COMPLETE", state.status(), state.reason());
            String events = request("cloudformation", "DescribeStackEvents").formParam("StackName", name)
                    .post("/").then().statusCode(200).extract().asString();
            List<String> failures = XmlParser.extractGroups(events, "member").stream()
                    .filter(event -> "Launch".equals(event.get("LogicalResourceId"))
                            && "CREATE_FAILED".equals(event.get("ResourceStatus")))
                    .map(event -> event.get("ResourceStatusReason"))
                    .toList();
            assertThat(failures, contains(equalTo(message)));
            request("autoscaling", "DescribeLaunchConfigurations")
                    .formParam("LaunchConfigurationNames.member.1", name).post("/").then().statusCode(200)
                    .body("DescribeLaunchConfigurationsResponse.DescribeLaunchConfigurationsResult"
                            + ".LaunchConfigurations.member.size()", equalTo(0));
        } finally {
            deleteStack(name);
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"1|1", "64|64", "\"2\"|2", "{\"Ref\":\"Hops\"}|5"})
    void hopLimitsAtTheBoundsAsStringsOrFromParametersAreAccepted(String hopLimit, String stored) {
        String name = "cfn-valid-metadata-" + UUID.randomUUID();
        String template = """
                {"Parameters":{"Hops":{"Type":"Number","Default":"5"}},
                "Resources":{"Launch":{"Type":"AWS::AutoScaling::LaunchConfiguration",
                "Properties":{"LaunchConfigurationName":"%s","ImageId":"ami-amazonlinux2023",
                "InstanceType":"t3.micro","MetadataOptions":{"HttpPutResponseHopLimit":%s}}}}}
                """.formatted(name, hopLimit);
        try {
            request("cloudformation", "CreateStack").formParam("StackName", name).formParam("TemplateBody", template)
                    .post("/").then().statusCode(200);

            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(name, AUTH);
            assertEquals("CREATE_COMPLETE", state.status(), state.reason());
            request("autoscaling", "DescribeLaunchConfigurations")
                    .formParam("LaunchConfigurationNames.member.1", name).post("/").then().statusCode(200)
                    .body("DescribeLaunchConfigurationsResponse.DescribeLaunchConfigurationsResult"
                            + ".LaunchConfigurations.member.MetadataOptions.HttpPutResponseHopLimit", equalTo(stored));
        } finally {
            deleteStack(name);
        }
    }

    // Ignores the status, so a test that failed before the stack existed keeps its own failure.
    private static void deleteStack(String name) {
        request("cloudformation", "DeleteStack").formParam("StackName", name).post("/");
    }

    private static RequestSpecification request(String service, String action) {
        return given().header("Authorization", EnforcementFixtures.auth(ACCOUNT, service)).formParam("Action", action);
    }
}
