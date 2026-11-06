package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.route53resolver.Route53ResolverClient;
import software.amazon.awssdk.services.route53resolver.model.IpAddressRequest;
import software.amazon.awssdk.services.route53resolver.model.ResolverEndpoint;
import software.amazon.awssdk.services.route53resolver.model.ResolverEndpointDirection;
import software.amazon.awssdk.services.route53resolver.model.ResourceNotFoundException;
import software.amazon.awssdk.services.route53resolver.model.Tag;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Route 53 Resolver tagging")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Route53ResolverTaggingTest {

    private static Route53ResolverClient resolver;
    private static String endpointId;
    private static String endpointArn;

    @BeforeAll
    static void setup() {
        resolver = TestFixtures.route53ResolverClient();
    }

    @AfterAll
    static void cleanup() {
        if (resolver == null) {
            return;
        }
        if (endpointId != null) {
            try {
                resolver.deleteResolverEndpoint(r -> r.resolverEndpointId(endpointId));
            } catch (RuntimeException e) {
                System.out.println("Route 53 Resolver cleanup skipped: " + e.getMessage());
            }
        }
        resolver.close();
    }

    private static Map<String, String> tags() {
        return resolver.listTagsForResource(r -> r.resourceArn(endpointArn)).tags().stream()
                .collect(Collectors.toMap(Tag::key, Tag::value));
    }

    @Test
    @Order(1)
    void createWithTags() {
        ResolverEndpoint endpoint =
                resolver.createResolverEndpoint(r -> r
                        .name("sdk-tagging-" + System.currentTimeMillis())
                        .creatorRequestId("sdk-tagging-" + System.nanoTime())
                        .direction(ResolverEndpointDirection.OUTBOUND)
                        .securityGroupIds("sg-12345678")
                        .ipAddresses(IpAddressRequest.builder().subnetId("subnet-aaaa1111").build(),
                                IpAddressRequest.builder().subnetId("subnet-bbbb2222").build())
                        .tags(Tag.builder().key("env").value("dev").build()))
                .resolverEndpoint();
        endpointId = endpoint.id();
        endpointArn = endpoint.arn();

        assertThat(tags()).containsExactly(Map.entry("env", "dev"));
    }

    @Test
    @Order(2)
    void tagResource() {
        resolver.tagResource(r -> r.resourceArn(endpointArn).tags(
                Tag.builder().key("env").value("prod").build(),
                Tag.builder().key("team").value("net").build()));

        assertThat(tags()).containsOnly(Map.entry("env", "prod"), Map.entry("team", "net"));
    }

    @Test
    @Order(3)
    void untagResource() {
        resolver.untagResource(r -> r.resourceArn(endpointArn).tagKeys(List.of("env")));

        assertThat(tags()).containsOnly(Map.entry("team", "net"));
    }

    @Test
    @Order(4)
    void deletedResourceHasNoTags() {
        resolver.deleteResolverEndpoint(r -> r.resolverEndpointId(endpointId));
        endpointId = null;

        assertThatThrownBy(() -> resolver.listTagsForResource(r -> r.resourceArn(endpointArn)))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
