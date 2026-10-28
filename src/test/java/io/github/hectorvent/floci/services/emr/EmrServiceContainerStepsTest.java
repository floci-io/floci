package io.github.hectorvent.floci.services.emr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.emr.model.EmrCluster;
import io.github.hectorvent.floci.services.emr.model.EmrStep;
import io.github.hectorvent.floci.services.emrserverless.EmrServerlessSparkRunner;
import io.github.hectorvent.floci.services.emrserverless.SparkJobRunner;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.InstanceProfile;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.github.hectorvent.floci.services.emr.EmrServiceFixture.ACCOUNT;
import static io.github.hectorvent.floci.services.emr.EmrServiceFixture.REGION;
import static io.github.hectorvent.floci.services.emr.EmrServiceFixture.cluster;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code docker} runner mode: each step runs in a container from when it goes RUNNING until its Spark
 * driver ends, and then is COMPLETED, or FAILED with its ActionOnFailure taken, as the API reference and
 * the Management Guide give it.
 */
class EmrServiceContainerStepsTest {

    private static final String REPORT = "s3://bucket/jobs/report.py";
    private static final String DRIVER_FAILED = "The Spark driver exited with code 1.";

    private final FakeRunner runner = new FakeRunner();
    private final EmrServiceFixture fixture = EmrServiceFixture.docker(runner, null);
    private final EmrService service = fixture.service;

    @Test
    void aStepRunsInAContainerFromWhenItStartsUntilItsDriverExits() {
        EmrCluster requested = cluster("real", true, sparkStep("report", "CONTINUE"));
        requested.setConfigurations("""
                [{"Classification": "spark-defaults", "Properties": {"spark.executor.cores": "2"}}]""");
        EmrCluster cluster = service.runJobFlow(requested, REGION);
        String stepId = cluster.getSteps().get(0).getId();

        assertEquals("RUNNING", service.describeStep(cluster.getId(), stepId).getState());
        assertEquals("RUNNING", service.describeCluster(cluster.getId()).getState());
        fixture.clock.advance(Duration.ofSeconds(30));
        fixture.runStepThread();

        EmrStep completed = service.describeStep(cluster.getId(), stepId);
        assertEquals("COMPLETED", completed.getState());
        assertEquals(30, Duration.between(completed.getStartDateTime(), completed.getEndDateTime()).toSeconds());
        assertEquals("WAITING", service.describeCluster(cluster.getId()).getState());
        assertEquals(List.of("STARTING", "PENDING report", "RUNNING report", "COMPLETED report", "WAITING"),
                fixture.states());
        SparkJobRunner.Job job = runner.jobs.get(0);
        assertEquals("emr", job.service());
        assertEquals(stepId, job.runId());
        assertEquals(ACCOUNT, job.accountId());
        assertEquals(REGION, job.region());
        assertEquals("emr-7.5.0", job.releaseLabel());
        assertNull(job.imageTemplate());
        assertNull(job.dockerNetwork());
        assertEquals(List.of(REPORT, "s3://bucket/out/"), job.application());
        assertEquals(Map.of("spark.executor.cores", "2"), job.sparkDefaults());
        assertEquals(Duration.ZERO, job.timeout());
        assertNull(job.logFolder());
        assertNull(job.cloudWatchLogs());
    }

    @Test
    void aStepsLogsGoUnderItsClustersLogUri() {
        EmrCluster requested = cluster("logged", true, sparkStep("report", "CONTINUE"));
        // The DescribeCluster example in the Management Guide has a LogUri in the s3n:// scheme.
        requested.setLogUri("s3n://aws-logs-" + ACCOUNT + "-" + REGION + "/elasticmapreduce/");
        EmrCluster cluster = service.runJobFlow(requested, REGION);

        fixture.runStepThread();

        assertEquals("s3n://aws-logs-" + ACCOUNT + "-" + REGION + "/elasticmapreduce/" + cluster.getId() + "/steps/"
                + cluster.getSteps().get(0).getId() + "/", runner.jobs.get(0).logFolder());
    }

