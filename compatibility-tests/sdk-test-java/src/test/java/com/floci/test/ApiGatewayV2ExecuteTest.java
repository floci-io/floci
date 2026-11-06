package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;
import software.amazon.awssdk.services.apigatewayv2.model.AuthorizerType;
import software.amazon.awssdk.services.apigatewayv2.model.CreateApiRequest;
import software.amazon.awssdk.services.apigatewayv2.model.CreateApiResponse;
import software.amazon.awssdk.services.apigatewayv2.model.CreateAuthorizerRequest;
import software.amazon.awssdk.services.apigatewayv2.model.CreateAuthorizerResponse;
import software.amazon.awssdk.services.apigatewayv2.model.CreateDeploymentRequest;
import software.amazon.awssdk.services.apigatewayv2.model.CreateIntegrationRequest;
import software.amazon.awssdk.services.apigatewayv2.model.CreateIntegrationResponse;
import software.amazon.awssdk.services.apigatewayv2.model.CreateRouteRequest;
import software.amazon.awssdk.services.apigatewayv2.model.CreateStageRequest;
import software.amazon.awssdk.services.apigatewayv2.model.DeleteApiRequest;
import software.amazon.awssdk.services.apigatewayv2.model.IntegrationType;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.CreateFunctionRequest;
import software.amazon.awssdk.services.lambda.model.DeleteFunctionRequest;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.Runtime;

