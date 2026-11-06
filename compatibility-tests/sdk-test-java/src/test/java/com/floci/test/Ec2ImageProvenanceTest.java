package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Image;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EC2 image creation provenance")
class Ec2ImageProvenanceTest {

    @Test
    @DisplayName("A copied image reports its source image and region, and the source filters find it")
    void aCopiedImageReportsItsSourceAndTheSourceFiltersFindIt() {
        String region = TestFixtures.region().id();
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String sourceId = ec2.registerImage(r -> r.name(TestFixtures.uniqueName("provenance-source")))
                    .imageId();
            try {
                String copyId = ec2.copyImage(r -> r.sourceImageId(sourceId).sourceRegion(region)
                        .name(TestFixtures.uniqueName("provenance-copy"))).imageId();
                try {
                    Image copy = ec2.describeImages(r -> r.imageIds(copyId)).images().get(0);

                    assertThat(copy.sourceImageId()).isEqualTo(sourceId);
                    assertThat(copy.sourceImageRegion()).isEqualTo(region);
                    assertThat(copy.sourceInstanceId()).isNull();
                    assertThat(ec2.describeImages(r -> r.filters(Filter.builder()
                                    .name("source-image-id").values(sourceId).build())).images())
                            .extracting(Image::imageId).containsExactly(copyId);
                    assertThat(ec2.describeImages(r -> r.imageIds(sourceId)).images().get(0).sourceImageId())
                            .isNull();
                } finally {
                    // The copy gets its own root snapshot, which deregistering alone would leave behind.
                    ec2.deregisterImage(r -> r.imageId(copyId).deleteAssociatedSnapshots(true));
                }
            } finally {
                ec2.deregisterImage(r -> r.imageId(sourceId));
            }
        }
    }
}
