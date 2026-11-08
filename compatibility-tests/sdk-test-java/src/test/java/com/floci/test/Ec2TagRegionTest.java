package com.floci.test;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.RegionMetadata;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateTagsRequest;
import software.amazon.awssdk.services.ec2.model.CreateVpcRequest;
import software.amazon.awssdk.services.ec2.model.DeleteVpcRequest;
import software.amazon.awssdk.services.ec2.model.DescribeTagsRequest;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagDescription;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EC2 tags are regional")
class Ec2TagRegionTest {

    @Test
    @DisplayName("DescribeTags lists only the tags of resources in the client's region")
    void describeTagsListsOnlyTheClientRegionsTags() {
        Optional<Region> second = otherRegionInThePartition();
        Assumptions.assumeTrue(second.isPresent(), () -> "no second region in partition " + TestFixtures.partition());
        Region other = second.get();
        try (Ec2Client home = TestFixtures.ec2Client(); Ec2Client away = clientFor(other)) {
            String homeVpc = home.createVpc(CreateVpcRequest.builder().cidrBlock("10.92.0.0/16").build()).vpc().vpcId();
            String awayVpc = away.createVpc(CreateVpcRequest.builder().cidrBlock("10.93.0.0/16").build()).vpc().vpcId();
            try {
                home.createTags(CreateTagsRequest.builder().resources(homeVpc)
                        .tags(Tag.builder().key("SdkRegionProbe").value("home").build()).build());
                away.createTags(CreateTagsRequest.builder().resources(awayVpc)
                        .tags(Tag.builder().key("SdkRegionProbe").value("away").build()).build());

                assertThat(probedIds(home)).containsExactly(homeVpc);
                assertThat(probedIds(away)).containsExactly(awayVpc);
            } finally {
                home.deleteVpc(DeleteVpcRequest.builder().vpcId(homeVpc).build());
                away.deleteVpc(DeleteVpcRequest.builder().vpcId(awayVpc).build());
            }
        }
    }

    private static List<String> probedIds(Ec2Client ec2) {
        return ec2.describeTags(DescribeTagsRequest.builder()
                        .filters(Filter.builder().name("key").values("SdkRegionProbe").build()).build())
                .tags().stream().map(TagDescription::resourceId).toList();
    }

    private static Optional<Region> otherRegionInThePartition() {
        Region home = TestFixtures.region();
        String partition = TestFixtures.partition();
        return Region.regions().stream()
                .filter(region -> !region.isGlobalRegion() && !region.equals(home))
                .filter(region -> {
                    RegionMetadata metadata = region.metadata();
                    return metadata != null && partition.equals(metadata.partition().id());
                })
                .sorted((a, b) -> a.id().compareTo(b.id()))
                .findFirst();
    }

    private static Ec2Client clientFor(Region region) {
        return Ec2Client.builder()
                .endpointOverride(TestFixtures.endpoint())
                .region(region)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }
}
