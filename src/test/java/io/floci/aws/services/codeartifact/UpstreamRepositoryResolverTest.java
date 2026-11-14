package io.floci.aws.services.codeartifact;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code resolveSearchOrder}'s own javadoc works through the exact A/B/C/D counterexample this
 * test locks in: a depth-first walk would wrongly search a 2-hop match before a 1-hop sibling.
 */
class UpstreamRepositoryResolverTest {

    @Test
    void directUpstreamsAreSearchedBeforeAnyOfTheirOwnTransitiveUpstreams() {
        // A -> [B, C], B -> [D]. A depth-first walk reaches D (via B) before C, a direct upstream
        // of A; the correct, breadth-first order is B, C (both direct), then D.
        Map<String, List<String>> upstreams = Map.of(
                "A", List.of("B", "C"),
                "B", List.of("D"),
                "C", List.of(),
                "D", List.of());

        List<String> order = UpstreamRepositoryResolver.resolveSearchOrder("A", upstreams::get);

        assertEquals(List.of("B", "C", "D"), order);
    }

    @Test
    void aCycleTerminatesInsteadOfLoopingForever() {
        Map<String, List<String>> upstreams = Map.of(
                "A", List.of("B"),
                "B", List.of("A"));

        List<String> order = UpstreamRepositoryResolver.resolveSearchOrder("A", upstreams::get);

        assertEquals(List.of("B"), order);
    }

    @Test
    void aLongerCycleTerminatesWithoutRevisitingAnyRepository() {
        Map<String, List<String>> upstreams = Map.of(
                "A", List.of("B"),
                "B", List.of("C"),
                "C", List.of("A"));

        List<String> order = UpstreamRepositoryResolver.resolveSearchOrder("A", upstreams::get);

        assertEquals(List.of("B", "C"), order);
    }

    @Test
    void aRepositoryReachableThroughTwoPathsAppearsExactlyOnce() {
        // A -> [B, C], B -> [D], C -> [D]: D is reachable from both B and C, but must only be
        // searched once, at the position its first discovery (through B) places it.
        Map<String, List<String>> upstreams = Map.of(
                "A", List.of("B", "C"),
                "B", List.of("D"),
                "C", List.of("D"),
                "D", List.of());

        List<String> order = UpstreamRepositoryResolver.resolveSearchOrder("A", upstreams::get);

        assertEquals(List.of("B", "C", "D"), order);
    }

    @Test
    void theStartingRepositoryItselfIsNeverIncluded() {
        Map<String, List<String>> upstreams = Map.of("A", List.of("B"), "B", List.of());

        List<String> order = UpstreamRepositoryResolver.resolveSearchOrder("A", upstreams::get);

        assertEquals(List.of("B"), order);
        assertTrue(order.stream().noneMatch("A"::equals));
    }

    @Test
    void aChainLongerThanTheCapStopsAtTheCapRatherThanSearchingForever() {
        // A chain of 30 repositories, each the sole upstream of the previous one: R0 -> R1 -> ...
        // -> R29. The walk must stop at MAX_RESOLUTION_REPOSITORIES (25) rather than continuing
        // past AWS's own documented region-wide search ceiling.
        Map<String, List<String>> upstreams = new HashMap<>();
        for (int i = 0; i < 30; i++) {
            String name = "R" + i;
            String next = i + 1 < 30 ? "R" + (i + 1) : null;
            upstreams.put(name, next == null ? List.of() : List.of(next));
        }

        List<String> order = UpstreamRepositoryResolver.resolveSearchOrder("R0", upstreams::get);

        assertEquals(UpstreamRepositoryResolver.MAX_RESOLUTION_REPOSITORIES, order.size());
        assertEquals(List.of("R1", "R2", "R3", "R4", "R5", "R6", "R7", "R8", "R9", "R10", "R11",
                "R12", "R13", "R14", "R15", "R16", "R17", "R18", "R19", "R20", "R21", "R22", "R23",
                "R24", "R25"), order);
    }

    @Test
    void anUnknownRepositoryHasNoUpstreamsAndStopsTheWalkThere() {
        Map<String, List<String>> upstreams = Map.of("A", List.of("unknown"));

        List<String> order = UpstreamRepositoryResolver.resolveSearchOrder("A",
                name -> upstreams.getOrDefault(name, List.of()));

        assertEquals(List.of("unknown"), order);
    }

    @Test
    void isReachableFindsADirectUpstream() {
        Map<String, List<String>> upstreams = Map.of("A", List.of("B"), "B", List.of());

        assertTrue(UpstreamRepositoryResolver.isReachable("A", "B", upstreams::get));
    }

    @Test
    void isReachableFindsATransitiveUpstream() {
        Map<String, List<String>> upstreams = Map.of("A", List.of("B"), "B", List.of("C"), "C", List.of());

        assertTrue(UpstreamRepositoryResolver.isReachable("A", "C", upstreams::get));
    }

    @Test
    void isReachableIsFalseWhenTheTargetIsNotInTheGraphAtAll() {
        Map<String, List<String>> upstreams = Map.of("A", List.of("B"), "B", List.of());

        assertFalse(UpstreamRepositoryResolver.isReachable("A", "Z", upstreams::get));
    }

    @Test
    void isReachableTerminatesOnACycleThatNeverReachesTheTarget() {
        Map<String, List<String>> upstreams = Map.of("A", List.of("B"), "B", List.of("A"));

        assertFalse(UpstreamRepositoryResolver.isReachable("A", "Z", upstreams::get));
    }

    /**
     * Locks in the fix for the exact gap {@code resolveSearchOrder}'s own cap would otherwise leave
     * in {@code validateUpstreams}'s cycle check: a chain of more than {@code
     * MAX_RESOLUTION_REPOSITORIES} (25) repositories whose closing edge sits beyond that cap must
     * still be found reachable, since a domain can hold far more repositories (AWS's own documented
     * 1,000-per-domain quota) than any one resolution ever searches.
     */
    @Test
    void isReachableFindsATargetBeyondTheResolutionSearchCap() {
        Map<String, List<String>> upstreams = new HashMap<>();
        int chainLength = UpstreamRepositoryResolver.MAX_RESOLUTION_REPOSITORIES + 5;
        for (int i = 0; i < chainLength; i++) {
            String name = "R" + i;
            String next = i + 1 < chainLength ? "R" + (i + 1) : null;
            upstreams.put(name, next == null ? List.of() : List.of(next));
        }
        String target = "R" + (chainLength - 1);

        assertTrue(UpstreamRepositoryResolver.isReachable("R0", target, upstreams::get));
        // Confirms resolveSearchOrder genuinely would not have found it, so this test is actually
        // exercising the gap isReachable closes, not a scenario the capped walk already covered.
        assertFalse(UpstreamRepositoryResolver.resolveSearchOrder("R0", upstreams::get).contains(target));
    }
}
