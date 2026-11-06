package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.BadRequestException;
import software.amazon.awssdk.services.apigateway.model.GetRestApisResponse;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ApiGatewayRestApisPaginationIntegrationTest {
    private static final String ACCOUNT = "760400000001";

    @Test
    void sdkPagesEveryApiAndUsesTheTwentyFiveItemDefault() {
        try (ApiGatewayClient client = AwsTestClients.create(ApiGatewayClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            List<String> ids = new ArrayList<>();
            try {
                for (int i = 0; i < 26; ++i) {
                    ids.add(client.createRestApi(r -> r.name("pagination")).id());
                }
                assertEquals(26, client.getRestApis(r -> r.limit(0)).items().size());
                assertEquals(26, client.getRestApis(r -> r.limit(-1)).items().size());
                GetRestApisResponse first = client.getRestApis(r -> r.limit(1));
                assertEquals(1, first.items().size());
                assertNotNull(first.position());
                List<String> found = client.getRestApisPaginator(r -> r.limit(7)).items().stream()
                        .map(api -> api.id()).toList();
                assertEquals(ids.size(), found.size());
                assertEquals(new HashSet<>(ids), new HashSet<>(found));
                GetRestApisResponse defaults = client.getRestApis();
                assertEquals(25, defaults.items().size());
                assertNotNull(defaults.position());
                GetRestApisResponse last = client.getRestApis(r -> r.position(defaults.position()));
                assertEquals(1, last.items().size());
                assertNull(last.position());
                assertEquals(26, client.getRestApis(r -> r.limit(500)).items().size());
            } finally {
                ids.forEach(id -> client.deleteRestApi(r -> r.restApiId(id)));
            }
        }
    }

    @Test
    void sdkRejectsOversizedPagesAndMalformedPositions() {
        try (ApiGatewayClient client = AwsTestClients.create(ApiGatewayClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            assertThrows(BadRequestException.class, () -> client.getRestApis(r -> r.limit(501)));
            assertThrows(BadRequestException.class, () -> client.getRestApis(r -> r.position("invalid-token")));
            assertTrue(client.getRestApis().items().isEmpty());
            assertNull(client.getRestApis().position());
        }
    }

    @Test
    void listAssertionsFindApisBeyondTheFirstDefaultPage() {
        List<String> ids = new ArrayList<>();
        try {
            for (int i = 0; i < 26; ++i) {
                ids.add(given().contentType("application/json").body("{\"name\":\"page-assertion\"}")
                        .post("/restapis").then().statusCode(201).extract().path("id"));
            }
            String lastId = ids.stream().max(String::compareTo).orElseThrow();
            List<String> firstPage = given().get("/restapis").then().statusCode(200)
                    .extract().path("item.id");
            assertFalse(firstPage.contains(lastId));
            assertTrue(ApiGatewayRestApiPages.restApiIds().containsAll(ids));
            assertNotNull(ApiGatewayRestApiPages.restApi(lastId).get("rootResourceId"));
        } finally {
            ids.forEach(id -> given().delete("/restapis/" + id).then().statusCode(202));
        }
    }
}
