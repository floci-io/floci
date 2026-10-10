package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ServiceRegistry;
import io.github.hectorvent.floci.services.redshiftdata.RedshiftDataJsonHandler;
import io.github.hectorvent.floci.services.redshiftdata.RedshiftDataService;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AslExecutorRedshiftDataDisabledTest {

    @ParameterizedTest
    @CsvSource({"false,true", "true,false"})
    void disabledServiceFailsTaskWithoutInvokingHandlerOrService(boolean parentEnabled, boolean childEnabled) {
        runTask(parentEnabled, childEnabled, "", "FAILED",
                "RedshiftData.ServiceNotAvailableException", "Service redshift-data is not enabled.");
    }

    @ParameterizedTest
    @ValueSource(strings = {".sync", ".sync:2"})
    void unsupportedSyncResourceFailsWithoutInvokingHandlerOrService(String suffix) {
        runTask(true, true, suffix, "FAILED", "States.TaskFailed",
                "Unsupported resource: arn:aws:states:::aws-sdk:redshiftdata:listStatements" + suffix);
    }

    @Test
    void bothEnabledServiceInvokesOrdinaryTask() {
        runTask(true, true, "", "SUCCEEDED", null, null);
    }

    private void runTask(boolean parentEnabled, boolean childEnabled, String suffix,
                         String status, String error, String cause) {
        ObjectMapper objectMapper = new ObjectMapper();
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        ServiceRegistry serviceRegistry = mock(ServiceRegistry.class);
        when(serviceRegistry.isServiceEnabled("redshift-data")).thenReturn(parentEnabled && childEnabled);
        RedshiftDataService service = mock(RedshiftDataService.class);
        when(service.listStatements(any())).thenReturn(objectMapper.createObjectNode());
        RedshiftDataJsonHandler handler = spy(new RedshiftDataJsonHandler(service));
        AslExecutor executor = new AslExecutor(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, objectMapper,
                new JsonataEvaluator(objectMapper), null, config, null, null, handler, serviceRegistry);

        StateMachine stateMachine = new StateMachine();
        stateMachine.setStateMachineArn("arn:aws:states:us-east-1:000000000000:stateMachine:disabled-data");
        stateMachine.setDefinition("""
                {"StartAt":"List","States":{"List":{"Type":"Task",
                  "Resource":"arn:aws:states:::aws-sdk:redshiftdata:listStatements","End":true}}}
                """.replace("listStatements\"", "listStatements" + suffix + "\""));
        Execution execution = new Execution();
        execution.setExecutionArn("arn:aws:states:us-east-1:000000000000:execution:disabled-data:run");
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput("{}");
        try {
            executor.executeSync(stateMachine, execution, new ArrayList<>(), (updated, events) -> { });

            assertEquals(status, execution.getStatus());
            assertEquals(error, execution.getError());
            assertEquals(cause, execution.getCause());
            if ("SUCCEEDED".equals(status)) {
                verify(service).listStatements(any());
                assertEquals("{}", execution.getOutput());
            } else {
                verifyNoInteractions(handler, service);
            }
        } finally {
            executor.stop();
        }
    }
}
