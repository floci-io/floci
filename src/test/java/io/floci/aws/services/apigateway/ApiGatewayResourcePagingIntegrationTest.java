package io.floci.aws.services.apigateway;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.BadRequestException;
import software.amazon.awssdk.services.apigateway.model.GetResourcesResponse;
import software.amazon.awssdk.services.apigateway.model.NotFoundException;
import software.amazon.awssdk.services.apigateway.model.Resource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class ApiGatewayResourcePagingIntegrationTest {

    private ApiGatewayClient client() {
        return AwsTestClients.create(ApiGatewayClient.builder(), "test", Region.US_EAST_1,
                Apache5HttpClient.builder());
    }

    @Test
    void getResourcesWithLimitFollowsPositionToTerminalPage() {
        try (ApiGatewayClient client = client()) {
            String apiId = client.createRestApi(request -> request.name("resource-paging-sdk")).id();
            try {
                String rootId = client.getResources(request -> request.restApiId(apiId)).items().get(0).id();
                Set<String> created = new HashSet<>();
                created.add(rootId);
                for (String part : List.of("a", "b")) {
                    created.add(client.createResource(request -> request.restApiId(apiId).parentId(rootId)
                            .pathPart(part)).id());
                }

                List<String> seen = ApiGatewayPagingWalk.oneItemPerPageIds(
                        position -> client.getResources(
                                request -> request.restApiId(apiId).limit(1).position(position)),
                        GetResourcesResponse::items, GetResourcesResponse::position, Resource::id);
                assertEquals(created, new HashSet<>(seen));

                GetResourcesResponse all = client.getResources(request -> request.restApiId(apiId));
                assertEquals(3, all.items().size());
                assertNull(all.position());

                assertThrows(BadRequestException.class, () -> client.getResources(
                        request -> request.restApiId(apiId).limit(1).position("missing")));
                assertThrows(BadRequestException.class, () -> client.getResources(
                        request -> request.restApiId(apiId).limit(0)));
                assertThrows(NotFoundException.class, () -> client.getResources(
                        request -> request.restApiId("missing").limit(1)));
            } finally {
                client.deleteRestApi(request -> request.restApiId(apiId));
            }
        }
    }

    @Test
    void getResourcesKeepsEmbeddedMethodsOnEachPage() {
        try (ApiGatewayClient client = client()) {
            String apiId = client.createRestApi(request -> request.name("resource-paging-embed")).id();
            try {
                String rootId = client.getResources(request -> request.restApiId(apiId)).items().get(0).id();
                String childId = client.createResource(request -> request.restApiId(apiId).parentId(rootId)
                        .pathPart("a")).id();
                client.putMethod(request -> request.restApiId(apiId).resourceId(childId).httpMethod("GET")
                        .authorizationType("NONE"));

                GetResourcesResponse first = client.getResources(
                        request -> request.restApiId(apiId).embed("methods").limit(1));
                assertEquals(1, first.items().size());
                assertNotNull(first.position());
                GetResourcesResponse second = client.getResources(
                        request -> request.restApiId(apiId).embed("methods").limit(1).position(first.position()));
                assertNull(second.position());
                Resource child = (first.items().get(0).id().equals(childId) ? first : second).items().get(0);
                assertEquals(Set.of("GET"), child.resourceMethods().keySet());
            } finally {
                client.deleteRestApi(request -> request.restApiId(apiId));
            }
        }
    }
}
