package io.floci.aws.services.apigatewayv2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.apigatewayv2.model.Integration;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;
import software.amazon.awssdk.services.apigatewayv2.model.CreateIntegrationResponse;
import software.amazon.awssdk.services.apigatewayv2.model.UpdateIntegrationResponse;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class ApiGatewayV2IntegrationDescriptionIntegrationTest {

    @Inject
    ApiGatewayV2Service service;

    @Inject
    ObjectMapper mapper;

    private ApiGatewayV2Client client;
    private String apiId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createApi() {
        client = AwsTestClients.create(ApiGatewayV2Client.builder(), "test", Region.US_EAST_1,
                Apache5HttpClient.builder());
        apiId = client.createApi(b -> b.name("integration-description").protocolType("HTTP")).apiId();
    }

    @AfterEach
    void deleteApi() {
        try {
            if (apiId != null) {
                client.deleteApi(b -> b.apiId(apiId));
            }
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    @Test
    void createReturnsAndStoresDescription() {
        CreateIntegrationResponse created = client.createIntegration(b -> b.apiId(apiId)
                .integrationType("HTTP_PROXY").integrationMethod("GET")
                .integrationUri("https://example.com").payloadFormatVersion("1.0")
                .description("probe"));

        assertAll(
                () -> assertEquals("probe", created.description()),
                () -> assertEquals("probe", client.getIntegration(b -> b.apiId(apiId)
                        .integrationId(created.integrationId())).description()),
                () -> assertEquals("probe", client.getIntegrations(b -> b.apiId(apiId))
                        .items().getFirst().description()));
    }

    @Test
    void updateReturnsAndStoresDescription() {
        String integrationId = createIntegration();

        UpdateIntegrationResponse updated = client.updateIntegration(b -> b.apiId(apiId)
                .integrationId(integrationId).description("probe2"));

        assertAll(
                () -> assertEquals("probe2", updated.description()),
                () -> assertEquals("probe2", client.getIntegration(b -> b.apiId(apiId)
                        .integrationId(integrationId)).description()),
                () -> assertEquals("probe2", client.getIntegrations(b -> b.apiId(apiId))
                        .items().getFirst().description()));
    }

    @Test
    void omittedDescriptionOnUpdatePreservesStoredValue() {
        String integrationId = createIntegration();
        client.updateIntegration(b -> b.apiId(apiId).integrationId(integrationId).description("probe"));

        UpdateIntegrationResponse updated = client.updateIntegration(b -> b.apiId(apiId)
                .integrationId(integrationId).integrationUri("https://updated.example.com"));

        assertEquals("https://updated.example.com", updated.integrationUri());
        assertEquals("probe", updated.description());
        assertEquals("probe", client.getIntegration(b -> b.apiId(apiId)
                .integrationId(integrationId)).description());
    }

    @Test
    void emptyDescriptionClearsStoredValue() {
        String integrationId = createIntegration();
        client.updateIntegration(b -> b.apiId(apiId).integrationId(integrationId).description("probe"));

        UpdateIntegrationResponse updated = client.updateIntegration(b -> b.apiId(apiId)
                .integrationId(integrationId).description(""));

        assertAll(
                () -> assertEquals("", updated.description()),
                () -> assertEquals("", client.getIntegration(b -> b.apiId(apiId)
                        .integrationId(integrationId)).description()),
                () -> assertEquals("", client.getIntegrations(b -> b.apiId(apiId))
                        .items().getFirst().description()));
    }

    @Test
    void descriptionIsOptional() {
        String integrationId = createIntegration();

        assertNull(client.getIntegration(b -> b.apiId(apiId).integrationId(integrationId)).description());
        assertNull(client.updateIntegration(b -> b.apiId(apiId).integrationId(integrationId)
                .integrationMethod("POST")).description());
    }

    @Test
    void copiedAndSerializedIntegrationRetainsDescriptionOnRestore() throws Exception {
        String integrationId = createIntegration();
        client.updateIntegration(b -> b.apiId(apiId).integrationId(integrationId).description("probe"));
        Integration snapshot = new Integration(service.getIntegration("us-east-1", apiId, integrationId));
        Integration reloaded = mapper.readValue(mapper.writeValueAsString(snapshot), Integration.class);

        client.deleteIntegration(b -> b.apiId(apiId).integrationId(integrationId));
        service.restoreIntegration("us-east-1", apiId, reloaded);

        assertEquals("probe", client.getIntegration(b -> b.apiId(apiId)
                .integrationId(integrationId)).description());
    }

    @Test
    void json11CreateUpdateGetAndListRetainDescription() {
        String integrationId = jsonRequest("CreateIntegration")
                .body(Map.of("ApiId", apiId, "IntegrationType", "HTTP_PROXY", "IntegrationMethod", "GET",
                        "IntegrationUri", "https://example.com", "PayloadFormatVersion", "1.0",
                        "Description", "probe"))
                .when().post("/")
                .then().statusCode(201)
                .body("Description", equalTo("probe"))
                .extract().path("IntegrationId");

        jsonRequest("GetIntegration")
                .body(Map.of("ApiId", apiId, "IntegrationId", integrationId))
                .when().post("/")
                .then().statusCode(200)
                .body("Description", equalTo("probe"));

        jsonRequest("UpdateIntegration")
                .body(Map.of("ApiId", apiId, "IntegrationId", integrationId, "Description", "probe2"))
                .when().post("/")
                .then().statusCode(200)
                .body("Description", equalTo("probe2"));

        jsonRequest("GetIntegration")
                .body(Map.of("ApiId", apiId, "IntegrationId", integrationId))
                .when().post("/")
                .then().statusCode(200)
                .body("Description", equalTo("probe2"));

        jsonRequest("GetIntegrations")
                .body(Map.of("ApiId", apiId))
                .when().post("/")
                .then().statusCode(200)
                .body("Items[0].Description", equalTo("probe2"));

        assertEquals("probe2", client.getIntegration(b -> b.apiId(apiId)
                .integrationId(integrationId)).description());
    }

    private RequestSpecification jsonRequest(String action) {
        return given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonApiGatewayV2." + action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20261111/us-east-1/apigatewayv2/aws4_request");
    }

    private String createIntegration() {
        return client.createIntegration(b -> b.apiId(apiId)
                .integrationType("HTTP_PROXY").integrationMethod("GET")
                .integrationUri("https://example.com").payloadFormatVersion("1.0")).integrationId();
    }
}
