package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class LambdaProvisionedConcurrencyIntegrationTest {
    private static final String PATH = "/2019-09-30/functions/example/provisioned-concurrency";
    @InjectMock
    ProvisionedConcurrencyPool pool;

    @Test
    void putUsesTheDatedAwsRouteAndReturnsAcceptedAllocation() {
        when(pool.put(anyString(), eq("example"), eq("live"), eq(1), anyString()))
                .thenReturn(new ProvisionedConcurrencyPool.Status(1, 0, 0, "IN_PROGRESS", null,
                        "2026-01-01T00:00:00Z"));
        given().contentType("application/json").queryParam("Qualifier", "live")
                .body("{\"ProvisionedConcurrentExecutions\":1}").put(PATH).then().statusCode(202)
                .body("Status", equalTo("IN_PROGRESS"))
                .body("RequestedProvisionedConcurrentExecutions", equalTo(1))
                .body("AllocatedProvisionedConcurrentExecutions", equalTo(0))
                .body("AvailableProvisionedConcurrentExecutions", equalTo(0))
                .body("LastModified", equalTo("2026-01-01T00:00:00Z"));
    }

    @Test
    void getReportsAllocatedCapacityAndDeleteIsEmpty204() {
        when(pool.get(anyString(), eq("example"), eq("live"), anyString()))
                .thenReturn(new ProvisionedConcurrencyPool.Status(2, 2, 1, "READY", null,
                        "2026-01-01T00:00:00Z"));
        given().queryParam("Qualifier", "live").get(PATH).then().statusCode(200)
                .body("Status", equalTo("READY"))
                .body("RequestedProvisionedConcurrentExecutions", equalTo(2))
                .body("AllocatedProvisionedConcurrentExecutions", equalTo(2))
                .body("AvailableProvisionedConcurrentExecutions", equalTo(1));
        given().queryParam("Qualifier", "live").delete(PATH).then().statusCode(204).body(equalTo(""));
        verify(pool).delete(anyString(), eq("example"), eq("live"), anyString());
    }

    @Test
    void missingConfigurationUsesTheModeledAwsException() {
        when(pool.get(anyString(), eq("example"), eq("live"), anyString()))
                .thenThrow(new AwsException("ProvisionedConcurrencyConfigNotFoundException", "absent", 404));
        given().queryParam("Qualifier", "live").get(PATH).then().statusCode(404)
                .body("__type", equalTo("ProvisionedConcurrencyConfigNotFoundException"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{\"ProvisionedConcurrentExecutions\":true}",
            "{\"ProvisionedConcurrentExecutions\":\"1\"}",
            "{\"ProvisionedConcurrentExecutions\":0}", "{\"ProvisionedConcurrentExecutions\":-1}",
            "{\"ProvisionedConcurrentExecutions\":2147483648}"})
    void invalidCountsNeverReachTheAllocator(String body) {
        given().contentType("application/json").queryParam("Qualifier", "live").body(body)
                .put(PATH).then().statusCode(400);
        verify(pool, never()).put(any(), any(), any(), anyInt(), any());
    }

    @Test
    void decimalCountUsesAwsIntegerCoercion() {
        when(pool.put(anyString(), eq("example"), eq("live"), eq(1), anyString()))
                .thenReturn(new ProvisionedConcurrencyPool.Status(1, 0, 0, "IN_PROGRESS", null,
                        "2026-10-20T20:11:30+0000"));
        given().contentType("application/json").queryParam("Qualifier", "live")
                .body("{\"ProvisionedConcurrentExecutions\":1.5}").put(PATH).then().statusCode(202)
                .body("RequestedProvisionedConcurrentExecutions", equalTo(1))
                .body("LastModified", equalTo("2026-10-20T20:11:30+0000"));
        verify(pool).put(anyString(), eq("example"), eq("live"), eq(1), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"ProvisionedConcurrentExecutions\":null}"})
    void omittedAndNullCountsUseAwsValidation(String body) {
        given().contentType("application/json").queryParam("Qualifier", "live").body(body)
                .put(PATH).then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("1 validation error detected: Value null at 'provisionedConcurrentExecutions' "
                        + "failed to satisfy constraint: Member must not be null"));
        verify(pool, never()).put(any(), any(), any(), anyInt(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void nonpositiveCountsUseAwsValidation(int count) {
        given().contentType("application/json").queryParam("Qualifier", "live")
                .body("{\"ProvisionedConcurrentExecutions\":" + count + "}").put(PATH).then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
        verify(pool, never()).put(any(), any(), any(), anyInt(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "\"1\""})
    void incompatibleCountTypesUseAwsSerializationError(String value) {
        given().contentType("application/json").queryParam("Qualifier", "live")
                .body("{\"ProvisionedConcurrentExecutions\":" + value + "}").put(PATH).then().statusCode(400)
                .body("__type", equalTo("SerializationException"));
        verify(pool, never()).put(any(), any(), any(), anyInt(), any());
    }
}
