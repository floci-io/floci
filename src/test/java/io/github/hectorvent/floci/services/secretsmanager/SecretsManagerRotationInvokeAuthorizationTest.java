package io.github.hectorvent.floci.services.secretsmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The hook RotateSecret uses to refuse a caller {@code lambda:InvokeFunction} on the rotation
 * function (Secrets Manager API Reference, RotateSecret, "Required permissions").
 */
class SecretsManagerRotationInvokeAuthorizationTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String TOKEN = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
    private static final String FUNCTION_ARN = "arn:aws:lambda:us-east-1:000000000000:function:rotator";

    private InMemoryStorage<String, Secret> store;
    private LambdaService lambda;
    private SecretsManagerService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryStorage<>();
        lambda = mock(LambdaService.class);
        when(lambda.getFunction(anyString(), anyString())).thenReturn(new LambdaFunction());
        when(lambda.invoke(anyString(), anyString(), any(byte[].class), any())).thenReturn(new InvokeResult());
        service = new SecretsManagerService(store, 30, new RegionResolver(REGION, ACCOUNT), lambda, new ObjectMapper());
        service.createSecret("my-secret", "v1", null, null, null, null, REGION);
    }

    @ParameterizedTest
    @CsvSource({
            "rotator, arn:aws:lambda:us-east-1:000000000000:function:rotator",
            "rotator:live, arn:aws:lambda:us-east-1:000000000000:function:rotator:live",
            "111122223333:function:rotator, arn:aws:lambda:us-east-1:111122223333:function:rotator",
            "arn:aws:lambda:us-east-1:111122223333:function:rotator, arn:aws:lambda:us-east-1:111122223333:function:rotator"
    })
    void checkAndInvokeUseTheSameFullArn(String given, String expected) {
        List<String> checked = new ArrayList<>();

        service.rotateSecret("my-secret", TOKEN, given, null, false, REGION, checked::add);

        assertEquals(List.of(expected), checked);
        // Invoked by that ARN, not by the reference as given: Lambda resolves a name or partial ARN
        // in the ambient account, which on the rotation thread is not the one that was checked.
        verify(lambda, timeout(5000).atLeastOnce()).invoke(eq(REGION), eq(expected), any(byte[].class), any());
    }

    @Test
    void refusedCallerLeavesTheSecretUntouchedAndNothingRuns() {
        Consumer<String> refuse = functionArn -> {
            throw new AwsException("AccessDeniedException", "refused by the caller check", 400);
        };

        AwsException e = assertThrows(AwsException.class, () ->
                service.rotateSecret("my-secret", TOKEN, FUNCTION_ARN, new Secret.RotationRules(7, null, null),
                        true, REGION, refuse));

        assertEquals("AccessDeniedException", e.getErrorCode());
        Secret secret = service.describeSecret("my-secret", REGION);
        // A refused call must not leave the function configured: the scheduled sweep would
        // otherwise invoke it later with no caller left to check.
        assertNull(secret.getRotationLambdaArn());
        assertFalse(secret.isRotationEnabled());
        assertNull(secret.getNextRotationDate());
        verify(lambda, never()).invoke(anyString(), anyString(), any(byte[].class), any());
    }

    @Test
    void refusalComesBeforeTheExistenceCheck() {
        when(lambda.getFunction(anyString(), anyString()))
                .thenThrow(new AwsException("ResourceNotFoundException", "Function not found", 404));
        Consumer<String> refuse = functionArn -> {
            throw new AwsException("AccessDeniedException", "refused by the caller check", 400);
        };

        AwsException e = assertThrows(AwsException.class, () ->
                service.rotateSecret("my-secret", TOKEN, FUNCTION_ARN, null, true, REGION, refuse));

        assertEquals("refused by the caller check", e.getMessage());
    }

    @Test
    void missingFunctionIsReportedAsSecretsManagerReportsIt() {
        when(lambda.getFunction(anyString(), anyString()))
                .thenThrow(new AwsException("ResourceNotFoundException", "Function not found", 404));

        AwsException e = assertThrows(AwsException.class, () ->
                service.rotateSecret("my-secret", TOKEN, FUNCTION_ARN, null, true, REGION, functionArn -> { }));

        assertEquals("AccessDeniedException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        assertEquals("Secrets Manager cannot invoke the specified Lambda function. Ensure that the function "
                + "policy grants access to the principal secretsmanager.amazonaws.com.", e.getMessage());
        assertNull(service.describeSecret("my-secret", REGION).getRotationLambdaArn());
    }

    @Test
    void missingFunctionLeavesThePreviouslyConfiguredFunctionInPlace() {
        Secret secret = service.describeSecret("my-secret", REGION);
        secret.setRotationLambdaArn(FUNCTION_ARN);
        store.put(REGION + "::my-secret", secret);
        when(lambda.getFunction(anyString(), anyString()))
                .thenThrow(new AwsException("ResourceNotFoundException", "Function not found", 404));

        assertThrows(AwsException.class, () -> service.rotateSecret("my-secret", TOKEN,
                "arn:aws:lambda:us-east-1:000000000000:function:missing", null, true, REGION, functionArn -> { }));

        assertEquals(FUNCTION_ARN, service.describeSecret("my-secret", REGION).getRotationLambdaArn());
    }

    @Test
    void rotationWithoutAnArnChecksTheConfiguredFunction() {
        Secret secret = service.describeSecret("my-secret", REGION);
        secret.setRotationLambdaArn(FUNCTION_ARN);
        store.put(REGION + "::my-secret", secret);
        List<String> checked = new ArrayList<>();

        service.rotateSecret("my-secret", TOKEN, null, null, false, REGION, checked::add);

        assertEquals(List.of(FUNCTION_ARN), checked);
    }
}
