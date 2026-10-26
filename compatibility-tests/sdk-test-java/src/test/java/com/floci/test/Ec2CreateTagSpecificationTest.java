package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateKeyPairResponse;
import software.amazon.awssdk.services.ec2.model.Image;
import software.amazon.awssdk.services.ec2.model.ImportKeyPairResponse;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.TagSpecification;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EC2 TagSpecification on CreateKeyPair, ImportKeyPair, CreateNetworkAcl and the image actions")
class Ec2CreateTagSpecificationTest {

    /** A throwaway ed25519 public key. */
    private static final String ED25519_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAII0fBPBUZHaEOBc2mySfmI5btu4mkvFfNRujmF7RH2fj";

    @Test
    @DisplayName("CreateKeyPair applies its tags, returns them in the response and describes them")
    void createKeyPairAppliesAndDescribesItsTags() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            CreateKeyPairResponse created = ec2.createKeyPair(r -> r.keyName(TestFixtures.uniqueName("tagged-rsa"))
                    .tagSpecifications(tagSpecification(ResourceType.KEY_PAIR, "Team", "keys")));
            try {
                assertThat(created.tags()).hasSize(1);
                assertThat(created.tags().get(0).key()).isEqualTo("Team");
                assertThat(created.tags().get(0).value()).isEqualTo("keys");

                assertThat(ec2.describeKeyPairs(r -> r.keyPairIds(created.keyPairId())).keyPairs().get(0)
                        .tags()).extracting(tag -> tag.key() + "=" + tag.value())
                        .containsExactly("Team=keys");
            } finally {
                ec2.deleteKeyPair(r -> r.keyPairId(created.keyPairId()));
            }
        }
    }

    @Test
    @DisplayName("ImportKeyPair applies its tags, returns them in the response and describes them")
    void importKeyPairAppliesAndDescribesItsTags() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            ImportKeyPairResponse imported = ec2.importKeyPair(r -> r.keyName(TestFixtures.uniqueName("tagged-ed25519"))
                    .publicKeyMaterial(SdkBytes.fromUtf8String(ED25519_KEY))
                    .tagSpecifications(tagSpecification(ResourceType.KEY_PAIR, "Team", "keys")));
            try {
                assertThat(imported.tags()).hasSize(1);
                assertThat(imported.tags().get(0).key()).isEqualTo("Team");
                assertThat(imported.tags().get(0).value()).isEqualTo("keys");

                assertThat(ec2.describeKeyPairs(r -> r.keyPairIds(imported.keyPairId())).keyPairs().get(0)
                        .tags()).extracting(tag -> tag.key() + "=" + tag.value())
                        .containsExactly("Team=keys");
            } finally {
                ec2.deleteKeyPair(r -> r.keyPairId(imported.keyPairId()));
            }
        }
    }

    @Test
    @DisplayName("CreateNetworkAcl applies its tags and describes them")
    void createNetworkAclAppliesAndDescribesItsTags() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String vpcId = ec2.createVpc(r -> r.cidrBlock("10.99.0.0/16")).vpc().vpcId();
            try {
                String aclId = ec2.createNetworkAcl(r -> r.vpcId(vpcId)
                        .tagSpecifications(tagSpecification(ResourceType.NETWORK_ACL, "Team", "net")))
                        .networkAcl().networkAclId();
                try {
                    assertThat(ec2.describeNetworkAcls(r -> r.networkAclIds(aclId)).networkAcls().get(0)
                            .tags()).extracting(tag -> tag.key() + "=" + tag.value())
                            .containsExactly("Team=net");
                } finally {
                    ec2.deleteNetworkAcl(r -> r.networkAclId(aclId));
                }
            } finally {
                ec2.deleteVpc(r -> r.vpcId(vpcId));
            }
        }
    }

    @Test
    @DisplayName("RegisterImage tags the AMI and CopyImage tags the copy and its new snapshots")
    void imageActionsTagTheAmiAndTheNewSnapshots() {
        String region = TestFixtures.region().id();
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String sourceId = ec2.registerImage(r -> r.name(TestFixtures.uniqueName("tagged-source"))
                    .tagSpecifications(tagSpecification(ResourceType.IMAGE, "Team", "images"))).imageId();
            try {
                assertThat(ec2.describeImages(r -> r.imageIds(sourceId)).images().get(0).tags())
                        .extracting(tag -> tag.key() + "=" + tag.value())
                        .containsExactly("Team=images");

                String copyId = ec2.copyImage(r -> r.sourceImageId(sourceId).sourceRegion(region)
                        .name(TestFixtures.uniqueName("tagged-copy"))
                        .tagSpecifications(tagSpecification(ResourceType.IMAGE, "Team", "images"),
                                tagSpecification(ResourceType.SNAPSHOT, "Team", "snapshots"))).imageId();
                try {
                    Image copy = ec2.describeImages(r -> r.imageIds(copyId)).images().get(0);
                    assertThat(copy.tags()).extracting(tag -> tag.key() + "=" + tag.value())
                            .containsExactly("Team=images");
                    List<String> snapshotIds = copy.blockDeviceMappings().stream()
                            .filter(mapping -> mapping.ebs() != null && mapping.ebs().snapshotId() != null)
                            .map(mapping -> mapping.ebs().snapshotId()).toList();
                    assertThat(snapshotIds).isNotEmpty();
                    for (String snapshotId : snapshotIds) {
                        assertThat(ec2.describeSnapshots(r -> r.snapshotIds(snapshotId)).snapshots().get(0)
                                .tags()).extracting(tag -> tag.key() + "=" + tag.value())
                                .containsExactly("Team=snapshots");
                    }
                } finally {
                    ec2.deregisterImage(r -> r.imageId(copyId).deleteAssociatedSnapshots(true));
                }
            } finally {
                ec2.deregisterImage(r -> r.imageId(sourceId));
            }
        }
    }

    private static TagSpecification tagSpecification(ResourceType resourceType, String key, String value) {
        return TagSpecification.builder().resourceType(resourceType)
                .tags(t -> t.key(key).value(value)).build();
    }
}
