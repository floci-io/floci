package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2instanceconnect.Ec2InstanceConnectClient;
import software.amazon.awssdk.services.ec2instanceconnect.model.Ec2InstanceNotFoundException;
import software.amazon.awssdk.services.ec2instanceconnect.model.InvalidArgsException;
import software.amazon.awssdk.services.ec2instanceconnect.model.SendSshPublicKeyResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EC2 Instance Connect")
class Ec2InstanceConnectTest {

    private static final String ED25519 =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl user@host";

    @Test
    @DisplayName("SendSSHPublicKey succeeds for a running instance and rejects a key SSH cannot use")
    void sendSshPublicKey() {
        try (Ec2Client ec2 = TestFixtures.ec2Client();
             Ec2InstanceConnectClient connect = TestFixtures.ec2InstanceConnectClient()) {
            String instanceId = ec2.runInstances(r -> r.imageId("ami-0abcdef1234567890")
                    .instanceType(InstanceType.T3_MICRO).minCount(1).maxCount(1)).instances().get(0).instanceId();
            try {
                ec2.waiter().waitUntilInstanceRunning(r -> r.instanceIds(instanceId));
                SendSshPublicKeyResponse response = connect.sendSSHPublicKey(r -> r.instanceId(instanceId)
                        .instanceOSUser("ec2-user").sshPublicKey(ED25519));
                assertThat(response.success()).isTrue();
                assertThat(response.requestId()).isNotBlank();

                assertThatThrownBy(() -> connect.sendSSHPublicKey(r -> r.instanceId(instanceId)
                        .instanceOSUser("ec2-user").sshPublicKey("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5 " + "c".repeat(80))))
                        .isInstanceOf(InvalidArgsException.class);
            } finally {
                ec2.terminateInstances(r -> r.instanceIds(instanceId));
            }
        }
    }

    @Test
    @DisplayName("SendSSHPublicKey on an unknown instance raises EC2InstanceNotFoundException")
    void unknownInstance() {
        try (Ec2InstanceConnectClient connect = TestFixtures.ec2InstanceConnectClient()) {
            assertThatThrownBy(() -> connect.sendSSHPublicKey(r -> r.instanceId("i-0000000000000000f")
                    .instanceOSUser("ec2-user").sshPublicKey(ED25519)))
                    .isInstanceOf(Ec2InstanceNotFoundException.class);
        }
    }
}
