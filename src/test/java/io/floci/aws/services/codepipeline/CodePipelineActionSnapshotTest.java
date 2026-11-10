package io.floci.aws.services.codepipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.codepipeline.model.CodePipelineExecution;
import io.floci.aws.services.codepipeline.model.CodePipelineExecution.ActionExecution;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodePipelineActionSnapshotTest {
    @Test
    void stopTraversalSurvivesAnActionBeingAppended() {
        CodePipelineExecution execution = new CodePipelineExecution();
        execution.getActionExecutions().add(runningAction());
        assertStableTraversal(execution);
    }

    @Test
    void replacingActionsPreservesStableTraversal() {
        CodePipelineExecution execution = new CodePipelineExecution();
        List<ActionExecution> actions = new ArrayList<>(List.of(runningAction()));
        execution.setActionExecutions(actions);
        actions.clear();
        assertEquals(1, execution.getActionExecutions().size());
        assertStableTraversal(execution);
    }

    @Test
    void restoredExecutionPreservesStableTraversal() throws Exception {
        CodePipelineExecution execution = new ObjectMapper().readValue(
                "{\"actionExecutions\":[{\"status\":\"InProgress\"}]}", CodePipelineExecution.class);
        assertStableTraversal(execution);
    }

    private static void assertStableTraversal(CodePipelineExecution execution) {
        ActionExecution appended = runningAction();
        execution.getActionExecutions().stream()
                .filter(action -> "InProgress".equals(action.getStatus()))
                .forEach(action -> {
                    execution.getActionExecutions().add(appended);
                    action.setStatus("Abandoned");
                });
        assertEquals(2, execution.getActionExecutions().size());
        assertEquals("Abandoned", execution.getActionExecutions().getFirst().getStatus());
        assertEquals("InProgress", appended.getStatus());
    }

    private static ActionExecution runningAction() {
        ActionExecution action = new ActionExecution();
        action.setStatus("InProgress");
        return action;
    }
}
