package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.stepfunctions.model.Activity;
import io.floci.aws.services.stepfunctions.model.Execution;
import io.floci.aws.services.stepfunctions.model.StateMachine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which values a {@code .$} field of a JSONPath payload template accepts: a JSONPath reference or
 * one Intrinsic Function call, with nothing but whitespace after the {@code )} that closes its
 * argument list. A string literal is skipped when that {@code )} is found, with a backslash
 * escaping the next character, so a quoted {@code \'} does not end it. Over a real service with
 * in-memory stores, a mocked {@link AslExecutor} and a mocked publisher, through
 * {@link StepFunctionsService#validateStateMachineDefinition}, which serves create, update and
 * validate alike.
 */
class StepFunctionsIntrinsicFieldValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static Stream<String> acceptedValues() {
        return Stream.of("$.a", "$$", "States.Format('it''s {}', $.a)", "States.Array($.a)",
                "States.Array()", "States.Array($.a) ");
    }

    @ParameterizedTest
    @MethodSource("acceptedValues")
    void parameterValueThatIsAPathOrOneIntrinsicCallIsAccepted(String value) {
        StepFunctionsService.ValidationResult result = validate(definitionWithParameter(value));
        assertTrue(result.valid(), "expected OK for " + value + ", got " + result.diagnostics());
        assertEquals(List.of(), result.diagnostics());
    }

    static Stream<String> refusedValues() {
        return Stream.of("foo", "States.StringToJson($.payload).status", "States.Array($.a, $.b)[0]",
                "States.Format('don\\'t', $.a) extra", "", "States.Array('literal').x");
    }

    @ParameterizedTest
    @MethodSource("refusedValues")
    void parameterValueThatIsNeitherAPathNorOneIntrinsicCallIsRefused(String value) {
        StepFunctionsService.ValidationResult result = validate(definitionWithParameter(value));
        assertEquals(false, result.valid(), "expected FAIL for " + value);
        assertEquals(1, result.diagnostics().size());
        StepFunctionsService.Diagnostic diagnostic = result.diagnostics().get(0);
        assertEquals("ERROR", diagnostic.severity());
        assertEquals("SCHEMA_VALIDATION_FAILED", diagnostic.code());
        assertEquals("The value for the field 'x.$' must be a valid JSONPath"
                + " or a valid intrinsic function call", diagnostic.message());
        assertEquals("/States/S/Parameters/x.$", diagnostic.location());
    }

    @Test
    void nestedTemplateWithMixedValidValuesIsAccepted() {
        ObjectNode parameters = MAPPER.createObjectNode();
        parameters.put("a.$", "$.x");
        parameters.put("b.$", "$$");
        parameters.put("c.$", "States.Array($.a)");
        parameters.put("d.$", "States.Format('it''s {}', $.a)");
        parameters.putObject("nested").put("e.$", "$.y");
        parameters.putArray("list").addObject()
                .put("f.$", "$.z")
                .put("g.$", "States.Array($.a)");

        StepFunctionsService.ValidationResult result = validate(definitionWith("Parameters", parameters));
        assertTrue(result.valid(), "expected OK, got " + result.diagnostics());
        assertEquals(List.of(), result.diagnostics());
    }

    @Test
    void nestedRefusedValueIsReportedAtItsOwnPath() {
        ObjectNode parameters = MAPPER.createObjectNode();
        parameters.putArray("ContainerOverrides").addObject()
                .putArray("Environment").addObject()
                .put("Value.$", "foo");

        StepFunctionsService.Diagnostic diagnostic = onlyDiagnostic(definitionWith("Parameters", parameters));
        assertEquals("The value for the field 'Value.$' must be a valid JSONPath"
                + " or a valid intrinsic function call", diagnostic.message());
        assertEquals("/States/S/Parameters/ContainerOverrides[0]/Environment[0]/Value.$",
                diagnostic.location());
    }

    @Test
    void refusedResultSelectorValueIsReportedAtItsTemplate() {
        ObjectNode selector = MAPPER.createObjectNode();
        selector.put("r.$", "States.StringToJson($.payload).status");

        StepFunctionsService.Diagnostic diagnostic = onlyDiagnostic(definitionWith("ResultSelector", selector));
        assertEquals("/States/T/ResultSelector/r.$", diagnostic.location());
    }

    @Test
    void refusedBatchInputValueIsReportedAtItsTemplate() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("StartAt", "M");
        ObjectNode map = root.putObject("States").putObject("M");
        map.put("Type", "Map").put("End", true);
        map.putObject("ItemBatcher").putObject("BatchInput").put("b.$", "foo");
        map.putObject("ItemProcessor").put("StartAt", "P");
        map.putObject("States").putObject("P").put("Type", "Pass").put("End", true);

        StepFunctionsService.Diagnostic diagnostic = onlyDiagnostic(root.toString());
        assertEquals("The value for the field 'b.$' must be a valid JSONPath"
                + " or a valid intrinsic function call", diagnostic.message());
        assertEquals("/States/M/ItemBatcher/BatchInput/b.$", diagnostic.location());
    }

    private static StepFunctionsService.Diagnostic onlyDiagnostic(String definition) {
        StepFunctionsService.ValidationResult result = validate(definition);
        assertEquals(1, result.diagnostics().size(), result.diagnostics().toString());
        return result.diagnostics().get(0);
    }

    private static StepFunctionsService.ValidationResult validate(String definition) {
        return buildService().validateStateMachineDefinition(definition, null, null, null);
    }

    private static String definitionWithParameter(String value) {
        ObjectNode parameters = MAPPER.createObjectNode();
        parameters.put("x.$", value);
        return definitionWith("Parameters", parameters);
    }

    private static String definitionWith(String templateField, ObjectNode template) {
        boolean passState = "Parameters".equals(templateField);
        ObjectNode root = MAPPER.createObjectNode();
        root.put("StartAt", passState ? "S" : "T");
        ObjectNode state = root.putObject("States").putObject(passState ? "S" : "T");
        if (passState) {
            state.put("Type", "Pass").put("End", true);
        } else {
            state.put("Type", "Task").put("Resource", "arn:aws:states:::lambda:invoke").put("End", true);
        }
        state.set(templateField, template);
        return root.toString();
    }

    private static StepFunctionsService buildService() {
        AccountAwareStorageBackend<StateMachine> smStore = AccountAwareStorageBackend.inMemory("000000000000");
        AccountAwareStorageBackend<Execution> execStore = AccountAwareStorageBackend.inMemory("000000000000");
        AccountAwareStorageBackend<Activity> actStore = AccountAwareStorageBackend.inMemory("000000000000");

        StorageFactory factory = mock(StorageFactory.class);
        doReturn(smStore).doReturn(execStore).doReturn(actStore)
                .when(factory).create(anyString(), anyString(), any());

        RegionResolver region = mock(RegionResolver.class);
        when(region.buildArn(eq("states"), any(), anyString()))
                .thenAnswer(i -> "arn:aws:states:us-east-1:000000000000:" + i.getArgument(2));

        return new StepFunctionsService(factory, region, mock(AslExecutor.class), new ObjectMapper(),
                mock(SfnMockLoader.class), mock(StepFunctionsEventPublisher.class));
    }
}
