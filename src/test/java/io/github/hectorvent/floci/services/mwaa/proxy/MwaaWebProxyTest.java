package io.github.hectorvent.floci.services.mwaa.proxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Routing-decision tests for {@link MwaaWebProxy}: which requests get intercepted by the CLI
 * handler versus forwarded verbatim to the backend Airflow container, and bearer-token parsing.
 * No real sockets are used — {@code isCliRequest}/{@code extractBearerToken} are pure functions.
 */
class MwaaWebProxyTest {

    @Test
    void interceptsOnlyPostToTheCliPath() {
        assertTrue(MwaaWebProxy.isCliRequest("POST", "/aws_mwaa/cli"));
        assertTrue(MwaaWebProxy.isCliRequest("post", "/aws_mwaa/cli"), "method match should be case-insensitive");
    }

    @Test
    void forwardsEverythingElse() {
        assertFalse(MwaaWebProxy.isCliRequest("GET", "/aws_mwaa/cli"), "GET to the CLI path is not intercepted");
        assertFalse(MwaaWebProxy.isCliRequest("POST", "/"), "POST elsewhere is forwarded");
        assertFalse(MwaaWebProxy.isCliRequest("POST", "/api/v1/dags"), "Airflow REST API traffic is forwarded");
        assertFalse(MwaaWebProxy.isCliRequest("POST", "/aws_mwaa/cli/"), "trailing slash does not match");
        assertFalse(MwaaWebProxy.isCliRequest("PUT", "/aws_mwaa/cli"));
    }

    @Test
    void extractsBearerTokenCaseInsensitively() {
        assertEquals("abc123", MwaaWebProxy.extractBearerToken("Bearer abc123"));
        assertEquals("abc123", MwaaWebProxy.extractBearerToken("bearer abc123"));
        assertEquals("abc123", MwaaWebProxy.extractBearerToken("BEARER   abc123".replace("   ", " ")));
    }

    @Test
    void rejectsMissingOrMalformedAuthorizationHeaders() {
        assertNull(MwaaWebProxy.extractBearerToken(null));
        assertNull(MwaaWebProxy.extractBearerToken(""));
        assertNull(MwaaWebProxy.extractBearerToken("Basic dXNlcjpwYXNz"));
        assertNull(MwaaWebProxy.extractBearerToken("Bearer "));
        assertNull(MwaaWebProxy.extractBearerToken("Bearer"));
    }
}