    @Test
    void aStepWhoseDriverFailsIsFailedAndOnContinueTheNextOneRuns() {
        EmrCluster cluster = service.runJobFlow(cluster("continuing", true,
                sparkStep("breaks", "CONTINUE"), sparkStep("next", "CONTINUE")), REGION);
        String failedId = cluster.getSteps().get(0).getId();

        runner.outcome = SparkJobRunner.Outcome.exitedWith(1);
        fixture.runStepThread();

        EmrStep failed = service.describeStep(cluster.getId(), failedId);
        assertEquals("FAILED", failed.getState());
        assertEquals("Unknown Error", failed.getFailureReason());
        assertEquals(DRIVER_FAILED, failed.getFailureMessage());
        assertEquals("RUNNING", service.describeStep(cluster.getId(), cluster.getSteps().get(1).getId()).getState());
        // The step's FAILED, then the next one's RUNNING.
        JsonNode event = fixture.published.get(fixture.published.size() - 2).path("detail");
        assertEquals("FAILED", event.path("state").asText());
        assertEquals("ERROR", event.path("severity").asText());
        assertEquals("Step " + failedId + " (breaks) in Amazon EMR cluster " + cluster.getId() + " (continuing) failed"
                + " at 2026-01-01 00:00 UTC.", event.path("message").asText());
        runner.outcome = SparkJobRunner.Outcome.exitedWith(0);
        fixture.runStepThread();
        assertEquals("WAITING", service.describeCluster(cluster.getId()).getState());
    }

    @Test
    void terminateClusterEndsTheClusterWithErrorsAndCancelsTheStepsLeft() {
        EmrCluster cluster = service.runJobFlow(cluster("doomed", true,
                sparkStep("breaks", "TERMINATE_CLUSTER"), sparkStep("never", "CONTINUE")), REGION);

        runner.outcome = SparkJobRunner.Outcome.exitedWith(1);
        fixture.runStepThread();

        EmrCluster ended = service.describeCluster(cluster.getId());
        assertEquals("TERMINATED_WITH_ERRORS", ended.getState());
        assertEquals("STEP_FAILURE", ended.getStateChangeReasonCode());
        assertEquals("Shut down as step failed", ended.getStateChangeMessage());
        assertEquals("CANCELLED", service.describeStep(cluster.getId(), cluster.getSteps().get(1).getId()).getState());
        assertEquals(1, runner.jobs.size());
        assertEquals(List.of("STARTING", "PENDING breaks", "PENDING never", "RUNNING breaks", "FAILED breaks",
                "CANCELLED never", "TERMINATED_WITH_ERRORS"), fixture.states());
        JsonNode event = fixture.published.get(fixture.published.size() - 1).path("detail");
        assertEquals("CRITICAL", event.path("severity").asText());
        assertEquals("{\"code\":\"STEP_FAILURE\",\"message\":\"Shut down as step failed\"}",
                event.path("stateChangeReason").asText());
        assertEquals("Amazon EMR Cluster " + cluster.getId() + " (doomed) has terminated with errors at"
                + " 2026-01-01 00:00 UTC with a reason of STEP_FAILURE.", event.path("message").asText());
    }

    @Test
    void terminateClusterLeavesAProtectedClusterThatWaitsAfterItsStepsRunning() {
        EmrCluster requested = cluster("protected", true, sparkStep("breaks", "TERMINATE_CLUSTER"),
                sparkStep("next", "CONTINUE"));
        requested.setTerminationProtected(true);
        EmrCluster cluster = service.runJobFlow(requested, REGION);

        runner.outcome = SparkJobRunner.Outcome.exitedWith(1);
        fixture.runStepThread();

        assertEquals("RUNNING", service.describeCluster(cluster.getId()).getState());
        assertEquals("RUNNING", service.describeStep(cluster.getId(), cluster.getSteps().get(1).getId()).getState());
    }

    @Test
    void terminateClusterEndsAProtectedClusterSetToEndAfterItsSteps() {
        EmrCluster requested = cluster("transient", false, sparkStep("breaks", "TERMINATE_CLUSTER"));
        requested.setTerminationProtected(true);
        EmrCluster cluster = service.runJobFlow(requested, REGION);

        runner.outcome = SparkJobRunner.Outcome.exitedWith(1);
        fixture.runStepThread();

        assertEquals("TERMINATED_WITH_ERRORS", service.describeCluster(cluster.getId()).getState());
    }

    @Test
    void cancelAndWaitCancelsThePendingStepsAndWaits() {
        EmrCluster cluster = service.runJobFlow(cluster("waits", true,
                sparkStep("breaks", "CANCEL_AND_WAIT"), sparkStep("cancelled", "CONTINUE")), REGION);

        runner.outcome = SparkJobRunner.Outcome.exitedWith(1);
        fixture.runStepThread();

        EmrCluster waiting = service.describeCluster(cluster.getId());
        assertEquals("WAITING", waiting.getState());
        assertEquals("CANCELLED", waiting.getSteps().get(1).getState());
        assertEquals(1, runner.jobs.size());
    }

