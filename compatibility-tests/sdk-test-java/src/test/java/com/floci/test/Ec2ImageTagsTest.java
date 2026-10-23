package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Image;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EC2 image tags")
class Ec2ImageTagsTest {

    @Test
    @DisplayName("RegisterImage tags the image with its image tag specification")
    void registerImageTagsTheImage() {
        String name = TestFixtures.uniqueName("image-tags-register");
        List<Tag> tags = List.of(Tag.builder().key("Build").value(name).build(),
                Tag.builder().key("Team").value("images").build());
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String imageId = ec2.registerImage(r -> r.name(name).tagSpecifications(TagSpecification.builder()
                    .resourceType(ResourceType.IMAGE).tags(tags).build())).imageId();
            try {
                assertThat(ec2.describeImages(r -> r.imageIds(imageId)).images().get(0).tags())
                        .containsExactlyInAnyOrderElementsOf(tags);
                assertThat(ec2.describeImages(r -> r.filters(Filter.builder()
                                .name("tag:Build").values(name).build())).images())
                        .extracting(Image::imageId).containsExactly(imageId);
            } finally {
                ec2.deregisterImage(r -> r.imageId(imageId));
            }
        }
    }

    @Test
    @DisplayName("RegisterImage refuses a tag specification for another resource type and registers nothing")
    void registerImageRefusesAnotherResourceType() {
        String name = TestFixtures.uniqueName("image-tags-wrong-type");
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            assertThatThrownBy(() -> ec2.registerImage(r -> r.name(name).tagSpecifications(TagSpecification.builder()
                    .resourceType(ResourceType.SNAPSHOT).tags(Tag.builder().key("Build").value(name).build())
                    .build())))
                    .isInstanceOfSatisfying(Ec2Exception.class, e ->
                            assertThat(e.awsErrorDetails().errorCode()).isEqualTo("InvalidParameterValue"));
            assertThat(ec2.describeImages(r -> r.owners("self").filters(Filter.builder()
                    .name("name").values(name).build())).images()).isEmpty();
        }
    }

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
