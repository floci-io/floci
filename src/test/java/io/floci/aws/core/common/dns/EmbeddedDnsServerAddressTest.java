package io.floci.aws.core.common.dns;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.CurrentContainerNetworkResolver;
import io.floci.aws.core.common.docker.DockerBridgeAddress;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.datagram.DatagramSocket;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.SocketException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The address the embedded DNS server gives the containers Floci launches. */
class EmbeddedDnsServerAddressTest {

    @Test
    void ownAddressIsThisContainersOnTheNetworkItsContainersJoin() throws Exception {
        CurrentContainerNetworkResolver currentContainer = mock(CurrentContainerNetworkResolver.class);
        // Not the host's lookup, which takes the first of its networks by name, one Floci made after a crash.
        when(currentContainer.resolveContainerIp()).thenReturn(Optional.of("172.19.0.2"));

        assertEquals("172.19.0.2", EmbeddedDnsServer.ownAddress(currentContainer));
    }

    @Test
    void ownAddressIsTheHostsLookupWhenThatNetworkCannotBeTold() throws Exception {
        CurrentContainerNetworkResolver currentContainer = mock(CurrentContainerNetworkResolver.class);
        when(currentContainer.resolveContainerIp()).thenReturn(Optional.empty());

        assertEquals(InetAddress.getLocalHost().getHostAddress(), EmbeddedDnsServer.ownAddress(currentContainer));
    }

    private final Vertx vertx = mock(Vertx.class);
    private final DatagramSocket socket = mock(DatagramSocket.class);
    private final DockerBridgeAddress dockerBridge = mock(DockerBridgeAddress.class);

    private static EmulatorConfig config(OptionalInt hostPort, boolean bridgeListenerEnabled) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.hostname()).thenReturn(Optional.empty());
        when(config.dns().extraSuffixes()).thenReturn(Optional.empty());
        when(config.dns().containerFallbackServers()).thenReturn(List.of());
        when(config.dns().spoofAwsEndpoints()).thenReturn(false);
        when(config.dns().hostPort()).thenReturn(hostPort);
        when(config.dns().bridgeListenerEnabled()).thenReturn(bridgeListenerEnabled);
        return config;
    }

    @SuppressWarnings("unchecked")
    private EmbeddedDnsServer server(EmulatorConfig config, boolean inContainer) {
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        when(containerDetector.isRunningInContainer()).thenReturn(inContainer);
        CurrentContainerNetworkResolver currentContainer = mock(CurrentContainerNetworkResolver.class);
        when(currentContainer.resolveContainerIp()).thenReturn(Optional.of("172.19.0.2"));
        when(vertx.createDatagramSocket(any())).thenReturn(socket);
        return new EmbeddedDnsServer(config, containerDetector, vertx, mock(Instance.class),
                mock(Instance.class), mock(Instance.class), currentContainer, dockerBridge);
    }

    @Test
    void inAContainerTheServerAnswersAtThatAddress() {
        when(socket.listen(53, "172.19.0.2")).thenReturn(Future.succeededFuture(socket));

        // The host port belongs to a native Floci; in Docker the container's port 53 is published instead.
        EmbeddedDnsServer server = server(config(OptionalInt.of(5354), true), true);

        assertEquals(Optional.of("172.19.0.2"), server.getServerIp());
        verify(socket).listen(53, "172.19.0.2");
        verify(socket, never()).listen(5354, "127.0.0.1");
        verify(dockerBridge, never()).resolve();
    }

    // Catches: a native Floci leaving the containers it launches without its resolver, so Cloud Map
    // and Route 53 private names fail in them.
    @Test
    void nativelyTheContainersResolveThroughTheDockerBridge() {
        when(dockerBridge.resolve()).thenReturn(Optional.of("172.17.0.1"));
        when(socket.listen(53, "172.17.0.1")).thenReturn(Future.succeededFuture(socket));

        EmbeddedDnsServer server = server(config(OptionalInt.empty(), true), false);

        assertEquals(Optional.of("172.17.0.1"), server.getServerIp());
    }

    // Catches: the host listener handed to containers, which cannot reach the host's loopback.
    @Test
    void nativelyTheHostListenerAnswersOnLoopbackAndIsNotGivenToContainers() {
        when(socket.listen(5354, "127.0.0.1")).thenReturn(Future.succeededFuture(socket));

        EmbeddedDnsServer server = server(config(OptionalInt.of(5354), false), false);

        verify(socket).listen(5354, "127.0.0.1");
        assertEquals(Optional.empty(), server.getServerIp());
    }

    // Catches: containers pointed at a bridge listener that never started, so every name they look up fails.
    @Test
    void nativelyABridgeTheProcessMayNotBindLeavesContainersResolvingThroughDocker() {
        when(dockerBridge.resolve()).thenReturn(Optional.of("172.17.0.1"));
        when(socket.listen(53, "172.17.0.1"))
                .thenReturn(Future.failedFuture(new SocketException("Permission denied")));

        EmbeddedDnsServer server = server(config(OptionalInt.empty(), true), false);

        assertEquals(Optional.empty(), server.getServerIp());
    }

    // Catches: startup going on while the containers' listener is still binding, so a container launched in
    // that window is created without Floci's resolver and is never given it.
    @Test
    void startupWaitsForTheContainersListenerToBind() throws Exception {
        when(dockerBridge.resolve()).thenReturn(Optional.of("172.17.0.1"));
        Promise<DatagramSocket> bind = Promise.promise();
        CountDownLatch bindRequested = new CountDownLatch(1);
        when(socket.listen(53, "172.17.0.1")).thenAnswer(invocation -> {
            bindRequested.countDown();
            return bind.future();
        });

        CompletableFuture<EmbeddedDnsServer> started =
                CompletableFuture.supplyAsync(() -> server(config(OptionalInt.empty(), true), false));
        assertTrue(bindRequested.await(5, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> started.get(200, TimeUnit.MILLISECONDS),
                "startup must still be waiting on the bind");
        bind.complete(socket);

        assertEquals(Optional.of("172.17.0.1"), started.get(5, TimeUnit.SECONDS).getServerIp());
    }

    @Test
    void nativelyWithNoBridgeAndNoHostPortNothingListens() {
        when(dockerBridge.resolve()).thenReturn(Optional.empty());

        EmbeddedDnsServer server = server(config(OptionalInt.empty(), true), false);

        verify(vertx, never()).createDatagramSocket(any());
        assertEquals(Optional.empty(), server.getServerIp());
    }

    @Test
    void theBridgeListenerCanBeTurnedOff() {
        when(dockerBridge.resolve()).thenReturn(Optional.of("172.17.0.1"));

        server(config(OptionalInt.empty(), false), false);

        verify(socket, never()).listen(anyInt(), anyString());
    }

    @Test
    void aHostPortOutsideThePortRangeStartsNoListener() {
        server(config(OptionalInt.of(70000), false), false);

        verify(socket, never()).listen(anyInt(), anyString());
    }
}
