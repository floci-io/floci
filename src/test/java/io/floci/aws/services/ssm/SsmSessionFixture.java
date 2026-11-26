package io.floci.aws.services.ssm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.command.BuildImageResultCallback;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.testing.AwsTestClients;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.SsmException;
import software.amazon.awssdk.services.ssm.model.StartSessionResponse;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Stock Linux agent with signed heartbeat readiness and, for sessions, an open control channel. */
final class SsmSessionFixture implements AutoCloseable {
    static final String IMAGE = "floci-ssm-session-test:local";
    static final String AGENT_VERSION = "3.3.5828.0";
    static final String ACCOUNT = "000000000000";
    static final Region REGION = Region.US_EAST_1;
    private static final ObjectMapper JSON = new ObjectMapper();

    final String name = "ssm-session-" + UUID.randomUUID();
    final DockerClient docker;
    final ContainerBuilder builder;
    final ContainerLifecycleManager lifecycle;
    final Ec2Service instances;
    final URI endpoint;
    final int tlsPort;
    final Path logs = Path.of("target", "ssm-session-logs", name);
    final List<String> documents = new ArrayList<>();
    final List<AccessKey> users = new ArrayList<>();
    final List<String> containers = new ArrayList<>();
    final List<String> networks = new ArrayList<>();
    final List<Session> sessions = new ArrayList<>();
    Ec2Client ec2;
    IamClient iam;
    SsmClient ssm;
    AccessKey operator;
    String instance;
    String guest;
    boolean roleCreated;
    boolean profileCreated;

    SsmSessionFixture(DockerClient docker, ContainerBuilder builder, ContainerLifecycleManager lifecycle,
                      Ec2Service instances, int httpPort, int tlsPort) {
        this.docker = docker;
        this.builder = builder;
        this.lifecycle = lifecycle;
        this.instances = instances;
        this.tlsPort = tlsPort;
        String gateway = docker.inspectNetworkCmd().withNetworkId("bridge").exec().getIpam().getConfig()
                .getFirst().getGateway();
        endpoint = URI.create("http://" + gateway + ":" + httpPort);
    }

