package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.opensearch.OpenSearchClient;
import software.amazon.awssdk.services.opensearch.model.*;
import software.amazon.awssdk.services.opensearch.model.Tag;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("OpenSearch Service")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OpenSearchTest {

    private static OpenSearchClient opensearch;
    private static final String DOMAIN_NAME = "os-domain-" + UUID.randomUUID().toString().substring(0, 8);
    private static String domainEndpoint;

    @BeforeAll
    static void setUp() {
        String endpoint = System.getenv("FLOCI_ENDPOINT");
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = "http://localhost:4566";
        }
        if (!endpoint.startsWith("http")) {
            endpoint = "http://" + endpoint;
        }

        opensearch = OpenSearchClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .build();
    }

    @AfterAll
    static void cleanup() {
        if (opensearch != null) {
            try {
                opensearch.deleteDomain(DeleteDomainRequest.builder()
                        .domainName(DOMAIN_NAME)
                        .build());
            } catch (Exception ignored) {}
            opensearch.close();
        }
    }

    @Test
    @Order(1)
    void createDomain() {
        CreateDomainResponse response = opensearch.createDomain(CreateDomainRequest.builder()
                .domainName(DOMAIN_NAME)
                .engineVersion("OpenSearch_2.11")
                .clusterConfig(ClusterConfig.builder()
                        .instanceType(OpenSearchPartitionInstanceType.T3_SMALL_SEARCH)
                        .instanceCount(1)
                        .build())
                .tagList(Tag.builder().key("env").value("test").build())
                .build());

        assertThat(response.domainStatus()).isNotNull();
        assertThat(response.domainStatus().domainName()).isEqualTo(DOMAIN_NAME);
        assertThat(response.domainStatus().arn()).isNotBlank();
    }

    @Test
    @Order(2)
    void listDomainNames() {
        ListDomainNamesResponse response = opensearch.listDomainNames(ListDomainNamesRequest.builder().build());
        assertThat(response.domainNames()).anyMatch(d -> d.domainName().equals(DOMAIN_NAME));
    }

    @Test
    @Order(3)
    void describeDomain() {
        DescribeDomainResponse response = opensearch.describeDomain(DescribeDomainRequest.builder()
                .domainName(DOMAIN_NAME)
                .build());

        assertThat(response.domainStatus().domainName()).isEqualTo(DOMAIN_NAME);
        domainEndpoint = response.domainStatus().endpoint();
    }

    @Test
    @Order(4)
    void addTags() {
        DescribeDomainResponse describe = opensearch.describeDomain(DescribeDomainRequest.builder()
                .domainName(DOMAIN_NAME)
                .build());

        opensearch.addTags(AddTagsRequest.builder()
                .arn(describe.domainStatus().arn())
                .tagList(Tag.builder().key("new-tag").value("new-value").build())
                .build());

        ListTagsResponse response = opensearch.listTags(ListTagsRequest.builder()
                .arn(describe.domainStatus().arn())
                .build());

        assertThat(response.tagList()).anyMatch(t -> t.key().equals("new-tag"));
    }

    @Test
    @Order(5)
    void removeTags() {
        DescribeDomainResponse describe = opensearch.describeDomain(DescribeDomainRequest.builder()
                .domainName(DOMAIN_NAME)
                .build());

        opensearch.removeTags(RemoveTagsRequest.builder()
                .arn(describe.domainStatus().arn())
                .tagKeys("new-tag")
                .build());

        ListTagsResponse response = opensearch.listTags(ListTagsRequest.builder()
                .arn(describe.domainStatus().arn())
                .build());

        assertThat(response.tagList()).noneMatch(t -> t.key().equals("new-tag"));
    }

    @Test
    @Order(6)
    void createDuplicateDomainFails() {
        assertThatThrownBy(() -> opensearch.createDomain(CreateDomainRequest.builder()
                        .domainName(DOMAIN_NAME)
                        .build()))
                .isInstanceOf(ResourceAlreadyExistsException.class);
    }

    @Test
    @Order(7)
    void domainEndpointReachable() {
        if (domainEndpoint == null || domainEndpoint.isBlank()) {
            // Mock mode: endpoint is empty — skip HTTP probe
            return;
        }

        // Wait for domain to be ready (processing = false)
        long start = System.currentTimeMillis();
        boolean ready = false;
        while (System.currentTimeMillis() - start < 180000) { // 3 minutes timeout
            DescribeDomainResponse response = opensearch.describeDomain(DescribeDomainRequest.builder()
                    .domainName(DOMAIN_NAME)
                    .build());
            if (!response.domainStatus().processing()) {
                ready = true;
                break;
            }
            try { Thread.sleep(5000); } catch (InterruptedException e) {}
        }

        assertThat(ready).as("Domain did not become ready in time").isTrue();

        try {
            HttpURLConnection conn = (HttpURLConnection)
                    URI.create(domainEndpoint + "/_cluster/health").toURL().openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            assertThat(code).isEqualTo(200);
        } catch (Exception e) {
            fail("OpenSearch endpoint " + domainEndpoint + " not reachable: " + e.getMessage());
        }
    }

    @Test
    @Order(8)
    void deleteDomain() {
        DeleteDomainResponse response = opensearch.deleteDomain(DeleteDomainRequest.builder()
                .domainName(DOMAIN_NAME)
                .build());

        assertThat(response.domainStatus()).isNotNull();
        assertThat(response.domainStatus().domainName()).isEqualTo(DOMAIN_NAME);
    }

    @Test
    @Order(9)
    void describeDomainAfterDeleteFails() {
        assertThatThrownBy(() -> opensearch.describeDomain(DescribeDomainRequest.builder()
                        .domainName(DOMAIN_NAME)
                        .build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
