package io.floci.aws.services.apigateway;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.BadRequestException;
import software.amazon.awssdk.services.apigateway.model.GetRestApisResponse;
import software.amazon.awssdk.services.apigateway.model.RestApi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ApiGatewayRestApiPagingIntegrationTest {

    private ApiGatewayClient client() {
        return AwsTestClients.create(ApiGatewayClient.builder(), "test", Region.US_EAST_1,
                Apache5HttpClient.builder());
    }

    @Test
    void getRestApisPagesWithLimitAndTheDefaultPageSize() {
        try (ApiGatewayClient client = client()) {
            Set<String> created = new HashSet<>();
            try {
                for (int i = 0; i < 26; i++) {
                    String name = "PagedApi" + i;
                    created.add(client.createRestApi(r -> r.name(name)).id());
                }

                GetRestApisResponse first = client.getRestApis(r -> r.limit(1));
                assertEquals(1, first.items().size());
                assertNotNull(first.position());
                GetRestApisResponse second = client.getRestApis(r -> r.limit(1).position(first.position()));
                assertEquals(1, second.items().size());
                assertNotEquals(first.items().get(0).id(), second.items().get(0).id());

                GetRestApisResponse defaultPage = client.getRestApis();
                assertEquals(25, defaultPage.items().size());
                assertNotNull(defaultPage.position());

                List<String> seen = new ArrayList<>(defaultPage.items().stream().map(RestApi::id).toList());
                String position = defaultPage.position();
                while (position != null) {
                    String next = position;
                    GetRestApisResponse page = client.getRestApis(r -> r.position(next));
                    seen.addAll(page.items().stream().map(RestApi::id).toList());
                    position = page.position();
                }
                assertEquals(seen.size(), new HashSet<>(seen).size());
                assertTrue(seen.containsAll(created));

                assertThrows(BadRequestException.class,
                        () -> client.getRestApis(r -> r.limit(1).position("missing")));
            } finally {
                created.forEach(id -> client.deleteRestApi(r -> r.restApiId(id)));
            }
        }
    }

    @Test
    void everyCollectionRefusesAnEmptyOrBlankPosition() {
        try (ApiGatewayClient client = client()) {
            String apiId = client.createRestApi(r -> r.name("BlankPositionApi")).id();
            String planId = client.createUsagePlan(r -> r.name("BlankPositionPlan")).id();
            String domainName = "blank-position.example.com";
            client.createDomainName(r -> r.domainName(domainName));
            try {
                List<String> collections = List.of(
                        "/restapis",
                        "/restapis/" + apiId + "/resources",
                        "/restapis/" + apiId + "/deployments",
                        "/restapis/" + apiId + "/authorizers",
                        "/restapis/" + apiId + "/requestvalidators",
                        "/restapis/" + apiId + "/models",
                        "/restapis/" + apiId + "/documentation/parts",
                        "/usageplans",
                        "/usageplans/" + planId + "/keys",
                        "/domainnames/" + domainName + "/basepathmappings",
                        "/vpclinks");
                for (String collection : collections) {
                    for (String position : List.of("", " ")) {
                        given().queryParam("position", position).when().get(collection).then().statusCode(400)
                                .body("__type", equalTo("BadRequestException"))
                                .body("message", equalTo("Invalid position parameter"));
                    }
                    given().when().get(collection).then().statusCode(200);
                }
            } finally {
                client.deleteDomainName(r -> r.domainName(domainName));
                client.deleteUsagePlan(r -> r.usagePlanId(planId));
                client.deleteRestApi(r -> r.restApiId(apiId));
            }
        }
    }
}
