package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Hermetic coverage for the CDK Provider-framework (two-phase async) custom-resource path. The
 * backing {@code framework.onEvent} Lambda is mocked so that — like the real framework — it returns
 * WITHOUT PUTting and the ResponseURL callback lands asynchronously (as the waiter state machine's
 * {@code framework.isComplete} / {@code framework.onTimeout} would drive it). No Docker / Step
 * Functions involved: this isolates the provisioner's detect-and-wait logic.
 */
class CustomResourceProviderFrameworkTest {

    private static final String SERVICE_TOKEN =
            "arn:aws:lambda:us-east-1:000000000000:function:framework-onEvent";
    private static final String WAITER_ARN =
            "arn:aws:states:us-east-1:000000000000:stateMachine:waiter";

    private final ObjectMapper mapper = new ObjectMapper();
    private LambdaService lambdaService;
    private ProviderFrameworkDetector detector;
    private CustomResourceResponseStore store;
    private CloudFormationResourceProvisioner provisioner;

    @BeforeEach
    void setUp() {
        lambdaService = mock(LambdaService.class);
        detector = new ProviderFrameworkDetector(lambdaService);
        store = new CustomResourceResponseStore(detector);
        ContainerReachableEndpoint endpoint = mock(ContainerReachableEndpoint.class);
        when(endpoint.baseUrl()).thenReturn("http://floci:4566");

        provisioner = CfnProvisionerFixture.builder()
                .lambda(lambdaService)
                .objectMapper(mapper)
                .customResourceResponseStore(store)
                .reachableEndpoint(endpoint)
                .config(mock(EmulatorConfig.class))
                .build();
    }

