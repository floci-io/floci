package io.floci.aws.services.apigateway;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.BadRequestException;
import software.amazon.awssdk.services.apigateway.model.GetModelsResponse;
import software.amazon.awssdk.services.apigateway.model.Model;
import software.amazon.awssdk.services.apigateway.model.NotFoundException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class ApiGatewayModelPagingIntegrationTest {

    private static final String SCHEMA = "{\"type\":\"object\"}";

    private ApiGatewayClient client() {
        return AwsTestClients.create(ApiGatewayClient.builder(), "test", Region.US_EAST_1,
                Apache5HttpClient.builder());
    }

    @Test
    void getModelsPagesWithLimitAndTheDefaultPageSize() {
        try (ApiGatewayClient client = client()) {
            String apiId = client.createRestApi(r -> r.name("model-paging")).id();
            try {
                Set<String> created = new HashSet<>();
                for (int i = 0; i < 27; i++) {
                    String name = "PagedModel" + i;
                    created.add(client.createModel(r -> r.restApiId(apiId).name(name)
                            .contentType("application/json").schema(SCHEMA)).id());
                }

                List<String> seen = ApiGatewayPagingWalk.oneItemPerPageIds(
                        position -> client.getModels(r -> r.restApiId(apiId).limit(1).position(position)),
                        GetModelsResponse::items, GetModelsResponse::position, Model::id);
                assertEquals(27, seen.size());
                assertEquals(created, new HashSet<>(seen));

                GetModelsResponse defaultPage = client.getModels(r -> r.restApiId(apiId));
                assertEquals(25, defaultPage.items().size());
                assertNotNull(defaultPage.position());
                GetModelsResponse lastPage = client.getModels(
                        r -> r.restApiId(apiId).position(defaultPage.position()));
                assertEquals(2, lastPage.items().size());
                assertNull(lastPage.position());

                assertThrows(BadRequestException.class,
                        () -> client.getModels(r -> r.restApiId(apiId).limit(1).position("missing")));
            } finally {
                client.deleteRestApi(r -> r.restApiId(apiId));
            }
        }
    }

    @Test
    void getModelsKeepsModelMetadataAndOmitsPositionOnTheLastPage() {
        try (ApiGatewayClient client = client()) {
            String apiId = client.createRestApi(r -> r.name("model-paging-metadata")).id();
            try {
                String id = client.createModel(r -> r.restApiId(apiId).name("Pet").description("a pet")
                        .contentType("application/json").schema(SCHEMA)).id();

                GetModelsResponse page = client.getModels(r -> r.restApiId(apiId).limit(500));
                assertNull(page.position());
                Model model = page.items().stream().filter(m -> id.equals(m.id())).findFirst().orElseThrow();
                assertEquals("Pet", model.name());
                assertEquals("a pet", model.description());
                assertEquals("application/json", model.contentType());
                assertEquals(SCHEMA, model.schema());
            } finally {
                client.deleteRestApi(r -> r.restApiId(apiId));
            }
        }
    }

    @Test
    void getModelsOnAnUnknownRestApiIsNotFound() {
        try (ApiGatewayClient client = client()) {
            assertThrows(NotFoundException.class, () -> client.getModels(r -> r.restApiId("missing000").limit(1)));
        }
    }
}
