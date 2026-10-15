package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingReconciler;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingService;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class AutoScalingConfigurationUserDataIntegrationTest {
    @Inject AutoScalingReconciler reconciler;
    @InjectSpy AutoScalingService asg;
    @Inject Ec2Service ec2;

    @BeforeEach
    void isolateDirectReconciliation() {
        // The custom background executor lists every group with null region and names.
        doReturn(List.of()).when(asg).describeAutoScalingGroups(isNull(), isNull());
    }

    @AfterEach
    void restoreBackgroundEnumeration() {
        reset(asg);
    }

    static Stream<Arguments> launchSources() {
        return Stream.concat(Stream.of("query", "cfn").flatMap(source ->
                Stream.of(null, "", "YQ==", "YQ", "/w", Base64.getEncoder().encodeToString(new byte[16384]), "H4sIAAAAAAAC/1NW1E/KzNMvzuBKTc7IV8hIzcnJ5wIAedQ/FxUAAAA=")
                        .map(data -> Arguments.of(source, data))), Stream.of(Arguments.of("cfn-intrinsic", "YQ==")));
    }

    @ParameterizedTest
    @MethodSource("launchSources")
    void launchPreservesOriginalEncodingAndImdsBytes(String source, String encoded) throws Exception {
        String name = "userdata-" + UUID.randomUUID().toString().substring(0, 8);
        createConfiguration(source, name, encoded);
        try {
            if (!source.equals("query")) {
                String status = request("cloudformation", "DescribeStacks").formParam("StackName", name)
                        .post("/").then().statusCode(200).extract().xmlPath()
                        .getString("DescribeStacksResponse.DescribeStacksResult.Stacks.member.StackStatus");
                assertEquals("CREATE_COMPLETE", status);
            }
            String configurationData = request("autoscaling", "DescribeLaunchConfigurations")
                    .formParam("LaunchConfigurationNames.member.1", name).post("/").then().statusCode(200)
                    .extract().xmlPath().getString("DescribeLaunchConfigurationsResponse."
                            + "DescribeLaunchConfigurationsResult.LaunchConfigurations.member.UserData");
            assertEquals(encoded == null ? "" : encoded, configurationData);
            RequestSpecification group = request("autoscaling", "CreateAutoScalingGroup")
                    .formParam("AutoScalingGroupName", name).formParam("MinSize", 0)
                    .formParam("MaxSize", 1).formParam("DesiredCapacity", 0)
                    .formParam("AvailabilityZones.member.1", "us-east-1a");
            group.formParam("LaunchConfigurationName", name);
            group.post("/").then().statusCode(200);
            try {
                request("autoscaling", "SetDesiredCapacity").formParam("AutoScalingGroupName", name)
                        .formParam("DesiredCapacity", 1).post("/").then().statusCode(200);
                reconciler.reconcile(asg.describeAutoScalingGroups("us-east-1", List.of(name)).getFirst());
                String id = request("autoscaling", "DescribeAutoScalingGroups")
                        .formParam("AutoScalingGroupNames.member.1", name).post("/").then().statusCode(200)
                        .extract().path("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult."
                                + "AutoScalingGroups.member.Instances.member.InstanceId");
                assertNotNull(id);
                Instance instance = ec2.describeInstances("us-east-1", List.of(id), Map.of()).getFirst()
                        .getInstances().getFirst();
                String actual = request("ec2", "DescribeInstanceAttribute").formParam("InstanceId", id)
                        .formParam("Attribute", "userData").post("/").then().statusCode(200)
                        .extract().xmlPath().getString("DescribeInstanceAttributeResponse.userData.value");
                try (Ec2MetadataTestFixture metadata = new Ec2MetadataTestFixture()) {
                    metadata.start(null, mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), instance);
                    HttpResponse<byte[]> response = metadata.get("/latest/user-data", null,
                            HttpResponse.BodyHandlers.ofByteArray());
                    assertAll(
                            () -> assertEquals(encoded == null ? "" : encoded, actual),
                            () -> assertEquals(normalize(Ec2UserDataDecoder.decode(encoded)), normalize(instance.getUserData())),
                            () -> assertEquals(encoded == null || encoded.isEmpty() ? 404 : 200, response.statusCode()),
                            () -> {
                                if (encoded != null && !encoded.isEmpty()) {
                                    assertArrayEquals(Base64.getDecoder().decode(encoded), response.body());
                                }
                            });
                }
            } finally {
                request("autoscaling", "DeleteAutoScalingGroup").formParam("AutoScalingGroupName", name)
                        .formParam("ForceDelete", true).post("/").then().statusCode(200);
            }
        } finally {
            deleteConfiguration(source, name);
        }
    }

    static Stream<Arguments> rejectedConfigurations() {
        return Stream.of("query", "cfn").flatMap(source -> Stream.of("not base64!", "H4sI",
                Base64.getEncoder().encodeToString(new byte[16385])).map(data -> Arguments.of(source, data)));
    }

    @ParameterizedTest
    @MethodSource("rejectedConfigurations")
    void invalidConfigurationFailsWithoutLeavingLaunchSource(String source, String data) {
        String name = "rejected-" + UUID.randomUUID().toString().substring(0, 8);
        if (source.equals("query")) {
            String code = request("autoscaling", "CreateLaunchConfiguration")
                    .formParam("LaunchConfigurationName", name).formParam("ImageId", "ami-amazonlinux2023")
                    .formParam("InstanceType", "t3.micro").formParam("UserData", data)
                    .post("/").then().statusCode(400).extract().xmlPath().getString("ErrorResponse.Error.Code");
            assertEquals("InvalidParameterValue", code);
        } else {
            createConfiguration(source, name, data);
            try {
                String status = request("cloudformation", "DescribeStacks").formParam("StackName", name)
                        .post("/").then().statusCode(200).extract().xmlPath()
                        .getString("DescribeStacksResponse.DescribeStacksResult.Stacks.member.StackStatus");
                assertEquals("ROLLBACK_COMPLETE", status);
                String events = request("cloudformation", "DescribeStackEvents").formParam("StackName", name)
                        .post("/").then().statusCode(200).extract().asString();
                assertTrue(events.contains("UserData"));
            } finally {
                deleteConfiguration(source, name);
            }
        }
        assertTrue(asg.describeLaunchConfigurations("us-east-1", List.of(name)).isEmpty());
        createConfiguration("query", name, "YQ==");
        deleteConfiguration("query", name);
    }

    private void createConfiguration(String source, String name, String encoded) {
        if (source.equals("query")) {
            RequestSpecification create = request("autoscaling", "CreateLaunchConfiguration")
                    .formParam("LaunchConfigurationName", name).formParam("ImageId", "ami-amazonlinux2023")
                    .formParam("InstanceType", "t3.micro");
            if (encoded != null) {
                create.formParam("UserData", encoded);
            }
            create.post("/").then().statusCode(200);
        } else {
            String data = encoded == null ? "" : ", \"UserData\": "
                    + (source.equals("cfn-intrinsic") ? "{\"Fn::Base64\": \"a\"}" : "\"" + encoded + "\"");
            String template = "{\"Resources\":{\"Lc\":{\"Type\":\"AWS::AutoScaling::LaunchConfiguration\","
                    + "\"Properties\":{\"LaunchConfigurationName\":\"" + name
                    + "\",\"ImageId\":\"ami-amazonlinux2023\",\"InstanceType\":\"t3.micro\"" + data + "}}}}";
            request("cloudformation", "CreateStack").formParam("StackName", name)
                    .formParam("TemplateBody", template).post("/").then().statusCode(200);
        }
    }

    private void deleteConfiguration(String source, String name) {
        if (source.equals("query")) {
            request("autoscaling", "DeleteLaunchConfiguration").formParam("LaunchConfigurationName", name)
                    .post("/").then().statusCode(200);
        } else {
            request("cloudformation", "DeleteStack").formParam("StackName", name)
                    .post("/").then().statusCode(200);
        }
    }

    static Stream<Arguments> inheritedData() {
        return Stream.of(Arguments.of("YQ==", null), Arguments.of("/w", null),
                Arguments.of("H4sIAAAAAAAC/1NW1E/KzNMvzuBKTc7IV8hIzcnJ5wIAedQ/FxUAAAA=", null),
                Arguments.of("YQ==", "Yg=="), Arguments.of("YQ==", ""),
                Arguments.of("YQ==", " "));
    }

    @ParameterizedTest
    @MethodSource("inheritedData")
    void configurationCopiesSourceEncodingUnlessOverridden(String sourceData, String override) {
        String name = "inherited-" + UUID.randomUUID().toString().substring(0, 8);
        String id = request("ec2", "RunInstances").formParam("ImageId", "ami-amazonlinux2023")
                .formParam("InstanceType", "t3.micro").formParam("UserData", sourceData)
                .post("/").then().statusCode(200).extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        try {
            RequestSpecification create = request("autoscaling", "CreateLaunchConfiguration")
                    .formParam("LaunchConfigurationName", name).formParam("InstanceId", id);
            if (override != null) {
                create.formParam("UserData", override);
            }
            create.post("/").then().statusCode(200);
            try {
                String actual = request("autoscaling", "DescribeLaunchConfigurations")
                        .formParam("LaunchConfigurationNames.member.1", name).post("/").then().statusCode(200)
                        .extract().xmlPath().getString("DescribeLaunchConfigurationsResponse."
                                + "DescribeLaunchConfigurationsResult.LaunchConfigurations.member.UserData");
                assertEquals(override == null || override.isBlank() ? sourceData : override, actual);
            } finally {
                request("autoscaling", "DeleteLaunchConfiguration").formParam("LaunchConfigurationName", name)
                        .post("/").then().statusCode(200);
            }
        } finally {
            request("ec2", "TerminateInstances").formParam("InstanceId.1", id).post("/")
                    .then().statusCode(200);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }

    private static RequestSpecification request(String service, String action) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260215/us-east-1/"
                + service + "/aws4_request, SignedHeaders=host, Signature=abc").formParam("Action", action);
    }
}
