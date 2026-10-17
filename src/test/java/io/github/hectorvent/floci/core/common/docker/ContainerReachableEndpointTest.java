package io.github.hectorvent.floci.core.common.docker;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Launched containers talk to the Floci process directly, so the port must be the one Floci
 * listens on ({@code quarkus.http.port}, or {@code floci.port} under TLS where the proxy owns the
 * public port), not the host-facing port advertised in {@code floci.base-url}.
 * With {@code docker run -p 4811:4566 -e FLOCI_BASE_URL=http://localhost:4811}, port 4811 exists
 * only on the host; inside the Docker network Floci answers on 4566.
 */
class ContainerReachableEndpointTest {

    private final EmulatorConfig config = mock(EmulatorConfig.class);
    private final DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
    private final EmbeddedDnsServer embeddedDnsServer = mock(EmbeddedDnsServer.class);
    private final EmulatorConfig.TlsConfig tls = mock(EmulatorConfig.TlsConfig.class);
    private final ContainerReachableEndpoint endpoint = endpoint(4566);

    private ContainerReachableEndpoint endpoint(int httpPort) {
        when(config.tls()).thenReturn(tls);
        return new ContainerReachableEndpoint(config, dockerHostResolver, embeddedDnsServer, httpPort);
    }

    private void hostMappedTo4811() {
        lenient().when(config.port()).thenReturn(4566);
        lenient().when(config.baseUrl()).thenReturn("http://localhost:4811");
        lenient().when(config.hostname()).thenReturn(Optional.empty());
    }

    @Test
    void embeddedDnsUsesListenPortNotAdvertisedPort() {
        hostMappedTo4811();
        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.of("172.18.0.2"));

        assertEquals("http://" + EmbeddedDnsServer.DEFAULT_SUFFIX + ":4566", endpoint.baseUrl());
    }

    @Test
    void explicitDnsUsesDockerHostEvenWhenEmbeddedDnsIsRunning() {
        hostMappedTo4811();
        lenient().when(embeddedDnsServer.getServerIp()).thenReturn(Optional.of("172.18.0.2"));
        when(dockerHostResolver.resolve()).thenReturn("172.18.0.9");

        assertEquals("http://172.18.0.9:4566", endpoint.baseUrl(false));
    }

    @Test
    void dockerHostFallbackUsesListenPortNotAdvertisedPort() {
        hostMappedTo4811();
        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.empty());
        when(dockerHostResolver.resolve()).thenReturn("172.18.0.2");

        assertEquals("http://172.18.0.2:4566", endpoint.baseUrl());
    }

    @Test
    void customListenPortAndHostnameAreHonoured() {
        // Listen port, advertised port and floci.port all differ, so only quarkus.http.port can
        // yield 4900.
        lenient().when(config.port()).thenReturn(4566);
        lenient().when(config.baseUrl()).thenReturn("http://localhost:4811");
        when(config.hostname()).thenReturn(Optional.of("floci"));
        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.of("172.18.0.2"));

        assertEquals("http://floci:4900", endpoint(4900).baseUrl());
    }

    @Test
    void httpPortOverriddenAloneIsTheListenPort() {
        // QUARKUS_HTTP_PORT=4811 FLOCI_BASE_URL=http://localhost:4811 with floci.port left at 4566:
        // the server listens on 4811, so that is where containers must call.
        lenient().when(config.port()).thenReturn(4566);
        lenient().when(config.baseUrl()).thenReturn("http://localhost:4811");
        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.empty());
        when(dockerHostResolver.resolve()).thenReturn("172.18.0.2");

        assertEquals("http://172.18.0.2:4811", endpoint(4811).baseUrl());
        assertEquals("http://172.18.0.2:4811", endpoint(4811).baseUrl(false));
    }

    @Test
    void tlsUsesPublicProxyPortNotInternalHttpPort() {
        // TLS pins quarkus.http.port to a loopback-only backend (4510); the TlsProxyServer serves
        // floci.port, the only one a container can reach.
        when(tls.enabled()).thenReturn(true);
        when(config.port()).thenReturn(4566);
        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.empty());
        when(dockerHostResolver.resolve()).thenReturn("172.18.0.2");

        assertEquals("http://172.18.0.2:4566", endpoint(4510).baseUrl());
        assertEquals("http://172.18.0.2:4566", endpoint(4510).baseUrl(false));
    }
}
