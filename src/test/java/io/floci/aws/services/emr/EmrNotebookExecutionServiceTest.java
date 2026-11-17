package io.floci.aws.services.emr;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.emr.model.EmrCluster;
import io.floci.aws.services.emr.model.EmrNotebookExecution;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static io.floci.aws.services.emr.EmrServiceFixture.ACCOUNT;
import static io.floci.aws.services.emr.EmrServiceFixture.REGION;
import static io.floci.aws.services.emr.EmrServiceFixture.cluster;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Notebook executions through {@link EmrNotebookExecutionService}: their lifecycle and what refuses one. */
class EmrNotebookExecutionServiceTest {

    private static final String NOTEBOOK_APPS = "[{\"Name\":\"Hadoop\"},{\"Name\":\"Spark\"},{\"Name\":\"Livy\"},"
            + "{\"Name\":\"JupyterEnterpriseGateway\"}]";
    private static final Duration RUN = Duration.ofMinutes(10);

    private final EmrServiceFixture fixture = new EmrServiceFixture(0, 0, 0);
    private final AccountAwareStorageBackend<EmrNotebookExecution> store = AccountAwareStorageBackend.inMemory(ACCOUNT);
    private final EmrNotebookExecutionService executions = new EmrNotebookExecutionService(store, fixture.service,
            new RegionResolver(REGION, ACCOUNT), fixture.clock, RUN);

    @Test
    void anExecutionRunsForTheStepDurationAndFinishesAsAwsReportsIt() {
        String clusterId = notebookCluster("emr-6.15.0", NOTEBOOK_APPS);

        EmrNotebookExecution started = executions.start(request(clusterId));

        assertTrue(started.getId().matches("ex-[A-Z0-9]{29}"), started.getId());
        assertEquals("arn:aws:elasticmapreduce:us-east-1:000000000000:notebook-execution/" + started.getId(),
                started.getArn());
        assertEquals("START_PENDING", started.getStatus());
        EmrNotebookExecution running = executions.describe(started.getId());
        assertEquals("RUNNING", running.getStatus());
        assertEquals("EMR", running.getEngineType());
        assertNull(running.getEndTime());

        fixture.clock.advance(RUN);
        EmrNotebookExecution finished = executions.describe(started.getId());
        assertEquals("FINISHED", finished.getStatus());
        assertEquals("Execution is finished for cluster " + clusterId + ".", finished.getLastStateChangeReason());
        assertEquals(started.getStartTime().plus(RUN), finished.getEndTime());
    }

    @Test
    void aRunningExecutionStopsAndOneThatHasEndedCannotStopAgain() {
        String clusterId = notebookCluster("emr-6.15.0", NOTEBOOK_APPS);
        String id = executions.start(request(clusterId)).getId();

        executions.stop(id);

        EmrNotebookExecution stopped = executions.describe(id);
        assertEquals("STOPPED", stopped.getStatus());
        assertEquals("Execution is stopped for cluster " + clusterId + ".", stopped.getLastStateChangeReason());
        assertEquals("InvalidRequestException", assertThrows(AwsException.class, () -> executions.stop(id))
                .getErrorCode());
    }

    @Test
    void anExecutionFailsAsItsClusterShutsDown() {
        String clusterId = notebookCluster("emr-6.15.0", NOTEBOOK_APPS);
        String id = executions.start(request(clusterId)).getId();

        fixture.service.terminateJobFlows(List.of(clusterId));

        EmrNotebookExecution failed = executions.describe(id);
        assertEquals("FAILED", failed.getStatus());
        assertTrue(failed.getLastStateChangeReason().contains("the cluster was terminated"),
                failed.getLastStateChangeReason());
    }

