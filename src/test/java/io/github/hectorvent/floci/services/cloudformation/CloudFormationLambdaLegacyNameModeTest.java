package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CloudFormationResourceRegistry;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for #2163: the same "legacy resource with no recorded name mode" gap fixed for
 * AWS::Logs::LogGroup in #1965/PR #2152, applied to Lambda's FunctionName. Uses the same direct
 * provisioner + mocked service approach as CloudFormationLogGroupProvisionerTest, since simulating
 * "created by an older floci version" isn't reachable through the black-box HTTP integration tests.
 */
class CloudFormationLambdaLegacyNameModeTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String STACK_NAME = "test-stack";

    private final ObjectMapper mapper = new ObjectMapper();
    private LambdaService lambdaService;
    private CloudFormationResourceProvisioner provisioner;

    @BeforeEach
    void setUp() {
        lambdaService = mock(LambdaService.class);
        when(lambdaService.updateFunctionConfiguration(anyString(), anyString(), anyMap()))
                .thenAnswer(inv -> lambdaFunction(inv.getArgument(1)));
        when(lambdaService.updateFunctionCode(anyString(), anyString(), anyMap()))
                .thenAnswer(inv -> lambdaFunction(inv.getArgument(1)));
        provisioner = new CloudFormationResourceProvisioner(
                null, null, null, null, lambdaService, null, null, null, null, null,
                null, null, null, null, null, null,
                mapper,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null,
                new CloudFormationResourceRegistry(List.of()), null);
    }

    @Test
    void legacyExplicitFunctionNameWithNoRecordedMode_replacesWhenNameIsRemoved() {
        String legacyExplicitName = "my-hand-chosen-function";
        when(lambdaService.getFunction(REGION, legacyExplicitName)).thenReturn(lambdaFunction(legacyExplicitName));
        when(lambdaService.createFunction(anyString(), anyMap()))
                .thenAnswer(inv -> lambdaFunction((String) ((Map<?, ?>) inv.getArgument(1)).get("FunctionName")));

        StackResource result = provision("{}", legacyExplicitName, Map.of());

        assertEquals("CREATE_COMPLETE", result.getStatus());
        assertNotEquals(legacyExplicitName, result.getPhysicalId());
        assertTrue(result.getPhysicalId().startsWith(STACK_NAME + "-Function-"),
                "expected a freshly generated name, got: " + result.getPhysicalId());
        verify(lambdaService).deleteFunction(REGION, legacyExplicitName);
    }

    @Test
    void legacyGeneratedFunctionNameWithNoRecordedMode_reconcilesInPlace() {
        String legacyGeneratedName = STACK_NAME + "-Function-abc123def456";
        when(lambdaService.getFunction(REGION, legacyGeneratedName)).thenReturn(lambdaFunction(legacyGeneratedName));

        StackResource result = provision("{}", legacyGeneratedName, Map.of());

        assertEquals("CREATE_COMPLETE", result.getStatus());
        assertEquals(legacyGeneratedName, result.getPhysicalId());
        verify(lambdaService, never()).createFunction(anyString(), anyMap());
        verify(lambdaService, never()).deleteFunction(anyString(), anyString());
    }

    @Test
    void legacyTruncatedGeneratedFunctionNameWithNoRecordedMode_reconcilesInPlace() {
        // At Lambda's 64-character limit, truncation is actually reachable (unlike LogGroup's
        // 512), so a legacy truncated generated name must still be correctly recognized as
        // generated rather than misclassified as explicit and needlessly replaced. This name is
        // exactly what generatePhysicalName("test-stack", logicalId, 64, false) produces: the
        // 61-character "test-stack-<logicalId>" base truncated to the 51 characters that leave
        // room for "-" plus a 12-character suffix, giving exactly 64 characters total.
        String logicalId = "MyVeryLongLambdaFunctionLogicalIdForTruncationTest";
        String legacyGeneratedName = "test-stack-MyVeryLongLambdaFunctionLogicalIdForTrun-abc123def456";
        assertEquals(64, legacyGeneratedName.length());
        when(lambdaService.getFunction(REGION, legacyGeneratedName)).thenReturn(lambdaFunction(legacyGeneratedName));

        StackResource result = provisioner.provision(logicalId, "AWS::Lambda::Function", props("{}"), engine(),
                REGION, ACCOUNT_ID, STACK_NAME, legacyGeneratedName, Map.of());

        assertEquals("CREATE_COMPLETE", result.getStatus());
        assertEquals(legacyGeneratedName, result.getPhysicalId());
        verify(lambdaService, never()).createFunction(anyString(), anyMap());
        verify(lambdaService, never()).deleteFunction(anyString(), anyString());
    }

    @Test
    void legacyGeneratedFunctionNameWithNoRecordedMode_warnsAboutTheAmbiguousInference() {
        // Regression: inferring "generated" for a legacy name that happens to match the generated
        // shape is a guess Floci cannot verify - if the name was actually pinned explicitly, this
        // inference silently suppresses the replacement AWS would perform. There's no way to close
        // that gap (the raw historical property value was never persisted to check against), so this
        // instead verifies the guess is at least observable: a warning logged specifically when the
        // inference is consequential (current template also has no FunctionName).
        String legacyGeneratedName = STACK_NAME + "-Function-abc123def456";
        when(lambdaService.getFunction(REGION, legacyGeneratedName)).thenReturn(lambdaFunction(legacyGeneratedName));

        List<String> messages = provisionerLogMessages(() -> provision("{}", legacyGeneratedName, Map.of()));

        assertTrue(messages.stream().anyMatch(m ->
                        m.contains("auto-generated because it matches the generated-name shape")),
                "expected a warning about the ambiguous legacy-name inference, got: " + messages);
    }

    @Test
    void legacyExplicitFunctionNameWithNoRecordedMode_doesNotWarn() {
        // The other inference direction (legacy name does NOT match the generated shape, so it's
        // treated as explicit) has no real ambiguity - an auto-generated name always has the
        // deterministic shape, so anything else genuinely must have been explicit. No warning should
        // fire for what is actually the safe, unambiguous, common case.
        String legacyExplicitName = "my-hand-chosen-function";
        when(lambdaService.getFunction(REGION, legacyExplicitName)).thenReturn(lambdaFunction(legacyExplicitName));
        when(lambdaService.createFunction(anyString(), anyMap()))
                .thenAnswer(inv -> lambdaFunction((String) ((Map<?, ?>) inv.getArgument(1)).get("FunctionName")));

        List<String> messages = provisionerLogMessages(() -> provision("{}", legacyExplicitName, Map.of()));

        assertFalse(messages.stream().anyMatch(m ->
                        m.contains("auto-generated because it matches the generated-name shape")),
                "did not expect a warning for the unambiguous explicit-name case, got: " + messages);
    }

    /** Collects the messages CloudFormationResourceProvisioner logs while {@code action} runs. */
    private List<String> provisionerLogMessages(Runnable action) {
        java.util.logging.Logger logger =
                java.util.logging.Logger.getLogger(CloudFormationResourceProvisioner.class.getName());
        List<String> messages = new CopyOnWriteArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord logRecord) {
                messages.add(logRecord.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
        }
        return messages;
    }

    private StackResource provision(String propertiesJson, String existingPhysicalId,
                                    Map<String, String> existingAttributes) {
        return provisioner.provision("Function", "AWS::Lambda::Function", props(propertiesJson), engine(),
                REGION, ACCOUNT_ID, STACK_NAME, existingPhysicalId, existingAttributes);
    }

    private static LambdaFunction lambdaFunction(String name) {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName(name);
        fn.setFunctionArn("arn:aws:lambda:" + REGION + ":" + ACCOUNT_ID + ":function:" + name);
        fn.setPackageType("Zip");
        fn.setRuntime("nodejs18.x");
        fn.setHandler("index.handler");
        fn.setRole("arn:aws:iam::" + ACCOUNT_ID + ":role/default");
        fn.setRevisionId("1");
        return fn;
    }

    private CloudFormationTemplateEngine engine() {
        return new CloudFormationTemplateEngine(
                ACCOUNT_ID, REGION, STACK_NAME, "stack/id",
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), mapper,
                (Function<String, String>) name -> null);
    }

    private JsonNode props(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
