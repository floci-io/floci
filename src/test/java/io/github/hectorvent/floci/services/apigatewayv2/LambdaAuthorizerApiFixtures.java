package io.github.hectorvent.floci.services.apigatewayv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.restassured.http.ContentType;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

final class LambdaAuthorizerApiFixtures {

    static final String REGION = "us-east-1";
    static final String AUTHORIZER = "arn:aws:lambda:us-east-1:000000000000:function:fixture-authorizer";
    static final String BACKEND = "arn:aws:lambda:us-east-1:000000000000:function:fixture-backend";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LambdaAuthorizerApiFixtures() {}

    static String createApi(String protocol, String stageName, Map<String, String> stageVariables,
                            Map<String, Object> authorizerFields) {
        String apiId = given().contentType(ContentType.JSON)
                .body(Map.of("name", "lambda-authorizer", "protocolType", protocol,
                        "routeSelectionExpression", "$request.body.action"))
                .post("/v2/apis").then().statusCode(201).extract().path("apiId");
        String apiPath = "/v2/apis/" + apiId;
        given().contentType(ContentType.JSON).body(Map.of("stageName", stageName, "stageVariables", stageVariables))
                .post(apiPath + "/stages").then().statusCode(201);
        Map<String, Object> authorizer = new LinkedHashMap<>(authorizerFields);
        authorizer.put("name", "fixture-authorizer");
        authorizer.put("authorizerType", "REQUEST");
        authorizer.put("authorizerUri", AUTHORIZER);
        String authorizerId = given().contentType(ContentType.JSON).body(authorizer)
                .post(apiPath + "/authorizers").then().statusCode(201).extract().path("authorizerId");
        boolean webSocket = "WEBSOCKET".equals(protocol);
        Map<String, String> integration = webSocket ? Map.of("integrationType", "MOCK")
                : Map.of("integrationType", "AWS_PROXY", "payloadFormatVersion", "2.0", "integrationUri", BACKEND);
        String integrationId = given().contentType(ContentType.JSON).body(integration)
                .post(apiPath + "/integrations").then().statusCode(201).extract().path("integrationId");
        given().contentType(ContentType.JSON)
                .body(Map.of("routeKey", webSocket ? "$connect" : "GET /items", "authorizationType", "CUSTOM",
                        "authorizerId", authorizerId, "target", "integrations/" + integrationId))
                .post(apiPath + "/routes").then().statusCode(201);
        return apiId;
    }

    static void stubAuthorizer(LambdaService lambdaService, Function<JsonNode, InvokeResult> response) {
        when(lambdaService.invokeTarget(eq(REGION), eq(AUTHORIZER), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> response.apply(MAPPER.readTree((byte[]) invocation.getArgument(2))));
    }

    static void stubBackend(LambdaService lambdaService) {
        when(lambdaService.invokeTarget(eq(REGION), eq(BACKEND), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(result("{\"statusCode\":200,\"body\":\"allowed\"}"));
    }

    static InvokeResult result(String payload) {
        return new InvokeResult(200, null, payload.getBytes(StandardCharsets.UTF_8), null, "request");
    }
}
