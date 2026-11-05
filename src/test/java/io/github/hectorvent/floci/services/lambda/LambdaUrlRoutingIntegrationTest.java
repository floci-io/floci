package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Exercises real HTTP routing and event construction, with the Lambda service mocked. */
@QuarkusTest
class LambdaUrlRoutingIntegrationTest {

    private static final String URL_ID = "encoded-path-url";
    private static final String HOST = URL_ID + ".lambda-url.us-east-1.localhost";
    private static final String FUNCTION_ARN = "arn:aws:lambda:us-east-1:000000000000:function:encoded-path";
    private static final String RAW_QUERY = "q=a%2525";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @InjectMock
    LambdaService lambdaService;

    @BeforeEach
    void echoInvocationEvent() {
        LambdaFunction function = new LambdaFunction();
        function.setFunctionName("encoded-path");
        function.setFunctionArn(FUNCTION_ARN);
        function.setAccountId("000000000000");
        when(lambdaService.getTargetByUrlId(URL_ID)).thenReturn(function);
        when(lambdaService.invokeArn(eq(FUNCTION_ARN), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(call -> {
                    String event = new String(call.getArgument(1, byte[].class), StandardCharsets.UTF_8);
                    byte[] payload = MAPPER.writeValueAsBytes(MAPPER.createObjectNode()
                            .put("statusCode", 200).put("body", event));
                    return new InvokeResult(200, null, payload, null, "encoded-path-request");
                });
    }

    @ParameterizedTest
    @CsvSource({
            "/, /",
            "/plain, /plain",
            "/x%25y, /x%y",
            "/trailing%25, /trailing%",
            "/a%252Fb, /a%2Fb",
            "/a%2Fb, /a/b",
            "/a%20b, /a b",
            "/a%3Fb%23c, /a?b#c",
            "/a+b, /a+b",
            "/caf%C3%A9, /café"
    })
    void hostRewriteMatchesPathStyleEventPaths(String path, String expectedPath) throws Exception {
        Response hosted = given().urlEncodingEnabled(false).header("Host", HOST)
                .when().get(path + "?" + RAW_QUERY);
        assertEvent(hosted, expectedPath, "GET");

        Response pathStyle = given().urlEncodingEnabled(false)
                .when().get("/lambda-url/" + URL_ID + path + "?" + RAW_QUERY);
        assertEvent(pathStyle, expectedPath, "GET");
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH"})
    void hostRewritePreservesPercentPathsForBodyMethods(String method) throws Exception {
        Response response = given().urlEncodingEnabled(false).header("Host", HOST)
                .contentType("text/plain").body("request body")
                .when().request(method, "/x%25y?" + RAW_QUERY);

        JsonNode event = assertEvent(response, "/x%y", method);
        assertEquals("request body", event.path("body").asText());
    }

    private static JsonNode assertEvent(Response response, String expectedPath, String method) throws Exception {
        assertEquals(200, response.statusCode(), response::asString);
        JsonNode event = MAPPER.readTree(response.asString());
        assertEquals(expectedPath, event.path("rawPath").asText());
        assertEquals(expectedPath, event.path("requestContext").path("http").path("path").asText());
        assertEquals(method, event.path("requestContext").path("http").path("method").asText());
        assertEquals(RAW_QUERY, event.path("rawQueryString").asText());
        assertEquals("a%25", event.path("queryStringParameters").path("q").asText());
        return event;
    }
}
