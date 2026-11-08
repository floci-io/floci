package io.floci.aws.services.ec2;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.AttachVolumeResponse;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Volume;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@QuarkusTest
class Ec2VolumeAttachmentFailureIntegrationTest {

    private static final AtomicLong ACCOUNTS = new AtomicLong(881100000000L);

    @InjectMock
    Ec2VolumeBlockDeviceManager blockDevices;

    @Test
    void failedAttachmentReturnsAnSdkErrorLeavesVolumeAvailableAndCanBeRetried() {
        when(blockDevices.attachVolume(any(), any(), anyString()))
                .thenThrow(new AwsException("InternalError", "Could not attach volume", 500));

        try (Ec2Client client = client()) {
            Instance instance = launch(client);
            Volume volume = createVolume(client, instance);
            try {
                Ec2Exception error = assertThrows(Ec2Exception.class, () -> client.attachVolume(r -> r
                        .volumeId(volume.volumeId()).instanceId(instance.instanceId()).device("/dev/xvdf")));
                assertEquals(500, error.statusCode());
                assertEquals("InternalError", error.awsErrorDetails().errorCode());
                Volume afterFailure = describeVolume(client, volume.volumeId());
                assertEquals("available", afterFailure.stateAsString());
                assertTrue(afterFailure.attachments().isEmpty());

                doReturn(Optional.of("/dev/xvdf")).when(blockDevices).attachVolume(any(), any(), anyString());
                client.attachVolume(r -> r.volumeId(volume.volumeId()).instanceId(instance.instanceId())
                        .device("/dev/xvdf"));
                assertEquals("in-use", describeVolume(client, volume.volumeId()).stateAsString());
            } finally {
                client.terminateInstances(r -> r.instanceIds(instance.instanceId()));
                client.deleteVolume(r -> r.volumeId(volume.volumeId()));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void physicalSuccessAndIntentionalSimulationKeepTheExistingSdkContract(boolean physical) {
        when(blockDevices.attachVolume(any(), any(), anyString()))
                .thenReturn(physical ? Optional.of("/dev/xvdf") : Optional.empty());

        try (Ec2Client client = client()) {
            Instance instance = launch(client);
            Volume volume = createVolume(client, instance);
            try {
                AttachVolumeResponse response = client.attachVolume(r -> r.volumeId(volume.volumeId())
                        .instanceId(instance.instanceId()).device("/dev/xvdf"));
                assertEquals("attaching", response.stateAsString());
                Volume attached = describeVolume(client, volume.volumeId());
                assertEquals("in-use", attached.stateAsString());
                assertEquals(1, attached.attachments().size());
                assertEquals("attached", attached.attachments().getFirst().stateAsString());
                assertEquals("/dev/xvdf", attached.attachments().getFirst().device());
            } finally {
                client.terminateInstances(r -> r.instanceIds(instance.instanceId()));
                client.deleteVolume(r -> r.volumeId(volume.volumeId()));
            }
        }
    }

    private Ec2Client client() {
        return AwsTestClients.create(Ec2Client.builder().overrideConfiguration(c -> c
                .retryStrategy(s -> s.maxAttempts(1))), Long.toString(ACCOUNTS.incrementAndGet()), Region.US_EAST_1);
    }

    private Instance launch(Ec2Client client) {
        Instance instance = client.runInstances(r -> r.imageId("ami-1234567890abcdef0").instanceType("t3.micro")
                .minCount(1).maxCount(1)).instances().getFirst();
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                assertEquals("running", client.describeInstances(r -> r.instanceIds(instance.instanceId()))
                        .reservations().getFirst().instances().getFirst().state().nameAsString()));
        return instance;
    }

    private Volume createVolume(Ec2Client client, Instance instance) {
        String volumeId = client.createVolume(r -> r.availabilityZone(instance.placement().availabilityZone())
                .size(1)).volumeId();
        return describeVolume(client, volumeId);
    }

    private Volume describeVolume(Ec2Client client, String volumeId) {
        return client.describeVolumes(r -> r.volumeIds(volumeId)).volumes().getFirst();
    }
}
