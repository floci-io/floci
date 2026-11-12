package io.floci.aws.core.common.dns;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.CurrentContainerNetworkResolver;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.datagram.DatagramSocket;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
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

    @Test
    @SuppressWarnings("unchecked")
    void inAContainerTheServerAnswersAtThatAddress() {
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        CurrentContainerNetworkResolver currentContainer = mock(CurrentContainerNetworkResolver.class);
        when(currentContainer.resolveContainerIp()).thenReturn(Optional.of("172.19.0.2"));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.hostname()).thenReturn(Optional.empty());
        when(config.dns().extraSuffixes()).thenReturn(Optional.empty());
        when(config.dns().containerFallbackServers()).thenReturn(List.of());
        when(config.dns().spoofAwsEndpoints()).thenReturn(false);
        Vertx vertx = mock(Vertx.class);
        DatagramSocket socket = mock(DatagramSocket.class);
        when(vertx.createDatagramSocket(any())).thenReturn(socket);
        when(socket.listen(53, "172.19.0.2")).thenReturn(Future.succeededFuture(socket));

        EmbeddedDnsServer server = new EmbeddedDnsServer(config, containerDetector, vertx, mock(Instance.class),
                mock(Instance.class), mock(Instance.class), currentContainer);

        assertEquals(Optional.of("172.19.0.2"), server.getServerIp());
        verify(socket).listen(53, "172.19.0.2");
    }
}
