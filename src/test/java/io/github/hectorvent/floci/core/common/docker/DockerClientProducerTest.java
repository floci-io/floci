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
}