    @Test
    void aClusterMustMeetTheEmrNotebooksRequirements() {
        assertRefused(notebookCluster("emr-5.17.0", NOTEBOOK_APPS), "emr-5.18.0");
        assertRefused(notebookCluster("emr-6.15.0", "[{\"Name\":\"Hadoop\"},{\"Name\":\"Spark\"},"
                + "{\"Name\":\"JupyterEnterpriseGateway\"}]"), "has no Livy");
        assertRefused(notebookCluster("emr-6.15.0", "[{\"Name\":\"Hadoop\"},{\"Name\":\"Spark\"},{\"Name\":\"Livy\"}]"),
                "has no JupyterEnterpriseGateway");
        // Before 5.32.0, no Jupyter Enterprise Gateway; the AWS runtime for Apache Spark names no Hadoop.
        executions.start(request(notebookCluster("emr-5.31.0",
                "[{\"Name\":\"Hadoop\"},{\"Name\":\"Spark\"},{\"Name\":\"Livy\"}]")));
        executions.start(request(notebookCluster("emr-spark-8.0.0",
                "[{\"Name\":\"Spark\"},{\"Name\":\"Livy\"},{\"Name\":\"JupyterEnterpriseGateway\"}]")));

        EmrServiceFixture starting = new EmrServiceFixture(600, 0, 0);
        EmrNotebookExecutionService early = new EmrNotebookExecutionService(AccountAwareStorageBackend.inMemory(
                ACCOUNT), starting.service, new RegionResolver(REGION, ACCOUNT), starting.clock, RUN);
        String startingId = starting.service.runJobFlow(clusterSpec("emr-6.15.0", NOTEBOOK_APPS), REGION).getId();
        AwsException notReady = assertThrows(AwsException.class, () -> early.start(request(startingId)));
        assertTrue(notReady.getMessage().contains("RUNNING or WAITING"), notReady.getMessage());
        assertEquals("InvalidRequestException", assertThrows(AwsException.class,
                () -> executions.start(request("j-NOSUCHCLUSTER"))).getErrorCode());
    }

    @Test
    void aRequestIsRefusedWithoutItsRequiredMembersOrWithAValueOutsideAnEnum() {
        String clusterId = notebookCluster("emr-6.15.0", NOTEBOOK_APPS);
        EmrNotebookExecution noRole = request(clusterId);
        noRole.setServiceRole(null);
        EmrNotebookExecution onEks = request(clusterId);
        onEks.setEngineType("EMR_ON_EKS");
        EmrNotebookExecution pdf = request(clusterId);
        pdf.setOutputNotebookFormat("PDF");
        for (EmrNotebookExecution refused : List.of(noRole, onEks, pdf, request(null))) {
            assertEquals("ValidationException", assertThrows(AwsException.class, () -> executions.start(refused))
                    .getErrorCode());
        }
    }

    @Test
    void aBlankServiceRoleIsRefused() {
        EmrNotebookExecution blank = request(notebookCluster("emr-6.15.0", NOTEBOOK_APPS));
        blank.setServiceRole(" ");

        assertEquals("InvalidRequestException", assertThrows(AwsException.class, () -> executions.start(blank))
                .getErrorCode());
    }

    @Test
    void aClusterLookupThatFailsForAnotherReasonLeavesTheExecutionAsItWas() {
        EmrService failing = mock(EmrService.class);
        EmrCluster cluster = clusterSpec("emr-6.15.0", NOTEBOOK_APPS);
        cluster.setId("j-FAILING");
        cluster.setState("WAITING");
        when(failing.describeCluster("j-FAILING")).thenReturn(cluster);
        when(failing.findDescribedCluster("j-FAILING")).thenThrow(new IllegalStateException("storage unavailable"));
        EmrNotebookExecutionService service = new EmrNotebookExecutionService(store, failing,
                new RegionResolver(REGION, ACCOUNT), fixture.clock, RUN);
        String id = service.start(request("j-FAILING")).getId();

        assertThrows(IllegalStateException.class, () -> service.describe(id));
        // RUNNING from its start, whatever its cluster; not FAILED as if the cluster had gone.
        assertEquals("RUNNING", store.get(id).orElseThrow().getStatus());
        assertNull(store.get(id).orElseThrow().getEndTime());
    }

