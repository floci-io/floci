package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.cognito.model.CognitoAuthEvent;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CognitoAuthEventsServiceTest {

    private InMemoryStorage<String, CognitoAuthEvent> authEvents;
    private CognitoService service;

    @BeforeEach
    void setUp() {
        authEvents = new InMemoryStorage<>();
        AcmService acmService = mock(AcmService.class);
        CognitoCertificateStubs.everyCertificateIsIssued(acmService);
        // A frozen clock: every event is created in the same millisecond, so only the sequence orders them.
        Clock frozen = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        service = new CognitoService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), authEvents, "http://localhost:4566", null,
                new RegionResolver("us-east-1", "000000000000"), null, acmService, null, null, null, frozen, true,
                null);
    }

    @Test
    void onlyPlusWithAuditOrEnforcedRecordsEvents() {
        assertFalse(CognitoService.signInEventsEnabled(pool(null, null)));
        assertFalse(CognitoService.signInEventsEnabled(pool("ESSENTIALS", "AUDIT")));
        assertFalse(CognitoService.signInEventsEnabled(pool("LITE", "ENFORCED")));
        assertFalse(CognitoService.signInEventsEnabled(pool("PLUS", "OFF")));
        assertFalse(CognitoService.signInEventsEnabled(pool("PLUS", null)));
        assertTrue(CognitoService.signInEventsEnabled(pool("PLUS", "AUDIT")));
        assertTrue(CognitoService.signInEventsEnabled(pool("PLUS", "ENFORCED")));
    }

    @Test
    void ineligiblePoolRecordsNothing() {
        UserPool pool = createPool("ESSENTIALS", "AUDIT");
        CognitoUser user = createUser(pool, "alice");

        assertNull(service.recordSignInEvent(pool, user, true));
        assertEquals(0, authEvents.keys().size());
    }

    @Test
    void eventsCreatedInTheSameMillisecondListNewestFirstAcrossPages() {
        UserPool pool = createPool("PLUS", "AUDIT");
        CognitoUser user = createUser(pool, "alice");
        List<String> recorded = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            recorded.add(service.recordSignInEvent(pool, user, i % 2 == 0));
        }

        List<String> listed = new ArrayList<>();
        String token = null;
        do {
            Map<String, Object> page = service.adminListUserAuthEvents(pool.getId(), "alice", 2, token);
            for (Object event : (List<?>) page.get("AuthEvents")) {
                listed.add((String) ((Map<?, ?>) event).get("EventId"));
            }
            token = (String) page.get("NextToken");
        } while (token != null);

        List<String> newestFirst = new ArrayList<>(recorded);
        Collections.reverse(newestFirst);
        assertEquals(newestFirst, listed);
    }

    @Test
    void tokenSurvivesAnEventRecordedBetweenPages() {
        UserPool pool = createPool("PLUS", "AUDIT");
        CognitoUser user = createUser(pool, "alice");
        String oldest = service.recordSignInEvent(pool, user, true);
        String middle = service.recordSignInEvent(pool, user, true);
        String newest = service.recordSignInEvent(pool, user, true);

        Map<String, Object> first = service.adminListUserAuthEvents(pool.getId(), "alice", 2, null);
        service.recordSignInEvent(pool, user, false);
        Map<String, Object> second = service.adminListUserAuthEvents(pool.getId(), "alice", 2,
                (String) first.get("NextToken"));

        List<?> secondEvents = (List<?>) second.get("AuthEvents");
        assertEquals(1, secondEvents.size());
        assertEquals(oldest, ((Map<?, ?>) secondEvents.get(0)).get("EventId"));
        assertNotNull(middle);
        assertNotNull(newest);
        assertFalse(second.containsKey("NextToken"));
    }

    @Test
    void completingAnEventMarksPassAndAddsTheMfaChallenge() {
        UserPool pool = createPool("PLUS", "ENFORCED");
        CognitoUser user = createUser(pool, "alice");
        String eventId = service.recordSignInEvent(pool, user, true);

        service.completeSignInEvent(pool.getId(), user, eventId, true);

        List<?> events = (List<?>) service.adminListUserAuthEvents(pool.getId(), "alice", null, null)
                .get("AuthEvents");
        Map<?, ?> event = (Map<?, ?>) events.get(0);
        assertEquals("Pass", event.get("EventResponse"));
        assertEquals(2, ((List<?>) event.get("ChallengeResponses")).size());
        assertFalse(event.containsKey("EventRisk"));
    }

    @Test
    void deletingTheUserOrThePoolDeletesItsEvents() {
        UserPool pool = createPool("PLUS", "AUDIT");
        CognitoUser alice = createUser(pool, "alice");
        CognitoUser bob = createUser(pool, "bob");
        service.recordSignInEvent(pool, alice, true);
        service.recordSignInEvent(pool, bob, true);
        assertEquals(2, authEvents.keys().size());

        service.adminDeleteUser(pool.getId(), "alice");
        assertEquals(1, authEvents.keys().size());

        service.deleteUserPool(pool.getId());
        assertEquals(0, authEvents.keys().size());
    }

    @Test
    void delimiterOverlappingSubsKeepTheirOwnEvents() {
        UserPool pool = createPool("PLUS", "AUDIT");
        CognitoUser shortSub = createUserWithSub(pool, "short-user", "a");
        CognitoUser longSub = createUserWithSub(pool, "long-user", "a::b");
        String shortEvent = service.recordSignInEvent(pool, shortSub, true);
        String longEvent = service.recordSignInEvent(pool, longSub, true);

        assertEquals(List.of(shortEvent), listedEventIds(pool.getId(), "short-user"));
        assertEquals(List.of(longEvent), listedEventIds(pool.getId(), "long-user"));

        service.adminDeleteUser(pool.getId(), "short-user");

        assertEquals(List.of(longEvent), listedEventIds(pool.getId(), "long-user"));
        assertEquals(1, authEvents.keys().size());
    }

    @Test
    void delimiterOverlappingPoolIdsKeepTheirOwnEvents() {
        UserPool outer = createPoolWithId("us-east-1_pool");
        UserPool inner = createPoolWithId("us-east-1_pool::x");
        CognitoUser outerUser = createUserWithSub(outer, "alice", "x::alice");
        CognitoUser innerUser = createUserWithSub(inner, "alice", "alice");
        String outerEvent = service.recordSignInEvent(outer, outerUser, true);
        String innerEvent = service.recordSignInEvent(inner, innerUser, true);

        assertEquals(List.of(outerEvent), listedEventIds(outer.getId(), "alice"));
        assertEquals(List.of(innerEvent), listedEventIds(inner.getId(), "alice"));

        service.deleteUserPool(outer.getId());

        assertEquals(List.of(innerEvent), listedEventIds(inner.getId(), "alice"));
        assertEquals(1, authEvents.keys().size());
    }

    @Test
    void signInEventsOfADeletedUserAreNotRecreated() {
        UserPool pool = createPool("PLUS", "AUDIT");
        CognitoUser stale = createUser(pool, "alice");
        String eventId = service.recordSignInEvent(pool, stale, true);
        service.adminDeleteUser(pool.getId(), "alice");
        assertEquals(0, authEvents.keys().size());

        service.completeSignInEvent(pool.getId(), stale, eventId, true);
        assertEquals(0, authEvents.keys().size());

        assertNull(service.recordSignInEvent(pool, stale, true));
        assertEquals(0, authEvents.keys().size());
    }

    @Test
    void ineligibleListingFailsBeforeTheUserIsLookedUp() {
        UserPool pool = createPool("ESSENTIALS", null);

        AwsException error = assertThrows(AwsException.class,
                () -> service.adminListUserAuthEvents(pool.getId(), "nobody", null, null));
        assertEquals("UserPoolAddOnNotEnabledException", error.getErrorCode());
    }

    private UserPool createPool(String tier, String mode) {
        Map<String, Object> request = new HashMap<>();
        request.put("PoolName", "events");
        request.put("UserPoolTier", tier);
        if (mode != null) {
            request.put("UserPoolAddOns", Map.of("AdvancedSecurityMode", mode));
        }
        return service.createUserPool(request, "us-east-1");
    }

    private CognitoUser createUser(UserPool pool, String username) {
        service.adminCreateUser(pool.getId(), username, Map.of(), "TempPass1!");
        return service.adminGetUser(pool.getId(), username);
    }

    private List<String> listedEventIds(String poolId, String username) {
        List<String> ids = new ArrayList<>();
        for (Object event : (List<?>) service.adminListUserAuthEvents(poolId, username, null, null)
                .get("AuthEvents")) {
            ids.add((String) ((Map<?, ?>) event).get("EventId"));
        }
        return ids;
    }

    private UserPool createPoolWithId(String id) {
        Map<String, Object> request = new HashMap<>();
        request.put("PoolName", "events");
        request.put("UserPoolTier", "PLUS");
        request.put("UserPoolAddOns", Map.of("AdvancedSecurityMode", "AUDIT"));
        request.put("UserPoolTags", new HashMap<>(Map.of("floci:override-id", id)));
        return service.createUserPool(request, "us-east-1");
    }

    private CognitoUser createUserWithSub(UserPool pool, String username, String sub) {
        service.adminCreateUser(pool.getId(), username, Map.of("sub", sub), "TempPass1!");
        return service.adminGetUser(pool.getId(), username);
    }

    private static UserPool pool(String tier, String mode) {
        UserPool pool = new UserPool();
        pool.setUserPoolTier(tier);
        if (mode != null) {
            pool.setUserPoolAddOns(Map.of("AdvancedSecurityMode", mode));
        }
        return pool;
    }
}
