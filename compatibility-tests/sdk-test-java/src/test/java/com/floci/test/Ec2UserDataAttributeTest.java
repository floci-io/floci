package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstanceAttributeResponse;
import software.amazon.awssdk.services.ec2.model.InstanceAttributeName;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.ec2.model.RequestLaunchTemplateData;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class Ec2UserDataAttributeTest {
    static Stream<Arguments> userDataCases() {
        String script = Base64.getEncoder().encodeToString("#!/bin/sh\necho 'héllo <world> & friends'\n"
                .getBytes(StandardCharsets.UTF_8));
        return Stream.of("direct", "template", "override", "fleet").flatMap(mode ->
                Stream.of(script, "YQ", "/w", "H4sIAAAAAAAC/1NW1E/KzNMvzuBKTc7IV8hIzcnJ5wIAedQ/FxUAAAA=")
                        .map(encoded -> Arguments.of(mode, encoded)));
    }

    @ParameterizedTest
    @MethodSource("userDataCases")
    @DisplayName("DescribeInstanceAttribute returns base64 UserData from direct and launch-template launches")
    void describesUserDataWithoutDoubleEncoding(String mode, String encoded) {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String templateId = null;
            String instanceId = null;
            try {
                RunInstancesRequest.Builder launch = RunInstancesRequest.builder().minCount(1).maxCount(1);
                if (!"direct".equals(mode)) {
                    templateId = ec2.createLaunchTemplate(r -> r.launchTemplateName("userdata-" + UUID.randomUUID())
                            .launchTemplateData(RequestLaunchTemplateData.builder()
                                    .imageId("ami-0abcdef1234567890").instanceType(InstanceType.T3_MICRO)
                                    .userData("override".equals(mode) ? "b3JpZ2luYWw=" : encoded).build())).launchTemplate().launchTemplateId();
                    launch.launchTemplate(LaunchTemplateSpecification.builder().launchTemplateId(templateId)
                            .version("$Latest").build());
                } else {
                    launch.imageId("ami-0abcdef1234567890").instanceType(InstanceType.T3_MICRO).userData(encoded);
                }
                if ("override".equals(mode)) {
                    launch.userData(encoded);
                }
                if ("fleet".equals(mode)) {
                    String fleetTemplate = templateId;
                    instanceId = ec2.createFleet(r -> r.type("instant")
                            .launchTemplateConfigs(c -> c.launchTemplateSpecification(t ->
                                    t.launchTemplateId(fleetTemplate).version("$Latest")))
                            .targetCapacitySpecification(t -> t.totalTargetCapacity(1)
                                    .defaultTargetCapacityType("on-demand")))
                            .instances().get(0).instanceIds().get(0);
                } else {
                    instanceId = ec2.runInstances(launch.build()).instances().get(0).instanceId();
                }
                String launchedId = instanceId;
                DescribeInstanceAttributeResponse response = ec2.describeInstanceAttribute(r ->
                        r.instanceId(launchedId).attribute(InstanceAttributeName.USER_DATA));
                assertThat(response.instanceId()).isEqualTo(instanceId);
                assertThat(response.userData()).isNotNull();
                assertThat(response.userData().value()).isEqualTo(encoded);
            } finally {
                if (instanceId != null) {
                    String cleanupId = instanceId;
                    ec2.terminateInstances(r -> r.instanceIds(cleanupId));
                }
                if (templateId != null) {
                    String cleanupTemplate = templateId;
                    ec2.deleteLaunchTemplate(r -> r.launchTemplateId(cleanupTemplate));
                }
            }
        }
    }
}
