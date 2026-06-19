package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.NetworkSettings;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceNetworkInterface;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class Ec2ContainerManagerTest {

    @org.junit.jupiter.api.AfterEach
    void resetBridgeIpPolling() {
        Ec2ContainerManager.containerBridgeIpAttempts = 30;
        Ec2ContainerManager.containerBridgeIpPollMillis = 500;
    }

    @Test
    void exposeReachablePrivateAddressUpdatesInstanceAndAttachedNetworkInterfaces() {
        Instance instance = new Instance();
        instance.setPrivateIpAddress("10.82.32.10");
        instance.setPrivateDnsName("ip-10-82-32-10.ec2.internal");

        InstanceNetworkInterface networkInterface = new InstanceNetworkInterface();
        networkInterface.setPrivateIpAddress("10.82.32.10");
        networkInterface.setPrivateDnsName("ip-10-82-32-10.ec2.internal");
        instance.setNetworkInterfaces(List.of(networkInterface));

        Ec2ContainerManager.exposeReachablePrivateAddress(instance, "192.168.215.21");

        assertEquals("192.168.215.21", instance.getPrivateIpAddress());
        assertEquals("ip-192-168-215-21.ec2.internal", instance.getPrivateDnsName());
        assertEquals("192.168.215.21", networkInterface.getPrivateIpAddress());
        assertEquals("ip-192-168-215-21.ec2.internal", networkInterface.getPrivateDnsName());
    }

    @Test
    void restoreMetadataRegistrationRegistersRunningPersistedContainer() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.isContainerRunning("container-1")).thenReturn(true);

        DockerClient dockerClient = mock(DockerClient.class);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse response = inspectResponse("192.168.215.42");
        when(dockerClient.inspectContainerCmd("container-1")).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);

        Ec2MetadataServer metadataServer = mock(Ec2MetadataServer.class);
        Ec2ContainerManager manager = new Ec2ContainerManager(
                mock(ContainerBuilder.class),
                lifecycleManager,
                mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class),
                mock(DockerHostResolver.class),
                dockerClient,
                mock(PortAllocator.class),
                mock(EmulatorConfig.class),
                metadataServer);

        Instance instance = new Instance();
        instance.setInstanceId("i-restored");
        instance.setDockerContainerId("container-1");
        instance.setContainerBridgeIp("192.168.215.7");

        assertTrue(manager.restoreMetadataRegistration(instance));

        assertEquals("192.168.215.42", instance.getContainerBridgeIp());
        assertEquals("192.168.215.42", instance.getPrivateIpAddress());
        verify(metadataServer).unregisterContainer("192.168.215.7", instance);
        verify(metadataServer).registerContainer("192.168.215.42", "i-restored", instance);
    }

    @Test
    void userDataExecutionCommandRunsScriptDirectlySoShebangIsHonored() {
        assertArrayEquals(new String[]{"/tmp/user-data.sh"}, Ec2ContainerManager.userDataExecutionCommand());
    }

    @Test
    void userDataShellScriptsPreservesRawShellScript() {
        String script = "#!/bin/bash\nset -euo pipefail\necho ready\n";

        assertEquals(List.of(script), Ec2ContainerManager.userDataShellScripts(script));
    }

    @Test
    void userDataShellScriptsExtractsMimeShellscriptPartsInOrder() {
        String userData = """
                Content-Type: multipart/mixed; boundary="==SAMPLE-CLOUD-INIT=="
                MIME-Version: 1.0

                --==SAMPLE-CLOUD-INIT==
                Content-Type: text/cloud-config; charset="us-ascii"

                #cloud-config

                --==SAMPLE-CLOUD-INIT==
                Content-Type: text/x-shellscript; charset="us-ascii"

                #!/bin/bash
                echo first

                --==SAMPLE-CLOUD-INIT==
                Content-Type: text/x-shellscript

                #!/bin/sh
                echo second

                --==SAMPLE-CLOUD-INIT==--
                """;

        assertEquals(
                List.of(
                        "#!/bin/bash\necho first\n",
                        "#!/bin/sh\necho second\n"),
                Ec2ContainerManager.userDataShellScripts(userData));
    }

    @Test
    void userDataShellScriptsIgnoresCloudConfigWithoutShellscript() {
        String userData = """
                Content-Type: multipart/mixed; boundary=cloudinit
                MIME-Version: 1.0

                --cloudinit
                Content-Type: text/cloud-config

                #cloud-config

                --cloudinit--
                """;

        assertTrue(Ec2ContainerManager.userDataShellScripts(userData).isEmpty());
    }

    @Test
    void metadataProxyInstallCommandInstallsLinkLocalProxyDependencies() {
        String[] command = Ec2ContainerManager.metadataProxyInstallCommand();

        assertEquals("sh", command[0]);
        assertTrue(command[2].contains("iproute2"));
        assertTrue(command[2].contains("socat"));
        assertTrue(command[2].contains("curl"));
    }

    @Test
    void metadataProxyStartCommandBindsAwsLinkLocalMetadataAddress() {
        String[] command = Ec2ContainerManager.metadataProxyStartCommand("floci", 9169);

        assertEquals("sh", command[0]);
        assertTrue(command[2].contains("169.254.169.254/32"));
        assertTrue(command[2].contains("TCP-LISTEN:80,bind=169.254.169.254"));
        assertTrue(command[2].contains("TCP:floci:9169"));
        assertTrue(command[2].contains("http://169.254.169.254/latest/meta-data/instance-id"));
    }

    @Test
    void localAwsEnvironmentProvidesCliCredentialsAndFlociEndpoint() {
        assertEquals(
                java.util.List.of(
                        "AWS_EC2_METADATA_SERVICE_ENDPOINT=http://floci:9169",
                        "AWS_ENDPOINT_URL=http://floci:4566",
                        "AWS_DEFAULT_REGION=us-west-2",
                        "AWS_REGION=us-west-2",
                        "AWS_ACCESS_KEY_ID=test",
                        "AWS_SECRET_ACCESS_KEY=test",
                        "AWS_SESSION_TOKEN=test-session-token"),
                Ec2ContainerManager.localAwsEnvironment(
                        "us-west-2",
                        "http://floci:4566",
                        "http://floci:9169"));
    }

    @Test
    void preferredMetadataSourceIpUsesConfiguredNetworkBeforeBridge() {
        ContainerNetwork bridge = new ContainerNetwork();
        bridge.withIpv4Address("172.17.0.8");
        ContainerNetwork floci = new ContainerNetwork();
        floci.withIpv4Address("192.168.215.10");

        assertEquals(
                "192.168.215.10",
                Ec2ContainerManager.preferredMetadataSourceIp(Map.of(
                        "bridge", bridge,
                        "custom-floci-network", floci)).orElseThrow());
    }

    @Test
    void preferredMetadataSourceIpFallsBackToBridge() {
        ContainerNetwork bridge = new ContainerNetwork();
        bridge.withIpv4Address("172.17.0.8");

        assertEquals(
                "172.17.0.8",
                Ec2ContainerManager.preferredMetadataSourceIp(Map.of("bridge", bridge)).orElseThrow());
    }

    @Test
    void preferredMetadataSourceIpIsEmptyWithoutUsableAddress() {
        ContainerNetwork bridge = new ContainerNetwork();

        assertTrue(Ec2ContainerManager.preferredMetadataSourceIp(Map.of("bridge", bridge)).isEmpty());
    }

    @Test
    void launchWaitsForContainerBridgeIpBeforeRegisteringImds() throws Exception {
        Ec2ContainerManager.containerBridgeIpAttempts = 3;
        Ec2ContainerManager.containerBridgeIpPollMillis = 1;
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse noIp = inspectResponse(null);
        InspectContainerResponse withIp = inspectResponse("172.18.0.9");
        when(harness.dockerClient.inspectContainerCmd("container-1")).thenReturn(inspect);
        when(inspect.exec()).thenReturn(noIp).thenReturn(withIp);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));

        Instance instance = instance("i-waitip");

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
        assertEquals("container-1", instance.getDockerContainerId());
        assertEquals("172.18.0.9", instance.getContainerBridgeIp());
        assertEquals("172.18.0.9", instance.getPrivateIpAddress());
        verify(inspect, times(2)).exec();
        verify(harness.metadataServer).registerContainer("172.18.0.9", "i-waitip", instance);
    }

    @Test
    void launchTerminatesWhenContainerBridgeIpNeverAppears() throws Exception {
        Ec2ContainerManager.containerBridgeIpAttempts = 2;
        Ec2ContainerManager.containerBridgeIpPollMillis = 1;
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse noIp = inspectResponse(null);
        when(harness.dockerClient.inspectContainerCmd("container-1")).thenReturn(inspect);
        when(inspect.exec()).thenReturn(noIp);

        Instance instance = instance("i-noip");

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "terminated".equals(instance.getState().getName()), Duration.ofSeconds(2));
        assertEquals("container-1", instance.getDockerContainerId());
        verify(harness.metadataServer, never()).registerContainer(anyString(), anyString(), any());
    }

    @Test
    void launchMarksInstanceRunningBeforeUserDataCompletes() throws Exception {
        Ec2ContainerManager.containerBridgeIpAttempts = 2;
        Ec2ContainerManager.containerBridgeIpPollMillis = 1;
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse withIp = inspectResponse("172.18.0.10");
        when(harness.dockerClient.inspectContainerCmd("container-1")).thenReturn(inspect);
        when(inspect.exec()).thenReturn(withIp);
        CountDownLatch userDataStarted = new CountDownLatch(1);
        CountDownLatch finishUserData = new CountDownLatch(1);
        harness.stubSuccessfulExecs(userDataStarted, finishUserData);
        Instance instance = instance("i-userdata");
        instance.setUserData("#!/bin/sh\necho ready\n");

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        assertTrue(userDataStarted.await(2, TimeUnit.SECONDS), "user data should start");
        assertEquals("running", instance.getState().getName());
        assertFalse(finishUserData.await(10, TimeUnit.MILLISECONDS), "user data should still be blocked");
        finishUserData.countDown();
        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
    }

    private static LaunchHarness launchHarness() {
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(new ContainerSpec("ubuntu:24.04"));

        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.create(any(ContainerSpec.class))).thenReturn("container-1");
        when(lifecycleManager.isContainerRunning("container-1")).thenReturn(true);

        DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
        when(dockerHostResolver.resolve()).thenReturn("floci");
        PortAllocator portAllocator = mock(PortAllocator.class);
        when(portAllocator.allocate(anyInt(), anyInt())).thenReturn(2201);

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.sshPortRangeStart()).thenReturn(2200);
        when(ec2.sshPortRangeEnd()).thenReturn(2299);
        when(ec2.imdsPort()).thenReturn(9169);

        DockerClient dockerClient = mock(DockerClient.class);
        Ec2MetadataServer metadataServer = mock(Ec2MetadataServer.class);
        Ec2ContainerManager manager = new Ec2ContainerManager(
                containerBuilder,
                lifecycleManager,
                mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class),
                dockerHostResolver,
                dockerClient,
                portAllocator,
                config,
                metadataServer);
        return new LaunchHarness(manager, dockerClient, metadataServer);
    }

    private static Instance instance(String instanceId) {
        Instance instance = new Instance();
        instance.setInstanceId(instanceId);
        return instance;
    }

    private static InspectContainerResponse inspectResponse(String ipAddress) {
        InspectContainerResponse inspect = mock(InspectContainerResponse.class);
        NetworkSettings networkSettings = mock(NetworkSettings.class);
        when(inspect.getNetworkSettings()).thenReturn(networkSettings);
        if (ipAddress != null) {
            ContainerNetwork bridge = new ContainerNetwork().withIpv4Address(ipAddress);
            when(networkSettings.getNetworks()).thenReturn(Map.of("bridge", bridge));
        }
        return inspect;
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition was not met before timeout");
    }

    private record LaunchHarness(Ec2ContainerManager manager,
                                 DockerClient dockerClient,
                                 Ec2MetadataServer metadataServer) {
        void stubSuccessfulExecs(CountDownLatch userDataStarted, CountDownLatch finishUserData) throws Exception {
            AtomicReference<String[]> currentCommand = new AtomicReference<>();
            ExecCreateCmd execCreate = mock(ExecCreateCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
            ExecCreateCmdResponse metadataExec = mock(ExecCreateCmdResponse.class);
            ExecCreateCmdResponse userDataExec = mock(ExecCreateCmdResponse.class);
            when(metadataExec.getId()).thenReturn("metadata-exec");
            when(userDataExec.getId()).thenReturn("userdata-exec");
            when(dockerClient.execCreateCmd("container-1")).thenReturn(execCreate);
            when(execCreate.withCmd(any(String[].class))).thenAnswer(invocation -> {
                Object[] args = invocation.getArguments();
                if (args.length == 1 && args[0] instanceof String[] command) {
                    currentCommand.set(command);
                } else {
                    currentCommand.set(Arrays.copyOf(args, args.length, String[].class));
                }
                return execCreate;
            });
            when(execCreate.exec()).thenAnswer(invocation -> {
                String[] command = currentCommand.get();
                if (command != null && command.length == 1 && "/tmp/user-data.sh".equals(command[0])) {
                    return userDataExec;
                }
                return metadataExec;
            });

            when(dockerClient.execStartCmd(anyString())).thenAnswer(invocation -> {
                String execId = invocation.getArgument(0);
                ExecStartCmd execStart = mock(ExecStartCmd.class);
                when(execStart.exec(any())).thenAnswer(startInvocation -> {
                    @SuppressWarnings("unchecked")
                    ResultCallback<Frame> callback = startInvocation.getArgument(0);
                    if ("userdata-exec".equals(execId)) {
                        userDataStarted.countDown();
                        finishUserData.await(2, TimeUnit.SECONDS);
                    }
                    callback.onComplete();
                    return callback;
                });
                return execStart;
            });

            InspectExecCmd inspectExec = mock(InspectExecCmd.class);
            InspectExecResponse inspectExecResponse = mock(InspectExecResponse.class);
            when(inspectExecResponse.getExitCodeLong()).thenReturn(0L);
            when(inspectExec.exec()).thenReturn(inspectExecResponse);
            when(dockerClient.inspectExecCmd(anyString())).thenReturn(inspectExec);

            CopyArchiveToContainerCmd copy = mock(CopyArchiveToContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
            when(dockerClient.copyArchiveToContainerCmd("container-1")).thenReturn(copy);
            when(copy.withTarInputStream(any(InputStream.class))).thenReturn(copy);
        }
    }
}