    @Test
    void cancelAndWaitEndsAClusterSetToEndAfterItsSteps() {
        EmrCluster cluster = service.runJobFlow(cluster("transient", false,
                sparkStep("breaks", "CANCEL_AND_WAIT"), sparkStep("never", "CONTINUE")), REGION);

        runner.outcome = SparkJobRunner.Outcome.exitedWith(1);
        fixture.runStepThread();

        EmrCluster ended = service.describeCluster(cluster.getId());
        assertEquals("TERMINATED_WITH_ERRORS", ended.getState());
        assertEquals("STEP_FAILURE", ended.getStateChangeReasonCode());
        assertEquals("CANCELLED", ended.getSteps().get(1).getState());
    }

    @Test
    void actionOnFailureDoesNotActivateWhileTheConcurrencyLevelIsAboveOne() {
        EmrCluster requested = cluster("parallel", true, sparkStep("breaks", "TERMINATE_CLUSTER"),
                sparkStep("alongside", "CONTINUE"));
        requested.setStepConcurrencyLevel(2);
        EmrCluster cluster = service.runJobFlow(requested, REGION);

        runner.outcome = SparkJobRunner.Outcome.exitedWith(1);
        fixture.runStepThread();

        assertEquals("RUNNING", service.describeCluster(cluster.getId()).getState());
        assertEquals("RUNNING", service.describeStep(cluster.getId(), cluster.getSteps().get(1).getId()).getState());
    }

    @Test
    void aStepFlociDoesNotRunFailsWithoutRunning() {
        EmrStep hive = sparkStep("query", "CONTINUE");
        hive.setArgs(List.of("hive-script", "--run-hive-script", "--args", "-f", "s3://bucket/query.q"));
        EmrCluster cluster = service.runJobFlow(cluster("hive", true, hive), REGION);

        fixture.runStepThread();

        EmrStep failed = service.describeStep(cluster.getId(), cluster.getSteps().get(0).getId());
        assertEquals("FAILED", failed.getState());
        assertEquals(EmrSparkSteps.NOT_RUN, failed.getFailureMessage());
        assertEquals(List.of(), runner.jobs);
    }

    @Test
    void describeStepAnswersAFailedStepsFailureDetails() {
        EmrCluster cluster = service.runJobFlow(cluster("described", true, sparkStep("breaks", "CONTINUE")), REGION);
        runner.outcome = SparkJobRunner.Outcome.couldNotStart("Could not pull image: Downloading");
        fixture.runStepThread();
        ObjectMapper objectMapper = new ObjectMapper();

        JsonNode answer = (JsonNode) new EmrHandler(service, objectMapper).handle("DescribeStep",
                objectMapper.createObjectNode().put("ClusterId", cluster.getId())
                        .put("StepId", cluster.getSteps().get(0).getId()), REGION).getEntity();

        JsonNode status = answer.path("Step").path("Status");
        assertEquals("FAILED", status.path("State").asText());
        assertEquals("Unknown Error", status.path("FailureDetails").path("Reason").asText());
        assertEquals("The step could not start: Could not pull image: Downloading",
                status.path("FailureDetails").path("Message").asText());
    }

    @Test
    void cancellingARunningStepStopsItsDriverAndStartsTheNext() {
        EmrCluster cluster = service.runJobFlow(cluster("cancelling", true,
                sparkStep("stopped", "TERMINATE_CLUSTER"), sparkStep("next", "CONTINUE")), REGION);
        String stoppedId = cluster.getSteps().get(0).getId();
        runner.whileRunning = () -> service.cancelSteps(cluster.getId(), List.of(stoppedId));

        fixture.runStepThread();

        assertEquals(Set.of(stoppedId), runner.stopped);
        EmrCluster running = service.describeCluster(cluster.getId());
        assertEquals("CANCELLED", running.getSteps().get(0).getState());
        // "Canceling a running step does not trigger the step ActionOnFailure."
        assertEquals("RUNNING", running.getState());
        assertEquals("RUNNING", running.getSteps().get(1).getState());
    }

