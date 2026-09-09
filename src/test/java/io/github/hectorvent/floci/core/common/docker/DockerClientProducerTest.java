package io.github.hectorvent.floci.core.common.docker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug condition exploration test for Docker host scheme normalization.
 *
 * Bug: When the Docker host configuration value is a bare host:port string
 * without a URI scheme (e.g., "10.37.124.101:2375"), URI.create() throws
 * IllegalArgumentException because the IP/hostname is parsed as an invalid
 * scheme name. The normalizeDockerHost method should prepend "tcp://" to
 * bare host:port values so they become valid URIs.
 *
 * EXPECTED OUTCOME on unfixed code: Test FAILS (compilation failure or
 * assertion failure, normalizeDockerHost method does not exist yet,
 * confirming the missing normalization logic).
 */
class DockerClientProducerTest {

    /**
     * Documents the bug: URI.create with bare host:port throws IllegalArgumentException.
     *
     * On unfixed code, URI.create("10.37.124.101:2375") throws:
     *   IllegalArgumentException: Illegal character in scheme name at index 0
     *
     * This is because "10.37.124.101" is parsed as a URI scheme, and dots
     * are illegal characters in scheme names per RFC 3986.
     */
    static Stream<String> bareHostPortInputs() {
        return Stream.of(
                "10.37.124.101:2375",
                "docker-daemon:2375",
                "192.168.1.100:2376",
                "localhost:2375"
        );
    }

    /**
     * Bug Condition: Bare host:port values are normalized with tcp:// prefix.
     *
     * For each bare host:port input, normalizeDockerHost should return "tcp://" + input,
     * and the result should be a valid URI (URI.create does not throw).
     */
    @ParameterizedTest
    @MethodSource("bareHostPortInputs")
    void bareHostPort_isNormalizedWithTcpScheme(String input) {
        String result = DockerClientProducer.normalizeDockerHost(input);

        assertEquals("tcp://" + input, result,
                "Bare host:port '" + input + "' should be normalized with tcp:// prefix");

        // The normalized value must be a valid URI: URI.create must not throw
        URI uri = assertDoesNotThrow(() -> URI.create(result),
                "Normalized value '" + result + "' should be a valid URI");
        assertNotNull(uri.getScheme(), "Normalized URI should have a scheme");
        assertEquals("tcp", uri.getScheme(), "Normalized URI scheme should be 'tcp'");
    }

    /**
     * Provides Docker host values that already carry a recognized URI scheme.
     * These values should pass through normalizeDockerHost unchanged.
     */
    static Stream<String> schemedUriInputs() {
        return Stream.of(
                "tcp://10.37.124.101:2375",
                "tcp://localhost:2375",
                "unix:///var/run/docker.sock",
                "npipe:////./pipe/docker_engine"
        );
    }

    /**
     * Preservation: Schemed URIs are passed through unchanged.
     *
     * For any Docker host value that already contains a recognized URI scheme
     * (tcp://, unix://, npipe://), normalizeDockerHost should return the value
     * unchanged, preserving all existing Docker client initialization behavior.
     */
    @ParameterizedTest
    @MethodSource("schemedUriInputs")
    void schemedUri_passedThroughUnchanged(String input) {
        String result = DockerClientProducer.normalizeDockerHost(input);

        assertEquals(input, result,
                "Schemed URI '" + input + "' should pass through unchanged");

        // The value should remain a valid URI
        URI uri = assertDoesNotThrow(() -> URI.create(result),
                "Schemed URI '" + result + "' should still be a valid URI");
        assertNotNull(uri.getScheme(), "Schemed URI should retain its scheme");
    }

    /**
     * Edge case: null input handling.
     *
     * normalizeDockerHost should handle null gracefully: either return null
     * or throw a clear exception, but not produce an invalid result.
     */
    @Test
    void nullInput_handledGracefully() {
        String result = DockerClientProducer.normalizeDockerHost(null);
        assertNull(result, "null input should return null");
    }

    /**
     * Edge case: empty string input handling.
     *
     * normalizeDockerHost should handle empty string gracefully: return it
     * unchanged since there is no meaningful host to normalize.
     */
    @Test
    void emptyInput_handledGracefully() {
        String result = DockerClientProducer.normalizeDockerHost("");
        assertEquals("", result, "Empty string input should return empty string");
    }

    // --- resolveEffectiveDockerHost tests ---

