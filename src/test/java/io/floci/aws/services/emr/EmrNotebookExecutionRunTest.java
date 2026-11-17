package io.floci.aws.services.emr;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.emr.model.EmrCluster;
import io.floci.aws.services.emr.model.EmrNotebookExecution;
import io.floci.aws.services.emrserverless.SparkJobRunner;
import io.floci.aws.services.iam.IamService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.floci.aws.services.emr.EmrServiceFixture.ACCOUNT;
import static io.floci.aws.services.emr.EmrServiceFixture.REGION;
import static io.floci.aws.services.emr.EmrServiceFixture.cluster;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Notebook executions in the docker runner mode, each running its notebook on its cluster as a step's command runs:
 * what runs, where the notebook as run goes, and the execution's statuses as the run goes, with a runner that stands in
 * for Docker and the cluster's threads run by hand.
 */
class EmrNotebookExecutionRunTest {

    private static final String NOTEBOOK_APPS = "[{\"Name\":\"Hadoop\"},{\"Name\":\"Spark\"},{\"Name\":\"Livy\"},"
            + "{\"Name\":\"JupyterEnterpriseGateway\"}]";

    private final RecordingRunner runner = new RecordingRunner();
    private final EmrServiceFixture fixture = EmrServiceFixture.docker(runner, mock(IamService.class));
    private final AccountAwareStorageBackend<EmrNotebookExecution> store = AccountAwareStorageBackend.inMemory(ACCOUNT);
    private final EmrNotebookExecutionService executions = service();

    private EmrNotebookExecutionService service() {
        return new EmrNotebookExecutionService(store, fixture.service, new RegionResolver(REGION, ACCOUNT),
                fixture.clock, Duration.ofMinutes(10));
    }

    @Test
    void aNotebookRunsOnItsClusterAndItsRunAsWrittenUnderTheOutputPrefix() {
        String clusterId = notebookCluster();
        EmrNotebookExecution request = request(clusterId);
        request.setNotebookS3Bucket("notebooks");
        request.setNotebookS3Key("team/analysis.ipynb");
        request.setOutputNotebookS3Bucket("results");
        request.setOutputNotebookS3Key("runs/");
        request.setOutputNotebookFormat("HTML");
        request.setNotebookParams("{\"day\":\"2026-11-17\"}");
        request.setEnvironmentVariables(Map.of("KERNEL_EXTRA_SPARK_OPTS", "--conf spark.executor.instances=1"));

        EmrNotebookExecution started = executions.start(request);
        String id = started.getId();

        assertEquals("START_PENDING", started.getStatus());
        assertEquals("s3://results/runs/" + id + "/analysis.html", started.getOutputNotebookUri());
        fixture.runStepThread();
        EmrNotebookExecution finished = executions.describe(id);
        assertEquals("FINISHED", finished.getStatus());
        assertEquals("Execution is finished for cluster " + clusterId + ".", finished.getLastStateChangeReason());
        SparkJobRunner.Job job = runner.jobs.get(0);
        assertEquals(id, job.runId());
        assertEquals(EmrNotebookRunner.command("s3://notebooks/team/analysis.ipynb", "s3://results/runs/" + id,
                "analysis", "{\"day\":\"2026-11-17\"}"), job.command());
        assertEquals(EmrNotebookRunner.PROGRAM, job.environment().get(EmrNotebookRunner.PROGRAM_VARIABLE));
        assertEquals("--conf spark.executor.instances=1", job.environment().get("KERNEL_EXTRA_SPARK_OPTS"));
        // On the cluster's storage, its HDFS and YARN running, as a step's command is.
        assertNotNull(job.storage());
    }

    @Test
    void aCellThatRaisesFailsTheExecutionAndItsRunGoesBesideTheNotebookWithNoPrefix() {
        runner.outcome = SparkJobRunner.Outcome.exitedWith(EmrNotebookRunner.CELL_RAISED);
        String clusterId = notebookCluster();
        EmrNotebookExecution request = request(clusterId);
        request.setNotebookS3Bucket("notebooks");
        request.setNotebookS3Key("team/analysis.ipynb");

        String id = executions.start(request).getId();
        fixture.runStepThread();
        EmrNotebookExecution failed = executions.describe(id);

        assertEquals("FAILED", failed.getStatus());
        assertEquals(String.format(EmrNotebookExecutionService.CELL_FAILED, clusterId),
                failed.getLastStateChangeReason());
        assertEquals("s3://notebooks/team/" + id + "/analysis.ipynb", failed.getOutputNotebookUri());
    }

    @Test
    void aRunThatFailsOtherwiseSaysItsExitCodeRatherThanACellsError() {
        runner.outcome = SparkJobRunner.Outcome.exitedWith(1);
        String clusterId = notebookCluster();

        String id = executions.start(notebookRequest(clusterId)).getId();
        fixture.runStepThread();

        assertEquals(String.format(EmrNotebookExecutionService.RUN_FAILED, clusterId, 1),
                executions.describe(id).getLastStateChangeReason());
    }

