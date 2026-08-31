package io.github.hectorvent.floci.services.lambda.launcher.kubernetes;

import io.github.hectorvent.floci.config.EmulatorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KubernetesFlociAddressResolverTest {

    @Mock EmulatorConfig config;
    private EmulatorConfig.LambdaServiceConfig.KubernetesExecutor kubernetes;
    private EmulatorConfig.TlsConfig tls;
    private KubernetesFlociAddressResolver resolver;

    @BeforeEach
    void setUp() {
        var services = mock(EmulatorConfig.ServicesConfig.class);
        var lambda = mock(EmulatorConfig.LambdaServiceConfig.class);
        kubernetes = mock(EmulatorConfig.LambdaServiceConfig.KubernetesExecutor.class);
        tls = mock(EmulatorConfig.TlsConfig.class);
        when(config.services()).thenReturn(services);
        when(services.lambda()).thenReturn(lambda);
        when(lambda.kubernetes()).thenReturn(kubernetes);
        when(lambda.runtimeApiBasePort()).thenReturn(9200);
        when(lambda.runtimeApiMaxPort()).thenReturn(9299);
        when(config.tls()).thenReturn(tls);
        when(tls.enabled()).thenReturn(false);
        when(config.port()).thenReturn(4566);
        resolver = new KubernetesFlociAddressResolver(config);
    }

    @Test
    void configuredAddressIsUsedVerbatim() {
        when(kubernetes.flociAddress()).thenReturn(Optional.of("192.168.1.20"));
        assertThat(resolver.resolve()).isEqualTo("192.168.1.20");
        assertThat(resolver.flociBaseUrl()).isEqualTo("http://192.168.1.20:4566");
    }

    @Test
    void addressWithSchemeIsRejectedEarly() {
        when(kubernetes.flociAddress()).thenReturn(Optional.of("http://192.168.1.20"));
        assertThatThrownBy(resolver::resolve)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bare host or IP");
    }

    @Test
    void addressWithPortIsRejectedEarly() {
        when(kubernetes.flociAddress()).thenReturn(Optional.of("192.168.1.20:4566"));
        assertThatThrownBy(resolver::resolve)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bare host or IP");
    }

    @Test
    void addressWithShellHostileCharactersIsRejectedEarly() {
        // resolve() output is embedded in single-quoted init-container commands,
        // so anything beyond hostname/IP characters must fail fast.
        when(kubernetes.flociAddress()).thenReturn(Optional.of("10.0.0.5'; reboot;'"));
        assertThatThrownBy(resolver::resolve)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bare host or IP");
    }

    @Test
    void tlsSwitchesBaseUrlScheme() {
        when(kubernetes.flociAddress()).thenReturn(Optional.of("floci.internal"));
        when(tls.enabled()).thenReturn(true);
        assertThat(resolver.flociBaseUrl()).isEqualTo("https://floci.internal:4566");
    }

    @Test
    void downloadBaseUrlStaysPlainHttpEvenWithTls() {
        when(kubernetes.flociAddress()).thenReturn(Optional.of("floci.internal"));
        when(tls.enabled()).thenReturn(true);
        assertThat(resolver.downloadBaseUrl()).isEqualTo("http://floci.internal:4566");
    }
}
