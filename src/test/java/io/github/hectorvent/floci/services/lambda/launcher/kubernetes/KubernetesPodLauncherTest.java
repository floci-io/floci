package io.github.hectorvent.floci.services.lambda.launcher.kubernetes;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodStatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.lambda.LambdaLayerService;
import io.github.hectorvent.floci.services.lambda.launcher.ContainerHandle;
import io.github.hectorvent.floci.services.lambda.launcher.ImageResolver;
import io.github.hectorvent.floci.services.lambda.model.ContainerState;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServer;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServerFactory;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@EnableKubernetesMockClient(crud = true)
class KubernetesPodLauncherTest {
    KubernetesClient client;

    private EmulatorConfig config;
    private RuntimeApiServerFactory runtimeApiServerFactory;
    private RuntimeApiServer runtimeApiServer;
    private ImageResolver imageResolver;
    private KubernetesFlociAddressResolver addressResolver;
    private LaunchedContainerAwsEnv awsEnv;
    private LambdaLayerService layerService;
    private KubernetesPodLogStreamer logStreamer;
    private S3Service s3Service;
    private KubernetesPodLauncher launcher;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class);
        var services = mock(EmulatorConfig.ServicesConfig.class);
        var lambda = mock(EmulatorConfig.LambdaServiceConfig.class);
        var kubernetes = mock(EmulatorConfig.LambdaServiceConfig.KubernetesExecutor.class);
        var tls = mock(EmulatorConfig.TlsConfig.class);
        lenient().when(config.services()).thenReturn(services);
        lenient().when(services.lambda()).thenReturn(lambda);
        lenient().when(lambda.kubernetes()).thenReturn(kubernetes);
        lenient().when(lambda.awsConfigPath()).thenReturn(Optional.empty());
        lenient().when(config.tls()).thenReturn(tls);
        lenient().when(tls.enabled()).thenReturn(false);
        lenient().when(config.defaultRegion()).thenReturn("us-east-1");
        lenient().when(kubernetes.namespace()).thenReturn("default");
        lenient().when(kubernetes.initImage()).thenReturn("busybox:1.36");
        lenient().when(kubernetes.labels()).thenReturn(Optional.empty());

        runtimeApiServerFactory = mock(RuntimeApiServerFactory.class);
        runtimeApiServer = mock(RuntimeApiServer.class);
        lenient().when(runtimeApiServerFactory.create()).thenReturn(runtimeApiServer);
        lenient().when(runtimeApiServer.getPort()).thenReturn(9200);
        lenient().when(runtimeApiServer.stop()).thenReturn(CompletableFuture.completedFuture(null));

        imageResolver = mock(ImageResolver.class);
        lenient().when(imageResolver.resolve("python3.12")).thenReturn("public.ecr.aws/lambda/python:3.12");

        addressResolver = mock(KubernetesFlociAddressResolver.class);
        lenient().when(addressResolver.resolve()).thenReturn("10.0.0.5");
        lenient().when(addressResolver.flociBaseUrl()).thenReturn("http://10.0.0.5:4566");
        lenient().when(addressResolver.downloadBaseUrl()).thenReturn("http://10.0.0.5:4566");

        awsEnv = mock(LaunchedContainerAwsEnv.class);
        lenient().when(awsEnv.sdkBaselineEnv(anyString(), any(), anyString()))
                .thenReturn(List.of("AWS_REGION=us-east-1"));

        layerService = mock(LambdaLayerService.class);
        logStreamer = mock(KubernetesPodLogStreamer.class);
        lenient().when(logStreamer.attach(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn(() -> { });
        lenient().when(logStreamer.logStreamName(anyString())).thenReturn("2026/07/25/[$LATEST]test");
        s3Service = mock(S3Service.class);

        launcher = new KubernetesPodLauncher(client, config, runtimeApiServerFactory, imageResolver,
                addressResolver, awsEnv, layerService, new LambdaPodSpecFactory(config),
                logStreamer, s3Service);
    }

    private LambdaFunction function() {
        var fn = new LambdaFunction();
        fn.setFunctionName("my-fn");
        fn.setAccountId("000000000000");
        fn.setFunctionArn("arn:aws:lambda:us-east-1:000000000000:function:my-fn");
        fn.setRuntime("python3.12");
        fn.setHandler("index.handler");
        fn.setMemorySize(128);
        fn.setTimeout(3);
        return fn;
    }

    /** Plays the kubelet: waits for a pod with the given name prefix, then sets its phase. */
    private void markPodPhaseInBackground(String namePrefix, String phase) {
        CompletableFuture.runAsync(() -> {
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(
                    () -> client.pods().inNamespace("default").list().getItems().stream()
                            .anyMatch(p -> p.getMetadata().getName().startsWith(namePrefix)));
            var pod = client.pods().inNamespace("default").list().getItems().stream()
                    .filter(p -> p.getMetadata().getName().startsWith(namePrefix))
                    .findFirst().orElseThrow();
            pod.setStatus(new PodStatusBuilder().withPhase(phase).build());
            client.pods().inNamespace("default").resource(pod).updateStatus();
        });
    }

    @Test
    void launchPassesTheFunctionsOwningAccountIntoTheBaselineAwsEnv() {
        // A pod-launched Lambda is subject to the same placeholder-credential bug as a
        // Docker-launched one: without the owning account it resolves to the emulator's
        // default account and reads the wrong partition.
        markPodPhaseInBackground("floci-lambda-my-fn-", "Running");
        var fn = function();
        fn.setAccountId("222222222222");
        fn.setFunctionArn("arn:aws:lambda:us-east-1:222222222222:function:my-fn");

        launcher.launch(fn);

        verify(awsEnv).sdkBaselineEnv(eq("us-east-1"), eq(Optional.empty()),
                eq("http://10.0.0.5:4566"), eq(Optional.empty()), eq("222222222222"));
    }

    @Test
    void launchDoesNotLetAPartialUserCredentialEnvironmentSplitTheBaselineTuple() {
        // This launcher never has execution-role credentials, so every pod rides the
        // owner-account/placeholder baseline. If the function's own Environment config defines
        // only AWS_ACCESS_KEY_ID (no matching secret or session token), that partial value must
        // not override just the baseline's access key and leave its secret/token in place.
        markPodPhaseInBackground("floci-lambda-my-fn-", "Running");
        lenient().when(awsEnv.sdkBaselineEnv(anyString(), any(), anyString(), any(), anyString()))
                .thenReturn(List.of(
                        "AWS_ACCESS_KEY_ID=000000000000",
                        "AWS_SECRET_ACCESS_KEY=test",
                        "AWS_SESSION_TOKEN=test"));
        var fn = function();
        fn.setEnvironment(Map.of("AWS_ACCESS_KEY_ID", "user-partial-key"));

        launcher.launch(fn);

        var pod = client.pods().inNamespace("default").list().getItems().stream()
                .filter(p -> p.getMetadata().getName().startsWith("floci-lambda-my-fn-"))
                .findFirst().orElseThrow();
        var envVars = pod.getSpec().getContainers().getFirst().getEnv();
        assertThat(envVars).extracting(EnvVar::getName, EnvVar::getValue)
                .contains(tuple("AWS_ACCESS_KEY_ID", "000000000000"))
                .doesNotContain(tuple("AWS_ACCESS_KEY_ID", "user-partial-key"));
    }

    @Test
    void launchCreatesPodAndReturnsHandle() {
        markPodPhaseInBackground("floci-lambda-my-fn-", "Running");

        var handle = launcher.launch(function());

        assertThat(handle).isNotNull();
        assertThat(handle.getFunctionName()).isEqualTo("my-fn");
        assertThat(handle.getContainerId()).startsWith("floci-lambda-my-fn-");

        var pod = client.pods().inNamespace("default").withName(handle.getContainerId()).get();
        assertThat(pod).isNotNull();
        assertThat(pod.getMetadata().getLabels())
                .containsEntry("app.kubernetes.io/managed-by", "floci");
        assertThat(pod.getSpec().getInitContainers()).hasSize(1);
        assertThat(pod.getSpec().getContainers().getFirst().getEnv())
                .extracting(EnvVar::getName, EnvVar::getValue)
                .contains(tuple("AWS_LAMBDA_RUNTIME_API", "10.0.0.5:9200"));
        assertThat(pod.getSpec().getInitContainers().getFirst().getCommand().get(2))
                .contains("http://10.0.0.5:4566/awslambda-us-east-1-tasks/snapshots/000000000000/my-fn");
    }

    @Test
    void downloadUrlCarriesTheOwningAccountForNonDefaultAccounts() {
        var fn = function();
        fn.setAccountId("111122223333");
        fn.setFunctionArn("arn:aws:lambda:us-east-1:111122223333:function:my-fn");
        markPodPhaseInBackground("floci-lambda-my-fn-", "Running");

        var handle = launcher.launch(fn);

        // The init container fetches unauthenticated, so the download URL must carry the
        // owning account or the object resolves under the default account and 404s.
        var pod = client.pods().inNamespace("default").withName(handle.getContainerId()).get();
        assertThat(pod.getSpec().getInitContainers().getFirst().getCommand().get(2))
                .contains("snapshots/111122223333/my-fn?X-Amz-Credential=111122223333%2F");
    }

    @Test
    void launchFailureReleasesPortAndDeletesPod() {
        markPodPhaseInBackground("floci-lambda-my-fn-", "Failed");

        assertThatThrownBy(() -> launcher.launch(function()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("failed to start");

        verify(runtimeApiServer).stop();
        verify(runtimeApiServerFactory).release(runtimeApiServer);
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(
                () -> client.pods().inNamespace("default").list().getItems().isEmpty());
    }

    @Test
    void launchFailureKeepsPortReservedWhenPodDeleteIsNotConfirmed() {
        // Once the pod is parked in Failed, every further Kubernetes API call fails, so
        // the cleanup delete can never confirm the pod is gone. The pod could still reach
        // Running later and poll AWS_LAMBDA_RUNTIME_API, so the port must stay reserved
        // instead of going back to the pool for another environment.
        var failingClient = spy(client);
        doAnswer(invocation -> {
            var failedPodExists = client.pods().inNamespace("default").list().getItems().stream()
                    .anyMatch(p -> p.getStatus() != null && "Failed".equals(p.getStatus().getPhase()));
            if (failedPodExists) {
                throw new RuntimeException("kube api down");
            }
            return invocation.callRealMethod();
        }).when(failingClient).pods();
        var failingLauncher = new KubernetesPodLauncher(failingClient, config,
                runtimeApiServerFactory, imageResolver, addressResolver, awsEnv, layerService,
                new LambdaPodSpecFactory(config), logStreamer, s3Service);
        markPodPhaseInBackground("floci-lambda-my-fn-", "Failed");

        assertThatThrownBy(() -> failingLauncher.launch(function()))
                .isInstanceOf(RuntimeException.class);

        verify(runtimeApiServer).stop();
        verify(runtimeApiServerFactory, never()).release(runtimeApiServer);
        assertThat(client.pods().inNamespace("default").list().getItems())
                .as("the unconfirmed pod is left for a later sweep")
                .isNotEmpty();
    }

    @Test
    void podThatExitsCleanlyIsTerminalNotATimeout() {
        // A runtime that exits before serving parks the pod in Succeeded; the launch
        // must fail fast instead of blocking for the full startup timeout.
        markPodPhaseInBackground("floci-lambda-my-fn-", "Succeeded");

        assertThatThrownBy(() -> launcher.launch(function()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("failed to start");
        verify(runtimeApiServerFactory).release(runtimeApiServer);
    }

    @Test
    void hotReloadIsRejectedBeforeAllocatingAnything() {
        var fn = function();
        fn.setHotReloadHostPath("/tmp/code");

        assertThatThrownBy(() -> launcher.launch(fn))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Hot reload");
        verify(runtimeApiServerFactory, never()).create();
    }

    @Test
    void missingCodeSnapshotFailsWithActionableMessage() {
        when(s3Service.headObject(anyString(), anyString()))
                .thenThrow(new AwsException("NoSuchKey", "not found", 404));

        assertThatThrownBy(() -> launcher.launch(function()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Re-deploy the function code");
        verify(runtimeApiServerFactory).release(runtimeApiServer);
    }

    @Test
    void stopDeletesPodAndReleasesRuntimeApiPort() {
        var pod = new PodBuilder()
                .withNewMetadata().withName("floci-lambda-my-fn-abc12345").endMetadata()
                .build();
        client.pods().inNamespace("default").resource(pod).create();

        var handle = new ContainerHandle("floci-lambda-my-fn-abc12345", "my-fn",
                runtimeApiServer, ContainerState.WARM);
        launcher.stop(handle);

        assertThat(client.pods().inNamespace("default").withName("floci-lambda-my-fn-abc12345").get())
                .isNull();
        verify(runtimeApiServer).stop();
        verify(runtimeApiServerFactory).release(runtimeApiServer);
        assertThat(handle.getState()).isEqualTo(ContainerState.STOPPED);
    }

    @Test
    void isAliveReflectsPodPhase() {
        var running = new PodBuilder()
                .withNewMetadata().withName("pod-running").endMetadata()
                .withStatus(new PodStatusBuilder().withPhase("Running").build())
                .build();
        var pending = new PodBuilder()
                .withNewMetadata().withName("pod-pending").endMetadata()
                .withStatus(new PodStatusBuilder().withPhase("Pending").build())
                .build();
        client.pods().inNamespace("default").resource(running).create();
        client.pods().inNamespace("default").resource(pending).create();

        assertThat(launcher.isAlive(handleFor("pod-running"))).isTrue();
        assertThat(launcher.isAlive(handleFor("pod-pending"))).isFalse();
        assertThat(launcher.isAlive(handleFor("pod-absent"))).isFalse();
    }

    @Test
    void orphanSweepDeletesOnlyManagedPods() {
        var orphan = new PodBuilder()
                .withNewMetadata().withName("floci-lambda-old-fn-dead1234")
                .withLabels(Map.of(
                        "app.kubernetes.io/managed-by", "floci",
                        "floci.io/service", "lambda"))
                .endMetadata()
                .build();
        var unrelated = new PodBuilder()
                .withNewMetadata().withName("some-other-pod").endMetadata()
                .build();
        client.pods().inNamespace("default").resource(orphan).create();
        client.pods().inNamespace("default").resource(unrelated).create();

        // Fail the launch right after the sweep so the test doesn't need a running pod.
        when(imageResolver.resolve(anyString())).thenThrow(new RuntimeException("no image"));
        assertThatThrownBy(() -> launcher.launch(function()))
                .isInstanceOf(RuntimeException.class);

        Awaitility.await().atMost(Duration.ofSeconds(5)).until(
                () -> client.pods().inNamespace("default").withName("floci-lambda-old-fn-dead1234").get() == null);
        assertThat(client.pods().inNamespace("default").withName("some-other-pod").get()).isNotNull();
    }

    @Test
    void stopDeletesPodEvenWhenPortReleaseThrows() {
        var pod = new PodBuilder()
                .withNewMetadata().withName("floci-lambda-my-fn-abc12345").endMetadata()
                .build();
        client.pods().inNamespace("default").resource(pod).create();
        doThrow(new IllegalStateException("release failed"))
                .when(runtimeApiServerFactory).release(runtimeApiServer);
        var streamClosed = new AtomicBoolean();
        var handle = new ContainerHandle("floci-lambda-my-fn-abc12345", "my-fn",
                runtimeApiServer, ContainerState.WARM);
        handle.setLogStream(() -> streamClosed.set(true));

        launcher.stop(handle);

        assertThat(client.pods().inNamespace("default").withName("floci-lambda-my-fn-abc12345").get())
                .isNull();
        assertThat(streamClosed).isTrue();
    }

    @Test
    void failedOrphanSweepIsRetriedWithoutKillingOwnPods() {
        var orphan = new PodBuilder()
                .withNewMetadata().withName("floci-lambda-old-fn-dead1234")
                .withLabels(Map.of(
                        "app.kubernetes.io/managed-by", "floci",
                        "floci.io/service", "lambda"))
                .endMetadata()
                .build();
        client.pods().inNamespace("default").resource(orphan).create();

        // First Kubernetes API access (the sweep's pod list) fails once.
        var failedOnce = new AtomicBoolean();
        var flakyClient = spy(client);
        doAnswer(invocation -> {
            if (failedOnce.compareAndSet(false, true)) {
                throw new RuntimeException("kube api down");
            }
            return invocation.callRealMethod();
        }).when(flakyClient).pods();
        var retryingLauncher = new KubernetesPodLauncher(flakyClient, config,
                runtimeApiServerFactory, imageResolver, addressResolver, awsEnv, layerService,
                new LambdaPodSpecFactory(config), logStreamer, s3Service);

        markPodPhaseInBackground("floci-lambda-my-fn-", "Running");
        var handle = retryingLauncher.launch(function());
        assertThat(client.pods().inNamespace("default").withName("floci-lambda-old-fn-dead1234").get())
                .as("orphan survives the failed sweep")
                .isNotNull();

        // The next launch retries the sweep. Fail it right after the sweep so the
        // test does not need a second running pod.
        when(imageResolver.resolve(anyString())).thenThrow(new RuntimeException("no image"));
        assertThatThrownBy(() -> retryingLauncher.launch(function()))
                .isInstanceOf(RuntimeException.class);

        Awaitility.await().atMost(Duration.ofSeconds(5)).until(
                () -> client.pods().inNamespace("default").withName("floci-lambda-old-fn-dead1234").get() == null);
        assertThat(client.pods().inNamespace("default").withName(handle.getContainerId()).get())
                .as("the retried sweep must not delete this process's own pod")
                .isNotNull();
    }

    private ContainerHandle handleFor(String podName) {
        return new ContainerHandle(podName, "my-fn", runtimeApiServer, ContainerState.WARM);
    }
}
