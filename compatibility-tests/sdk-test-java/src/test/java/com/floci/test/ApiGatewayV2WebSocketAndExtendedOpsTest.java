package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;
import software.amazon.awssdk.services.apigatewayv2.model.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Compatibility tests for the new API Gateway v2 operations added in issue #526:
 * WebSocket API support, Update operations, Route Responses, Integration Responses,
 * Models, and Tagging — all exercised via the real AWS SDK v2 Java client.
 */
@DisplayName("API Gateway v2 — WebSocket & extended operations")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayV2WebSocketAndExtendedOpsTest {

    private static ApiGatewayV2Client gw;

    // ── shared resource IDs ──
    private static String wsApiId;
    private static String httpApiId;
    private static String wsRouteId;
    private static String integrationId;
    private static String authorizerId;
    private static String deploymentId;
    private static String routeResponseId;
    private static String integrationResponseId;
    private static String modelId;

    @BeforeAll
    static void setup() {
        gw = TestFixtures.apiGatewayV2Client();
    }

    @AfterAll
    static void cleanup() {
        if (gw == null) return;
        safeDelete(() -> gw.deleteApi(DeleteApiRequest.builder().apiId(wsApiId).build()));
        safeDelete(() -> gw.deleteApi(DeleteApiRequest.builder().apiId(httpApiId).build()));
        gw.close();
    }

    // ──────────────────────────── HTTP API defaults ────────────────────────────

    @Test @Order(1)
    @DisplayName("CreateApi (HTTP) populates AWS defaults")
    void createHttpApiWithDefaults() {
        CreateApiResponse res = gw.createApi(CreateApiRequest.builder()
                .name(TestFixtures.uniqueName("http-ext"))
                .protocolType(ProtocolType.HTTP)
                .build());
        httpApiId = res.apiId();

        assertThat(httpApiId).isNotBlank();
        assertThat(res.protocolType()).isEqualTo(ProtocolType.HTTP);
        assertThat(res.apiEndpoint()).contains("https://");
        assertThat(res.routeSelectionExpression()).isEqualTo("${request.method} ${request.path}");
        assertThat(res.apiKeySelectionExpression()).isEqualTo("$request.header.x-api-key");
        assertThat(res.createdDate()).isNotNull();
    }

    @Test @Order(2)
    @DisplayName("GetApi (HTTP) returns persisted defaults")
    void getHttpApiVerifyDefaults() {
        requireHttpApi();
        GetApiResponse res = gw.getApi(GetApiRequest.builder().apiId(httpApiId).build());

        assertThat(res.routeSelectionExpression()).isEqualTo("${request.method} ${request.path}");
        assertThat(res.apiKeySelectionExpression()).isEqualTo("$request.header.x-api-key");
    }

    // ──────────────────────────── WebSocket API lifecycle ────────────────────────────

    @Test @Order(10)
    @DisplayName("CreateApi (WEBSOCKET) with routeSelectionExpression")
    void createWebSocketApi() {
        CreateApiResponse res = gw.createApi(CreateApiRequest.builder()
                .name(TestFixtures.uniqueName("ws-ext"))
                .protocolType(ProtocolType.WEBSOCKET)
                .routeSelectionExpression("$request.body.action")
                .description("Java compat WS API")
                .apiKeySelectionExpression("$request.header.x-api-key")
                .build());
        wsApiId = res.apiId();

        assertThat(wsApiId).isNotBlank();
        assertThat(res.protocolType()).isEqualTo(ProtocolType.WEBSOCKET);
        assertThat(res.apiEndpoint()).contains("wss://");
        assertThat(res.routeSelectionExpression()).isEqualTo("$request.body.action");
        assertThat(res.description()).isEqualTo("Java compat WS API");
    }

    @Test @Order(11)
    @DisplayName("CreateApi (WEBSOCKET) without routeSelectionExpression is rejected")
    void createWebSocketApiMissingRse() {
        assertThatThrownBy(() -> gw.createApi(CreateApiRequest.builder()
                .name(TestFixtures.uniqueName("ws-no-rse"))
                .protocolType(ProtocolType.WEBSOCKET)
                .build()))
                .isInstanceOf(ApiGatewayV2Exception.class);
    }

    @Test @Order(12)
    @DisplayName("UpdateApi preserves unmodified fields")
    void updateWebSocketApi() {
        requireWsApi();
        UpdateApiResponse res = gw.updateApi(UpdateApiRequest.builder()
                .apiId(wsApiId)
                .name("ws-updated-java")
                .build());

        assertThat(res.name()).isEqualTo("ws-updated-java");
        assertThat(res.protocolType()).isEqualTo(ProtocolType.WEBSOCKET);
        assertThat(res.routeSelectionExpression()).isEqualTo("$request.body.action");
        assertThat(res.apiEndpoint()).contains("wss://");
    }

    @Test @Order(13)
    @DisplayName("UpdateApi on non-existent API returns 404")
    void updateApiNotFound() {
        assertThatThrownBy(() -> gw.updateApi(UpdateApiRequest.builder()
                .apiId("nonexistent999")
                .name("ghost")
                .build()))
                .isInstanceOf(NotFoundException.class);
    }

    // ──────────────────────────── Routes with routeResponseSelectionExpression ────────────────────────────

    @Test @Order(20)
    @DisplayName("CreateRoute with routeResponseSelectionExpression")
    void createRouteWithRrse() {
        requireWsApi();
        CreateRouteResponse res = gw.createRoute(CreateRouteRequest.builder()
                .apiId(wsApiId)
                .routeKey("$default")
                .authorizationType("NONE")
                .routeResponseSelectionExpression("$default")
                .build());
        wsRouteId = res.routeId();

        assertThat(wsRouteId).isNotBlank();
        assertThat(res.routeKey()).isEqualTo("$default");
        assertThat(res.routeResponseSelectionExpression()).isEqualTo("$default");
    }

    @Test @Order(21)
    @DisplayName("UpdateRoute preserves unmodified fields")
    void updateRoute() {
        requireWsApi(); requireWsRoute();
        UpdateRouteResponse res = gw.updateRoute(UpdateRouteRequest.builder()
                .apiId(wsApiId)
                .routeId(wsRouteId)
                .target("integrations/fake-id")
                .build());

        assertThat(res.target()).isEqualTo("integrations/fake-id");
        assertThat(res.routeKey()).isEqualTo("$default");
        assertThat(res.routeResponseSelectionExpression()).isEqualTo("$default");
    }

    // ──────────────────────────── Integrations + Update ────────────────────────────

    @Test @Order(30)
    @DisplayName("CreateIntegration")
    void createIntegration() {
        requireWsApi();
        CreateIntegrationResponse res = gw.createIntegration(CreateIntegrationRequest.builder()
                .apiId(wsApiId)
                .integrationType(IntegrationType.HTTP_PROXY)
                .integrationUri("https://example.com")
                .payloadFormatVersion("2.0")
                .build());
        integrationId = res.integrationId();

        assertThat(integrationId).isNotBlank();
    }

    @Test @Order(31)
    @DisplayName("UpdateIntegration preserves unmodified fields")
    void updateIntegration() {
        requireWsApi(); requireIntegration();
        UpdateIntegrationResponse res = gw.updateIntegration(UpdateIntegrationRequest.builder()
                .apiId(wsApiId)
                .integrationId(integrationId)
                .integrationUri("https://updated.example.com")
                .build());

        assertThat(res.integrationUri()).isEqualTo("https://updated.example.com");
        assertThat(res.integrationType()).isEqualTo(IntegrationType.HTTP_PROXY);
        assertThat(res.payloadFormatVersion()).isEqualTo("2.0");
    }

    // ──────────────────────────── Authorizers + Update ────────────────────────────

    @Test @Order(40)
    @DisplayName("CreateAuthorizer (JWT) with string identitySource")
    void createAuthorizer() {
        requireWsApi();
        CreateAuthorizerResponse res = gw.createAuthorizer(CreateAuthorizerRequest.builder()
                .apiId(wsApiId)
                .name("jwt-ext-auth")
                .authorizerType(AuthorizerType.JWT)
                .identitySource("$request.header.Authorization")
                .jwtConfiguration(j -> j.issuer("https://issuer.example.com").audience("aud-1"))
                .build());
        authorizerId = res.authorizerId();

        assertThat(authorizerId).isNotBlank();
        assertThat(res.authorizerType()).isEqualTo(AuthorizerType.JWT);
        assertThat(res.identitySource()).containsExactly("$request.header.Authorization");
    }

    @Test @Order(41)
    @DisplayName("UpdateAuthorizer preserves unmodified fields")
    void updateAuthorizer() {
        requireWsApi(); requireAuthorizer();
        UpdateAuthorizerResponse res = gw.updateAuthorizer(UpdateAuthorizerRequest.builder()
                .apiId(wsApiId)
                .authorizerId(authorizerId)
                .name("jwt-updated")
                .build());

        assertThat(res.name()).isEqualTo("jwt-updated");
        assertThat(res.authorizerType()).isEqualTo(AuthorizerType.JWT);
        assertThat(res.identitySource()).containsExactly("$request.header.Authorization");
    }

    // ──────────────────────────── Stages & Deployments + Update ────────────────────────────

    @Test @Order(50)
    @DisplayName("CreateDeployment")
    void createDeployment() {
        requireWsApi();
        CreateDeploymentResponse res = gw.createDeployment(CreateDeploymentRequest.builder()
                .apiId(wsApiId)
                .description("ext-deploy")
                .build());
        deploymentId = res.deploymentId();

        assertThat(deploymentId).isNotBlank();
    }

    @Test @Order(51)
    @DisplayName("UpdateDeployment preserves unmodified fields")
    void updateDeployment() {
        requireWsApi(); requireDeployment();
        UpdateDeploymentResponse res = gw.updateDeployment(UpdateDeploymentRequest.builder()
                .apiId(wsApiId)
                .deploymentId(deploymentId)
                .description("updated-deploy")
                .build());

        assertThat(res.description()).isEqualTo("updated-deploy");
        assertThat(res.deploymentStatusAsString()).isEqualTo("DEPLOYED");
    }

    @Test @Order(52)
    @DisplayName("CreateStage + UpdateStage preserves unmodified fields")
    void createAndUpdateStage() {
        requireWsApi(); requireDeployment();
        gw.createStage(CreateStageRequest.builder()
                .apiId(wsApiId)
                .stageName("dev")
                .deploymentId(deploymentId)
                .autoDeploy(false)
                .build());

        UpdateStageResponse res = gw.updateStage(UpdateStageRequest.builder()
                .apiId(wsApiId)
                .stageName("dev")
                .autoDeploy(true)
                .build());

        assertThat(res.autoDeploy()).isTrue();
        assertThat(res.deploymentId()).isEqualTo(deploymentId);
        assertThat(res.lastUpdatedDate()).isNotNull();
    }

    // ──────────────────────────── Route Responses ────────────────────────────

    @Test @Order(60)
    @DisplayName("Route Response full CRUD")
    void routeResponseCrud() {
        requireWsApi(); requireWsRoute();

        // Create
        CreateRouteResponseResponse createRes = gw.createRouteResponse(CreateRouteResponseRequest.builder()
                .apiId(wsApiId)
                .routeId(wsRouteId)
                .routeResponseKey("$default")
                .modelSelectionExpression("$default")
                .build());
        routeResponseId = createRes.routeResponseId();
        assertThat(routeResponseId).isNotBlank();
        assertThat(createRes.routeResponseKey()).isEqualTo("$default");

        // Get
        GetRouteResponseResponse getRes = gw.getRouteResponse(GetRouteResponseRequest.builder()
                .apiId(wsApiId).routeId(wsRouteId).routeResponseId(routeResponseId).build());
        assertThat(getRes.routeResponseId()).isEqualTo(routeResponseId);

        // List
        GetRouteResponsesResponse listRes = gw.getRouteResponses(GetRouteResponsesRequest.builder()
                .apiId(wsApiId).routeId(wsRouteId).build());
        assertThat(listRes.items()).extracting(RouteResponse::routeResponseId).contains(routeResponseId);

        // Update
        UpdateRouteResponseResponse updateRes = gw.updateRouteResponse(UpdateRouteResponseRequest.builder()
                .apiId(wsApiId).routeId(wsRouteId).routeResponseId(routeResponseId)
                .routeResponseKey("$updated")
                .build());
        assertThat(updateRes.routeResponseKey()).isEqualTo("$updated");

        // Delete
        gw.deleteRouteResponse(DeleteRouteResponseRequest.builder()
                .apiId(wsApiId).routeId(wsRouteId).routeResponseId(routeResponseId).build());
        assertThatThrownBy(() -> gw.getRouteResponse(GetRouteResponseRequest.builder()
                .apiId(wsApiId).routeId(wsRouteId).routeResponseId(routeResponseId).build()))
                .isInstanceOf(NotFoundException.class);
    }

    // ──────────────────────────── Integration Responses ────────────────────────────

    @Test @Order(70)
    @DisplayName("Integration Response full CRUD")
    void integrationResponseCrud() {
        requireWsApi(); requireIntegration();

        // Create
        CreateIntegrationResponseResponse createRes = gw.createIntegrationResponse(CreateIntegrationResponseRequest.builder()
                .apiId(wsApiId)
                .integrationId(integrationId)
                .integrationResponseKey("$default")
                .contentHandlingStrategy("CONVERT_TO_TEXT")
                .build());
        integrationResponseId = createRes.integrationResponseId();
        assertThat(integrationResponseId).isNotBlank();
        assertThat(createRes.contentHandlingStrategy()).hasToString("CONVERT_TO_TEXT");

        // Get
        GetIntegrationResponseResponse getRes = gw.getIntegrationResponse(GetIntegrationResponseRequest.builder()
                .apiId(wsApiId).integrationId(integrationId)
                .integrationResponseId(integrationResponseId).build());
        assertThat(getRes.integrationResponseId()).isEqualTo(integrationResponseId);

        // List
        GetIntegrationResponsesResponse listRes = gw.getIntegrationResponses(GetIntegrationResponsesRequest.builder()
                .apiId(wsApiId).integrationId(integrationId).build());
        assertThat(listRes.items()).extracting(IntegrationResponse::integrationResponseId)
                .contains(integrationResponseId);

        // Update
        UpdateIntegrationResponseResponse updateRes = gw.updateIntegrationResponse(UpdateIntegrationResponseRequest.builder()
                .apiId(wsApiId).integrationId(integrationId)
                .integrationResponseId(integrationResponseId)
                .contentHandlingStrategy("CONVERT_TO_BINARY")
                .build());
        assertThat(updateRes.contentHandlingStrategy()).hasToString("CONVERT_TO_BINARY");

        // Delete
        gw.deleteIntegrationResponse(DeleteIntegrationResponseRequest.builder()
                .apiId(wsApiId).integrationId(integrationId)
                .integrationResponseId(integrationResponseId).build());
        assertThatThrownBy(() -> gw.getIntegrationResponse(GetIntegrationResponseRequest.builder()
                .apiId(wsApiId).integrationId(integrationId)
                .integrationResponseId(integrationResponseId).build()))
                .isInstanceOf(NotFoundException.class);
    }

    // ──────────────────────────── Models ────────────────────────────

    @Test @Order(80)
    @DisplayName("Model full CRUD with merge-patch")
    void modelCrud() {
        requireWsApi();

        // Create
        CreateModelResponse createRes = gw.createModel(CreateModelRequest.builder()
                .apiId(wsApiId)
                .name("PetModel")
                .schema("{\"type\":\"object\"}")
                .contentType("application/json")
                .description("A pet schema")
                .build());
        modelId = createRes.modelId();
        assertThat(modelId).isNotBlank();
        assertThat(createRes.name()).isEqualTo("PetModel");

        // Get
        GetModelResponse getRes = gw.getModel(GetModelRequest.builder()
                .apiId(wsApiId).modelId(modelId).build());
        assertThat(getRes.name()).isEqualTo("PetModel");
        assertThat(getRes.contentType()).isEqualTo("application/json");

        // List
        GetModelsResponse listRes = gw.getModels(GetModelsRequest.builder().apiId(wsApiId).build());
        assertThat(listRes.items()).extracting(Model::modelId).contains(modelId);

        // Update (merge-patch)
        UpdateModelResponse updateRes = gw.updateModel(UpdateModelRequest.builder()
                .apiId(wsApiId).modelId(modelId)
                .description("updated description")
                .build());
        assertThat(updateRes.description()).isEqualTo("updated description");
        assertThat(updateRes.name()).isEqualTo("PetModel");
        assertThat(updateRes.contentType()).isEqualTo("application/json");

        // Delete
        gw.deleteModel(DeleteModelRequest.builder().apiId(wsApiId).modelId(modelId).build());
        assertThatThrownBy(() -> gw.getModel(GetModelRequest.builder()
                .apiId(wsApiId).modelId(modelId).build()))
                .isInstanceOf(NotFoundException.class);
    }

    // ──────────────────────────── Tagging ────────────────────────────

    @Test @Order(90)
    @DisplayName("Create API with tags, TagResource, UntagResource, GetTags")
    void tagging() {
        // Create API with initial tags
        CreateApiResponse createRes = gw.createApi(CreateApiRequest.builder()
                .name(TestFixtures.uniqueName("tag-java"))
                .protocolType(ProtocolType.HTTP)
                .tags(Map.of("initial", "tag"))
                .build());
        String tagApiId = createRes.apiId();
        String arn = TestFixtures.arn("apigateway", "", "/apis/" + tagApiId);

        try {
            // Verify tags on create
            assertThat(createRes.tags()).containsEntry("initial", "tag");

            // TagResource — add more tags
            gw.tagResource(TagResourceRequest.builder()
                    .resourceArn(arn)
                    .tags(Map.of("env", "production", "team", "platform"))
                    .build());

            Map<String, String> tags = gw.getTags(GetTagsRequest.builder().resourceArn(arn).build()).tags();
            assertThat(tags).containsEntry("initial", "tag");
            assertThat(tags).containsEntry("env", "production");
            assertThat(tags).containsEntry("team", "platform");

            // UntagResource — remove specific keys
            gw.untagResource(UntagResourceRequest.builder()
                    .resourceArn(arn)
                    .tagKeys("initial", "team")
                    .build());

            Map<String, String> tagsAfter = gw.getTags(GetTagsRequest.builder().resourceArn(arn).build()).tags();
            assertThat(tagsAfter).containsEntry("env", "production");
            assertThat(tagsAfter).doesNotContainKey("initial");
            assertThat(tagsAfter).doesNotContainKey("team");

        } finally {
            safeDelete(() -> gw.deleteApi(DeleteApiRequest.builder().apiId(tagApiId).build()));
        }
    }

    @Test @Order(91)
    @DisplayName("GetTags on API with no tags returns empty map")
    void getTagsEmpty() {
        CreateApiResponse createRes = gw.createApi(CreateApiRequest.builder()
                .name(TestFixtures.uniqueName("notag-java"))
                .protocolType(ProtocolType.HTTP)
                .build());
        String noTagApiId = createRes.apiId();
        String arn = TestFixtures.arn("apigateway", "", "/apis/" + noTagApiId);

        try {
            Map<String, String> tags = gw.getTags(GetTagsRequest.builder().resourceArn(arn).build()).tags();
            assertThat(tags).isEmpty();
        } finally {
            safeDelete(() -> gw.deleteApi(DeleteApiRequest.builder().apiId(noTagApiId).build()));
        }
    }

    @Test @Order(92)
    @DisplayName("TagResource on non-existent API returns 404")
    void tagResourceNotFound() {
        assertThatThrownBy(() -> gw.tagResource(TagResourceRequest.builder()
                .resourceArn(TestFixtures.arn("apigateway", "", "/apis/nonexistent999"))
                .tags(Map.of("k", "v"))
                .build()))
                .isInstanceOf(NotFoundException.class);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private static void safeDelete(Runnable r) {
        try { r.run(); } catch (Exception ignored) {}
    }

    private static void requireWsApi() {
        Assumptions.assumeTrue(wsApiId != null, "WebSocket API must exist");
    }

    private static void requireHttpApi() {
        Assumptions.assumeTrue(httpApiId != null, "HTTP API must exist");
    }

    private static void requireWsRoute() {
        Assumptions.assumeTrue(wsRouteId != null, "WebSocket route must exist");
    }

    private static void requireIntegration() {
        Assumptions.assumeTrue(integrationId != null, "Integration must exist");
    }

    private static void requireAuthorizer() {
        Assumptions.assumeTrue(authorizerId != null, "Authorizer must exist");
    }

    private static void requireDeployment() {
        Assumptions.assumeTrue(deploymentId != null, "Deployment must exist");
    }
}
