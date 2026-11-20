package io.floci.aws.services.lambda;

import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.lambda.model.InvokeResult;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class LambdaInvokeLogTailIntegrationTest {

    @InjectMock
    LambdaService lambdaService;

    @ParameterizedTest
    @ValueSource(strings = {"RequestResponse", "default"})
    void aSynchronousTailRequestReturnsTheEncodedHeader(String invocationType) {
        when(lambdaService.invoke(any(), any(), any(), any(), any(), any(), any(), any(), eq(true)))
                .thenReturn(result(200, "aGVsbG8K"));

        given()
                .header("X-Amz-Invocation-Type", "default".equals(invocationType) ? "" : invocationType)
                .header("X-Amz-Log-Type", "Tail")
                .body("{}")
        .when()
                .post("/2015-03-31/functions/log-tail/invocations")
        .then()
                .statusCode(200)
                .header("X-Amz-Log-Result", equalTo("aGVsbG8K"));

        verify(lambdaService).invoke(any(), eq("log-tail"), any(), any(),
                eq(InvocationType.RequestResponse), any(), any(), any(), eq(true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Event", "DryRun"})
    void nonSynchronousInvocationsIgnoreTail(String invocationType) {
        int status = "Event".equals(invocationType) ? 202 : 204;
        when(lambdaService.invoke(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(result(status, "should-not-be-exposed"));

        given()
                .header("X-Amz-Invocation-Type", invocationType)
                .header("X-Amz-Log-Type", "Tail")
                .body("{}")
        .when()
                .post("/2015-03-31/functions/log-tail/invocations")
        .then()
                .statusCode(status)
                .header("X-Amz-Log-Result", nullValue());
    }

    private static InvokeResult result(int status, String logs) {
        return new InvokeResult(status, null, "{}".getBytes(StandardCharsets.UTF_8), logs, "request-id");
    }
}
