package io.floci.aws.services.emr;

import io.floci.aws.core.common.AppUiRoute;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.testing.ManualClock;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// It names Floci's port, localhost:4566, and opens no socket.
@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class EmrTrinoRoutesTest {

    private static final String CLUSTER = "j-2AXXXXXXGAPLF";
    private static final String HOST = "j-2axxxxxxgaplf.emr-trino.localhost:4566";
    private static final ContainerLifecycleManager.EndpointInfo ENDPOINT =
            new ContainerLifecycleManager.EndpointInfo("127.0.0.1", 41234);

    private final EmrService clusters = mock(EmrService.class);
    private final ManualClock clock = new ManualClock(Instant.parse("2026-11-26T12:00:00Z"));
    private EmrTrinoRoutes routes;

    @BeforeEach
    void setUp() {
        routes = new EmrTrinoRoutes(clusters, clock);
    }

    @Test
    void aHostOfAnotherKindIsNoneOfItsRoutes() {
        assertNull(routes.route("j-2axxxxxxgaplf.emrappui.localhost:4566", "http"));
        assertNull(routes.route("localhost:4566", "http"));
        assertNull(routes.route("my-bucket.emr-trino.localhost:4566", "http"));
        assertNull(routes.route("emr-trino.localhost:4566", "http"));
    }

    @Test
    void aClusterWithNoTrinoOfFlocisIsNotFound() {
        when(clusters.trinoCluster(CLUSTER)).thenReturn(Optional.empty());

        AppUiRoute route = routes.route(HOST, "http");

        assertEquals(404, route.status());
        assertEquals(String.format(EmrTrinoRoutes.NO_SUCH_CLUSTER, CLUSTER), route.refusal());
    }

    @Test
    void aClusterStillStartingIsAskedAgainAndOneThatHasEndedIsGone() {
        when(clusters.trinoCluster(CLUSTER)).thenReturn(Optional.of(cluster("STARTING")));
        assertEquals(503, routes.route(HOST, "http").status());

        when(clusters.trinoCluster(CLUSTER)).thenReturn(Optional.of(cluster("TERMINATED")));
        assertEquals(404, routes.route(HOST, "http").status());
        when(clusters.trinoCluster(CLUSTER)).thenReturn(Optional.of(cluster("TERMINATING")));
        assertEquals(String.format(EmrTrinoRoutes.CLUSTER_ENDED, CLUSTER), routes.route(HOST, "http").refusal());

        verify(clusters, never()).startPrimaryNode(anyString());
        verify(clusters, never()).trinoInterface(anyString(), anyInt());
    }

    @Test
    void aTrinoNotAnsweringYetHasItsPrimaryNodeStartedAndTheClientAskedAgain() {
        when(clusters.trinoCluster(CLUSTER)).thenReturn(Optional.of(cluster("WAITING")));
        when(clusters.trinoInterface(CLUSTER, 8890)).thenReturn(Optional.empty());

        AppUiRoute route = routes.route(HOST, "http");

        assertEquals(503, route.status());
        assertEquals(String.format(EmrTrinoRoutes.NOT_READY, CLUSTER), route.refusal());
        verify(clusters).startPrimaryNode(CLUSTER);
    }

    @Test
    void aTrinoWhoseStartFailedTellsTheClientWhyWhileItIsNotTriedAgain() {
        when(clusters.trinoCluster(CLUSTER)).thenReturn(Optional.of(cluster("WAITING")));
        when(clusters.trinoInterface(CLUSTER, 8890)).thenReturn(Optional.empty());
        when(clusters.primaryNodeFailure(CLUSTER)).thenReturn(Optional.of("Trino stopped before it was ready"));

        AppUiRoute route = routes.route(HOST, "http");

        assertEquals(503, route.status());
        assertEquals(String.format(EmrTrinoRoutes.NOT_STARTED, CLUSTER, "Trino stopped before it was ready"),
                route.refusal());
        // Asked for still: the service does not start it again within the minute.
        verify(clusters).startPrimaryNode(CLUSTER);
    }

    @Test
    void aRunningTrinoIsReachedWithTheSchemeTheClientUsedItsPortFoundOnceInTenSeconds() {
        when(clusters.trinoCluster(CLUSTER)).thenReturn(Optional.of(cluster("RUNNING")));
        when(clusters.trinoInterface(CLUSTER, 8890)).thenReturn(Optional.of(ENDPOINT));

        // A host name is read without case.
        AppUiRoute route = routes.route(HOST.toUpperCase(), "https");

        assertNull(route.refusal());
        assertEquals("127.0.0.1", route.host());
        assertEquals(41234, route.port());
        assertEquals(Map.of("X-Forwarded-Proto", "https"), route.headers());
        assertEquals(Map.of(), route.rewrites());
        assertEquals(503, route.unansweredStatus());
        assertEquals(Map.of("X-Forwarded-Proto", "http"), routes.route(HOST, "http").headers());
        verify(clusters, times(1)).trinoInterface(CLUSTER, 8890);

        clock.advance(Duration.ofSeconds(10));
        routes.route(HOST, "http");
        verify(clusters, times(2)).trinoInterface(CLUSTER, 8890);

        // One that does not answer is looked for again.
        routes.route(HOST, "http").unreachable().run();
        routes.route(HOST, "http");
        verify(clusters, times(3)).trinoInterface(CLUSTER, 8890);
        verify(clusters, never()).startPrimaryNode(anyString());
    }

    private static EmrService.TrinoCluster cluster(String state) {
        return new EmrService.TrinoCluster("000000000000", "us-east-1", CLUSTER, state, 8890);
    }
}