    @Test
    void aRunWhoseClusterShutsDownBeforeItStartsRunsNothing() {
        String clusterId = notebookCluster();
        String id = executions.start(notebookRequest(clusterId)).getId();

        fixture.service.terminateJobFlows(List.of(clusterId));
        fixture.runStepThread();

        assertTrue(runner.jobs.isEmpty(), "the notebook of a shut-down cluster started");
        EmrNotebookExecution failed = executions.describe(id);
        assertEquals("FAILED", failed.getStatus());
        assertEquals("Execution failed for cluster " + clusterId + ", as the cluster was terminated.",
                failed.getLastStateChangeReason());
    }

    @Test
    void aRunHandedToItsThreadBeforeATeardownRunsNothingAfterIt() {
        String clusterId = notebookCluster();
        String id = executions.start(notebookRequest(clusterId)).getId();

        // A reset's teardown, then its wipe, before the thread starts.
        fixture.service.stopManagedContainers();
        fixture.service.beforeReset();
        fixture.runStepThread();

        assertTrue(runner.jobs.isEmpty(), "a notebook started after the teardown");
        assertEquals("FAILED", executions.describe(id).getStatus());
    }

    @Test
    void aRunNoThreadTakesFailsAtOnceAndHoldsNothingOfItsCluster() {
        String clusterId = notebookCluster();
        fixture.threadsRefused = true;

        EmrNotebookExecution started = executions.start(notebookRequest(clusterId));

        assertEquals("FAILED", started.getStatus());
        assertTrue(started.getLastStateChangeReason().contains("The pool takes no more threads"),
                started.getLastStateChangeReason());
        assertEquals("FAILED", executions.describe(started.getId()).getStatus());
        // Nothing of the run is left to hold the cluster's storage once it ends.
        fixture.threadsRefused = false;
        fixture.service.terminateJobFlows(List.of(clusterId));
        while (fixture.stepThreadsWaiting() > 0) {
            fixture.runStepThread();
        }
        assertTrue(fixture.clusterStorage.removed.contains(clusterId), fixture.clusterStorage.removed.toString());
    }

    @Test
    void anExecutionOfAnEditorsNotebookAloneFailsAsFlociKeepsNoEditorsLocation() {
        String clusterId = notebookCluster();

        EmrNotebookExecution started = executions.start(request(clusterId));

        assertEquals("FAILED", started.getStatus());
        assertEquals(String.format(EmrNotebookExecutionService.NO_NOTEBOOK, clusterId),
                started.getLastStateChangeReason());
        assertEquals(0, fixture.stepThreadsWaiting());
    }

    @Test
    void aStoppedExecutionIsStoppingUntilItsRunHasStopped() {
        String clusterId = notebookCluster();
        EmrNotebookExecution request = request(clusterId);
        request.setNotebookS3Bucket("notebooks");
        request.setNotebookS3Key("analysis.ipynb");
        String id = executions.start(request).getId();

        executions.stop(id);
        assertEquals("STOPPING", executions.describe(id).getStatus());
        fixture.runStepThread();

        EmrNotebookExecution stopped = executions.describe(id);
        assertEquals("STOPPED", stopped.getStatus());
        assertEquals("Execution is stopped for cluster " + clusterId + ".", stopped.getLastStateChangeReason());
        assertTrue(runner.jobs.isEmpty(), "a stopped execution's notebook did not start");
    }

    @Test
    void anExecutionNoThreadRunsAnyMoreWasInterruptedByARestart() {
        String clusterId = notebookCluster();
        EmrNotebookExecution request = request(clusterId);
        request.setNotebookS3Bucket("notebooks");
        request.setNotebookS3Key("analysis.ipynb");
        String id = executions.start(request).getId();

        // A new process: the stored execution is there, but no thread of it runs the notebook.
        EmrNotebookExecution after = service().describe(id);

        assertEquals("FAILED", after.getStatus());
        assertEquals(EmrNotebookExecutionService.INTERRUPTED, after.getLastStateChangeReason());
    }

    private String notebookCluster() {
        EmrCluster cluster = cluster("notebooks", true);
        cluster.setReleaseLabel("emr-7.5.0");
        cluster.setApplications(NOTEBOOK_APPS);
        return fixture.service.runJobFlow(cluster, REGION).getId();
    }

    private static EmrNotebookExecution notebookRequest(String clusterId) {
        EmrNotebookExecution request = request(clusterId);
        request.setNotebookS3Bucket("notebooks");
        request.setNotebookS3Key("analysis.ipynb");
        return request;
    }

    private static EmrNotebookExecution request(String clusterId) {
        EmrNotebookExecution request = new EmrNotebookExecution();
        request.setEngineId(clusterId);
        request.setServiceRole("EMR_Notebooks_DefaultRole");
        request.setEditorId("e-ABCDEFGHIJK1234567890ABCD");
        request.setRelativePath("analysis.ipynb");
        request.setName("run");
        return request;
    }

    /** Runs each command at once, as the Docker runner would, ending as {@code outcome} says. */
    private static final class RecordingRunner implements SparkJobRunner {
        final List<Job> jobs = new CopyOnWriteArrayList<>();
        volatile Outcome outcome = Outcome.exitedWith(0);

        @Override
        public Outcome run(Job job, Progress progress) {
            if (progress.cancelled()) {
                return Outcome.halted();
            }
            jobs.add(job);
            progress.started();
            return outcome;
        }

        @Override
        public void stop(String runId) {
        }

        @Override
        public void removeLeftoverDrivers(String service) {
        }
    }
}