    @Test
    void aMarkerResumesOnlyTheListingItCameFrom() {
        String clusterId = notebookCluster("emr-6.15.0", NOTEBOOK_APPS);
        for (int i = 0; i <= EmrNotebookExecutionService.PAGE_SIZE; i++) {
            executions.start(request(clusterId));
        }
        String marker = executions.list(null, null, null, null, null, null).nextToken();

        assertEquals(1, executions.list(null, null, null, null, null, marker).items().size());
        assertEquals("InvalidRequestException", assertThrows(AwsException.class,
                () -> executions.list("null", null, null, null, null, marker)).getErrorCode());
        EmrNotebookExecutionService elsewhere = new EmrNotebookExecutionService(store, fixture.service,
                new RegionResolver("eu-west-1", ACCOUNT), fixture.clock, RUN);
        assertEquals("InvalidRequestException", assertThrows(AwsException.class,
                () -> elsewhere.list(null, null, null, null, null, marker)).getErrorCode());
    }

    @Test
    void theAccountRunsAtMostAHundredExecutionsInARegion() {
        String clusterId = notebookCluster("emr-6.15.0", NOTEBOOK_APPS);
        for (int i = 0; i < EmrNotebookExecutionService.MAX_CONCURRENT_EXECUTIONS; i++) {
            executions.start(request(clusterId));
        }

        AwsException full = assertThrows(AwsException.class, () -> executions.start(request(clusterId)));
        assertTrue(full.getMessage().contains("100 concurrent notebook executions"), full.getMessage());

        fixture.clock.advance(RUN);
        executions.start(request(clusterId));
    }

    @Test
    void listingGivesTheNewestFirstFilteredWithinItsWindowAPageAtATime() {
        String clusterId = notebookCluster("emr-6.15.0", NOTEBOOK_APPS);
        String old = executions.start(request(clusterId)).getId();
        fixture.clock.advance(Duration.ofDays(31));
        EmrNotebookExecution editor = request(clusterId);
        editor.setEditorId("e-EDITOR");
        String first = executions.start(editor).getId();
        String second = executions.start(request(clusterId)).getId();
        executions.stop(second);

        assertEquals(List.of(second, first), ids(executions.list(null, null, null, null, null, null)));
        assertEquals(List.of(second, first, old), ids(executions.list(null, null, Instant.EPOCH, null, null, null)));
        assertEquals(List.of(first), ids(executions.list("e-EDITOR", null, null, null, null, null)));
        assertEquals(List.of(second), ids(executions.list(null, "STOPPED", null, null, null, null)));
        assertEquals(List.of(), ids(executions.list(null, null, null, null, "j-OTHER", null)));
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> executions.list(null, "DONE", null, null, null, null)).getErrorCode());
    }

    private void assertRefused(String clusterId, String message) {
        AwsException refused = assertThrows(AwsException.class, () -> executions.start(request(clusterId)));
        assertEquals("InvalidRequestException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains(message), refused.getMessage());
    }

    private static List<String> ids(PaginatedResult<EmrNotebookExecution> page) {
        return page.items().stream().map(EmrNotebookExecution::getId).toList();
    }

    private String notebookCluster(String releaseLabel, String applications) {
        return fixture.service.runJobFlow(clusterSpec(releaseLabel, applications), REGION).getId();
    }

    private static EmrCluster clusterSpec(String releaseLabel, String applications) {
        EmrCluster cluster = cluster("notebooks", true);
        cluster.setReleaseLabel(releaseLabel);
        cluster.setApplications(applications);
        return cluster;
    }

    private static EmrNotebookExecution request(String clusterId) {
        EmrNotebookExecution request = new EmrNotebookExecution();
        request.setEngineId(clusterId);
        request.setServiceRole("EMR_Notebooks_DefaultRole");
        request.setRelativePath("test.ipynb");
        request.setName("run");
        return request;
    }
}
