package io.floci.aws.services.apigatewayv2;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;
import software.amazon.awssdk.services.apigatewayv2.model.Api;
import software.amazon.awssdk.services.apigatewayv2.model.BadRequestException;
import software.amazon.awssdk.services.apigatewayv2.model.GetApisResponse;
import software.amazon.awssdk.services.apigatewayv2.model.ProtocolType;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayV2GetApisPaginationIntegrationTest {

    // A Region no other test creates APIs in, so the pages hold exactly this test's APIs.
    private static final Region REGION = Region.AP_SOUTHEAST_3;
    private static final List<String> NAMES = List.of("paging-a", "paging-b", "paging-c");
    private static final List<String> CREATED = new ArrayList<>();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static ApiGatewayV2Client client() {
        return AwsTestClients.create(ApiGatewayV2Client.builder(), "test", REGION, Apache5HttpClient.builder());
    }

    @Test
    @Order(1)
    void createApis() {
        try (ApiGatewayV2Client client = client()) {
            for (String name : NAMES) {
                CREATED.add(client.createApi(r -> r.name(name).protocolType(ProtocolType.HTTP)).apiId());
            }
        }
    }

    @Test
    @Order(2)
    void getApisPagesOneApiAtATimeWithATokenOnEveryPageButTheLast() {
        try (ApiGatewayV2Client client = client()) {
            List<String> seen = new ArrayList<>();
            List<Boolean> tokens = new ArrayList<>();
            String token = null;
            do {
                String next = token;
                GetApisResponse page = client.getApis(r -> r.maxResults("1").nextToken(next));
                assertEquals(1, page.items().size());
                page.items().stream().map(Api::apiId).forEach(seen::add);
                token = page.nextToken();
                tokens.add(token != null);
            } while (token != null && seen.size() < 10);

            assertEquals(CREATED.stream().sorted().toList(), seen.stream().sorted().toList());
            assertEquals(List.of(true, true, false), tokens);
        }
    }

    @Test
    @Order(2)
    void getApisWithoutMaxResultsReturnsEveryApiWithoutAToken() {
        try (ApiGatewayV2Client client = client()) {
            GetApisResponse page = client.getApis(r -> { });
            assertEquals(3, page.items().size());
            assertNull(page.nextToken());
        }
    }

    @Test
    @Order(2)
    void getApisRefusesAnUnreadableToken() {
        try (ApiGatewayV2Client client = client()) {
            BadRequestException e = assertThrows(BadRequestException.class,
                    () -> client.getApis(r -> r.nextToken("not-a-token")));
            assertEquals(400, e.statusCode());
        }
    }

    @Test
    @Order(2)
    void getApisRefusesAMaxResultsBelowOne() {
        try (ApiGatewayV2Client client = client()) {
            BadRequestException e = assertThrows(BadRequestException.class,
                    () -> client.getApis(r -> r.maxResults("0")));
            assertEquals(400, e.statusCode());
        }
    }

    @Test
    @Order(2)
    void getApisOverJson11PagesWithMaxResultsAndNextToken() {
        String token = given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonApiGatewayV2.GetApis")
                .header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=test/20260413/" + REGION.id() + "/apigatewayv2/aws4_request")
                .body("{\"MaxResults\":\"2\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("Items", hasSize(2))
                .body("NextToken", notNullValue())
                .extract().path("NextToken");

        given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonApiGatewayV2.GetApis")
                .header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=test/20260413/" + REGION.id() + "/apigatewayv2/aws4_request")
                .body("{\"MaxResults\":\"2\",\"NextToken\":\"" + token + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("Items", hasSize(1))
                .body("NextToken", nullValue());
    }

    @Test
    @Order(3)
    void deleteApis() {
        try (ApiGatewayV2Client client = client()) {
            for (String apiId : CREATED) {
                client.deleteApi(r -> r.apiId(apiId));
            }
        }
    }
}
