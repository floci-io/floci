package io.floci.aws.services.mwaa;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.NetworkSettings;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.ContainerExecStubs;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.common.docker.LaunchedContainerAwsEnv;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.mwaa.model.Environment;
import io.floci.aws.services.mwaa.model.NetworkConfiguration;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ResourceLock(ParallelLocks.PORTS)
class MwaaEnvironmentManagerTest {

    private ContainerLifecycleManager lifecycleManager;
    private ContainerDetector containerDetector;
    private MwaaEnvironmentManager manager;
    private EmulatorConfig.MwaaServiceConfig mwaaConfig;
    private Ec2Service ec2Service;
    private EmulatorConfig config;

    @BeforeEach
    void setUp() {
        EmulatorConfig.DockerConfig dockerConfig = Mockito.mock(EmulatorConfig.DockerConfig.class);
        when(dockerConfig.imageRegistryBase()).thenReturn(Optional.empty());
        when(dockerConfig.logMaxSize()).thenReturn("10m");
        when(dockerConfig.logMaxFile()).thenReturn("3");

        mwaaConfig = Mockito.mock(EmulatorConfig.MwaaServiceConfig.class);
        when(mwaaConfig.dockerNetwork()).thenReturn(Optional.empty());
        when(mwaaConfig.defaultPostgresImage()).thenReturn("postgres:16-alpine");

        EmulatorConfig.ServicesConfig servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        when(servicesConfig.mwaa()).thenReturn(mwaaConfig);
        when(servicesConfig.dockerNetwork()).thenReturn(Optional.empty());

        config = Mockito.mock(EmulatorConfig.class);
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
        when(awsEnv.sdkBaselineEnv(eq("eu-west-1"), any())).thenReturn(List.of(
                "AWS_DEFAULT_REGION=eu-west-1",
                "AWS_ACCESS_KEY_ID=test",
                "AWS_SECRET_ACCESS_KEY=test",
                "AWS_ENDPOINT_URL=http://localhost:4566"));

        ec2Service = Mockito.mock(Ec2Service.class);
        Mockito.doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(ec2Service).runWithSubnetReferencesHeld(any(), any());
        manager = new MwaaEnvironmentManager(containerBuilder, lifecycleManager, containerDetector, config,
                new RegionResolver("us-east-1", "000000000000"), awsEnv, ec2Service);
    }