    /**
     * When floci.docker.docker-host is at its default (unix socket) and DOCKER_HOST env var
     * is set to a bare host:port, the env var should be used (normalized with tcp://).
     * This is the Bitbucket Pipelines scenario from issue #663.
     */
    @Test
    void resolveEffectiveDockerHost_dockerHostEnvBareHostPort_usesNormalizedEnv() {
        String result = DockerClientProducer.resolveEffectiveDockerHost(
                "unix:///var/run/docker.sock", "10.37.124.101:2375", false, null);
        assertEquals("tcp://10.37.124.101:2375", result,
                "Bare DOCKER_HOST env var should be normalized and used when config is at its default");
    }

    /**
     * When floci.docker.docker-host is at its default and DOCKER_HOST env var is already
     * a valid tcp:// URI, it should be used unchanged.
     */
    @Test
    void resolveEffectiveDockerHost_dockerHostEnvTcpUri_usedDirectly() {
        String result = DockerClientProducer.resolveEffectiveDockerHost(
                "unix:///var/run/docker.sock", "tcp://10.37.124.101:2375", false, null);
        assertEquals("tcp://10.37.124.101:2375", result,
                "Valid tcp:// DOCKER_HOST env var should be used when config is at its default");
    }

    /**
     * When floci.docker.docker-host is explicitly configured to a non-default value,
     * that value takes priority over DOCKER_HOST env var.
     */
    @Test
    void resolveEffectiveDockerHost_explicitFlociConfig_takesPriorityOverEnv() {
        String result = DockerClientProducer.resolveEffectiveDockerHost(
                "tcp://custom-daemon:2376", "10.37.124.101:2375", false, null);
        assertEquals("tcp://custom-daemon:2376", result,
                "Explicit floci.docker.docker-host should take priority over DOCKER_HOST env var");
    }

    /**
     * When DOCKER_HOST env var is null and floci.docker.docker-host is default on a non-Windows
     * platform, use the unix-socket default (existing Linux/macOS behavior, unchanged).
     */
    @Test
    void resolveEffectiveDockerHost_noEnvVar_nonWindows_usesUnixSocketDefault() {
        String result = DockerClientProducer.resolveEffectiveDockerHost(
                "unix:///var/run/docker.sock", null, false, null);
        assertEquals("unix:///var/run/docker.sock", result,
                "Default unix socket should be used on non-Windows platforms when DOCKER_HOST is not set");
    }

    /**
     * When DOCKER_HOST env var is blank and floci.docker.docker-host is default on a non-Windows
     * platform, use the unix-socket default (existing Linux/macOS behavior, unchanged).
     */
    @Test
    void resolveEffectiveDockerHost_blankEnvVar_nonWindows_usesUnixSocketDefault() {
        String result = DockerClientProducer.resolveEffectiveDockerHost(
                "unix:///var/run/docker.sock", "", false, null);
        assertEquals("unix:///var/run/docker.sock", result,
                "Default unix socket should be used on non-Windows platforms when DOCKER_HOST is blank");
    }

    /**
     * Bug: on native Windows, /var/run/docker.sock has no equivalent. Docker Desktop exposes
     * its API via a named pipe instead. When floci.docker.docker-host is still at its unix-socket
     * default and no DOCKER_HOST override is set, Windows must fall back to the named pipe rather
     * than the unreachable unix-socket path (issue floci-io/floci#2006).
     */
    @Test
    void resolveEffectiveDockerHost_noEnvVar_windows_usesNamedPipeDefault() {
        String result = DockerClientProducer.resolveEffectiveDockerHost(
                "unix:///var/run/docker.sock", null, true, null);
        assertEquals("npipe:////./pipe/docker_engine", result,
                "Windows should fall back to the named pipe when the unix-socket default is unreachable");
    }

    /**
     * Same as above, but with a blank (rather than null) DOCKER_HOST env var.
     */
    @Test
    void resolveEffectiveDockerHost_blankEnvVar_windows_usesNamedPipeDefault() {
        String result = DockerClientProducer.resolveEffectiveDockerHost(
                "unix:///var/run/docker.sock", "", true, null);
        assertEquals("npipe:////./pipe/docker_engine", result,
                "Windows should fall back to the named pipe when DOCKER_HOST is blank");
    }

