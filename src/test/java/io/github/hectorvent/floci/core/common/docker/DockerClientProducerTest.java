package io.github.hectorvent.floci.core.common.docker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
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
 * assertion failure — normalizeDockerHost method does not exist yet,
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
     * Bug Condition — Bare host:port values are normalized with tcp:// prefix.
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

        // The normalized value must be a valid URI — URI.create must not throw
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
     * Preservation — Schemed URIs are passed through unchanged.
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
     * normalizeDockerHost should handle null gracefully — either return null
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
     * normalizeDockerHost should handle empty string gracefully — return it
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
                "unix:///var/run/docker.sock", "10.37.124.101:2375", false);
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
                "unix:///var/run/docker.sock", "tcp://10.37.124.101:2375", false);
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
                "tcp://custom-daemon:2376", "10.37.124.101:2375", false);
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
                "unix:///var/run/docker.sock", null, false);
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
                "unix:///var/run/docker.sock", "", false);
        assertEquals("unix:///var/run/docker.sock", result,
                "Default unix socket should be used on non-Windows platforms when DOCKER_HOST is blank");
    }

    /**
     * Bug: on native Windows, /var/run/docker.sock has no equivalent — Docker Desktop exposes
     * its API via a named pipe instead. When floci.docker.docker-host is still at its unix-socket
     * default and no DOCKER_HOST override is set, Windows must fall back to the named pipe rather
     * than the unreachable unix-socket path (issue floci-io/floci#2006).
     */
    @Test
    void resolveEffectiveDockerHost_noEnvVar_windows_usesNamedPipeDefault() {
        String result = DockerClientProducer.resolveEffectiveDockerHost(
                "unix:///var/run/docker.sock", null, true);
        assertEquals("npipe:////./pipe/docker_engine", result,
                "Windows should fall back to the named pipe when the unix-socket default is unreachable");
    }

    /**
     * Same as above, but with a blank (rather than null) DOCKER_HOST env var.
     */
    @Test
    void resolveEffectiveDockerHost_blankEnvVar_windows_usesNamedPipeDefault() {
        String result = DockerClientProducer.resolveEffectiveDockerHost(
                "unix:///var/run/docker.sock", "", true);
        assertEquals("npipe:////./pipe/docker_engine", result,
                "Windows should fall back to the named pipe when DOCKER_HOST is blank");
    }

    /**
     * An explicit DOCKER_HOST env var still takes priority over the Windows named-pipe fallback.
     */
    @Test
    void resolveEffectiveDockerHost_envVarSet_windows_usesEnvNotNamedPipe() {
        String result = DockerClientProducer.resolveEffectiveDockerHost(
                "unix:///var/run/docker.sock", "tcp://10.0.0.5:2375", true);
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
                "tcp://custom-daemon:2376", null, true);
        assertEquals("tcp://custom-daemon:2376", result,
                "An explicitly-configured docker-host should take priority over the Windows named-pipe fallback");
    }
}
