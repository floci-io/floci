package io.floci.aws.services.emr;

import io.floci.aws.core.common.LinkSigner;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.emr.model.EmrCluster;
import io.floci.aws.services.emr.model.EmrPersistentAppUi;
import io.floci.aws.services.emrserverless.EmrServerlessSparkRunner;
import io.floci.aws.services.emrserverless.SparkJobRunner;
import io.floci.aws.services.iam.IamService;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.floci.aws.services.emr.EmrServiceFixture.ACCOUNT;
import static io.floci.aws.services.emr.EmrServiceFixture.REGION;
import static io.floci.aws.services.emr.EmrServiceFixture.cluster;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * On-cluster application UI links in the docker runner mode ({@link EmrAppUiService}): an interface of the cluster's
 * primary node opens on a host of its own, which routes to the node's port, its pages' links to the node's other
 * interfaces made the browser's; until the node runs, the link starts it and is not ready.
 */
class EmrOnClusterAppUiTest {

    private static final int RESOURCE_MANAGER = EmrClusterStorage.RESOURCE_MANAGER_WEB_PORT;
    private static final int JOB_HISTORY = EmrServerlessSparkRunner.JOB_HISTORY_WEB_PORT;
    private static final ContainerLifecycleManager.EndpointInfo RESOURCE_MANAGER_ON_HOST =
            new ContainerLifecycleManager.EndpointInfo("localhost", 32801);

    private final RecordingRunner runner = new RecordingRunner();
    private final EmrServiceFixture fixture = EmrServiceFixture.docker(runner, mock(IamService.class));
    private final EmrAppUiService appUis = new EmrAppUiService(
            AccountAwareStorageBackend.<EmrPersistentAppUi>inMemory(ACCOUNT), fixture.service,
            new RegionResolver(REGION, ACCOUNT), fixture.clock, "http://localhost:4566", LinkSigner.withNewKey());

    @Test
    void theResourceManagersLinkOpensItOnAHostOfItsOwnWhoseLinksToTheNodesOtherInterfacesAreTheBrowsers() {
        fixture.clusterStorage.webInterfaces.put(RESOURCE_MANAGER, RESOURCE_MANAGER_ON_HOST);
        EmrCluster cluster = yarnCluster();

        EmrAppUiService.Presigned link = appUis.onClusterUrl(cluster.getId(), null, null, false);

        assertTrue(link.ready());
        URI url = URI.create(link.url());
        assertEquals("/cluster", url.getPath());
        assertTrue(url.getAuthority().matches("[a-z2-7]{26}-8088\\.emrappui\\.localhost:4566"), link.url());
        String secret = url.getHost().substring(0, 26);
        EmrAppUiService.UiRoute route = appUis.route(url.getAuthority(), "http");
        assertNull(route.refusal());
        assertEquals("localhost", route.host());
        assertEquals(32801, route.port());
        String primary = EmrService.masterPrivateDnsName(cluster);
        assertEquals("http://" + secret + "-" + JOB_HISTORY + ".emrappui.localhost:4566",
                route.rewrites().get("http://" + primary + ":" + JOB_HISTORY));
        assertEquals("http://" + secret + "-" + RESOURCE_MANAGER + ".emrappui.localhost:4566",
                route.rewrites().get("http://" + primary + ":" + RESOURCE_MANAGER));
        // The NodeManager of a primary node that works, as on a cluster of one node.
        assertTrue(route.rewrites().containsKey("http://" + primary + ":" + EmrClusterStorage.NODE_MANAGER_WEB_PORT));
        assertEquals(0, fixture.stepThreadsWaiting());
    }

    @Test
    void eachInterfaceLandsOnTheApplicationAskedFor() {
        fixture.clusterStorage.webInterfaces.put(RESOURCE_MANAGER, RESOURCE_MANAGER_ON_HOST);
        String clusterId = yarnCluster().getId();
        String application = "application_1763395200000_0002";

        assertEquals("/cluster/app/" + application,
                path(appUis.onClusterUrl(clusterId, "ResourceManager", application, false)));
        assertEquals("/proxy/" + application + "/",
                path(appUis.onClusterUrl(clusterId, "ApplicationMaster", application, false)));
        assertEquals("/jobhistory/job/job_1763395200000_0002",
                path(appUis.onClusterUrl(clusterId, "JobHistoryServer", application, false)));
        assertTrue(appUis.onClusterUrl(clusterId, "JobHistoryServer", null, false).url().contains("-19888."));
        // A cluster with Spark: its Spark History Server, on the primary node.
        EmrAppUiService.Presigned history = appUis.onClusterUrl(clusterId, "SparkHistoryServer", application, false);
        assertTrue(history.url().contains("-18080.emrappui."), history.url());
        assertEquals("/history/" + application + "/", path(history));
        assertEquals("/", path(appUis.onClusterUrl(clusterId, "SparkHistoryServer", null, false)));
    }