    /**
     * An explicit DOCKER_HOST env var still takes priority over the Windows named-pipe fallback.
     */
    @Test
    void resolveEffectiveDockerHost_envVarSet_windows_usesEnvNotNamedPipe() {
        String result = DockerClientProducer.resolveEffectiveDockerHost(
                "unix:///var/run/docker.sock", "tcp://10.0.0.5:2375", true, null);
        assertEquals("tcp://10.0.0.5:2375", result,
                "An explicit DOCKER_HOST should take priority over the Windows named-pipe fallback");
    }

    /**
     * An explicitly-configured (non-default) floci.docker.docker-host still takes priority over
     * the Windows named-pipe fallback.
     */
    @Test
    void resolveEffectiveDockerHost_explicitFlociConfig_windows_takesPriorityOverNamedPipe() {
        String result = DockerClientProducer.resolveEffectiveDockerHost(
                "tcp://custom-daemon:2376", null, true, null);
        assertEquals("tcp://custom-daemon:2376", result,
                "An explicitly-configured docker-host should take priority over the Windows named-pipe fallback");
    }

    private static void writeContextFixture(Path dockerConfigDir, String contextName, String host)
            throws IOException {
        writeContextFixture(dockerConfigDir, contextName, host, false);
    }

    private static void writeContextFixture(
            Path dockerConfigDir, String contextName, String host, boolean skipTlsVerify) throws IOException {
        Files.createDirectories(dockerConfigDir);
        Files.writeString(dockerConfigDir.resolve("config.json"),
                "{\"currentContext\":\"" + contextName + "\"}");
        Path metaDir = dockerConfigDir.resolve("contexts").resolve("meta").resolve(sha256Hex(contextName));
        Files.createDirectories(metaDir);
        Files.writeString(metaDir.resolve("meta.json"),
                "{\"Name\":\"" + contextName + "\",\"Metadata\":{},"
                        + "\"Endpoints\":{\"docker\":{\"Host\":\"" + host + "\",\"SkipTLSVerify\":"
                        + skipTlsVerify + "}}}");
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * When floci.docker.docker-host is at its default and DOCKER_HOST is unset, the endpoint of
     * the active Docker context (e.g. Colima) is resolved from ~/.docker/config.json and its
     * context metadata file, and used instead of the hardcoded unix-socket default.
     */
    @Test
    void resolveEffectiveDockerHost_activeNonDefaultContext_usesContextEndpoint(@TempDir Path tempDir)
            throws IOException {
        writeContextFixture(tempDir, "colima", "unix:///Users/dev/.colima/default/docker.sock");

        String result = DockerClientProducer.resolveEffectiveDockerHost(
                "unix:///var/run/docker.sock", null, false, tempDir);

        assertEquals("unix:///Users/dev/.colima/default/docker.sock", result,
                "The active Docker context's endpoint should be used when no explicit override is set");
    }

    /**
     * An explicitly-configured floci.docker.docker-host still takes priority over the active
     * Docker context's endpoint.
     */
    @Test
    void resolveEffectiveDockerHost_explicitFlociConfig_takesPriorityOverContext(@TempDir Path tempDir)
            throws IOException {
        writeContextFixture(tempDir, "colima", "unix:///Users/dev/.colima/default/docker.sock");

        String result = DockerClientProducer.resolveEffectiveDockerHost(
                "tcp://custom-daemon:2376", null, false, tempDir);

        assertEquals("tcp://custom-daemon:2376", result,
                "Explicit floci.docker.docker-host should take priority over the active Docker context");
    }

    /**
     * An explicit DOCKER_HOST env var still takes priority over the active Docker context's
     * endpoint.
     */
    @Test
    void resolveEffectiveDockerHost_dockerHostEnv_takesPriorityOverContext(@TempDir Path tempDir)
            throws IOException {
        writeContextFixture(tempDir, "colima", "unix:///Users/dev/.colima/default/docker.sock");

        String result = DockerClientProducer.resolveEffectiveDockerHost(
                "unix:///var/run/docker.sock", "tcp://10.0.0.5:2375", false, tempDir);

        assertEquals("tcp://10.0.0.5:2375", result,
                "DOCKER_HOST env var should take priority over the active Docker context");
    }

    /**
     * When the active context is the built-in "default" context (no metadata file on disk), it
     * carries no endpoint of its own, so resolution falls through to the platform default.
     */
    @Test
    void resolveEffectiveDockerHost_defaultContext_fallsThroughToPlatformDefault(@TempDir Path tempDir)
            throws IOException {
        Files.writeString(tempDir.resolve("config.json"), "{\"currentContext\":\"default\"}");

        String result = DockerClientProducer.resolveEffectiveDockerHost(
                "unix:///var/run/docker.sock", null, false, tempDir);

        assertEquals("unix:///var/run/docker.sock", result,
                "The built-in default context should fall through to the platform default");
    }

    /**
     * When ~/.docker/config.json does not exist at all, resolution falls through gracefully to
     * the platform default rather than throwing.
     */
    @Test
    void resolveEffectiveDockerHost_missingDockerConfig_fallsThroughToPlatformDefault(@TempDir Path tempDir) {
        String result = DockerClientProducer.resolveEffectiveDockerHost(
                "unix:///var/run/docker.sock", null, false, tempDir.resolve("does-not-exist"));

        assertEquals("unix:///var/run/docker.sock", result,
                "A missing Docker config directory should fall through to the platform default");
    }

    /**
     * When ~/.docker/config.json is malformed JSON, resolution falls through gracefully to the
     * platform default rather than throwing.
     */
    @Test
    void resolveEffectiveDockerHost_malformedDockerConfig_fallsThroughToPlatformDefault(@TempDir Path tempDir)
            throws IOException {
        Files.writeString(tempDir.resolve("config.json"), "{not valid json");

        String result = DockerClientProducer.resolveEffectiveDockerHost(
                "unix:///var/run/docker.sock", null, false, tempDir);

        assertEquals("unix:///var/run/docker.sock", result,
                "A malformed Docker config file should fall through to the platform default");
    }

    /**
     * When the active context is set but its metadata file is missing from disk, resolution
     * falls through gracefully to the platform default rather than throwing.
     */
    @Test
    void resolveEffectiveDockerHost_missingContextMetadata_fallsThroughToPlatformDefault(@TempDir Path tempDir)
            throws IOException {
        Files.writeString(tempDir.resolve("config.json"), "{\"currentContext\":\"orbstack\"}");

        String result = DockerClientProducer.resolveEffectiveDockerHost(
                "unix:///var/run/docker.sock", null, false, tempDir);

        assertEquals("unix:///var/run/docker.sock", result,
                "A missing context metadata file should fall through to the platform default");
    }

    /**
     * On Windows, the active Docker context's endpoint still takes priority over the named-pipe
     * default.
     */
    @Test
    void resolveEffectiveDockerHost_activeContext_windows_takesPriorityOverNamedPipe(@TempDir Path tempDir)
            throws IOException {
        writeContextFixture(tempDir, "rancher-desktop", "npipe:////./pipe/rancher_desktop");

        String result = DockerClientProducer.resolveEffectiveDockerHost(
                "unix:///var/run/docker.sock", null, true, tempDir);

        assertEquals("npipe:////./pipe/rancher_desktop", result,
                "The active Docker context's endpoint should take priority over the Windows named-pipe default");
    }

    /**
     * An empty {@code config.json} makes Jackson's {@code readTree} return {@code null} rather
     * than throwing. Resolution must treat that the same as "no context configured" instead of
     * dereferencing the null root, which previously crashed emulator startup with an NPE.
     */
    @Test
    void resolveEffectiveDockerHost_emptyConfigJson_fallsThroughToPlatformDefault(@TempDir Path tempDir)
            throws IOException {
        Files.writeString(tempDir.resolve("config.json"), "");

        String result = assertDoesNotThrow(() -> DockerClientProducer.resolveEffectiveDockerHost(
                "unix:///var/run/docker.sock", null, false, tempDir));

        assertEquals("unix:///var/run/docker.sock", result,
                "An empty config.json should fall through to the platform default rather than crashing");
    }

    /**
     * Same as above, but with a whitespace-only file rather than a fully empty one.
     */
    @Test
    void resolveEffectiveDockerHost_whitespaceOnlyConfigJson_fallsThroughToPlatformDefault(@TempDir Path tempDir)
            throws IOException {
        Files.writeString(tempDir.resolve("config.json"), "   \n\t  ");

        String result = assertDoesNotThrow(() -> DockerClientProducer.resolveEffectiveDockerHost(
                "unix:///var/run/docker.sock", null, false, tempDir));

        assertEquals("unix:///var/run/docker.sock", result,
                "A whitespace-only config.json should fall through to the platform default rather than crashing");
    }

    /**
     * When {@code floci.docker.docker-config-path} is explicitly configured, it wins outright,
     * even over a {@code DOCKER_CONFIG} env var pointing elsewhere.
     */
    @Test
    void resolveDockerConfigDir_explicitConfigProperty_takesPriorityOverDockerConfigEnv(@TempDir Path tempDir) {
        Path configuredDir = tempDir.resolve("explicit-config");
        Path envDir = tempDir.resolve("env-config");

        Path result = DockerClientProducer.resolveDockerConfigDir(
                Optional.of(configuredDir.toString()), envDir.toString());

        assertEquals(configuredDir, result,
                "An explicitly configured docker-config-path should take priority over DOCKER_CONFIG");
    }

    /**
     * When no explicit config property is set, the standard {@code DOCKER_CONFIG} env var
     * relocates the whole Docker config directory, matching the Docker CLI's own behavior.
     */
    @Test
    void resolveDockerConfigDir_dockerConfigEnv_usedWhenNoExplicitConfig(@TempDir Path tempDir) {
        Path envDir = tempDir.resolve("env-config");

        Path result = DockerClientProducer.resolveDockerConfigDir(Optional.empty(), envDir.toString());

        assertEquals(envDir, result, "DOCKER_CONFIG should be honored when no explicit override is configured");
    }

    /**
     * When neither the explicit config property nor DOCKER_CONFIG is set, the standard
     * {@code ~/.docker} default applies.
     */
    @Test
    void resolveDockerConfigDir_noOverrides_usesDockerHomeDefault() {
        Path result = DockerClientProducer.resolveDockerConfigDir(Optional.empty(), null);

        assertEquals(Paths.get(System.getProperty("user.home", ""), ".docker"), result,
                "With no overrides, the default ~/.docker directory should be used");
    }

    /**
     * The standard {@code DOCKER_CONTEXT} env var must take priority over {@code config.json}'s
     * {@code currentContext} field, matching real Docker CLI behavior.
     */
    @Test
    void resolveEffectiveDockerHost_dockerContextEnv_overridesConfigJsonCurrentContext(@TempDir Path tempDir)
            throws IOException {
        writeContextFixture(tempDir, "colima", "unix:///Users/dev/.colima/default/docker.sock");
        writeContextFixture(tempDir, "orbstack", "unix:///Users/dev/.orbstack/run/docker.sock");
        Files.writeString(tempDir.resolve("config.json"), "{\"currentContext\":\"colima\"}");

        String result = DockerClientProducer.resolveEffectiveDockerHost(
                "unix:///var/run/docker.sock", null, false, tempDir, "orbstack");

        assertEquals("unix:///Users/dev/.orbstack/run/docker.sock", result,
                "DOCKER_CONTEXT should override config.json's currentContext");
    }

    /**
     * A blank {@code DOCKER_CONTEXT} env var is treated as unset, falling back to
     * {@code config.json}'s {@code currentContext} field.
     */
    @Test
    void resolveEffectiveDockerHost_blankDockerContextEnv_fallsBackToConfigJson(@TempDir Path tempDir)
            throws IOException {
        writeContextFixture(tempDir, "colima", "unix:///Users/dev/.colima/default/docker.sock");

        String result = DockerClientProducer.resolveEffectiveDockerHost(
                "unix:///var/run/docker.sock", null, false, tempDir, "");

        assertEquals("unix:///Users/dev/.colima/default/docker.sock", result,
                "A blank DOCKER_CONTEXT should fall back to config.json's currentContext");
    }

    /**
     * An explicit DOCKER_HOST env var still takes priority over DOCKER_CONTEXT.
     */
    @Test
    void resolveEffectiveDockerHost_dockerHostEnv_takesPriorityOverDockerContextEnv(@TempDir Path tempDir)
            throws IOException {
        writeContextFixture(tempDir, "orbstack", "unix:///Users/dev/.orbstack/run/docker.sock");

        String result = DockerClientProducer.resolveEffectiveDockerHost(
                "unix:///var/run/docker.sock", "tcp://10.0.0.5:2375", false, tempDir, "orbstack");

        assertEquals("tcp://10.0.0.5:2375", result,
                "DOCKER_HOST env var should take priority over DOCKER_CONTEXT");
    }

    private static void writeContextTlsFixture(
            Path dockerConfigDir, String contextName, boolean withCa, boolean withCert, boolean withKey)
            throws IOException {
        Path tlsDir = dockerConfigDir.resolve("contexts").resolve("tls")
                .resolve(sha256Hex(contextName)).resolve("docker");
        Files.createDirectories(tlsDir);
        if (withCa) {
            Files.writeString(tlsDir.resolve("ca.pem"), "-----BEGIN CERTIFICATE-----\nca\n-----END CERTIFICATE-----");
        }
        if (withCert) {
            Files.writeString(tlsDir.resolve("cert.pem"),
                    "-----BEGIN CERTIFICATE-----\ncert\n-----END CERTIFICATE-----");
        }
        if (withKey) {
            Files.writeString(tlsDir.resolve("key.pem"),
                    "-----BEGIN PRIVATE KEY-----\nkey\n-----END PRIVATE KEY-----");
        }
    }

    /**
     * When the active context's TLS storage directory holds a complete set of client-certificate
     * material (ca.pem, cert.pem, key.pem, exactly as the Docker CLI lays it out), it is
     * surfaced alongside the resolved host so it can be applied to the Docker client.
     */
    @Test
    void resolveDockerConnection_contextWithCompleteTlsMaterial_returnsTlsCertPath(@TempDir Path tempDir)
            throws IOException {
        writeContextFixture(tempDir, "remote-tls", "tcp://remote-docker:2376");
        writeContextTlsFixture(tempDir, "remote-tls", true, true, true);

        DockerClientProducer.ResolvedDockerConnection result = DockerClientProducer.resolveDockerConnection(
                "unix:///var/run/docker.sock", null, false, tempDir, null);

        assertEquals("tcp://remote-docker:2376", result.host());
        assertTrue(result.tlsCertPath().isPresent(), "TLS material should be surfaced when present");
        assertEquals(tempDir.resolve("contexts").resolve("tls").resolve(sha256Hex("remote-tls")).resolve("docker"),
                result.tlsCertPath().get());
        assertFalse(result.skipTlsVerify(), "SkipTLSVerify defaults to false and should be reported as such");
    }

    /**
     * A context with {@code SkipTLSVerify: true} must surface that flag alongside its TLS
     * material, so the client certificate is presented but the daemon's server certificate is
     * not validated, matching the Docker CLI's own behavior for such a context.
     */
    @Test
    void resolveDockerConnection_contextWithSkipTlsVerify_reportsSkipTlsVerifyTrue(@TempDir Path tempDir)
            throws IOException {
        writeContextFixture(tempDir, "remote-tls", "tcp://remote-docker:2376", true);
        writeContextTlsFixture(tempDir, "remote-tls", true, true, true);

        DockerClientProducer.ResolvedDockerConnection result = DockerClientProducer.resolveDockerConnection(
                "unix:///var/run/docker.sock", null, false, tempDir, null);

        assertTrue(result.tlsCertPath().isPresent(), "TLS material should still be surfaced");
        assertTrue(result.skipTlsVerify(), "SkipTLSVerify: true in the context metadata should be honored");
    }

    /**
     * A context with no TLS storage directory at all (the common case: a plaintext remote
     * daemon, or a context pointing at a local unix socket / named pipe) resolves without any
     * TLS material, and does not fail.
     */
    @Test
    void resolveDockerConnection_contextWithoutTlsMaterial_returnsEmptyTlsCertPath(@TempDir Path tempDir)
            throws IOException {
        writeContextFixture(tempDir, "colima", "unix:///Users/dev/.colima/default/docker.sock");

        DockerClientProducer.ResolvedDockerConnection result = DockerClientProducer.resolveDockerConnection(
                "unix:///var/run/docker.sock", null, false, tempDir, null);

        assertEquals("unix:///Users/dev/.colima/default/docker.sock", result.host());
        assertTrue(result.tlsCertPath().isEmpty(), "A context without TLS material should not report any");
    }

    /**
     * A context whose TLS storage directory exists but is missing one of the required files
     * indicates a broken TLS setup. Rather than silently connecting without a client certificate
     * (or falling back to plaintext), resolution fails loudly.
     */
    @Test
    void resolveDockerConnection_contextWithIncompleteTlsMaterial_throwsIllegalStateException(@TempDir Path tempDir)
            throws IOException {
        writeContextFixture(tempDir, "remote-tls", "tcp://remote-docker:2376");
        writeContextTlsFixture(tempDir, "remote-tls", true, false, false);

        assertThrows(IllegalStateException.class, () -> DockerClientProducer.resolveDockerConnection(
                "unix:///var/run/docker.sock", null, false, tempDir, null),
                "An incomplete TLS material directory should fail loudly rather than connect insecurely");
    }
}
