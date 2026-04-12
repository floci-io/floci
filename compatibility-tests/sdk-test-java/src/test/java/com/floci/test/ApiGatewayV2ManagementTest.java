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
import software.amazon.awssdk.services.apigatewayv2.model.ApiGatewayV2Exception;
import software.amazon.awssdk.services.apigatewayv2.model.AuthorizerType;
import software.amazon.awssdk.services.apigatewayv2.model.CreateApiRequest;
import software.amazon.awssdk.services.apigatewayv2.model.CreateAuthorizerRequest;
import software.amazon.awssdk.services.apigatewayv2.model.CreateDeploymentRequest;
import software.amazon.awssdk.services.apigatewayv2.model.CreateIntegrationRequest;
import software.amazon.awssdk.services.apigatewayv2.model.CreateRouteRequest;
import software.amazon.awssdk.services.apigatewayv2.model.CreateStageRequest;
import software.amazon.awssdk.services.apigatewayv2.model.DeleteApiRequest;
import software.amazon.awssdk.services.apigatewayv2.model.DeleteAuthorizerRequest;
import software.amazon.awssdk.services.apigatewayv2.model.DeleteDeploymentRequest;
import software.amazon.awssdk.services.apigatewayv2.model.DeleteIntegrationRequest;
import software.amazon.awssdk.services.apigatewayv2.model.DeleteRouteRequest;
import software.amazon.awssdk.services.apigatewayv2.model.DeleteStageRequest;
import software.amazon.awssdk.services.apigatewayv2.model.GetApiRequest;
import software.amazon.awssdk.services.apigatewayv2.model.GetApisResponse;
import software.amazon.awssdk.services.apigatewayv2.model.GetAuthorizerRequest;
import software.amazon.awssdk.services.apigatewayv2.model.GetAuthorizersRequest;
import software.amazon.awssdk.services.apigatewayv2.model.GetDeploymentRequest;
import software.amazon.awssdk.services.apigatewayv2.model.GetDeploymentsRequest;
import software.amazon.awssdk.services.apigatewayv2.model.GetIntegrationRequest;
import software.amazon.awssdk.services.apigatewayv2.model.GetIntegrationsRequest;
import software.amazon.awssdk.services.apigatewayv2.model.GetRouteRequest;
import software.amazon.awssdk.services.apigatewayv2.model.GetRoutesRequest;
import software.amazon.awssdk.services.apigatewayv2.model.GetStageRequest;
import software.amazon.awssdk.services.apigatewayv2.model.GetStagesRequest;
import software.amazon.awssdk.services.apigatewayv2.model.IntegrationType;
import software.amazon.awssdk.services.apigatewayv2.model.NotFoundException;
import software.amazon.awssdk.services.apigatewayv2.model.ProtocolType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("API Gateway v2 management API")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayV2ManagementTest {

    private static ApiGatewayV2Client apigwv2;

    private static String apiId;
    private static String authorizerId;
    private static String integrationId;
    private static String routeId;
    private static String deploymentId;
    private static final String stageName = "test";
    private static boolean stageCreated;

    @BeforeAll
    static void setup() {
        apigwv2 = TestFixtures.apiGatewayV2Client();
    }

    @AfterAll
    static void cleanup() {
        if (apigwv2 != null) {
            if (apiId != null) {
                deleteIfPresent(() -> apigwv2.deleteRoute(DeleteRouteRequest.builder()
                        .apiId(apiId)
                        .routeId(routeId)
                        .build()));
                deleteIfPresent(() -> apigwv2.deleteIntegration(DeleteIntegrationRequest.builder()
                        .apiId(apiId)
                        .integrationId(integrationId)
                        .build()));
                deleteIfPresent(() -> apigwv2.deleteAuthorizer(DeleteAuthorizerRequest.builder()
                        .apiId(apiId)
                        .authorizerId(authorizerId)
                        .build()));
                deleteIfPresent(() -> apigwv2.deleteStage(DeleteStageRequest.builder()
                        .apiId(apiId)
                        .stageName(stageName)
                        .build()));
                deleteIfPresent(() -> apigwv2.deleteDeployment(DeleteDeploymentRequest.builder()
                        .apiId(apiId)
                        .deploymentId(deploymentId)
                        .build()));
                deleteIfPresent(() -> apigwv2.deleteApi(DeleteApiRequest.builder()
                        .apiId(apiId)
                        .build()));
            }
            apigwv2.close();
        }
    }

    @Test
    @Order(1)
    void createApi() {
        var response = apigwv2.createApi(CreateApiRequest.builder()
                .name(TestFixtures.uniqueName("http-api"))
                .protocolType(ProtocolType.HTTP)
                .build());

        apiId = response.apiId();

        assertThat(apiId).isNotBlank();
        assertThat(response.name()).startsWith("http-api-");
        assertThat(response.protocolType()).isEqualTo(ProtocolType.HTTP);
        assertThat(response.apiEndpoint()).contains(apiId + ".execute-api.us-east-1.amazonaws.com");
        assertThat(response.createdDate()).isNotNull();
    }

    @Test
    @Order(2)
    void getApi() {
        requireApi();
        var response = apigwv2.getApi(GetApiRequest.builder()
                .apiId(apiId)
                .build());

        assertThat(response.apiId()).isEqualTo(apiId);
        assertThat(response.protocolType()).isEqualTo(ProtocolType.HTTP);
    }

    @Test
    @Order(3)
    void listApis() {
        requireApi();
        GetApisResponse response = apigwv2.getApis();

        assertThat(response.items())
                .extracting(item -> item.apiId())
                .contains(apiId);
    }

    @Test
    @Order(4)
    void createAuthorizer() {
        requireApi();
        var response = apigwv2.createAuthorizer(CreateAuthorizerRequest.builder()
                .apiId(apiId)
                .name(TestFixtures.uniqueName("jwt-auth"))
                .authorizerType(AuthorizerType.JWT)
                .identitySource("$request.header.Authorization")
                .jwtConfiguration(jwt -> jwt
                        .issuer("https://issuer.example.com")
                        .audience(List.of("aud-1", "aud-2")))
                .build());

        authorizerId = response.authorizerId();

        assertThat(authorizerId).isNotBlank();
        assertThat(response.authorizerType()).isEqualTo(AuthorizerType.JWT);
        assertThat(response.identitySource()).containsExactly("$request.header.Authorization");
        assertThat(response.jwtConfiguration().issuer()).isEqualTo("https://issuer.example.com");
        assertThat(response.jwtConfiguration().audience()).containsExactly("aud-1", "aud-2");
    }

    @Test
    @Order(5)
    void getAndListAuthorizers() {
        requireApi();
        requireAuthorizer();
        var getResponse = apigwv2.getAuthorizer(GetAuthorizerRequest.builder()
                .apiId(apiId)
                .authorizerId(authorizerId)
                .build());

        assertThat(getResponse.authorizerId()).isEqualTo(authorizerId);

        var listResponse = apigwv2.getAuthorizers(GetAuthorizersRequest.builder()
                .apiId(apiId)
                .build());

        assertThat(listResponse.items())
                .extracting(item -> item.authorizerId())
                .contains(authorizerId);
    }

    @Test
    @Order(6)
    void createIntegration() {
        requireApi();
        var response = apigwv2.createIntegration(CreateIntegrationRequest.builder()
                .apiId(apiId)
                .integrationType(IntegrationType.AWS_PROXY)
                .integrationUri("arn:aws:lambda:us-east-1:000000000000:function:phase2-handler")
                .payloadFormatVersion("2.0")
                .build());

        integrationId = response.integrationId();

        assertThat(integrationId).isNotBlank();
        assertThat(response.integrationType()).isEqualTo(IntegrationType.AWS_PROXY);
        assertThat(response.integrationUri()).isEqualTo("arn:aws:lambda:us-east-1:000000000000:function:phase2-handler");
        assertThat(response.payloadFormatVersion()).isEqualTo("2.0");
    }

    @Test
    @Order(7)
    void getAndListIntegrations() {
        requireApi();
        requireIntegration();
        var getResponse = apigwv2.getIntegration(GetIntegrationRequest.builder()
                .apiId(apiId)
                .integrationId(integrationId)
                .build());

        assertThat(getResponse.integrationId()).isEqualTo(integrationId);

        var listResponse = apigwv2.getIntegrations(GetIntegrationsRequest.builder()
                .apiId(apiId)
                .build());

        assertThat(listResponse.items())
                .extracting(item -> item.integrationId())
                .contains(integrationId);
    }

    @Test
    @Order(8)
    void createRoute() {
        requireApi();
        requireAuthorizer();
        requireIntegration();
        var response = apigwv2.createRoute(CreateRouteRequest.builder()
                .apiId(apiId)
                .routeKey("GET /phase2")
                .authorizationType("JWT")
                .authorizerId(authorizerId)
                .target("integrations/" + integrationId)
                .build());

        routeId = response.routeId();

        assertThat(routeId).isNotBlank();
        assertThat(response.routeKey()).isEqualTo("GET /phase2");
        assertThat(response.authorizationTypeAsString()).isEqualTo("JWT");
        assertThat(response.target()).isEqualTo("integrations/" + integrationId);
    }

    @Test
    @Order(9)
    void getAndListRoutes() {
        requireApi();
        requireRoute();
        var getResponse = apigwv2.getRoute(GetRouteRequest.builder()
                .apiId(apiId)
                .routeId(routeId)
                .build());

        assertThat(getResponse.routeId()).isEqualTo(routeId);

        var listResponse = apigwv2.getRoutes(GetRoutesRequest.builder()
                .apiId(apiId)
                .build());

        assertThat(listResponse.items())
                .extracting(item -> item.routeId())
                .contains(routeId);
    }

    @Test
    @Order(10)
    void createDeployment() {
        requireApi();
        var response = apigwv2.createDeployment(CreateDeploymentRequest.builder()
                .apiId(apiId)
                .description("phase2 deployment")
                .build());

        deploymentId = response.deploymentId();

        assertThat(deploymentId).isNotBlank();
        assertThat(response.deploymentStatus()).isEqualTo("DEPLOYED");
        assertThat(response.description()).isEqualTo("phase2 deployment");
        assertThat(response.createdDate()).isNotNull();
    }

    @Test
    @Order(11)
    void getAndListDeployments() {
        requireApi();
        requireDeployment();
        var getResponse = apigwv2.getDeployment(GetDeploymentRequest.builder()
                .apiId(apiId)
                .deploymentId(deploymentId)
                .build());

        assertThat(getResponse.deploymentId()).isEqualTo(deploymentId);
        assertThat(getResponse.description()).isEqualTo("phase2 deployment");

        var listResponse = apigwv2.getDeployments(GetDeploymentsRequest.builder()
                .apiId(apiId)
                .build());

        assertThat(listResponse.items())
                .extracting(item -> item.deploymentId())
                .contains(deploymentId);
    }

    @Test
    @Order(12)
    void createStage() {
        requireApi();
        requireDeployment();
        var response = apigwv2.createStage(CreateStageRequest.builder()
                .apiId(apiId)
                .stageName(stageName)
                .deploymentId(deploymentId)
                .autoDeploy(false)
                .build());

        assertThat(response.stageName()).isEqualTo(stageName);
        assertThat(response.deploymentId()).isEqualTo(deploymentId);
        assertThat(response.autoDeploy()).isFalse();
        assertThat(response.createdDate()).isNotNull();
        assertThat(response.lastUpdatedDate()).isNotNull();
        stageCreated = true;
    }

    @Test
    @Order(13)
    void getAndListStages() {
        requireApi();
        requireStage();
        var getResponse = apigwv2.getStage(GetStageRequest.builder()
                .apiId(apiId)
                .stageName(stageName)
                .build());

        assertThat(getResponse.stageName()).isEqualTo(stageName);
        assertThat(getResponse.deploymentId()).isEqualTo(deploymentId);

        var listResponse = apigwv2.getStages(GetStagesRequest.builder()
                .apiId(apiId)
                .build());

        assertThat(listResponse.items())
                .extracting(item -> item.stageName())
                .contains(stageName);
        // Verify deploymentId is present in the list response
        assertThat(listResponse.items())
                .filteredOn(item -> stageName.equals(item.stageName()))
                .first()
                .extracting(item -> item.deploymentId())
                .isEqualTo(deploymentId);
    }

    @Test
    @Order(14)
    void deleteStage() {
        requireApi();
        requireStage();
        apigwv2.deleteStage(DeleteStageRequest.builder()
                .apiId(apiId)
                .stageName(stageName)
                .build());

        assertThatThrownBy(() -> apigwv2.getStage(GetStageRequest.builder()
                .apiId(apiId)
                .stageName(stageName)
                .build()))
                .isInstanceOf(NotFoundException.class);

        stageCreated = false;
    }

    @Test
    @Order(15)
    void deleteDeployment() {
        requireApi();
        requireDeployment();
        apigwv2.deleteDeployment(DeleteDeploymentRequest.builder()
                .apiId(apiId)
                .deploymentId(deploymentId)
                .build());

        assertThatThrownBy(() -> apigwv2.getDeployment(GetDeploymentRequest.builder()
                .apiId(apiId)
                .deploymentId(deploymentId)
                .build()))
                .isInstanceOf(NotFoundException.class);

        deploymentId = null;
    }

    @Test
    @Order(16)
    void deleteRoute() {
        requireApi();
        requireRoute();
        apigwv2.deleteRoute(DeleteRouteRequest.builder()
                .apiId(apiId)
                .routeId(routeId)
                .build());

        assertThatThrownBy(() -> apigwv2.getRoute(GetRouteRequest.builder()
                .apiId(apiId)
                .routeId(routeId)
                .build()))
                .isInstanceOf(NotFoundException.class);

        routeId = null;
    }

    @Test
    @Order(17)
    void deleteIntegration() {
        requireApi();
        requireIntegration();
        apigwv2.deleteIntegration(DeleteIntegrationRequest.builder()
                .apiId(apiId)
                .integrationId(integrationId)
                .build());

        assertThatThrownBy(() -> apigwv2.getIntegration(GetIntegrationRequest.builder()
                .apiId(apiId)
                .integrationId(integrationId)
                .build()))
                .isInstanceOf(NotFoundException.class);

        integrationId = null;
    }

    @Test
    @Order(18)
    void deleteAuthorizer() {
        requireApi();
        requireAuthorizer();
        apigwv2.deleteAuthorizer(DeleteAuthorizerRequest.builder()
                .apiId(apiId)
                .authorizerId(authorizerId)
                .build());

        assertThatThrownBy(() -> apigwv2.getAuthorizer(GetAuthorizerRequest.builder()
                .apiId(apiId)
                .authorizerId(authorizerId)
                .build()))
                .isInstanceOf(NotFoundException.class);

        authorizerId = null;
    }

    @Test
    @Order(19)
    void deleteApi() {
        requireApi();
        apigwv2.deleteApi(DeleteApiRequest.builder()
                .apiId(apiId)
                .build());

        assertThatThrownBy(() -> apigwv2.getApi(GetApiRequest.builder()
                .apiId(apiId)
                .build()))
                .isInstanceOf(NotFoundException.class);

        apiId = null;
    }

    private static void deleteIfPresent(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (NotFoundException ignored) {
        } catch (ApiGatewayV2Exception ignored) {
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }

    private static void requireApi() {
        Assumptions.assumeTrue(apiId != null, "API must exist from earlier ordered test");
    }

    private static void requireAuthorizer() {
        Assumptions.assumeTrue(authorizerId != null, "Authorizer must exist from earlier ordered test");
    }

    private static void requireIntegration() {
        Assumptions.assumeTrue(integrationId != null, "Integration must exist from earlier ordered test");
    }

    private static void requireRoute() {
        Assumptions.assumeTrue(routeId != null, "Route must exist from earlier ordered test");
    }

    private static void requireDeployment() {
        Assumptions.assumeTrue(deploymentId != null, "Deployment must exist from earlier ordered test");
    }

    private static void requireStage() {
        Assumptions.assumeTrue(stageCreated, "Stage must exist from earlier ordered test");
    }
}
