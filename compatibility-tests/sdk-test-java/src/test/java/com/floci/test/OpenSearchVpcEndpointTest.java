package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.opensearch.OpenSearchClient;
import software.amazon.awssdk.services.opensearch.model.ClusterConfig;
import software.amazon.awssdk.services.opensearch.model.CreateDomainResponse;
import software.amazon.awssdk.services.opensearch.model.DescribeVpcEndpointsResponse;
import software.amazon.awssdk.services.opensearch.model.DomainStatus;
import software.amazon.awssdk.services.opensearch.model.OpenSearchPartitionInstanceType;
import software.amazon.awssdk.services.opensearch.model.VPCOptions;
import software.amazon.awssdk.services.opensearch.model.VpcEndpoint;
import software.amazon.awssdk.services.opensearch.model.VpcEndpointErrorCode;
import software.amazon.awssdk.services.opensearch.model.VpcEndpointStatus;
import software.amazon.awssdk.services.opensearch.model.VpcEndpointSummary;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A domain in a VPC and its OpenSearch Service-managed VPC endpoints, through the AWS SDK for Java v2. */
@DisplayName("OpenSearch VPC endpoints")
class OpenSearchVpcEndpointTest {

    private static final String DOMAIN_NAME = "os-vpce-" + UUID.randomUUID().toString().substring(0, 8);

    private static OpenSearchClient opensearch;
    private static Ec2Client ec2;
    private static String vpcId;
    private static List<String> subnetIds;

    @BeforeAll
    static void setUp() {
        String endpoint = System.getenv("FLOCI_ENDPOINT");
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = "http://localhost:4566";
        }
        if (!endpoint.startsWith("http")) {
            endpoint = "http://" + endpoint;
        }
        StaticCredentialsProvider credentials =
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        opensearch = OpenSearchClient.builder().endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1).credentialsProvider(credentials).build();
        ec2 = Ec2Client.builder().endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1).credentialsProvider(credentials).build();

        vpcId = ec2.createVpc(r -> r.cidrBlock("10.42.0.0/16")).vpc().vpcId();
        subnetIds = List.of(
                ec2.createSubnet(r -> r.vpcId(vpcId).cidrBlock("10.42.1.0/24").availabilityZone("us-east-1a"))
                        .subnet().subnetId(),
                ec2.createSubnet(r -> r.vpcId(vpcId).cidrBlock("10.42.2.0/24").availabilityZone("us-east-1b"))
                        .subnet().subnetId());
    }

    @AfterAll
    static void cleanup() {
        try {
            opensearch.listVpcEndpointsForDomain(r -> r.domainName(DOMAIN_NAME)).vpcEndpointSummaryList()
                    .forEach(summary -> opensearch.deleteVpcEndpoint(r -> r.vpcEndpointId(summary.vpcEndpointId())));
            opensearch.deleteDomain(r -> r.domainName(DOMAIN_NAME));
        } catch (Exception ignored) {
            // Best effort: the test may have failed before creating them.
        }
        try {
            subnetIds.forEach(id -> ec2.deleteSubnet(r -> r.subnetId(id)));
            ec2.deleteVpc(r -> r.vpcId(vpcId));
        } catch (Exception ignored) {
            // Best effort.
        }
        opensearch.close();
        ec2.close();
    }

    @Test
    void aVpcDomainAndItsEndpointRoundTripThroughTheSdk() {
        CreateDomainResponse created = opensearch.createDomain(r -> r.domainName(DOMAIN_NAME)
                .engineVersion("OpenSearch_2.19")
                .clusterConfig(ClusterConfig.builder()
                        .instanceType(OpenSearchPartitionInstanceType.T3_SMALL_SEARCH).instanceCount(1).build())
                .vpcOptions(VPCOptions.builder().subnetIds(subnetIds).build()));
        String domainArn = created.domainStatus().arn();

        // A domain in a VPC is reached through Endpoints.vpc and carries no Endpoint.
        DomainStatus status = opensearch.describeDomain(r -> r.domainName(DOMAIN_NAME)).domainStatus();
        assertThat(status.endpoint()).isNull();

        VpcEndpoint createdEndpoint = opensearch.createVpcEndpoint(r -> r.domainArn(domainArn)
                .vpcOptions(VPCOptions.builder().subnetIds(subnetIds).securityGroupIds("sg-sdk").build()))
                .vpcEndpoint();
        String id = createdEndpoint.vpcEndpointId();
        assertThat(id).matches("^aos-[0-9a-f]{12}$");
        assertThat(createdEndpoint.status()).isEqualTo(VpcEndpointStatus.CREATING);
        assertThat(createdEndpoint.domainArn()).isEqualTo(domainArn);
        assertThat(createdEndpoint.vpcOptions().vpcId()).isEqualTo(vpcId);
        assertThat(createdEndpoint.vpcOptions().subnetIds()).containsExactlyElementsOf(subnetIds);
        assertThat(createdEndpoint.vpcOptions().availabilityZones()).containsExactly("us-east-1a", "us-east-1b");

        DescribeVpcEndpointsResponse described =
                opensearch.describeVpcEndpoints(r -> r.vpcEndpointIds(id, "aos-000000000000"));
        assertThat(described.vpcEndpoints()).singleElement()
                .satisfies(endpoint -> assertThat(endpoint.status()).isEqualTo(VpcEndpointStatus.ACTIVE));
        assertThat(described.vpcEndpointErrors()).singleElement().satisfies(error -> {
            assertThat(error.vpcEndpointId()).isEqualTo("aos-000000000000");
            assertThat(error.errorCode()).isEqualTo(VpcEndpointErrorCode.ENDPOINT_NOT_FOUND);
        });

        VpcEndpoint updated = opensearch.updateVpcEndpoint(r -> r.vpcEndpointId(id)
                .vpcOptions(VPCOptions.builder().subnetIds(subnetIds.get(0)).securityGroupIds("sg-other").build()))
                .vpcEndpoint();
        assertThat(updated.status()).isEqualTo(VpcEndpointStatus.UPDATING);
        assertThat(updated.vpcOptions().securityGroupIds()).containsExactly("sg-other");

        List<VpcEndpointSummary> forDomain =
                opensearch.listVpcEndpointsForDomain(r -> r.domainName(DOMAIN_NAME)).vpcEndpointSummaryList();
        assertThat(forDomain).extracting(VpcEndpointSummary::vpcEndpointId).containsExactly(id);
        assertThat(opensearch.listVpcEndpoints(r -> { }).vpcEndpointSummaryList())
                .extracting(VpcEndpointSummary::vpcEndpointId).contains(id);

        VpcEndpointSummary deleted = opensearch.deleteVpcEndpoint(r -> r.vpcEndpointId(id)).vpcEndpointSummary();
        assertThat(deleted.status()).isEqualTo(VpcEndpointStatus.DELETING);
        assertThat(opensearch.describeVpcEndpoints(r -> r.vpcEndpointIds(id)).vpcEndpoints()).isEmpty();
    }
}
