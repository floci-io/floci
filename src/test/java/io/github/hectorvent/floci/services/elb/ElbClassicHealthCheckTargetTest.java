package io.github.hectorvent.floci.services.elb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The Classic {@code HealthCheck.Target} grammar — one string carrying protocol, port and, for the
 * HTTP protocols, a path. ELBv2 splits these across three members, which is why this parsing
 * exists at all.
 */
class ElbClassicHealthCheckTargetTest {

    @Test
    void httpTargetCarriesItsPath() {
        ElbClassicHealthChecker.Target target = ElbClassicHealthChecker.Target.parse("HTTP:8080/health");
        assertEquals("http", target.scheme());
        assertEquals(8080, target.port());
        assertEquals("/health", target.path());
    }

    @Test
    void httpTargetWithNoPathDefaultsToRoot() {
        ElbClassicHealthChecker.Target target = ElbClassicHealthChecker.Target.parse("HTTP:80");
        assertEquals("/", target.path());
    }

    @Test
    void tcpTargetHasNoPathAndSoIsProbedByConnection() {
        ElbClassicHealthChecker.Target target = ElbClassicHealthChecker.Target.parse("TCP:8080");
        assertNull(target.path());
        assertNull(target.scheme());
        assertEquals(8080, target.port());
    }

    @Test
    void sslTargetIsAConnectionProbeToo() {
        assertNull(ElbClassicHealthChecker.Target.parse("SSL:443").path());
    }

    @Test
    void malformedTargetsAreRejectedRatherThanGuessed() {
        assertNull(ElbClassicHealthChecker.Target.parse(null));
        assertNull(ElbClassicHealthChecker.Target.parse(""));
        assertNull(ElbClassicHealthChecker.Target.parse("HTTP"));
        assertNull(ElbClassicHealthChecker.Target.parse("HTTP:notaport/"));
        assertNull(ElbClassicHealthChecker.Target.parse("GOPHER:70/"));
    }
}
