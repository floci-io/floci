package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class AutoScalingLaunchConfigurationMetadataIntegrationTest {
    private static final String ACCOUNT = "222222222222";
    private static final String GROUP = "DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult"
            + ".AutoScalingGroups.member.";
    private static final String LAUNCH_CONFIGURATION = "DescribeLaunchConfigurationsResponse"
            + ".DescribeLaunchConfigurationsResult.LaunchConfigurations.member";
    private static final String INSTANCE = "DescribeInstancesResponse.reservationSet.item.instancesSet.item.";

    @Inject
    AutoScalingReconciler reconciler;

    @Test
    void launchPreservesRequiredMetadata() {
        String name = "launch-metadata-" + UUID.randomUUID();
        request("autoscaling", "CreateLaunchConfiguration")
                .formParam("LaunchConfigurationName", name).formParam("ImageId", "ami-amazonlinux2023")
                .formParam("InstanceType", "t3.micro")
                .formParam("MetadataOptions.HttpTokens", "required")
                .formParam("MetadataOptions.HttpEndpoint", "enabled")
                .formParam("MetadataOptions.HttpPutResponseHopLimit", "2")
                .post("/").then().statusCode(200);
        try {
            createGroup(name);
            reconciler.reconcileAll();
            String instance = request("autoscaling", "DescribeAutoScalingGroups")
                    .formParam("AutoScalingGroupNames.member.1", name).post("/").then().statusCode(200)
                    .body(GROUP + "Instances.member.size()", equalTo(1))
                    .extract().path(GROUP + "Instances.member.InstanceId");
            request("ec2", "DescribeInstances").formParam("InstanceId.1", instance).post("/").then().statusCode(200)
                    .body(INSTANCE + "metadataOptions.httpTokens", equalTo("required"))
                    .body(INSTANCE + "metadataOptions.httpEndpoint", equalTo("enabled"))
                    .body(INSTANCE + "metadataOptions.httpPutResponseHopLimit", equalTo("2"));
        } finally {
            deleteGroupAndLaunchConfiguration(name);
        }
    }

    @Test
    void refreshReplacementPreservesRequiredMetadata() {
        String name = "refresh-metadata-" + UUID.randomUUID();
        request("autoscaling", "CreateLaunchConfiguration")
                .formParam("LaunchConfigurationName", name).formParam("ImageId", "ami-amazonlinux2023")
                .formParam("InstanceType", "t3.micro")
                .formParam("MetadataOptions.HttpTokens", "required")
                .formParam("MetadataOptions.HttpPutResponseHopLimit", "2")
                .post("/").then().statusCode(200);
        try {
            createGroup(name);
            reconciler.reconcileAll();
            reconciler.reconcileAll();
            String original = request("autoscaling", "DescribeAutoScalingGroups")
                    .formParam("AutoScalingGroupNames.member.1", name).post("/").then().statusCode(200)
                    .extract().path(GROUP + "Instances.member.InstanceId");
            String refreshId = request("autoscaling", "StartInstanceRefresh")
                    .formParam("AutoScalingGroupName", name).post("/").then().statusCode(200).extract().path(
                            "StartInstanceRefreshResponse.StartInstanceRefreshResult.InstanceRefreshId");
            reconciler.reconcileAll();
            reconciler.reconcileAll();
            // The refresh completes once its replacement is in service.
            request("autoscaling", "DescribeInstanceRefreshes")
                    .formParam("AutoScalingGroupName", name).formParam("InstanceRefreshIds.member.1", refreshId)
                    .post("/").then().statusCode(200)
                    .body("DescribeInstanceRefreshesResponse.DescribeInstanceRefreshesResult"
                            + ".InstanceRefreshes.member.Status", equalTo("Successful"));
            String replacement = request("autoscaling", "DescribeAutoScalingGroups")
                    .formParam("AutoScalingGroupNames.member.1", name).post("/").then().statusCode(200)
                    .extract().path(GROUP + "Instances.member.InstanceId");
            assertThat(replacement, not(equalTo(original)));
            request("ec2", "DescribeInstances").formParam("InstanceId.1", replacement).post("/").then()
                    .statusCode(200)
                    .body(INSTANCE + "metadataOptions.httpTokens", equalTo("required"))
                    .body(INSTANCE + "metadataOptions.httpPutResponseHopLimit", equalTo("2"));
        } finally {
            deleteGroupAndLaunchConfiguration(name);
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "MetadataOptions.HttpTokens=invalid|ValidationError|1 validation error detected: Value 'invalid' at"
                    + " 'metadataOptions.httpTokens' failed to satisfy constraint: Member must satisfy enum value"
                    + " set: [optional, required]",
            "MetadataOptions.HttpEndpoint=invalid|ValidationError|1 validation error detected: Value 'invalid' at"
                    + " 'metadataOptions.httpEndpoint' failed to satisfy constraint: Member must satisfy enum value"
                    + " set: [disabled, enabled]",
            "MetadataOptions.HttpPutResponseHopLimit=0|ValidationError|1 validation error detected: Value '0' at"
                    + " 'metadataOptions.httpPutResponseHopLimit' failed to satisfy constraint: Member must have"
                    + " value greater than or equal to 1",
            "MetadataOptions.HttpPutResponseHopLimit=65|ValidationError|1 validation error detected: Value '65' at"
                    + " 'metadataOptions.httpPutResponseHopLimit' failed to satisfy constraint: Member must have"
                    + " value less than or equal to 64",
            "MetadataOptions.HttpPutResponseHopLimit=not-an-integer|MalformedInput"
                    + "|HttpPutResponseHopLimit must be an integer.",
            // The awsQuery protocol binds the value to a 32-bit integer before model validation runs.
            "MetadataOptions.HttpPutResponseHopLimit=2147483648|MalformedInput"
                    + "|HttpPutResponseHopLimit must be an integer."})
    void invalidMetadataRefusesWithoutCreatingLaunchConfiguration(String parameter, String code, String message) {
        String name = "invalid-metadata-" + UUID.randomUUID();
        String[] pair = parameter.split("=", 2);
        request("autoscaling", "CreateLaunchConfiguration")
                .formParam("LaunchConfigurationName", name).formParam("ImageId", "ami-amazonlinux2023")
                .formParam("InstanceType", "t3.micro").formParam(pair[0], pair[1])
                .post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo(code))
                .body("ErrorResponse.Error.Message", equalTo(message));
        request("autoscaling", "DescribeLaunchConfigurations")
                .formParam("LaunchConfigurationNames.member.1", name).post("/").then().statusCode(200)
                .body(LAUNCH_CONFIGURATION + ".size()", equalTo(0));
    }

    @Test
    void everyInvalidMetadataMemberIsReportedInOneValidationError() {
        String name = "invalid-metadata-" + UUID.randomUUID();
        request("autoscaling", "CreateLaunchConfiguration")
                .formParam("LaunchConfigurationName", name).formParam("ImageId", "ami-amazonlinux2023")
                .formParam("InstanceType", "t3.micro")
                .formParam("MetadataOptions.HttpTokens", "invalid")
                .formParam("MetadataOptions.HttpPutResponseHopLimit", "65")
                .formParam("MetadataOptions.HttpEndpoint", "invalid")
                .post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message", equalTo("3 validation errors detected: Value 'invalid' at"
                        + " 'metadataOptions.httpTokens' failed to satisfy constraint: Member must satisfy enum value"
                        + " set: [optional, required]; Value '65' at 'metadataOptions.httpPutResponseHopLimit' failed"
                        + " to satisfy constraint: Member must have value less than or equal to 64; Value 'invalid' at"
                        + " 'metadataOptions.httpEndpoint' failed to satisfy constraint: Member must satisfy enum value"
                        + " set: [disabled, enabled]"));
        request("autoscaling", "DescribeLaunchConfigurations")
                .formParam("LaunchConfigurationNames.member.1", name).post("/").then().statusCode(200)
                .body(LAUNCH_CONFIGURATION + ".size()", equalTo(0));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void describeOmitsMetadataNeverGivenAndReturnsCloudFormationValues(boolean cloudFormation) {
        String name = "metadata-roundtrip-" + UUID.randomUUID();
        try {
            if (cloudFormation) {
                String template = """
                        {"Resources":{"Launch":{"Type":"AWS::AutoScaling::LaunchConfiguration",
                        "Properties":{"LaunchConfigurationName":"%s","ImageId":"ami-amazonlinux2023",
                        "InstanceType":"t3.micro","MetadataOptions":{"HttpTokens":"required",
                        "HttpEndpoint":"disabled","HttpPutResponseHopLimit":3}}}}}
                        """.formatted(name);
                request("cloudformation", "CreateStack").formParam("StackName", name)
                        .formParam("TemplateBody", template).post("/").then().statusCode(200);
                CfnStackWaits.awaitStatus(name, "CREATE_COMPLETE", EnforcementFixtures.auth(ACCOUNT, "cloudformation"));
            } else {
                request("autoscaling", "CreateLaunchConfiguration")
                        .formParam("LaunchConfigurationName", name).formParam("ImageId", "ami-amazonlinux2023")
                        .formParam("InstanceType", "t3.micro").post("/").then().statusCode(200);
            }
            ValidatableResponse described = describeLaunchConfiguration(name);
            if (cloudFormation) {
                described.body(LAUNCH_CONFIGURATION + ".MetadataOptions.HttpTokens", equalTo("required"))
                        .body(LAUNCH_CONFIGURATION + ".MetadataOptions.HttpEndpoint", equalTo("disabled"))
                        .body(LAUNCH_CONFIGURATION + ".MetadataOptions.HttpPutResponseHopLimit", equalTo("3"));
            } else {
                described.body(LAUNCH_CONFIGURATION + ".MetadataOptions.size()", equalTo(0));
            }
        } finally {
            if (cloudFormation) {
                deleteStack(name);
            } else {
                deleteLaunchConfiguration(name);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cloudFormationResolvesConditionalMetadataOptions(boolean strict) {
        String name = "conditional-metadata-" + UUID.randomUUID();
        String template = """
                {"Parameters":{"Strict":{"Type":"String"}},
                "Conditions":{"IsStrict":{"Fn::Equals":[{"Ref":"Strict"},"yes"]}},
                "Resources":{"Launch":{"Type":"AWS::AutoScaling::LaunchConfiguration",
                "Properties":{"LaunchConfigurationName":"%s","ImageId":"ami-amazonlinux2023",
                "InstanceType":"t3.micro","MetadataOptions":{"Fn::If":["IsStrict",
                {"HttpTokens":"required","HttpPutResponseHopLimit":{"Ref":"AWS::NoValue"}},
                {"Ref":"AWS::NoValue"}]}}}}}
                """.formatted(name);
        try {
            request("cloudformation", "CreateStack").formParam("StackName", name)
                    .formParam("TemplateBody", template)
                    .formParam("Parameters.member.1.ParameterKey", "Strict")
                    .formParam("Parameters.member.1.ParameterValue", strict ? "yes" : "no")
                    .post("/").then().statusCode(200);
            CfnStackWaits.awaitStatus(name, "CREATE_COMPLETE", EnforcementFixtures.auth(ACCOUNT, "cloudformation"));
            ValidatableResponse described = describeLaunchConfiguration(name);
            if (strict) {
                described.body(LAUNCH_CONFIGURATION + ".MetadataOptions.HttpTokens", equalTo("required"))
                        .body(LAUNCH_CONFIGURATION + ".MetadataOptions.HttpEndpoint", equalTo("enabled"))
                        .body(LAUNCH_CONFIGURATION + ".MetadataOptions.HttpPutResponseHopLimit", equalTo("1"));
            } else {
                described.body(LAUNCH_CONFIGURATION + ".MetadataOptions.size()", equalTo(0));
            }
        } finally {
            deleteStack(name);
        }
    }

    @Test
    void partialMetadataFillsOnlyTheOtherMetadataDefaults() {
        String name = "partial-metadata-" + UUID.randomUUID();
        request("autoscaling", "CreateLaunchConfiguration")
                .formParam("LaunchConfigurationName", name).formParam("ImageId", "ami-amazonlinux2023")
                .formParam("InstanceType", "t3.micro").formParam("MetadataOptions.HttpTokens", "required")
                .post("/").then().statusCode(200);
        try {
            describeLaunchConfiguration(name)
                    .body(LAUNCH_CONFIGURATION + ".MetadataOptions.HttpTokens", equalTo("required"))
                    .body(LAUNCH_CONFIGURATION + ".MetadataOptions.HttpEndpoint", equalTo("enabled"))
                    .body(LAUNCH_CONFIGURATION + ".MetadataOptions.HttpPutResponseHopLimit", equalTo("1"));
        } finally {
            deleteLaunchConfiguration(name);
        }
    }

    private static void createGroup(String name) {
        request("autoscaling", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", name).formParam("LaunchConfigurationName", name)
                .formParam("MinSize", "1").formParam("MaxSize", "1").formParam("DesiredCapacity", "1")
                .formParam("AvailabilityZones.member.1", "us-east-1a").post("/").then().statusCode(200);
    }

    /** The named launch configuration's Describe response, after checking it was found. */
    private static ValidatableResponse describeLaunchConfiguration(String name) {
        return request("autoscaling", "DescribeLaunchConfigurations")
                .formParam("LaunchConfigurationNames.member.1", name).post("/").then().statusCode(200)
                .body(LAUNCH_CONFIGURATION + ".LaunchConfigurationName", equalTo(name));
    }

    // Cleanup ignores the status, so a test that failed before creating something keeps its own failure.
    private static void deleteGroupAndLaunchConfiguration(String name) {
        request("autoscaling", "DeleteAutoScalingGroup").formParam("AutoScalingGroupName", name)
                .formParam("ForceDelete", "true").post("/");
        deleteLaunchConfiguration(name);
    }

    private static void deleteLaunchConfiguration(String name) {
        request("autoscaling", "DeleteLaunchConfiguration").formParam("LaunchConfigurationName", name).post("/");
    }

    private static void deleteStack(String name) {
        request("cloudformation", "DeleteStack").formParam("StackName", name).post("/");
    }

    private static RequestSpecification request(String service, String action) {
        return given().header("Authorization", EnforcementFixtures.auth(ACCOUNT, service)).formParam("Action", action);
    }
}
