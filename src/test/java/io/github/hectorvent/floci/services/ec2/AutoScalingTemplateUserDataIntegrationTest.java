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
class AutoScalingTemplateUserDataIntegrationTest {
    @Inject Ec2Service ec2;
    @Inject AutoScalingReconciler reconciler;
    @InjectSpy AutoScalingService asg;

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
        return Stream.of("template", "mixed").flatMap(source ->
                Stream.of(null, "", "YQ==", "YQ", "/w", "H4sIAAAAAAAC/1NW1E/KzNMvzuBKTc7IV8hIzcnJ5wIAedQ/FxUAAAA=")
                        .map(data -> Arguments.of(source, data)));
    }

    @ParameterizedTest
    @MethodSource("launchSources")
    void launchPreservesOriginalEncodingAndImdsBytes(String source, String encoded) throws Exception {
        String name = "userdata-" + UUID.randomUUID().toString().substring(0, 8);
        RequestSpecification create = request("ec2", "CreateLaunchTemplate");
        String prefix = "LaunchTemplateData.";
        create.formParam("LaunchTemplateName", name)
                .formParam(prefix + "ImageId", "ami-amazonlinux2023")
                .formParam(prefix + "InstanceType", "t3.micro");
        if (encoded != null) {
            create.formParam(prefix + "UserData", encoded);
        }
        create.post("/").then().statusCode(200);
        try {
            RequestSpecification group = request("autoscaling", "CreateAutoScalingGroup")
                    .formParam("AutoScalingGroupName", name).formParam("MinSize", 0)
                    .formParam("MaxSize", 1).formParam("DesiredCapacity", 0)
                    .formParam("AvailabilityZones.member.1", "us-east-1a");
            String template = "mixed".equals(source)
                        ? "MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification." : "LaunchTemplate.";
            group.formParam(template + "LaunchTemplateName", name).formParam(template + "Version", "1");
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
            request("ec2", "DeleteLaunchTemplate")
                    .formParam("LaunchTemplateName", name)
                    .post("/").then().statusCode(200);
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
