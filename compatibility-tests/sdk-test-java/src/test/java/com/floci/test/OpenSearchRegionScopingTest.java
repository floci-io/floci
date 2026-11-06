package com.floci.test;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.RegionMetadata;
import software.amazon.awssdk.services.opensearch.OpenSearchClient;
import software.amazon.awssdk.services.opensearch.model.ClusterConfig;
import software.amazon.awssdk.services.opensearch.model.CreateDomainRequest;
import software.amazon.awssdk.services.opensearch.model.DeleteDomainRequest;
import software.amazon.awssdk.services.opensearch.model.DescribeDomainRequest;
import software.amazon.awssdk.services.opensearch.model.ListDomainNamesRequest;
import software.amazon.awssdk.services.opensearch.model.ResourceAlreadyExistsException;
import software.amazon.awssdk.services.opensearch.model.ResourceNotFoundException;
import software.amazon.awssdk.services.opensearch.model.UpdateDomainConfigRequest;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OpenSearch domains are regional")
class OpenSearchRegionScopingTest {

    private static final String SHARED_NAME = "os-region-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String HOME_ONLY_NAME = "os-home-" + UUID.randomUUID().toString().substring(0, 8);

    @Test
    @DisplayName("A domain is invisible outside its region and the same name coexists in two regions")
    void domainIsScopedToItsRegion() {
        Optional<Region> second = otherRegionInThePartition();
        Assumptions.assumeTrue(second.isPresent(),
                () -> "no second region in partition " + TestFixtures.partition());
        Region other = second.get();
        try (OpenSearchClient home = TestFixtures.openSearchClient(); OpenSearchClient away = clientFor(other)) {
            try {
                String homeRegion = TestFixtures.region().id();
                String awayRegion = other.id();

                String sharedArn = home.createDomain(CreateDomainRequest.builder()
                                .domainName(SHARED_NAME).engineVersion("OpenSearch_2.11").build())
                        .domainStatus().arn();
                assertThat(sharedArn).contains(":es:" + homeRegion + ":");

                assertThatThrownBy(() -> away.describeDomain(DescribeDomainRequest.builder()
                                .domainName(SHARED_NAME).build()))
                        .isInstanceOf(ResourceNotFoundException.class);

                String awayArn = away.createDomain(CreateDomainRequest.builder()
                                .domainName(SHARED_NAME).engineVersion("OpenSearch_2.11").build())
                        .domainStatus().arn();
                assertThat(awayArn).contains(":es:" + awayRegion + ":");

                home.createDomain(CreateDomainRequest.builder().domainName(HOME_ONLY_NAME).build());

                assertThat(away.listDomainNames(ListDomainNamesRequest.builder().build()).domainNames())
                        .extracting(d -> d.domainName())
                        .doesNotContain(HOME_ONLY_NAME);
                assertThat(home.listDomainNames(ListDomainNamesRequest.builder().build()).domainNames())
                        .extracting(d -> d.domainName())
                        .contains(HOME_ONLY_NAME);

                away.updateDomainConfig(UpdateDomainConfigRequest.builder()
                        .domainName(SHARED_NAME)
                        .clusterConfig(ClusterConfig.builder().instanceCount(3).build())
                        .build());
                assertThat(home.describeDomain(DescribeDomainRequest.builder().domainName(SHARED_NAME).build())
                        .domainStatus().clusterConfig().instanceCount()).isEqualTo(1);
                assertThat(away.describeDomain(DescribeDomainRequest.builder().domainName(SHARED_NAME).build())
                        .domainStatus().clusterConfig().instanceCount()).isEqualTo(3);

                away.deleteDomain(DeleteDomainRequest.builder().domainName(SHARED_NAME).build());
                assertThatThrownBy(() -> away.describeDomain(DescribeDomainRequest.builder()
                                .domainName(SHARED_NAME).build()))
                        .isInstanceOf(ResourceNotFoundException.class);
                assertThat(home.describeDomain(DescribeDomainRequest.builder().domainName(SHARED_NAME).build())
                        .domainStatus().arn()).isEqualTo(sharedArn);
            } finally {
                deleteQuietly(home, SHARED_NAME);
                deleteQuietly(home, HOME_ONLY_NAME);
                deleteQuietly(away, SHARED_NAME);
            }
        }
    }

    @Test
    @DisplayName("The same name in the same region still conflicts")
    void sameNameInTheSameRegionStillConflicts() {
        try (OpenSearchClient home = TestFixtures.openSearchClient()) {
            home.createDomain(CreateDomainRequest.builder().domainName(HOME_ONLY_NAME).build());

            assertThatThrownBy(() -> home.createDomain(CreateDomainRequest.builder()
                            .domainName(HOME_ONLY_NAME).build()))
                    .isInstanceOf(ResourceAlreadyExistsException.class);
        } finally {
            deleteQuietly(TestFixtures.openSearchClient(), HOME_ONLY_NAME);
        }
    }

    private static void deleteQuietly(OpenSearchClient client, String name) {
        try {
            client.deleteDomain(DeleteDomainRequest.builder().domainName(name).build());
        } catch (Exception ignored) {
            // Cleanup: a never-created domain and an already-deleted one both land here.
        }
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

    private static OpenSearchClient clientFor(Region region) {
        return OpenSearchClient.builder()
                .endpointOverride(TestFixtures.endpoint())
                .region(region)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }
}
