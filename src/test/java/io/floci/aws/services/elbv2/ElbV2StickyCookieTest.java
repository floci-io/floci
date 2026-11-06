package io.floci.aws.services.elbv2;

import io.floci.aws.services.elbv2.model.TargetDescription;
import io.floci.aws.services.elbv2.model.TargetGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ElbV2StickyCookieTest {

    private static final String SCOPE = "load-balancer\ntarget-group";
    private final Clock clock = mock(Clock.class);
    private final TargetGroup group = new TargetGroup();
    private final TargetDescription first = target(8080);
    private final TargetDescription second = target(8081);
    private Instant now = Instant.parse("2026-10-19T00:00:00Z");
    private ElbV2StickyCookie cookies;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenAnswer(ignored -> now);
        cookies = new ElbV2StickyCookie(clock);
        group.setProtocol("HTTP");
        group.setTargetType("ip");
        group.setPort(8080);
        group.getAttributes().put("stickiness.enabled", "true");
    }

    @Test
    void bindsTheEffectivePortAndOnlyCurrentCandidates() {
        String value = value(first);
        assertSame(first, select("AWSALB=" + value, List.of(first, second), 10));
        assertNull(select("AWSALB=" + value, List.of(second), 10));
        first.setPort(null);
        assertSame(first, select("AWSALB=" + value, List.of(first), 10));
        group.setPort(8082);
        assertNull(select("AWSALB=" + value, List.of(first), 10));
    }

    @Test
    void expiresAtTheConfiguredDurationAndRejectsFutureCookies() {
        String value = value(first);
        now = now.plusSeconds(4);
        assertSame(first, select("AWSALB=" + value, List.of(first), 5));
        String refreshed = value(first);
        now = now.plusSeconds(1);
        assertNull(select("AWSALB=" + value, List.of(first), 5));
        assertSame(first, select("AWSALB=" + refreshed, List.of(first), 5));
        now = now.minusSeconds(6);
        assertNull(select("AWSALB=" + value, List.of(first), 5));
    }

    @Test
    void rejectsModifiedMalformedOversizedAndOtherScopeCookies() {
        String value = value(first);
        byte[] modified = Base64.getUrlDecoder().decode(value);
        modified[modified.length - 1] ^= 1;
        String tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(modified);
        for (String invalid : List.of(tampered, "not%encoded", "", "a", "x".repeat(2049))) {
            assertNull(select("AWSALB=" + invalid, List.of(first), 10));
        }
        assertNull(cookies.target(List.of("AWSALB=" + value), "other-load-balancer\ntarget-group",
                group, List.of(first), 10));
        assertNull(cookies.target(List.of("AWSALB=" + value), "load-balancer\nother-group",
                group, List.of(first), 10));
        assertNull(new ElbV2StickyCookie(clock).target(List.of("AWSALB=" + value), SCOPE,
                group, List.of(first), 10));
    }

    @Test
    void corsCookieTakesPrecedenceAcrossCookieHeaders() {
        String regular = value(first);
        String cors = value(second);
        assertSame(second, cookies.target(List.of("other=one; AWSALB=" + regular, "AWSALBCORS=" + cors),
                SCOPE, group, List.of(first, second), 10));
        assertNull(select("AWSALB=" + regular + "; AWSALBCORS=invalid", List.of(first, second), 10));
        assertNull(select("XAWSALB=" + regular, List.of(first), 10));
    }

    @Test
    void emitsBothCookiesWithSevenDayExpiresAndCorsAttributes() {
        List<String> headers = cookies.headers(SCOPE, group, first);
        assertEquals(2, headers.size());
        assertEquals(headers.get(0).substring("AWSALB".length()),
                headers.get(1).substring("AWSALBCORS".length()).replace("; SameSite=None; Secure", ""));
        assertTrue(headers.get(0).contains("; Expires=Mon, 26 Oct 2026 00:00:00 GMT; Path=/"));
        assertTrue(headers.get(1).endsWith("; SameSite=None; Secure"));
        assertFalse(headers.get(0).contains("Max-Age"));
    }

    @Test
    void enablesOnlySupportedDurationBasedConfigurations() {
        assertEquals(86400, ElbV2StickyCookie.durationSeconds(group));
        for (String duration : List.of("0", "604801", "-1", "oops")) {
            group.getAttributes().put("stickiness.lb_cookie.duration_seconds", duration);
            assertEquals(0, ElbV2StickyCookie.durationSeconds(group));
        }
        group.getAttributes().put("stickiness.lb_cookie.duration_seconds", "604800");
        assertEquals(604800, ElbV2StickyCookie.durationSeconds(group));
        group.getAttributes().put("stickiness.type", "app_cookie");
        assertEquals(0, ElbV2StickyCookie.durationSeconds(group));
        group.getAttributes().put("stickiness.type", "lb_cookie");
        group.getAttributes().put("load_balancing.algorithm.type", "weighted_random");
        assertEquals(0, ElbV2StickyCookie.durationSeconds(group));
        group.getAttributes().remove("load_balancing.algorithm.type");
        group.getAttributes().put("load_balancing.cross_zone.enabled", "false");
        assertEquals(0, ElbV2StickyCookie.durationSeconds(group));
        group.getAttributes().remove("load_balancing.cross_zone.enabled");
        group.setProtocol("TCP");
        assertEquals(0, ElbV2StickyCookie.durationSeconds(group));
    }

    @Test
    void crossZoneOnlyUsesTheTargetGroupSetting() {
        assertEquals(86400, ElbV2StickyCookie.durationSeconds(group));
        for (String enabled : List.of("true", "use_load_balancer_configuration")) {
            group.getAttributes().put("load_balancing.cross_zone.enabled", enabled);
            assertEquals(86400, ElbV2StickyCookie.durationSeconds(group));
        }
        group.getAttributes().put("load_balancing.cross_zone.enabled", "false");
        assertEquals(0, ElbV2StickyCookie.durationSeconds(group));
    }

    private String value(TargetDescription target) {
        String header = cookies.headers(SCOPE, group, target).getFirst();
        return header.substring("AWSALB=".length(), header.indexOf(';'));
    }

    private TargetDescription select(String header, List<TargetDescription> candidates, long duration) {
        return cookies.target(List.of(header), SCOPE, group, candidates, duration);
    }

    private static TargetDescription target(int port) {
        TargetDescription target = new TargetDescription();
        target.setId("127.0.0.1");
        target.setPort(port);
        return target;
    }
}
