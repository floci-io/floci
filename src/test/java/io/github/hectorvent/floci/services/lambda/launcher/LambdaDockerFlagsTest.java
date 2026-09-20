package io.github.hectorvent.floci.services.lambda.launcher;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LambdaDockerFlagsTest {

    @Test
    void parsesSupportedFlagsAndQuotedValues() {
        LambdaDockerFlags flags = LambdaDockerFlags.parse(
                "--env \"NODE_EXTRA_CA_CERTS=/opt/certs/root ca.pem\" "
                        + "--volume /tmp/certs:/opt/certs:ro "
                        + "--publish 127.0.0.1:5050:5050 --add-host api.local:host-gateway "
                        + "--dns 1.1.1.1 --label purpose=debug --network dev-net "
                        + "--user 1000:1000 --privileged --platform linux/amd64");

        assertEquals(List.of("NODE_EXTRA_CA_CERTS=/opt/certs/root ca.pem"), flags.environment());
        assertEquals(List.of("/tmp/certs:/opt/certs:ro"), flags.volumes());
        assertEquals(List.of("127.0.0.1:5050:5050"), flags.publishedPorts());
        assertEquals(List.of("api.local:host-gateway"), flags.extraHosts());
        assertEquals(List.of("1.1.1.1"), flags.dnsServers());
        assertEquals(Map.of("purpose", "debug"), flags.labels());
        assertEquals("dev-net", flags.network());
        assertEquals("1000:1000", flags.user());
        assertTrue(flags.privileged());
        assertEquals("linux/amd64", flags.platform());
    }

    @Test
    void supportsShortAliases() {
        LambdaDockerFlags flags = LambdaDockerFlags.parse(
                "-e KEY=value -v host:/container -p 8080:80 -u 1000");

        assertEquals(List.of("KEY=value"), flags.environment());
        assertEquals(List.of("host:/container"), flags.volumes());
        assertEquals(List.of("8080:80"), flags.publishedPorts());
        assertEquals("1000", flags.user());
    }

    @Test
    void rejectsUnsupportedFlags() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> LambdaDockerFlags.parse("--cap-add NET_ADMIN"));

        assertTrue(exception.getMessage().contains("--cap-add"));
    }

    @Test
    void rejectsMissingFlagValues() {
        assertThrows(IllegalArgumentException.class, () -> LambdaDockerFlags.parse("--env"));
        assertThrows(IllegalArgumentException.class, () -> LambdaDockerFlags.parse("--dns --privileged"));
    }
}
