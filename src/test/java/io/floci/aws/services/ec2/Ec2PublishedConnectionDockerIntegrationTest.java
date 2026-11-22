package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.IpPermission;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(Ec2PublishedConnectionDockerIntegrationTest.Profile.class)
@EnabledIfEnvironmentVariable(named = "FLOCI_TEST_EC2_PUBLISHING", matches = "true")
class Ec2PublishedConnectionDockerIntegrationTest {
    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ec2.mock", "false",
                    "floci.network.security-group-enforcement.enabled", "true",
                    "floci.docker.resource-namespace", "ec2-published-connection-test");
        }
    }

    @Inject Ec2Service service;
    @Inject DockerClient docker;

    @Test
    void revocationClosesListenerAndPreservesEstablishedSocket() throws Exception {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "000000000000", Region.US_EAST_1)) {
            String group = ec2.createSecurityGroup(r -> r.groupName("echo-" + UUID.randomUUID())
                    .description("Published echo test")).groupId();
            try {
                String id = ec2.runInstances(r -> r.imageId("ami-alpine").instanceType("t3.micro").minCount(1)
                        .maxCount(1).securityGroupIds(group)).instances().getFirst().instanceId();
                try {
                    await().atMost(Duration.ofMinutes(2)).until(() -> service.getInstance("us-east-1", id)
                            .map(i -> "running".equals(i.getState().getName())).orElse(false));
                    Instance instance = service.getInstance("us-east-1", id).orElseThrow();
                    ContainerExec.Result echo = ContainerExec.run(docker, instance.getDockerContainerId(),
                            new String[]{"sh", "-c", "nc -lk -p 8080 -e cat </dev/null >/dev/null 2>&1 &"}, 5);
                    assertEquals(0, echo.exitCode(), echo.summary());
                    IpPermission rule = IpPermission.builder().ipProtocol("tcp").fromPort(8080).toPort(8080)
                            .ipRanges(r -> r.cidrIp("0.0.0.0/0")).build();
                    ec2.authorizeSecurityGroupIngress(r -> r.groupId(group).ipPermissions(rule));
                    int port = await().atMost(Duration.ofSeconds(30)).until(
                            () -> service.getInstance("us-east-1", id).orElseThrow().getPublishedPorts().get(8080),
                            Objects::nonNull);
                    try (Socket tracked = new Socket()) {
                        tracked.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                        tracked.setSoTimeout(3000);
                        assertEquals(1, echoByte(tracked, 1));
                        ec2.revokeSecurityGroupIngress(r -> r.groupId(group).ipPermissions(rule));
                        assertFalse(service.getInstance("us-east-1", id).orElseThrow().getPublishedPorts()
                                .containsKey(8080));
                        try (Socket fresh = new Socket()) {
                            fresh.setSoTimeout(3000);
                            try {
                                fresh.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                                assertEquals(-1, echoByte(fresh, 2));
                            } catch (IOException expected) {
                                // Docker Desktop may refuse or reset the new connection.
                            }
                        }
                        assertEquals(3, echoByte(tracked, 3));
                    }
                } finally {
                    ec2.terminateInstances(r -> r.instanceIds(id));
                }
            } finally {
                await().atMost(Duration.ofSeconds(60)).ignoreExceptions()
                        .until(() -> ec2.deleteSecurityGroup(r -> r.groupId(group)).sdkHttpResponse().isSuccessful());
            }
        }
    }

    private static int echoByte(Socket socket, int value) throws IOException {
        socket.getOutputStream().write(value);
        return socket.getInputStream().read();
    }
}
