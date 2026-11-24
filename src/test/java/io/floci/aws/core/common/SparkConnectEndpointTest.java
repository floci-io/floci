package io.floci.aws.core.common;

import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class SparkConnectEndpointTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void theEndpointIsThePathOnFlocisAddressWithOrWithoutItsTrailingSlash() {
        assertEquals("http://localhost:4566/_aws/emr/sessions/j-1/is-1", SparkConnectEndpoint.issue(
                "http://localhost:4566/", "/_aws/emr/sessions/j-1/is-1", NOW, Duration.ofHours(1)).url());
        assertEquals("http://localhost:4566/_aws/emr/sessions/j-1/is-1", SparkConnectEndpoint.issue(
                "http://localhost:4566", "/_aws/emr/sessions/j-1/is-1", NOW, Duration.ofHours(1)).url());
    }

    @Test
    void eachIssueGivesANewTokenThatExpiresAfterItsValidity() {
        SparkConnectEndpoint first = SparkConnectEndpoint.issue("http://localhost:4566", "/s", NOW,
                Duration.ofHours(1));
        SparkConnectEndpoint second = SparkConnectEndpoint.issue("http://localhost:4566", "/s", NOW,
                Duration.ofHours(1));

        assertTrue(first.authToken().matches("[0-9a-f]{64}"), first.authToken());
        assertNotEquals(first.authToken(), second.authToken());
        assertEquals(NOW.plus(Duration.ofHours(1)), first.authTokenExpiresAt());
    }
}
