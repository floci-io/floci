package io.github.hectorvent.floci.services.elasticache.proxy;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import io.github.hectorvent.floci.services.elasticache.model.TransitEncryption;
import io.github.hectorvent.floci.testing.DockerAvailability;
import io.github.hectorvent.floci.testing.TlsTrust;
import io.github.hectorvent.floci.testutil.IamServiceTestHelper;
import io.github.hectorvent.floci.testutil.SigV4TokenTestHelper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ElastiCacheAclProxyIntegrationTest {
    @TempDir
    static Path directory;

    private final AtomicReference<AuthMode> memberMode = new AtomicReference<>();
    private final AtomicReference<String> access = new AtomicReference<>();
    private String name;
    private ElastiCacheAuthProxy proxy;
    private SSLContext trust;

    private String docker(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(arguments));
        Path output = Files.createTempFile(directory, "docker-", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Docker command timed out");
        }
        assertEquals(0, process.exitValue(), () -> {
            try {
                return Files.readString(output);
            } catch (Exception e) {
                return "Docker command failed";
            }
        });
        return Files.readString(output).trim();
    }

    private static String command(Socket socket, String... values) throws Exception {
        StringBuilder request = new StringBuilder("*" + values.length + "\r\n");
        for (String value : values) {
            request.append('$').append(value.getBytes(StandardCharsets.UTF_8).length).append("\r\n")
                    .append(value).append("\r\n");
        }
        socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
    }

    private static Socket connect(int port, SSLContext trust) throws Exception {
        SSLSocket socket = (SSLSocket) trust.getSocketFactory().createSocket("127.0.0.1", port);
        socket.setSoTimeout(5000);
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        socket.startHandshake();
        return socket;
    }

    @BeforeAll
    void startBackend() throws Exception {
        Assumptions.assumeTrue(DockerAvailability.isAvailable(),
                "Docker is required for actual Valkey ACL enforcement");
        name = "floci-acl-test-" + UUID.randomUUID();
        docker("run", "--detach", "--name", name, "--publish", "127.0.0.1::6379", "valkey/valkey:8",
                "valkey-server", "--protected-mode", "no", "--save", "", "--appendonly", "no");
        await().atMost(20, TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals("PONG", docker("exec", name, "valkey-cli", "PING")));
        int backendPort = Integer.parseInt(docker("inspect", "--format",
                "{{(index (index .NetworkSettings.Ports \"6379/tcp\") 0).HostPort}}", name));
        assertEquals("OK", docker("exec", name, "valkey-cli", "SET", "other:key", "private-example"));
        FlociCertificateAuthority authority = FlociCertificateAuthority.loadOrCreate(directory.resolve("ca"));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        ElastiCacheTlsCertificates certificates = new ElastiCacheTlsCertificates(authority, config,
                new CertificateGenerator());
        trust = TlsTrust.trusting(authority.certificate());
        SigV4Validator sigV4 = new SigV4Validator(
                IamServiceTestHelper.iamServiceWithAccessKey("AKIDCACHE", "secret-cache"));
        proxy = new ElastiCacheAuthProxy("acl-test", AuthMode.PASSWORD, "127.0.0.1", backendPort,
                new ElastiCacheAuthProxy.PasswordValidator() {
                    @Override
                    public boolean validatePassword(String username, String password) {
                        return "subscriber".equals(username) && "synthetic-password".equals(password);
                    }

                    @Override
                    public boolean hasMembers() { return true; }

                    @Override
                    public AuthMode memberAuthMode(String username) {
                        return "subscriber".equals(username) ? memberMode.get() : null;
                    }

                    @Override
                    public String accessString(String username) { return access.get(); }

                    @Override
                    public TransitEncryption transitEncryption() { return TransitEncryption.REQUIRED; }
                }, sigV4, certificates::sslContext);
        proxy.start(0);
    }

    @ParameterizedTest
    @EnumSource(value = AuthMode.class, names = {"PASSWORD", "IAM"})
    void backendEnforcesCommandsKeysAndChannelsWithoutDefaultUserEscape(AuthMode mode) throws Exception {
        memberMode.set(mode);
        access.set("on ~* &allowed:* -@all +subscribe +psubscribe +ping");
        String credential = mode == AuthMode.IAM ? SigV4TokenTestHelper.createElastiCacheToken(
                "acl-test", "subscriber", "AKIDCACHE", "secret-cache", Instant.now().minusSeconds(60), 900)
                : "synthetic-password";
        int port = proxy.localPort();
        try (Socket invalid = connect(port, trust)) {
            assertTrue(command(invalid, "AUTH", "subscriber", "wrong-credential").startsWith("-ERR"));
        }
        try (Socket subscriber = connect(port, trust)) {
            assertEquals("+OK", command(subscriber, "AUTH", "subscriber", credential));
            assertEquals("+PONG", command(subscriber, "PING"));
            assertTrue(command(subscriber, "SET", "key", "value").startsWith("-NOPERM"));
            assertTrue(command(subscriber, "GET", "key").startsWith("-NOPERM"));
            assertTrue(command(subscriber, "PUBLISH", "allowed:channel", "message").startsWith("-NOPERM"));
            assertTrue(command(subscriber, "FLUSHALL").startsWith("-NOPERM"));
            assertTrue(command(subscriber, "AUTH", "default", "anything").startsWith("-WRONGPASS"));
            assertTrue(command(subscriber, "HELLO", "2", "AUTH", "default", "anything").startsWith("-WRONGPASS"));
            assertTrue(command(subscriber, "SET", "key", "still-denied").startsWith("-NOPERM"));
            assertTrue(command(subscriber, "SUBSCRIBE", "forbidden:channel").startsWith("-NOPERM"));
            assertEquals("*3", command(subscriber, "SUBSCRIBE", "allowed:channel"));
        }
        // Permission updates are applied on the next successful authentication.
        access.set("on ~allowed:* &* +@all");
        try (Socket writer = connect(port, trust)) {
            assertEquals("+OK", command(writer, "AUTH", "subscriber", credential));
            assertEquals("+OK", command(writer, "SET", "allowed:key", "value"));
            assertEquals(":1", command(writer, "EXISTS", "allowed:key"));
            assertTrue(command(writer, "SET", "other:key", "value").startsWith("-NOPERM"));
            assertTrue(command(writer, "GET", "other:key").startsWith("-NOPERM"));
            assertTrue(command(writer, "ACL", "SETUSER", "default", "on", "nopass", "+@all").startsWith("-NOPERM"));
            assertTrue(command(writer, "CONFIG", "SET", "requirepass", "").startsWith("-NOPERM"));
            assertTrue(command(writer, "MONITOR").startsWith("-NOPERM"));
            assertTrue(command(writer, "SLOWLOG", "GET").startsWith("-NOPERM"));
            assertTrue(command(writer, "SYNC").startsWith("-NOPERM"),
                    "SYNC must not expose keys outside the user's allowed pattern");
            assertTrue(command(writer, "PSYNC", "?", "-1").startsWith("-NOPERM"),
                    "PSYNC must not expose keys outside the user's allowed pattern");
            assertAll(
                    () -> assertTrue(command(writer, "SAVE").startsWith("-NOPERM"), "SAVE must be denied"),
                    () -> assertTrue(command(writer, "BGSAVE").startsWith("-NOPERM"), "BGSAVE must be denied"),
                    () -> assertTrue(command(writer, "BGREWRITEAOF").startsWith("-NOPERM"), "BGREWRITEAOF must be denied"),
                    () -> assertTrue(command(writer, "MIGRATE", "127.0.0.1", "1", "allowed:key", "0", "100")
                            .startsWith("-NOPERM"), "MIGRATE must be denied even for an allowed key"));
            assertEquals(":1", command(writer, "EXISTS", "allowed:key"));
            assertEquals("+PONG", command(writer, "PING"));
        }
        access.set("on ~* &* -@all +ping");
        try (Socket hello = connect(port, trust)) {
            assertTrue(command(hello, "HELLO", "2", "AUTH", "subscriber", credential).startsWith("*"));
        }
    }

    @AfterAll
    void stopBackend() throws Exception {
        try {
            if (proxy != null) {
                proxy.stop();
            }
        } finally {
            if (name != null) {
                docker("rm", "--force", "--volumes", name);
            }
        }
    }
}
