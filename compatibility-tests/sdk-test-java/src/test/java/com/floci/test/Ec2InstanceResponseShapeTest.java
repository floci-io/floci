package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Verifies that RunInstances / DescribeInstances responses include all fields
 * that the Terraform AWS provider (v5+) dereferences without nil-checks.
 *
 * Issue: https://github.com/floci-io/floci/issues/871
 *
 * The crash in terraform-provider-aws resourceInstanceRead is caused by nil
 * pointer dereferences on missing response fields — most critically the ENI
 * attachment block, which Terraform reads to identify the primary network
 * interface by DeviceIndex.
 */
@DisplayName("EC2 Instance Response Shape (issue #871)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2InstanceResponseShapeTest {

    private static Ec2Client ec2;
    private static String instanceId;
    private static String instanceIdForCleanup;
    private static Instance instance;

    @BeforeAll
    static void setup() {
        ec2 = TestFixtures.ec2Client();

        RunInstancesResponse run = ec2.runInstances(RunInstancesRequest.builder()
                .imageId("ami-0abcdef1234567890")
                .instanceType(InstanceType.T3_MICRO)
                .minCount(1)
                .maxCount(1)
                .build());

        instanceId = run.instances().get(0).instanceId();
        instanceIdForCleanup = instanceId;

        DescribeInstancesResponse describe = ec2.describeInstances(
                DescribeInstancesRequest.builder().instanceIds(instanceId).build());

        instance = describe.reservations().get(0).instances().get(0);
    }

    @AfterAll
    static void cleanup() {
        if (ec2 != null) {
            try {
                if (instanceId != null) {
                    ec2.terminateInstances(TerminateInstancesRequest.builder()
                            .instanceIds(instanceId).build());
                }
                if (instanceIdForCleanup != null) {
                    for (int i = 0; i < 30; i++) {
                        Thread.sleep(1000);
                        DescribeInstancesResponse r = ec2.describeInstances(
                                DescribeInstancesRequest.builder().instanceIds(instanceIdForCleanup).build());
                        if (!r.reservations().isEmpty() && !r.reservations().get(0).instances().isEmpty()) {
                            InstanceStateName state = r.reservations().get(0).instances().get(0).state().name();
                            if (state == InstanceStateName.TERMINATED) break;
                        }
                    }
                }
            } catch (Exception ignored) {}
            ec2.close();
        }
    }

    // ── Core instance fields ─────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("instanceId and state are present")
    void instanceIdAndState() {
        assertThat(instance.instanceId()).isNotNull().startsWith("i-");
        assertThat(instance.state()).isNotNull();
        assertThat(instance.state().name()).isNotNull();
    }

    @Test
    @Order(2)
    @DisplayName("cpuOptions is not null with coreCount and threadsPerCore")
    void cpuOptions() {
        assertThat(instance.cpuOptions())
                .as("cpuOptions must not be null — Terraform dereferences CpuOptions.CoreCount directly")
                .isNotNull();
        assertThat(instance.cpuOptions().coreCount())
                .as("cpuOptions.coreCount must not be null")
                .isNotNull()
                .isGreaterThan(0);
        assertThat(instance.cpuOptions().threadsPerCore())
                .as("cpuOptions.threadsPerCore must not be null")
                .isNotNull()
                .isGreaterThan(0);
    }

    @Test
    @Order(3)
    @DisplayName("metadataOptions is not null with required sub-fields")
    void metadataOptions() {
        assertThat(instance.metadataOptions())
                .as("metadataOptions must not be null — Terraform reads HttpTokens/HttpEndpoint")
                .isNotNull();
        assertThat(instance.metadataOptions().httpTokens())
                .as("metadataOptions.httpTokens must not be null")
                .isNotNull();
        assertThat(instance.metadataOptions().httpEndpoint())
                .as("metadataOptions.httpEndpoint must not be null")
                .isNotNull();
        assertThat(instance.metadataOptions().httpPutResponseHopLimit())
                .as("metadataOptions.httpPutResponseHopLimit must not be null")
                .isNotNull();
    }

    @Test
    @Order(4)
    @DisplayName("maintenanceOptions is not null")
    void maintenanceOptions() {
        assertThat(instance.maintenanceOptions())
                .as("maintenanceOptions must not be null — Terraform reads AutoRecovery")
                .isNotNull();
        assertThat(instance.maintenanceOptions().autoRecovery())
                .as("maintenanceOptions.autoRecovery must not be null")
                .isNotNull();
    }

    @Test
    @Order(5)
    @DisplayName("enclaveOptions is not null")
    void enclaveOptions() {
        assertThat(instance.enclaveOptions())
                .as("enclaveOptions must not be null — Terraform reads Enabled flag")
                .isNotNull();
        assertThat(instance.enclaveOptions().enabled())
                .as("enclaveOptions.enabled must not be null")
                .isNotNull();
    }

    @Test
    @Order(6)
    @DisplayName("hibernationOptions is not null")
    void hibernationOptions() {
        assertThat(instance.hibernationOptions())
                .as("hibernationOptions must not be null — Terraform reads Configured flag")
                .isNotNull();
        assertThat(instance.hibernationOptions().configured())
                .as("hibernationOptions.configured must not be null")
                .isNotNull();
    }

    @Test
    @Order(7)
    @DisplayName("privateDnsNameOptions is not null with hostnameType")
    void privateDnsNameOptions() {
        assertThat(instance.privateDnsNameOptions())
                .as("privateDnsNameOptions must not be null — Terraform reads HostnameType")
                .isNotNull();
        assertThat(instance.privateDnsNameOptions().hostnameType())
                .as("privateDnsNameOptions.hostnameType must not be null")
                .isNotNull();
    }

    @Test
    @Order(8)
    @DisplayName("capacityReservationSpecification is not null")
    void capacityReservationSpecification() {
        assertThat(instance.capacityReservationSpecification())
                .as("capacityReservationSpecification must not be null — Terraform reads Preference")
                .isNotNull();
        assertThat(instance.capacityReservationSpecification().capacityReservationPreference())
                .as("capacityReservationSpecification.preference must not be null")
                .isNotNull();
    }

    @Test
    @Order(9)
    @DisplayName("stateReason is not null")
    void stateReason() {
        assertThat(instance.stateReason())
                .as("stateReason must not be null — Terraform reads Code and Message")
                .isNotNull();
    }

    @Test
    @Order(10)
    @DisplayName("blockDeviceMappings contains root device entry with all required EBS fields")
    void blockDeviceMappings() {
        assertThat(instance.blockDeviceMappings())
                .as("blockDeviceMappings must not be empty — Terraform reads root_block_device attributes")
                .isNotEmpty();
        InstanceBlockDeviceMapping rootDevice = instance.blockDeviceMappings().get(0);
        assertThat(rootDevice.deviceName())
                .as("blockDeviceMapping deviceName must not be null")
                .isNotNull();
        assertThat(rootDevice.ebs())
                .as("blockDeviceMapping ebs must not be null")
                .isNotNull();
        assertThat(rootDevice.ebs().volumeId())
                .as("blockDeviceMapping ebs.volumeId must not be null — Terraform calls DescribeVolumes(volumeId); " +
                    "empty/null causes InvalidVolume.NotFound for volume '' (issue #871)")
                .isNotNull()
                .startsWith("vol-");
        assertThat(rootDevice.ebs().status())
                .as("blockDeviceMapping ebs.status must not be null")
                .isNotNull()
                .isEqualTo(AttachmentStatus.ATTACHED);
        assertThat(rootDevice.ebs().deleteOnTermination())
                .as("blockDeviceMapping ebs.deleteOnTermination must not be null")
                .isNotNull();
        assertThat(rootDevice.ebs().attachTime())
                .as("blockDeviceMapping ebs.attachTime must not be null — present in all real AWS responses")
                .isNotNull();
    }

    @Test
    @Order(16)
    @DisplayName("root volume referenced in blockDeviceMappings is queryable via DescribeVolumes")
    void rootVolumeDescribable() {
        String volumeId = instance.blockDeviceMappings().get(0).ebs().volumeId();
        assertThat(volumeId).as("volumeId from blockDeviceMapping must not be null").isNotNull();

        DescribeVolumesResponse resp = ec2.describeVolumes(
                DescribeVolumesRequest.builder().volumeIds(volumeId).build());

        assertThat(resp.volumes()).as("DescribeVolumes must return the root volume").isNotEmpty();
        Volume vol = resp.volumes().get(0);
        assertThat(vol.volumeId()).isEqualTo(volumeId);
        assertThat(vol.stateAsString())
                .as("root volume state must be in-use while instance is running")
                .isEqualTo("in-use");
    }

    // ── Primary network interface (ENI) ──────────────────────────────────────

    @Test
    @Order(11)
    @DisplayName("networkInterfaces contains at least one ENI")
    void networkInterfacesNotEmpty() {
        assertThat(instance.networkInterfaces())
                .as("networkInterfaces must not be empty")
                .isNotEmpty();
    }

    @Test
    @Order(12)
    @DisplayName("primary ENI has non-null attachment — critical for Terraform DeviceIndex read")
    void eniAttachmentNotNull() {
        InstanceNetworkInterface primaryEni = instance.networkInterfaces().get(0);
        assertThat(primaryEni.attachment())
                .as("ENI attachment must not be null — Terraform reads Attachment.DeviceIndex " +
                    "to find the primary interface; nil causes panic in resourceInstanceRead")
                .isNotNull();
    }

    @Test
    @Order(13)
    @DisplayName("primary ENI attachment has deviceIndex == 0")
    void eniAttachmentDeviceIndex() {
        InstanceNetworkInterface primaryEni = instance.networkInterfaces().get(0);
        assertThat(primaryEni.attachment().deviceIndex())
                .as("Primary ENI must have deviceIndex 0")
                .isNotNull()
                .isEqualTo(0);
    }

    @Test
    @Order(14)
    @DisplayName("primary ENI attachment has attachmentId")
    void eniAttachmentId() {
        InstanceNetworkInterface primaryEni = instance.networkInterfaces().get(0);
        assertThat(primaryEni.attachment().attachmentId())
                .as("ENI attachmentId must not be null")
                .isNotNull()
                .startsWith("eni-attach-");
    }

    @Test
    @Order(15)
    @DisplayName("primary ENI has privateIpAddresses list")
    void eniPrivateIpAddresses() {
        InstanceNetworkInterface primaryEni = instance.networkInterfaces().get(0);
        assertThat(primaryEni.privateIpAddresses())
                .as("ENI privateIpAddresses must not be empty")
                .isNotEmpty();
        assertThat(primaryEni.privateIpAddresses().get(0).privateIpAddress())
                .as("ENI primary private IP must not be null")
                .isNotNull();
        assertThat(primaryEni.privateIpAddresses().get(0).primary())
                .as("ENI first privateIpAddress must have primary=true")
                .isNotNull()
                .isTrue();
    }

    // ── TerminateInstances state transitions ─────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("TerminateInstances returns correct state transition")
    void terminateInstances() {
        TerminateInstancesResponse resp = ec2.terminateInstances(
                TerminateInstancesRequest.builder().instanceIds(instanceId).build());

        assertThat(resp.terminatingInstances()).isNotEmpty();
        InstanceStateChange change = resp.terminatingInstances().get(0);
        assertThat(change.instanceId()).isEqualTo(instanceId);
        assertThat(change.currentState().name()).isEqualTo(InstanceStateName.SHUTTING_DOWN);
        instanceId = null;
    }
}
