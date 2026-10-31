package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.ListStacksResponse;

import static org.assertj.core.api.Assertions.*;

/**
 * Ensures that requests to non-S3 service hostnames (e.g. cloudformation.amazonaws.com)
 * are not incorrectly hijacked by the S3 virtual host filter.
 *
 * <p>The TCP connection lands on the configured Floci endpoint; an execution
 * interceptor overrides the Host header so Floci sees the request as though it
 * came from {@code cloudformation.us-east-1.amazonaws.com}. This decouples the
 * test from DNS setup so it works equally against localhost, a docker service
 * name (e.g. {@code http://floci:4566}), or a remote Floci deployment.
 */
@DisplayName("CloudFormation Virtual Host")
class CloudFormationVirtualHostTests {

    private static final String VIRTUAL_HOST = "cloudformation.us-east-1.amazonaws.com";

    private static CloudFormationClient cfn;

    @BeforeAll
    static void setup() {
        cfn = CloudFormationClient.builder()
                .endpointOverride(TestFixtures.endpoint())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .overrideConfiguration(c -> c.addExecutionInterceptor(new HostHeaderSpoofInterceptor(VIRTUAL_HOST)))
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

    /**
     * Rewrites the Host header on outbound requests so Floci sees the incoming
     * request as virtual-hosted under {@code hostHeader}, while the underlying
     * TCP connection still goes to the endpoint configured via
     * {@code endpointOverride}.
     */
    private static final class HostHeaderSpoofInterceptor implements ExecutionInterceptor {
        private final String hostHeader;

        HostHeaderSpoofInterceptor(String hostHeader) {
            this.hostHeader = hostHeader;
        }

        @Override
        public SdkHttpRequest modifyHttpRequest(Context.ModifyHttpRequest context,
                                                ExecutionAttributes executionAttributes) {
            return context.httpRequest().toBuilder()
                    .putHeader("Host", hostHeader)
                    .build();
        }
    }
}
