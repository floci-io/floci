package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class LambdaInvokeClientContextIntegrationTest {

    private static final String CONTEXT_JSON = "{\"custom\":{\"traceparent\":\"00-abc-def-01\"}}";

    @InjectMock
    LambdaService lambdaService;


    private String encodedContext() {
        String pretty = "{ \"custom\": { \"traceparent\": \"00-abc-def-01\" } }";
        return Base64.getEncoder().encodeToString(pretty.getBytes(StandardCharsets.UTF_8));
    }

    private void stubInvoke() {
        when(lambdaService.invoke(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new InvokeResult(200, null, "{}".getBytes(), null, "req"));
    }

    @Test
    void requestResponse_validClientContext_isDecodedAndPassedToTheService() {
        stubInvoke();

        given()
                .header("X-Amz-Client-Context", encodedContext())
                .contentType("application/json")
                .body("{}")
                .when().post("/2015-03-31/functions/ctx-fn/invocations")
                .then().statusCode(200);

        verify(lambdaService).invoke(any(), eq("ctx-fn"), any(), any(),
                eq(InvocationType.RequestResponse), eq(CONTEXT_JSON), isNull());
    }

    @Test
    void event_clientContext_isNotPassedToTheService() {
        stubInvoke();

        given()
                .header("X-Amz-Invocation-Type", "Event")
                .header("X-Amz-Client-Context", encodedContext())
                .contentType("application/json")
                .body("{}")
                .when().post("/2015-03-31/functions/ctx-fn-event/invocations")
                .then().statusCode(200);

        verify(lambdaService).invoke(any(), eq("ctx-fn-event"), any(), any(),
                eq(InvocationType.Event), isNull(), isNull());
    }

    @Test
    void requestResponse_nonAsciiClientContext_isEscapedForTheHeader() {
        stubInvoke();
        String raw = "{\"custom\":{\"name\":\"café\"}}";

        given()
                .header("X-Amz-Client-Context",
                        Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)))
                .contentType("application/json")
                .body("{}")
                .when().post("/2015-03-31/functions/ctx-fn-utf/invocations")
                .then().statusCode(200);

        verify(lambdaService).invoke(any(), eq("ctx-fn-utf"), any(), any(),
                eq(InvocationType.RequestResponse), eq("{\"custom\":{\"name\":\"caf\\u00E9\"}}"), isNull());
    }
}
