package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;
import software.amazon.awssdk.services.apigatewayv2.model.IntegrationType;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.Runtime;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How an HTTP API answers the Lambda proxy results it cannot read, in payload formats 1.0 and 2.0.
 * Measured on AWS (eu-west-1, 2026-10-19): a function that raises, a 1.0 result without a
 * statusCode, and a statusCode that is not a whole number from 100 to 599 are answered 500 with
 * {"message":"Internal Server Error"}; a statusCode given as text that spells one is taken; format
 * 2.0 takes a result without a statusCode as a 200 whose body is the result itself. A body that is
 * an object, one said to be base64 that is not, and an isBase64Encoded of "yes" are answered 500
 * too, while "True" is read as true. A result with a body and no Content-Type header of its own is
 * answered text/plain; charset=utf-8.
 */
@DisplayName("API Gateway V2 Lambda proxy responses")
class ApiGatewayV2LambdaProxyResponseTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/lambda-role";
    private static final String ERROR = "{\"message\":\"Internal Server Error\"}";
    private static final String STAGE = "test";

    private static ApiGatewayV2Client apiGatewayV2;
    private static LambdaClient lambda;
    private static HttpClient http;
    private static String functionName;
    private static String apiId;

    @BeforeAll
    static void setup() {
        Assumptions.assumeTrue(TestFixtures.isLambdaDispatchAvailable(), "Lambda dispatch is unavailable here");
        apiGatewayV2 = TestFixtures.apiGatewayV2Client();
        lambda = TestFixtures.lambdaClient();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        functionName = TestFixtures.uniqueName("http-api-proxy-fn");

        String functionArn = lambda.createFunction(request -> request
                .functionName(functionName)
                .runtime(Runtime.NODEJS20_X)
                .role(ROLE)
                .handler("index.handler")
                .timeout(30)
                .code(code -> code.zipFile(SdkBytes.fromByteArray(LambdaUtils.proxyResponseZip())))).functionArn();
        apiId = apiGatewayV2.createApi(request -> request
                .name(TestFixtures.uniqueName("http-api-proxy"))
                .protocolType("HTTP")).apiId();
        for (String format : new String[] {"1.0", "2.0"}) {
            String integrationId = apiGatewayV2.createIntegration(request -> request
                    .apiId(apiId)
                    .integrationType(IntegrationType.AWS_PROXY)
                    .integrationUri(functionArn)
                    .payloadFormatVersion(format)).integrationId();
            apiGatewayV2.createRoute(request -> request
                    .apiId(apiId)
                    .routeKey("GET /v" + format.charAt(0) + "/{mode}")
                    .target("integrations/" + integrationId));
        }
        apiGatewayV2.createStage(request -> request.apiId(apiId).stageName(STAGE).autoDeploy(true));

        // As ApiGatewayV2ExecuteTest does: a result the gateway can read must come back through the
        // API before the cases run, or this environment cannot dispatch from API Gateway to Lambda.
        boolean dispatches;
        try {
            HttpResponse<String> warmup = get("2.0", "ok");
            dispatches = warmup.statusCode() == 200 && "ok".equals(warmup.body());
        } catch (IOException e) {
            dispatches = false;
        }
        Assumptions.assumeTrue(dispatches, "API Gateway does not dispatch to Lambda here");
    }

    @AfterAll
    static void cleanup() {
        if (apiGatewayV2 != null) {
            if (apiId != null) {
                apiGatewayV2.deleteApi(request -> request.apiId(apiId));
            }
            apiGatewayV2.close();
        }
        if (lambda != null) {
            lambda.deleteFunction(request -> request.functionName(functionName));
            lambda.close();
        }
    }

    @ParameterizedTest(name = "{1} in payload {0}")
    @CsvSource(delimiter = '|', value = {
        "1.0|raise|500|" + ERROR,
        "2.0|raise|500|" + ERROR,
        "1.0|nostatus|500|" + ERROR,
        "1.0|badstatus|500|" + ERROR,
        "2.0|badstatus|500|" + ERROR,
        "1.0|highstatus|500|" + ERROR,
        "2.0|highstatus|500|" + ERROR,
        "1.0|textstatus|201|x",
        "2.0|textstatus|201|x",
        "2.0|nostatus|200|{\"body\":\"x\",\"headers\":{\"x-probe\":\"1\"}}",
        "1.0|badbase64|500|" + ERROR,
        "2.0|badbase64|500|" + ERROR,
        "1.0|bodyobject|500|" + ERROR,
        "2.0|bodyobject|500|" + ERROR,
        "1.0|base64yes|500|" + ERROR,
        "2.0|base64yes|500|" + ERROR,
        "1.0|base64capital|200|hello",
        "2.0|base64capital|200|hello"
    })
    void aResultIsAnsweredAsAwsReadsIt(String format, String mode, int status, String body) throws Exception {
        HttpResponse<String> response = get(format, mode);

        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).isEqualTo(body);
    }

    @ParameterizedTest(name = "payload {0}")
    @ValueSource(strings = {"1.0", "2.0"})
    void aResultWithoutAContentTypeIsAnsweredAsTextPlain(String format) throws Exception {
        // Measured on AWS (eu-west-1, 2026-10-19): a result with a statusCode and a body but no
        // Content-Type header of its own is answered text/plain; charset=utf-8, in both formats.
        HttpResponse<String> response = get(format, "ok");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(contentType -> {
                    assertThat(contentType).startsWith("text/plain");
                    assertThat(contentType.toLowerCase()).contains("charset=utf-8");
                });
    }

    private static HttpResponse<String> get(String format, String mode) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TestFixtures.endpoint() + "/execute-api/" + apiId + "/" + STAGE + "/v"
                        + format.charAt(0) + "/" + mode))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for " + request.uri(), e);
        }
    }
}
