package io.floci.aws.services.apigateway;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.BadRequestException;
import software.amazon.awssdk.services.apigateway.model.GetDeploymentsResponse;
import software.amazon.awssdk.services.apigateway.model.IntegrationType;
import software.amazon.awssdk.services.apigateway.model.NotFoundException;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ApiGatewayDeploymentsPaginationIntegrationTest {

    @Test
    void explicitLimitAndSdkTraversalPreserveDeploymentFields() {
        try (Fixture fixture = new Fixture(3)) {
            GetDeploymentsResponse first = fixture.client.getDeployments(r ->
                    r.restApiId(fixture.apiId).limit(1));
            assertEquals(1, first.items().size());
            assertNotNull(first.position());
            Set<String> seen = new HashSet<>();
            fixture.client.getDeploymentsPaginator(r -> r.restApiId(fixture.apiId).limit(1))
                    .forEach(page -> {
                        assertEquals(1, page.items().size());
                        page.items().forEach(deployment -> {
                            assertTrue(seen.add(deployment.id()));
                            assertNotNull(deployment.createdDate());
                            assertTrue(deployment.description().startsWith("deployment-"));
                        });
                    });
            assertEquals(fixture.ids, seen);
            GetDeploymentsResponse complete = fixture.client.getDeployments(r ->
                    r.restApiId(fixture.apiId).limit(500));
            assertEquals(fixture.ids, complete.items().stream().map(deployment -> deployment.id())
                    .collect(Collectors.toSet()));
            assertNull(complete.position());
            fixture.ids.forEach(id -> assertEquals(id, fixture.client.getDeployment(r ->
                    r.restApiId(fixture.apiId).deploymentId(id)).id()));
        }
    }

    @Test
    void omittedLimitUsesTwentyFiveAndTheLastPageOmitsPosition() {
        try (Fixture fixture = new Fixture(26)) {
            GetDeploymentsResponse first = fixture.client.getDeployments(r -> r.restApiId(fixture.apiId));
            assertEquals(25, first.items().size());
            assertNotNull(first.position());
            GetDeploymentsResponse last = fixture.client.getDeployments(r ->
                    r.restApiId(fixture.apiId).position(first.position()));
            assertEquals(1, last.items().size());
            assertNull(last.position());
        }
    }

    @Test
    void emptyApiAndMissingParentKeepTheirExistingResponses() {
        try (Fixture fixture = new Fixture(0)) {
            GetDeploymentsResponse empty = fixture.client.getDeployments(r ->
                    r.restApiId(fixture.apiId).limit(1));
            assertTrue(empty.items().isEmpty());
            assertNull(empty.position());
            assertThrows(NotFoundException.class, () -> fixture.client.getDeployments(r ->
                    r.restApiId("missing-api").limit(1)));
        }
    }

    @Test
    void positionStaysWithItsApiAndDoesNotExposeForeignParents() {
        String account = account();
        try (Fixture first = new Fixture(account, 2);
                Fixture otherApi = new Fixture(account, 2);
                ApiGatewayClient otherAccount = AwsTestClients.create(
                        ApiGatewayClient.builder(), account(), Region.US_EAST_1);
                ApiGatewayClient otherRegion = AwsTestClients.create(
                        ApiGatewayClient.builder(), account, Region.US_WEST_2)) {
            String position = first.client.getDeployments(r ->
                    r.restApiId(first.apiId).limit(1)).position();
            assertNotNull(position);
            assertThrows(BadRequestException.class, () -> otherApi.client.getDeployments(r ->
                    r.restApiId(otherApi.apiId).limit(1).position(position)));
            assertThrows(NotFoundException.class, () -> otherAccount.getDeployments(r ->
                    r.restApiId(first.apiId).limit(1).position(position)));
            assertThrows(NotFoundException.class, () -> otherRegion.getDeployments(r ->
                    r.restApiId(first.apiId).limit(1).position(position)));
        }
    }

    private static String account() {
        return String.format("%012d", Math.floorMod(System.nanoTime(), 1_000_000_000_000L));
    }

    private static final class Fixture implements AutoCloseable {
        private final ApiGatewayClient client;
        private final String apiId;
        private final Set<String> ids = new HashSet<>();

        private Fixture(int count) {
            this(account(), count);
        }

        private Fixture(String account, int count) {
            client = AwsTestClients.create(ApiGatewayClient.builder(), account, Region.US_EAST_1);
            apiId = client.createRestApi(r -> r.name("deployment-page-" + UUID.randomUUID())).id();
            String root = client.getResources(r -> r.restApiId(apiId)).items().stream()
                    .filter(resource -> "/".equals(resource.path())).findFirst().orElseThrow().id();
            client.putMethod(r -> r.restApiId(apiId).resourceId(root).httpMethod("GET")
                    .authorizationType("NONE"));
            client.putIntegration(r -> r.restApiId(apiId).resourceId(root).httpMethod("GET")
                    .type(IntegrationType.MOCK).requestTemplates(Map.of("application/json", "{\"statusCode\":200}")));
            for (int i = 0; i < count; i++) {
                String description = "deployment-" + i;
                ids.add(client.createDeployment(r -> r.restApiId(apiId).description(description)).id());
            }
        }

        @Override
        public void close() {
            try {
                client.deleteRestApi(r -> r.restApiId(apiId));
            } finally {
                client.close();
            }
        }
    }
}
