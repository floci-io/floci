package io.github.hectorvent.floci.services.codedeploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.codedeploy.model.Deployment;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.ssm.SsmCommandService;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeDeployServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/CodeDeployRole";
    private static final ObjectMapper JSON = new ObjectMapper();

    private LambdaService lambdaService;
    private SsmCommandService ssmCommandService;
    private CodeDeployService service;

    @BeforeEach
    void setUp() {
        lambdaService = mock(LambdaService.class);
        ssmCommandService = mock(SsmCommandService.class);
        service = newService(Duration.ofHours(1));
    }

    private CodeDeployService newService(Duration hookCallbackTimeout) {
        return newService(hookCallbackTimeout, Clock.systemUTC());
    }

    private CodeDeployService newService(Duration hookCallbackTimeout, Clock clock) {
        CodeDeployService svc = new CodeDeployService(lambdaService, mock(EcsService.class),
                mock(ElbV2Service.class), ssmCommandService, mock(Ec2Service.class), new ObjectMapper(),
                new RegionResolver(REGION, ACCOUNT_ID), null, hookCallbackTimeout, clock);
        svc.initializeStorage();
        return svc;
    }

    // ---- Server platform (SSM) hooks ---------------------------------------------------

    @Test
    void missingSsmInstanceFailsHook() {
        service.registerOnPremisesInstance(REGION, "instance-1", "arn:aws:sts::000000000000:session/s",
                "arn:aws:iam::000000000000:user/u");
        when(ssmCommandService.isInstanceRegistered("instance-1", REGION)).thenReturn(false);

        String deploymentId = createServerDeployment("app-missing-ssm", "group-missing-ssm", 30);

        Deployment deployment = awaitTerminal(deploymentId, Duration.ofSeconds(5));

        assertEquals("Failed", deployment.getStatus());
        assertEquals("HEALTH_CONSTRAINTS", deployment.getErrorInformation().get("code"));
        Map<String, Object> event = instanceTargetEvent(deploymentId, "instance-1", "ApplicationStart");
        assertEquals("Failed", event.get("status"));
        Map<String, String> diagnostics = diagnosticsOf(event);
        assertEquals("UnknownError", diagnostics.get("errorCode"));
        assertEquals("CodeDeploy agent was not able to receive the lifecycle event. Check the CodeDeploy agent "
                        + "logs on your host and make sure the agent is running and can connect to the CodeDeploy server.",
                diagnostics.get("message"));
    }

    @Test
    void ssmCommandFailureFailsHook() {
        service.registerOnPremisesInstance(REGION, "instance-1", "arn:aws:sts::000000000000:session/s",
                "arn:aws:iam::000000000000:user/u");
        when(ssmCommandService.isInstanceRegistered("instance-1", REGION)).thenReturn(true);
        when(ssmCommandService.sendCommandToInstance(eq("instance-1"), anyString(), any(), eq(30), eq(REGION)))
                .thenReturn("cmd-1");
        when(ssmCommandService.getCommandInvocationStatus("cmd-1", "instance-1", REGION)).thenReturn("Failed");

        String deploymentId = createServerDeployment("app-ssm-failure", "group-ssm-failure", 30);

        Deployment deployment = awaitTerminal(deploymentId, Duration.ofSeconds(5));

        assertEquals("Failed", deployment.getStatus());
        assertEquals("HEALTH_CONSTRAINTS", deployment.getErrorInformation().get("code"));
        Map<String, Object> event = instanceTargetEvent(deploymentId, "instance-1", "ApplicationStart");
        assertEquals("Failed", event.get("status"));
        Map<String, String> diagnostics = diagnosticsOf(event);
        assertEquals("ScriptFailed", diagnostics.get("errorCode"));
        assertEquals("Script at specified location: scripts/start_server.sh failed with SSM command status Failed",
                diagnostics.get("message"));
    }

    @Test
    void ssmScriptTimeoutFailsHook() {
        service.registerOnPremisesInstance(REGION, "instance-1", "arn:aws:sts::000000000000:session/s",
                "arn:aws:iam::000000000000:user/u");
        when(ssmCommandService.isInstanceRegistered("instance-1", REGION)).thenReturn(true);
        when(ssmCommandService.sendCommandToInstance(eq("instance-1"), anyString(), any(), eq(1), eq(REGION)))
                .thenReturn("cmd-timeout");
        when(ssmCommandService.getCommandInvocationStatus("cmd-timeout", "instance-1", REGION))
                .thenReturn("InProgress");

        String deploymentId = createServerDeployment("app-ssm-timeout", "group-ssm-timeout", 1);

        Deployment deployment = awaitTerminal(deploymentId, Duration.ofSeconds(5));

        assertEquals("Failed", deployment.getStatus());
        Map<String, Object> event = instanceTargetEvent(deploymentId, "instance-1", "ApplicationStart");
        Map<String, String> diagnostics = diagnosticsOf(event);
        assertEquals("ScriptTimedOut", diagnostics.get("errorCode"));
        assertEquals("Script at specified location: scripts/start_server.sh failed to complete in 1 seconds",
                diagnostics.get("message"));
    }

    @Test
    void ssmScriptRunningPastThirtySecondsWithinDeclaredTimeoutSucceeds() {
        MutableClock clock = new MutableClock();
        CodeDeployService clockedService = newService(Duration.ofHours(1), clock);
        clockedService.registerOnPremisesInstance(REGION, "instance-1", "arn:aws:sts::000000000000:session/s",
                "arn:aws:iam::000000000000:user/u");
        when(ssmCommandService.isInstanceRegistered("instance-1", REGION)).thenReturn(true);
        when(ssmCommandService.sendCommandToInstance(eq("instance-1"), anyString(), any(), eq(300), eq(REGION)))
                .thenReturn("cmd-long");
        AtomicInteger polls = new AtomicInteger();
        when(ssmCommandService.getCommandInvocationStatus("cmd-long", "instance-1", REGION))
                .thenAnswer(invocation -> {
                    clock.advance(Duration.ofSeconds(20));
                    return polls.incrementAndGet() < 3 ? "InProgress" : "Success";
                });

        String deploymentId = createServerDeployment(clockedService, "app-ssm-long", "group-ssm-long", 300);

        Deployment deployment = awaitTerminal(clockedService, deploymentId, Duration.ofSeconds(5));

        assertEquals("Succeeded", deployment.getStatus());
    }

    @Test
    void hookWithoutTimeoutIsSentWithTheAppSpecDefaultOfOneHour() {
        service.registerOnPremisesInstance(REGION, "instance-1", "arn:aws:sts::000000000000:session/s",
                "arn:aws:iam::000000000000:user/u");
        when(ssmCommandService.isInstanceRegistered("instance-1", REGION)).thenReturn(true);
        when(ssmCommandService.sendCommandToInstance(eq("instance-1"), anyString(), any(), eq(3600), eq(REGION)))
                .thenReturn("cmd-default");
        when(ssmCommandService.getCommandInvocationStatus("cmd-default", "instance-1", REGION))
                .thenReturn("Success");

        String deploymentId = createServerDeployment(service, "app-default-timeout", "group-default-timeout", """
                os: linux
                hooks:
                  ApplicationStart:
                    - location: scripts/start_server.sh
                """);

        Deployment deployment = awaitTerminal(deploymentId, Duration.ofSeconds(5));

        assertEquals("Succeeded", deployment.getStatus());
        verify(ssmCommandService).sendCommandToInstance(eq("instance-1"), anyString(), any(), eq(3600), eq(REGION));
    }

    @Test
    void invalidHookTimeoutFailsBeforeInstallWithoutRunningAnyScript() {
        service.registerOnPremisesInstance(REGION, "instance-1", "arn:aws:sts::000000000000:session/s",
                "arn:aws:iam::000000000000:user/u");
        when(ssmCommandService.isInstanceRegistered("instance-1", REGION)).thenReturn(true);

        int index = 0;
        for (String timeout : List.of("0", "-5", "abc", "30s", "\"\"")) {
            String suffix = "invalid-timeout-" + index++;
            String deploymentId = createServerDeployment(service, "app-" + suffix, "group-" + suffix, """
                    os: linux
                    hooks:
                      ApplicationStart:
                        - location: scripts/start_server.sh
                          timeout: %s
                    """.formatted(timeout));

            Deployment deployment = awaitTerminal(deploymentId, Duration.ofSeconds(5));

            assertEquals("Failed", deployment.getStatus(), "timeout " + timeout);
            Map<String, Object> event = instanceTargetEvent(deploymentId, "instance-1", "BeforeInstall");
            assertEquals("Failed", event.get("status"), "timeout " + timeout);
            Map<String, String> diagnostics = diagnosticsOf(event);
            assertEquals("UnknownError", diagnostics.get("errorCode"), "timeout " + timeout);
            assertEquals("The deployment failed because an invalid timeout value was provided for a script in "
                    + "the application specification file. Make corrections in the hooks section of the "
                    + "AppSpec file, and then try again.", diagnostics.get("message"), "timeout " + timeout);
            assertFalse(hasLifecycleEvent(deploymentId, "instance-1", "ApplicationStart"), "timeout " + timeout);
        }
        verify(ssmCommandService, never()).sendCommandToInstance(anyString(), anyString(), any(), anyInt(), anyString());
    }

    // ---- Lambda platform hooks ----------------------------------------------------------

    @Test
    void lambdaHookInvocationFailureMarksHookFailed() {
        createLambdaAppAndGroup("app-invoke-fail", "group-invoke-fail");
        when(lambdaService.invoke(eq(REGION), eq("beforeHook"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenThrow(new RuntimeException("function not found"));

        String deploymentId = createLambdaDeployment("app-invoke-fail", "group-invoke-fail", "beforeHook", null);

        Deployment deployment = awaitTerminal(deploymentId, Duration.ofSeconds(5));

        assertEquals("Failed", deployment.getStatus());
        assertEquals("HOOK_EXECUTION_FAILURE", deployment.getErrorInformation().get("code"));
        Map<String, Object> event = lambdaTargetEvent(deploymentId, "BeforeAllowTraffic");
        assertEquals("Failed", event.get("status"));
        Map<String, String> diagnostics = diagnosticsOf(event);
        assertEquals("UnknownError", diagnostics.get("errorCode"));
        assertEquals("Lambda function beforeHook could not be invoked: function not found",
                diagnostics.get("message"));
    }

    @Test
    void lambdaHookFunctionErrorMarksHookFailed() {
        createLambdaAppAndGroup("app-function-error", "group-function-error");
        InvokeResult erroring = new InvokeResult(200, "Unhandled", new byte[0], null, "req-1");
        when(lambdaService.invoke(eq(REGION), eq("beforeHook"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(erroring);

        String deploymentId = createLambdaDeployment("app-function-error", "group-function-error", "beforeHook", null);

        Deployment deployment = awaitTerminal(deploymentId, Duration.ofSeconds(5));

        assertEquals("Failed", deployment.getStatus());
        assertEquals("HOOK_EXECUTION_FAILURE", deployment.getErrorInformation().get("code"));
        Map<String, Object> event = lambdaTargetEvent(deploymentId, "BeforeAllowTraffic");
        assertEquals("Failed", event.get("status"));
        Map<String, String> diagnostics = diagnosticsOf(event);
        assertEquals("UnknownError", diagnostics.get("errorCode"));
        assertEquals("Lambda function beforeHook returned a function error: Unhandled",
                diagnostics.get("message"));
    }

    @Test
    void explicitSuccessfulCallbackMarksHookSucceeded() {
        createLambdaAppAndGroup("app-explicit-success", "group-explicit-success");
        InvokeResult cleanInvoke = new InvokeResult(200, null, new byte[0], null, "req-2");
        when(lambdaService.invoke(eq(REGION), eq("beforeHook"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    byte[] payload = invocation.getArgument(2);
                    service.putLifecycleEventHookExecutionStatus("ignored", executionIdFrom(payload), "Succeeded");
                    return cleanInvoke;
                });

        String deploymentId = createLambdaDeployment("app-explicit-success", "group-explicit-success", "beforeHook", null);

        Deployment deployment = awaitTerminal(deploymentId, Duration.ofSeconds(5));

        assertEquals("Succeeded", deployment.getStatus());
    }

    @Test
    void lambdaHookCallbackTimeoutFailsHook() {
        CodeDeployService shortTimeoutService = newService(Duration.ofSeconds(1));
        shortTimeoutService.createApplication(REGION, "app-hook-timeout", "Lambda", null);
        shortTimeoutService.createDeploymentGroup(REGION, "app-hook-timeout", "group-hook-timeout",
                "CodeDeployDefault.LambdaAllAtOnce", ROLE_ARN, null);
        InvokeResult cleanInvoke = new InvokeResult(200, null, new byte[0], null, "req-3");
        when(lambdaService.invoke(eq(REGION), eq("beforeHook"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(cleanInvoke);

        String deploymentId = shortTimeoutService.createDeployment(REGION, "app-hook-timeout",
                "group-hook-timeout", null, lambdaRevision("beforeHook", null), "no callback ever arrives");

        Deployment deployment = awaitTerminal(shortTimeoutService, deploymentId, Duration.ofSeconds(5));

        assertEquals("Failed", deployment.getStatus());
        assertEquals("HOOK_EXECUTION_FAILURE", deployment.getErrorInformation().get("code"));
        Map<String, Object> event = lambdaTargetEvent(shortTimeoutService, deploymentId, "BeforeAllowTraffic");
        assertEquals("Failed", event.get("status"));
        Map<String, String> diagnostics = diagnosticsOf(event);
        assertEquals("UnknownError", diagnostics.get("errorCode"));
        assertEquals("Lambda function beforeHook did not call PutLifecycleEventHookExecutionStatus within 1 seconds",
                diagnostics.get("message"));
    }

    @Test
    void stopDeploymentDuringHookWaitEndsAsStopped() {
        createLambdaAppAndGroup("app-stop-during-wait", "group-stop-during-wait");
        InvokeResult cleanInvoke = new InvokeResult(200, null, new byte[0], null, "req-6");
        when(lambdaService.invoke(eq(REGION), eq("beforeHook"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(cleanInvoke);

        String deploymentId = createLambdaDeployment("app-stop-during-wait", "group-stop-during-wait",
                "beforeHook", null);

        // Wait until the hook has actually been invoked so the stop lands during the callback wait.
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                verify(lambdaService).invoke(eq(REGION), eq("beforeHook"), any(byte[].class), any()));
        service.stopDeployment(REGION, deploymentId);

        Deployment deployment = awaitTerminal(deploymentId, Duration.ofSeconds(5));

        assertEquals("Stopped", deployment.getStatus());
        Map<String, Object> event = lambdaTargetEvent(deploymentId, "BeforeAllowTraffic");
        assertEquals("Skipped", event.get("status"));
    }

    @Test
    void failedBeforeAllowTrafficHookPreventsTrafficShift() {
        createLambdaAppAndGroup("app-no-progress", "group-no-progress");
        InvokeResult cleanInvoke = new InvokeResult(200, null, new byte[0], null, "req-4");
        when(lambdaService.invoke(eq(REGION), eq("beforeHook"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    byte[] payload = invocation.getArgument(2);
                    service.putLifecycleEventHookExecutionStatus("ignored", executionIdFrom(payload), "Failed");
                    return cleanInvoke;
                });

        String deploymentId = createLambdaDeployment("app-no-progress", "group-no-progress", "beforeHook", "afterHook");

        Deployment deployment = awaitTerminal(deploymentId, Duration.ofSeconds(5));

        assertEquals("Failed", deployment.getStatus());
        assertEquals("HOOK_EXECUTION_FAILURE", deployment.getErrorInformation().get("code"));
        Map<String, Object> beforeAllowTrafficEvent = lambdaTargetEvent(deploymentId, "BeforeAllowTraffic");
        assertEquals("Failed", beforeAllowTrafficEvent.get("status"));
        assertEquals("Lambda function beforeHook reported that lifecycle event BeforeAllowTraffic failed",
                diagnosticsOf(beforeAllowTrafficEvent).get("message"));
        verify(lambdaService, never()).invoke(eq(REGION), eq("afterHook"), any(byte[].class), any());
        verify(lambdaService, never()).updateAlias(anyString(), anyString(), anyString(), anyString(), any(), any());
        List<String> recordedEvents = lambdaTargetEvents(deploymentId).stream()
                .map(e -> (String) e.get("lifecycleEventName"))
                .toList();
        assertTrue(recordedEvents.contains("BeforeAllowTraffic"));
        assertFalse(recordedEvents.contains("AllowTraffic"), "AllowTraffic must not run after a failed hook");
        assertFalse(recordedEvents.contains("AfterAllowTraffic"), "AfterAllowTraffic must not run after a failed hook");
    }

    @Test
    void lateLifecycleCallbackAfterDeploymentTerminalIsNoOp() {
        createLambdaAppAndGroup("app-late-callback", "group-late-callback");
        InvokeResult cleanInvoke = new InvokeResult(200, null, new byte[0], null, "req-5");
        AtomicReference<String> capturedExecutionId = new AtomicReference<>();
        when(lambdaService.invoke(eq(REGION), eq("beforeHook"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    byte[] payload = invocation.getArgument(2);
                    String executionId = executionIdFrom(payload);
                    capturedExecutionId.set(executionId);
                    service.putLifecycleEventHookExecutionStatus("ignored", executionId, "Succeeded");
                    return cleanInvoke;
                });

        String deploymentId = createLambdaDeployment("app-late-callback", "group-late-callback", "beforeHook", null);
        Deployment deployment = awaitTerminal(deploymentId, Duration.ofSeconds(5));
        assertEquals("Succeeded", deployment.getStatus());

        service.putLifecycleEventHookExecutionStatus("ignored", capturedExecutionId.get(), "Failed");

        assertEquals("Succeeded", service.getDeployment(REGION, deploymentId).getStatus());
    }

    // ---- Helpers --------------------------------------------------------------------------

    private String createServerDeployment(String appName, String groupName, int timeoutSeconds) {
        return createServerDeployment(service, appName, groupName, timeoutSeconds);
    }

    private String createServerDeployment(CodeDeployService target, String appName, String groupName,
                                          int timeoutSeconds) {
        return createServerDeployment(target, appName, groupName, """
                os: linux
                hooks:
                  ApplicationStart:
                    - location: scripts/start_server.sh
                      timeout: %d
                """.formatted(timeoutSeconds));
    }

    private String createServerDeployment(CodeDeployService target, String appName, String groupName,
                                          String appSpec) {
        target.createApplication(REGION, appName, "Server", null);
        target.createDeploymentGroup(REGION, appName, groupName, "CodeDeployDefault.AllAtOnce", ROLE_ARN, null);
        Map<String, Object> revision = Map.of("revisionType", "AppSpecContent",
                "appSpecContent", Map.of("content", appSpec));
        return target.createDeployment(REGION, appName, groupName, null, revision, "server hook test");
    }

    private void createLambdaAppAndGroup(String appName, String groupName) {
        service.createApplication(REGION, appName, "Lambda", null);
        service.createDeploymentGroup(REGION, appName, groupName,
                "CodeDeployDefault.LambdaAllAtOnce", ROLE_ARN, null);
    }

    private String createLambdaDeployment(String appName, String groupName, String beforeHook, String afterHook) {
        return service.createDeployment(REGION, appName, groupName, null,
                lambdaRevision(beforeHook, afterHook), "lambda hook test");
    }

    private Map<String, Object> lambdaRevision(String beforeHook, String afterHook) {
        StringBuilder hooks = new StringBuilder("[");
        if (beforeHook != null) {
            hooks.append("{\"BeforeAllowTraffic\":\"").append(beforeHook).append("\"}");
        }
        if (afterHook != null) {
            if (hooks.length() > 1) {
                hooks.append(",");
            }
            hooks.append("{\"AfterAllowTraffic\":\"").append(afterHook).append("\"}");
        }
        hooks.append("]");
        String content = """
                {
                  "version": "0.0",
                  "Resources": [
                    {"myFunc": {"Type": "AWS::Lambda::Function", "Properties": {
                      "Name": "myFunc", "Alias": "live", "CurrentVersion": "1", "TargetVersion": "2"
                    }}}
                  ],
                  "Hooks": %s
                }
                """.formatted(hooks);
        return Map.of("revisionType", "AppSpecContent", "appSpecContent", Map.of("content", content));
    }

    private static String executionIdFrom(byte[] payload) {
        try {
            JsonNode node = JSON.readTree(payload);
            return node.get("LifecycleEventHookExecutionId").asText();
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse hook payload", e);
        }
    }

    private Deployment awaitTerminal(String deploymentId, Duration atMost) {
        return awaitTerminal(service, deploymentId, atMost);
    }

    private Deployment awaitTerminal(CodeDeployService target, String deploymentId, Duration atMost) {
        await().atMost(atMost).pollInterval(Duration.ofMillis(25)).until(() ->
                isTerminal(target.getDeployment(REGION, deploymentId).getStatus()));
        return target.getDeployment(REGION, deploymentId);
    }

    private boolean isTerminal(String status) {
        return "Succeeded".equals(status) || "Failed".equals(status) || "Stopped".equals(status);
    }

    @SuppressWarnings("unchecked")
    private boolean hasLifecycleEvent(String deploymentId, String targetId, String eventName) {
        Map<String, Object> target = service.batchGetDeploymentTargets(REGION, deploymentId, List.of(targetId)).get(0);
        Map<String, Object> instanceTarget = (Map<String, Object>) target.get("instanceTarget");
        List<Map<String, Object>> events = (List<Map<String, Object>>) instanceTarget.get("lifecycleEvents");
        return events.stream().anyMatch(e -> eventName.equals(e.get("lifecycleEventName")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> instanceTargetEvent(String deploymentId, String targetId, String eventName) {
        Map<String, Object> target = service.batchGetDeploymentTargets(REGION, deploymentId, List.of(targetId)).get(0);
        Map<String, Object> instanceTarget = (Map<String, Object>) target.get("instanceTarget");
        List<Map<String, Object>> events = (List<Map<String, Object>>) instanceTarget.get("lifecycleEvents");
        return events.stream()
                .filter(e -> eventName.equals(e.get("lifecycleEventName")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No lifecycle event named " + eventName));
    }

    private Map<String, Object> lambdaTargetEvent(String deploymentId, String eventName) {
        return lambdaTargetEvent(service, deploymentId, eventName);
    }

    private Map<String, Object> lambdaTargetEvent(CodeDeployService target, String deploymentId, String eventName) {
        return lambdaTargetEvents(target, deploymentId).stream()
                .filter(e -> eventName.equals(e.get("lifecycleEventName")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No lifecycle event named " + eventName));
    }

    private List<Map<String, Object>> lambdaTargetEvents(String deploymentId) {
        return lambdaTargetEvents(service, deploymentId);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> lambdaTargetEvents(CodeDeployService target, String deploymentId) {
        Map<String, Object> targetMap = target.batchGetDeploymentTargets(REGION, deploymentId, List.of()).get(0);
        Map<String, Object> lambdaTarget = (Map<String, Object>) targetMap.get("lambdaTarget");
        return (List<Map<String, Object>>) lambdaTarget.get("lifecycleEvents");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> diagnosticsOf(Map<String, Object> event) {
        return (Map<String, String>) event.get("diagnostics");
    }
}
