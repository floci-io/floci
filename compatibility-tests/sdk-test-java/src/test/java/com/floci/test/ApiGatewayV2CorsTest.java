package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;
import software.amazon.awssdk.services.apigatewayv2.model.AuthorizerType;
import software.amazon.awssdk.services.apigatewayv2.model.IntegrationType;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.Runtime;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An HTTP API's CORS configuration on the answers API Gateway gives itself, set up through the SDK.
 * Measured on AWS (eu-west-1, 2026-10-16 and 2026-10-18): a JWT authorizer's 401, an unsigned
 * AWS_IAM call's 403 and a throttled function's 503 carry Access-Control-Allow-Origin and
 * Access-Control-Expose-Headers for an allowed origin and no CORS header for another. A preflight
 * no route matches is answered 204 with the preflight's headers for an allowed origin and with none
 * for another, and so is an OPTIONS request that is not a preflight. That preflight always carries
 * Access-Control-Allow-Headers, empty when none are configured, and Vary: origin when the
 * configuration names two origins, none when it names one.
 */
@DisplayName("API Gateway v2 HTTP API CORS")
class ApiGatewayV2CorsTest {

    private static final String ORIGIN = "https://app.example.com";
    private static final String OTHER_ORIGIN = "https://evil.example";

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private static ApiGatewayV2Client apiGatewayV2;
    private static LambdaClient lambda;
    private static String throttledFunction;
    private static String apiId;
    private static String twoOriginsApiId;

    @BeforeAll
    static void setup() {
        apiGatewayV2 = TestFixtures.apiGatewayV2Client();
        apiId = apiGatewayV2.createApi(request -> request
                .name(TestFixtures.uniqueName("compat-http-api-cors"))
                .protocolType("HTTP")
                .corsConfiguration(cors -> cors
                        .allowOrigins(ORIGIN)
                        .allowMethods("GET")
                        .allowHeaders("authorization")
                        .exposeHeaders("x-probe")
                        .maxAge(300))).apiId();
        String integrationId = apiGatewayV2.createIntegration(request -> request
                .apiId(apiId)
                .integrationType(IntegrationType.HTTP_PROXY)
                .integrationMethod("GET")
                .integrationUri("https://example.com")
                .payloadFormatVersion("1.0")).integrationId();
        String authorizerId = apiGatewayV2.createAuthorizer(request -> request
                .apiId(apiId)
                .name("jwt")
                .authorizerType(AuthorizerType.JWT)
                .identitySource("$request.header.Authorization")
                .jwtConfiguration(jwt -> jwt.issuer("https://issuer.example.com").audience("cors"))).authorizerId();
        apiGatewayV2.createRoute(request -> request.apiId(apiId).routeKey("GET /jwt")
                .authorizationType("JWT").authorizerId(authorizerId).target("integrations/" + integrationId));
        apiGatewayV2.createRoute(request -> request.apiId(apiId).routeKey("GET /iam")
                .authorizationType("AWS_IAM").target("integrations/" + integrationId));

        lambda = TestFixtures.lambdaClient();
        throttledFunction = TestFixtures.uniqueName("compat-cors-throttled");
        String functionArn = lambda.createFunction(request -> request
                .functionName(throttledFunction)
                .runtime(Runtime.NODEJS20_X)
                .role("arn:aws:iam::000000000000:role/lambda-role")
                .handler("index.handler")
                .code(code -> code.zipFile(SdkBytes.fromByteArray(TestFixtures.probeZip())))).functionArn();
        lambda.putFunctionConcurrency(request -> request.functionName(throttledFunction)
                .reservedConcurrentExecutions(0));
        String lambdaIntegrationId = apiGatewayV2.createIntegration(request -> request
                .apiId(apiId)
                .integrationType(IntegrationType.AWS_PROXY)
                .integrationUri(functionArn)
                .payloadFormatVersion("2.0")).integrationId();
        apiGatewayV2.createRoute(request -> request.apiId(apiId).routeKey("GET /throttled")
                .target("integrations/" + lambdaIntegrationId));
        apiGatewayV2.createStage(request -> request.apiId(apiId).stageName("$default").autoDeploy(true));

        twoOriginsApiId = apiGatewayV2.createApi(request -> request
                .name(TestFixtures.uniqueName("compat-http-api-cors-two"))
                .protocolType("HTTP")
                .corsConfiguration(cors -> cors.allowOrigins(ORIGIN, "https://other.example.com").allowMethods("GET")))
                .apiId();
        apiGatewayV2.createStage(request -> request.apiId(twoOriginsApiId).stageName("$default").autoDeploy(true));
    }