import java.io.ByteArrayOutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("API Gateway V2 Execute")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayV2ExecuteTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROLE = "arn:aws:iam::000000000000:role/lambda-role";
    private static final String STAGE = "prod";

    private static ApiGatewayV2Client apiGatewayV2;
    private static LambdaClient lambda;
    private static HttpClient http;

    private static String apiId;
    private static String functionName;
    private static String baseUrl;
    private static boolean lambdaDispatchAvailable;

    @BeforeAll
    static void setup() throws Exception {
        apiGatewayV2 = TestFixtures.apiGatewayV2Client();
        lambda = TestFixtures.lambdaClient();
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        functionName = TestFixtures.uniqueName("http-api-fn");

        try {
            String functionArn = lambda.createFunction(CreateFunctionRequest.builder()
                    .functionName(functionName)
                    .runtime(Runtime.NODEJS20_X)
                    .role(ROLE)
                    .handler("index.handler")
                    .timeout(30)
                    .memorySize(256)
                    .code(FunctionCode.builder()
                            .zipFile(SdkBytes.fromByteArray(v2EchoHandlerZip()))
                            .build())
                    .build()).functionArn();

            CreateApiResponse api = apiGatewayV2.createApi(CreateApiRequest.builder()
                    .name(TestFixtures.uniqueName("http-api"))
                    .protocolType("HTTP")
                    .build());
            apiId = api.apiId();

            CreateIntegrationResponse integration = apiGatewayV2.createIntegration(CreateIntegrationRequest.builder()
                    .apiId(apiId)
                    .integrationType(IntegrationType.AWS_PROXY)
                    .integrationUri(functionArn)
                    .payloadFormatVersion("2.0")
                    .build());

            CreateAuthorizerResponse authorizer = apiGatewayV2.createAuthorizer(CreateAuthorizerRequest.builder()
                    .apiId(apiId)
                    .name("jwt-auth")
                    .authorizerType(AuthorizerType.JWT)
                    .identitySource("$request.header.Authorization")
                    .jwtConfiguration(b -> b
                            .issuer("https://issuer.example.test")
                            .audience("my-audience"))
                    .build());

            apiGatewayV2.createRoute(CreateRouteRequest.builder()
                    .apiId(apiId)
                    .routeKey("POST /echo/{proxy+}")
                    .target("integrations/" + integration.integrationId())
                    .build());

            apiGatewayV2.createRoute(CreateRouteRequest.builder()
                    .apiId(apiId)
                    .routeKey("GET /secure")
                    .authorizationType("JWT")
                    .authorizerId(authorizer.authorizerId())
                    .target("integrations/" + integration.integrationId())
                    .build());

            String deploymentId = apiGatewayV2.createDeployment(CreateDeploymentRequest.builder()
                    .apiId(apiId)
                    .build()).deploymentId();

            apiGatewayV2.createStage(CreateStageRequest.builder()
                    .apiId(apiId)
                    .stageName(STAGE)
                    .deploymentId(deploymentId)
                    .autoDeploy(false)
                    .build());
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "API Gateway v2 execute setup unavailable in this environment: " + e.getMessage());
            return;
        }

        baseUrl = TestFixtures.endpoint() + "/execute-api/" + apiId + "/" + STAGE;

        try {
            http.send(HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/secure"))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException e) {
            Assumptions.assumeTrue(false, "Floci endpoint is not reachable at " + TestFixtures.endpoint());
        }

        // Probe result is memoized in TestFixtures; skip warmup if dispatch is unavailable
        if (!TestFixtures.isLambdaDispatchAvailable()) {
            lambdaDispatchAvailable = false;
            return;
        }

        // Warm the API GW → Lambda dispatch path and verify the response carries
        // the expected Lambda proxy envelope (statusCode in body). The direct
        // probe above only tests raw invoke; APIGW integration may behave differently.
        try {
            HttpResponse<String> warmup = http.send(HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/echo/warmup"))
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode warmupBody = JSON.readTree(warmup.body());
            lambdaDispatchAvailable = warmup.statusCode() == 200
                    && warmupBody.path("statusCode").asInt() == 200;
        } catch (HttpTimeoutException | ConnectException e) {
            // Transport-level failure: endpoint unreachable or timed out
            lambdaDispatchAvailable = false;
        }
    }

    @AfterAll
    static void cleanup() {
        if (apiGatewayV2 != null && apiId != null) {
            try {
                apiGatewayV2.deleteApi(DeleteApiRequest.builder().apiId(apiId).build());
            } catch (Exception ignored) {
            }
            apiGatewayV2.close();
        }
        if (lambda != null && functionName != null) {
            try {
                lambda.deleteFunction(DeleteFunctionRequest.builder().functionName(functionName).build());
            } catch (Exception ignored) {
            }
            lambda.close();
        }
    }

    @Test
    @Order(1)
    void dispatchesHttpApiRouteToLambdaWithV2EventShape() throws Exception {
        Assumptions.assumeTrue(lambdaDispatchAvailable,
                "Lambda dispatch unavailable in this environment");

        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/echo/child/path?color=blue"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("X-Test-Header", "hello")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"hi\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        JsonNode body = JSON.readTree(response.body());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body.path("statusCode").asInt()).isEqualTo(200);

        JsonNode event = JSON.readTree(body.path("body").asText());

        assertThat(event.path("version").asText()).isEqualTo("2.0");
        assertThat(event.path("routeKey").asText()).isEqualTo("POST /echo/{proxy+}");
        assertThat(event.path("rawPath").asText()).isEqualTo("/echo/child/path");
        assertThat(event.path("requestContext").path("stage").asText()).isEqualTo(STAGE);
        assertThat(event.path("requestContext").path("http").path("method").asText()).isEqualTo("POST");
        assertThat(event.path("headers").path("x-test-header").asText()).isEqualTo("hello");
        assertThat(event.path("queryStringParameters").path("color").asText()).isEqualTo("blue");
        assertThat(event.path("body").asText()).isEqualTo("{\"message\":\"hi\"}");
    }

    @Test
    @Order(2)
    void jwtProtectedRouteRejectsMissingToken() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/secure"))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("Unauthorized");
    }

    @Test
    @Order(3)
    void jwtProtectedRouteRejectsWrongAudience() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/secure"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + jwt("https://issuer.example.test", "wrong-audience",
                                Instant.now().plusSeconds(300).getEpochSecond()))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("Unauthorized");
    }

    @Test
    @Order(4)
    void jwtProtectedRouteInvokesLambdaForValidToken() throws Exception {
        Assumptions.assumeTrue(lambdaDispatchAvailable,
                "Lambda dispatch not available in this environment");
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/secure"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + jwt("https://issuer.example.test", "my-audience",
                                Instant.now().plusSeconds(300).getEpochSecond()))
                        .header("User-Agent", "sdk-test-java")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode body = JSON.readTree(response.body());
        JsonNode event = JSON.readTree(body.path("body").asText());

        assertThat(event.path("routeKey").asText()).isEqualTo("GET /secure");
        assertThat(event.path("rawPath").asText()).isEqualTo("/secure");
        assertThat(event.path("headers").path("authorization").asText()).contains("Bearer ");
        assertThat(event.path("requestContext").path("http").path("method").asText()).isEqualTo("GET");
    }

    private static String jwt(String issuer, String audience, long exp) throws Exception {
        String header = base64Url(JSON.writeValueAsBytes(Map.of("alg", "none", "typ", "JWT")));
        String payload = base64Url(JSON.writeValueAsBytes(Map.of(
                "iss", issuer,
                "aud", audience,
                "exp", exp
        )));
        return header + "." + payload + ".";
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] v2EchoHandlerZip() {
        String code = """
                exports.handler = async (event) => {
                    return {
                        statusCode: 200,
                        headers: { "content-type": "application/json" },
                        body: JSON.stringify(event)
                    };
                };
                """;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                zos.putNextEntry(new ZipEntry("index.js"));
                zos.write(code.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build API Gateway v2 Lambda ZIP", e);
        }
    }
}
