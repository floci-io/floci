package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.BatchGetJobsResponse;
import software.amazon.awssdk.services.glue.model.BatchStopJobRunResponse;
import software.amazon.awssdk.services.glue.model.EntityNotFoundException;
import software.amazon.awssdk.services.glue.model.JobRun;
import software.amazon.awssdk.services.glue.model.JobRunState;
import software.amazon.awssdk.services.glue.model.WorkerType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Glue job runs")
class GlueJobRunTest {

    private static final String JOB_NAME = TestFixtures.uniqueName("run_job");
    private static final String ROLE = "arn:aws:iam::000000000000:role/glue";

    private static GlueClient glue;

    @BeforeAll
    static void setup() {
        glue = TestFixtures.glueClient();
        glue.createJob(r -> r
                .name(JOB_NAME)
                .role(ROLE)
                .command(c -> c.name("glueetl").scriptLocation("s3://scripts/etl.py"))
                .workerType(WorkerType.G_1_X)
                .numberOfWorkers(2)
                .tags(Map.of("team", "data")));
    }

    @AfterAll
    static void cleanup() {
        if (glue == null) {
            return;
        }
        try {
            glue.deleteJob(r -> r.jobName(JOB_NAME));
        }
        catch (Exception ignored) {
            // Cleanup is best effort: the job name is unique to this run and the emulator is disposable.
        }
        glue.close();
    }

    @Test
    @DisplayName("StartJobRun, GetJobRun and GetJobRuns parse with the SDK")
    void startAndReadARun() {
        String runId = glue.startJobRun(r -> r
                .jobName(JOB_NAME)
                .arguments(Map.of("--day", "2026-09-25"))
                .numberOfWorkers(4)).jobRunId();

        assertThat(runId).matches("jr_[0-9a-f]{64}");

        JobRun run = glue.getJobRun(r -> r.jobName(JOB_NAME).runId(runId)).jobRun();
        assertThat(run.id()).isEqualTo(runId);
        assertThat(run.jobName()).isEqualTo(JOB_NAME);
        assertThat(run.jobRunState()).isEqualTo(JobRunState.SUCCEEDED);
        assertThat(run.arguments()).containsEntry("--day", "2026-09-25");
        assertThat(run.workerType()).isEqualTo(WorkerType.G_1_X);
        assertThat(run.numberOfWorkers()).isEqualTo(4);
        assertThat(run.startedOn()).isNotNull();
        assertThat(run.completedOn()).isNotNull();

        assertThat(glue.getJobRuns(r -> r.jobName(JOB_NAME)).jobRuns())
                .extracting(JobRun::id)
                .contains(runId);
    }

    @Test
    @DisplayName("BatchStopJobRun reports a finished run under Errors")
    void stopAFinishedRun() {
        String runId = glue.startJobRun(r -> r.jobName(JOB_NAME)).jobRunId();

        BatchStopJobRunResponse response = glue.batchStopJobRun(r -> r.jobName(JOB_NAME).jobRunIds(runId));

        assertThat(response.successfulSubmissions()).isEmpty();
        assertThat(response.errors()).hasSize(1);
        assertThat(response.errors().get(0).jobRunId()).isEqualTo(runId);
        assertThat(response.errors().get(0).errorDetail().errorCode()).isEqualTo("InvalidInputException");
    }

    @Test
    @DisplayName("ListJobs filters on tags and BatchGetJobs reports missing names")
    void listAndBatchGetJobs() {
        assertThat(glue.listJobs(r -> r.tags(Map.of("team", "data"))).jobNames()).contains(JOB_NAME);

        BatchGetJobsResponse response = glue.batchGetJobs(r -> r.jobNames(JOB_NAME, "absent_job"));
        assertThat(response.jobs()).extracting(job -> job.name()).containsExactly(JOB_NAME);
        assertThat(response.jobsNotFound()).containsExactly("absent_job");
    }

    @Test
    @DisplayName("GetJobRun on an unknown run is EntityNotFoundException")
    void unknownRun() {
        assertThatThrownBy(() -> glue.getJobRun(r -> r.jobName(JOB_NAME).runId("jr_missing")))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
