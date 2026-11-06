package com.floci.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateFleetResponse;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.RequestSpotLaunchSpecification;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.SpotInstanceRequest;
import software.amazon.awssdk.services.servicequotas.ServiceQuotasClient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class Ec2VcpuAdmissionTest {
    @Test
    void quotaAdmissionFollowsOptInWhileSpotAndOtherAccountsRemainIndependent() {
        assumeFalse(TestFixtures.isRealAws(), "Local opt-in admission and mock guest lifecycle only");
        Region region = TestFixtures.region();
        String owner = "765432101234";
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(owner, "test"));
        List<String> ids = new ArrayList<>();
        List<String> otherIds = new ArrayList<>();
        List<String> regionIds = new ArrayList<>();
        String templateId = null;
        String spotRequestId = null;
        try (Ec2Client ec2 = client(region, owner);
             Ec2Client other = client(region, "765432101235");
             Ec2Client otherRegion = client(Region.of(region.id().startsWith("cn-")
                     ? "cn-northwest-1" : "eu-west-1"), owner);
             ServiceQuotasClient quotas = ServiceQuotasClient.builder().endpointOverride(TestFixtures.endpoint())
                     .region(region).credentialsProvider(credentials).build()) {
            int limit = quotas.getServiceQuota(r -> r.serviceCode("ec2").quotaCode("L-1216C47A"))
                    .quota().value().intValue();
            int expectedLimit = Integer.parseInt(System.getenv().getOrDefault(
                    "FLOCI_TEST_EXPECTED_STANDARD_ON_DEMAND_VCPUS", "5"));
            assertThat(limit).as("applied quota retains the ordinary default or explicit fixture value")
                    .isEqualTo(expectedLimit);
            if (!enforcementExpected()) {
                try {
                    ec2.runInstances(r -> r.imageId("ami-0abcdef1234567890").instanceType(InstanceType.T2_MICRO)
                            .minCount(6).maxCount(6)).instances().forEach(i -> ids.add(i.instanceId()));
                    ids.add(ec2.runInstances(r -> r.imageId("ami-0abcdef1234567890")
                            .instanceType(InstanceType.M5_2_XLARGE).minCount(1).maxCount(1))
                            .instances().get(0).instanceId());
                    assertThat(ids).hasSize(7);
                    ec2.stopInstances(r -> r.instanceIds(ids));
                    ec2.startInstances(r -> r.instanceIds(ids));
                    assertThat(ec2.describeInstances(r -> r.instanceIds(ids)).reservations())
                            .flatExtracting(reservation -> reservation.instances())
                            .allSatisfy(instance -> assertThat(instance.state().nameAsString()).isEqualTo("running"));
                } finally {
                    if (!ids.isEmpty()) {
                        ec2.terminateInstances(r -> r.instanceIds(ids));
                    }
                }
                return;
            }
            assertThat(limit).as("compatibility fixture uses a small positive applied quota")
                    .isBetween(2, 32);
            int guests = limit / 2;
            try {
                RunInstancesResponse batch = ec2.runInstances(r -> r.imageId("ami-0abcdef1234567890")
                        .instanceType(InstanceType.T3_MICRO).minCount(1).maxCount(guests + 2));
                batch.instances().forEach(i -> ids.add(i.instanceId()));
                assertThat(ids).hasSize(guests);
                ec2.waiter().waitUntilInstanceRunning(r -> r.instanceIds(ids));
                Ec2Exception full = assertThrows(Ec2Exception.class, () -> launch(ec2));
                assertThat(full.statusCode()).isEqualTo(400);
                assertThat(full.awsErrorDetails().errorCode()).isEqualTo("VcpuLimitExceeded");
                otherIds.add(launch(other));
                regionIds.add(launch(otherRegion));

                SpotInstanceRequest spot = ec2.requestSpotInstances(r -> r.instanceCount(1).type("one-time")
                        .launchSpecification(RequestSpotLaunchSpecification.builder()
                                .imageId("ami-0abcdef1234567890").instanceType(InstanceType.T3_MICRO).build()))
                        .spotInstanceRequests().get(0);
                spotRequestId = spot.spotInstanceRequestId();
                ids.add(spot.instanceId());
                String spotId = spot.instanceId();
                ec2.stopInstances(r -> r.instanceIds(spotId));
                ec2.startInstances(r -> r.instanceIds(spotId));

                templateId = ec2.createLaunchTemplate(r -> r.launchTemplateName("quota-" + UUID.randomUUID())
                        .launchTemplateData(d -> d.imageId("ami-0abcdef1234567890")
                                .instanceType(InstanceType.T3_MICRO))).launchTemplate().launchTemplateId();
                String directSpot = ec2.runInstances(r -> r.imageId("ami-0abcdef1234567890")
                        .instanceType(InstanceType.T3_MICRO).minCount(1).maxCount(1)
                        .instanceMarketOptions(m -> m.marketType("spot"))).instances().get(0).instanceId();
                ids.add(directSpot);
                assertThat(ec2.describeInstances(r -> r.instanceIds(directSpot)).reservations().get(0)
                        .instances().get(0).instanceLifecycleAsString()).isEqualTo("spot");
                ec2.stopInstances(r -> r.instanceIds(directSpot));
                ec2.startInstances(r -> r.instanceIds(directSpot));
                assertThat(ec2.describeInstances(r -> r.instanceIds(directSpot)).reservations().get(0)
                        .instances().get(0).instanceLifecycleAsString()).isEqualTo("spot");
                String template = templateId;
                ec2.createLaunchTemplateVersion(r -> r.launchTemplateId(template)
                        .launchTemplateData(d -> d.imageId("ami-0abcdef1234567890")
                                .instanceType(InstanceType.T3_MICRO).instanceMarketOptions(m -> m.marketType("spot"))));
                RunInstancesResponse templateSpot = ec2.runInstances(r -> r.minCount(1).maxCount(1)
                        .launchTemplate(t -> t.launchTemplateId(template).version("$Latest")));
                ids.add(templateSpot.instances().get(0).instanceId());
                assertThat(templateSpot.instances().get(0).instanceLifecycleAsString()).isEqualTo("spot");
                assertThat(assertThrows(Ec2Exception.class, () -> ec2.runInstances(r -> r.minCount(1).maxCount(1)
                        .launchTemplate(t -> t.launchTemplateId(template).version("$Latest"))
                        .instanceMarketOptions(m -> m.marketType("on-demand"))))
                        .awsErrorDetails().errorCode()).isEqualTo("VcpuLimitExceeded");
                CreateFleetResponse fleet = ec2.createFleet(r -> r.type("instant").launchTemplateConfigs(c ->
                                c.launchTemplateSpecification(t -> t.launchTemplateId(template).version("$Latest")))
                        .targetCapacitySpecification(t -> t.totalTargetCapacity(1).defaultTargetCapacityType("spot")));
                fleet.instances().forEach(group -> ids.addAll(group.instanceIds()));
                assertThat(fleet.instances()).hasSize(1);
                assertThat(assertThrows(Ec2Exception.class, () -> launch(ec2)).awsErrorDetails().errorCode())
                        .isEqualTo("VcpuLimitExceeded");

                String stopped = ids.get(0);
                ec2.stopInstances(r -> r.instanceIds(stopped));
                String replacement = launch(ec2);
                ids.add(replacement);
                ec2.waiter().waitUntilInstanceRunning(r -> r.instanceIds(replacement));
                assertThat(assertThrows(Ec2Exception.class, () -> ec2.startInstances(r -> r.instanceIds(stopped)))
                        .awsErrorDetails().errorCode()).isEqualTo("VcpuLimitExceeded");
                assertThat(ec2.describeInstances(r -> r.instanceIds(stopped)).reservations().get(0)
                        .instances().get(0).state().nameAsString()).isEqualTo("stopped");
                ec2.terminateInstances(r -> r.instanceIds(replacement));
                ec2.startInstances(r -> r.instanceIds(stopped));
                assertThat(ec2.describeInstances(r -> r.instanceIds(stopped)).reservations().get(0)
                        .instances().get(0).state().nameAsString()).isEqualTo("running");
            } finally {
                List<Executable> cleanup = new ArrayList<>();
                if (!ids.isEmpty()) {
                    cleanup.add(() -> ec2.terminateInstances(r -> r.instanceIds(ids)));
                }
                if (!otherIds.isEmpty()) {
                    cleanup.add(() -> other.terminateInstances(r -> r.instanceIds(otherIds)));
                }
                if (!regionIds.isEmpty()) {
                    cleanup.add(() -> otherRegion.terminateInstances(r -> r.instanceIds(regionIds)));
                }
                if (spotRequestId != null) {
                    String requestId = spotRequestId;
                    cleanup.add(() -> ec2.cancelSpotInstanceRequests(r -> r.spotInstanceRequestIds(requestId)));
                }
                if (templateId != null) {
                    String template = templateId;
                    cleanup.add(() -> ec2.deleteLaunchTemplate(r -> r.launchTemplateId(template)));
                }
                assertAll(cleanup);
            }
        }
    }

    @Test
    void unknownTypesFollowTheEnforcementOptIn() {
        assumeFalse(TestFixtures.isRealAws(), "Local unsupported-type mapping is not an AWS response claim");
        try (Ec2Client ec2 = client(TestFixtures.region(), "765432101236")) {
            if (!enforcementExpected()) {
                String id = ec2.runInstances(r -> r.imageId("ami-0abcdef1234567890")
                        .instanceType("m999.unmeasured").minCount(1).maxCount(1)).instances().get(0).instanceId();
                try {
                    ec2.stopInstances(r -> r.instanceIds(id));
                    ec2.startInstances(r -> r.instanceIds(id));
                    assertThat(ec2.describeInstances(r -> r.instanceIds(id)).reservations().get(0)
                            .instances().get(0).state().nameAsString()).isEqualTo("running");
                } finally {
                    ec2.terminateInstances(r -> r.instanceIds(id));
                }
                return;
            }
            Ec2Exception unsupported = assertThrows(Ec2Exception.class, () -> ec2.runInstances(r ->
                    r.imageId("ami-0abcdef1234567890").instanceType("m999.unmeasured").minCount(1).maxCount(1)));
            assertThat(unsupported.statusCode()).isEqualTo(400);
            assertThat(unsupported.awsErrorDetails().errorCode()).isEqualTo("InvalidParameterValue");
            assertThat(ec2.describeInstances().reservations()).isEmpty();
        }
    }

    private static boolean enforcementExpected() {
        return Boolean.parseBoolean(System.getenv().getOrDefault("FLOCI_TEST_EC2_ENFORCE_VCPU_QUOTAS", "false"));
    }

    private static String launch(Ec2Client ec2) {
        return ec2.runInstances(r -> r.imageId("ami-0abcdef1234567890").instanceType(InstanceType.T3_MICRO)
                .minCount(1).maxCount(1)).instances().get(0).instanceId();
    }

    private static Ec2Client client(Region region, String account) {
        return Ec2Client.builder().endpointOverride(TestFixtures.endpoint()).region(region)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .build();
    }
}
