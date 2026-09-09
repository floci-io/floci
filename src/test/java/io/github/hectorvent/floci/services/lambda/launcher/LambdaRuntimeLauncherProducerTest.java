package io.github.hectorvent.floci.services.lambda.launcher;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.lambda.launcher.kubernetes.KubernetesPodLauncher;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LambdaRuntimeLauncherProducerTest {

    @Mock EmulatorConfig config;
    @Mock Instance<ContainerLauncher> dockerInstance;
    @Mock Instance<KubernetesPodLauncher> kubernetesInstance;
    @Mock ContainerLauncher containerLauncher;
    @Mock KubernetesPodLauncher kubernetesPodLauncher;

    private EmulatorConfig.LambdaServiceConfig lambda;
    private final LambdaRuntimeLauncherProducer producer = new LambdaRuntimeLauncherProducer();

    @BeforeEach
    void setUp() {
        var services = mock(EmulatorConfig.ServicesConfig.class);
        lambda = mock(EmulatorConfig.LambdaServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.lambda()).thenReturn(lambda);
        when(dockerInstance.get()).thenReturn(containerLauncher);
        when(kubernetesInstance.get()).thenReturn(kubernetesPodLauncher);
    }

    @Test
    void dockerExecutorSelectsContainerLauncher() {
        when(lambda.executor()).thenReturn("docker");
        assertThat(producer.launcher(config, dockerInstance, kubernetesInstance))
                .isSameAs(containerLauncher);
        verify(kubernetesInstance, never()).get();
    }

    @Test
    void kubernetesExecutorSelectsPodLauncher() {
        when(lambda.executor()).thenReturn("kubernetes");
        assertThat(producer.launcher(config, dockerInstance, kubernetesInstance))
                .isSameAs(kubernetesPodLauncher);
        verify(dockerInstance, never()).get();
    }

    @Test
    void executorValueIsCaseInsensitiveAndTrimmed() {
        when(lambda.executor()).thenReturn(" Kubernetes\n");
        assertThat(producer.launcher(config, dockerInstance, kubernetesInstance))
                .isSameAs(kubernetesPodLauncher);
    }

    @Test
    void unknownExecutorFailsWithValidValues() {
        when(lambda.executor()).thenReturn("podman");
        assertThatThrownBy(() -> producer.launcher(config, dockerInstance, kubernetesInstance))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("docker, kubernetes");
    }

    @Test
    void startupValidationRejectsUnknownExecutorBeforeFirstInvoke() {
        when(lambda.executor()).thenReturn("podman");
        assertThatThrownBy(() -> producer.validateExecutor(new StartupEvent(), config, kubernetesInstance))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startupValidationAcceptsValidExecutors() {
        when(lambda.executor()).thenReturn("docker");
        assertThatCode(() -> producer.validateExecutor(new StartupEvent(), config, kubernetesInstance))
                .doesNotThrowAnyException();
        verify(kubernetesPodLauncher, never()).initializeClient();
        when(lambda.executor()).thenReturn("kubernetes");
        assertThatCode(() -> producer.validateExecutor(new StartupEvent(), config, kubernetesInstance))
                .doesNotThrowAnyException();
        verify(kubernetesPodLauncher).initializeClient();
    }
}
