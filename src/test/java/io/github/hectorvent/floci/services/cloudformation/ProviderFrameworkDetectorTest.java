package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The two-phase CDK Provider framework is recognised from the {@code framework.onEvent} Lambda's
 * environment: only that shape PUTs asynchronously, and only it earns the longer idle budget.
 */
class ProviderFrameworkDetectorTest {

    private static final String REGION = "us-east-1";
    private static final String SERVICE_TOKEN =
            "arn:aws:lambda:us-east-1:000000000000:function:framework-onEvent";
    private static final Duration SYNCHRONOUS = Duration.ofSeconds(10);

    private LambdaService lambdaService;
    private ProviderFrameworkDetector detector;

    @BeforeEach
    void setUp() {
        lambdaService = mock(LambdaService.class);
        detector = new ProviderFrameworkDetector(lambdaService);
    }

    private void stubEnvironment(Map<String, String> env) {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("framework-onEvent");
        fn.setFunctionArn(SERVICE_TOKEN);
        fn.setEnvironment(env);
        when(lambdaService.getFunction(eq(REGION), eq(SERVICE_TOKEN))).thenReturn(fn);
    }

    private Map<String, String> frameworkEnv() {
        return Map.of(
                "USER_ON_EVENT_FUNCTION_ARN", "arn:aws:lambda:us-east-1:000000000000:function:onEvent",
                "USER_IS_COMPLETE_FUNCTION_ARN", "arn:aws:lambda:us-east-1:000000000000:function:isComplete",
                "WAITER_STATE_MACHINE_ARN", "arn:aws:states:us-east-1:000000000000:stateMachine:waiter");
    }

    @Test
    void bothWaiterMarkersPresentMeansProviderFramework() {
        stubEnvironment(frameworkEnv());

        assertTrue(detector.isProviderFrameworkOnEvent(SERVICE_TOKEN, REGION));
    }

    @Test
    void onEventOnlyProviderIsNotProviderFramework() {
        stubEnvironment(Map.of(
                "USER_ON_EVENT_FUNCTION_ARN", "arn:aws:lambda:us-east-1:000000000000:function:onEvent"));

        assertFalse(detector.isProviderFrameworkOnEvent(SERVICE_TOKEN, REGION));
    }

    @Test
    void isCompleteWithoutWaiterIsNotProviderFramework() {
        stubEnvironment(Map.of(
                "USER_IS_COMPLETE_FUNCTION_ARN", "arn:aws:lambda:us-east-1:000000000000:function:isComplete"));

        assertFalse(detector.isProviderFrameworkOnEvent(SERVICE_TOKEN, REGION));
    }

    @Test
    void blankMarkerIsNotProviderFramework() {
        stubEnvironment(Map.of(
                "USER_IS_COMPLETE_FUNCTION_ARN", "arn:aws:lambda:us-east-1:000000000000:function:isComplete",
                "WAITER_STATE_MACHINE_ARN", "  "));

        assertFalse(detector.isProviderFrameworkOnEvent(SERVICE_TOKEN, REGION));
    }

    @Test
    void functionWithNoEnvironmentIsNotProviderFramework() {
        stubEnvironment(null);

        assertFalse(detector.isProviderFrameworkOnEvent(SERVICE_TOKEN, REGION));
    }

    /** Detection is best-effort: an unresolvable ServiceToken must not fail the resource. */
    @Test
    void unresolvableFunctionIsTreatedAsSynchronousHandler() {
        when(lambdaService.getFunction(any(), any()))
                .thenThrow(new RuntimeException("ResourceNotFoundException"));

        assertFalse(detector.isProviderFrameworkOnEvent(SERVICE_TOKEN, REGION));
        assertEquals(SYNCHRONOUS, detector.responseTimeout(SERVICE_TOKEN, REGION, SYNCHRONOUS));
    }

    @Test
    void providerFrameworkGetsTheAsyncBudgetAndOthersTheSynchronousOne() {
        stubEnvironment(frameworkEnv());
        assertEquals(Duration.ofMinutes(3), detector.responseTimeout(SERVICE_TOKEN, REGION, SYNCHRONOUS));

        stubEnvironment(Map.of("USER_ON_EVENT_FUNCTION_ARN", "arn:aws:lambda:us-east-1:0:function:onEvent"));
        assertEquals(SYNCHRONOUS, detector.responseTimeout(SERVICE_TOKEN, REGION, SYNCHRONOUS));
    }

    @Test
    void testSeamShortensTheAsyncBudget() {
        stubEnvironment(frameworkEnv());
        detector.setAsyncCustomResourceTimeoutForTesting(Duration.ofMillis(300));

        assertEquals(Duration.ofMillis(300), detector.responseTimeout(SERVICE_TOKEN, REGION, SYNCHRONOUS));
    }
}
