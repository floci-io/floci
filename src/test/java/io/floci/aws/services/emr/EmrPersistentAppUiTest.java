package io.floci.aws.services.emr;

import io.floci.aws.core.common.AppUiRoute;
import io.floci.aws.core.common.LinkSigner;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.emr.model.EmrCluster;
import io.floci.aws.services.emr.model.EmrPersistentAppUi;
import io.floci.aws.services.emrserverless.EmrServerlessSparkRunner;
import io.floci.aws.services.emrserverless.SparkJobRunner;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static io.floci.aws.services.emr.EmrServiceFixture.ACCOUNT;
import static io.floci.aws.services.emr.EmrServiceFixture.REGION;
import static io.floci.aws.services.emr.EmrServiceFixture.cluster;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Persistent application UI links in the docker runner mode ({@link EmrAppUiService}): while the cluster runs, its
 * primary node's interface; once it has ended, for 30 days, its kept history's, served by a container of its own
 * that starts on the link and stops once no link to it is valid.
 */
@ResourceLock(ParallelLocks.PORTS)
class EmrPersistentAppUiTest {

    private static final int SPARK_HISTORY = EmrServerlessSparkRunner.SPARK_HISTORY_WEB_PORT;
    private static final ContainerLifecycleManager.EndpointInfo ON_HOST =
            new ContainerLifecycleManager.EndpointInfo("localhost", 32803);

    private final EmrServiceFixture fixture = EmrServiceFixture.docker(new FinishingRunner(),
            mock(IamService.class));
    private final EmrAppUiService appUis = new EmrAppUiService(
            AccountAwareStorageBackend.<EmrPersistentAppUi>inMemory(ACCOUNT), fixture.service,
            new RegionResolver(REGION, ACCOUNT), fixture.clock, "http://localhost:4566", LinkSigner.withNewKey());

    @Test
    void aPersistentUiOpensItsClustersLiveInterfaceWhileTheClusterRuns() {
        fixture.clusterStorage.webInterfaces.put(SPARK_HISTORY, ON_HOST);
        EmrCluster cluster = sparkCluster();
        String appUi = appUis.create(arn(cluster), null, null).appUi().getId();

        EmrAppUiService.Presigned link = appUis.persistentUrl(appUi, "SHS", "application_1_0001");

        assertTrue(link.ready());
        URI url = URI.create(link.url());
        assertTrue(url.getAuthority().contains("-18080.emrappui."), link.url());
        assertEquals("/history/application_1_0001/", url.getPath());
        assertEquals(32803, appUis.route(url.getAuthority(), "http").port());
    }

    @Test
    void onceItsClusterHasEndedItOpensTheKeptHistoryUntilItsThirtyDaysHavePassed() {
        EmrCluster cluster = sparkCluster();
        String appUi = appUis.create(arn(cluster), null, null).appUi().getId();
        startAndEnd(cluster);

        // What its interfaces show was kept before its storage went.
        assertEquals(List.of("kept " + cluster.getId()), fixture.clusterStorage.historyEvents);
        EmrAppUiService.Presigned starting = appUis.persistentUrl(appUi, "SHS", null);

        assertFalse(starting.ready());
        String primary = EmrService.masterPrivateDnsName(cluster);
        assertEquals("starting " + cluster.getId() + " as " + primary,
                fixture.clusterStorage.historyEvents.get(1));
        URI url = URI.create(starting.url());
        assertEquals(EmrAppUiService.HISTORY_NOT_READY, appUis.route(url.getAuthority(), "http").refusal());
        fixture.clusterStorage.historyInterfaces.put(SPARK_HISTORY, ON_HOST);
        AppUiRoute route = appUis.route(url.getAuthority(), "http");
        assertNull(route.refusal());
        assertEquals(32803, route.port());
        assertTrue(appUis.persistentUrl(appUi, "SHS", null).ready());

        fixture.clock.advance(EmrService.HISTORY_KEPT);
        assertTrue(appUis.persistentUrl(appUi, "SHS", null).url()
                .startsWith("http://localhost:4566" + EmrAppUiService.PATH + "?"));
    }

    @Test
    void theContainerServingAKeptHistoryStopsOnceNoLinkToItIsValid() {
        EmrCluster cluster = sparkCluster();
        String appUi = appUis.create(arn(cluster), null, null).appUi().getId();
        startAndEnd(cluster);
        fixture.clusterStorage.historyInterfaces.put(SPARK_HISTORY, ON_HOST);
        appUis.persistentUrl(appUi, "SHS", null);

        appUis.reapHistory(ACCOUNT, REGION, cluster.getId());
        assertFalse(fixture.clusterStorage.historyEvents.contains("stopped " + cluster.getId()));
        fixture.clock.advance(EmrAppUiService.VALIDITY);
        appUis.reapHistory(ACCOUNT, REGION, cluster.getId());

        assertTrue(fixture.clusterStorage.historyEvents.contains("stopped " + cluster.getId()));
        // The histories within their 30 days stay, the cluster's among them.
        assertTrue(fixture.clusterStorage.historySweeps.getLast().contains(cluster.getId()));
    }

