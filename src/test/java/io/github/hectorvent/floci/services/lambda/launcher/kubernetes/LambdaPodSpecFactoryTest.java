package io.github.hectorvent.floci.services.lambda.launcher.kubernetes;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.github.hectorvent.floci.config.EmulatorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LambdaPodSpecFactoryTest {

    @Mock
    EmulatorConfig config;
    private LambdaPodSpecFactory factory;

    private EmulatorConfig.LambdaServiceConfig.KubernetesExecutor kubernetes;
    private EmulatorConfig.TlsConfig tls;

    @BeforeEach
    void setUp() {
        var services = mock(EmulatorConfig.ServicesConfig.class);
        var lambda = mock(EmulatorConfig.LambdaServiceConfig.class);
        kubernetes = mock(EmulatorConfig.LambdaServiceConfig.KubernetesExecutor.class);
        tls = mock(EmulatorConfig.TlsConfig.class);
        when(config.services()).thenReturn(services);
        when(services.lambda()).thenReturn(lambda);
        when(lambda.kubernetes()).thenReturn(kubernetes);
        when(config.tls()).thenReturn(tls);
        when(tls.enabled()).thenReturn(false);
        when(kubernetes.initImage()).thenReturn("busybox:1.36");
        when(kubernetes.labels()).thenReturn(Optional.empty());
        factory = new LambdaPodSpecFactory(config);
    }

    private Pod buildZipPod() {
        return factory.buildPod("floci-lambda-my-fn-abc12345", "my-fn",
                "public.ecr.aws/lambda/python:3.12",
                List.of("AWS_LAMBDA_RUNTIME_API=10.0.0.5:9200", "_HANDLER=index.handler"),
                "http://10.0.0.5:4566/awslambda-us-east-1-tasks/snapshots/000000000000/my-fn",
                List.of(), false, "index.handler", null, 256, Optional.empty());
    }

    @Test
    void zipFunctionPodHasInitContainerTaskVolumeAndEnv() {
        var spec = buildZipPod().getSpec();

        assertThat(spec.getRestartPolicy()).isEqualTo("Never");
        assertThat(spec.getTerminationGracePeriodSeconds()).isEqualTo(5L);
        assertThat(spec.getInitContainers()).hasSize(1);

        var init = spec.getInitContainers().getFirst();
        assertThat(init.getImage()).isEqualTo("busybox:1.36");
        assertThat(init.getCommand().get(2))
                .contains("wget -q -O /tmp/code.zip")
                .contains("unzip -oq /tmp/code.zip -d /var/task")
                .doesNotContain("--no-check-certificate");
        assertThat(init.getVolumeMounts())
                .extracting(VolumeMount::getName, VolumeMount::getMountPath)
                .containsExactly(tuple("task", "/var/task"));

        var runtime = spec.getContainers().getFirst();
        assertThat(runtime.getName()).isEqualTo("runtime");
        assertThat(runtime.getArgs()).containsExactly("index.handler");
        assertThat(runtime.getResources().getLimits().get("memory")).hasToString("256Mi");
        assertThat(runtime.getResources().getRequests().get("memory")).hasToString("256Mi");
        assertThat(runtime.getVolumeMounts())
                .extracting(VolumeMount::getName, VolumeMount::getMountPath)
                .contains(tuple("task", "/var/task"));
        assertThat(runtime.getEnv())
                .extracting(EnvVar::getName, EnvVar::getValue)
                .contains(tuple("AWS_LAMBDA_RUNTIME_API", "10.0.0.5:9200"));

        assertThat(spec.getVolumes()).extracting(Volume::getName).containsExactly("task");
    }

    @Test
    void standardLabelsAreApplied() {
        var labels = buildZipPod().getMetadata().getLabels();
        assertThat(labels)
                .containsEntry("app.kubernetes.io/managed-by", "floci")
                .containsEntry("floci.io/service", "lambda")
                .containsEntry("floci.io/function-name", "my-fn");
    }

    @Test
    void userLabelsAreParsedAndApplied() {
        when(kubernetes.labels()).thenReturn(Optional.of(
                List.of("team=platform", "floci.io/env=ci", "empty-ok=")));
        var labels = buildZipPod().getMetadata().getLabels();
        assertThat(labels)
                .containsEntry("team", "platform")
                .containsEntry("floci.io/env", "ci")
                .containsEntry("empty-ok", "");
    }

    @Test
    void invalidUserLabelsAreDroppedNotSanitized() {
        // '=' in a value and spaces in a key are rejected by the API server, so
        // such entries must never reach the pod spec.
        when(kubernetes.labels()).thenReturn(Optional.of(
                List.of("malformed", "a=b=c", "bad key=x", "team=platform")));
        var labels = buildZipPod().getMetadata().getLabels();
        assertThat(labels)
                .containsEntry("team", "platform")
                .doesNotContainKeys("malformed", "a", "bad key");
    }

    @Test
    void layersAreDownloadedInOrderIntoOpt() {
        var pod = factory.buildPod("floci-lambda-my-fn-abc12345", "my-fn",
                "public.ecr.aws/lambda/nodejs:20",
                List.of(),
                "http://10.0.0.5:4566/awslambda-us-east-1-tasks/snapshots/000000000000/my-fn",
                List.of("http://10.0.0.5:4566/b/layers/1", "http://10.0.0.5:4566/b/layers/2"),
                false, "index.handler", null, 128, Optional.empty());

        var spec = pod.getSpec();
        assertThat(spec.getVolumes()).extracting(Volume::getName).contains("opt");
        assertThat(spec.getContainers().getFirst().getVolumeMounts())
                .extracting(VolumeMount::getName, VolumeMount::getMountPath)
                .contains(tuple("opt", "/opt"));
        assertThat(spec.getInitContainers().getFirst().getVolumeMounts())
                .extracting(VolumeMount::getName, VolumeMount::getMountPath)
                .contains(tuple("opt", "/opt"));

        assertThat(spec.getInitContainers().getFirst().getCommand().get(2))
                .contains("unzip -oq /tmp/layer0.zip -d /opt")
                .containsSubsequence(
                        "wget -q -O /tmp/layer0.zip 'http://10.0.0.5:4566/b/layers/1'",
                        "wget -q -O /tmp/layer1.zip 'http://10.0.0.5:4566/b/layers/2'");
    }

    @Test
    void providedRuntimeCopiesBootstrapIntoVarRuntime() {
        // The provided.* entrypoint execs /var/runtime/bootstrap with no /var/task
        // fallback, so the init container must copy it there and restore the exec bit.
        var pod = factory.buildPod("floci-lambda-custom-abc12345", "custom",
                "public.ecr.aws/lambda/provided:al2023",
                List.of(),
                "http://10.0.0.5:4566/awslambda-us-east-1-tasks/snapshots/000000000000/custom",
                List.of(), true, "bootstrap", null, 128, Optional.empty());

        var spec = pod.getSpec();
        assertThat(spec.getVolumes()).extracting(Volume::getName).contains("runtime");
        assertThat(spec.getContainers().getFirst().getVolumeMounts())
                .extracting(VolumeMount::getName, VolumeMount::getMountPath)
                .contains(tuple("runtime", "/var/runtime"));
        assertThat(spec.getInitContainers().getFirst().getVolumeMounts())
                .extracting(VolumeMount::getName)
                .contains("runtime");
        assertThat(spec.getInitContainers().getFirst().getCommand().get(2))
                .contains("cp /var/task/bootstrap /var/runtime/bootstrap")
                .contains("chmod +x /var/runtime/bootstrap");
    }

    @Test
    void nonProvidedRuntimeDoesNotMaskVarRuntime() {
        // Masking /var/runtime on a normal runtime would delete the runtime interface client.
        assertThat(buildZipPod().getSpec().getVolumes())
                .extracting(Volume::getName)
                .doesNotContain("runtime");
    }

    @Test
    void managedLabelsCannotBeOverriddenByUserLabels() {
        when(kubernetes.labels()).thenReturn(Optional.of(
                List.of("app.kubernetes.io/managed-by=evil", "floci.io/service=other")));
        var labels = buildZipPod().getMetadata().getLabels();
        assertThat(labels)
                .containsEntry("app.kubernetes.io/managed-by", "floci")
                .containsEntry("floci.io/service", "lambda");
    }

    @Test
    void dollarSignsInEnvValuesAreEscapedForKubernetesExpansion() {
        var pod = factory.buildPod("floci-lambda-my-fn-abc12345", "my-fn",
                "public.ecr.aws/lambda/python:3.12",
                List.of("SECRET=pa$$word", "TEMPLATE=$(HOME)/x"),
                "http://10.0.0.5:4566/b/k", List.of(), false, "h", null, 128, Optional.empty());
        assertThat(pod.getSpec().getContainers().getFirst().getEnv())
                .extracting(EnvVar::getName, EnvVar::getValue)
                .contains(
                        tuple("SECRET", "pa$$$$word"),
                        tuple("TEMPLATE", "$$(HOME)/x"));
    }

    @Test
    void tlsMountsCaCertAndStillDownloadsWithPlainWget() {
        // Downloads stay plain HTTP even in TLS mode (busybox wget cannot TLS-handshake
        // with Floci); the CA mount is for SDK calls made from inside the function.
        when(tls.enabled()).thenReturn(true);
        var pod = factory.buildPod("floci-lambda-my-fn-abc12345", "my-fn",
                "public.ecr.aws/lambda/python:3.12",
                List.of(),
                "http://10.0.0.5:4566/awslambda-us-east-1-tasks/snapshots/000000000000/my-fn",
                List.of(), false, "index.handler", null, 128,
                Optional.of(KubernetesPodLauncher.CA_CONFIG_MAP_NAME));

        var spec = pod.getSpec();
        assertThat(spec.getVolumes()).anySatisfy(volume -> {
            assertThat(volume.getName()).isEqualTo("floci-ca");
            assertThat(volume.getConfigMap().getName()).isEqualTo(KubernetesPodLauncher.CA_CONFIG_MAP_NAME);
        });
        assertThat(spec.getContainers().getFirst().getVolumeMounts()).anySatisfy(volumeMount -> {
            assertThat(volumeMount.getName()).isEqualTo("floci-ca");
            assertThat(volumeMount.getMountPath()).isEqualTo("/etc/floci-ca.crt");
            assertThat(volumeMount.getSubPath()).isEqualTo(KubernetesPodLauncher.CA_CONFIG_MAP_KEY);
        });
        assertThat(spec.getInitContainers().getFirst().getCommand().get(2))
                .contains("wget -q")
                .doesNotContain("--no-check-certificate");
    }

    @Test
    void dollarSignsInCommandAndArgsAreEscapedForKubernetesExpansion() {
        var pod = factory.buildPod("floci-lambda-img-abc12345", "img",
                "123456789012.dkr.ecr.us-east-1.amazonaws.com/my-image:latest",
                List.of(), null, List.of(), false, null,
                new LambdaPodSpecFactory.ImageConfig(
                        List.of("/entry.sh"), List.of("run('$(AWS_REGION)')", "pa$$word"), "/work"),
                512, Optional.empty());
        assertThat(pod.getSpec().getContainers().getFirst().getArgs())
                .containsExactly("run('$$(AWS_REGION)')", "pa$$$$word");
    }

    @Test
    void imagePackageTypeHasNoInitContainerAndMapsImageConfig() {
        var pod = factory.buildPod("floci-lambda-img-abc12345", "img",
                "123456789012.dkr.ecr.us-east-1.amazonaws.com/my-image:latest",
                List.of(), null, List.of(), false, null,
                new LambdaPodSpecFactory.ImageConfig(
                        List.of("/entry.sh"), List.of("arg1", "arg2"), "/work"),
                512, Optional.empty());

        var spec = pod.getSpec();
        assertThat(spec.getInitContainers()).isEmpty();
        assertThat(spec.getVolumes()).isEmpty();

        var runtime = spec.getContainers().getFirst();
        assertThat(runtime.getCommand()).containsExactly("/entry.sh");
        assertThat(runtime.getArgs()).containsExactly("arg1", "arg2");
        assertThat(runtime.getWorkingDir()).isEqualTo("/work");
    }

    @Test
    void envEntriesSplitOnFirstEquals() {
        var pod = factory.buildPod("floci-lambda-my-fn-abc12345", "my-fn",
                "public.ecr.aws/lambda/python:3.12",
                List.of("KEY=a=b", "EMPTY="),
                "http://10.0.0.5:4566/b/k", List.of(), false, "h", null, 128, Optional.empty());
        assertThat(pod.getSpec().getContainers().getFirst().getEnv())
                .extracting(EnvVar::getName, EnvVar::getValue)
                .contains(
                        tuple("KEY", "a=b"),
                        tuple("EMPTY", ""));
    }

    @Test
    void envEntriesWithEmptyNamesAreDropped() {
        // The API server rejects a pod whose env var has an empty name.
        var pod = factory.buildPod("floci-lambda-my-fn-abc12345", "my-fn",
                "public.ecr.aws/lambda/python:3.12",
                List.of("=oops", "KEY=1"),
                "http://10.0.0.5:4566/b/k", List.of(), false, "h", null, 128, Optional.empty());
        assertThat(pod.getSpec().getContainers().getFirst().getEnv())
                .extracting(EnvVar::getName)
                .containsExactly("KEY");
    }

    @Test
    void podNameIsDnsSafeAndClamped() {
        var name = LambdaPodSpecFactory.podName("My_Function.With.Dots", "abc12345");
        assertThat(name).isEqualTo("floci-lambda-my-function-with-dots-abc12345");
        assertThat(name).hasSizeLessThanOrEqualTo(63);

        var longName = LambdaPodSpecFactory.podName("a".repeat(100), "abc12345");
        assertThat(longName)
                .hasSizeLessThanOrEqualTo(63)
                .endsWith("-abc12345")
                .matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?");

        assertThat(LambdaPodSpecFactory.podName("_foo", "abc12345"))
                .isEqualTo("floci-lambda-foo-abc12345");
        assertThat(LambdaPodSpecFactory.podName("___", "abc12345"))
                .isEqualTo("floci-lambda-fn-abc12345");
    }

    @Test
    void labelValueSanitization() {
        assertThat(LambdaPodSpecFactory.sanitizeLabelValue("fn:name")).isEqualTo("fn-name");
        assertThat(LambdaPodSpecFactory.sanitizeLabelValue("-fn-")).isEqualTo("fn");
        assertThat(LambdaPodSpecFactory.sanitizeLabelValue("x".repeat(100))).hasSizeLessThanOrEqualTo(63);
    }
}
