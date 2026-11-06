package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.ec2.model.PrivateIpAddressSpecification;
import software.amazon.awssdk.services.ec2.model.RequestLaunchTemplateData;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Ec2RunInstancesPrivateIpTest {
    private static final String COMBINATION_MESSAGE =
            "Network interfaces and an instance-level private IP address may not be specified on the same request";

    @Test
    @DisplayName("RunInstances gives the instance the PrivateIpAddress it asked for and refuses one in use")
    void launchesAtTheRequestedPrivateIp() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String vpcId = ec2.createVpc(r -> r.cidrBlock("10.76.0.0/16")).vpc().vpcId();
            String subnetId = null;
            List<String> instanceIds = new ArrayList<>();
            try {
                subnetId = ec2.createSubnet(r -> r.vpcId(vpcId).cidrBlock("10.76.1.0/24"))
                        .subnet().subnetId();
                String subnet = subnetId;

                for (String requested : List.of("10.76.1.50", "10.76.1.60")) {
                    Instance launched = ec2.runInstances(r -> r.imageId("ami-0abcdef1234567890")
                            .instanceType(InstanceType.T3_MICRO).minCount(1).maxCount(1)
                            .subnetId(subnet).privateIpAddress(requested)).instances().get(0);
                    instanceIds.add(launched.instanceId());
                    assertThat(launched.privateIpAddress()).isEqualTo(requested);

                    Instance described = ec2.describeInstances(r -> r.instanceIds(launched.instanceId()))
                            .reservations().get(0).instances().get(0);
                    assertThat(described.privateIpAddress()).isEqualTo(requested);
                    assertThat(described.networkInterfaces()).singleElement()
                            .satisfies(eni -> assertThat(eni.privateIpAddress()).isEqualTo(requested));
                }

                Ec2Exception inUse = assertThrows(Ec2Exception.class, () -> ec2.runInstances(r ->
                        r.imageId("ami-0abcdef1234567890").instanceType(InstanceType.T3_MICRO)
                                .minCount(1).maxCount(1).subnetId(subnet).privateIpAddress("10.76.1.50")));
                assertThat(inUse.statusCode()).isEqualTo(400);
                assertThat(inUse.awsErrorDetails().errorCode()).isEqualTo("InvalidIPAddress.InUse");

                Ec2Exception outside = assertThrows(Ec2Exception.class, () -> ec2.runInstances(r ->
                        r.imageId("ami-0abcdef1234567890").instanceType(InstanceType.T3_MICRO)
                                .minCount(1).maxCount(1).subnetId(subnet).privateIpAddress("10.76.2.50")));
                assertThat(outside.statusCode()).isEqualTo(400);
                assertThat(outside.awsErrorDetails().errorCode()).isEqualTo("InvalidParameterValue");

                // Neither refusal leaves an instance behind in the subnet.
                assertThat(instanceIdsInSubnet(ec2, subnet)).containsExactlyInAnyOrderElementsOf(instanceIds);
            } finally {
                if (!instanceIds.isEmpty()) {
                    ec2.terminateInstances(r -> r.instanceIds(instanceIds));
                }
                if (subnetId != null) {
                    String cleanup = subnetId;
                    ec2.deleteSubnet(r -> r.subnetId(cleanup));
                }
                ec2.deleteVpc(r -> r.vpcId(vpcId));
            }
        }
    }

    @Test
    @DisplayName("RunInstances takes the address of the Primary PrivateIpAddresses entry on the interface spec")
    void launchesAtThePrimaryEntryOfTheInterfaceSpec() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String vpcId = ec2.createVpc(r -> r.cidrBlock("10.78.0.0/16")).vpc().vpcId();
            String subnetId = null;
            String instanceId = null;
            try {
                subnetId = ec2.createSubnet(r -> r.vpcId(vpcId).cidrBlock("10.78.1.0/24"))
                        .subnet().subnetId();
                String subnet = subnetId;
                String requested = "10.78.1.50";

                // The shape the AWS CLI sends for --private-ip-address with --associate-public-ip-address.
                Instance launched = ec2.runInstances(r -> r.imageId("ami-0abcdef1234567890")
                        .instanceType(InstanceType.T3_MICRO).minCount(1).maxCount(1)
                        .networkInterfaces(n -> n.deviceIndex(0).subnetId(subnet).associatePublicIpAddress(true)
                                .privateIpAddresses(PrivateIpAddressSpecification.builder()
                                        .primary(true).privateIpAddress(requested).build())))
                        .instances().get(0);
                instanceId = launched.instanceId();
                assertThat(launched.privateIpAddress()).isEqualTo(requested);

                Instance described = ec2.describeInstances(r -> r.instanceIds(launched.instanceId()))
                        .reservations().get(0).instances().get(0);
                assertThat(described.privateIpAddress()).isEqualTo(requested);
                assertThat(described.networkInterfaces()).singleElement()
                        .satisfies(eni -> assertThat(eni.privateIpAddress()).isEqualTo(requested));
            } finally {
                if (instanceId != null) {
                    String cleanupInstance = instanceId;
                    ec2.terminateInstances(r -> r.instanceIds(cleanupInstance));
                }
                if (subnetId != null) {
                    String cleanup = subnetId;
                    ec2.deleteSubnet(r -> r.subnetId(cleanup));
                }
                ec2.deleteVpc(r -> r.vpcId(vpcId));
            }
        }
    }

    @Test
    @DisplayName("RunInstances from a launch template gives the instance its primary interface's PrivateIpAddress")
    void launchesAtTheLaunchTemplateInterfaceAddress() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String vpcId = ec2.createVpc(r -> r.cidrBlock("10.79.0.0/16")).vpc().vpcId();
            String subnetId = null;
            String templateId = null;
            String instanceId = null;
            try {
                subnetId = ec2.createSubnet(r -> r.vpcId(vpcId).cidrBlock("10.79.1.0/24"))
                        .subnet().subnetId();
                String subnet = subnetId;
                String requested = "10.79.1.70";

                templateId = ec2.createLaunchTemplate(r -> r.launchTemplateName("fixed-ip-" + UUID.randomUUID())
                        .launchTemplateData(RequestLaunchTemplateData.builder()
                                .imageId("ami-0abcdef1234567890").instanceType(InstanceType.T3_MICRO)
                                .networkInterfaces(n -> n.deviceIndex(0).subnetId(subnet).privateIpAddress(requested))
                                .build()))
                        .launchTemplate().launchTemplateId();
                LaunchTemplateSpecification template = LaunchTemplateSpecification.builder()
                        .launchTemplateId(templateId).build();

                Instance launched = ec2.runInstances(r -> r.launchTemplate(template).minCount(1).maxCount(1))
                        .instances().get(0);
                instanceId = launched.instanceId();
                assertThat(launched.privateIpAddress()).isEqualTo(requested);

                Instance described = ec2.describeInstances(r -> r.instanceIds(launched.instanceId()))
                        .reservations().get(0).instances().get(0);
                assertThat(described.privateIpAddress()).isEqualTo(requested);
                assertThat(described.networkInterfaces()).singleElement()
                        .satisfies(eni -> assertThat(eni.privateIpAddress()).isEqualTo(requested));
            } finally {
                if (instanceId != null) {
                    String cleanupInstance = instanceId;
                    ec2.terminateInstances(r -> r.instanceIds(cleanupInstance));
                }
                if (templateId != null) {
                    String cleanupTemplate = templateId;
                    ec2.deleteLaunchTemplate(r -> r.launchTemplateId(cleanupTemplate));
                }
                if (subnetId != null) {
                    String cleanup = subnetId;
                    ec2.deleteSubnet(r -> r.subnetId(cleanup));
                }
                ec2.deleteVpc(r -> r.vpcId(vpcId));
            }
        }
    }

    @Test
    @DisplayName("RunInstances refuses an instance-level PrivateIpAddress together with network interfaces")
    void refusesAnInstanceLevelPrivateIpWithNetworkInterfaces() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String vpcId = ec2.createVpc(r -> r.cidrBlock("10.74.0.0/16")).vpc().vpcId();
            String subnetId = null;
            String templateId = null;
            try {
                subnetId = ec2.createSubnet(r -> r.vpcId(vpcId).cidrBlock("10.74.1.0/24"))
                        .subnet().subnetId();
                String subnet = subnetId;

                Ec2Exception onRequest = assertThrows(Ec2Exception.class, () -> ec2.runInstances(r ->
                        r.imageId("ami-0abcdef1234567890").instanceType(InstanceType.T3_MICRO)
                                .minCount(1).maxCount(1).privateIpAddress("10.74.1.80")
                                .networkInterfaces(n -> n.deviceIndex(0).subnetId(subnet))));
                assertThat(onRequest.statusCode()).isEqualTo(400);
                assertThat(onRequest.awsErrorDetails().errorCode()).isEqualTo("InvalidParameterCombination");
                assertThat(onRequest.awsErrorDetails().errorMessage()).isEqualTo(COMBINATION_MESSAGE);

                templateId = ec2.createLaunchTemplate(r -> r.launchTemplateName("fixed-ip-" + UUID.randomUUID())
                        .launchTemplateData(RequestLaunchTemplateData.builder()
                                .imageId("ami-0abcdef1234567890").instanceType(InstanceType.T3_MICRO)
                                .networkInterfaces(n -> n.deviceIndex(0).subnetId(subnet))
                                .build()))
                        .launchTemplate().launchTemplateId();
                LaunchTemplateSpecification template = LaunchTemplateSpecification.builder()
                        .launchTemplateId(templateId).build();

                Ec2Exception onTemplate = assertThrows(Ec2Exception.class, () -> ec2.runInstances(r ->
                        r.launchTemplate(template).minCount(1).maxCount(1).privateIpAddress("10.74.1.81")));
                assertThat(onTemplate.statusCode()).isEqualTo(400);
                assertThat(onTemplate.awsErrorDetails().errorCode()).isEqualTo("InvalidParameterCombination");
                assertThat(onTemplate.awsErrorDetails().errorMessage()).isEqualTo(COMBINATION_MESSAGE);

                assertThat(instanceIdsInSubnet(ec2, subnet)).isEmpty();
            } finally {
                // A launch the refusal failed to stop would otherwise block the subnet delete.
                if (subnetId != null) {
                    List<String> launched = instanceIdsInSubnet(ec2, subnetId);
                    if (!launched.isEmpty()) {
                        ec2.terminateInstances(r -> r.instanceIds(launched));
                    }
                }
                if (templateId != null) {
                    String cleanupTemplate = templateId;
                    ec2.deleteLaunchTemplate(r -> r.launchTemplateId(cleanupTemplate));
                }
                if (subnetId != null) {
                    String cleanup = subnetId;
                    ec2.deleteSubnet(r -> r.subnetId(cleanup));
                }
                ec2.deleteVpc(r -> r.vpcId(vpcId));
            }
        }
    }

    private static List<String> instanceIdsInSubnet(Ec2Client ec2, String subnetId) {
        return ec2.describeInstances(r -> r.filters(f -> f.name("subnet-id").values(subnetId)))
                .reservations().stream()
                .flatMap(reservation -> reservation.instances().stream())
                .map(Instance::instanceId)
                .toList();
    }
}
