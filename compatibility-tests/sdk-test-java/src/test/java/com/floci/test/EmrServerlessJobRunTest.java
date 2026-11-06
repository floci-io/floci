package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.emrserverless.EmrServerlessClient;
import software.amazon.awssdk.services.emrserverless.model.Application;
import software.amazon.awssdk.services.emrserverless.model.ApplicationState;
import software.amazon.awssdk.services.emrserverless.model.ConflictException;
import software.amazon.awssdk.services.emrserverless.model.JobRun;
import software.amazon.awssdk.services.emrserverless.model.JobRunAttemptSummary;
import software.amazon.awssdk.services.emrserverless.model.JobRunMode;
import software.amazon.awssdk.services.emrserverless.model.JobRunState;
import software.amazon.awssdk.services.emrserverless.model.JobRunSummary;
import software.amazon.awssdk.services.emrserverless.model.ResourceNotFoundException;
import software.amazon.awssdk.services.emrserverless.model.StartJobRunResponse;
import software.amazon.awssdk.services.emrserverless.model.ValidationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EMR Serverless job runs")
class EmrServerlessJobRunTest {

    private static final String ROLE = TestFixtures.globalArn("iam", "000000000000", "role/emr-serverless-job");

    private static EmrServerlessClient emr;
    private static String applicationId;

    @BeforeAll
    static void setup() {
        emr = TestFixtures.emrServerlessClient();
        applicationId = emr.createApplication(r -> r
                .name(TestFixtures.uniqueName("jobs-app"))
                .releaseLabel("emr-7.5.0")
                .type("SPARK")
                .clientToken(UUID.randomUUID().toString())).applicationId();
    }

    @AfterAll
    static void cleanup() {
        if (emr == null) {
            return;
        }
        try {
            emr.stopApplication(r -> r.applicationId(applicationId));
            emr.deleteApplication(r -> r.applicationId(applicationId));
        }
        catch (Exception ignored) {
            // Cleanup is best effort: the application is unique to this run and the emulator is disposable.
        }
        emr.close();
    }

    private static StartJobRunResponse startSparkRun(JobRunMode mode) {
        return emr.startJobRun(r -> r
                .applicationId(applicationId)
                .executionRoleArn(ROLE)
                .name("nightly")
                .mode(mode)
                .tags(Map.of("team", "data"))
                .jobDriver(d -> d.sparkSubmit(s -> s
                        .entryPoint("s3://bucket/jobs/app.jar")
                        .entryPointArguments("s3://bucket/in")
                        .sparkSubmitParameters("--class example.Main"))));
    }

    @Test
    @DisplayName("StartJobRun auto-starts the application and GetJobRun reports a finished run")
    void startAndGet() {
        Instant before = Instant.now().minus(1, ChronoUnit.DAYS);
        StartJobRunResponse started = startSparkRun(JobRunMode.BATCH);
        Instant after = Instant.now().plus(1, ChronoUnit.DAYS);

        assertThat(started.applicationId()).isEqualTo(applicationId);
        assertThat(started.arn()).endsWith("/applications/" + applicationId + "/jobruns/" + started.jobRunId());
        Application application = emr.getApplication(r -> r.applicationId(applicationId)).application();
        assertThat(application.state()).isEqualTo(ApplicationState.STARTED);
        // Epoch seconds on the wire; milliseconds would read as a year past 50,000.
        assertThat(application.createdAt()).isBetween(before, after);
        assertThat(application.updatedAt()).isBetween(application.createdAt(), after);

        JobRun run = emr.getJobRun(r -> r.applicationId(applicationId).jobRunId(started.jobRunId())).jobRun();
        assertThat(run.state()).isEqualTo(JobRunState.SUCCESS);
        assertThat(run.executionRole()).isEqualTo(ROLE);
        assertThat(run.releaseLabel()).isEqualTo("emr-7.5.0");
        assertThat(run.createdBy()).startsWith("arn:" + TestFixtures.partition() + ":");
        assertThat(run.createdAt()).isBetween(before, after);
        assertThat(run.jobDriver().sparkSubmit().entryPoint()).isEqualTo("s3://bucket/jobs/app.jar");
        assertThat(run.tags()).containsEntry("team", "data");
        assertThat(run.executionTimeoutMinutes()).isEqualTo(720L);
        assertThat(run.mode()).isEqualTo(JobRunMode.BATCH);
    }

