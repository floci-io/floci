package io.github.hectorvent.floci.services.codeartifact;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Each case is one of the three transformations AWS's own NuGet normalization doc documents. */
class NuGetVersionsTest {

    /**
     * AWS's own worked example text is "1.00 is treated as 1.0", a 2-segment result; this
     * intentionally returns {@code 1.0.0} instead, matching what the real {@code
     * NuGetVersion.ToNormalizedString()} BaGetter itself calls actually produces (always at least
     * Major.Minor.Patch) rather than AWS's own apparently-simplified doc example. This class
     * exists only to match what BaGetter's own version list already contains well enough to find
     * a caller's version in it, so matching the real sidecar's output is what matters here, not
     * reproducing AWS's doc text byte-for-byte.
     */
    @Test
    void stripsLeadingZeroesFromEachSegment() {
        assertEquals("1.0.0", NuGetVersions.normalize("1.00"));
        assertEquals("1.1.1", NuGetVersions.normalize("1.01.1"));
        assertEquals("1.0.0.1", NuGetVersions.normalize("1.00.0.1"));
    }

    @Test
    void omitsAZeroFourthSegment() {
        assertEquals("1.0.0", NuGetVersions.normalize("1.0.0.0"));
        assertEquals("1.0.1", NuGetVersions.normalize("1.0.01.0"));
    }

    @Test
    void removesSemVerBuildMetadata() {
        assertEquals("1.0.7", NuGetVersions.normalize("1.0.7+r3456"));
    }

    @Test
    void padsAShortReleaseUpToThreeSegments() {
        assertEquals("1.0.0", NuGetVersions.normalize("1.0"));
        assertEquals("1.0.0", NuGetVersions.normalize("1"));
    }

    @Test
    void keepsAPrereleaseLabelAndLowercasesIt() {
        assertEquals("1.0.0-beta.1", NuGetVersions.normalize("1.0.0-Beta.1"));
    }

    @Test
    void leavesAnythingItDoesNotConfidentlyUnderstandUnchanged() {
        assertEquals("not-a-version", NuGetVersions.normalize("not-a-version"));
    }

    /**
     * Found by Qodo review: a plain {@code split} silently drops a trailing empty string, so
     * {@code "1.0.0."} would otherwise normalize identically to the real version {@code "1.0.0"}
     * and let a malformed caller-supplied version resolve a published one.
     */
    @Test
    void leavesATrailingDotUnchangedRatherThanNormalizingItAwayAsAnEmptySegment() {
        assertEquals("1.0.0.", NuGetVersions.normalize("1.0.0."));
    }

    /**
     * Found by Qodo review: an oversized numeric segment used to throw {@code
     * NumberFormatException} out of {@code normalize} instead of falling back like any other
     * segment it isn't confident it understood.
     */
    @Test
    void leavesASegmentTooLargeForALongUnchangedRatherThanThrowing() {
        String tooLarge = "99999999999999999999.0.0";
        assertEquals(tooLarge, NuGetVersions.normalize(tooLarge));
    }
}
