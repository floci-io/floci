package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.BatchGetTriggersResponse;
import software.amazon.awssdk.services.glue.model.JobRun;
import software.amazon.awssdk.services.glue.model.JobRunState;
import software.amazon.awssdk.services.glue.model.LogicalOperator;
import software.amazon.awssdk.services.glue.model.Trigger;
import software.amazon.awssdk.services.glue.model.TriggerState;
import software.amazon.awssdk.services.glue.model.TriggerType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Glue triggers")
class GlueTriggerTest {

    private static final String EXTRACT = TestFixtures.uniqueName("trigger_extract");
    private static final String LOAD = TestFixtures.uniqueName("trigger_load");
    private static final String MANUAL = TestFixtures.uniqueName("trigger_manual");
    private static final String CHAIN = TestFixtures.uniqueName("trigger_chain");
    private static final String ROLE = "arn:aws:iam::000000000000:role/glue";

    private static GlueClient glue;

    @BeforeAll
    static void setup() {
        glue = TestFixtures.glueClient();
        for (String job : List.of(EXTRACT, LOAD)) {
            glue.createJob(r -> r.name(job).role(ROLE).command(c -> c.name("glueetl"))
                    .executionProperty(p -> p.maxConcurrentRuns(5)));
        }
        glue.createTrigger(r -> r.name(MANUAL).type(TriggerType.ON_DEMAND)
                .actions(a -> a.jobName(EXTRACT)).tags(Map.of("team", "data")));
        glue.createTrigger(r -> r.name(CHAIN).type(TriggerType.CONDITIONAL).startOnCreation(true)
                .actions(a -> a.jobName(LOAD))
                .predicate(p -> p.conditions(c -> c.logicalOperator(LogicalOperator.EQUALS)
                        .jobName(EXTRACT).state(JobRunState.SUCCEEDED))));
    }

    @AfterAll
    static void cleanup() {
        if (glue == null) {
            return;
        }
        for (String trigger : List.of(MANUAL, CHAIN)) {
            try {
                glue.deleteTrigger(r -> r.name(trigger));
            }
            catch (Exception ignored) {
                // Cleanup is best effort: the names are unique to this run and the emulator is disposable.
            }
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
    @DisplayName("StartTrigger runs an ON_DEMAND trigger and a CONDITIONAL trigger follows it")
    void onDemandThenConditional() {
        glue.startTrigger(r -> r.name(MANUAL));

        JobRun extractRun = glue.getJobRuns(r -> r.jobName(EXTRACT)).jobRuns().get(0);
        assertThat(extractRun.triggerName()).isEqualTo(MANUAL);
        JobRun loadRun = glue.getJobRuns(r -> r.jobName(LOAD)).jobRuns().get(0);
        assertThat(loadRun.triggerName()).isEqualTo(CHAIN);
        assertThat(loadRun.jobRunState()).isEqualTo(JobRunState.SUCCEEDED);
    }

    @Test
    @DisplayName("GetTrigger, BatchGetTriggers and ListTriggers parse with the SDK")
    void readTriggers() {
        Trigger chain = glue.getTrigger(r -> r.name(CHAIN)).trigger();
        assertThat(chain.type()).isEqualTo(TriggerType.CONDITIONAL);
        assertThat(chain.state()).isEqualTo(TriggerState.ACTIVATED);
        assertThat(chain.predicate().conditions().get(0).jobName()).isEqualTo(EXTRACT);

        BatchGetTriggersResponse batch = glue.batchGetTriggers(r -> r.triggerNames(MANUAL, "absent_trigger"));
        assertThat(batch.triggers()).extracting(Trigger::name).containsExactly(MANUAL);
        assertThat(batch.triggersNotFound()).containsExactly("absent_trigger");

        assertThat(glue.listTriggers(r -> r.tags(Map.of("team", "data"))).triggerNames()).contains(MANUAL);
    }

    @Test
    @DisplayName("StopTrigger and UpdateTrigger round trip")
    void stopAndUpdate() {
        glue.stopTrigger(r -> r.name(CHAIN));
        assertThat(glue.getTrigger(r -> r.name(CHAIN)).trigger().state()).isEqualTo(TriggerState.DEACTIVATED);

        Trigger updated = glue.updateTrigger(r -> r.name(CHAIN).triggerUpdate(u -> u.description("load after extract")))
                .trigger();
        assertThat(updated.description()).isEqualTo("load after extract");

        glue.startTrigger(r -> r.name(CHAIN));
        assertThat(glue.getTrigger(r -> r.name(CHAIN)).trigger().state()).isEqualTo(TriggerState.ACTIVATED);
    }
}
