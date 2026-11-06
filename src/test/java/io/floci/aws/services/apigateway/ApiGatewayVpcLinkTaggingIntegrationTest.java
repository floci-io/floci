package io.floci.aws.services.apigateway;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.BadRequestException;
import software.amazon.awssdk.services.apigateway.model.CreateVpcLinkResponse;
import software.amazon.awssdk.services.apigateway.model.NotFoundException;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class ApiGatewayVpcLinkTaggingIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;

    private ApiGatewayClient client() {
        return ApiGatewayClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    @Test
    void vpcLinkTagsRoundTripThroughTheSdkAndVpcLinkRead() {
        try (ApiGatewayClient client = client()) {
            CreateVpcLinkResponse link = client.createVpcLink(r -> r.name("tagged-link")
                    .targetArns("arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/net/test/123")
                    .tags(Map.of("owner", "network", "keep", "yes")));
            String arn = "arn:aws:apigateway:us-east-1::/vpclinks/" + link.id();
            try {
                assertEquals(link.tags(), client.getTags(r -> r.resourceArn(arn)).tags());
                client.tagResource(r -> r.resourceArn(arn).tags(Map.of("owner", "platform", "env", "test")));
                Map<String, String> updated = Map.of("owner", "platform", "keep", "yes", "env", "test");
                assertEquals(updated, client.getTags(r -> r.resourceArn(arn)).tags());
                assertEquals(updated, client.getVpcLink(r -> r.vpcLinkId(link.id())).tags());
                client.untagResource(r -> r.resourceArn(arn).tagKeys("env", "absent"));
                assertEquals(Map.of("owner", "platform", "keep", "yes"),
                        client.getTags(r -> r.resourceArn(arn)).tags());
            } finally {
                client.deleteVpcLink(r -> r.vpcLinkId(link.id()));
            }
            assertThrows(NotFoundException.class, () -> client.getTags(r -> r.resourceArn(arn)));
            assertThrows(NotFoundException.class,
                    () -> client.tagResource(r -> r.resourceArn(arn).tags(Map.of("env", "test"))));
            assertThrows(NotFoundException.class, () -> client.untagResource(r -> r.resourceArn(arn).tagKeys("env")));
        }
    }

    @Test
    void reservedTagUpdatesAreRejectedWithoutChangingExistingTags() {
        try (ApiGatewayClient client = client()) {
            Map<String, String> original = Map.of("owner", "network");
            CreateVpcLinkResponse link = client.createVpcLink(r -> r.name("reserved-tag-link")
                    .targetArns("arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/net/test/123")
                    .tags(original));
            String arn = "arn:aws:apigateway:us-east-1::/vpclinks/" + link.id();
            try {
                for (String key : List.of("floci:custom-id", "floci:reserved", "_custom_id_")) {
                    BadRequestException error = assertThrows(BadRequestException.class,
                            () -> client.tagResource(r -> r.resourceArn(arn)
                                    .tags(Map.of("owner", "changed", key, "reserved"))));
                    assertEquals(400, error.statusCode());
                    assertEquals(original, client.getTags(r -> r.resourceArn(arn)).tags());
                    assertEquals(original, client.getVpcLink(r -> r.vpcLinkId(link.id())).tags());
                }
            } finally {
                client.deleteVpcLink(r -> r.vpcLinkId(link.id()));
            }
        }
    }

    @Test
    void nestedVpcLinkArnIsRejected() {
        try (ApiGatewayClient client = client()) {
            String arn = "arn:aws:apigateway:us-east-1::/vpclinks/missing/nested";
            assertThrows(BadRequestException.class, () -> client.getTags(r -> r.resourceArn(arn)));
            assertThrows(BadRequestException.class,
                    () -> client.tagResource(r -> r.resourceArn(arn).tags(Map.of("env", "test"))));
            assertThrows(BadRequestException.class, () -> client.untagResource(r -> r.resourceArn(arn).tagKeys("env")));
        }
    }
}
