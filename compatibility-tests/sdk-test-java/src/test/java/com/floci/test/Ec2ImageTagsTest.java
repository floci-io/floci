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
}