    @Test
    void aStepCancelledBeforeItsDriverStartsNeverStartsIt() {
        EmrCluster cluster = service.runJobFlow(cluster("early", true, sparkStep("never", "CONTINUE")), REGION);

        service.cancelSteps(cluster.getId(), List.of(cluster.getSteps().get(0).getId()));
        fixture.runStepThread();

        assertEquals(List.of(), runner.driversStarted);
        assertEquals("CANCELLED", service.describeCluster(cluster.getId()).getSteps().get(0).getState());
    }

    @Test
    void terminatingTheClusterStopsTheDriversOfItsRunningSteps() {
        EmrCluster cluster = service.runJobFlow(cluster("ended", true, sparkStep("stopped", "CONTINUE")), REGION);
        String stepId = cluster.getSteps().get(0).getId();
        runner.whileRunning = () -> service.terminateJobFlows(List.of(cluster.getId()));

        fixture.runStepThread();

        assertEquals(Set.of(stepId), runner.stopped);
        EmrCluster ended = service.describeCluster(cluster.getId());
        assertEquals("TERMINATED", ended.getState());
        assertEquals("USER_REQUEST", ended.getStateChangeReasonCode());
        assertEquals("CANCELLED", ended.getSteps().get(0).getState());
    }

    @Test
    void aStepRunningWhenFlociStoppedFailsOnceFlociIsBack() {
        EmrCluster cluster = service.runJobFlow(cluster("restarted", true, sparkStep("cut", "CONTINUE")), REGION);
        String stepId = cluster.getSteps().get(0).getId();

        EmrServiceFixture restarted = fixture.restart();
        restarted.pass(Duration.ZERO);

        EmrStep failed = restarted.service.describeStep(cluster.getId(), stepId);
        assertEquals("FAILED", failed.getState());
        assertEquals(EmrSparkSteps.INTERRUPTED, failed.getFailureMessage());
        assertEquals(List.of("FAILED cut", "WAITING"), restarted.states());
    }

    @Test
    void aStepsThreadWritesNothingOnceAResetHasBegun() {
        service.runJobFlow(cluster("reset", true, sparkStep("cut", "CONTINUE")), REGION);
        // The reset's teardown stops the driver while it runs; beforeReset() comes before the storage wipe.
        runner.whileRunning = service::beforeReset;

        fixture.runStepThread();

        assertEquals(List.of("STARTING", "PENDING cut", "RUNNING cut"), fixture.states());
        // The runner stops a running driver whose step the reset removed.
        assertEquals(List.of(false, true), runner.cancelledAsRunning);
    }

    @Test
    void aDriverFlociStoppedLeavesItsStepForTheResetOrTheRestartToEnd() {
        EmrCluster cluster = service.runJobFlow(
                cluster("stopped", true, sparkStep("cut", "CONTINUE"), sparkStep("next", "CONTINUE")), REGION);
        String stepId = cluster.getSteps().get(0).getId();
        // A reset's teardown, or Floci's shutdown, stops the driver; the step asked for no stop.
        runner.outcome = SparkJobRunner.Outcome.halted();

        fixture.runStepThread();

        // Taking CONTINUE now would start the next step's driver after the teardown has passed.
        assertEquals("RUNNING", service.describeStep(cluster.getId(), stepId).getState());
        assertEquals(0, fixture.stepThreadsWaiting());
        assertEquals(List.of("STARTING", "PENDING cut", "PENDING next", "RUNNING cut"), fixture.states());
        EmrServiceFixture restarted = fixture.restart();
        restarted.pass(Duration.ZERO);
        assertEquals("FAILED", restarted.service.describeStep(cluster.getId(), stepId).getState());
        assertEquals(List.of("FAILED cut", "RUNNING next"), restarted.states());
    }

    @Test
    void leftoverDriversAreRemovedBeforeARestartedClusterCanStartAStep() {
        service.runJobFlow(cluster("restarted", true, sparkStep("cut", "CONTINUE"), sparkStep("next", "CONTINUE")),
                REGION);
        SparkJobRunner restartedRunner = mock(SparkJobRunner.class);
        EmrService.Wakeups wakeups = mock(EmrService.Wakeups.class);

        fixture.restartReconciling(restartedRunner, wakeups);

        // The wakeup that fails the step Floci was running starts the next one, whose driver the sweep would remove.
        InOrder order = inOrder(restartedRunner, wakeups);
        order.verify(restartedRunner).removeLeftoverDrivers("emr");
        order.verify(wakeups).at(any(), any());
    }