    /** Docker answers an inspect of the container: present, or gone once it has been removed. */
    private InspectContainerCmd dockerInspecting(String container, boolean present) {
        DockerClient dockerClient = lifecycleManager.getDockerClient() != null ? lifecycleManager.getDockerClient()
                : Mockito.mock(DockerClient.class);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        InspectContainerCmd inspect = Mockito.mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd(container)).thenReturn(inspect);
        if (present) {
            when(inspect.exec()).thenReturn(Mockito.mock(InspectContainerResponse.class));
        } else {
            when(inspect.exec()).thenThrow(new NotFoundException("No such container: " + container));
        }
        return inspect;
    }

    private Environment environmentInSubnets(String... subnetIds) {
        NetworkConfiguration network = new NetworkConfiguration();
        network.setSubnetIds(List.of(subnetIds));
        network.setSecurityGroupIds(List.of("sg-1"));
        Environment environment = new Environment();
        environment.setName("vpc-env");
        environment.setAccountId("000000000000");
        environment.setNetworkConfiguration(network);
        return environment;
    }

    private void subnetInVpc(String subnetId, String vpcId) {
        Subnet subnet = new Subnet();
        subnet.setSubnetId(subnetId);
        subnet.setVpcId(vpcId);
        subnet.setCidrBlock("172.31.0.0/20");
        when(ec2Service.describeSubnets("us-east-1", List.of(subnetId), Map.of())).thenReturn(List.of(subnet));
    }

    private void airflowContainerStarts() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(lifecycleManager.create(any())).thenReturn("airflow-container-id");
        when(lifecycleManager.startCreated(eq("airflow-container-id"), any())).thenReturn(new ContainerInfo(
                "airflow-container-id", Map.of(8080, new EndpointInfo("172.18.0.5", 8080))));
    }

    @Test
    void anEnvironmentInAVpcJoinsItsNetworkBeforeAirflowStartsAndGivesTheAddressBackOnDelete() {
        airflowContainerStarts();
        subnetInVpc("subnet-a", "vpc-1");
        when(ec2Service.allocateVpcPrivateIp("000000000000", "us-east-1", "subnet-a")).thenReturn(Optional.of("172.31.0.20"));
        when(ec2Service.attachContainerToVpc("000000000000", "us-east-1", "vpc-1", "subnet-a", "airflow-container-id", "172.31.0.20"))
                .thenReturn(Optional.of("floci-vpc-1"));
        Environment environment = environmentInSubnets("subnet-a", "subnet-b");

        manager.startAirflowContainer(environment, "2.10.5", "172.18.0.9", "db-secret-pw", null, null);
        verify(ec2Service, never()).releaseVpcPrivateIp(any(), any(), any(), any());
        assertTrue(manager.holdsSubnet("000000000000", "us-east-1", "subnet-a"));
        assertTrue(manager.holdsVpc("000000000000", "us-east-1", "vpc-1"));
        assertFalse(manager.holdsSubnet("111111111111", "us-east-1", "subnet-a"));
        dockerInspecting("airflow-container-id", false);
        manager.stopEnvironment(environment);
        assertFalse(manager.holdsSubnet("000000000000", "us-east-1", "subnet-a"));

        InOrder order = Mockito.inOrder(lifecycleManager, ec2Service);
        order.verify(lifecycleManager).create(any());
        order.verify(ec2Service).attachContainerToVpc("000000000000", "us-east-1", "vpc-1", "subnet-a", "airflow-container-id",
                "172.31.0.20");
        order.verify(lifecycleManager).startCreated(eq("airflow-container-id"), any());
        order.verify(lifecycleManager).stopAndRemove("airflow-container-id", null);
        order.verify(ec2Service).releaseVpcPrivateIp("000000000000", "us-east-1", "subnet-a", "172.31.0.20");
    }

    @Test
    void anAddressTheVpcNetworkRefusesIsGivenBackAndAirflowStillStarts() {
        airflowContainerStarts();
        subnetInVpc("subnet-a", "vpc-1");
        when(ec2Service.allocateVpcPrivateIp("000000000000", "us-east-1", "subnet-a")).thenReturn(Optional.of("172.31.0.21"));
        when(ec2Service.attachContainerToVpc("000000000000", "us-east-1", "vpc-1", "subnet-a", "airflow-container-id", "172.31.0.21"))
                .thenReturn(Optional.empty());

        manager.startAirflowContainer(environmentInSubnets("subnet-a"), "2.10.5", "172.18.0.9", "db-secret-pw",
                null, null);

        verify(ec2Service).releaseVpcPrivateIp("000000000000", "us-east-1", "subnet-a", "172.31.0.21");
        verify(lifecycleManager).startCreated(eq("airflow-container-id"), any());
    }

    @Test
    void aFailedStartGivesTheVpcAddressBackAfterRemovingTheContainer() {
        airflowContainerStarts();
        subnetInVpc("subnet-a", "vpc-1");
        when(ec2Service.allocateVpcPrivateIp("000000000000", "us-east-1", "subnet-a")).thenReturn(Optional.of("172.31.0.22"));
        when(ec2Service.attachContainerToVpc("000000000000", "us-east-1", "vpc-1", "subnet-a", "airflow-container-id", "172.31.0.22"))
                .thenReturn(Optional.of("floci-vpc-1"));
        when(lifecycleManager.startCreated(eq("airflow-container-id"), any()))
                .thenThrow(new RuntimeException("start failed"));
        dockerInspecting("airflow-container-id", false);

        assertThrows(RuntimeException.class, () -> manager.startAirflowContainer(environmentInSubnets("subnet-a"),
                "2.10.5", "172.18.0.9", "db-secret-pw", null, null));

        InOrder order = Mockito.inOrder(lifecycleManager, ec2Service);
        order.verify(lifecycleManager).removeIfExists("airflow-container-id");
        order.verify(ec2Service).releaseVpcPrivateIp("000000000000", "us-east-1", "subnet-a", "172.31.0.22");
    }

    @Test
    void aContainerDockerFailedToRemoveKeepsItsVpcAddress() {
        airflowContainerStarts();
        subnetInVpc("subnet-a", "vpc-1");
        when(ec2Service.allocateVpcPrivateIp("000000000000", "us-east-1", "subnet-a")).thenReturn(Optional.of("172.31.0.24"));
        when(ec2Service.attachContainerToVpc("000000000000", "us-east-1", "vpc-1", "subnet-a", "airflow-container-id", "172.31.0.24"))
                .thenReturn(Optional.of("floci-vpc-1"));
        Environment environment = environmentInSubnets("subnet-a");
        manager.startAirflowContainer(environment, "2.10.5", "172.18.0.9", "db-secret-pw", null, null);
        dockerInspecting("airflow-container-id", true);

        manager.stopEnvironment(environment);

        verify(ec2Service, never()).releaseVpcPrivateIp(any(), any(), any(), any());
        assertTrue(manager.holdsSubnet("000000000000", "us-east-1", "subnet-a"));
    }

    @Test
    void aContainerKeptAcrossARestartReclaimsItsVpcAddress() {
        subnetInVpc("subnet-a", "vpc-1");
        Environment environment = environmentInSubnets("subnet-a");
        environment.setArn("arn:aws:airflow:us-east-1:000000000000:environment/vpc-env");
        DockerClient dockerClient = Mockito.mock(DockerClient.class);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        InspectContainerCmd inspect = Mockito.mock(InspectContainerCmd.class);
        InspectContainerResponse response = Mockito.mock(InspectContainerResponse.class);
        NetworkSettings settings = Mockito.mock(NetworkSettings.class);
        when(dockerClient.inspectContainerCmd(manager.airflowContainerName(environment)))
                .thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);
        when(response.getId()).thenReturn("kept-container-id");
        when(response.getNetworkSettings()).thenReturn(settings);
        when(settings.getNetworks()).thenReturn(Map.of(
                "floci-mwaa", new ContainerNetwork().withIpv4Address("172.18.0.5"),
                "floci-vpc-1", new ContainerNetwork().withIpv4Address("172.31.0.30")));
        when(ec2Service.reserveVpcPrivateIp("000000000000", "us-east-1", "subnet-a", "172.31.0.30")).thenReturn(true);

        manager.reclaimVpcAddress(environment);

        verify(ec2Service).reserveVpcPrivateIp("000000000000", "us-east-1", "subnet-a", "172.31.0.30");
        verify(ec2Service, never()).reserveVpcPrivateIp("000000000000", "us-east-1", "subnet-a", "172.18.0.5");
        assertTrue(manager.holdsSubnet("000000000000", "us-east-1", "subnet-a"));
    }

    @Test
    void aContainerAnOlderFlociNamedReclaimsItsVpcAddressAndADockerThatCannotSayThrows() {
        subnetInVpc("subnet-a", "vpc-1");
        Environment environment = environmentInSubnets("subnet-a");
        environment.setArn("arn:aws:airflow:us-east-1:000000000000:environment/vpc-env");
        DockerClient dockerClient = Mockito.mock(DockerClient.class);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        String current = manager.airflowContainerName(environment);
        String legacy = ContainerStorageHelper.legacyDockerName(config, current);
        InspectContainerCmd missing = Mockito.mock(InspectContainerCmd.class);
        when(missing.exec()).thenThrow(new NotFoundException("No such container: " + current));
        InspectContainerCmd kept = Mockito.mock(InspectContainerCmd.class);
        InspectContainerResponse response = Mockito.mock(InspectContainerResponse.class);
        NetworkSettings settings = Mockito.mock(NetworkSettings.class);
        when(dockerClient.inspectContainerCmd(current)).thenReturn(missing);
        when(dockerClient.inspectContainerCmd(legacy)).thenReturn(kept);
        when(kept.exec()).thenReturn(response);
        when(response.getId()).thenReturn("legacy-container-id");
        when(response.getNetworkSettings()).thenReturn(settings);
        when(settings.getNetworks()).thenReturn(Map.of(
                "floci-vpc-1", new ContainerNetwork().withIpv4Address("172.31.0.31")));
        when(ec2Service.reserveVpcPrivateIp("000000000000", "us-east-1", "subnet-a", "172.31.0.31")).thenReturn(true);

        manager.reclaimVpcAddress(environment);

        verify(ec2Service).reserveVpcPrivateIp("000000000000", "us-east-1", "subnet-a", "172.31.0.31");
        when(dockerClient.inspectContainerCmd(current)).thenThrow(new IllegalStateException("daemon unreachable"));
        assertThrows(IllegalStateException.class, () -> manager.reclaimVpcAddress(environment));
    }

    @Test
    void anEnvironmentOutsideAnyVpcEc2KnowsStaysOnItsOwnNetwork() {
        airflowContainerStarts();
        when(ec2Service.describeSubnets("us-east-1", List.of("subnet-unknown"), Map.of())).thenReturn(List.of());

        manager.startAirflowContainer(environmentInSubnets("subnet-unknown"), "2.10.5", "172.18.0.9",
                "db-secret-pw", null, null);
        Environment withoutNetwork = new Environment();
        withoutNetwork.setName("no-vpc-env");
        manager.startAirflowContainer(withoutNetwork, "2.10.5", "172.18.0.9", "db-secret-pw", null, null);

        verify(ec2Service, never()).allocateVpcPrivateIp(any(), any(), any());
        verify(ec2Service, never()).attachContainerToVpc(any(), any(), any(), any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private ContainerSpec startAirflowAndCaptureSpec(boolean runningInContainer) {
        return startAirflowAndCaptureSpec(runningInContainer, null);
    }

    private ContainerSpec startAirflowAndCaptureSpec(boolean runningInContainer, byte[] startupScriptContent) {
        return startAirflowAndCaptureSpec(runningInContainer, startupScriptContent, null);
    }

    @SuppressWarnings("unchecked")
    private ContainerSpec startAirflowAndCaptureSpec(boolean runningInContainer, byte[] startupScriptContent,
                                                     byte[] requirementsContent) {
        when(containerDetector.isRunningInContainer()).thenReturn(runningInContainer);
        when(lifecycleManager.create(any())).thenReturn("airflow-container-id");
        ContainerInfo info = new ContainerInfo("airflow-container-id",
                Map.of(8080, new EndpointInfo("172.18.0.5", 8080)));
        when(lifecycleManager.startCreated(eq("airflow-container-id"), any())).thenReturn(info);

        Environment environment = new Environment();
        environment.setName("my-env");

        manager.startAirflowContainer(environment, "2.10.5", "172.18.0.9", "db-secret-pw", startupScriptContent,
                requirementsContent);

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
        // Real Amazon MWAA runs 2.10.x on Python 3.11, not 3.12.
        assertEquals("apache/airflow:2.10.5-python3.11", spec.image());
    }

    @Test
    void pythonTagForMatchesRealMwaasAirflowPythonPairing() {
        // Every version through 2.10.x runs Python 3.11 on real Amazon MWAA.
        assertEquals("python3.11", MwaaEnvironmentManager.pythonTagFor("2.7.2"));
        assertEquals("python3.11", MwaaEnvironmentManager.pythonTagFor("2.8.4"));
        assertEquals("python3.11", MwaaEnvironmentManager.pythonTagFor("2.9.3"));
        assertEquals("python3.11", MwaaEnvironmentManager.pythonTagFor("2.10.5"));
        // 2.11.0 onward, and every 3.x release, runs Python 3.12.
        assertEquals("python3.12", MwaaEnvironmentManager.pythonTagFor("2.11.0"));
        assertEquals("python3.12", MwaaEnvironmentManager.pythonTagFor("2.11.2"));
        assertEquals("python3.12", MwaaEnvironmentManager.pythonTagFor("3.0.6"));
    }

    @Test
    void airflowConfigurationOptionsEnvTranslatesSectionDotKeyToAirflowDoubleUnderscoreFormat() {
        assertEquals(List.of("AIRFLOW__CORE__DAGS_ARE_PAUSED_AT_CREATION=false"),
                MwaaEnvironmentManager.airflowConfigurationOptionsEnv(
                        Map.of("core.dags_are_paused_at_creation", "false")));
    }

    @Test
    void airflowConfigurationOptionsEnvSkipsAKeyMissingTheSectionDotKeySeparator() {
        assertEquals(List.of(), MwaaEnvironmentManager.airflowConfigurationOptionsEnv(
                Map.of("not_dotted", "value")));
    }

    @Test
    void airflowConfigurationOptionsEnvSkipsAnEmptySectionOrKey() {
        assertEquals(List.of(), MwaaEnvironmentManager.airflowConfigurationOptionsEnv(
                Map.of(".key", "value", "section.", "value")));
    }

    @Test
    void airflowConfigurationOptionsEnvSkipsAnEntryThatWouldOverrideFlociOwnedWiring() {
        // core.executor -> AIRFLOW__CORE__EXECUTOR, one of Floci's own mandatory (LocalExecutor)
        // vars. A configuration option request doesn't get to override that.
        assertEquals(List.of(), MwaaEnvironmentManager.airflowConfigurationOptionsEnv(
                Map.of("core.executor", "CeleryExecutor")));
    }

    @Test
    void airflowConfigurationOptionsEnvHandlesANullOrEmptyMap() {
        assertEquals(List.of(), MwaaEnvironmentManager.airflowConfigurationOptionsEnv(null));
        assertEquals(List.of(), MwaaEnvironmentManager.airflowConfigurationOptionsEnv(Map.of()));
    }

    @Test
    void startAirflowContainerAppliesConfiguredAirflowConfigurationOptions() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(lifecycleManager.create(any())).thenReturn("airflow-container-id");
        when(lifecycleManager.startCreated(eq("airflow-container-id"), any())).thenReturn(
                new ContainerInfo("airflow-container-id", Map.of(8080, new EndpointInfo("172.18.0.5", 8080))));

        Environment environment = new Environment();
        environment.setName("my-env");
        environment.setAirflowConfigurationOptions(Map.of(
                "core.dags_are_paused_at_creation", "false",
                "core.executor", "CeleryExecutor"));

        manager.startAirflowContainer(environment, "2.10.5", "172.18.0.9", "db-secret-pw", null, null);

        ArgumentCaptor<ContainerSpec> captor = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).create(captor.capture());
        assertTrue(captor.getValue().env().contains("AIRFLOW__CORE__DAGS_ARE_PAUSED_AT_CREATION=false"));
        // The colliding entry must not have replaced Floci's own required executor setting.
        assertTrue(captor.getValue().env().contains("AIRFLOW__CORE__EXECUTOR=LocalExecutor"));
        assertFalse(captor.getValue().env().contains("AIRFLOW__CORE__EXECUTOR=CeleryExecutor"));
    }

    @Test
    void theGeneratedAdminPasswordIsKeptOnTheEnvironmentForWebLogins() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(lifecycleManager.create(any())).thenReturn("airflow-container-id");
        when(lifecycleManager.startCreated(eq("airflow-container-id"), any())).thenReturn(new ContainerInfo(
                "airflow-container-id", Map.of(8080, new EndpointInfo("172.18.0.5", 8080))));
        Environment environment = new Environment();
        environment.setName("my-env");

        manager.startAirflowContainer(environment, "2.10.5", "172.18.0.9", "db-secret-pw", null, null);

        ArgumentCaptor<ContainerSpec> captor = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).create(captor.capture());
        assertNotNull(environment.getAirflowAdminPassword());
        assertTrue(captor.getValue().env().contains("_AIRFLOW_WWW_USER_PASSWORD=" + environment.getAirflowAdminPassword()));
        assertTrue(captor.getValue().env().contains("_AIRFLOW_WWW_USER_USERNAME=admin"));
        assertTrue(captor.getValue().env().contains("AIRFLOW__FAB__AUTH_RATE_LIMITED=False"));
    }

    @Test
    void theSignInRateLimitIsOffInTheSectionEachVersionReadsUnlessAnOptionSetsIt() {
        assertEquals(List.of("AIRFLOW__WEBSERVER__AUTH_RATE_LIMITED=False"),
                MwaaEnvironmentManager.signInRateLimitEnv("2.8.4", null));
        assertEquals(List.of("AIRFLOW__FAB__AUTH_RATE_LIMITED=False"),
                MwaaEnvironmentManager.signInRateLimitEnv("2.9.2", Map.of()));
        assertEquals(List.of(), MwaaEnvironmentManager.signInRateLimitEnv("2.10.5",
                Map.of("fab.auth_rate_limited", "True")));
        assertEquals(List.of(), MwaaEnvironmentManager.signInRateLimitEnv("2.10.5",
                Map.of("webserver.auth_rate_limited", "True")));
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
    void airflowContainerUsesTheEnvironmentRegionForItsOwnAwsSdkCalls() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(lifecycleManager.create(any())).thenReturn("airflow-container-id");
        when(lifecycleManager.startCreated(eq("airflow-container-id"), any())).thenReturn(
                new ContainerInfo("airflow-container-id", Map.of(8080, new EndpointInfo("172.18.0.5", 8080))));

        Environment environment = new Environment();
        environment.setName("my-env");
        environment.setArn("arn:aws:airflow:eu-west-1:111111111111:environment/my-env");

        manager.startAirflowContainer(environment, "2.10.5", "172.18.0.9", "db-secret-pw", null, null);

        ArgumentCaptor<ContainerSpec> captor = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).create(captor.capture());
        assertTrue(captor.getValue().env().contains("AWS_DEFAULT_REGION=eu-west-1"));
    }

    @Test
    void entrypointAndCmdOverrideTheStockImageDefaults() {
        ContainerSpec spec = startAirflowAndCaptureSpec(false);

        assertEquals(List.of("sh", "-c"), spec.entrypoint());
        assertEquals(1, spec.cmd().size());
        String script = spec.cmd().get(0);
        assertTrue(script.contains("airflow db migrate"));
        assertTrue(script.contains("airflow users create"));
        assertTrue(script.contains("(while true; do airflow triggerer; "));
        assertTrue(script.contains("airflow scheduler &"));
        assertTrue(script.contains("exec airflow webserver"));
        // migrate/user-create must precede the background triggerer and scheduler, not run inside
        // their background chain.
        assertTrue(script.indexOf("airflow db migrate") < script.indexOf("airflow triggerer"));
        assertTrue(script.indexOf("airflow db migrate") < script.indexOf("airflow scheduler &"));
    }

    @Test
    void bootstrapScriptInstallsRequirementsAfterTheStartupScriptAndBeforeAirflowStarts() {
        String script = MwaaEnvironmentManager.airflowBootstrapScript();

        int startupIdx = script.indexOf("/startup.sh");
        int saveEnvIdx = script.indexOf("(umask 077; export -p > /tmp/mwaa-startup.env)");
        int installIdx = script.indexOf("if [ -f /tmp/mwaa-requirements.txt ]; then sh /tmp/mwaa-install-requirements.sh"
                + " || ");
        int migrateIdx = script.indexOf("airflow db migrate");

        assertTrue(saveEnvIdx >= 0 && installIdx >= 0);
        assertTrue(startupIdx < saveEnvIdx && saveEnvIdx < installIdx && installIdx < migrateIdx,
                "real MWAA runs the startup script, then installs requirements, then starts Airflow");
    }

    @Test
    void requirementsInstallScriptKeepsTheStartupExportsLimitsPipAndRecordsItsExitCode() {
        String script = MwaaEnvironmentManager.requirementsInstallScript();

        assertTrue(script.startsWith("if [ -f /tmp/mwaa-startup.env ]; then . /tmp/mwaa-startup.env; fi\n"));
        assertTrue(script.contains("timeout 900 python -m pip install --no-cache-dir -r /tmp/mwaa-requirements.txt\n"));
        assertTrue(script.contains("echo \"$code\" > /tmp/mwaa-requirements.exit\n"));
        assertTrue(script.endsWith("exit \"$code\"\n"));
    }

    @Test
    void requirementsReinstallCopiesTheFileAndRunsTheInstallScript() throws Exception {
        DockerClient dockerClient = Mockito.mock(DockerClient.class);
        CopyArchiveToContainerCmd copyCmd = Mockito.mock(CopyArchiveToContainerCmd.class, Mockito.RETURNS_SELF);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        when(dockerClient.copyArchiveToContainerCmd(anyString())).thenReturn(copyCmd);
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, "airflow-container-id", 0,
                "", "");
        Environment environment = new Environment();
        environment.setName("my-env");
        environment.setAirflowContainerId("airflow-container-id");

        manager.installRequirements(environment, "boto3\n".getBytes(StandardCharsets.UTF_8));

        verify(copyCmd, times(2)).withRemotePath("/tmp");
        assertEquals(List.of(List.of("sh", "/tmp/mwaa-install-requirements.sh")), commands);
    }

    @Test
    void lastRequirementsInstallExitCodeReadsWhatTheInstallScriptRecorded() {
        DockerClient dockerClient = Mockito.mock(DockerClient.class);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        Environment environment = new Environment();
        environment.setName("my-env");
        environment.setAirflowContainerId("airflow-container-id");

        List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, "airflow-container-id", 0,
                "1\n", "");

        assertEquals(1, manager.lastRequirementsInstallExitCode(environment));
        assertEquals(List.of(List.of("cat", "/tmp/mwaa-requirements.exit")), commands);
    }

    @Test
    void lastRequirementsInstallExitCodeIsUnknownWhenNothingWasRecorded() {
        DockerClient dockerClient = Mockito.mock(DockerClient.class);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        Environment environment = new Environment();
        environment.setName("my-env");
        environment.setAirflowContainerId("airflow-container-id");
        ContainerExecStubs.completeEveryExec(dockerClient, "airflow-container-id", 1, "",
                "cat: /tmp/mwaa-requirements.exit: No such file or directory\n");

        assertNull(manager.lastRequirementsInstallExitCode(environment));
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
    void containerNamingAppliesTheConfiguredResourceNamespace() {
        EmulatorConfig.DockerConfig dockerConfig = Mockito.mock(EmulatorConfig.DockerConfig.class);
        when(dockerConfig.resourceNamespace()).thenReturn(Optional.of("ns1"));
        EmulatorConfig namespacedConfig = Mockito.mock(EmulatorConfig.class);
        when(namespacedConfig.docker()).thenReturn(dockerConfig);

        Environment environment = new Environment();
        environment.setName("my-env");
        environment.setAccountId("000000000000");
        environment.setArn("arn:aws:airflow:us-east-1:000000000000:environment/my-env");
        MwaaEnvironmentManager namespaced = namingManager(namespacedConfig,
                new RegionResolver("us-east-1", "000000000000"));

        assertEquals("floci-aws-ns1-mwaa-000000000000.us-east-1.my-env-db",
                namespaced.dbContainerName(environment));
        assertEquals("floci-aws-ns1-mwaa-000000000000.us-east-1.my-env-airflow",
                namespaced.airflowContainerName(environment));
    }

    @Test
    void anEnvironmentWithoutAnArnIsNamedInTheConfiguredDefaultRegion() {
        EmulatorConfig.DockerConfig dockerConfig = Mockito.mock(EmulatorConfig.DockerConfig.class);
        when(dockerConfig.resourceNamespace()).thenReturn(Optional.empty());
        EmulatorConfig chinaConfig = Mockito.mock(EmulatorConfig.class);
        when(chinaConfig.docker()).thenReturn(dockerConfig);
        MwaaEnvironmentManager china = namingManager(chinaConfig, new RegionResolver("cn-north-1", "000000000000"));

        Environment environment = new Environment();
        environment.setName("my-env");
        environment.setAccountId("000000000000");

        assertEquals("floci-aws-mwaa-000000000000.cn-north-1.my-env-db", china.dbContainerName(environment));
        environment.setArn("arn:aws:airflow:eu-west-1:000000000000:environment/my-env");
        assertEquals("floci-aws-mwaa-000000000000.eu-west-1.my-env-db", china.dbContainerName(environment));
    }

    /** A manager over {@code namingConfig} and {@code regionResolver}, for naming its containers. */
    private MwaaEnvironmentManager namingManager(EmulatorConfig namingConfig, RegionResolver regionResolver) {
        return new MwaaEnvironmentManager(null, lifecycleManager, containerDetector, namingConfig, regionResolver,
                null, ec2Service);
    }

    @Test
    void scopedContainerNamesDifferForSameNameEnvironments() {
        Environment eastEnvironment = new Environment();
        eastEnvironment.setName("shared-name");
        eastEnvironment.setAccountId("111111111111");
        eastEnvironment.setArn("arn:aws:airflow:us-east-1:111111111111:environment/shared-name");

        Environment westEnvironment = new Environment();
        westEnvironment.setName("shared-name");
        westEnvironment.setAccountId("222222222222");
        westEnvironment.setArn("arn:aws:airflow:eu-west-1:222222222222:environment/shared-name");

        assertNotEquals(manager.dbContainerName(eastEnvironment), manager.dbContainerName(westEnvironment));
        assertNotEquals(manager.airflowContainerName(eastEnvironment), manager.airflowContainerName(westEnvironment));
    }

    @Test
    void healthCheckRequiresMetadatabaseSchedulerAndTriggererHealthy() {
        String allHealthy = "{\"metadatabase\": {\"status\": \"healthy\"}, \"scheduler\": {\"status\": \"healthy\"}, "
                + "\"triggerer\": {\"latest_triggerer_heartbeat\": \"2026-11-15T00:40:57+00:00\", \"status\": \"healthy\"}}";
        String noTriggerer = "{\"metadatabase\": {\"status\": \"healthy\"}, \"scheduler\": {\"status\": \"healthy\"}, "
                + "\"triggerer\": {\"latest_triggerer_heartbeat\": null, \"status\": null}}";

        assertTrue(MwaaEnvironmentManager.isHealthy(allHealthy));
        assertFalse(MwaaEnvironmentManager.isHealthy(noTriggerer));
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
        void requirementsAreInjectedBeforeTheContainerStartsWhenProvided() {
            startAirflowAndCaptureSpec(false, null, "boto3\n".getBytes(StandardCharsets.UTF_8));

            verify(copyCmd).withRemotePath("/etc/sudoers.d");
            verify(copyCmd, times(2)).withRemotePath("/tmp");
            verify(copyCmd, times(3)).exec();
        }

        @Test
        void requirementsInjectionFailurePreventsTheContainerFromStarting() {
            when(copyCmd.exec()).thenThrow(new RuntimeException("docker cp failed"));
            when(lifecycleManager.create(any())).thenReturn("airflow-container-id");

            Environment environment = new Environment();
            environment.setName("my-env");

            assertThrows(IllegalStateException.class, () -> manager.startAirflowContainer(
                    environment, "2.10.5", "172.18.0.9", "db-secret-pw", null,
                    "boto3\n".getBytes(StandardCharsets.UTF_8)));

            verify(lifecycleManager).removeIfExists("airflow-container-id");
            verify(lifecycleManager, never()).startCreated(anyString(), any());
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
                    environment, "2.10.5", "172.18.0.9", "db-secret-pw", "export FOO=bar\n".getBytes(), null));

            verify(lifecycleManager).removeIfExists("airflow-container-id");
            verify(lifecycleManager, never()).startCreated(anyString(), any());
        }
    }

    /** Crash detection the readiness poller relies on to stop waiting on dead containers, verified
     *  against a mocked DockerClient (mirrors BatchDockerRunnerTest's deep-stub style). */
    @Nested
    class ContainerExitDetection {

        private DockerClient dockerClient;

        @BeforeEach
        void setUpDockerClient() {
            dockerClient = Mockito.mock(DockerClient.class, Mockito.RETURNS_DEEP_STUBS);
            when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        }

        private Environment environmentWithContainers(String dbContainerId, String airflowContainerId) {
            Environment environment = new Environment();
            environment.setName("my-env");
            environment.setDbContainerId(dbContainerId);
            environment.setAirflowContainerId(airflowContainerId);
            return environment;
        }

        @Test
        void reportsNotExitedWhileBothContainersAreStillRunning() {
            when(dockerClient.inspectContainerCmd("db-container-id").exec().getState().getRunning())
                    .thenReturn(true);
            when(dockerClient.inspectContainerCmd("airflow-container-id").exec().getState().getRunning())
                    .thenReturn(true);

            assertFalse(manager.hasAnyContainerExited(environmentWithContainers("db-container-id", "airflow-container-id")));
        }

        @Test
        void reportsExitedOnceTheAirflowContainerHasStopped() {
            when(dockerClient.inspectContainerCmd("db-container-id").exec().getState().getRunning())
                    .thenReturn(true);
            when(dockerClient.inspectContainerCmd("airflow-container-id").exec().getState().getRunning())
                    .thenReturn(false);

            assertTrue(manager.hasAnyContainerExited(environmentWithContainers("db-container-id", "airflow-container-id")));
        }

        @Test
        void reportsExitedOnceTheSiblingPostgresContainerHasStopped() {
            // Airflow itself keeps running when Postgres dies underneath it, it just never reports
            // metadatabase healthy, so this must be detected independently of the Airflow
            // container's own running state.
            when(dockerClient.inspectContainerCmd("db-container-id").exec().getState().getRunning())
                    .thenReturn(false);
            when(dockerClient.inspectContainerCmd("airflow-container-id").exec().getState().getRunning())
                    .thenReturn(true);

            assertTrue(manager.hasAnyContainerExited(environmentWithContainers("db-container-id", "airflow-container-id")));
        }

        @Test
        void reportsExitedWhenEitherContainerHasBeenRemoved() {
            when(dockerClient.inspectContainerCmd("db-container-id").exec().getState().getRunning())
                    .thenReturn(true);
            when(dockerClient.inspectContainerCmd("airflow-container-id").exec())
                    .thenThrow(new NotFoundException("no such container"));

            assertTrue(manager.hasAnyContainerExited(environmentWithContainers("db-container-id", "airflow-container-id")));
        }

        @Test
        void reportsNotExitedWhenNeitherContainerHasBeenCreatedYet() {
            assertFalse(manager.hasAnyContainerExited(new Environment()));
        }

        @Test
        void reportsNotExitedWhenTheDockerDaemonInspectFailsForAnUnrelatedReason() {
            // Anything other than NotFoundException is inconclusive, not "the container is gone" -
            // must not propagate, since the readiness poller shares one loop across every CREATING
            // environment and a thrown exception here would starve every other environment's check
            // for this poll tick, not just this one's.
            when(dockerClient.inspectContainerCmd("db-container-id").exec().getState().getRunning())
                    .thenReturn(true);
            when(dockerClient.inspectContainerCmd("airflow-container-id").exec())
                    .thenThrow(new RuntimeException("docker daemon connection reset"));

            assertFalse(manager.hasAnyContainerExited(environmentWithContainers("db-container-id", "airflow-container-id")));
        }
    }

    /**
     * Explicit DeleteEnvironment tears down through {@code stopEnvironment}. The shutdown
     * retention option is decided by {@link MwaaService#shutdown()} and must not turn a Delete
     * into metadata removal that leaves the containers running.
     */
    @Test
    void stopEnvironmentRemovesContainersEvenWhenShutdownRetentionIsEnabled() {
        when(mwaaConfig.keepRunningOnShutdown()).thenReturn(true);
        Environment environment = new Environment();
        environment.setName("deleted-env");
        environment.setAirflowContainerId("airflow-container-id");
        environment.setDbContainerId("db-container-id");

        manager.stopEnvironment(environment);

        Mockito.verify(lifecycleManager).stopAndRemove("airflow-container-id", null);
        Mockito.verify(lifecycleManager).stopAndRemove("db-container-id", null);
    }

    @Test
    void runAirflowCliRunsTheAirflowCommandInTheAirflowContainer() throws Exception {
        DockerClient dockerClient = Mockito.mock(DockerClient.class);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, "airflow-container-id", 2,
                "dag1\n", "warning\n");

        ContainerExec.Result result = manager.runAirflowCli("airflow-container-id", "2.10.5", "dags list");

        assertEquals(List.of(List.of("airflow", "dags", "list")), commands);
        assertEquals(2, result.exitCode());
        assertEquals("dag1\n", result.stdout());
        assertEquals("warning\n", result.stderr());
    }

    @Nested
    class AirflowCli {

        private static final String AIRFLOW_ID = "airflow-container-id";
        private DockerClient dockerClient;

        @BeforeEach
        void stubDocker() {
            dockerClient = Mockito.mock(DockerClient.class);
            when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        }

        @Test
        void shellSyntaxInACliCommandReachesAirflowAsPlainArguments() throws Exception {
            List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, AIRFLOW_ID, 0, "", "");

            manager.runAirflowCli(AIRFLOW_ID, "2.10.5", "dags list ; touch /tmp/pwned $(id) `id` | cat > /tmp/out");

            assertEquals(List.of(List.of("airflow", "dags", "list", ";", "touch", "/tmp/pwned", "$(id)", "`id`",
                    "|", "cat", ">", "/tmp/out")), commands);
        }

        @Test
        void unsupportedCommandsNeverReachTheAirflowContainer() throws Exception {
            List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, AIRFLOW_ID, 0, "", "");

            for (String command : List.of("users list", "users create", "db shell", "config get-value",
                    "webserver", "scheduler", "celery worker", "dags unknown", "dags", "")) {
                ContainerExec.Result result = manager.runAirflowCli(AIRFLOW_ID, "2.10.5", command);
                assertEquals(1, result.exitCode(), command);
                assertTrue(result.stderr().contains("not supported"), command);
            }
            assertEquals(List.of(), commands);
        }

        @Test
        void allowsEverySupportedMwaaCliCommand() {
            for (String command : List.of(
                    "cheat-sheet", "version", "connections add", "connections delete",
                    "dags backfill", "dags delete", "dags list", "dags list-import-errors",
                    "dags list-jobs", "dags list-runs", "dags next-execution", "dags pause",
                    "dags report", "dags reserialize", "dags show", "dags state", "dags test",
                    "dags trigger", "dags unpause", "db clean", "providers behaviours",
                    "providers get", "providers hooks", "providers links", "providers list",
                    "providers notifications", "providers secrets", "providers triggerer",
                    "providers widgets", "roles add-perms", "roles create", "roles del-perms",
                    "roles list", "tasks clear", "tasks failed-deps", "tasks list", "tasks render",
                    "tasks run", "tasks state", "tasks states-for-dag-run", "tasks test",
                    "variables delete", "variables get", "variables list", "variables set")) {
                assertTrue(MwaaEnvironmentManager.isSupportedCliCommand(List.of(command.split(" ")), "2.8.1"), command);
            }
        }

        @Test
        void providersNotificationsNeedsAirflow28() throws Exception {
            List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, AIRFLOW_ID, 0, "", "");

            ContainerExec.Result refused = manager.runAirflowCli(AIRFLOW_ID, "2.7.2", "providers notifications");
            manager.runAirflowCli(AIRFLOW_ID, "2.7.2", "providers triggerer");
            manager.runAirflowCli(AIRFLOW_ID, "2.8.1", "providers notifications");

            assertEquals(1, refused.exitCode());
            assertTrue(refused.stderr().contains("not supported"));
            assertEquals(List.of(List.of("airflow", "providers", "triggerer"),
                    List.of("airflow", "providers", "notifications")), commands);
        }

        @Test
        void quotedCliArgumentsReachAirflowWhole() throws Exception {
            List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, AIRFLOW_ID, 0, "", "");

            manager.runAirflowCli(AIRFLOW_ID, "2.10.5", "dags trigger -c '{\"key\": \"a b\"}' my_dag");
            manager.runAirflowCli(AIRFLOW_ID, "2.10.5", "variables set greeting \"say \\\"hi\\\" to $USER\" ''");
            manager.runAirflowCli(AIRFLOW_ID, "2.10.5", "variables get two\\ words");

            assertEquals(List.of(
                    List.of("airflow", "dags", "trigger", "-c", "{\"key\": \"a b\"}", "my_dag"),
                    List.of("airflow", "variables", "set", "greeting", "say \"hi\" to $USER", ""),
                    List.of("airflow", "variables", "get", "two words")), commands);
        }

        @Test
        void backslashNewlineJoinsTheTextOnEitherSide() throws Exception {
            List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, AIRFLOW_ID, 0, "", "");

            manager.runAirflowCli(AIRFLOW_ID, "2.10.5", "variables set greeting hel\\\nlo \"wor\\\nld\"");

            assertEquals(List.of(List.of("airflow", "variables", "set", "greeting", "hello", "world")), commands);
        }

        @Test
        void onlySpacesTabsAndNewlinesSeparateArguments() throws Exception {
            List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, AIRFLOW_ID, 0, "", "");

            manager.runAirflowCli(AIRFLOW_ID, "2.10.5", "variables\tset\ngreeting hello\u2003world");

            assertEquals(List.of(List.of("airflow", "variables", "set", "greeting", "hello\u2003world")), commands);
        }

        @Test
        void unterminatedQuoteIsRejectedWithoutRunningAnything() {
            List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, AIRFLOW_ID, 0, "", "");

            assertThrows(IllegalArgumentException.class,
                    () -> manager.runAirflowCli(AIRFLOW_ID, "2.10.5", "dags trigger -c '{\"key\": 1}"));
            assertEquals(List.of(), commands);
        }
    }

    @Nested
    class Restart {

        private static final String[] SECRETS = {
                "AIRFLOW__DATABASE__SQL_ALCHEMY_CONN="
                        + "postgresql+psycopg2://airflow:running-db-pw@172.18.0.9:5432/airflow",
                "AIRFLOW__CORE__FERNET_KEY=running-fernet-key",
                "_AIRFLOW_WWW_USER_PASSWORD=running-admin-pw"};

        private Environment environment;
        private DockerClient docker;

        @BeforeEach
        void anEnvironment() {
            environment = new Environment();
            environment.setName("my-env");
            docker = Mockito.mock(DockerClient.class);
            when(lifecycleManager.getDockerClient()).thenReturn(docker);
            // Docker has no container of any name a test does not give one.
            when(docker.inspectContainerCmd(anyString())).thenAnswer(invocation -> Mockito.mock(
                    InspectContainerCmd.class, missing -> {
                        throw new NotFoundException("No such container: " + invocation.getArgument(0));
                    }));
            when(lifecycleManager.tryListVolumeNames()).thenReturn(Optional.of(Set.of()));
        }

        private void withSavedSecrets() {
            environment.setDbPassword("saved-db-pw");
            environment.setFernetKey("saved-fernet-key");
            environment.setAirflowAdminPassword("saved-admin-pw");
        }

        /** A container Docker has under {@code name}, started with {@code env}. */
        private void survivor(String name, String id, boolean running, String... env) {
            InspectContainerResponse response = Mockito.mock(InspectContainerResponse.class, Mockito.RETURNS_DEEP_STUBS);
            when(response.getId()).thenReturn(id);
            when(response.getState().getRunning()).thenReturn(running);
            when(response.getConfig().getEnv()).thenReturn(env);
            InspectContainerCmd inspect = Mockito.mock(InspectContainerCmd.class);
            when(inspect.exec()).thenReturn(response);
            Mockito.doReturn(inspect).when(docker).inspectContainerCmd(name);
        }

        /** Docker answers no inspect of any container. */
        private void dockerCannotSay() {
            when(docker.inspectContainerCmd(anyString())).thenThrow(new IllegalStateException("daemon unreachable"));
        }

        private String legacy(String name) {
            return ContainerStorageHelper.legacyDockerName(config, name);
        }

        private String airflowName() {
            return manager.airflowContainerName(environment);
        }

        private String dbName() {
            return manager.dbContainerName(environment);
        }

        private ContainerSpec createdSpec() {
            ArgumentCaptor<ContainerSpec> spec = ArgumentCaptor.forClass(ContainerSpec.class);
            verify(lifecycleManager).create(spec.capture());
            return spec.getValue();
        }

        @Test
        void aRestartedAirflowGetsTheSecretsItsDatabaseWasSetUpWith() {
            withSavedSecrets();
            when(containerDetector.isRunningInContainer()).thenReturn(false);
            when(lifecycleManager.create(any())).thenReturn("airflow-container-id");
            when(lifecycleManager.startCreated(eq("airflow-container-id"), any())).thenReturn(new ContainerInfo(
                    "airflow-container-id", Map.of(8080, new EndpointInfo("172.18.0.5", 8080))));

            manager.startAirflowContainer(environment, "2.10.5", "172.18.0.9", "saved-db-pw", null, null);

            ContainerSpec spec = createdSpec();
            assertTrue(spec.env().contains("AIRFLOW__CORE__FERNET_KEY=saved-fernet-key"));
            assertTrue(spec.env().contains("_AIRFLOW_WWW_USER_PASSWORD=saved-admin-pw"));
            assertEquals("saved-fernet-key", environment.getFernetKey());
            assertEquals("saved-admin-pw", environment.getAirflowAdminPassword());
        }

        @Test
        void aNewEnvironmentStartsOnEmptyVolumesWithSecretsOfItsOwn() {
            withSavedSecrets();
            when(lifecycleManager.create(any())).thenThrow(new IllegalStateException("stop here"));

            assertThrows(IllegalStateException.class,
                    () -> manager.startEnvironment(environment, "2.10.5", null, null));

            InOrder inOrder = Mockito.inOrder(lifecycleManager);
            for (String volume : List.of(dbName(), airflowName() + "-dags", airflowName() + "-logs")) {
                inOrder.verify(lifecycleManager).removeVolumeStrict(volume);
                inOrder.verify(lifecycleManager).removeVolumeStrict(legacy(volume));
            }
            inOrder.verify(lifecycleManager).ensureVolume(dbName());
            assertNotEquals("saved-db-pw", environment.getDbPassword());
            assertNotEquals("saved-fernet-key", environment.getFernetKey());
            assertNotEquals("saved-admin-pw", environment.getAirflowAdminPassword());
        }

        @Test
        void aRestartKeepsTheVolumesAndTheSecretsTheDatabaseWasSetUpWith() {
            withSavedSecrets();
            Set<String> volumes = Set.of(dbName());
            when(lifecycleManager.tryListVolumeNames()).thenReturn(Optional.of(volumes));
            when(lifecycleManager.create(any())).thenThrow(new IllegalStateException("stop here"));

            assertThrows(IllegalStateException.class,
                    () -> manager.restartEnvironment(environment, "2.10.5", null, null));

            ContainerSpec spec = createdSpec();
            assertTrue(spec.env().contains("POSTGRES_PASSWORD=saved-db-pw"));
            assertEquals(List.of(dbName()), spec.mounts().stream().map(Mount::getSource).toList());
            verify(lifecycleManager).ensureVolume(dbName());
            verify(lifecycleManager, never()).removeVolumeStrict(anyString());
            verify(lifecycleManager, never()).removeVolume(anyString());
            assertEquals("saved-fernet-key", environment.getFernetKey());
        }

        @Test
        void aRestartUsesTheVolumeAnOlderFlociLeftWhenOnlyThatOneIsThere() {
            withSavedSecrets();
            Set<String> volumes = Set.of(legacy(dbName()));
            when(lifecycleManager.tryListVolumeNames()).thenReturn(Optional.of(volumes));
            when(lifecycleManager.create(any())).thenThrow(new IllegalStateException("stop here"));

            assertThrows(IllegalStateException.class,
                    () -> manager.restartEnvironment(environment, "2.10.5", null, null));

            assertEquals(List.of(legacy(dbName())), createdSpec().mounts().stream().map(Mount::getSource).toList());
            verify(lifecycleManager).ensureVolume(legacy(dbName()));
        }

        @Test
        void aRestartDockerCannotListTheVolumesOfIsRefused() {
            withSavedSecrets();
            when(lifecycleManager.tryListVolumeNames()).thenReturn(Optional.empty());

            assertThrows(IllegalStateException.class,
                    () -> manager.restartEnvironment(environment, "2.10.5", null, null));

            verify(lifecycleManager, never()).ensureVolume(anyString());
            verify(lifecycleManager, never()).create(any());
        }

        @Test
        void aRestartWithoutTheSavedSecretsIsRefused() {
            assertThrows(IllegalStateException.class,
                    () -> manager.restartEnvironment(environment, "2.10.5", null, null));

            verify(lifecycleManager, never()).create(any());
        }

        @Test
        void containersLeftUnderEitherNameGoBeforeARestart() {
            withSavedSecrets();
            survivor(airflowName(), "old-airflow", true);
            survivor(legacy(dbName()), "old-legacy-db", false);
            when(lifecycleManager.create(any())).thenThrow(new IllegalStateException("stop here"));

            assertThrows(IllegalStateException.class,
                    () -> manager.restartEnvironment(environment, "2.10.5", null, null));

            InOrder inOrder = Mockito.inOrder(lifecycleManager);
            inOrder.verify(lifecycleManager).removeIfExistsStrict("old-airflow");
            inOrder.verify(lifecycleManager).removeIfExistsStrict("old-legacy-db");
            inOrder.verify(lifecycleManager).create(any());
        }

        @Test
        void aRestartDoesNotStartBesideAContainerDockerWillNotRemove() {
            withSavedSecrets();
            survivor(legacy(dbName()), "stuck-db", true);
            Mockito.doThrow(new IllegalStateException("Failed to remove stale container stuck-db"))
                    .when(lifecycleManager).removeIfExistsStrict("stuck-db");

            assertThrows(IllegalStateException.class,
                    () -> manager.restartEnvironment(environment, "2.10.5", null, null));

            verify(lifecycleManager, never()).create(any());
        }

        @Test
        void aRestartDoesNotStartWhenDockerCannotSayWhatIsLeft() {
            withSavedSecrets();
            dockerCannotSay();

            assertThrows(IllegalStateException.class,
                    () -> manager.restartEnvironment(environment, "2.10.5", null, null));

            verify(lifecycleManager, never()).create(any());
        }

        @Test
        void stoppingTheContainersRemovesThemByIdAndByNameAndKeepsTheVolumes() {
            environment.setAirflowContainerId("airflow-container-id");
            environment.setDbContainerId("db-container-id");
            survivor(legacy(airflowName()), "unreached-airflow", true);

            manager.stopContainers(environment);

            verify(lifecycleManager).stopAndRemove("airflow-container-id", null);
            verify(lifecycleManager).stopAndRemove("db-container-id", null);
            verify(lifecycleManager).removeIfExistsStrict("unreached-airflow");
            verify(lifecycleManager, never()).removeVolume(anyString());
        }

        @Test
        void stoppingOnlyItsOwnContainersLeavesItsNamesAlone() {
            environment.setAirflowContainerId("airflow-container-id");
            environment.setDbContainerId("db-container-id");
            survivor(airflowName(), "recreated-airflow", true);

            manager.stopOwnContainers(environment);

            verify(lifecycleManager).stopAndRemove("airflow-container-id", null);
            verify(lifecycleManager).stopAndRemove("db-container-id", null);
            verify(lifecycleManager, never()).removeIfExistsStrict(anyString());
            verify(lifecycleManager, never()).removeVolume(anyString());
        }

        @Test
        void stoppingTheEnvironmentRemovesWhatIsLeftUnderItsNamesInEitherScheme() {
            survivor(airflowName(), "survivor-airflow", true);

            manager.stopEnvironment(environment);

            verify(lifecycleManager).removeIfExistsStrict("survivor-airflow");
            for (String volume : List.of(dbName(), airflowName() + "-dags", airflowName() + "-logs")) {
                verify(lifecycleManager).removeVolume(volume);
                verify(lifecycleManager).removeVolume(legacy(volume));
            }
        }

        @Test
        void aDockerThatCannotSayWhatIsLeftDoesNotStopTheDelete() {
            dockerCannotSay();

            manager.stopEnvironment(environment);

            verify(lifecycleManager).removeVolume(dbName());
        }

        @Test
        void runningContainersAreTakenOverWithTheSecretsTheyRunWith() {
            survivor(airflowName(), "running-airflow", true, SECRETS);
            survivor(dbName(), "running-db", true);
            when(lifecycleManager.adopt(eq("running-airflow"), eq(List.of(8080)), any())).thenReturn(new ContainerInfo(
                    "running-airflow", Map.of(8080, new EndpointInfo("localhost", 32768))));

            assertTrue(manager.adoptRunningEnvironment(environment));

            assertEquals("running-airflow", environment.getAirflowContainerId());
            assertEquals("running-db", environment.getDbContainerId());
            assertEquals("running-db-pw", environment.getDbPassword());
            assertEquals("running-fernet-key", environment.getFernetKey());
            assertEquals("running-admin-pw", environment.getAirflowAdminPassword());
            assertEquals("localhost", environment.getAirflowInternalHost());
            assertEquals(32768, environment.getAirflowInternalPort());
        }

        @Test
        void runningContainersAnOlderFlociNamedAreTakenOver() {
            survivor(legacy(airflowName()), "legacy-airflow", true, SECRETS);
            survivor(legacy(dbName()), "legacy-db", true);
            when(lifecycleManager.adopt(eq("legacy-airflow"), eq(List.of(8080)), any())).thenReturn(new ContainerInfo(
                    "legacy-airflow", Map.of(8080, new EndpointInfo("localhost", 32769))));

            assertTrue(manager.adoptRunningEnvironment(environment));

            assertEquals("legacy-airflow", environment.getAirflowContainerId());
            assertEquals("legacy-db", environment.getDbContainerId());
            assertEquals("running-db-pw", environment.getDbPassword());
        }

        @Test
        void containersAreNotTakenOverUnlessBothRun() {
            survivor(airflowName(), "running-airflow", true, SECRETS);
            survivor(dbName(), "exited-db", false);

            assertFalse(manager.adoptRunningEnvironment(environment));

            verify(lifecycleManager, never()).adopt(anyString(), any(), any());
            assertNull(environment.getAirflowContainerId());
        }

        @Test
        void containersAreNotTakenOverWithoutTheSecretsTheyRunWith() {
            survivor(airflowName(), "running-airflow", true, SECRETS[0], SECRETS[2]);
            survivor(dbName(), "running-db", true);

            assertFalse(manager.adoptRunningEnvironment(environment));

            verify(lifecycleManager, never()).adopt(anyString(), any(), any());
        }

        @Test
        void aDockerThatCannotSayFailsTheTakeoverRatherThanReadingAsNothingLeft() {
            dockerCannotSay();

            assertThrows(IllegalStateException.class, () -> manager.adoptRunningEnvironment(environment));

            verify(lifecycleManager, never()).adopt(anyString(), any(), any());
        }

        @Test
        void theSecretsAreRecoveredFromAStoppedContainerAnOlderFlociLeft() {
            survivor(legacy(airflowName()), "stopped-airflow", false, SECRETS);

            assertTrue(manager.recoverSecrets(environment));

            assertEquals("running-db-pw", environment.getDbPassword());
            assertEquals("running-fernet-key", environment.getFernetKey());
            assertEquals("running-admin-pw", environment.getAirflowAdminPassword());
        }

        @Test
        void noSecretsAreRecoveredFromAContainerThatDoesNotNameThem() {
            survivor(airflowName(), "foreign-airflow", false, SECRETS[1]);

            assertFalse(manager.recoverSecrets(environment));
            assertNull(environment.getFernetKey());
        }

        @Test
        void noSecretsAreRecoveredWithNoContainerLeft() {
            assertFalse(manager.recoverSecrets(environment));
            assertNull(environment.getDbPassword());
        }

        @Test
        void aDockerThatCannotSayFailsTheRecovery() {
            dockerCannotSay();

            assertThrows(IllegalStateException.class, () -> manager.recoverSecrets(environment));
        }

        @Test
        void anEnvironmentWithoutSecretsGetsNoNewOnesWhileItsDatabaseVolumesAreLeft() {
            Set<String> volumes = Set.of(legacy(dbName()));
            when(lifecycleManager.tryListVolumeNames()).thenReturn(Optional.of(volumes));

            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> manager.giveNewSecrets(environment));

            assertTrue(refused.getMessage().contains(legacy(dbName())), refused.getMessage());
            assertNull(environment.getDbPassword());
            verify(lifecycleManager, never()).removeVolumeStrict(anyString());
        }

        @Test
        void anEnvironmentWithoutSecretsAndNothingLeftGetsNewOnes() {
            manager.giveNewSecrets(environment);

            assertTrue(MwaaEnvironmentManager.hasSecrets(environment));
            verify(lifecycleManager, never()).create(any());
        }

        @Test
        void anEnvironmentWithoutSecretsGetsNoNewOnesWhenDockerCannotListTheVolumes() {
            when(lifecycleManager.tryListVolumeNames()).thenReturn(Optional.empty());

            assertThrows(IllegalStateException.class, () -> manager.giveNewSecrets(environment));

            assertNull(environment.getDbPassword());
        }

        @Test
        void theDagFilesAreReadFromTheContainerWithoutPythonsCaches() {
            environment.setAirflowContainerId("airflow-container-id");
            ContainerExecStubs.completeEveryExec(docker, "airflow-container-id", 0, "/opt/airflow/dags/a.py\n"
                    + "/opt/airflow/dags/sub/b.py\n/opt/airflow/dags/__pycache__/a.cpython-311.pyc\n", "");

            assertEquals(List.of("a.py", "sub/b.py"), manager.listDagFiles(environment));
        }

        @Test
        void aDagListingThatFailsIsNotReadAsAnEmptyFolder() {
            environment.setAirflowContainerId("airflow-container-id");
            ContainerExecStubs.completeEveryExec(docker, "airflow-container-id", 1, "", "find: permission denied");

            assertThrows(IllegalStateException.class, () -> manager.listDagFiles(environment));
        }

        @Test
        void theDatabasePasswordIsReadFromTheUrlAirflowWasGiven() {
            assertEquals("pw-with_chars", MwaaEnvironmentManager.dbPasswordOf(
                    "postgresql+psycopg2://airflow:pw-with_chars@10.0.0.2:5432/airflow"));
            assertNull(MwaaEnvironmentManager.dbPasswordOf("sqlite:////tmp/airflow.db"));
            assertNull(MwaaEnvironmentManager.dbPasswordOf("mysql+mysqldb://someone:password@db.example:3306/airflow"));
            assertNull(MwaaEnvironmentManager.dbPasswordOf("postgresql+psycopg2://airflow:@10.0.0.2:5432/airflow"));
            assertNull(MwaaEnvironmentManager.dbPasswordOf(null));
        }

        @Test
        void nothingStartsOrIsTakenOverOnceFlociIsShuttingDown() {
            withSavedSecrets();
            manager.markShuttingDown();

            assertThrows(MwaaEnvironmentManager.ShuttingDownException.class,
                    () -> manager.startEnvironment(environment, "2.10.5", null, null));
            assertThrows(MwaaEnvironmentManager.ShuttingDownException.class,
                    () -> manager.restartEnvironment(environment, "2.10.5", null, null));
            assertThrows(MwaaEnvironmentManager.ShuttingDownException.class,
                    () -> manager.adoptRunningEnvironment(environment));

            assertTrue(manager.isShuttingDown());
            verify(lifecycleManager, never()).create(any());
            verify(docker, never()).inspectContainerCmd(anyString());
            assertEquals("saved-db-pw", environment.getDbPassword());
        }

        @Test
        void aDatabaseContainerThatDoesNotStartIsRecordedForItsCleanup() {
            withSavedSecrets();
            when(lifecycleManager.create(any())).thenReturn("db-container-id");
            when(lifecycleManager.startCreated(eq("db-container-id"), any()))
                    .thenThrow(new IllegalStateException("port taken"));

            assertThrows(IllegalStateException.class,
                    () -> manager.restartEnvironment(environment, "2.10.5", null, null));

            assertEquals("db-container-id", environment.getDbContainerId());
        }

        @Test
        void aShutdownDuringTheAirflowStartRemovesBothContainers() {
            withSavedSecrets();
            ContainerExecStubs.completeEveryExec(docker, "db-container-id", 0, "", "");
            InspectContainerCmd inspect = Mockito.mock(InspectContainerCmd.class);
            InspectContainerResponse response = Mockito.mock(InspectContainerResponse.class);
            NetworkSettings settings = Mockito.mock(NetworkSettings.class);
            ContainerNetwork network = Mockito.mock(ContainerNetwork.class);
            Mockito.doReturn(inspect).when(docker).inspectContainerCmd("db-container-id");
            when(inspect.exec()).thenReturn(response);
            when(response.getNetworkSettings()).thenReturn(settings);
            when(settings.getNetworks()).thenReturn(Map.of("bridge", network));
            when(network.getIpAddress()).thenReturn("172.18.0.9");
            when(lifecycleManager.create(any())).thenReturn("db-container-id", "airflow-container-id");
            when(lifecycleManager.startCreated(eq("db-container-id"), any()))
                    .thenReturn(new ContainerInfo("db-container-id", Map.of()));
            when(lifecycleManager.startCreated(eq("airflow-container-id"), any())).thenAnswer(invocation -> {
                manager.markShuttingDown();
                return new ContainerInfo("airflow-container-id", Map.of(8080, new EndpointInfo("172.18.0.5", 8080)));
            });

            assertThrows(MwaaEnvironmentManager.ShuttingDownException.class,
                    () -> manager.restartEnvironment(environment, "2.10.5", null, null));

            verify(lifecycleManager).stopAndRemove("airflow-container-id", null);
            verify(lifecycleManager).stopAndRemove("db-container-id", null);
        }

        @Test
        void aStartOvertakenByTheShutdownRemovesWhatItStarted() {
            withSavedSecrets();
            when(lifecycleManager.create(any())).thenReturn("db-container-id");
            when(lifecycleManager.startCreated(eq("db-container-id"), any())).thenAnswer(invocation -> {
                manager.markShuttingDown();
                return new ContainerInfo("db-container-id", Map.of());
            });

            assertThrows(MwaaEnvironmentManager.ShuttingDownException.class,
                    () -> manager.restartEnvironment(environment, "2.10.5", null, null));

            verify(lifecycleManager).stopAndRemove("db-container-id", null);
            verify(lifecycleManager, times(1)).create(any());
        }
    }
}
