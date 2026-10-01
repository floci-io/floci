package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.ServiceDeployment;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a service deployment reports, driven off the reconciler tick by tick. Deployments are
 * selected by task set id rather than by position in a listing: several minted in one clock tick
 * share a createdAt, so their order in the listing is map iteration order.
 */
class EcsServiceDeploymentStatusTest {

    private static final String REGION = "us-east-1";

    @Test
    void theCurrentDeploymentIsInProgressUntilTheServicesTasksAreRunning() {
        EcsService service = newMockModeService();
        service.createCluster("dstat-cluster", REGION);
        registerTaskDef(service, "dstat-fam", "app:1");
        service.createService("dstat-cluster", "dstat-svc", "dstat-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);

        // Created, nothing launched yet: runningCount 0 of a requested 1.
        ServiceDeployment pending = onlyDeployment(service, "dstat-svc", "dstat-cluster");
        assertEquals("IN_PROGRESS", pending.getStatus());
        assertNull(pending.getFinishedAt(), "an unfinished deployment has no finishedAt");

        // One tick: the count is taken from the tasks at read time, not from svc.runningCount,
        // which lags a tick behind.
        service.reconcileServices();

        ServiceDeployment done = onlyDeployment(service, "dstat-svc", "dstat-cluster");
        assertEquals("SUCCESSFUL", done.getStatus());
        assertNotNull(done.getFinishedAt(), "a finished deployment reports when it finished");
    }

    /** A service already at its requested count has converged, so its deployment is finished. */
    @Test
    void aServiceAlreadyAtItsRequestedCountHasNothingToWaitFor() {
        EcsService service = newMockModeService();
        service.createCluster("dzero-cluster", REGION);
        registerTaskDef(service, "dzero-fam", "app:1");
        service.createService("dzero-cluster", "dzero-svc", "dzero-fam", 0,
                LaunchType.FARGATE, List.of(), null, REGION);

        ServiceDeployment deployment = onlyDeployment(service, "dzero-svc", "dzero-cluster");
        assertEquals("SUCCESSFUL", deployment.getStatus());
        assertNotNull(deployment.getFinishedAt());
    }

    @Test
    void everyDeploymentTargetsARevisionNamedByItsOwnTaskSetId() {
        EcsService service = newMockModeService();
        service.createCluster("drev-cluster", REGION);
        registerTaskDef(service, "drev-fam", "app:1");
        EcsServiceModel created = service.createService("drev-cluster", "drev-svc", "drev-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);
        // Read out now: createService and updateService hand back the same live model object.
        String firstDeploymentId = created.getDeploymentId();
        assertRevisionCarriesTaskSetId(service, "drev-svc", "drev-cluster", firstDeploymentId);

        service.reconcileServices();
        String secondDeploymentId = service.updateService("drev-cluster", "drev-svc", null, null,
                null, null, true, REGION).getDeploymentId();
        assertNotEquals(firstDeploymentId, secondDeploymentId,
                "forceNewDeployment mints a new deployment id");
        assertRevisionCarriesTaskSetId(service, "drev-svc", "drev-cluster", secondDeploymentId);

        // The superseded deployment keeps pointing at its own revision, so the two never collide.
        List<ServiceDeployment> all = service.listServiceDeploymentsDetailed("drev-svc",
                "drev-cluster", null, REGION);
        assertEquals(2, all.size());
        assertEquals(2, all.stream().map(ServiceDeployment::getTargetServiceRevisionArn)
                .distinct().count(), "each deployment targets its own revision");
    }

    /**
     * A task-definition change is judged on the new deployment's own tasks, not on the old
     * revision's task that is still running.
     */
    @Test
    void aTaskDefinitionChangeIsNotFinishedWhileOnlyTheOldTasksAreRunning() {
        EcsService service = newMockModeService();
        service.createCluster("dupd-cluster", REGION);
        registerTaskDef(service, "dupd-fam", "app:1");
        EcsServiceModel created = service.createService("dupd-cluster", "dupd-svc", "dupd-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);
        String firstDeploymentId = created.getDeploymentId();
        service.reconcileServices();
        assertEquals("SUCCESSFUL", deploymentOf(service, "dupd-svc", "dupd-cluster",
                        firstDeploymentId).getStatus(),
                "precondition: the first deployment converged");

        TaskDefinition rev2 = registerTaskDef(service, "dupd-fam", "app:2");
        String rolled = service.updateService("dupd-cluster", "dupd-svc",
                "dupd-fam:" + rev2.getRevision(), null, null, REGION).getDeploymentId();

        // No tick yet. The only RUNNING task belongs to the superseded deployment, so the new
        // deployment has nothing of its own up.
        ServiceDeployment rolling = deploymentOf(service, "dupd-svc", "dupd-cluster", rolled);
        assertEquals("IN_PROGRESS", rolling.getStatus(),
                "a deployment with none of its own tasks running is not finished");
        assertNull(rolling.getFinishedAt(), "and carries no finishedAt");

        // One tick launches the new revision's task; now the deployment really has converged,
        // even though the old task is still draining beside it.
        service.reconcileServices();
        ServiceDeployment settled = deploymentOf(service, "dupd-svc", "dupd-cluster", rolled);
        assertEquals("SUCCESSFUL", settled.getStatus(),
                "its own task is running, so it is finished even while the old one drains");
        assertNotNull(settled.getFinishedAt());
    }

    /** SUCCESSFUL is terminal: tasks dying afterwards do not un-finish the deployment. */
    @Test
    void aFinishedDeploymentStaysFinishedWhenItsTasksDie() {
        EcsService service = newMockModeService();
        service.createCluster("ddie-cluster", REGION);
        registerTaskDef(service, "ddie-fam", "app:1");
        String deploymentId = service.createService("ddie-cluster", "ddie-svc", "ddie-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION).getDeploymentId();
        service.reconcileServices();
        ServiceDeployment finished = deploymentOf(service, "ddie-svc", "ddie-cluster", deploymentId);
        assertEquals("SUCCESSFUL", finished.getStatus());
        Instant finishedAt = finished.getFinishedAt();
        assertNotNull(finishedAt);

        // Read again while still converged: finishedAt is stamped once, not on every read.
        assertEquals(finishedAt,
                deploymentOf(service, "ddie-svc", "ddie-cluster", deploymentId).getFinishedAt(),
                "finishedAt is stamped once, not re-stamped on every read while converged");

        String taskArn = runningTasks(service).getFirst().getTaskArn();
        service.stopTask("ddie-cluster", taskArn, "test kill", REGION);
        assertEquals(0, runningTasks(service).size(), "precondition: nothing of it is running");

        ServiceDeployment after = deploymentOf(service, "ddie-svc", "ddie-cluster", deploymentId);
        assertEquals("SUCCESSFUL", after.getStatus(), "a completed deployment does not un-complete");
        assertEquals(finishedAt, after.getFinishedAt(), "and keeps the instant it finished at");
    }

    /**
     * Scaling to zero is converged while the task is still draining, since {@code >=} is used.
     * AWS's own waiter compares with {@code ==} and would wait for the task to stop.
     */
    @Test
    void scalingToZeroIsConvergedBeforeTheTaskHasActuallyStopped() {
        EcsService service = newMockModeService();
        service.createCluster("dscale-cluster", REGION);
        registerTaskDef(service, "dscale-fam", "app:1");
        String id = service.createService("dscale-cluster", "dscale-svc", "dscale-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION).getDeploymentId();
        service.reconcileServices();

        service.updateService("dscale-cluster", "dscale-svc", null, 0, null, REGION);
        assertEquals(1, runningTasks(service).size(), "the task has not been drained yet");
        assertEquals("SUCCESSFUL", deploymentOf(service, "dscale-svc", "dscale-cluster", id).getStatus());

        service.reconcileServices();
        assertEquals(0, runningTasks(service).size(), "and now it is drained");
        assertEquals("SUCCESSFUL", deploymentOf(service, "dscale-svc", "dscale-cluster", id).getStatus());
    }

    /**
     * Two task-definition changes in a row with no read between them. The first deployment had
     * converged, so the update that supersedes it settles it SUCCESSFUL; the second never had a
     * task of its own, so the next update stops it.
     */
    @Test
    void twoUpdatesInSuccessionSettleOnlyTheNewestDeployment() {
        EcsService service = newMockModeService();
        service.createCluster("drapid-cluster", REGION);
        registerTaskDef(service, "drapid-fam", "app:1");
        // createService and updateService return the same live model, so read each id at once.
        String first = service.createService("drapid-cluster", "drapid-svc", "drapid-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION).getDeploymentId();
        service.reconcileServices();

        TaskDefinition rev2 = registerTaskDef(service, "drapid-fam", "app:2");
        String second = service.updateService("drapid-cluster", "drapid-svc",
                "drapid-fam:" + rev2.getRevision(), null, null, REGION).getDeploymentId();
        TaskDefinition rev3 = registerTaskDef(service, "drapid-fam", "app:3");
        String newest = service.updateService("drapid-cluster", "drapid-svc",
                "drapid-fam:" + rev3.getRevision(), null, null, REGION).getDeploymentId();

        assertEquals(3, service.listServiceDeploymentsDetailed("drapid-svc", "drapid-cluster",
                null, REGION).size(), "one per create/update");
        assertEquals("IN_PROGRESS",
                deploymentOf(service, "drapid-svc", "drapid-cluster", newest).getStatus(),
                "the newest has none of its own tasks running");
        assertEquals("SUCCESSFUL",
                deploymentOf(service, "drapid-svc", "drapid-cluster", first).getStatus(),
                "the first had converged before it was superseded, though nothing read it");
        assertEquals("STOPPED",
                deploymentOf(service, "drapid-svc", "drapid-cluster", second).getStatus(),
                "the second never had a task of its own");

        service.reconcileServices();
        assertEquals("SUCCESSFUL",
                deploymentOf(service, "drapid-svc", "drapid-cluster", newest).getStatus(),
                "and converges once its own task is up");
    }

    /**
     * Right after a task-definition change, {@code rolloutState} and the deployment record
     * describe the same moment and must agree. Later they may differ: the record is history,
     * rolloutState follows the live service.
     */
    @Test
    void theRolloutStateAndTheDeploymentRecordAgreeWhileARolloutIsInFlight() {
        EcsService service = newMockModeService();
        service.createCluster("dboth-cluster", REGION);
        registerTaskDef(service, "dboth-fam", "app:1");
        EcsServiceModel created = service.createService("dboth-cluster", "dboth-svc", "dboth-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);
        // Two ticks: the first launches the task, the second counts it in svc.runningCount.
        // With only one, the service-wide count is 0 and the old miscount passes by accident.
        service.reconcileServices();
        service.reconcileServices();
        assertEquals(1, created.getRunningCount(),
                "precondition: a genuine steady state, not one tick short of it");

        TaskDefinition rev2 = registerTaskDef(service, "dboth-fam", "app:2");
        String rolled = service.updateService("dboth-cluster", "dboth-svc",
                "dboth-fam:" + rev2.getRevision(), null, null, REGION).getDeploymentId();

        EcsServiceModel svc = service.serviceByArn(
                service.describeServices("dboth-cluster", List.of("dboth-svc"), REGION)
                        .getFirst().getServiceArn());
        assertEquals("IN_PROGRESS", service.deploymentsFor(svc).getFirst().getRolloutState(),
                "the live rollout has not started: only the old revision's task is up");
        assertEquals("IN_PROGRESS",
                deploymentOf(service, "dboth-svc", "dboth-cluster", rolled).getStatus(),
                "and the record says the same about the same moment");
        assertTrue(service.eventsFor(svc).isEmpty(),
                "no steady-state event while the rollout is in flight");

        service.reconcileServices();
        assertEquals("COMPLETED", service.deploymentsFor(svc).getFirst().getRolloutState());
        assertEquals("SUCCESSFUL",
                deploymentOf(service, "dboth-svc", "dboth-cluster", rolled).getStatus());
    }

    /**
     * A DAEMON service still finishes its deployment after a task-definition change. DAEMON
     * services do not roll yet, so the old revision's task is still the one running; the last
     * assertions fail once they do, which is when the DAEMON case in the count should go.
     */
    @Test
    void aDaemonServiceStillFinishesItsDeploymentAfterATaskDefinitionChange() {
        EcsService service = newMockModeService();
        service.createCluster("ddmn-cluster", REGION);
        service.registerContainerInstance("ddmn-cluster", null, List.of(), REGION);
        TaskDefinition rev1 = registerTaskDef(service, "ddmn-fam", "app:1");
        service.createService("ddmn-cluster", "ddmn-svc", "ddmn-fam", 1, LaunchType.EC2,
                List.of(), null, null, "DAEMON", null, null, REGION);
        service.reconcileServices();
        service.reconcileServices();

        TaskDefinition rev2 = registerTaskDef(service, "ddmn-fam", "app:2");
        String rolled = service.updateService("ddmn-cluster", "ddmn-svc",
                "ddmn-fam:" + rev2.getRevision(), null, null, REGION).getDeploymentId();
        service.reconcileServices();
        service.reconcileServices();

        ServiceDeployment record = deploymentOf(service, "ddmn-svc", "ddmn-cluster", rolled);
        assertEquals("SUCCESSFUL", record.getStatus(),
                "a DAEMON deployment must still finish, or a steady-state wait hangs for ever");
        assertNotNull(record.getFinishedAt());

        List<EcsTask> live = runningTasks(service);
        assertEquals(1, live.size(), "one task per container instance");
        assertEquals(rev1.getTaskDefinitionArn(), live.getFirst().getTaskDefinitionArn(),
                "DAEMON does not roll on a task-definition change; the count is honest about that");
        assertNotEquals(rev2.getTaskDefinitionArn(), live.getFirst().getTaskDefinitionArn());
    }

    /**
     * A deployment superseded before it converged is STOPPED, not left IN_PROGRESS. The second
     * service is never updated, so its deployment must stay in flight.
     */
    @Test
    void supersedingAnUnconvergedDeploymentStopsIt() {
        EcsService service = newMockModeService();
        service.createCluster("dsup-cluster", REGION);
        registerTaskDef(service, "dsup-fam", "app:1");
        String first = service.createService("dsup-cluster", "dsup-svc", "dsup-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION).getDeploymentId();
        registerTaskDef(service, "dsup-other-fam", "other:1");
        String bystander = service.createService("dsup-cluster", "dsup-other", "dsup-other-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION).getDeploymentId();

        // No tick: neither service has launched anything, so both deployments are in flight.
        assertEquals("IN_PROGRESS",
                deploymentOf(service, "dsup-svc", "dsup-cluster", first).getStatus(),
                "precondition: the first deployment never converged");

        TaskDefinition rev2 = registerTaskDef(service, "dsup-fam", "app:2");
        String second = service.updateService("dsup-cluster", "dsup-svc",
                "dsup-fam:" + rev2.getRevision(), null, null, REGION).getDeploymentId();

        ServiceDeployment superseded = deploymentOf(service, "dsup-svc", "dsup-cluster", first);
        assertEquals("STOPPED", superseded.getStatus(),
                "a deployment another one took over from is finished, not still rolling out");
        assertNotNull(superseded.getFinishedAt(),
                "and reports when it ended, like every deployment that is no longer running");
        assertNotNull(superseded.getStoppedAt(), "and when it was stopped");
        assertTrue(superseded.getStatusReason() != null
                        && superseded.getStatusReason().contains(deploymentArnOf(service,
                                "dsup-svc", "dsup-cluster", second)),
                "the reason names the deployment that took over, which is what a reader needs "
                        + "and the status alone does not say; was: " + superseded.getStatusReason());

        assertEquals("IN_PROGRESS",
                deploymentOf(service, "dsup-svc", "dsup-cluster", second).getStatus(),
                "while the deployment that took over is the one now in flight");
        assertEquals("IN_PROGRESS",
                deploymentOf(service, "dsup-other", "dsup-cluster", bystander).getStatus(),
                "another service's deployment is none of this update's business");
    }

    /** STOPPED is terminal: the replacement converging does not make the stopped one succeed. */
    @Test
    void aSupersededDeploymentDoesNotSucceedWhenItsReplacementDoes() {
        EcsService service = newMockModeService();
        service.createCluster("dsup2-cluster", REGION);
        registerTaskDef(service, "dsup2-fam", "app:1");
        String first = service.createService("dsup2-cluster", "dsup2-svc", "dsup2-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION).getDeploymentId();

        TaskDefinition rev2 = registerTaskDef(service, "dsup2-fam", "app:2");
        String second = service.updateService("dsup2-cluster", "dsup2-svc",
                "dsup2-fam:" + rev2.getRevision(), null, null, REGION).getDeploymentId();
        Instant stoppedAt = deploymentOf(service, "dsup2-svc", "dsup2-cluster", first).getFinishedAt();
        assertNotNull(stoppedAt, "precondition: the superseded deployment ended");

        service.reconcileServices();
        assertEquals("SUCCESSFUL",
                deploymentOf(service, "dsup2-svc", "dsup2-cluster", second).getStatus(),
                "precondition: the replacement converged");

        ServiceDeployment superseded = deploymentOf(service, "dsup2-svc", "dsup2-cluster", first);
        assertEquals("STOPPED", superseded.getStatus(),
                "the deployment that was taken over from did not succeed, its replacement did");
        assertEquals(stoppedAt, superseded.getFinishedAt(),
                "and still reports the instant it ended");
    }

    /** A deployment that finished, and was read, before it was superseded keeps its record. */
    @Test
    void aDeploymentThatFinishedBeforeItWasSupersededStaysSuccessful() {
        EcsService service = newMockModeService();
        service.createCluster("dsup3-cluster", REGION);
        registerTaskDef(service, "dsup3-fam", "app:1");
        String first = service.createService("dsup3-cluster", "dsup3-svc", "dsup3-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION).getDeploymentId();
        service.reconcileServices();

        ServiceDeployment finished = deploymentOf(service, "dsup3-svc", "dsup3-cluster", first);
        assertEquals("SUCCESSFUL", finished.getStatus(), "precondition: it converged and was read");
        Instant finishedAt = finished.getFinishedAt();
        assertNotNull(finishedAt);

        TaskDefinition rev2 = registerTaskDef(service, "dsup3-fam", "app:2");
        service.updateService("dsup3-cluster", "dsup3-svc", "dsup3-fam:" + rev2.getRevision(),
                null, null, REGION);

        ServiceDeployment after = deploymentOf(service, "dsup3-svc", "dsup3-cluster", first);
        assertEquals("SUCCESSFUL", after.getStatus(),
                "a deployment that completed was not stopped by the one that followed it");
        assertEquals(finishedAt, after.getFinishedAt(),
                "and keeps the instant it finished at, not the instant it was superseded");
        assertNull(after.getStoppedAt(), "a deployment that succeeded was never stopped");
        assertNull(after.getStatusReason(), "and has no reason it failed to finish");
    }

    /**
     * The update settles the outgoing deployment itself, so whether it finished does not depend
     * on anything having read it. Changing the desired count in the same request must not count
     * against it either: it had reached the count it was asked for.
     */
    @Test
    void aConvergedDeploymentNobodyReadIsSuccessfulWhenSuperseded() {
        EcsService service = newMockModeService();
        service.createCluster("dunread-cluster", REGION);
        registerTaskDef(service, "dunread-fam", "app:1");
        String first = service.createService("dunread-cluster", "dunread-svc", "dunread-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION).getDeploymentId();
        service.reconcileServices();

        TaskDefinition rev2 = registerTaskDef(service, "dunread-fam", "app:2");
        String second = service.updateService("dunread-cluster", "dunread-svc",
                "dunread-fam:" + rev2.getRevision(), 3, null, REGION).getDeploymentId();

        ServiceDeployment finished = deploymentOf(service, "dunread-svc", "dunread-cluster", first);
        assertEquals("SUCCESSFUL", finished.getStatus(),
                "its task was running when the update arrived");
        assertNotNull(finished.getFinishedAt());
        assertNull(finished.getStoppedAt());
        assertEquals(List.of(deploymentArnOf(service, "dunread-svc", "dunread-cluster", second)),
                service.listServiceDeployments("dunread-svc", "dunread-cluster",
                        List.of("IN_PROGRESS"), REGION),
                "only the new deployment is in progress");
    }

    /**
     * Deleting a service stops a deployment it leaves unfinished, so a listing filtered on
     * IN_PROGRESS no longer returns it.
     */
    @Test
    void deletingAServiceStopsTheDeploymentItLeavesUnfinished() {
        EcsService service = newMockModeService();
        service.createCluster("ddel-cluster", REGION);
        registerTaskDef(service, "ddel-fam", "app:1");
        String only = service.createService("ddel-cluster", "ddel-svc", "ddel-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION).getDeploymentId();

        // No tick, so nothing was ever launched and the deployment is still rolling out.
        assertEquals("IN_PROGRESS",
                deploymentOf(service, "ddel-svc", "ddel-cluster", only).getStatus(),
                "precondition: the deployment never converged");
        assertEquals(1, service.listServiceDeployments("ddel-svc", "ddel-cluster",
                List.of("IN_PROGRESS"), REGION).size(), "precondition: and reads as in flight");

        service.deleteService("ddel-cluster", "ddel-svc", true, REGION);

        ServiceDeployment stopped = deploymentOf(service, "ddel-svc", "ddel-cluster", only);
        assertEquals("STOPPED", stopped.getStatus(),
                "a deployment whose service is gone has ended, and nothing else can ever say so");
        assertNotNull(stopped.getFinishedAt(), "it reports when it ended");
        assertNotNull(stopped.getStoppedAt(), "and when it was stopped");
        assertEquals("The service was deleted.", stopped.getStatusReason(),
                "naming the reason it could not finish");

        assertTrue(service.listServiceDeployments("ddel-svc", "ddel-cluster",
                        List.of("IN_PROGRESS"), REGION).isEmpty(),
                "and a client asking what is rolling out is no longer told this is");
    }

    /** Deleting a service leaves its finished deployments SUCCESSFUL. */
    @Test
    void deletingAServiceLeavesItsFinishedDeploymentsSuccessful() {
        EcsService service = newMockModeService();
        service.createCluster("ddel2-cluster", REGION);
        registerTaskDef(service, "ddel2-fam", "app:1");
        String only = service.createService("ddel2-cluster", "ddel2-svc", "ddel2-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION).getDeploymentId();
        service.reconcileServices();

        ServiceDeployment finished = deploymentOf(service, "ddel2-svc", "ddel2-cluster", only);
        assertEquals("SUCCESSFUL", finished.getStatus(), "precondition: it converged and was read");
        Instant finishedAt = finished.getFinishedAt();
        assertNotNull(finishedAt);

        service.deleteService("ddel2-cluster", "ddel2-svc", true, REGION);

        ServiceDeployment after = deploymentOf(service, "ddel2-svc", "ddel2-cluster", only);
        assertEquals("SUCCESSFUL", after.getStatus(),
                "a rollout that completed is history, and deleting the service does not undo it");
        assertEquals(finishedAt, after.getFinishedAt(), "keeping the instant it finished at");
        assertNull(after.getStoppedAt(), "and carrying none of the stopped fields");
        assertNull(after.getStatusReason());
    }

    /**
     * As {@link #aConvergedDeploymentNobodyReadIsSuccessfulWhenSuperseded}, for a delete: the
     * delete settles the deployment against the service as it stood, so a rollout that had
     * finished is SUCCESSFUL whether or not anything read it first.
     */
    @Test
    void deletingAServiceSettlesAConvergedDeploymentNobodyRead() {
        EcsService service = newMockModeService();
        service.createCluster("ddel3-cluster", REGION);
        registerTaskDef(service, "ddel3-fam", "app:1");
        String only = service.createService("ddel3-cluster", "ddel3-svc", "ddel3-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION).getDeploymentId();
        service.reconcileServices();

        service.deleteService("ddel3-cluster", "ddel3-svc", true, REGION);

        ServiceDeployment after = deploymentOf(service, "ddel3-svc", "ddel3-cluster", only);
        assertEquals("SUCCESSFUL", after.getStatus(),
                "its task was running when the delete arrived");
        assertNotNull(after.getFinishedAt());
        assertNull(after.getStoppedAt(), "a deployment that succeeded was never stopped");
        assertNull(after.getStatusReason());
    }

    /** The one place the provider's join is asserted: the revision ARN carries the task set id. */
    private static void assertRevisionCarriesTaskSetId(EcsService service, String name,
                                                       String cluster, String deploymentId) {
        String taskSetId = deploymentId.substring(deploymentId.indexOf('/') + 1);
        List<String> targets = service.listServiceDeploymentsDetailed(name, cluster, null, REGION)
                .stream().map(ServiceDeployment::getTargetServiceRevisionArn).toList();
        assertTrue(targets.stream().anyMatch(arn -> arn != null && arn.contains(taskSetId)),
                "some deployment's revision ARN must carry task set id " + taskSetId
                        + ", targets were: " + targets);
    }

    /** The deployment carrying {@code deploymentId}, found by the task set id in its revision ARN. */
    private static ServiceDeployment deploymentOf(EcsService service, String name, String cluster,
                                                  String deploymentId) {
        String taskSetId = deploymentId.substring(deploymentId.indexOf('/') + 1);
        return service.listServiceDeploymentsDetailed(name, cluster, null, REGION).stream()
                .filter(d -> d.getTargetServiceRevisionArn() != null
                        && d.getTargetServiceRevisionArn().endsWith("/" + taskSetId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no deployment for " + deploymentId));
    }

    /** The ARN of the deployment carrying {@code deploymentId}, selected the same way. */
    private static String deploymentArnOf(EcsService service, String name, String cluster,
                                          String deploymentId) {
        return deploymentOf(service, name, cluster, deploymentId).getServiceDeploymentArn();
    }

    private static ServiceDeployment onlyDeployment(EcsService service, String name, String cluster) {
        List<ServiceDeployment> deployments =
                service.listServiceDeploymentsDetailed(name, cluster, null, REGION);
        assertEquals(1, deployments.size(), "one create, one deployment");
        return deployments.getFirst();
    }

    private static List<EcsTask> runningTasks(EcsService service) {
        return service.describeTasks(null, service.listTasks(null, null, null, null, REGION), REGION)
                .stream().filter(t -> "RUNNING".equals(t.getLastStatus())).toList();
    }

    private static TaskDefinition registerTaskDef(EcsService service, String family, String image) {
        ContainerDefinition cd = new ContainerDefinition();
        cd.setName("app");
        cd.setImage(image);
        return service.registerTaskDefinition(family, List.of(cd), null, null, null,
                null, null, List.of(), REGION);
    }

    private static EcsService newMockModeService() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(true);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsService service = new EcsService(
                new RegionResolver(REGION, "000000000000"),
                mock(EcsContainerManager.class),
                config,
                mock(EcsLoadBalancerRegistrar.class),
                new InMemoryStorageFactory(),
                null);
        service.initializeStorage();
        return service;
    }

    private static final class InMemoryStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private InMemoryStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                        String fileName,
                                                        TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName,
                    ignored -> AccountAwareStorageBackend.inMemory("000000000000"));
        }
    }
}
