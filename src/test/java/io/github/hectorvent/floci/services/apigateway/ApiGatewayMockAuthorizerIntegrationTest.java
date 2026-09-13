package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class ApiGatewayMockAuthorizerIntegrationTest {

    private static final String FUNCTION = "mock-vtl-authorizer";
    private static final String AUTHORIZER_URI = "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/"
            + "arn:aws:lambda:us-east-1:000000000000:function:" + FUNCTION + "/invocations";
    private static final String RESPONSE_TEMPLATE = """
            {"principalId":"$context.authorizer.principalId",
             "role":"$context.authorizer.role",
             "numberIsString":$context.authorizer.numberKey.equals('1'),
             "booleanIsString":$context.authorizer.booleanKey.equals('true')}
            """;

    @InjectMock
    LambdaService lambdaService;

    private String apiId;
    private String methodPath;

    @BeforeEach
    void createApi() {
        apiId = given().contentType(ContentType.JSON)
                .body(Map.of("name", "mock-authorizer-vtl"))
                .post("/restapis").then().statusCode(201).extract().path("id");
        String rootId = given().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");
        String resourceId = given().contentType(ContentType.JSON)
                .body(Map.of("pathPart", "claims"))
                .post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201).extract().path("id");
        methodPath = "/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET";
    }

    @AfterEach
    void deleteApi() {
        if (apiId != null) {
            given().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TOKEN", "REQUEST"})
    void requestTemplateSelectsResponseUsingAuthorizerClaims(String type) throws Exception {
        configureAuthorizer(type, "Allow");
        configureMock("""
                #if($context.authorizer.principalId == 'test-user'
                  && $context.authorizer.role == 'admin'
                  && $context.authorizer.numberKey.equals('1')
                  && $context.authorizer.booleanKey.equals('true'))
                {"statusCode":201}
                #else
                {"statusCode":500}
                #end
                """, RESPONSE_TEMPLATE);

        assertAuthorizerResponse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TOKEN", "REQUEST"})
    void responseTemplateReceivesAuthorizerClaims(String type) throws Exception {
        configureAuthorizer(type, "Allow");
        configureMock("{\"statusCode\":201}", RESPONSE_TEMPLATE);

        assertAuthorizerResponse();
    }

    @Test
    void deniedAuthorizerStillRejectsMockRequest() throws Exception {
        configureAuthorizer("TOKEN", "Deny");
        configureMock("{\"statusCode\":201}", "{\"mock\":true}");

        given().header("Authorization", "Bearer denied")
                .get("/execute-api/" + apiId + "/test/claims")
                .then().statusCode(403)
                .body(equalTo("{\"message\":\"User is not authorized to access this resource\"}"));
    }

    @Test
    void unauthenticatedMockDoesNotGainAnAuthorizerContext() {
        given().contentType(ContentType.JSON).body(Map.of("authorizationType", "NONE"))
                .put(methodPath).then().statusCode(201);
        configureMock("{\"statusCode\":201}",
                "#if($context.authorizer){\"hasAuthorizer\":true}#else{\"hasAuthorizer\":false}#end");

        given().get("/execute-api/" + apiId + "/test/claims")
                .then().statusCode(201).body("hasAuthorizer", equalTo(false));
        verify(lambdaService, never()).invoke(eq("us-east-1"), eq(FUNCTION), any(byte[].class),
                eq(InvocationType.RequestResponse));
    }

    private void configureAuthorizer(String type, String effect) throws Exception {
        String authorizerId = given().contentType(ContentType.JSON)
                .body(Map.of("name", "claims", "type", type, "authorizerUri", AUTHORIZER_URI,
                        "identitySource", "method.request.header.Authorization", "authorizerResultTtlInSeconds", 0))
                .post("/restapis/" + apiId + "/authorizers")
                .then().statusCode(201).extract().path("id");
        given().contentType(ContentType.JSON)
                .body(Map.of("authorizationType", "CUSTOM", "authorizerId", authorizerId))
                .put(methodPath).then().statusCode(201);

        byte[] payload = new ObjectMapper().writeValueAsBytes(Map.of(
                "principalId", "test-user",
                "policyDocument", Map.of("Version", "2012-10-17", "Statement", List.of(Map.of(
                        "Action", "execute-api:Invoke", "Effect", effect, "Resource", "*"))),
                "context", Map.of("principalId", "custom-principal", "role", "admin",
                        "numberKey", 1, "booleanKey", true)));
        when(lambdaService.invoke(eq("us-east-1"), eq(FUNCTION), any(byte[].class),
                eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, null, payload, null, "authorizer-request"));
    }

    private void configureMock(String requestTemplate, String responseTemplate) {
        given().contentType(ContentType.JSON)
                .body(Map.of("type", "MOCK", "requestTemplates", Map.of("application/json", requestTemplate)))
                .put(methodPath + "/integration").then().statusCode(201);
        for (String status : List.of("201", "500")) {
            given().contentType(ContentType.JSON).body(Map.of())
                    .put(methodPath + "/responses/" + status).then().statusCode(201);
            given().contentType(ContentType.JSON)
                    .body(Map.of("selectionPattern", status, "responseTemplates", Map.of(
                            "application/json", "201".equals(status) ? responseTemplate : "{\"error\":\"claims missing\"}")))
                    .put(methodPath + "/integration/responses/" + status).then().statusCode(201);
        }
        given().contentType(ContentType.JSON).body(Map.of("stageName", "test"))
                .post("/restapis/" + apiId + "/deployments").then().statusCode(201);
    }

    private void assertAuthorizerResponse() {
        for (String endpoint : List.of(
                "/execute-api/" + apiId + "/test/claims",
                "/restapis/" + apiId + "/test/_user_request_/claims")) {
            given().header("Authorization", "Bearer allowed")
                    .get(endpoint)
                    .then().statusCode(201)
                    .body("principalId", equalTo("test-user"))
                    .body("role", equalTo("admin"))
                    .body("numberIsString", equalTo(true))
                    .body("booleanIsString", equalTo(true));
        }
    }
}