    private CloudFormationTemplateEngine engine() {
        return new CloudFormationTemplateEngine("000000000000", "us-east-1", "my-stack",
                "stack/id", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), mapper,
                (Function<String, String>) name -> null);
    }

    private ObjectNode props() {
        ObjectNode props = mapper.createObjectNode();
        props.put("ServiceToken", SERVICE_TOKEN);
        return props;
    }

    /** Registers the ServiceToken as a framework.onEvent Lambda: env carries the two async markers. */
    private void stubFrameworkOnEvent() {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("framework-onEvent");
        fn.setFunctionArn(SERVICE_TOKEN);
        fn.setEnvironment(Map.of(
                "USER_ON_EVENT_FUNCTION_ARN", "arn:aws:lambda:us-east-1:000000000000:function:onEvent",
                "USER_IS_COMPLETE_FUNCTION_ARN", "arn:aws:lambda:us-east-1:000000000000:function:isComplete",
                "WAITER_STATE_MACHINE_ARN", WAITER_ARN));
        when(lambdaService.getFunction(eq("us-east-1"), eq(SERVICE_TOKEN))).thenReturn(fn);
    }

    /** Registers the ServiceToken as an onEvent-ONLY provider: no isComplete/waiter markers. */
    private void stubOnEventOnly() {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("framework-onEvent");
        fn.setFunctionArn(SERVICE_TOKEN);
        fn.setEnvironment(Map.of(
                "USER_ON_EVENT_FUNCTION_ARN", "arn:aws:lambda:us-east-1:000000000000:function:onEvent"));
        when(lambdaService.getFunction(eq("us-east-1"), eq(SERVICE_TOKEN))).thenReturn(fn);
    }

    /** onEvent returns without PUTting; a background PUT lands after {@code delayMs}, as the waiter would. */
    private void stubDeferredPut(String status, String physicalId, Map<String, String> data, long delayMs) {
        when(lambdaService.invoke(any(), eq(SERVICE_TOKEN), any(), eq(InvocationType.RequestResponse)))
                .thenAnswer(inv -> {
                    String token = tokenOf(inv.getArgument(2));
                    Thread t = new Thread(() -> {
                        try {
                            Thread.sleep(delayMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        ObjectNode response = mapper.createObjectNode();
                        response.put("Status", status);
                        if (physicalId != null) {
                            response.put("PhysicalResourceId", physicalId);
                        }
                        if (data != null) {
                            ObjectNode dataNode = response.putObject("Data");
                            data.forEach(dataNode::put);
                        }
                        if (!"SUCCESS".equals(status)) {
                            response.put("Reason", "onTimeout: resource never became complete");
                        }
                        store.complete(token, response);
                    }, "deferred-put");
                    t.setDaemon(true);
                    t.start();
                    return new InvokeResult(200, null, "null".getBytes(), null, "req-1");
                });
    }

    private String tokenOf(byte[] payload) throws Exception {
        JsonNode event = mapper.readTree(payload);
        String responseUrl = event.get("ResponseURL").asText();
        return responseUrl.substring(responseUrl.lastIndexOf('/') + 1);
    }

    @Test
    void frameworkOnEventAwaitsAsyncPutAndPropagatesData() {
        stubFrameworkOnEvent();
        stubDeferredPut("SUCCESS", "account-provisioned", Map.of("AccountId", "111122223333"), 150);

        StackResource r = provisioner.provision("CreateAccounts", "Custom::CreateOrganizationAccounts",
                props(), engine(), "us-east-1", "000000000000", "my-stack");

        assertEquals("CREATE_COMPLETE", r.getStatus());
        assertEquals("account-provisioned", r.getPhysicalId());
        assertEquals("111122223333", r.getAttributes().get("AccountId"));
    }

    @Test
    void onEventOnlyProviderStillSucceedsViaSynchronousPut() {
        stubOnEventOnly();
        // Synchronous PUT during the invoke, exactly as an onEvent-only framework.onEvent does.
        when(lambdaService.invoke(any(), eq(SERVICE_TOKEN), any(), eq(InvocationType.RequestResponse)))
                .thenAnswer(inv -> {
                    String token = tokenOf(inv.getArgument(2));
                    ObjectNode response = mapper.createObjectNode();
                    response.put("Status", "SUCCESS");
                    response.put("PhysicalResourceId", "moved");
                    store.complete(token, response);
                    return new InvokeResult(200, null, "null".getBytes(), null, "req-1");
                });

        StackResource r = provisioner.provision("MoveAccounts", "Custom::MoveAccounts",
                props(), engine(), "us-east-1", "000000000000", "my-stack");

        assertEquals("CREATE_COMPLETE", r.getStatus());
        assertEquals("moved", r.getPhysicalId());
    }

    @Test
    void asyncPutThatNeverArrivesFailsCleanlyWithinBudget() {
        detector.setAsyncCustomResourceTimeoutForTesting(Duration.ofMillis(300));
        stubFrameworkOnEvent();
        // onEvent returns without PUTting and nothing ever calls back.
        when(lambdaService.invoke(any(), eq(SERVICE_TOKEN), any(), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, null, "null".getBytes(), null, "req-1"));

        StackResource r = provisioner.provision("CreateAccounts", "Custom::CreateOrganizationAccounts",
                props(), engine(), "us-east-1", "000000000000", "my-stack");

        assertEquals("CREATE_FAILED", r.getStatus());
        assertTrue(r.getStatusReason().contains("Timed out"),
                "expected a bounded timeout failure, got: " + r.getStatusReason());
    }

    @Test
    void waiterOnTimeoutFailurePropagatesAsResourceFailure() {
        stubFrameworkOnEvent();
        stubDeferredPut("FAILED", null, null, 150);

        StackResource r = provisioner.provision("CreateAccounts", "Custom::CreateOrganizationAccounts",
                props(), engine(), "us-east-1", "000000000000", "my-stack");

        assertEquals("CREATE_FAILED", r.getStatus());
        assertTrue(r.getStatusReason().contains("onTimeout"),
                "expected the onTimeout FAILED reason to surface, got: " + r.getStatusReason());
    }
}
