package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import static org.assertj.core.api.Assertions.assertThat;

class Ec2UserDataAttributeTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("DescribeInstanceAttribute returns base64 UserData from direct and launch-template launches")
    void describesUserDataWithoutDoubleEncoding(boolean useTemplate) {
        String script = "#!/bin/sh\necho 'héllo <world> & friends'\n";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String templateId = null;
            String instanceId = null;
            try {
                RunInstancesRequest.Builder launch = RunInstancesRequest.builder().minCount(1).maxCount(1);
                if (useTemplate) {
                    templateId = ec2.createLaunchTemplate(r -> r.launchTemplateName("userdata-" + UUID.randomUUID())
                            .launchTemplateData(RequestLaunchTemplateData.builder()
                                    .imageId("ami-0abcdef1234567890").instanceType(InstanceType.T3_MICRO)
                                    .userData(encoded).build())).launchTemplate().launchTemplateId();
                    launch.launchTemplate(LaunchTemplateSpecification.builder().launchTemplateId(templateId)
                            .version("$Latest").build());
                } else {
                    launch.imageId("ami-0abcdef1234567890").instanceType(InstanceType.T3_MICRO).userData(encoded);
                }
                instanceId = ec2.runInstances(launch.build()).instances().get(0).instanceId();
                String launchedId = instanceId;
                DescribeInstanceAttributeResponse response = ec2.describeInstanceAttribute(r ->
                        r.instanceId(launchedId).attribute(InstanceAttributeName.USER_DATA));
                assertThat(response.instanceId()).isEqualTo(instanceId);
                assertThat(response.userData()).isNotNull();
                assertThat(response.userData().value()).isEqualTo(encoded);
                assertThat(new String(Base64.getDecoder().decode(response.userData().value()), StandardCharsets.UTF_8))
                        .isEqualTo(script);
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
