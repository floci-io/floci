package io.floci.aws.services.ssm;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.config.SelfSignedTlsProfile;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.TestAccounts;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.InvocationDoesNotExistException;
import software.amazon.awssdk.services.ssm.model.ResumeSessionResponse;
import software.amazon.awssdk.services.ssm.model.Session;
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
@TestProfile(SsmSessionManagerDockerNightlyIntegrationTest.Profile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
class SsmSessionManagerDockerNightlyIntegrationTest {
    public static class Profile extends SelfSignedTlsProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> settings = new HashMap<>(super.getConfigOverrides());
            settings.put("floci.services.ec2.mock", "false");
            Path catalog = Path.of("target/ssm-session-image-catalog.yaml").toAbsolutePath();
            try {
                String contents = Files.readString(Path.of("src/test/resources/ssm/session-manager/image-catalog.yaml"));
                if ("aarch64".equals(System.getProperty("os.arch"))) {
                    contents = contents.replace("architecture: x86_64", "architecture: arm64");
                }
                Files.writeString(catalog, contents);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot prepare the Session Manager test AMI catalogue", e);
            }
            settings.put("floci.services.ec2.image-catalog-path", catalog.toString());
            settings.put("floci.auth.validate-signatures", "true");
            settings.put("floci.services.iam.enforcement-enabled", "true");
            settings.put("floci.network.security-group-enforcement.enabled", "false");
            settings.put("floci.services.ec2.volume-block-devices", "false");
            return settings;
        }
    }

    private static final String SHELL_DOCUMENT = """
            {"schemaVersion":"1.0","sessionType":"Standard_Stream",
            "parameters":{"sample":{"type":"String","default":"example","allowedPattern":"^[a-z]+$"},
            "requiredSample":{"type":"String","allowedPattern":"^[a-z]+$"}},
            "inputs":{"runAsEnabled":false,"shellProfile":{"linux":
            "printf 'PROFILE=%s:%s\\n' '{{ sample }}' '{{ requiredSample }}'"}}}
            """;

    @Inject DockerClient docker;
    @Inject ContainerBuilder builder;
    @Inject ContainerLifecycleManager lifecycle;
    @Inject Ec2Service instances;
    @Inject IamService identities;
    @ConfigProperty(name = "quarkus.http.test-ssl-port") int tlsPort;
    @TestHTTPResource URI httpEndpoint;

    private SsmSessionFixture fixture;

    @BeforeAll
    @Timeout(720)
    void startGuest() throws Exception {
        fixture = new SsmSessionFixture(docker, builder, lifecycle, instances, httpEndpoint.getPort(), tlsPort);
        fixture.start();
    }

    @AfterAll
    @Timeout(120)
    void stopGuest() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void shellRunsInsideGuestAndExitsCleanly() throws Exception {
        String guestHostname = fixture.successfulExec(fixture.guest, "hostname").trim();
        try (SsmSessionFixture.Session session = fixture.startSession(null, Map.of())) {
            String output = session.shell("printf 'HOST=%s\\n' \"$(hostname)\"; printf 'USER=%s\\n' \"$(id -un)\"; exit\n");
            assertTrue(output.contains("HOST=" + guestHostname), output);
            assertTrue(output.contains("USER=ssm-user"), output);
            awaitHistory(session.response.sessionId());
        }
    }

    @Test
    void startListsSessionBeforeClientAttaches() throws Exception {
        try (SsmSessionFixture.Session session = fixture.startSession(null, Map.of())) {
            Session listed = fixture.ssm.describeSessions(r -> r.state("Active")
                    .filters(f -> f.key("SessionId").value(session.response.sessionId())))
                    .sessions().getFirst();
            assertEquals(session.response.sessionId(), listed.sessionId());
            assertEquals(fixture.instance, listed.target());
            assertEquals("Connected", listed.statusAsString());
            assertNotNull(listed.startDate());
        }
    }

    @Test
    void connectedSessionCanBeResumedWithoutClosingItsShell() throws Exception {
        try (SsmSessionFixture.Session session = fixture.startSession(null, Map.of())) {
            session.attach();
            session.send("export CANARY=still-running; printf 'BEFORE=%s\\n' \"$CANARY\"\n");
            session.awaitOutput("BEFORE=still-running");
            ResumeSessionResponse resumed = fixture.ssm.resumeSession(
                    r -> r.sessionId(session.response.sessionId()));
            assertEquals(session.response.sessionId(), resumed.sessionId());
            assertNotEquals(session.response.tokenValue(), resumed.tokenValue());
            session.send("printf 'AFTER=%s\\n' \"$CANARY\"; exit\n");
            session.awaitOutput("AFTER=still-running");
            session.awaitExit();
        }
    }

    @Test
    void terminationClosesAttachedClientAndPreventsResume() throws Exception {
        try (SsmSessionFixture.Session session = fixture.startSession(null, Map.of())) {
            session.attach();
            session.send("printf 'READY=%s\\n' ready\n");
            session.awaitOutput("READY=ready");
            session.terminate();
            session.awaitExit();
            awaitHistory(session.response.sessionId());
            assertError("ValidationException", () -> fixture.ssm.resumeSession(r -> r.sessionId(session.response.sessionId())));
            assertError("ValidationException", () -> fixture.ssm.terminateSession(r -> r.sessionId(session.response.sessionId())));
        }
    }

    @Test
    void simultaneousSessionsHaveIndependentShellState() throws Exception {
        try (SsmSessionFixture.Session first = fixture.startSession(null, Map.of());
             SsmSessionFixture.Session second = fixture.startSession(null, Map.of())) {
            assertNotEquals(first.response.sessionId(), second.response.sessionId());
            first.attach();
            second.attach();
            first.send("export CANARY=first; printf 'STATE=%s\\n' \"$CANARY\"\n");
            second.send("printf 'STATE=%s\\n' \"${CANARY-unset}\"; exit\n");
            first.awaitOutput("STATE=first");
            second.awaitOutput("STATE=unset");
            first.terminate();
            first.awaitExit();
            second.awaitExit();
        }
    }

    @Test
    void localForwardingCarriesConcurrentConnectionsToGuestLoopback() throws Exception {
        try (SsmSessionFixture.Session session = fixture.startSession("AWS-StartPortForwardingSession",
                Map.of("portNumber", List.of("18080"), "localPortNumber", List.of("15432")))) {
            session.attach();
            session.awaitOutput("Port 15432 opened");
            assertEquals("firstsecond", forwardedEcho(session, "first", "second"));
            session.terminate();
            session.awaitExit();
        }
    }

    @Test
    void remoteForwardingReachesPrivateDestinationFromGuest() throws Exception {
        SsmSessionFixture.RemoteTarget target = fixture.remoteTarget(false);
        try (SsmSessionFixture.Session session = fixture.startSession("AWS-StartPortForwardingSessionToRemoteHost",
                Map.of("host", List.of(target.address()), "portNumber", List.of("18080"),
                        "localPortNumber", List.of("15432")))) {
            assertNotEquals(0L, fixture.exec(session.clientContainer(),
                    "printf direct | socat -T 1 - TCP:" + target.address() + ":18080,connect-timeout=1", 3).exitCode(),
                    "Remote destination must not be directly reachable from the client");
            session.attach();
            session.awaitOutput("Port 15432 opened");
            assertEquals("firstsecond", forwardedEcho(session, "first", "second"));
        }
    }

    @Test
    void postgresQueryTravelsThroughGuest() throws Exception {
        SsmSessionFixture.RemoteTarget target = fixture.remoteTarget(true);
        try (SsmSessionFixture.Session session = fixture.startSession("AWS-StartPortForwardingSessionToRemoteHost",
                Map.of("host", List.of(target.address()), "portNumber", List.of("5432"),
                        "localPortNumber", List.of("15432")))) {
            session.attach();
            session.awaitOutput("Port 15432 opened");
            String output = fixture.successfulExec(session.clientContainer,
                    "PGPASSWORD=session-test PGCONNECT_TIMEOUT=5 psql -h 127.0.0.1 -p 15432 "
                            + "-U postgres -d session_test -Atc 'select inet_client_addr(), 6 * 7'");
            assertEquals(target.guestAddress() + "|42", output.trim());
        }
    }

    @Test
    void sshAuthenticatesAndRunsCommandWithoutLocalTerminal() throws Exception {
        try (SsmSessionFixture.Session session = fixture.startSession("AWS-StartSSHSession", Map.of())) {
            String client = session.clientContainer();
            fixture.successfulExec(client, "ssh-keygen -q -t ed25519 -N '' -f /tmp/client-key");
            String publicKey = fixture.successfulExec(client, "cat /tmp/client-key.pub");
            fixture.successfulExec(fixture.guest, "printf %s " + SsmSessionFixture.quote(publicKey)
                    + " > /home/session-test/.ssh/authorized_keys; chown -R session-test:session-test "
                    + "/home/session-test/.ssh; chmod 700 /home/session-test/.ssh; "
                    + "chmod 600 /home/session-test/.ssh/authorized_keys");
            String hostKey = fixture.successfulExec(fixture.guest, "cat /etc/ssh/ssh_host_ed25519_key.pub");
            fixture.successfulExec(client, "printf %s " + SsmSessionFixture.quote("session-target " + hostKey)
                    + " > /tmp/known_hosts; printf %s "
                    + SsmSessionFixture.quote("#!/bin/sh\nexec " + session.pluginCommand() + "\n")
                    + " > /tmp/proxy; chmod 700 /tmp/proxy");
            String output = fixture.successfulExec(client,
                    "ssh -T -o BatchMode=yes -o ConnectTimeout=10 -o StrictHostKeyChecking=yes "
                            + "-o UserKnownHostsFile=/tmp/known_hosts -o ProxyCommand=/tmp/proxy "
                            + "-i /tmp/client-key session-test@session-target 'id -un'");
            assertEquals("session-test", output.trim());
        }
    }

    @Test
    void stockPluginResumesSameShellAfterTransportLoss() throws Exception {
        try (SessionConnectProxy proxy = new SessionConnectProxy(fixture.endpoint);
             SsmSessionFixture.Session session = fixture.startSession(null, Map.of())) {
            session.attach("HTTPS_PROXY=http://host.docker.internal:" + proxy.port()
                    + " HTTP_PROXY=http://host.docker.internal:" + proxy.port() + " NO_PROXY=");
            session.send("export CANARY=retained; printf 'SHELL=%s:%s\\n' \"$$\" \"$CANARY\"\n");
            await().atMost(Duration.ofSeconds(30)).until(() -> proxy.connections() > 0);
            session.awaitOutput(":retained");
            String before = session.output().lines().filter(line -> line.matches("SHELL=[0-9]+:retained\\r?"))
                    .findFirst().orElseThrow().trim();
            int connections = proxy.connections();
            proxy.disconnect();
            await().atMost(Duration.ofSeconds(30)).until(() -> proxy.connections() > connections);
            session.send("printf 'SHELL=%s:%s\\n' \"$$\" \"$CANARY\"; exit\n");
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertEquals(2,
                    session.output().lines().map(String::trim).filter(before::equals).count()));
            session.awaitExit();
        }
    }

    @Test
    void runCommandStillWorksWithConnectedSessionAgent() throws Exception {
        try (SsmSessionFixture.Session session = fixture.startSession(null, Map.of())) {
            session.attach();
            session.send("printf 'SESSION=%s\\n' connected\n");
            session.awaitOutput("SESSION=connected");
            String command = fixture.ssm.sendCommand(r -> r.instanceIds(fixture.instance)
                    .documentName("AWS-RunShellScript").parameters(Map.of("commands", List.of("printf command-output"))))
                    .command().commandId();
            await().ignoreException(InvocationDoesNotExistException.class).atMost(Duration.ofSeconds(30))
                    .untilAsserted(() -> assertEquals("command-output",
                    fixture.ssm.getCommandInvocation(r -> r.commandId(command).instanceId(fixture.instance))
                            .standardOutputContent()));
        }
    }

    @Test
    void customShellUsesDefaultAndSuppliedParameters() throws Exception {
        String document = fixture.document(SHELL_DOCUMENT, "Session");
        try (SsmSessionFixture.Session session = fixture.startSession(document,
                Map.of("requiredSample", List.of("given")))) {
            assertTrue(session.shell("exit\n").contains("PROFILE=example:given"));
        }
    }

    @Test
    void customPortDocumentAppliesDefaultPort() throws Exception {
        String document = fixture.document("""
                {"schemaVersion":"1.0","sessionType":"Port","parameters":{
                "portNumber":{"type":"String","default":"18080"},
                "localPortNumber":{"type":"String","default":"15432"}},
                "properties":{"type":"LocalPortForwarding","portNumber":"{{ portNumber }}",
                "localPortNumber":"{{ localPortNumber }}"}}
                """, "Session");
        try (SsmSessionFixture.Session session = fixture.startSession(document, Map.of())) {
            session.attach();
            session.awaitOutput("Port 15432 opened");
            assertEquals("default-port", forwardedEcho(session, "default-port"));
        }
    }

    @Test
    void accountShellPreferencesOverrideImplicitFallback() throws Exception {
        String name = "SSM-SessionManagerRunShell";
        fixture.ssm.createDocument(r -> r.name(name).documentType("Session").content("""
                {"schemaVersion":"1.0","sessionType":"Standard_Stream",
                "inputs":{"runAsEnabled":false,"shellProfile":{"linux":"export DEFAULT_CANARY=account-default"}}}
                """));
        try (SsmSessionFixture.Session session = fixture.startSession(null, Map.of())) {
            assertTrue(session.shell("printf 'PREFERENCE=%s\\n' \"$DEFAULT_CANARY\"; exit\n")
                    .contains("PREFERENCE=account-default"));
        } finally {
            fixture.ssm.deleteDocument(r -> r.name(name));
        }
    }

    static Stream<Map<String, List<String>>> invalidParameters() {
        return Stream.of(Map.of(), Map.of("requiredSample", List.of("UPPER")),
                Map.of("requiredSample", List.of("a", "b")), Map.of("requiredSample", List.of()),
                Map.of("requiredSample", List.of("valid"), "unknown", List.of("value")),
                Map.of("RequiredSample", List.of("valid")));
    }

    @ParameterizedTest
    @MethodSource("invalidParameters")
    void rejectsInvalidDocumentParameters(Map<String, List<String>> parameters) {
        String document = fixture.document(SHELL_DOCUMENT, "Session");
        assertError("InvalidParameters", () -> fixture.ssm.startSession(r -> r.target(fixture.instance)
                .documentName(document).parameters(parameters)));
    }

    @Test
    void explicitMissingDefaultDocumentIsNotImplicitShellFallback() {
        assertError("InvalidDocument", () -> fixture.ssm.startSession(r -> r.target(fixture.instance)
                .documentName("SSM-SessionManagerRunShell")));
    }

    @Test
    void commandDocumentCannotStartSession() {
        String document = fixture.document("{\"schemaVersion\":\"2.2\",\"mainSteps\":[]}", "Command");
        assertError("InvalidDocument", () -> fixture.ssm.startSession(r -> r.target(fixture.instance).documentName(document)));
    }

    @Test
    void anotherAccountCannotSeeOrStartSessionsForGuest() throws Exception {
        String account = TestAccounts.randomId();
        String user = fixture.name + "-foreign";
        // The emulator and SDK each define an AccessKey type.
        io.floci.aws.services.iam.model.AccessKey key = RequestScopes.callAs(account, () -> {
            identities.createUser(user, "/");
            identities.putUserPolicy(user, "sessions",
                    "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"ssm:*\",\"Resource\":\"*\"}]}");
            return identities.createAccessKey(user);
        });
        try (SsmSessionFixture.Session session = fixture.startSession(null, Map.of());
             SsmClient other = AwsTestClients.create(SsmClient.builder(), key.getAccessKeyId(), key.getSecretAccessKey(),
                     SsmSessionFixture.REGION, fixture.endpoint)) {
            assertTrue(other.describeSessions(r -> r.state("Active")).sessions().isEmpty());
            assertEquals("notconnected", other.getConnectionStatus(r -> r.target(fixture.instance)).statusAsString());
            assertError("TargetNotConnected", () -> other.startSession(r -> r.target(fixture.instance)));
        } finally {
            RequestScopes.runAs(account, () -> {
                identities.deleteUserPolicy(user, "sessions");
                identities.deleteAccessKey(user, key.getAccessKeyId());
                identities.deleteUser(user);
            });
        }
    }

    @Test
    void anotherRegionCannotSeeSessionsOrConnectToGuest() throws Exception {
        try (SsmSessionFixture.Session session = fixture.startSession(null, Map.of());
             SsmClient other = AwsTestClients.create(SsmClient.builder(), fixture.operator.accessKeyId(),
                     fixture.operator.secretAccessKey(), Region.EU_WEST_1, fixture.endpoint)) {
            assertTrue(other.describeSessions(r -> r.state("Active")).sessions().isEmpty());
            assertEquals("notconnected", other.getConnectionStatus(r -> r.target(fixture.instance)).statusAsString());
            assertError("TargetNotConnected", () -> other.startSession(r -> r.target(fixture.instance)));
        }
    }

    @Test
    void targetPermissionAloneDoesNotAuthoriseImplicitDefaultDocument() {
        AccessKey user = fixture.createUser("{\"Effect\":\"Allow\",\"Action\":\"ssm:StartSession\",\"Resource\":\""
                + targetArn() + "\"}");
        try (SsmClient restricted = fixture.client(user)) {
            assertError("AccessDeniedException", () -> restricted.startSession(r -> r.target(fixture.instance)));
        }
    }

    @Test
    void targetAndAmazonOwnedDocumentPermissionsAuthoriseSession() throws Exception {
        AccessKey user = fixture.createUser("{\"Effect\":\"Allow\",\"Action\":\"ssm:StartSession\",\"Resource\":[\""
                + targetArn() + "\",\"arn:aws:ssm:us-east-1::document/AWS-StartPortForwardingSession\"]},"
                + "{\"Effect\":\"Allow\",\"Action\":\"ssm:TerminateSession\",\"Resource\":\"*\"}");
        try (SsmClient restricted = fixture.client(user);
             SsmSessionFixture.Session session = fixture.startSession(restricted, user,
                     "AWS-StartPortForwardingSession", Map.of())) {
            assertFalse(session.response.sessionId().isBlank());
        }
    }

    @Test
    void documentPermissionAloneDoesNotAuthoriseTarget() {
        AccessKey user = fixture.createUser("{\"Effect\":\"Allow\",\"Action\":\"ssm:StartSession\","
                + "\"Resource\":\"arn:aws:ssm:us-east-1::document/AWS-StartSSHSession\"}");
        try (SsmClient restricted = fixture.client(user)) {
            assertError("AccessDeniedException", () -> restricted.startSession(r -> r.target(fixture.instance)
                    .documentName("AWS-StartSSHSession")));
        }
    }

    @Test
    void explicitDocumentDenyOverridesSessionAllow() {
        AccessKey user = fixture.createUser("{\"Effect\":\"Allow\",\"Action\":\"ssm:*\",\"Resource\":\"*\"},"
                + "{\"Effect\":\"Deny\",\"Action\":\"ssm:StartSession\","
                + "\"Resource\":\"arn:aws:ssm:us-east-1::document/AWS-StartSSHSession\"}");
        try (SsmClient restricted = fixture.client(user)) {
            assertError("AccessDeniedException", () -> restricted.startSession(r -> r.target(fixture.instance)
                    .documentName("AWS-StartSSHSession")));
        }
    }

    @Test
    void stoppedGuestRejectsNewSessionsAndReconnectsAfterRestart() throws Exception {
        fixture.ec2.stopInstances(r -> r.instanceIds(fixture.instance));
        try {
            awaitGuestState(InstanceStateName.STOPPED, "notconnected", Duration.ofSeconds(45));
            assertError("TargetNotConnected", () -> fixture.ssm.startSession(r -> r.target(fixture.instance)));
        } finally {
            fixture.ec2.startInstances(r -> r.instanceIds(fixture.instance));
            awaitGuestState(InstanceStateName.RUNNING, "connected", Duration.ofSeconds(60));
        }
        try (SsmSessionFixture.Session session = fixture.startSession(null, Map.of())) {
            assertTrue(session.shell("printf 'RESTORED=%s\\n' yes; exit\n").contains("RESTORED=yes"));
        }
    }

    private void awaitGuestState(InstanceStateName state, String connectionStatus, Duration timeout) {
        await().atMost(timeout).untilAsserted(() -> {
            assertEquals(state, fixture.ec2.describeInstances(r -> r.instanceIds(fixture.instance))
                    .reservations().getFirst().instances().getFirst().state().name());
            assertEquals(connectionStatus,
                    fixture.ssm.getConnectionStatus(r -> r.target(fixture.instance)).statusAsString());
        });
    }

    private String targetArn() {
        return "arn:aws:ec2:us-east-1:" + SsmSessionFixture.ACCOUNT + ":instance/" + fixture.instance;
    }

    private String forwardedEcho(SsmSessionFixture.Session session, String... payloads) {
        // The plugin closes a multiplexed stream on local EOF. Keep all connections open through the reads.
        String probe = """
                sockets=()
                for payload in "$@"; do
                    exec {socket}<>/dev/tcp/127.0.0.1/15432
                    sockets+=("$socket")
                    printf '%s' "$payload" >&"$socket"
                done
                index=0
                for payload in "$@"; do
                    IFS= read -r -t 5 -N "${#payload}" -u "${sockets[$index]}" response
                    printf '%s' "$response"
                    index=$((index + 1))
                done
                """;
        String arguments = String.join(" ", Stream.of(payloads).map(SsmSessionFixture::quote).toList());
        return fixture.successfulExec(session.clientContainer(),
                "LC_ALL=C timeout 8s bash -ec " + SsmSessionFixture.quote(probe) + " echo-probe " + arguments);
    }

    private void awaitHistory(String sessionId) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertTrue(fixture.ssm.describeSessions(r -> r.state("Active")
                    .filters(f -> f.key("SessionId").value(sessionId))).sessions().isEmpty());
            List<Session> entries = fixture.ssm.describeSessions(r -> r.state("History")
                    .filters(f -> f.key("SessionId").value(sessionId))).sessions();
            assertEquals(1, entries.size());
            Session history = entries.getFirst();
            assertEquals("Terminated", history.statusAsString());
            assertNotNull(history.endDate());
        });
    }

    private static void assertError(String code, Executable action) {
        SsmException error = assertThrows(SsmException.class, action);
        assertEquals(code, error.awsErrorDetails().errorCode());
    }
}
