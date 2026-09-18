package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.RequestLaunchTemplateData;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.iam.IamClient;

import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class Ec2InstanceProfilePathTest {
    static Stream<Arguments> launches() {
        return Stream.of("direct", "template", "fleet", "override").flatMap(mode ->
                Stream.of("/", "/workers/nested/").map(path -> Arguments.of(mode, path)));
    }

    @ParameterizedTest
    @MethodSource("launches")
    @DisplayName("EC2 launches resolve profile names to the full IAM ARN, including its path")
    void preservesInstanceProfilePath(String mode, String path) {
        String name = "profile-path-" + UUID.randomUUID();
        try (Ec2Client ec2 = TestFixtures.ec2Client(); IamClient iam = TestFixtures.iamClient()) {
            String profileArn = iam.createInstanceProfile(r -> r.instanceProfileName(name).path(path))
                    .instanceProfile().arn();
            String instanceId = null;
            String templateId = null;
            try {
                RunInstancesRequest.Builder launch = RunInstancesRequest.builder().minCount(1).maxCount(1);
                if (mode.equals("direct")) {
                    launch.imageId("ami-0abcdef1234567890").instanceType(InstanceType.T3_MICRO)
                            .iamInstanceProfile(p -> p.name(name));
                } else {
                    templateId = ec2.createLaunchTemplate(r -> r.launchTemplateName(name)
                            .launchTemplateData(RequestLaunchTemplateData.builder()
                                    .imageId("ami-0abcdef1234567890").instanceType(InstanceType.T3_MICRO)
                                    .iamInstanceProfile(p -> p.name(mode.equals("override") ? "missing-" + name : name))
                                    .build())).launchTemplate().launchTemplateId();
                    String selectedTemplate = templateId;
                    launch.launchTemplate(t -> t.launchTemplateId(selectedTemplate).version("$Latest"));
                    if (mode.equals("override")) {
                        launch.iamInstanceProfile(p -> p.name(name));
                    }
                }
                if (mode.equals("fleet")) {
                    String selectedTemplate = templateId;
                    instanceId = ec2.createFleet(r -> r.type("instant")
                            .launchTemplateConfigs(c -> c.launchTemplateSpecification(t ->
                                    t.launchTemplateId(selectedTemplate).version("$Latest")))
                            .targetCapacitySpecification(t -> t.totalTargetCapacity(1)
                                    .defaultTargetCapacityType("on-demand")))
                            .instances().get(0).instanceIds().get(0);
                } else {
                    instanceId = ec2.runInstances(launch.build()).instances().get(0).instanceId();
                }
                String selectedInstance = instanceId;
                assertThat(ec2.describeInstances(r -> r.instanceIds(selectedInstance))
                        .reservations().get(0).instances().get(0).iamInstanceProfile().arn()).isEqualTo(profileArn);
            } finally {
                if (instanceId != null) {
                    String cleanupId = instanceId;
                    ec2.terminateInstances(r -> r.instanceIds(cleanupId));
                }
                if (templateId != null) {
                    String cleanupId = templateId;
                    ec2.deleteLaunchTemplate(r -> r.launchTemplateId(cleanupId));
                }
                iam.deleteInstanceProfile(r -> r.instanceProfileName(name));
            }
        }
    }
}