    @Test
    @DisplayName("A repeated client token returns the original run; reused with another request it conflicts")
    void clientTokenIdempotency() {
        String token = UUID.randomUUID().toString();
        String first = emr.startJobRun(r -> r.applicationId(applicationId).clientToken(token)
                .executionRoleArn(ROLE).jobDriver(d -> d.sparkSubmit(s -> s.entryPoint("s3://bucket/a.jar"))))
                .jobRunId();
        String again = emr.startJobRun(r -> r.applicationId(applicationId).clientToken(token)
                .executionRoleArn(ROLE).jobDriver(d -> d.sparkSubmit(s -> s.entryPoint("s3://bucket/a.jar"))))
                .jobRunId();
        assertThat(again).isEqualTo(first);

        assertThatThrownBy(() -> emr.startJobRun(r -> r.applicationId(applicationId).clientToken(token)
                .executionRoleArn(ROLE).jobDriver(d -> d.sparkSubmit(s -> s.entryPoint("s3://bucket/b.jar")))))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("CancelJobRun cancels a streaming run and refuses a finished one")
    void cancel() {
        String streaming = startSparkRun(JobRunMode.STREAMING).jobRunId();
        assertThat(emr.getJobRun(r -> r.applicationId(applicationId).jobRunId(streaming)).jobRun().state())
                .isEqualTo(JobRunState.RUNNING);

        emr.cancelJobRun(r -> r.applicationId(applicationId).jobRunId(streaming));
        assertThat(emr.getJobRun(r -> r.applicationId(applicationId).jobRunId(streaming)).jobRun().state())
                .isEqualTo(JobRunState.CANCELLED);

        assertThatThrownBy(() -> emr.cancelJobRun(r -> r.applicationId(applicationId).jobRunId(streaming)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Job run is not in a cancellable state");
    }

    @Test
    @DisplayName("ListJobRuns returns summaries filtered by state")
    void list() {
        String streaming = startSparkRun(JobRunMode.STREAMING).jobRunId();
        try {
            List<JobRunSummary> running = emr.listJobRuns(r -> r.applicationId(applicationId)
                    .states(JobRunState.RUNNING)).jobRuns();
            assertThat(running).extracting(JobRunSummary::id).contains(streaming);
            assertThat(running).allSatisfy(summary -> assertThat(summary.state()).isEqualTo(JobRunState.RUNNING));
            assertThat(emr.listJobRunsPaginator(r -> r.applicationId(applicationId)).jobRuns())
                    .extracting(JobRunSummary::applicationId).containsOnly(applicationId);
        }
        finally {
            emr.cancelJobRun(r -> r.applicationId(applicationId).jobRunId(streaming));
        }
    }

    @Test
    @DisplayName("A run is its one attempt: GetJobRun and ListJobRunAttempts report attempt 1")
    void attempts() {
        String jobRunId = startSparkRun(JobRunMode.BATCH).jobRunId();

        JobRun run = emr.getJobRun(r -> r.applicationId(applicationId).jobRunId(jobRunId).attempt(1)).jobRun();
        assertThat(run.attempt()).isEqualTo(1);
        assertThat(run.attemptCreatedAt()).isEqualTo(run.createdAt());
        assertThat(run.attemptUpdatedAt()).isEqualTo(run.updatedAt());

        List<JobRunAttemptSummary> attempts = emr.listJobRunAttempts(r -> r
                .applicationId(applicationId).jobRunId(jobRunId)).jobRunAttempts();
        assertThat(attempts).singleElement().satisfies(attempt -> {
            assertThat(attempt.id()).isEqualTo(jobRunId);
            assertThat(attempt.attempt()).isEqualTo(1);
            assertThat(attempt.jobCreatedAt()).isEqualTo(run.createdAt());
            assertThat(attempt.state()).isEqualTo(JobRunState.SUCCESS);
            assertThat(attempt.mode()).isEqualTo(JobRunMode.BATCH);
        });

        assertThatThrownBy(() -> emr.getJobRun(r -> r.applicationId(applicationId).jobRunId(jobRunId).attempt(2)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("A run's ARN takes tags, which GetJobRun reports")
    void jobRunTags() {
        StartJobRunResponse started = startSparkRun(JobRunMode.BATCH);

        emr.tagResource(r -> r.resourceArn(started.arn()).tags(Map.of("env", "dev")));
        assertThat(emr.listTagsForResource(r -> r.resourceArn(started.arn())).tags())
                .containsEntry("team", "data")
                .containsEntry("env", "dev");

        emr.untagResource(r -> r.resourceArn(started.arn()).tagKeys("env"));
        assertThat(emr.getJobRun(r -> r.applicationId(applicationId).jobRunId(started.jobRunId())).jobRun().tags())
                .containsOnlyKeys("team");
    }
}
