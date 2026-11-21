package io.floci.aws.services.apigateway;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.BadRequestException;
import software.amazon.awssdk.services.apigateway.model.GetVpcLinksResponse;
import software.amazon.awssdk.services.apigateway.model.VpcLink;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ApiGatewayVpcLinkPagingIntegrationTest {

    private static final String TARGET_ARN =
            "arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/net/test/123";

    private ApiGatewayClient client() {
        return AwsTestClients.create(ApiGatewayClient.builder(), "test", Region.US_EAST_1,
                Apache5HttpClient.builder());
    }

    @Test
    void getVpcLinksPagesWithLimitAndTheDefaultPageSize() {
        try (ApiGatewayClient client = client()) {
            Set<String> created = new HashSet<>();
            try {
                for (int i = 0; i < 26; i++) {
                    String name = "PagedLink" + i;
                    created.add(client.createVpcLink(r -> r.name(name).targetArns(TARGET_ARN)).id());
                }

                GetVpcLinksResponse first = client.getVpcLinks(r -> r.limit(1));
                assertEquals(1, first.items().size());
                assertNotNull(first.position());
                GetVpcLinksResponse second = client.getVpcLinks(r -> r.limit(1).position(first.position()));
                assertEquals(1, second.items().size());
                assertNotEquals(first.items().get(0).id(), second.items().get(0).id());

                GetVpcLinksResponse defaultPage = client.getVpcLinks();
                assertEquals(25, defaultPage.items().size());
                assertNotNull(defaultPage.position());

                List<String> seen = new ArrayList<>(defaultPage.items().stream().map(VpcLink::id).toList());
                String position = defaultPage.position();
                while (position != null) {
                    String next = position;
                    GetVpcLinksResponse page = client.getVpcLinks(r -> r.position(next));
                    seen.addAll(page.items().stream().map(VpcLink::id).toList());
                    position = page.position();
                }
                assertEquals(seen.size(), new HashSet<>(seen).size());
                assertTrue(seen.containsAll(created));

                assertThrows(BadRequestException.class,
                        () -> client.getVpcLinks(r -> r.limit(1).position("missing")));
            } finally {
                created.forEach(id -> client.deleteVpcLink(r -> r.vpcLinkId(id)));
            }
        }
    }
}
