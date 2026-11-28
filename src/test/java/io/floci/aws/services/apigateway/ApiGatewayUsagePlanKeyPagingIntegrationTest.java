package io.floci.aws.services.apigateway;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.BadRequestException;
import software.amazon.awssdk.services.apigateway.model.GetUsagePlanKeysResponse;
import software.amazon.awssdk.services.apigateway.model.UsagePlanKey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GetUsagePlanKeys honours {@code limit} and {@code position}.
 */
@QuarkusTest
class ApiGatewayUsagePlanKeyPagingIntegrationTest {

    private ApiGatewayClient client() {
        return AwsTestClients.create(ApiGatewayClient.builder(), "test", Region.US_EAST_1,
                Apache5HttpClient.builder());
    }

    private List<String> attachKeys(ApiGatewayClient client, String planId, String prefix, int count) {
        List<String> keyIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String name = prefix + "-" + i;
            String keyId = client.createApiKey(request -> request.name(name).enabled(true)).id();
            keyIds.add(keyId);
            client.createUsagePlanKey(request -> request.usagePlanId(planId).keyId(keyId).keyType("API_KEY"));
        }
        return keyIds;
    }

    private void cleanUp(ApiGatewayClient client, String planId, List<String> keyIds) {
        for (String keyId : keyIds) {
            client.deleteApiKey(request -> request.apiKey(keyId));
        }
        client.deleteUsagePlan(request -> request.usagePlanId(planId));
    }

    @Test
    void limitOneReturnsPositionAndFollowsToTerminalPage() {
        try (ApiGatewayClient client = client()) {
            String planId = client.createUsagePlan(request -> request.name("plan-key-paging-walk")).id();
            List<String> keyIds = new ArrayList<>();
            try {
                keyIds.addAll(attachKeys(client, planId, "plan-key-walk", 3));

                GetUsagePlanKeysResponse first = client.getUsagePlanKeys(request -> request.usagePlanId(planId)
                        .limit(1));
                assertEquals(1, first.items().size());
                assertNotNull(first.position());
                UsagePlanKey sample = first.items().get(0);
                assertEquals("API_KEY", sample.type());
                assertNotNull(sample.value());
                assertTrue(sample.name().startsWith("plan-key-walk-"));

                List<String> seen = ApiGatewayPagingWalk.oneItemPerPageIds(
                        position -> client.getUsagePlanKeys(
                                request -> request.usagePlanId(planId).limit(1).position(position)),
                        GetUsagePlanKeysResponse::items, GetUsagePlanKeysResponse::position, UsagePlanKey::id);
                assertEquals(3, seen.size());
                assertEquals(new HashSet<>(keyIds), new HashSet<>(seen));
            } finally {
                cleanUp(client, planId, keyIds);
            }
        }
    }

    @Test
    void omittedLimitReturnsFirstTwentyFiveKeys() {
        try (ApiGatewayClient client = client()) {
            String planId = client.createUsagePlan(request -> request.name("plan-key-paging-default")).id();
            List<String> keyIds = new ArrayList<>();
            try {
                keyIds.addAll(attachKeys(client, planId, "plan-key-default", 26));

                GetUsagePlanKeysResponse first = client.getUsagePlanKeys(request -> request.usagePlanId(planId));
                assertEquals(25, first.items().size());
                assertNotNull(first.position());

                GetUsagePlanKeysResponse second = client.getUsagePlanKeys(request -> request.usagePlanId(planId)
                        .position(first.position()));
                assertEquals(1, second.items().size());
                assertNull(second.position());

                Set<String> seen = new HashSet<>();
                first.items().forEach(key -> seen.add(key.id()));
                second.items().forEach(key -> seen.add(key.id()));
                assertEquals(new HashSet<>(keyIds), seen);
            } finally {
                cleanUp(client, planId, keyIds);
            }
        }
    }

    @Test
    void emptyPlanAndBadInput() {
        try (ApiGatewayClient client = client()) {
            String planId = client.createUsagePlan(request -> request.name("plan-key-paging-errors")).id();
            List<String> keyIds = new ArrayList<>();
            try {
                GetUsagePlanKeysResponse empty = client.getUsagePlanKeys(request -> request.usagePlanId(planId)
                        .limit(1));
                assertEquals(0, empty.items().size());
                assertNull(empty.position());

                keyIds.addAll(attachKeys(client, planId, "plan-key-errors", 2));

                GetUsagePlanKeysResponse all = client.getUsagePlanKeys(request -> request.usagePlanId(planId)
                        .limit(2));
                assertEquals(2, all.items().size());
                assertNull(all.position());

                assertThrows(BadRequestException.class,
                        () -> client.getUsagePlanKeys(request -> request.usagePlanId(planId).limit(0)));
                assertThrows(BadRequestException.class,
                        () -> client.getUsagePlanKeys(request -> request.usagePlanId(planId).position("missing")));
            } finally {
                cleanUp(client, planId, keyIds);
            }
        }
    }
}
