package io.floci.aws.services.apigateway;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.BadRequestException;
import software.amazon.awssdk.services.apigateway.model.GetUsagePlansResponse;
import software.amazon.awssdk.services.apigateway.model.UsagePlan;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * GetUsagePlans honours {@code limit} and {@code position}. Runs in a region no other test uses so the
 * account-wide usage plan collection holds only this class's plans.
 */
@QuarkusTest
class ApiGatewayUsagePlanPagingIntegrationTest {

    private static final Region REGION = Region.CA_WEST_1;

    private ApiGatewayClient client() {
        return AwsTestClients.create(ApiGatewayClient.builder(), "test", REGION, Apache5HttpClient.builder());
    }

    @BeforeEach
    void deleteRegionPlans() {
        try (ApiGatewayClient client = client()) {
            for (UsagePlan plan : client.getUsagePlans(request -> request.limit(500)).items()) {
                client.deleteUsagePlan(request -> request.usagePlanId(plan.id()));
            }
        }
    }

    private Set<String> createPlans(ApiGatewayClient client, int count) {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < count; i++) {
            String name = "paging-plan-" + i;
            ids.add(client.createUsagePlan(request -> request.name(name)).id());
        }
        return ids;
    }

    @Test
    void limitOneReturnsPositionAndFollowsToTerminalPage() {
        try (ApiGatewayClient client = client()) {
            Set<String> created = createPlans(client, 3);

            GetUsagePlansResponse first = client.getUsagePlans(request -> request.limit(1));
            assertEquals(1, first.items().size());
            assertNotNull(first.position());

            List<String> seen = ApiGatewayPagingWalk.oneItemPerPageIds(
                    position -> client.getUsagePlans(request -> request.limit(1).position(position)),
                    GetUsagePlansResponse::items, GetUsagePlansResponse::position, UsagePlan::id);
            assertEquals(created, new HashSet<>(seen));
        }
    }

    @Test
    void omittedLimitReturnsFirstTwentyFivePlans() {
        try (ApiGatewayClient client = client()) {
            createPlans(client, 26);

            GetUsagePlansResponse first = client.getUsagePlans(request -> { });
            assertEquals(25, first.items().size());
            assertNotNull(first.position());

            GetUsagePlansResponse second = client.getUsagePlans(request -> request.position(first.position()));
            assertEquals(1, second.items().size());
            assertNull(second.position());
        }
    }

    @Test
    void limitLargerThanCollectionReturnsEverythingWithoutPosition() {
        try (ApiGatewayClient client = client()) {
            createPlans(client, 3);

            GetUsagePlansResponse page = client.getUsagePlans(request -> request.limit(5));
            assertEquals(3, page.items().size());
            assertNull(page.position());
        }
    }

    @Test
    void keyIdFilterIsPagedToo() {
        try (ApiGatewayClient client = client()) {
            Set<String> plans = createPlans(client, 4);
            String keyId = client.createApiKey(request -> request.name("paging-key").enabled(true)).id();
            Set<String> keyed = new HashSet<>();
            for (String planId : plans) {
                if (keyed.size() == 2) {
                    break;
                }
                client.createUsagePlanKey(request -> request.usagePlanId(planId).keyId(keyId).keyType("API_KEY"));
                keyed.add(planId);
            }
            try {
                List<String> seen = ApiGatewayPagingWalk.oneItemPerPageIds(
                        position -> client.getUsagePlans(request -> request.keyId(keyId).limit(1).position(position)),
                        GetUsagePlansResponse::items, GetUsagePlansResponse::position, UsagePlan::id);
                assertEquals(keyed, new HashSet<>(seen));
            } finally {
                client.deleteApiKey(request -> request.apiKey(keyId));
            }
        }
    }

    @Test
    void unknownPositionIsRejected() {
        try (ApiGatewayClient client = client()) {
            createPlans(client, 2);

            assertThrows(BadRequestException.class,
                    () -> client.getUsagePlans(request -> request.limit(1).position("not-a-plan")));
        }
    }
}