    @Test
    void aLinkAskedWhileItsClustersStorageIsStillGoingIsNotReadyAndStartsNothing() {
        EmrCluster cluster = sparkCluster();
        String appUi = appUis.create(arn(cluster), null, null).appUi().getId();
        appUis.onClusterUrl(cluster.getId(), null, null, false);
        fixture.runStepThread();
        // Its history's volume, which the node made as it started.
        fixture.clusterStorage.historiesKept.add(cluster.getId());
        fixture.service.terminateJobFlows(List.of(cluster.getId()));

        // Its primary node may still hold the timeline server's store, and its event logs are not yet copied.
        EmrAppUiService.Presigned link = appUis.persistentUrl(appUi, "SHS", null);

        assertFalse(link.ready());
        assertTrue(fixture.clusterStorage.historyEvents.stream().noneMatch(event -> event.startsWith("starting")),
                fixture.clusterStorage.historyEvents.toString());
        endStorage(cluster);
        assertFalse(appUis.persistentUrl(appUi, "SHS", null).ready());
        assertEquals("starting " + cluster.getId() + " as " + EmrService.masterPrivateDnsName(cluster),
                fixture.clusterStorage.historyEvents.get(1));
    }

    @Test
    void aFailedCopyOfItsEventLogsKeepsTheClustersStorageForALaterTry() {
        EmrCluster cluster = sparkCluster();
        String appUi = appUis.create(arn(cluster), null, null).appUi().getId();
        appUis.onClusterUrl(cluster.getId(), null, null, false);
        fixture.runStepThread();
        fixture.clusterStorage.historiesKept.add(cluster.getId());
        fixture.clusterStorage.keepFails = true;
        fixture.service.terminateJobFlows(List.of(cluster.getId()));
        while (fixture.stepThreadsWaiting() > 0) {
            fixture.runStepThread();
        }

        // Its HDFS, which holds the event logs, stays, and its history is not yet there to open.
        assertEquals(List.of("could not keep " + cluster.getId()), fixture.clusterStorage.historyEvents);
        assertFalse(fixture.clusterStorage.removed.contains(cluster.getId()));
        assertTrue(fixture.service.describeCluster(cluster.getId()).isStorageCreated());
        assertFalse(appUis.persistentUrl(appUi, "SHS", null).ready());
    }

    @Test
    void aKeptHistoryNoLinkAskedForGoesOnceItsThirtyDaysHavePassed() {
        EmrCluster cluster = sparkCluster();
        startAndEnd(cluster);
        assertTrue(fixture.clusterStorage.historySweeps.isEmpty());

        // The sweep its end set, a minute past its 30 days.
        fixture.pass(EmrService.HISTORY_KEPT);

        assertFalse(fixture.clusterStorage.historySweeps.isEmpty());
        assertFalse(fixture.clusterStorage.historySweeps.getLast().contains(cluster.getId()));
    }

    // Its primary node started, as a link to it before any step starts it, then the cluster ended, its storage gone.
    private void startAndEnd(EmrCluster cluster) {
        appUis.onClusterUrl(cluster.getId(), null, null, false);
        fixture.runStepThread();
        fixture.service.terminateJobFlows(List.of(cluster.getId()));
        endStorage(cluster);
    }

    private void endStorage(EmrCluster cluster) {
        while (fixture.stepThreadsWaiting() > 0) {
            fixture.runStepThread();
        }
        assertTrue(fixture.clusterStorage.removed.contains(cluster.getId()));
        fixture.clock.advance(Duration.ofMinutes(1));
    }

    private EmrCluster sparkCluster() {
        fixture.clusterStorage.ownNetwork = true;
        EmrCluster cluster = cluster("history", true);
        cluster.setReleaseLabel("emr-7.5.0");
        cluster.setApplications("[{\"Name\":\"Hadoop\"},{\"Name\":\"Spark\"}]");
        return fixture.service.runJobFlow(cluster, REGION);
    }

    private static String arn(EmrCluster cluster) {
        return "arn:aws:elasticmapreduce:us-east-1:000000000000:cluster/" + cluster.getId();
    }

    /** Ends each command at once, as the Docker runner would one that runs nothing. */
    private static final class FinishingRunner implements SparkJobRunner {

        @Override
        public Outcome run(Job job, Progress progress) {
            progress.started();
            return Outcome.exitedWith(0);
        }

        @Override
        public void stop(String runId) {
        }

        @Override
        public void removeLeftoverDrivers(String service) {
        }
    }
}
