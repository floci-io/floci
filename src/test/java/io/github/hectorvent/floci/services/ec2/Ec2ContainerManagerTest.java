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
import io.github.hectorvent.floci.core.common.RegionResolver;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
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
                mock(Ec2PortForwardManager.class),
                mock(RegionResolver.class));

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
    void launchLabelsContainerWithResourceIdentity() throws Exception {
        LaunchHarness harness = launchHarness();
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));

        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse response = inspectResponse("192.168.215.42");
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);

        Instance instance = instance("i-labeltest");
        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        verify(harness.builder, timeout(2000)).withLabels(Map.of(
                "io.floci", "aws",
                "io.floci.service", "ec2",
                "io.floci.resource-id", "i-labeltest",
                "io.floci.account", "000000000000",
                "io.floci.region", "us-west-2"));
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
    void sshdInstallProbeAlsoInstallsTheSshClientPackage() {
        // Packer's default file transfer runs scp *on the instance*, so a guest with only
        // openssh-server fails the shell provisioner with "SCP failed to start. This usually
        // means that SCP is not properly installed on the remote system." Real AMIs ship the
        // client; installing only the server leaves sftp-server present but /usr/bin/scp
        // absent. Package names verified against the images themselves: openssh-clients on
        // rpm distributions, openssh-client on Debian, and apk's openssh already has both.
        String script = SSHD_INSTALL_PROBE_CMD[2];

        assertTrue(script.contains("openssh-server openssh-clients"), script);
        assertTrue(script.contains("openssh-server openssh-client >"), script);
        // The guard has to consider scp as well, or an image that already has sshd but no
        // client skips the install and provisioning still fails with nothing in the logs.
        assertTrue(script.contains("! command -v scp"), script);
        // ...and so does the trailing status check, on its own exit code. Ending the script on
        // "command -v sshd" alone would report a guest that has the server but whose client
        // install failed as a success, which is the state this guard was widened to catch.
        assertTrue(script.endsWith("command -v sshd >/dev/null 2>&1 || exit 1;"
                + "command -v scp >/dev/null 2>&1 || exit "
                + Ec2ContainerManager.SSH_CLIENT_MISSING_EXIT_CODE), script);
    }

    @Test
    void sshdInstallProbeHandlesYumForAmazonLinux2() {
        // public.ecr.aws/amazonlinux/amazonlinux:2 ships only yum -- no dnf, no apt-get, no
        // apk. It is NOT the default image (image-catalog.yaml pins that to amazonlinux:2023,
        // which has dnf); it is reached by explicitly requesting ami-amazonlinux2. Without a
        // yum branch the if/elif chain falls through, the trailing "command -v sshd" fails,
        // and startSshd() returns early, so such an instance is never reachable over SSH.
        //
        // Asserted against the production accessor, not the SSHD_INSTALL_PROBE_CMD copy below,
        // so removing the yum branch fails THIS test rather than only the withCmd verify.
        String script = Ec2ContainerManager.sshdInstallProbeCommand()[2];

        assertTrue(script.contains("command -v yum"), script);
        // The yum branch installs the client package alongside the server, exactly as the dnf
        // branch does -- an AL2 guest needs scp for the same reason every other guest does.
        assertTrue(script.contains("yum install -y openssh-server openssh-clients"), script);
        // dnf must still win where both exist (Amazon Linux 2023, modern Fedora/RHEL), where
        // yum is only a compatibility shim over dnf.
        assertTrue(script.indexOf("command -v dnf") < script.indexOf("command -v yum"),
                "dnf must be probed before yum");
    }

    @Test
    void metadataProxyInstallCommandHandlesYumForAmazonLinux2() {
        // The IMDS proxy dependency install had the same gap, and unlike the sshd probe it
        // ends in an explicit failure: on an ami-amazonlinux2 instance it reached the else
        // branch and exited 1 with "No supported package manager found for IMDS proxy
        // dependencies", leaving the instance without a link-local metadata endpoint.
        String script = Ec2ContainerManager.metadataProxyInstallCommand()[2];

        assertTrue(script.contains("command -v yum"), script);
        assertTrue(script.contains("yum install -y iproute socat curl ca-certificates"), script);
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
        verify(harness.lifecycleManager, timeout(2_000)).removeIfExists(TEST_CONTAINER_ID);
        verify(harness.portAllocator, timeout(2_000)).release(2201);
        assertNull(instance.getDockerContainerId());
        verify(harness.metadataServer, never()).registerContainer(anyString(), anyString(), any());
    }

    @Test
    void launchRetriesWithNextPortWhenDockerReportsHostPortCollision() throws Exception {
        Ec2ContainerManager.containerBridgeIpAttempts = 1;
        Ec2ContainerManager.containerBridgeIpPollMillis = 1;
        LaunchHarness harness = launchHarness();
        when(harness.portAllocator.allocate(anyInt(), anyInt())).thenReturn(2201, 2202);
        when(harness.lifecycleManager.create(any(ContainerSpec.class)))
                .thenReturn("container-conflict", TEST_CONTAINER_ID);
        when(harness.lifecycleManager.startCreated(anyString(), any(ContainerSpec.class)))
                .thenThrow(new RuntimeException("Bind for 0.0.0.0:2201 failed: port is already allocated"))
                .thenReturn(null);

        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse withIp = inspectResponse("172.18.0.12");
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(withIp);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));

        Instance instance = instance("i-port-collision");
        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
        assertEquals(2202, instance.getSshHostPort());
        verify(harness.lifecycleManager).removeIfExists("container-conflict");
        verify(harness.portAllocator).markReserved(2201);
        verify(harness.builder).withPortBinding(22, 2201);
        verify(harness.builder).withPortBinding(22, 2202);
    }

    @Test
    void launchReleasesPortAndCleansUpContainerAfterNonRetryableStartFailure() throws Exception {
        LaunchHarness harness = launchHarness();
        when(harness.lifecycleManager.startCreated(eq(TEST_CONTAINER_ID), any(ContainerSpec.class)))
                .thenThrow(new RuntimeException("Docker daemon unavailable"));

        Instance instance = instance("i-start-failure");
        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "terminated".equals(instance.getState().getName()), Duration.ofSeconds(2));
        verify(harness.lifecycleManager).removeIfExists(TEST_CONTAINER_ID);
        verify(harness.portAllocator).release(2201);
    }

    @Test
    void launchTerminatesBeforeCleanupFailure() throws Exception {
        LaunchHarness harness = launchHarness();
        when(harness.lifecycleManager.startCreated(eq(TEST_CONTAINER_ID), any(ContainerSpec.class)))
                .thenThrow(new RuntimeException("Docker daemon unavailable"));
        doThrow(new RuntimeException("port-forward cleanup failed"))
                .when(harness.portForwardManager).unpublishAll(any(Instance.class));

        Instance instance = instance("i-cleanup-failure");
        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "terminated".equals(instance.getState().getName()), Duration.ofSeconds(2));
        verify(harness.portForwardManager).unpublishAll(instance);
    }

    @Test
    void cancelledLaunchRemovesContainerThatStartsAfterCancellation() throws Exception {
        LaunchHarness harness = launchHarness();
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch allowStart = new CountDownLatch(1);
        when(harness.lifecycleManager.startCreated(eq(TEST_CONTAINER_ID), any(ContainerSpec.class))).thenAnswer(invocation -> {
            startEntered.countDown();
            assertTrue(allowStart.await(2, TimeUnit.SECONDS));
            return null;
        });

        Instance instance = instance("i-cancelled-launch");
        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        assertTrue(startEntered.await(2, TimeUnit.SECONDS), "container startup should begin");
        assertTrue(harness.manager.cancelLaunch(instance));
        verify(harness.lifecycleManager).removeIfExists(TEST_CONTAINER_ID);
        verify(harness.portAllocator).release(2201);

        allowStart.countDown();
        verify(harness.portAllocator, after(500).times(1)).release(2201);
    }

    @Test
    void cancelledLaunchRemovesContainerCreatedAfterCancellation() throws Exception {
        LaunchHarness harness = launchHarness();
        CountDownLatch createEntered = new CountDownLatch(1);
        CountDownLatch allowCreate = new CountDownLatch(1);
        when(harness.lifecycleManager.create(any(ContainerSpec.class))).thenAnswer(invocation -> {
            createEntered.countDown();
            assertTrue(allowCreate.await(2, TimeUnit.SECONDS));
            return TEST_CONTAINER_ID;
        });

        Instance instance = instance("i-cancelled-during-create");
        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        assertTrue(createEntered.await(2, TimeUnit.SECONDS), "container creation should begin");
        assertTrue(harness.manager.cancelLaunch(instance));
        assertEquals("terminated", instance.getState().getName());

        allowCreate.countDown();
        verify(harness.lifecycleManager, timeout(2_000)).removeIfExists(TEST_CONTAINER_ID);
        verify(harness.portAllocator, timeout(2_000)).release(2201);
        verify(harness.lifecycleManager, never()).startCreated(eq(TEST_CONTAINER_ID), any(ContainerSpec.class));
        assertEquals("terminated", instance.getState().getName());
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
            "if ! command -v sshd >/dev/null 2>&1 || ! command -v scp >/dev/null 2>&1; then"
            + "  if command -v dnf >/dev/null 2>&1; then dnf install -y openssh-server openssh-clients >/dev/null 2>&1;"
            + "  elif command -v yum >/dev/null 2>&1; then yum install -y openssh-server openssh-clients >/dev/null 2>&1;"
            + "  elif command -v apt-get >/dev/null 2>&1; then DEBIAN_FRONTEND=noninteractive apt-get install -y openssh-server openssh-client >/dev/null 2>&1;"
            + "  elif command -v apk >/dev/null 2>&1; then apk add --no-cache openssh >/dev/null 2>&1;"
            + "  fi;"
            + "fi;"
            + "command -v sshd >/dev/null 2>&1 || exit 1;"
            + "command -v scp >/dev/null 2>&1 || exit 2"};

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
        ExecCreateCmd execCreate = launchWithSshdInstallProbeExitCode(1, "i-sshdinstallfail");

        verify(execCreate, timeout(2000)).withCmd(eq(SSHD_INSTALL_PROBE_CMD));
        verify(execCreate, never()).withCmd(eq(new String[]{"ssh-keygen", "-A"}));
        verify(execCreate, never()).withCmd(eq(new String[]{"/usr/sbin/sshd"}));
    }

    @Test
    void launchWhenOnlySshClientInstallFails_stillStartsSshd() throws Exception {
        // The probe's guard now also fires when scp is missing, so a guest that already has sshd
        // but whose client-package install fails must not be reported as a plain success - that is
        // exactly the state where Packer's default scp-based file transfer breaks. It is not fatal
        // either: sshd is present and the instance is worth making reachable. The script separates
        // the two with exit 2, which warns and carries on rather than returning early.
        ExecCreateCmd execCreate = launchWithSshdInstallProbeExitCode(
                Ec2ContainerManager.SSH_CLIENT_MISSING_EXIT_CODE, "i-scpinstallfail");

        verify(execCreate, timeout(2000)).withCmd(eq(SSHD_INSTALL_PROBE_CMD));
        verify(execCreate, timeout(2000)).withCmd(eq(new String[]{"ssh-keygen", "-A"}));
        verify(execCreate, timeout(2000)).withCmd(eq(new String[]{"/usr/sbin/sshd"}));
    }

    /**
     * Launches an instance where every exec succeeds except the sshd install probe, which reports
     * {@code probeExitCode}. Returns the {@link ExecCreateCmd} the launch issued its commands
     * through so callers can assert on which steps were attempted.
     */
    private ExecCreateCmd launchWithSshdInstallProbeExitCode(int probeExitCode, String instanceId)
            throws Exception {
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
            when(response.getExitCodeLong()).thenReturn(isSshdInstallProbe ? (long) probeExitCode : 0L);
            return response;
        });

        CopyArchiveToContainerCmd copy = mock(CopyArchiveToContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(harness.dockerClient.copyArchiveToContainerCmd(TEST_CONTAINER_ID)).thenReturn(copy);
        when(copy.withTarInputStream(any(InputStream.class))).thenReturn(copy);

        Instance instance = instance(instanceId);

        harness.manager.launch(instance, "ubuntu:24.04", "ssh-ed25519 AAAAtest", "us-west-2");

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
        return execCreate;
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
        Ec2PortForwardManager portForwardManager = mock(Ec2PortForwardManager.class);
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
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
                portForwardManager,
                regionResolver);
        return new LaunchHarness(manager, lifecycleManager, dockerClient, metadataServer, logStreamer, builder,
                portAllocator, portForwardManager, new CopyOnWriteArrayList<>());
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
                                 ContainerLifecycleManager lifecycleManager,
                                 DockerClient dockerClient,
                                 Ec2MetadataServer metadataServer,
                                 ContainerLogStreamer logStreamer,
                                 ContainerBuilder.Builder builder,
                                 PortAllocator portAllocator,
                                 Ec2PortForwardManager portForwardManager,
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
