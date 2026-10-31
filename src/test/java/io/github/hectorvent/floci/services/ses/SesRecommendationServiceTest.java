package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.SesRecommendationService.Recommendation;
import io.github.hectorvent.floci.services.ses.model.AccountVdmAttributes;
import io.github.hectorvent.floci.services.ses.model.Identity;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SesRecommendationServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private MutableClock clock;
    private SesIdentityService identities;
    private SesAccountService accounts;
    private SesRecommendationService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        // No Route53, so no domain has DMARC or BIMI records.
        identities = new SesIdentityService(new InMemoryStorage<>(), null, clock);
        accounts = new SesAccountService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());
        accounts.putAccountVdmAttributes(REGION, new AccountVdmAttributes(true, false, false));
        service = new SesRecommendationService(identities, accounts, new RegionResolver(REGION, ACCOUNT), clock);
    }

    private Identity saveDomain(String domain, boolean signing, Instant created) {
        Identity identity = new Identity(domain, "Domain");
        identity.setDkimEnabled(signing);
        identity.setDkimTokens(List.of("token"));
        identity.setCreatedAt(created);
        identities.save(identity, REGION);
        return identity;
    }

    private static String arn(String domain) {
        return "arn:aws:ses:" + REGION + ":" + ACCOUNT + ":identity/" + domain;
    }

    private List<Recommendation> list(Map<String, String> filter) {
        return service.list(filter, null, null, REGION).items();
    }

    private static List<String> codes(List<Recommendation> recommendations) {
        return recommendations.stream().map(r -> r.resourceArn().substring(r.resourceArn().indexOf('/') + 1)
                + ":" + r.description()).toList();
    }

    @Test
    void listsNewestIdentityFirstWithCodesOrderedWithin() {
        saveDomain("old.example", true, Instant.parse("2024-01-01T00:00:00Z"));
        saveDomain("new.example", false, Instant.parse("2025-01-01T00:00:00Z"));
        assertEquals(List.of("new.example:BIMI1", "new.example:DKIM1", "new.example:DMARC1",
                        "old.example:BIMI1", "old.example:DMARC1"),
                codes(list(Map.of())));
    }

    @Test
    void everyRecommendationIsOpenDatedFromItsIdentityAndUpdatedNow() {
        Instant created = Instant.parse("2024-01-01T00:00:00Z");
        saveDomain("example.com", true, created);
        Instant before = clock.instant();
        Recommendation recommendation = list(Map.of("TYPE", "DMARC")).getFirst();
        assertEquals(arn("example.com"), recommendation.resourceArn());
        assertEquals("OPEN", recommendation.status());
        assertEquals("HIGH", recommendation.impact());
        assertEquals(created, recommendation.createdTimestamp());
        // MutableClock ticks a millisecond per read, so the list's own read comes after this one.
        assertTrue(recommendation.lastUpdatedTimestamp().isAfter(before));
    }

    @Test
    void emptyWithoutVdm() {
        saveDomain("example.com", false, clock.instant());
        accounts.putAccountVdmAttributes(REGION, new AccountVdmAttributes(false, false, false));
        assertEquals(List.of(), list(Map.of()));
        assertEquals(List.of(), service.list(Map.of(), null, null, "eu-west-3").items());
    }

    @Test
    void filterIsValidatedWithoutVdm() {
        accounts.putAccountVdmAttributes(REGION, new AccountVdmAttributes(false, false, false));
        assertMessage("Invalid recommendation TYPE specified", Map.of("TYPE", "ENGAGEMENT"));
    }

    @Test
    void unverifiedDomainsAndEmailAddressesAreNotAdvised() {
        Identity pending = saveDomain("pending.example", true, clock.instant());
        pending.setVerificationStatus("Pending");
        identities.save(pending, REGION);
        identities.save(new Identity("alice@example.com", "EmailAddress"), REGION);
        assertEquals(List.of(), list(Map.of()));
    }

    @Test
    void typeFilterIgnoresImpact() {
        saveDomain("example.com", false, clock.instant());
        assertEquals(List.of("example.com:DKIM1"), codes(list(Map.of("TYPE", "DKIM", "IMPACT", "LOW"))));
    }

    @Test
    void impactFilterAppliesWithoutType() {
        saveDomain("example.com", false, clock.instant());
        assertEquals(List.of("example.com:BIMI1"), codes(list(Map.of("IMPACT", "LOW"))));
    }

    @Test
    void statusFilterFixedIsEmpty() {
        saveDomain("example.com", false, clock.instant());
        assertEquals(List.of(), list(Map.of("STATUS", "FIXED")));
        assertEquals(3, list(Map.of("STATUS", "OPEN")).size());
    }

    @Test
    void resourceArnFilterMatchesTheIdentityArn() {
        saveDomain("a.example", true, clock.instant());
        saveDomain("b.example", true, clock.instant());
        assertEquals(List.of("b.example:BIMI1", "b.example:DMARC1"),
                codes(list(Map.of("RESOURCE_ARN", arn("b.example")))));
        assertEquals(List.of(), list(Map.of("RESOURCE_ARN", arn("missing.example"))));
    }

    @Test
    void invalidFilterValuesNameTheirKey() {
        assertMessage("Invalid recommendation TYPE specified", Map.of("TYPE", "ENGAGEMENT"));
        assertMessage("Invalid recommendation IMPACT specified", Map.of("IMPACT", "MEDIUM"));
        assertMessage("Invalid recommendation STATUS specified", Map.of("STATUS", "open"));
    }

    @Test
    void impactIsValidatedEvenWhenTypeMakesItIgnored() {
        assertMessage("Invalid recommendation IMPACT specified", Map.of("TYPE", "DKIM", "IMPACT", "high"));
    }

    @Test
    void resourceArnMustBeAnSesArnOfTheCallerAccount() {
        assertMessage("Filter resource is invalid", Map.of("RESOURCE_ARN", "not-an-arn"));
        assertMessage("Filter resource is invalid",
                Map.of("RESOURCE_ARN", "arn:aws:sns:us-east-1:000000000000:identity/example.com"));
        assertMessage("Provided resource ARN does not match the caller account",
                Map.of("RESOURCE_ARN", "arn:aws:ses:us-east-1:111111111111:identity/example.com"));
    }

    @Test
    void pagesOfTheDefaultFifty() {
        for (int i = 0; i < 26; i++) {
            saveDomain("d" + i + ".example", true, clock.instant().plus(Duration.ofSeconds(i)));
        }
        PaginatedResult<Recommendation> first = service.list(Map.of(), null, null, REGION);
        assertEquals(50, first.items().size());
        assertNotNull(first.nextToken());
        PaginatedResult<Recommendation> second = service.list(Map.of(), null, first.nextToken(), REGION);
        assertEquals(2, second.items().size());
        assertNull(second.nextToken());
    }

    @Test
    void tokenIsBoundToItsFilter() {
        saveDomain("a.example", true, clock.instant());
        saveDomain("b.example", true, clock.instant().plusSeconds(1));
        String token = service.list(Map.of("STATUS", "OPEN"), 1, null, REGION).nextToken();
        assertNotNull(token);
        assertEquals(1, service.list(Map.of("STATUS", "OPEN"), 1, token, REGION).items().size());
        assertPagingMessage("Failed to deserialize token.", null, token);
        AwsException e = assertThrows(AwsException.class,
                () -> service.list(Map.of("TYPE", "BIMI"), 1, token, REGION));
        assertEquals("Failed to deserialize token.", e.getMessage());
    }

    @Test
    void pageSizeOutsideItsBounds() {
        assertPagingMessage("The PageSize should be between 1 and 1000", 0, null);
        assertPagingMessage("The PageSize should be between 1 and 1000", 1001, null);
    }

    @Test
    void unreadableOrEmptyToken() {
        assertPagingMessage("Failed to deserialize token.", null, "garbage");
        assertPagingMessage("Failed to deserialize token.", null, "");
    }

    private void assertMessage(String message, Map<String, String> filter) {
        AwsException e = assertThrows(AwsException.class, () -> service.list(filter, null, null, REGION));
        assertEquals("BadRequestException", e.getErrorCode());
        assertEquals(message, e.getMessage());
    }

    private void assertPagingMessage(String message, Integer pageSize, String nextToken) {
        AwsException e = assertThrows(AwsException.class,
                () -> service.list(Map.of(), pageSize, nextToken, REGION));
        assertEquals("BadRequestException", e.getErrorCode());
        assertEquals(message, e.getMessage());
    }
}
