package io.floci.aws.services.lambda.launcher;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.services.lambda.launcher.kubernetes.KubernetesPodLauncher;
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
    @Mock Instance<ProcessLauncher> processInstance;
    @Mock ContainerLauncher containerLauncher;
    @Mock KubernetesPodLauncher kubernetesPodLauncher;
    @Mock ProcessLauncher processLauncher;
    @Mock ContainerDetector containerDetector;

    private EmulatorConfig.LambdaServiceConfig lambda;
    private final LambdaRuntimeLauncherProducer producer = new LambdaRuntimeLauncherProducer();

    @BeforeEach
    void setUp() {
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        lambda = mock(EmulatorConfig.LambdaServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.lambda()).thenReturn(lambda);
        when(lambda.reconcileContainersOnStartup()).thenReturn(true);
        when(dockerInstance.get()).thenReturn(containerLauncher);
        when(kubernetesInstance.get()).thenReturn(kubernetesPodLauncher);
        when(processInstance.get()).thenReturn(processLauncher);
    }

    @Test
    void dockerExecutorSelectsContainerLauncher() {
        when(lambda.executor()).thenReturn("docker");
        assertThat(producer.launcher(config, dockerInstance, kubernetesInstance, processInstance))
                .isSameAs(containerLauncher);
        verify(kubernetesInstance, never()).get();
    }

    @Test
    void kubernetesExecutorSelectsPodLauncher() {
        when(lambda.executor()).thenReturn("kubernetes");
        assertThat(producer.launcher(config, dockerInstance, kubernetesInstance, processInstance))
                .isSameAs(kubernetesPodLauncher);
        verify(dockerInstance, never()).get();
    }

    @Test
    void processExecutorSelectsProcessLauncher() {
        when(lambda.executor()).thenReturn("process");
        assertThat(producer.launcher(config, dockerInstance, kubernetesInstance, processInstance))
                .isSameAs(processLauncher);
        verify(dockerInstance, never()).get();
        verify(kubernetesInstance, never()).get();
    }

    @Test
    void startupRefusesTheProcessExecutorInsideAContainer() {
        when(lambda.executor()).thenReturn("process");
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        assertThatThrownBy(() -> producer.validateExecutor(new StartupEvent(), config, kubernetesInstance,
                containerDetector))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inside a container");
    }

    @Test
    void startupAcceptsTheProcessExecutorOutsideAContainer() {
        when(lambda.executor()).thenReturn("process");
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        assertThatCode(() -> producer.validateExecutor(new StartupEvent(), config, kubernetesInstance,
                containerDetector))
                .doesNotThrowAnyException();
        verify(kubernetesInstance, never()).get();
    }

    @Test
    void processStartupDoesNotInstantiateDocker() {
        when(lambda.enabled()).thenReturn(true);
        when(lambda.executor()).thenReturn("process");
        producer.reconcileDockerContainers(new StartupEvent(), config, dockerInstance);
        verify(dockerInstance, never()).get();
    }

    @Test
    void executorValueIsCaseInsensitiveAndTrimmed() {
        when(lambda.executor()).thenReturn(" Kubernetes\n");
        assertThat(producer.launcher(config, dockerInstance, kubernetesInstance, processInstance))
                .isSameAs(kubernetesPodLauncher);
    }

    @Test
    void unknownExecutorFailsWithValidValues() {
        when(lambda.executor()).thenReturn("podman");
        assertThatThrownBy(() -> producer.launcher(config, dockerInstance, kubernetesInstance, processInstance))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("docker, kubernetes, process");
    }

    @Test
    void startupValidationRejectsUnknownExecutorBeforeFirstInvoke() {
        when(lambda.executor()).thenReturn("podman");
        assertThatThrownBy(() -> producer.validateExecutor(new StartupEvent(), config, kubernetesInstance, containerDetector))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startupValidationAcceptsValidExecutors() {
        when(lambda.executor()).thenReturn("docker");
        assertThatCode(() -> producer.validateExecutor(new StartupEvent(), config, kubernetesInstance, containerDetector))
                .doesNotThrowAnyException();
        verify(kubernetesPodLauncher, never()).initializeClient();
        when(lambda.executor()).thenReturn("kubernetes");
        assertThatCode(() -> producer.validateExecutor(new StartupEvent(), config, kubernetesInstance, containerDetector))
                .doesNotThrowAnyException();
        verify(kubernetesPodLauncher).initializeClient();
    }

    @Test
    void startupReconcilesOnlyTheEnabledDockerExecutor() {
        when(lambda.enabled()).thenReturn(true);
        when(lambda.executor()).thenReturn("docker");
        producer.reconcileDockerContainers(new StartupEvent(), config, dockerInstance);
        verify(containerLauncher).removeLeftoverContainers();
        verify(kubernetesInstance, never()).get();
    }

    @Test
    void kubernetesStartupDoesNotInstantiateDocker() {
        when(lambda.enabled()).thenReturn(true);
        when(lambda.executor()).thenReturn("kubernetes");
        producer.reconcileDockerContainers(new StartupEvent(), config, dockerInstance);
        verify(dockerInstance, never()).get();
    }

    @Test
    void disabledLambdaDoesNotInstantiateDockerForCleanup() {
        producer.reconcileDockerContainers(new StartupEvent(), config, dockerInstance);
        verify(dockerInstance, never()).get();
    }

    @Test
    void disabledReconciliationDoesNotInstantiateDockerForCleanup() {
        when(lambda.enabled()).thenReturn(true);
        when(lambda.executor()).thenReturn("docker");
        when(lambda.reconcileContainersOnStartup()).thenReturn(false);
        producer.reconcileDockerContainers(new StartupEvent(), config, dockerInstance);
        verify(dockerInstance, never()).get();
    }
}
