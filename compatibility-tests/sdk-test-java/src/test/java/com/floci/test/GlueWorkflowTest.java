package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.JobRunState;
import software.amazon.awssdk.services.glue.model.LogicalOperator;
import software.amazon.awssdk.services.glue.model.Node;
import software.amazon.awssdk.services.glue.model.NodeType;
import software.amazon.awssdk.services.glue.model.TriggerType;
import software.amazon.awssdk.services.glue.model.Workflow;
import software.amazon.awssdk.services.glue.model.WorkflowRun;
import software.amazon.awssdk.services.glue.model.WorkflowRunStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Glue workflows")
class GlueWorkflowTest {

    private static final String WORKFLOW = TestFixtures.uniqueName("workflow");
    private static final String EXTRACT = TestFixtures.uniqueName("workflow_extract");
    private static final String LOAD = TestFixtures.uniqueName("workflow_load");
    private static final String START = TestFixtures.uniqueName("workflow_start");
    private static final String THEN = TestFixtures.uniqueName("workflow_then");
    private static final String ROLE = "arn:aws:iam::000000000000:role/glue";

    private static GlueClient glue;

    @BeforeAll
    static void setup() {
        glue = TestFixtures.glueClient();
        for (String job : List.of(EXTRACT, LOAD)) {
            glue.createJob(r -> r.name(job).role(ROLE).command(c -> c.name("glueetl")));
        }
        glue.createWorkflow(r -> r.name(WORKFLOW).defaultRunProperties(Map.of("env", "dev")));
        glue.createTrigger(r -> r.name(START).workflowName(WORKFLOW).type(TriggerType.ON_DEMAND)
                .actions(a -> a.jobName(EXTRACT)));
        glue.createTrigger(r -> r.name(THEN).workflowName(WORKFLOW).type(TriggerType.CONDITIONAL)
                .startOnCreation(true).actions(a -> a.jobName(LOAD))
                .predicate(p -> p.conditions(c -> c.logicalOperator(LogicalOperator.EQUALS)
                        .jobName(EXTRACT).state(JobRunState.SUCCEEDED))));
    }

    @AfterAll
    static void cleanup() {
        if (glue == null) {
            return;
        }
        for (String trigger : List.of(START, THEN)) {
            try {
                glue.deleteTrigger(r -> r.name(trigger));
            }
            catch (Exception ignored) {
                // Cleanup is best effort: the names are unique to this run and the emulator is disposable.
            }
        }
        try {
            glue.deleteWorkflow(r -> r.name(WORKFLOW));
        }
        catch (Exception ignored) {
            // Cleanup is best effort, as above.
        }
        for (String job : List.of(EXTRACT, LOAD)) {
            try {
                glue.deleteJob(r -> r.jobName(job));
            }
            catch (Exception ignored) {
                // Cleanup is best effort, as above.
            }
        }
        glue.close();
    }

    @Test
    @DisplayName("StartWorkflowRun runs the triggers to completion and GetWorkflowRun parses")
    void runToCompletion() {
        String runId = glue.startWorkflowRun(r -> r.name(WORKFLOW).runProperties(Map.of("day", "2026-09-26"))).runId();

        WorkflowRun run = glue.getWorkflowRun(r -> r.name(WORKFLOW).runId(runId).includeGraph(true)).run();
        assertThat(run.status()).isEqualTo(WorkflowRunStatus.COMPLETED);
        assertThat(run.statistics().succeededActions()).isEqualTo(2);
        assertThat(run.workflowRunProperties()).containsEntry("env", "dev").containsEntry("day", "2026-09-26");
        Node load = run.graph().nodes().stream().filter(n -> LOAD.equals(n.name())).findFirst().orElseThrow();
        assertThat(load.jobDetails().jobRuns()).hasSize(1);
        assertThat(load.jobDetails().jobRuns().get(0).jobRunState()).isEqualTo(JobRunState.SUCCEEDED);
    }

    @Test
    @DisplayName("GetWorkflow with IncludeGraph and ListWorkflows parse")
    void readWorkflow() {
        Workflow workflow = glue.getWorkflow(r -> r.name(WORKFLOW).includeGraph(true)).workflow();
        assertThat(workflow.name()).isEqualTo(WORKFLOW);
        assertThat(workflow.createdOn()).isNotNull();
        assertThat(workflow.graph().nodes()).extracting(Node::type)
                .containsExactlyInAnyOrder(NodeType.TRIGGER, NodeType.TRIGGER, NodeType.JOB, NodeType.JOB);
        assertThat(workflow.graph().edges()).hasSize(3);
        assertThat(glue.listWorkflows(r -> r.maxResults(25)).workflows()).contains(WORKFLOW);
    }
}
