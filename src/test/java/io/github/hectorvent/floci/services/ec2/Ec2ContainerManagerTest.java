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
import com.github.dockerjava.api.model.StreamType;
import java.nio.charset.StandardCharsets;
import com.github.dockerjava.api.model.NetworkSettings;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
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
import java.util.concurrent.CopyOnWriteArrayList;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class Ec2ContainerManagerTest {

    private static final String TEST_USER_DATA_OUTPUT = "test-output";
    private static final String TEST_CONTAINER_ID = "container-1";
    private static final String TEST_LOG_STREAM_NAME = "yyyy/MM/dd/user-data";
    private static final String TEST_SSH_PUBLIC_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITest test@floci";

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
    void exposeReachablePrivateAddressPreservesAllocatedIpWhenAwsFaithful() {
        // #1983: with awsFaithfulPrivateIp=true, the CFN/subnet-allocated private
        // IP set at launch is left untouched — the container bridge IP is not
        // reported (routing still uses it via containerBridgeIp, tracked elsewhere).
        Instance instance = new Instance();
        instance.setPrivateIpAddress("10.82.32.10");
        instance.setPrivateDnsName("ip-10-82-32-10.ec2.internal");

        InstanceNetworkInterface networkInterface = new InstanceNetworkInterface();
        networkInterface.setPrivateIpAddress("10.82.32.10");
        networkInterface.setPrivateDnsName("ip-10-82-32-10.ec2.internal");
        instance.setNetworkInterfaces(List.of(networkInterface));

        Ec2ContainerManager.exposeReachablePrivateAddress(instance, "192.168.215.21", true);

        assertEquals("10.82.32.10", instance.getPrivateIpAddress());
        assertEquals("ip-10-82-32-10.ec2.internal", instance.getPrivateDnsName());
        assertEquals("10.82.32.10", networkInterface.getPrivateIpAddress());
    }

    @Test
    void restoreMetadataRegistrationRegistersRunningPersistedContainer() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(true);

        DockerClient dockerClient = mock(DockerClient.class);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse response = inspectResponse("192.168.215.42");
        when(dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
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
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                metadataServer,
                mock(Ec2PortForwardManager.class));

        Instance instance = new Instance();
        instance.setInstanceId("i-restored");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
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
    void launchInstanceUserDataStreamToCloudWatch() throws Exception {
        LaunchHarness harness = launchHarness();
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));

        // manually set up container bridge IP
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse response = inspectResponse("192.168.215.42");
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);

        String instanceId = "i-userdatacloudwatch";
        Instance instance = instance(instanceId);
        instance.setUserData("""
                #!/bin/bash
                echo test
                """);
        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");
        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));

        verify(harness.logStreamer, timeout(2000)).streamToCloudWatchLogs(
            any(String.class), any(String.class), eq("us-west-2"), eq(TEST_USER_DATA_OUTPUT)
        );
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
    void launchSystemdGuestUsesInitInsteadOfTail() throws Exception {
        Ec2ContainerManager.containerBridgeIpAttempts = 1;
        Ec2ContainerManager.containerBridgeIpPollMillis = 1;
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse withIp = inspectResponse("172.18.0.11");
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(withIp);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        Instance instance = instance("i-systemd");

        harness.manager.launch(instance,
                new ResolvedAmiImage("floci/ami-ubuntu:24.04-arm64", ResolvedAmiImage.SYSTEMD_RUNTIME, true),
                null,
                "us-west-2");

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
        verify(harness.builder).withCmd(List.of("/sbin/init"));
        verify(harness.builder).withCgroupnsMode("host");
        verify(harness.builder).withBind("/sys/fs/cgroup", "/sys/fs/cgroup");
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
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(noIp).thenReturn(withIp);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));

        Instance instance = instance("i-waitip");

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
        assertEquals(TEST_CONTAINER_ID, instance.getDockerContainerId());
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
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(noIp);

        Instance instance = instance("i-noip");

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "terminated".equals(instance.getState().getName()), Duration.ofSeconds(2));
        assertEquals(TEST_CONTAINER_ID, instance.getDockerContainerId());
        verify(harness.metadataServer, never()).registerContainer(anyString(), anyString(), any());
    }

    @Test
    void launchMarksInstanceRunningBeforeUserDataCompletes() throws Exception {
        Ec2ContainerManager.containerBridgeIpAttempts = 2;
        Ec2ContainerManager.containerBridgeIpPollMillis = 1;
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse withIp = inspectResponse("172.18.0.10");
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
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

    @Test
    void launchCreatesSshdPrivilegeSeparationDirectoryBeforeStartingSshd() throws Exception {
        Ec2ContainerManager.containerBridgeIpAttempts = 1;
        Ec2ContainerManager.containerBridgeIpPollMillis = 1;
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse withIp = inspectResponse("172.18.0.12");
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(withIp);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));

        harness.manager.launch(instance("i-sshd"), "ubuntu:24.04", TEST_SSH_PUBLIC_KEY, "us-west-2");

        awaitUntil(() -> commandIndex(harness.executedCommands, "/usr/sbin/sshd") >= 0, Duration.ofSeconds(2));

        int mkdirIndex = commandIndex(harness.executedCommands, "mkdir", "-p", "/run/sshd");
        int sshdIndex = commandIndex(harness.executedCommands, "/usr/sbin/sshd");
        assertTrue(mkdirIndex >= 0,
                "sshd startup should create the /run/sshd privilege-separation directory");
        assertTrue(mkdirIndex < sshdIndex,
                "/run/sshd must be created before sshd starts, otherwise sshd exits with "
                        + "\"Missing privilege separation directory\"");
    }

    @Test
    void launchWithoutKeyPairStillStartsSshd() throws Exception {
        // Regression for #2184: sshd previously only started when a key pair was supplied, so
        // run-instances without --key-name left no daemon listening at all ("connection refused")
        // instead of matching real AWS AMIs, which run sshd as part of normal boot regardless of
        // whether a key pair is attached to the instance.
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse withIp = inspectResponse("172.18.0.20");
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(withIp);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        Instance instance = instance("i-nokeypair");

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
        ExecCreateCmd execCreate = harness.dockerClient.execCreateCmd(TEST_CONTAINER_ID);
        verify(execCreate, timeout(2000)).withCmd(eq(new String[]{"/usr/sbin/sshd"}));
        // No key pair was supplied, so nothing should have been written to authorized_keys.
        verify(harness.dockerClient, never()).copyArchiveToContainerCmd(TEST_CONTAINER_ID);
    }

    private static final String[] SSHD_INSTALL_PROBE_CMD = {"sh", "-c",
            "if ! command -v sshd >/dev/null 2>&1; then"
            + "  if command -v dnf >/dev/null 2>&1; then dnf install -y openssh-server >/dev/null 2>&1;"
            + "  elif command -v apt-get >/dev/null 2>&1; then DEBIAN_FRONTEND=noninteractive apt-get install -y openssh-server >/dev/null 2>&1;"
            + "  elif command -v apk >/dev/null 2>&1; then apk add --no-cache openssh >/dev/null 2>&1;"
            + "  fi;"
            + "fi;"
            + "command -v sshd >/dev/null 2>&1"};

    @Test
    void launchWhenSshdInstallFails_doesNotAttemptKeygenOrStart() throws Exception {
        // Regression reported as a follow-up on #2245 (duytanisme): sshd not starting even when a
        // key pair IS provided. Root cause: execInContainer() discards exit codes entirely, so a
        // shell if/elif chain with no matching package manager - or whose install command itself
        // failed (no network yet, apt lock held, etc.) - still exits 0 by bash convention with no
        // final check, meaning startSshd() proceeded to ssh-keygen and sshd regardless and still
        // logged success. This forces the install probe to report failure and asserts the later
        // steps are never even attempted, rather than running against a daemon that was never
        // installed.
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse withIp = inspectResponse("172.18.0.21");
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(withIp);

        ExecCreateCmd execCreate = mock(ExecCreateCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(harness.dockerClient.execCreateCmd(TEST_CONTAINER_ID)).thenReturn(execCreate);
        AtomicReference<String[]> currentCommand = new AtomicReference<>();
        when(execCreate.withCmd(any(String[].class))).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            currentCommand.set(args.length == 1 && args[0] instanceof String[] cmd
                    ? cmd : Arrays.copyOf(args, args.length, String[].class));
            return execCreate;
        });
        ExecCreateCmdResponse execResponse = mock(ExecCreateCmdResponse.class);
        when(execResponse.getId()).thenReturn("exec-1");
        when(execCreate.exec()).thenReturn(execResponse);

        when(harness.dockerClient.execStartCmd(anyString())).thenAnswer(invocation -> {
            ExecStartCmd execStart = mock(ExecStartCmd.class);
            when(execStart.exec(any())).thenAnswer(startInvocation -> {
                @SuppressWarnings("unchecked")
                ResultCallback<Frame> callback = startInvocation.getArgument(0);
                callback.onComplete();
                return callback;
            });
            return execStart;
        });

        // Every exec succeeds except the sshd-install probe script, simulating an image with none
        // of dnf/apt-get/apk, or whose install command itself failed.
        InspectExecCmd inspectExec = mock(InspectExecCmd.class);
        when(harness.dockerClient.inspectExecCmd(anyString())).thenReturn(inspectExec);
        when(inspectExec.exec()).thenAnswer(invocation -> {
            InspectExecResponse response = mock(InspectExecResponse.class);
            boolean isSshdInstallProbe = Arrays.equals(currentCommand.get(), SSHD_INSTALL_PROBE_CMD);
            when(response.getExitCodeLong()).thenReturn(isSshdInstallProbe ? 1L : 0L);
            return response;
        });

        CopyArchiveToContainerCmd copy = mock(CopyArchiveToContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(harness.dockerClient.copyArchiveToContainerCmd(TEST_CONTAINER_ID)).thenReturn(copy);
        when(copy.withTarInputStream(any(InputStream.class))).thenReturn(copy);

        Instance instance = instance("i-sshdinstallfail");

        harness.manager.launch(instance, "ubuntu:24.04", "ssh-ed25519 AAAAtest", "us-west-2");

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
        verify(execCreate, timeout(2000)).withCmd(eq(SSHD_INSTALL_PROBE_CMD));
        verify(execCreate, never()).withCmd(eq(new String[]{"ssh-keygen", "-A"}));
        verify(execCreate, never()).withCmd(eq(new String[]{"/usr/sbin/sshd"}));
    }

    private static LaunchHarness launchHarness() {
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(new ContainerSpec("ubuntu:24.04"));

        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.create(any(ContainerSpec.class))).thenReturn(TEST_CONTAINER_ID);
        when(lifecycleManager.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(true);

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
        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        Ec2ContainerManager manager = new Ec2ContainerManager(
                containerBuilder,
                lifecycleManager,
                logStreamer,
                mock(ContainerDetector.class),
                dockerHostResolver,
                dockerClient,
                portAllocator,
                config,
                metadataServer,
                mock(Ec2PortForwardManager.class));
        return new LaunchHarness(manager, dockerClient, metadataServer, logStreamer, builder,
                new CopyOnWriteArrayList<>());
    }

    /**
     * Index of the first exec matching {@code expected} exactly, or -1 when it was never run.
     */
    private static int commandIndex(List<String[]> executedCommands, String... expected) {
        for (int i = 0; i < executedCommands.size(); i++) {
            if (Arrays.equals(executedCommands.get(i), expected)) {
                return i;
            }
        }
        return -1;
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
                                 Ec2MetadataServer metadataServer,
                                 ContainerLogStreamer logStreamer,
                                 ContainerBuilder.Builder builder,
                                 List<String[]> executedCommands) {
        void stubSuccessfulExecs(CountDownLatch userDataStarted, CountDownLatch finishUserData) throws Exception {
            AtomicReference<String[]> currentCommand = new AtomicReference<>();
            ExecCreateCmd execCreate = mock(ExecCreateCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
            ExecCreateCmdResponse metadataExec = mock(ExecCreateCmdResponse.class);
            ExecCreateCmdResponse userDataExec = mock(ExecCreateCmdResponse.class);
            when(metadataExec.getId()).thenReturn("metadata-exec");
            when(userDataExec.getId()).thenReturn("userdata-exec");
            when(dockerClient.execCreateCmd(TEST_CONTAINER_ID)).thenReturn(execCreate);
            when(execCreate.withCmd(any(String[].class))).thenAnswer(invocation -> {
                Object[] args = invocation.getArguments();
                if (args.length == 1 && args[0] instanceof String[] command) {
                    currentCommand.set(command);
                } else {
                    currentCommand.set(Arrays.copyOf(args, args.length, String[].class));
                }
                executedCommands.add(currentCommand.get());
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
                        // test docker api frame
                        Frame frame = new Frame(StreamType.STDOUT, TEST_USER_DATA_OUTPUT.getBytes(StandardCharsets.UTF_8));
                        callback.onNext(frame);
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
            when(dockerClient.copyArchiveToContainerCmd(TEST_CONTAINER_ID)).thenReturn(copy);
            when(copy.withTarInputStream(any(InputStream.class))).thenReturn(copy);

            when(logStreamer.generateLogStreamName(anyString())).thenReturn(TEST_LOG_STREAM_NAME);
        }
    }
}
