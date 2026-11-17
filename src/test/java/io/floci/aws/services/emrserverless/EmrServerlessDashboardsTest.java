package io.floci.aws.services.emrserverless;

import io.floci.aws.testing.MutableClock;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmrServerlessDashboardsTest {

    // Where MutableClock starts; each read moves it a millisecond, within the second a link's expiry is counted in.
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final byte[] KEY = "a key of this process, 32 bytes!".getBytes(StandardCharsets.US_ASCII);

    private final MutableClock clock = new MutableClock();
    private final EmrServerlessDashboards dashboards = new EmrServerlessDashboards("http://localhost:4566/", clock,
            KEY);

    @Test
    void aLinkNamesTheRunsAttemptOnFlocisAddressAndExpiresInAnHour() {
        String url = dashboards.url("123456789012", "eu-west-1", "00fabcd", "00frun", 2);

        assertTrue(url.startsWith("http://localhost:4566/_aws/emr-serverless/dashboard?"), url);
        Map<String, String> query = query(url);
        assertEquals("123456789012", query.get("account"));
        assertEquals("eu-west-1", query.get("region"));
        assertEquals("00fabcd", query.get("application"));
        assertEquals("00frun", query.get("jobRun"));
        assertEquals("2", query.get("attempt"));
        // "The URL is valid for one hour after you generate it."
        assertEquals(String.valueOf(NOW.plus(Duration.ofHours(1)).getEpochSecond()), query.get("expires"));
        assertEquals(new EmrServerlessDashboards.Link("123456789012", "eu-west-1", "00fabcd", "00frun", 2,
                NOW.plus(Duration.ofHours(1)).getEpochSecond()), verified(query));
    }

    @Test
    void aLinkWhoseHourHasPassedOpensNothing() {
        Map<String, String> query = query(dashboards.url("123456789012", "us-east-1", "app", "run", 1));

        clock.advance(Duration.ofMinutes(59));
        assertNotNull(verified(query));
        clock.advance(Duration.ofMinutes(1));
        assertNull(verified(query));
    }

    @Test
    void aLinkWithAnyOfItsPartsChangedOpensNothing() {
        Map<String, String> query = query(dashboards.url("123456789012", "us-east-1", "app", "run", 1));

        for (String part : new String[]{"account", "region", "application", "jobRun", "attempt", "expires"}) {
            Map<String, String> changed = new LinkedHashMap<>(query);
            changed.put(part, part.equals("attempt") || part.equals("expires")
                    ? String.valueOf(Long.parseLong(query.get(part)) + 1) : query.get(part) + "x");
            assertNull(verified(changed), part);
        }
        Map<String, String> unsigned = new LinkedHashMap<>(query);
        unsigned.remove("signature");
        assertNull(verified(unsigned));
        Map<String, String> malformed = new LinkedHashMap<>(query);
        malformed.put("attempt", "one");
        assertNull(verified(malformed));
    }

    @Test
    void aLinkFromAnotherProcessesKeyOpensNothing() {
        EmrServerlessDashboards restarted = new EmrServerlessDashboards("http://localhost:4566", clock,
                "another process's key, 32 bytes".getBytes(StandardCharsets.US_ASCII));
        Map<String, String> query = query(restarted.url("123456789012", "us-east-1", "app", "run", 1));

        assertNull(verified(query));
    }

    private EmrServerlessDashboards.Link verified(Map<String, String> query) {
        return dashboards.verified(query.get("account"), query.get("region"), query.get("application"),
                query.get("jobRun"), query.get("attempt"), query.get("expires"), query.get("signature"));
    }

    private static Map<String, String> query(String url) {
        Map<String, String> query = new LinkedHashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            int equals = pair.indexOf('=');
            query.put(pair.substring(0, equals),
                    URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return query;
    }
}