    @Test
    void aLinkAskedBeforeThePrimaryNodeRunsStartsItOnceAndIsNotReadyUntilThen() {
        String clusterId = yarnCluster().getId();

        EmrAppUiService.Presigned first = appUis.onClusterUrl(clusterId, null, null, false);
        EmrAppUiService.Presigned second = appUis.onClusterUrl(clusterId, null, null, false);

        assertFalse(first.ready());
        assertFalse(second.ready());
        // One start, its command run as a step's on the cluster, its HDFS and YARN started first.
        assertEquals(1, fixture.stepThreadsWaiting());
        EmrAppUiService.UiRoute notYet = appUis.route(URI.create(first.url()).getAuthority(), "http");
        assertEquals(503, notYet.status());
        assertEquals(EmrAppUiService.NOT_READY, notYet.refusal());
        fixture.runStepThread();
        assertEquals(List.of("true"), runner.jobs.get(0).command());
        fixture.clusterStorage.webInterfaces.put(RESOURCE_MANAGER, RESOURCE_MANAGER_ON_HOST);
        assertNull(appUis.route(URI.create(first.url()).getAuthority(), "http").refusal());
        assertTrue(appUis.onClusterUrl(clusterId, null, null, false).ready());
    }

    @Test
    void aLinkPastItsHourNotThisProcesssOrForAnotherPortIsRefused() {
        fixture.clusterStorage.webInterfaces.put(RESOURCE_MANAGER, RESOURCE_MANAGER_ON_HOST);
        String authority = URI.create(appUis.onClusterUrl(yarnCluster().getId(), null, null, false).url())
                .getAuthority();
        String secret = authority.substring(0, 26);

        assertEquals(404, appUis.route(secret + "-9870.emrappui.localhost:4566", "http").status());
        assertEquals(403, appUis.route("a".repeat(26) + "-8088.emrappui.localhost:4566", "http").status());
        // Not a link's host at all, which goes on to the rest of Floci.
        assertNull(appUis.route("bucket.emrappui.localhost:4566", "http"));
        fixture.clock.advance(EmrAppUiService.VALIDITY);
        EmrAppUiService.UiRoute expired = appUis.route(authority, "http");
        assertEquals(403, expired.status());
        assertEquals(EmrAppUiController.INVALID, expired.refusal());
    }

    @Test
    void aLinkOfAClusterThatHasEndedReachesNothing() {
        fixture.clusterStorage.webInterfaces.put(RESOURCE_MANAGER, RESOURCE_MANAGER_ON_HOST);
        String clusterId = yarnCluster().getId();
        String authority = URI.create(appUis.onClusterUrl(clusterId, null, null, false).url()).getAuthority();
        assertNull(appUis.route(authority, "http").refusal());

        fixture.service.terminateJobFlows(List.of(clusterId));
        EmrAppUiService.UiRoute ended = appUis.route(authority, "http");

        // Its node's port, still within the link's hour, may be another container's by then.
        assertEquals(404, ended.status());
        assertEquals(String.format(EmrAppUiService.CLUSTER_ENDED, clusterId), ended.refusal());
    }

    @Test
    void aPortFoundOnThePrimaryNodeIsAskedForAgainAfterAFewSeconds() {
        fixture.clusterStorage.webInterfaces.put(RESOURCE_MANAGER, RESOURCE_MANAGER_ON_HOST);
        String authority = URI.create(appUis.onClusterUrl(yarnCluster().getId(), null, null, false).url())
                .getAuthority();
        assertEquals(32801, appUis.route(authority, "http").port());
        // The node started again, its port published anew.
        fixture.clusterStorage.webInterfaces.put(RESOURCE_MANAGER,
                new ContainerLifecycleManager.EndpointInfo("localhost", 32901));

        assertEquals(32801, appUis.route(authority, "http").port());
        fixture.clock.advance(Duration.ofSeconds(10));
        assertEquals(32901, appUis.route(authority, "http").port());
    }

    @Test
    void anInterfaceFlociDoesNotRunOpensFlocisOwnPage() {
        fixture.clusterStorage.webInterfaces.put(RESOURCE_MANAGER, RESOURCE_MANAGER_ON_HOST);

        EmrAppUiService.Presigned link = appUis.onClusterUrl(yarnCluster().getId(), "TezUI", null, false);

        assertTrue(link.ready());
        assertTrue(link.url().startsWith("http://localhost:4566" + EmrAppUiService.PATH + "?"), link.url());
        assertEquals(0, fixture.stepThreadsWaiting());
    }

    private EmrCluster yarnCluster() {
        fixture.clusterStorage.ownNetwork = true;
        EmrCluster cluster = cluster("app-uis", true);
        cluster.setReleaseLabel("emr-7.5.0");
        cluster.setApplications("[{\"Name\":\"Hadoop\"},{\"Name\":\"Spark\"}]");
        return fixture.service.runJobFlow(cluster, REGION);
    }

    private static String path(EmrAppUiService.Presigned link) {
        return URI.create(link.url()).getPath();
    }

    /** Runs each command at once, as the Docker runner would. */
    private static final class RecordingRunner implements SparkJobRunner {
        final List<Job> jobs = new CopyOnWriteArrayList<>();

        @Override
        public Outcome run(Job job, Progress progress) {
            jobs.add(job);
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