    @Test
    void aStepRunsAsItsRuntimeRoleOrElseAsTheClustersInstanceProfileRole() {
        IamService iam = mock(IamService.class);
        InstanceProfile profile = new InstanceProfile();
        profile.setRoleNames(List.of("emr-ec2"));
        IamRole role = new IamRole();
        role.setArn("arn:aws:iam::" + ACCOUNT + ":role/emr-ec2");
        when(iam.findInstanceProfile(ACCOUNT, "EMR_EC2_DefaultRole")).thenReturn(Optional.of(profile));
        when(iam.findRole(ACCOUNT, "emr-ec2")).thenReturn(Optional.of(role));
        EmrServiceFixture withIam = EmrServiceFixture.docker(runner, iam);
        EmrStep runtime = sparkStep("runtime", "CONTINUE");
        runtime.setExecutionRoleArn("arn:aws:iam::" + ACCOUNT + ":role/step");
        EmrCluster requested = cluster("roles", true, sparkStep("profile", "CONTINUE"), runtime);
        requested.setJobFlowRole("EMR_EC2_DefaultRole");

        withIam.service.runJobFlow(requested, REGION);
        withIam.runStepThread();
        withIam.runStepThread();

        assertEquals("arn:aws:iam::" + ACCOUNT + ":role/emr-ec2", runner.jobs.get(0).executionRole());
        assertEquals("arn:aws:iam::" + ACCOUNT + ":role/step", runner.jobs.get(1).executionRole());
    }

    @Test
    void aStepRunsWithoutARoleWhenIamKnowsNone() {
        service.runJobFlow(cluster("roleless", true, sparkStep("plain", "CONTINUE")), REGION);

        fixture.runStepThread();

        assertNull(runner.jobs.get(0).executionRole());
    }

    @Test
    void leftoverDriversAreRemovedWhenTheServiceStartsUnlessReconcilingIsOff() {
        for (boolean reconcile : List.of(true, false)) {
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.services().emr().runnerMode()).thenReturn("docker");
            when(config.services().emr().reconcileContainersOnStartup()).thenReturn(reconcile);
            EmrServerlessSparkRunner sparkRunner = mock(EmrServerlessSparkRunner.class);
            RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);

            new EmrService(new InMemoryStorageFactory(ACCOUNT), config, regionResolver,
                    new EmrEventPublisher(mock(EventBridgeService.class), new ObjectMapper(), regionResolver),
                    sparkRunner, mock(IamService.class)).shutdown();

            verify(sparkRunner, times(reconcile ? 1 : 0)).removeLeftoverDrivers("emr");
        }
    }

    // A spark-submit step through command-runner.jar, written for a cluster with YARN.
    private static EmrStep sparkStep(String name, String actionOnFailure) {
        EmrStep step = new EmrStep();
        step.setName(name);
        step.setActionOnFailure(actionOnFailure);
        step.setJar("command-runner.jar");
        step.setArgs(new ArrayList<>(List.of("spark-submit", "--deploy-mode", "cluster", REPORT, "s3://bucket/out/")));
        return step;
    }

    /**
     * Runs a step's driver on the test's thread: it starts, does what the test asks, and ends with
     * {@code outcome}, or stopped once {@link #stop} has been called for it.
     */
    private static final class FakeRunner implements SparkJobRunner {
        final List<Job> jobs = new ArrayList<>();
        final Set<String> stopped = new HashSet<>();
        final List<String> driversStarted = new ArrayList<>();
        // What progress.cancelled() said once the driver ran, and again after whileRunning.
        final List<Boolean> cancelledAsRunning = new ArrayList<>();
        Outcome outcome = Outcome.exitedWith(0);
        Runnable whileRunning = () -> { };

        @Override
        public Outcome run(Job job, Progress progress) {
            jobs.add(job);
            // As the Docker runner, a stop counts only for a run in progress, so a cancel before then is read here.
            if (progress.cancelled()) {
                return Outcome.halted();
            }
            driversStarted.add(job.runId());
            progress.started();
            cancelledAsRunning.add(progress.cancelled());
            whileRunning.run();
            cancelledAsRunning.add(progress.cancelled());
            return stopped.contains(job.runId()) ? Outcome.halted() : outcome;
        }

        @Override
        public void stop(String runId) {
            stopped.add(runId);
        }

        @Override
        public void removeLeftoverDrivers(String service) {
            // A test's drivers live no longer than its runs.
        }
    }
}