    @AfterAll
    static void cleanup() {
        if (apiGatewayV2 != null) {
            if (apiId != null) {
                apiGatewayV2.deleteApi(request -> request.apiId(apiId));
            }
            if (twoOriginsApiId != null) {
                apiGatewayV2.deleteApi(request -> request.apiId(twoOriginsApiId));
            }
            apiGatewayV2.close();
        }
        if (lambda != null) {
            if (throttledFunction != null) {
                lambda.deleteFunction(request -> request.functionName(throttledFunction));
            }
            lambda.close();
        }
    }

    @Test
    @DisplayName("an authorizer's refusal carries the CORS headers for an allowed origin only")
    void anAuthorizersRefusalCarriesTheCorsHeaders() throws IOException, InterruptedException {
        HttpResponse<String> jwt = send(apiId, "GET", "/jwt", ORIGIN, null);
        assertThat(jwt.statusCode()).isEqualTo(401);
        assertThat(jwt.headers().firstValue("Access-Control-Allow-Origin")).contains(ORIGIN);
        assertThat(jwt.headers().firstValue("Access-Control-Expose-Headers")).contains("x-probe");

        HttpResponse<String> iam = send(apiId, "GET", "/iam", ORIGIN, null);
        assertThat(iam.statusCode()).isEqualTo(403);
        assertThat(iam.headers().firstValue("Access-Control-Allow-Origin")).contains(ORIGIN);
        assertThat(iam.headers().firstValue("Access-Control-Expose-Headers")).contains("x-probe");

        HttpResponse<String> other = send(apiId, "GET", "/jwt", OTHER_ORIGIN, null);
        assertThat(other.statusCode()).isEqualTo(401);
        assertThat(corsHeaderNames(other)).isEmpty();
    }

    @Test
    @DisplayName("a preflight no route matches gets the preflight's headers for an allowed origin only")
    void aPreflightNoRouteMatchesIsAnsweredFromTheConfiguration() throws IOException, InterruptedException {
        HttpResponse<String> allowed = send(apiId, "OPTIONS", "/anything", ORIGIN, "GET");
        assertThat(allowed.statusCode()).isEqualTo(204);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).contains(ORIGIN);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Methods")).contains("GET");
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Headers")).contains("authorization");
        assertThat(allowed.headers().firstValue("Access-Control-Max-Age")).contains("300");
        assertThat(allowed.headers().firstValue("Access-Control-Expose-Headers")).isEmpty();
        assertThat(allowed.headers().firstValue("Vary")).isEmpty();

        HttpResponse<String> other = send(apiId, "OPTIONS", "/anything", OTHER_ORIGIN, "GET");
        assertThat(other.statusCode()).isEqualTo(204);
        assertThat(corsHeaderNames(other)).isEmpty();

        HttpResponse<String> notAPreflight = send(apiId, "OPTIONS", "/anything", null, null);
        assertThat(notAPreflight.statusCode()).isEqualTo(204);
        assertThat(corsHeaderNames(notAPreflight)).isEmpty();
    }

    @Test
    @DisplayName("a throttled function's 503 carries the CORS headers")
    void aThrottledFunctionsAnswerCarriesTheCorsHeaders() throws IOException, InterruptedException {
        HttpResponse<String> throttled = send(apiId, "GET", "/throttled", ORIGIN, null);
        assertThat(throttled.statusCode()).isEqualTo(503);
        assertThat(throttled.body()).contains("Service Unavailable");
        assertThat(throttled.headers().firstValue("Access-Control-Allow-Origin")).contains(ORIGIN);
        assertThat(throttled.headers().firstValue("Access-Control-Expose-Headers")).contains("x-probe");
    }

    @Test
    @DisplayName("a preflight to an API with two origins varies by origin and names no headers")
    void aPreflightToAnApiWithTwoOriginsVariesByOrigin() throws IOException, InterruptedException {
        HttpResponse<String> preflight = send(twoOriginsApiId, "OPTIONS", "/anything", ORIGIN, "GET");
        assertThat(preflight.statusCode()).isEqualTo(204);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).contains(ORIGIN);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Methods")).contains("GET");
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Headers")).contains("");
        assertThat(preflight.headers().firstValue("Vary")).hasValueSatisfying(
                vary -> assertThat(vary).isEqualToIgnoringCase("origin"));
    }

    private static HttpResponse<String> send(String api, String method, String path, String origin,
                                             String requestMethod) throws IOException, InterruptedException {
        URI uri = URI.create(TestFixtures.endpoint() + "/execute-api/" + api + "/$default" + path);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (origin != null) {
            request.header("Origin", origin);
        }
        if (requestMethod != null) {
            request.header("Access-Control-Request-Method", requestMethod);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> corsHeaderNames(HttpResponse<String> response) {
        return response.headers().map().keySet().stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith("access-control-"))
                .toList();
    }
}
