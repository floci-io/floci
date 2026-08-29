package io.github.hectorvent.floci.services.mwaa;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.services.mwaa.model.Environment;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.model.Mount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MwaaEnvironmentManagerTest {

    private ContainerLifecycleManager lifecycleManager;
    private ContainerDetector containerDetector;
    private RegionResolver regionResolver;
    private MwaaEnvironmentManager manager;

    @BeforeEach
    void setUp() {
        EmulatorConfig.DockerConfig dockerConfig = Mockito.mock(EmulatorConfig.DockerConfig.class);
        when(dockerConfig.imageRegistryBase()).thenReturn(Optional.empty());
        when(dockerConfig.logMaxSize()).thenReturn("10m");
        when(dockerConfig.logMaxFile()).thenReturn("3");

        EmulatorConfig.MwaaServiceConfig mwaaConfig = Mockito.mock(EmulatorConfig.MwaaServiceConfig.class);
        when(mwaaConfig.dockerNetwork()).thenReturn(Optional.empty());
        when(mwaaConfig.defaultPostgresImage()).thenReturn("postgres:16-alpine");

        EmulatorConfig.ServicesConfig servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        when(servicesConfig.mwaa()).thenReturn(mwaaConfig);
        when(servicesConfig.dockerNetwork()).thenReturn(Optional.empty());

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        when(config.docker()).thenReturn(dockerConfig);
        when(config.services()).thenReturn(servicesConfig);
        when(config.defaultRegion()).thenReturn("us-east-1");

        DockerHostResolver dockerHostResolver = Mockito.mock(DockerHostResolver.class);
        EmbeddedDnsServer embeddedDnsServer = Mockito.mock(EmbeddedDnsServer.class);
        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.empty());
        ContainerBuilder containerBuilder = new ContainerBuilder(config, dockerHostResolver, embeddedDnsServer);

        lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
        containerDetector = Mockito.mock(ContainerDetector.class);

        LaunchedContainerAwsEnv awsEnv = Mockito.mock(LaunchedContainerAwsEnv.class);
        when(awsEnv.sdkBaselineEnv(eq("us-east-1"), any())).thenReturn(List.of(
                "AWS_DEFAULT_REGION=us-east-1",
                "AWS_ACCESS_KEY_ID=test",
                "AWS_SECRET_ACCESS_KEY=test",
                "AWS_ENDPOINT_URL=http://localhost:4566"));

        regionResolver = Mockito.mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");

        manager = new MwaaEnvironmentManager(containerBuilder, lifecycleManager, containerDetector, config,
                awsEnv, regionResolver);
    }

    @SuppressWarnings("unchecked")
    private ContainerSpec startAirflowAndCaptureSpec(boolean runningInContainer) {
        return startAirflowAndCaptureSpec(runningInContainer, null);
    }

    @SuppressWarnings("unchecked")
    private ContainerSpec startAirflowAndCaptureSpec(boolean runningInContainer, byte[] startupScriptContent) {
        when(containerDetector.isRunningInContainer()).thenReturn(runningInContainer);
        when(lifecycleManager.create(any())).thenReturn("airflow-container-id");
        ContainerInfo info = new ContainerInfo("airflow-container-id",
                Map.of(8080, new EndpointInfo("172.18.0.5", 8080)));
        when(lifecycleManager.startCreated(eq("airflow-container-id"), any())).thenReturn(info);

        Environment environment = new Environment();
        environment.setName("my-env");

        manager.startAirflowContainer(environment, "2.10.5", "172.18.0.9", "db-secret-pw", startupScriptContent);

        ArgumentCaptor<ContainerSpec> captor = ArgumentCaptor.forClass(ContainerSpec.class);
        Mockito.verify(lifecycleManager).create(captor.capture());
        Mockito.verify(lifecycleManager).startCreated(eq("airflow-container-id"), any());

        assertEquals("airflow-container-id", environment.getAirflowContainerId());
        assertEquals("172.18.0.5", environment.getAirflowInternalHost());
        assertEquals(8080, environment.getAirflowInternalPort());
        return captor.getValue();
    }

    @Test
    void startAirflowContainerLabelsContainerWithResourceIdentity() {
        ContainerSpec spec = startAirflowAndCaptureSpec(false);

        assertEquals(Map.of(
                "io.floci", "aws",
                "io.floci.service", "mwaa",
                "io.floci.resource-id", "my-env",
                "io.floci.account", "000000000000",
                "io.floci.region", "us-east-1"),
                spec.labels());
    }

    @Test
    void airflowImageTagSubstitutesTheRequestedVersion() {
        ContainerSpec spec = startAirflowAndCaptureSpec(false);
        assertEquals("apache/airflow:2.10.5-python3.12", spec.image());
    }

    @Test
    void airflowEnvVarsWireTheResolvedPostgresDsn() {
        ContainerSpec spec = startAirflowAndCaptureSpec(false);

        assertTrue(spec.env().contains("AIRFLOW__CORE__EXECUTOR=LocalExecutor"));
        assertTrue(spec.env().contains("AIRFLOW__CORE__LOAD_EXAMPLES=false"));
        assertTrue(spec.env().stream().anyMatch(e -> e.startsWith("AIRFLOW__DATABASE__SQL_ALCHEMY_CONN=")
                && e.contains("172.18.0.9") && e.contains("db-secret-pw")));
        assertTrue(spec.env().stream().anyMatch(e -> e.startsWith("AIRFLOW__CORE__FERNET_KEY=")));
        assertTrue(spec.env().stream().anyMatch(e -> e.startsWith("AIRFLOW__WEBSERVER__SECRET_KEY=")));
        assertTrue(spec.env().stream().anyMatch(e -> e.startsWith("_AIRFLOW_WWW_USER_USERNAME=")));
        assertTrue(spec.env().stream().anyMatch(e -> e.startsWith("_AIRFLOW_WWW_USER_PASSWORD=")));
    }

    @Test
    void airflowContainerGetsPointedBackAtFlociForItsOwnAwsSdkCalls() {
        ContainerSpec spec = startAirflowAndCaptureSpec(false);

        assertTrue(spec.env().contains("AWS_ENDPOINT_URL=http://localhost:4566"),
                "DAG code's own boto3 calls must target Floci, not real AWS");
        assertTrue(spec.env().contains("AWS_DEFAULT_REGION=us-east-1"));
    }

    @Test
    void entrypointAndCmdOverrideTheStockImageDefaults() {
        ContainerSpec spec = startAirflowAndCaptureSpec(false);

        assertEquals(List.of("sh", "-c"), spec.entrypoint());
        assertEquals(1, spec.cmd().size());
        String script = spec.cmd().get(0);
        assertTrue(script.contains("airflow db migrate"));
        assertTrue(script.contains("airflow users create"));
        assertTrue(script.contains("airflow scheduler &"));
        assertTrue(script.contains("exec airflow webserver"));
        // migrate/user-create must precede the background scheduler, not run inside its background chain.
        assertTrue(script.indexOf("airflow db migrate") < script.indexOf("airflow scheduler &"));
    }

    @Test
    void bootstrapScriptSourcesAnOptionalStartupScriptBeforeMigrating() {
        ContainerSpec spec = startAirflowAndCaptureSpec(false);
        String script = spec.cmd().get(0);

        assertTrue(script.contains("if [ -f /startup.sh ]; then . /startup.sh || exit 1; fi"));
        assertTrue(script.indexOf("/startup.sh") < script.indexOf("airflow db migrate"),
                "the startup script must run before migration, matching real MWAA's ordering");
    }

    // Coverage for supplying startup-script bytes now lives in the ContainerFileInjection nested
    // class below, where the DockerClient is actually mocked — the copy must succeed for the
    // create -> inject -> start sequence to continue (see startupScriptInjectionFailurePreventsThe-
    // ContainerFromStarting for the failure path this class didn't previously test).

    @Test
    void namedVolumesMountDagsAndLogs() {
        ContainerSpec spec = startAirflowAndCaptureSpec(false);

        List<String> targets = spec.mounts().stream().map(Mount::getTarget).toList();
        assertTrue(targets.contains("/opt/airflow/dags"));
        assertTrue(targets.contains("/opt/airflow/logs"));
        assertTrue(spec.mounts().stream().map(Mount::getSource).anyMatch(s -> s.endsWith("-dags")));
        assertTrue(spec.mounts().stream().map(Mount::getSource).anyMatch(s -> s.endsWith("-logs")));
    }

    @Test
    void nativeModeAllocatesADynamicHostPortForTheWebserver() {
        ContainerSpec spec = startAirflowAndCaptureSpec(false);

        assertTrue(spec.exposedPorts().contains(8080));
        assertTrue(spec.portBindings().containsKey(8080));
        assertEquals(0, spec.portBindings().get(8080), "0 is the dynamic-allocation sentinel");
    }

    @Test
    void containerModePublishesNoHostPortForTheWebserver() {
        ContainerSpec spec = startAirflowAndCaptureSpec(true);

        assertTrue(spec.exposedPorts().contains(8080));
        assertFalse(spec.portBindings().containsKey(8080));
    }

    @Test
    void containerNamingMatchesThePlan() {
        assertEquals("floci-mwaa-my-env-db", MwaaEnvironmentManager.dbContainerName(null, "my-env"));
        assertEquals("floci-mwaa-my-env-airflow", MwaaEnvironmentManager.airflowContainerName(null, "my-env"));
    }

    @Test
    void containerNamingAppliesTheConfiguredResourceNamespace() {
        EmulatorConfig.DockerConfig dockerConfig = Mockito.mock(EmulatorConfig.DockerConfig.class);
        when(dockerConfig.resourceNamespace()).thenReturn(Optional.of("ns1"));
        EmulatorConfig namespacedConfig = Mockito.mock(EmulatorConfig.class);
        when(namespacedConfig.docker()).thenReturn(dockerConfig);

        assertEquals("floci-ns1-mwaa-my-env-db",
                MwaaEnvironmentManager.dbContainerName(namespacedConfig, "my-env"));
        assertEquals("floci-ns1-mwaa-my-env-airflow",
                MwaaEnvironmentManager.airflowContainerName(namespacedConfig, "my-env"));
    }

    @Test
    void healthCheckRequiresBothMetadatabaseAndSchedulerHealthy() {
        String bothHealthy = "{\"metadatabase\":{\"status\":\"healthy\"},\"scheduler\":{\"status\":\"healthy\"}}";
        assertTrue(MwaaEnvironmentManager.healthySection(bothHealthy, "metadatabase"));
        assertTrue(MwaaEnvironmentManager.healthySection(bothHealthy, "scheduler"));

        String schedulerUnhealthy = "{\"metadatabase\":{\"status\":\"healthy\"},\"scheduler\":{\"status\":\"unhealthy\"}}";
        assertTrue(MwaaEnvironmentManager.healthySection(schedulerUnhealthy, "metadatabase"));
        assertFalse(MwaaEnvironmentManager.healthySection(schedulerUnhealthy, "scheduler"));
    }

    @Test
    void bootstrapScriptSnapshotsAndRestoresProtectedVarsAroundTheStartupScript() {
        String script = MwaaEnvironmentManager.airflowBootstrapScript();

        int snapshotIdx = script.indexOf("_FLOCI_ORIG_AIRFLOW__CORE__FERNET_KEY=\"$AIRFLOW__CORE__FERNET_KEY\"");
        int startupIdx = script.indexOf("/startup.sh");
        int restoreIdx = script.indexOf("export AIRFLOW__CORE__FERNET_KEY=\"$_FLOCI_ORIG_AIRFLOW__CORE__FERNET_KEY\"");

        assertTrue(snapshotIdx >= 0, "must snapshot the original value before the startup script can touch it");
        assertTrue(restoreIdx >= 0, "must re-export the snapshotted value after the startup script runs");
        assertTrue(snapshotIdx < startupIdx && startupIdx < restoreIdx,
                "ordering must be snapshot -> startup script -> restore, so an override can't survive");

        // Spot-check a Floci-specific (non-AWS-reserved) var too, since a script accidentally
        // clobbering AWS_ENDPOINT_URL would break the AWS-SDK-redirection this environment depends on.
        assertTrue(script.contains("_FLOCI_ORIG_AWS_ENDPOINT_URL=\"$AWS_ENDPOINT_URL\""));
        assertTrue(script.contains("export AWS_ENDPOINT_URL=\"$_FLOCI_ORIG_AWS_ENDPOINT_URL\""));
    }

    /** Sudoers-grant and startup-script injection, verified against a mocked DockerClient — mirrors
     *  EksClusterManagerTest's InjectEcrRegistryMirror nested class for the same kind of assertion. */
    @Nested
    class ContainerFileInjection {

        private DockerClient dockerClient;
        private CopyArchiveToContainerCmd copyCmd;

        @BeforeEach
        void setUpDockerClient() {
            dockerClient = Mockito.mock(DockerClient.class);
            copyCmd = Mockito.mock(CopyArchiveToContainerCmd.class, Mockito.RETURNS_SELF);
            when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
            when(dockerClient.copyArchiveToContainerCmd(anyString())).thenReturn(copyCmd);
        }

        @Test
        void sudoersGrantIsAlwaysInjectedEvenWithoutAStartupScript() {
            startAirflowAndCaptureSpec(false, null);

            verify(copyCmd).withRemotePath("/etc/sudoers.d");
            verify(copyCmd, times(1)).exec();
        }

        @Test
        void startupScriptIsInjectedInAdditionToTheSudoersGrantWhenProvided() {
            startAirflowAndCaptureSpec(false, "export FOO=bar\n".getBytes());

            verify(copyCmd).withRemotePath("/etc/sudoers.d");
            verify(copyCmd).withRemotePath("/");
            verify(copyCmd, times(2)).exec();
        }

        @Test
        void startupScriptInjectionFailurePreventsTheContainerFromStarting() {
            // Shared copyCmd mock, so this also makes the (best-effort) sudoers-grant copy fail —
            // that alone must not abort anything; only the configured-startup-script failure should.
            when(copyCmd.exec()).thenThrow(new RuntimeException("docker cp failed"));
            when(lifecycleManager.create(any())).thenReturn("airflow-container-id");

            Environment environment = new Environment();
            environment.setName("my-env");

            assertThrows(IllegalStateException.class, () -> manager.startAirflowContainer(
                    environment, "2.10.5", "172.18.0.9", "db-secret-pw", "export FOO=bar\n".getBytes()));

            verify(lifecycleManager).removeIfExists("airflow-container-id");
            verify(lifecycleManager, never()).startCreated(anyString(), any());
        }
    }
}
