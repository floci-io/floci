package io.github.hectorvent.floci.services.quicksight;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuickSightServiceTest {
    private static final String ACCOUNT = "000000000000";
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = mock(Clock.class);
    private QuickSightService service;
    private String principal;

    @BeforeEach
    void setUp() throws Exception {
        when(clock.instant()).thenReturn(START);
        service = new QuickSightService(mapper, new RegionResolver("eu-west-2", ACCOUNT),
                AccountAwareStorageBackend.inMemory(ACCOUNT), AccountAwareStorageBackend.inMemory(ACCOUNT),
                "https://localhost:3443", clock);
        principal = service.registerUser(ACCOUNT, "default", mapper.readTree("""
                {"UserName":"reader","Email":"reader@example.com","UserRole":"READER","IdentityType":"QUICKSIGHT"}
                """)).path("User").path("Arn").asText();
        ObjectNode dashboard = mapper.createObjectNode().put("Name", "One");
        dashboard.putObject("Definition");
        dashboard.putArray("Permissions").addObject().put("Principal", principal)
                .putArray("Actions").add("quicksight:DescribeDashboard");
        service.create(ACCOUNT, "dashboard", "one", dashboard);
    }

    @Test
    void embedUrlExpiresAtTheFiveMinuteRedemptionDeadline() {
        String token = token(service.generateEmbedUrl(ACCOUNT, embedRequest()).path("EmbedUrl").asText());
        when(clock.instant()).thenReturn(START.plusSeconds(300));
        AwsException error = assertThrows(AwsException.class,
                () -> service.redeem(token, token, "one", "https://localhost:3443", null));
        assertEquals(403, error.getHttpStatus());
    }

    @Test
    void redemptionRejectsADashboardThatWasReplacedWithoutSharing() throws Exception {
        String token = token(service.generateEmbedUrl(ACCOUNT, embedRequest()).path("EmbedUrl").asText());
        service.delete(ACCOUNT, "dashboard", "one");
        service.create(ACCOUNT, "dashboard", "one", mapper.readTree("{\"Name\":\"Private replacement\",\"Definition\":{}}"));
        AwsException error = assertThrows(AwsException.class,
                () -> service.redeem(token, token, "one", "https://localhost:3443", null));
        assertEquals(403, error.getHttpStatus());
    }

    @Test
    void searchRejectsAnUnregisteredUser() {
        ObjectNode request = mapper.createObjectNode();
        request.putArray("Filters").addObject().put("Name", "QUICKSIGHT_USER")
                .put("Operator", "StringEquals").put("Value", principal + "-missing");
        AwsException error = assertThrows(AwsException.class, () -> service.searchDashboards(ACCOUNT, request));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
    }

    @Test
    void redeemedSessionsRequireTheirCookieAndExpireAtTheSessionDeadline() {
        String token = token(service.generateEmbedUrl(ACCOUNT, embedRequest()).path("EmbedUrl").asText());
        assertThrows(AwsException.class, () -> service.redeem(token, token, "one", "https://other.example", null));
        assertThrows(AwsException.class, () -> service.redeem(token, "wrong", "one", "https://localhost:3443", null));
        assertThrows(AwsException.class, () -> service.redeem(token, token, "other", "https://localhost:3443", null));
        String cookie = service.redeem(token, token, "one", "https://localhost:3443", null).path("_SessionCookie").asText();
        assertThrows(AwsException.class, () -> service.redeem(token, token, "one", "https://localhost:3443", null));
        assertThrows(AwsException.class, () -> service.redeem(token, token, "one", "https://localhost:3443", token));
        when(clock.instant()).thenReturn(START.plusSeconds(899));
        assertEquals("One", service.redeem(token, null, "one", "https://localhost:3443", cookie).path("Name").asText());
        when(clock.instant()).thenReturn(START.plusSeconds(900));
        assertThrows(AwsException.class, () -> service.redeem(token, null, "one", "https://localhost:3443", cookie));
    }

    @Test
    void embeddingRejectsUnknownUsersMissingDashboardsAndUnsharedDashboards() throws Exception {
        ObjectNode request = embedRequest().put("UserArn", principal + "-missing");
        assertEquals(404, assertThrows(AwsException.class, () -> service.generateEmbedUrl(ACCOUNT, request)).getHttpStatus());
        request.put("UserArn", principal);
        request.withObject("/ExperienceConfiguration/Dashboard").put("InitialDashboardId", "missing");
        assertEquals(404, assertThrows(AwsException.class, () -> service.generateEmbedUrl(ACCOUNT, request)).getHttpStatus());
        service.create(ACCOUNT, "dashboard", "private", mapper.readTree("{\"Name\":\"Private\",\"Definition\":{}}"));
        request.withObject("/ExperienceConfiguration/Dashboard").put("InitialDashboardId", "private");
        assertEquals(401, assertThrows(AwsException.class, () -> service.generateEmbedUrl(ACCOUNT, request)).getHttpStatus());
    }

    @Test
    void embeddingEnforcesDomainAndLifetimeLimits() {
        ObjectNode request = embedRequest().put("SessionLifetimeInMinutes", 14);
        assertEquals("SessionLifetimeInMinutesInvalidException", assertThrows(AwsException.class, () -> service.generateEmbedUrl(ACCOUNT, request)).getErrorCode());
        request.put("SessionLifetimeInMinutes", 601);
        assertThrows(AwsException.class, () -> service.generateEmbedUrl(ACCOUNT, request));
        request.put("SessionLifetimeInMinutes", 15);
        request.putArray("AllowedDomains").add("http://remote.example");
        assertThrows(AwsException.class, () -> service.generateEmbedUrl(ACCOUNT, request));
        request.putArray("AllowedDomains");
        assertThrows(AwsException.class, () -> service.generateEmbedUrl(ACCOUNT, request));
    }

    @Test
    void resetRemovesBothResourcesAndEmbeddingSessions() {
        String token = token(service.generateEmbedUrl(ACCOUNT, embedRequest()).path("EmbedUrl").asText());
        service.clear();
        assertEquals(404, assertThrows(AwsException.class, () -> service.describe(ACCOUNT, "dashboard", "one")).getHttpStatus());
        assertEquals(403, assertThrows(AwsException.class,
                () -> service.redeem(token, token, "one", "https://localhost:3443", null)).getHttpStatus());
    }

    @Test
    void cleanupRemovesExpiredSessionsAcrossAccountsAndPreservesLiveSessions() throws Exception {
        String abandoned = token(service.generateEmbedUrl(ACCOUNT, embedRequest()).path("EmbedUrl").asText());
        String foreignAccount = "111122223333";
        String foreignUser = service.registerUser(foreignAccount, "default", mapper.readTree("""
                {"UserName":"reader","Email":"reader@example.com","UserRole":"READER","IdentityType":"QUICKSIGHT"}
                """)).path("User").path("Arn").asText();
        ObjectNode foreignDashboard = mapper.createObjectNode().put("Name", "Foreign");
        foreignDashboard.putObject("Definition");
        foreignDashboard.putArray("Permissions").addObject().put("Principal", foreignUser)
                .putArray("Actions").add("quicksight:DescribeDashboard");
        service.create(foreignAccount, "dashboard", "one", foreignDashboard);
        String foreign = token(service.generateEmbedUrl(foreignAccount, embedRequest().put("UserArn", foreignUser))
                .path("EmbedUrl").asText());
        String foreignCookie = service.redeem(foreign, foreign, "one", "https://localhost:3443", null)
                .path("_SessionCookie").asText();

        when(clock.instant()).thenReturn(START.plusSeconds(299));
        assertEquals(0, service.cleanupExpiredSessions(clock.instant()));
        String fresh = token(service.generateEmbedUrl(ACCOUNT, embedRequest()).path("EmbedUrl").asText());
        when(clock.instant()).thenReturn(START.plusSeconds(300));
        assertEquals(1, service.cleanupExpiredSessions(clock.instant()));
        assertEquals(0, service.cleanupExpiredSessions(clock.instant()));
        assertThrows(AwsException.class, () -> service.redeem(abandoned, abandoned, "one", "https://localhost:3443", null));
        String freshCookie = service.redeem(fresh, fresh, "one", "https://localhost:3443", null)
                .path("_SessionCookie").asText();

        when(clock.instant()).thenReturn(START.plusSeconds(600));
        assertEquals(0, service.cleanupExpiredSessions(clock.instant()));
        assertEquals("Foreign", service.redeem(foreign, null, "one", "https://localhost:3443", foreignCookie).path("Name").asText());
        when(clock.instant()).thenReturn(START.plusSeconds(900));
        assertEquals(1, service.cleanupExpiredSessions(clock.instant()));
        assertEquals(0, service.cleanupExpiredSessions(clock.instant()));
        assertThrows(AwsException.class, () -> service.redeem(foreign, null, "one", "https://localhost:3443", foreignCookie));
        assertEquals("One", service.redeem(fresh, null, "one", "https://localhost:3443", freshCookie).path("Name").asText());
        when(clock.instant()).thenReturn(START.plusSeconds(1200));
        assertEquals(1, service.cleanupExpiredSessions(clock.instant()));
        assertEquals(0, service.cleanupExpiredSessions(clock.instant()));
        assertThrows(AwsException.class, () -> service.redeem(fresh, null, "one", "https://localhost:3443", freshCookie));
        assertEquals("One", service.describe(ACCOUNT, "dashboard", "one").path("Dashboard").path("Name").asText());
    }

    private ObjectNode embedRequest() {
        ObjectNode request = mapper.createObjectNode().put("UserArn", principal).put("SessionLifetimeInMinutes", 15);
        request.putObject("ExperienceConfiguration").putObject("Dashboard").put("InitialDashboardId", "one");
        request.putArray("AllowedDomains").add("https://localhost:3443");
        return request;
    }

    private String token(String url) {
        return URI.create(url).getPath().split("/")[2];
    }
}
