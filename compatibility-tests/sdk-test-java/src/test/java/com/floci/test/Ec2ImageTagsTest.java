package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Tag;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EC2 CopyImage tags")
class Ec2ImageTagsTest {

    @Test
    @DisplayName("CopyImage copies the source image's tags only when CopyImageTags is true")
    void copyImageCopiesTagsOnlyWhenAsked() {
        String region = TestFixtures.region().id();
        Tag team = Tag.builder().key("Team").value("images").build();
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String sourceId = ec2.registerImage(r -> r.name(TestFixtures.uniqueName("image-tags-copy-source")))
                    .imageId();
            try {
                ec2.createTags(r -> r.resources(sourceId).tags(team));
                String untaggedId = ec2.copyImage(r -> r.sourceImageId(sourceId).sourceRegion(region)
                        .name(TestFixtures.uniqueName("image-tags-copy-default"))).imageId();
                try {
                    String taggedId = ec2.copyImage(r -> r.sourceImageId(sourceId).sourceRegion(region)
                            .name(TestFixtures.uniqueName("image-tags-copy-tags")).copyImageTags(true)).imageId();
                    try {
                        assertThat(ec2.describeImages(r -> r.imageIds(untaggedId)).images().get(0).tags())
                                .isEmpty();
                        assertThat(ec2.describeImages(r -> r.imageIds(taggedId)).images().get(0).tags())
                                .containsExactly(team);
                        assertThat(ec2.describeImages(r -> r.imageIds(sourceId)).images().get(0).tags())
                                .containsExactly(team);
                    } finally {
                        // Each copy gets its own root snapshot, which deregistering alone would leave behind.
                        ec2.deregisterImage(r -> r.imageId(taggedId).deleteAssociatedSnapshots(true));
                    }
                } finally {
                    ec2.deregisterImage(r -> r.imageId(untaggedId).deleteAssociatedSnapshots(true));
                }
            } finally {
                ec2.deregisterImage(r -> r.imageId(sourceId));
            }
        }
    }
}