    void start() throws Exception {
        Files.createDirectories(logs);
        try (BuildImageResultCallback callback = new BuildImageResultCallback()) {
            docker.buildImageCmd(Path.of("src/test/resources/ssm/session-manager").toFile())
                    .withTags(Set.of(IMAGE)).withRemove(true).exec(callback).awaitImageId(600, TimeUnit.SECONDS);
        }
        ec2 = AwsTestClients.create(Ec2Client.builder(), "test", REGION, endpoint);
        iam = AwsTestClients.create(IamClient.builder(), "test", REGION, endpoint);
        iam.createRole(r -> r.roleName(name).assumeRolePolicyDocument("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                """));
        roleCreated = true;
        iam.attachRolePolicy(r -> r.roleName(name).policyArn("arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"));
        iam.createInstanceProfile(r -> r.instanceProfileName(name));
        profileCreated = true;
        iam.addRoleToInstanceProfile(r -> r.instanceProfileName(name).roleName(name));
        operator = createUser("{\"Effect\":\"Allow\",\"Action\":[\"ssm:*\",\"ssmmessages:*\"],\"Resource\":\"*\"}");
        ssm = client(operator);
        String userData = """
                #!/bin/sh
                set -eu
                mkdir -p /etc/amazon/ssm /etc/systemd/system/amazon-ssm-agent.service.d
                printf '%%s' '{"Ssm":{"Endpoint":"%1$s"},"Mgs":{"Endpoint":"%2$s"},"Mds":{"Endpoint":"%1$s"},
                "Agent":{"TelemetryMetricsToSSM":false,"GlobalEnhancedTelemetryEnabled":false}}' \
                    > /etc/amazon/ssm/amazon-ssm-agent.json
                printf '[Service]\\nEnvironment=SSL_CERT_FILE=/etc/floci-ca-bundle.pem\\n' \
                    > /etc/systemd/system/amazon-ssm-agent.service.d/floci.conf
                cat > /etc/systemd/system/floci-session-echo.service <<'UNIT'
                [Unit]
                Description=Session Manager fixture loopback echo
                [Service]
                ExecStart=/usr/bin/socat TCP-LISTEN:18080,bind=127.0.0.1,reuseaddr,fork EXEC:cat
                [Install]
                WantedBy=multi-user.target
                UNIT
                systemctl daemon-reload
                systemctl enable --now amazon-ssm-agent floci-session-echo.service
                """.formatted("https://host.docker.internal:" + tlsPort,
                "https://host.docker.internal:" + tlsPort);
        instance = ec2.runInstances(r -> r.imageId("ami-0f10c155a90000001").instanceType("t3.micro")
                .minCount(1).maxCount(1).iamInstanceProfile(p -> p.name(name))
                .metadataOptions(m -> m.httpTokens("required").httpEndpoint("enabled"))
                .userData(Base64.getEncoder().encodeToString(userData.getBytes(StandardCharsets.UTF_8))))
                .instances().getFirst().instanceId();
        await().pollInterval(Duration.ofMillis(500)).atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            assertTrue(ssm.describeInstanceInformation(r -> r.filters(f -> f.key("InstanceIds").values(instance)))
                    .instanceInformationList().stream().anyMatch(i -> instance.equals(i.instanceId())
                            && AGENT_VERSION.equals(i.agentVersion()) && "Online".equals(i.pingStatusAsString())));
            assertEquals("connected",
                    ssm.getConnectionStatus(r -> r.target(instance)).statusAsString());
        });
        guest = instances.getInstance(REGION.id(), instance).orElseThrow().getDockerContainerId();
    }

    AccessKey createUser(String statements) {
        String user = name + "-" + users.size();
        iam.createUser(r -> r.userName(user));
        AccessKey key = iam.createAccessKey(r -> r.userName(user)).accessKey();
        users.add(key);
        iam.putUserPolicy(r -> r.userName(user).policyName("sessions")
                .policyDocument("{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}"));
        return key;
    }

    SsmClient client(AccessKey key) {
        return AwsTestClients.create(SsmClient.builder(), AwsBasicCredentials.create(key.accessKeyId(),
                key.secretAccessKey()), REGION, endpoint);
    }

    String document(String content, String type) {
        String document = name + "-doc-" + documents.size();
        ssm.createDocument(r -> r.name(document).documentType(type).documentFormat("JSON").content(content));
        documents.add(document);
        return document;
    }

    Session startSession(String document, Map<String, List<String>> parameters) throws Exception {
        return startSession(ssm, operator, document, parameters);
    }

    Session startSession(SsmClient client, AccessKey key, String document,
                         Map<String, List<String>> parameters) throws Exception {
        StartSessionResponse response = client.startSession(r -> r.target(instance).documentName(document)
                .parameters(parameters));
        Session session = new Session(client, key, response, document, parameters);
        sessions.add(session);
        assertEquals(endpoint.getHost(), URI.create(response.streamUrl()).getHost(),
                "Session stream must use the client-reachable request endpoint");
        return session;
    }

    String container(List<String> command) {
        String id = lifecycle.createAndStartEphemeral(builder.newContainer(IMAGE)
                .withName(name + "-client-" + containers.size()).withHostDockerInternalOnLinux()
                .withEntrypoint(command).build()).containerId();
        containers.add(id);
        return id;
    }

    record RemoteTarget(String container, String address, String guestAddress) { }

    RemoteTarget remoteTarget(boolean postgres) {
        String network = docker.createNetworkCmd().withName(name + "-remote-" + networks.size())
                .withInternal(true).exec().getId();
        networks.add(network);
        docker.connectToNetworkCmd().withNetworkId(network).withContainerId(guest).exec();
        ContainerBuilder.Builder spec = builder.newContainer(postgres ? "postgres:17" : IMAGE)
                .withName(name + "-remote-" + containers.size()).withDockerNetwork(Optional.of(network));
        if (postgres) {
            spec.withEnv("POSTGRES_PASSWORD", "session-test").withEnv("POSTGRES_DB", "session_test")
                    .withEntrypoint(List.of("docker-entrypoint.sh")).withCmd(List.of("postgres"));
        } else {
            spec.withEntrypoint(List.of("socat", "TCP-LISTEN:18080,reuseaddr,fork", "EXEC:cat"));
        }
        String id = lifecycle.createAndStartEphemeral(spec.build()).containerId();
        containers.add(id);
        String networkName = docker.inspectNetworkCmd().withNetworkId(network).exec().getName();
        String address = docker.inspectContainerCmd(id).exec().getNetworkSettings().getNetworks()
                .get(networkName).getIpAddress();
        String guestAddress = docker.inspectContainerCmd(guest).exec().getNetworkSettings().getNetworks()
                .get(networkName).getIpAddress();
        if (postgres) {
            // The image starts a temporary Unix socket server to initialise the database.
            await().atMost(Duration.ofSeconds(30))
                    .until(() -> exec(id, "pg_isready -h 127.0.0.1 -U postgres", 3).exitCode() == 0);
        }
        return new RemoteTarget(id, address, guestAddress);
    }

    ContainerExec.Result exec(String container, String script, int seconds) {
        return ContainerExec.run(docker, container, new String[]{"sh", "-c", script}, seconds);
    }

    String successfulExec(String container, String script) {
        ContainerExec.Result result = exec(container, script, 15);
        assertEquals(0L, result.exitCode(), result.summary());
        return result.stdout();
    }

    static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    final class Session implements AutoCloseable {
        final SsmClient owner;
        final AccessKey credentials;
        final StartSessionResponse response;
        final String request;
        String clientContainer;
        boolean terminated;
        boolean closed;

        Session(SsmClient owner, AccessKey credentials, StartSessionResponse response,
                String document, Map<String, List<String>> parameters) throws IOException {
            this.owner = owner;
            this.credentials = credentials;
            this.response = response;
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("Target", instance);
            if (document != null) {
                fields.put("DocumentName", document);
            }
            fields.put("Parameters", parameters);
            request = JSON.writeValueAsString(fields);
        }

        String pluginCommand() throws IOException {
            String payload = JSON.writeValueAsString(Map.of("SessionId", response.sessionId(),
                    "StreamUrl", response.streamUrl(), "TokenValue", response.tokenValue()));
            String host = endpoint.getHost();
            return "env AWS_ACCESS_KEY_ID=" + quote(credentials.accessKeyId())
                    + " AWS_SECRET_ACCESS_KEY=" + quote(credentials.secretAccessKey())
                    + " AWS_DEFAULT_REGION=us-east-1 AWS_EC2_METADATA_DISABLED=true"
                    + " AWS_SSM_PLUGIN_ADDITIONAL_STREAM_HOSTS=" + quote(host)
                    + " session-manager-plugin " + quote(payload) + " us-east-1 StartSession '' "
                    + quote(request) + " " + quote(endpoint.toString());
        }

        String clientContainer() {
            if (clientContainer == null) {
                clientContainer = container(List.of("sleep", "infinity"));
            }
            return clientContainer;
        }

        String shell(String input) throws Exception {
            // Keep stdin open until the remote shell exits. The plugin returns immediately on local EOF.
            attach();
            send(input);
            awaitExit();
            return output();
        }

        void attach() throws Exception {
            attach("");
        }

        void attach(String environment) throws Exception {
            successfulExec(clientContainer(), "mkfifo /tmp/input; "
                    + "(exec 3<>/tmp/input; " + environment + " " + pluginCommand()
                    + " </tmp/input >/tmp/plugin.log 2>&1; echo $? >/tmp/plugin.exit) "
                    + ">/tmp/driver.log 2>&1 & echo $! >/tmp/plugin.pid");
        }

        void send(String input) {
            successfulExec(clientContainer, "printf %s " + quote(input) + " > /tmp/input");
        }

        String output() {
            return successfulExec(clientContainer, "cat /tmp/plugin.log");
        }

        void awaitOutput(String marker) {
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                String text = output();
                assertTrue(text.contains(marker), text);
            });
        }

        void awaitExit() {
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertEquals("0", successfulExec(clientContainer, "cat /tmp/plugin.exit").trim()));
        }

        void terminate() {
            owner.terminateSession(r -> r.sessionId(response.sessionId()));
            terminated = true;
        }

        @Override
        public void close() throws Exception {
            if (closed) {
                return;
            }
            try {
                if (clientContainer != null) {
                    ContainerExec.Result result = exec(clientContainer, "cat /tmp/plugin.log 2>/dev/null || true", 5);
                    Files.writeString(logs.resolve(response.sessionId() + ".log"), result.stdout() + result.stderr());
                }
            } finally {
                if (!terminated) {
                    try {
                        terminate();
                    } catch (SsmException e) {
                        if (!"ValidationException".equals(e.awsErrorDetails().errorCode())) {
                            throw e;
                        }
                        // A shell that exited normally is already terminated.
                    }
                }
                closed = true;
            }
        }
    }

    @Override
    public void close() throws Exception {
        List<Executable> cleanup = new ArrayList<>();
        for (Session session : sessions) {
            cleanup.add(session::close);
        }
        if (instance != null) {
            cleanup.add(() -> {
                String id = instances.getInstance(REGION.id(), instance).orElseThrow().getDockerContainerId();
                ContainerExec.Result log = exec(id, "cat /var/log/amazon/ssm/amazon-ssm-agent.log", 5);
                Files.writeString(logs.resolve("agent.log"), log.stdout() + log.stderr());
            });
            cleanup.add(() -> {
                String id = instances.getInstance(REGION.id(), instance).orElseThrow().getDockerContainerId();
                ec2.terminateInstances(r -> r.instanceIds(instance));
                await().atMost(Duration.ofSeconds(30)).until(() -> docker.listContainersCmd()
                        .withShowAll(true).withIdFilter(List.of(id)).exec().isEmpty());
            });
        }
        for (String container : containers) {
            cleanup.add(() -> lifecycle.removeIfExistsStrict(container, true));
        }
        for (String network : networks) {
            cleanup.add(() -> docker.removeNetworkCmd(network).exec());
        }
        for (String document : documents) {
            cleanup.add(() -> ssm.deleteDocument(r -> r.name(document)));
        }
        for (AccessKey key : users) {
            cleanup.add(() -> iam.deleteUserPolicy(r -> r.userName(key.userName()).policyName("sessions")));
            cleanup.add(() -> iam.deleteAccessKey(r -> r.userName(key.userName()).accessKeyId(key.accessKeyId())));
            cleanup.add(() -> iam.deleteUser(r -> r.userName(key.userName())));
        }
        if (profileCreated) {
            cleanup.add(() -> iam.removeRoleFromInstanceProfile(r -> r.instanceProfileName(name).roleName(name)));
            cleanup.add(() -> iam.deleteInstanceProfile(r -> r.instanceProfileName(name)));
        }
        if (roleCreated) {
            cleanup.add(() -> iam.detachRolePolicy(r -> r.roleName(name)
                    .policyArn("arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore")));
            cleanup.add(() -> iam.deleteRole(r -> r.roleName(name)));
        }
        try {
            assertAll("SSM fixture cleanup", cleanup);
        } finally {
            if (ssm != null) {
                ssm.close();
            }
            if (iam != null) {
                iam.close();
            }
            if (ec2 != null) {
                ec2.close();
            }
        }
    }
}
