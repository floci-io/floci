package io.github.hectorvent.floci.services.ecs.exec;

import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The token gate in front of an interactive shell. A session is claimed once, by the exact token
 * that was minted for it, never by a replay of that token, and never once it has outlived its TTL.
 */
class EcsExecSessionRegistryTest {

    private static final String TASK_ARN =
            "arn:aws:ecs:us-east-1:000000000000:task/my-cluster/0123456789abcdef";
    private static final String CLUSTER_ARN =
            "arn:aws:ecs:us-east-1:000000000000:cluster/my-cluster";

    private final MutableClock clock = new MutableClock();
    private final EcsExecSessionRegistry registry = new EcsExecSessionRegistry(clock);

    @Test
    void claimWithTheMintedToken() {
        ExecSession created = mint();

        Optional<ExecSession> claimed = registry.claim(created.sessionId(), created.tokenValue());

        assertTrue(claimed.isPresent());
        assertEquals("deadbeef", claimed.get().runtimeId());
        assertEquals(List.of("/bin/sh"), claimed.get().command());
    }

    @Test
    void claim_wrongToken_isRejectedAndLeavesTheSession() {
        ExecSession created = mint();

        assertTrue(registry.claim(created.sessionId(), "not-the-token").isEmpty());
        assertTrue(registry.find(created.sessionId()).isPresent(),
                "a failed claim must not let a stranger cancel somebody else's session");
        assertTrue(registry.claim(created.sessionId(), created.tokenValue()).isPresent());
    }

    @Test
    void claim_tokenOfADifferentLength_isRejected() {
        ExecSession created = mint();

        assertTrue(registry.claim(created.sessionId(), created.tokenValue() + "x").isEmpty());
        assertTrue(registry.claim(created.sessionId(), "").isEmpty());
    }

    @Test
    void claim_missingToken_isRejected() {
        ExecSession created = mint();

        assertTrue(registry.claim(created.sessionId(), null).isEmpty());
    }

    @Test
    void claim_unknownSession_isRejected() {
        assertTrue(registry.claim("ecs-execute-command-nothingtoseehere", "any").isEmpty());
    }

    @Test
    void claim_isSingleUse() {
        ExecSession created = mint();

        assertTrue(registry.claim(created.sessionId(), created.tokenValue()).isPresent());
        assertTrue(registry.claim(created.sessionId(), created.tokenValue()).isEmpty(),
                "a replayed token must not open a second channel");
        assertTrue(registry.find(created.sessionId()).isEmpty());
    }

    @Test
    void everySessionGetsItsOwnToken() {
        ExecSession first = mint();
        ExecSession second = mint();

        assertNotEquals(first.sessionId(), second.sessionId());
        assertNotEquals(first.tokenValue(), second.tokenValue());
        assertTrue(registry.claim(first.sessionId(), second.tokenValue()).isEmpty());
    }

    @Test
    void clearDropsEveryOpenSession() {
        ExecSession created = mint();

        registry.clear();

        assertFalse(registry.find(created.sessionId()).isPresent());
    }

    @Test
    void claim_sessionPastItsTtl_isRejected() {
        ExecSession created = mint();

        clock.advance(EcsExecSessionRegistry.SESSION_TTL.plusSeconds(1));

        assertTrue(registry.claim(created.sessionId(), created.tokenValue()).isEmpty(),
                "a session nobody connected to must stop being claimable once it expires");
        assertTrue(registry.find(created.sessionId()).isEmpty(),
                "an expired session must be dropped, not left holding its token");
    }

    @Test
    void claim_sessionWithinItsTtl_stillWorks() {
        ExecSession created = mint();

        clock.advance(EcsExecSessionRegistry.SESSION_TTL.minusSeconds(1));

        assertTrue(registry.claim(created.sessionId(), created.tokenValue()).isPresent());
    }

    @Test
    void find_sessionPastItsTtl_isEmpty() {
        ExecSession created = mint();

        clock.advance(EcsExecSessionRegistry.SESSION_TTL.plusSeconds(1));

        assertTrue(registry.find(created.sessionId()).isEmpty(),
                "the channel upgrade must refuse an expired session instead of opening a channel");
    }

    private ExecSession mint() {
        return registry.create(TASK_ARN, CLUSTER_ARN, "app",
                TASK_ARN.replace(":task/", ":container/"), "deadbeef", List.of("/bin/sh"), true);
    }
}
