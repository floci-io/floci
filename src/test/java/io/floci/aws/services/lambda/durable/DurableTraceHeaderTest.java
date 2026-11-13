package io.floci.aws.services.lambda.durable;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurableTraceHeaderTest {

    private static final String ROOT = "1-6af70000-0000000000000000000000aa";
    private static final String PARENT = "00000000000000a1";
    private static final long NOW = 0x6af80000L;
    private static final String NEW_ROOT = "1-6af80000-[0-9a-f]{24}";
    private static final String NEW_ID = "[0-9a-f]{16}";
    private static final String CALLER = "Root=1-6af6281a-10d34ccb165fe4201aea66dd;Parent=4800428e122b3d8d;Sampled=0";

    private static String execution(String received, boolean active) {
        return DurableTraceHeader.forExecution(received, active, NOW);
    }

    @Test
    void noHeaderOnPassThroughGetsNewRootAndParentUnsampled() {
        assertTrue(execution(null, false).matches("Root=" + NEW_ROOT + ";Parent=" + NEW_ID + ";Sampled=0"));
    }

    @Test
    void noHeaderOnActiveGetsNewRootAndParentSampled() {
        assertTrue(execution(null, true).matches("Root=" + NEW_ROOT + ";Parent=" + NEW_ID + ";Sampled=1"));
    }

    @Test
    void fullSampledHeaderOnPassThroughIsKept() {
        String header = "Root=" + ROOT + ";Parent=" + PARENT + ";Sampled=1";
        assertEquals(header, execution(header, false));
    }

    @Test
    void fullUnsampledHeaderOnActiveIsKept() {
        String header = "Root=" + ROOT + ";Parent=" + PARENT + ";Sampled=0";
        assertEquals(header, execution(header, true));
    }

    @Test
    void fullSampledHeaderOnActiveGetsANewParent() {
        String stored = execution("Root=" + ROOT + ";Parent=" + PARENT + ";Sampled=1", true);

        assertTrue(stored.matches("Root=" + ROOT + ";Parent=" + NEW_ID + ";Sampled=1"));
        assertNotEquals("Root=" + ROOT + ";Parent=" + PARENT + ";Sampled=1", stored);
    }

    @Test
    void headerWithoutSampledOnActiveGetsANewParentAndSampled() {
        String stored = execution("Root=" + ROOT + ";Parent=" + PARENT, true);

        assertTrue(stored.matches("Root=" + ROOT + ";Parent=" + NEW_ID + ";Sampled=1"));
        assertNotEquals("Root=" + ROOT + ";Parent=" + PARENT + ";Sampled=1", stored);
    }

    @Test
    void rootOnlyOnActiveGetsANewParentAndSampled() {
        assertTrue(execution("Root=" + ROOT, true).matches("Root=" + ROOT + ";Parent=" + NEW_ID + ";Sampled=1"));
    }

    @Test
    void rootOnlyOnPassThroughGetsANewParentAndSampledZero() {
        assertTrue(execution("Root=" + ROOT, false).matches("Root=" + ROOT + ";Parent=" + NEW_ID + ";Sampled=0"));
    }

    @Test
    void parentWithoutSampledOnPassThroughKeepsTheParentAndIsUnsampled() {
        assertEquals("Root=" + ROOT + ";Parent=" + PARENT + ";Sampled=0",
                execution("Root=" + ROOT + ";Parent=" + PARENT, false));
    }

    @Test
    void unsampledHeaderWithoutParentOnActiveStaysWithoutParent() {
        assertEquals("Root=" + ROOT + ";Sampled=0", execution("Root=" + ROOT + ";Sampled=0", true));
    }

    @Test
    void sampledHeaderWithoutParentOnPassThroughStaysWithoutParent() {
        assertEquals("Root=" + ROOT + ";Sampled=1", execution("Root=" + ROOT + ";Sampled=1", false));
    }

    @Test
    void invalidSampledCountsAsAbsent() {
        assertEquals("Root=" + ROOT + ";Parent=" + PARENT + ";Sampled=0",
                execution("Root=" + ROOT + ";Parent=" + PARENT + ";Sampled=?", false));
    }

    @Test
    void parentAndSampledWithoutRootGetANewRoot() {
        assertTrue(execution("Parent=" + PARENT + ";Sampled=1", false)
                .matches("Root=" + NEW_ROOT + ";Parent=" + PARENT + ";Sampled=1"));
    }

    @Test
    void garbageOnPassThroughIsReplacedByANewHeader() {
        assertTrue(execution("garbage", false).matches("Root=" + NEW_ROOT + ";Parent=" + NEW_ID + ";Sampled=0"));
    }

    @Test
    void garbageOnActiveIsReplacedByANewHeader() {
        assertTrue(execution("garbage", true).matches("Root=" + NEW_ROOT + ";Parent=" + NEW_ID + ";Sampled=1"));
    }

    @Test
    void anInvalidRootIsReplacedAndOnlyTheFirstOfAKeyCounts() {
        String stored = execution("Root=bad;Parent=" + PARENT + ";Parent=" + "1".repeat(16) + ";Sampled=1", false);

        assertTrue(stored.matches("Root=" + NEW_ROOT + ";Parent=" + PARENT + ";Sampled=1"));
    }

    @Test
    void fieldsAreTrimmedAndOtherKeysAreIgnored() {
        assertEquals("Root=" + ROOT + ";Parent=" + PARENT + ";Sampled=1",
                execution(" Self=1-x ; Root=" + ROOT + " ; Parent=" + PARENT + " ;Sampled=1", false));
    }

    @Test
    void activeInvocationsShareRootAndSampledAndDifferInParent() {
        String stored = "Root=" + ROOT + ";Parent=" + PARENT + ";Sampled=1";

        String first = DurableTraceHeader.forInvocation(stored, true);
        String second = DurableTraceHeader.forInvocation(stored, true);

        assertTrue(first.matches("Root=" + ROOT + ";Parent=" + NEW_ID + ";Sampled=1"));
        assertTrue(second.matches("Root=" + ROOT + ";Parent=" + NEW_ID + ";Sampled=1"));
        assertNotEquals(first, second);
    }

    @Test
    void activeInvocationKeepsAnUnsampledHeaderWithAParent() {
        String header = DurableTraceHeader.forInvocation("Root=" + ROOT + ";Sampled=0", true);

        assertTrue(header.matches("Root=" + ROOT + ";Parent=" + NEW_ID + ";Sampled=0"));
    }

    @Test
    void passThroughInvocationReturnsTheStoredHeader() {
        String stored = "Root=" + ROOT + ";Parent=" + PARENT + ";Sampled=1";
        assertEquals(stored, DurableTraceHeader.forInvocation(stored, false));
    }

    @Test
    void passThroughInvocationOfASampledHeaderWithoutAParentGetsTheRootOnly() {
        assertEquals("Root=" + ROOT, DurableTraceHeader.forInvocation("Root=" + ROOT + ";Sampled=1", false));
    }

    @Test
    void passThroughInvocationsOfAnUnsampledHeaderWithoutAParentGetANewParentEach() {
        String stored = "Root=" + ROOT + ";Sampled=0";

        String first = DurableTraceHeader.forInvocation(stored, false);
        String second = DurableTraceHeader.forInvocation(stored, false);

        assertTrue(first.matches("Root=" + ROOT + ";Parent=" + NEW_ID + ";Sampled=0"));
        assertTrue(second.matches("Root=" + ROOT + ";Parent=" + NEW_ID + ";Sampled=0"));
        assertNotEquals(first, second);
    }

    @Test
    void invocationWithoutAStoredHeaderGetsNone() {
        assertNull(DurableTraceHeader.forInvocation(null, true));
        assertNull(DurableTraceHeader.forInvocation(null, false));
    }

    private static String chained(String requested, boolean active) {
        return DurableTraceHeader.forChainedChild(requested, CALLER, active);
    }

    @Test
    void chainedFullHeaderIsKeptOnPassThrough() {
        String header = "Root=" + ROOT + ";Parent=00000000000000aa;Sampled=1";
        assertEquals(header, chained(header, false));
    }

    @Test
    void chainedFullUnsampledHeaderIsKeptOnActive() {
        String header = "Root=" + ROOT + ";Parent=00000000000000cc;Sampled=0";
        assertEquals(header, chained(header, true));
    }

    @Test
    void chainedFullSampledHeaderKeepsItsParentOnActive() {
        String header = "Root=" + ROOT + ";Parent=00000000000000e1;Sampled=1";
        assertEquals(header, chained(header, true));
    }

    @Test
    void chainedRootOnlyOnPassThroughGetsANewParentAndUnsampled() {
        assertTrue(chained("Root=" + ROOT, false).matches("Root=" + ROOT + ";Parent=" + NEW_ID + ";Sampled=0"));
    }

    @Test
    void chainedRootOnlyOnActiveGetsSampledWithoutAParent() {
        assertEquals("Root=" + ROOT + ";Sampled=1", chained("Root=" + ROOT, true));
    }

    @Test
    void chainedRootAndSampledOnPassThroughGetsNoParent() {
        assertEquals("Root=" + ROOT + ";Sampled=1", chained("Root=" + ROOT + ";Sampled=1", false));
    }

    @Test
    void chainedRootAndParentOnPassThroughGetsUnsampled() {
        assertEquals("Root=" + ROOT + ";Parent=00000000000000e4;Sampled=0",
                chained("Root=" + ROOT + ";Parent=00000000000000e4", false));
    }

    @Test
    void chainedRootAndUnsampledOnActiveGetsNoParent() {
        assertEquals("Root=" + ROOT + ";Sampled=0", chained("Root=" + ROOT + ";Sampled=0", true));
    }

    @Test
    void chainedRootAndParentOnActiveGetsSampled() {
        assertEquals("Root=" + ROOT + ";Parent=00000000000000e6;Sampled=1",
                chained("Root=" + ROOT + ";Parent=00000000000000e6", true));
    }

    @Test
    void chainedUnknownSampledOnActiveBecomesSampled() {
        assertEquals("Root=" + ROOT + ";Parent=00000000000000e7;Sampled=1",
                chained("Root=" + ROOT + ";Parent=00000000000000e7;Sampled=?", true));
    }

    @Test
    void chainedWithoutAUsableRequestTakesTheCallerHeaderAsIs() {
        for (boolean active : new boolean[] {false, true}) {
            assertEquals(CALLER, chained(null, active));
            assertEquals(CALLER, chained("", active));
            assertEquals(CALLER, chained("garbage", active));
            assertEquals(CALLER, chained("Parent=00000000000000e7;Sampled=1", active));
        }
    }

    @Test
    void chainedCallerHeaderWithoutAParentPassesVerbatim() {
        String caller = "Root=" + ROOT + ";Sampled=1";
        assertEquals(caller, DurableTraceHeader.forChainedChild(null, caller, false));
    }
}
