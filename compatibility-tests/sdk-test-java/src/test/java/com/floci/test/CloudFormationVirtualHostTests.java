package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.ListStacksResponse;

import java.net.URI;

import static org.assertj.core.api.Assertions.*;

/**
 * Ensures that requests to non-S3 service hostnames (e.g. cloudformation.amazonaws.com)
 * are not incorrectly hijacked by the S3 virtual host filter.
 */
@DisplayName("CloudFormation Virtual Host")
class CloudFormationVirtualHostTests {

    private static CloudFormationClient cfn;

    @BeforeAll
    static void setup() {
        // We override the endpoint to simulate a virtual host style request for CloudFormation
        URI endpoint = TestFixtures.endpoint();
        URI cfnEndpoint = URI.create("http://cloudformation.us-east-1.amazonaws.com:" + endpoint.getPort());
        if (endpoint.getHost().equals("localhost")) {
            cfnEndpoint = URI.create("http://cloudformation.localhost:" + endpoint.getPort());
        }

        cfn = CloudFormationClient.builder()
                .endpointOverride(cfnEndpoint)
                .region(software.amazon.awssdk.regions.Region.US_EAST_1)
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                        software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("test", "test")))
                .build();
    }

    @AfterAll
    static void cleanup() {
        if (cfn != null) {
            cfn.close();
        }
    }

    @Test
    @DisplayName("ListStacks - virtual host request succeeds")
    void listStacksVirtualHost() {
        ListStacksResponse resp = cfn.listStacks();
        assertThat(resp.sdkHttpResponse().isSuccessful()).isTrue();
    }
}
