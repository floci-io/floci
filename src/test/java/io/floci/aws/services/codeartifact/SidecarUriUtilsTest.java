package io.floci.aws.services.codeartifact;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Focused unit coverage for the helper all three sidecar clients (Maven, npm, pypi) depend on to
 * build their request URIs. The behavior-level regression tests for the real finding that
 * motivated this encoding (path traversal and URI-fragment/query injection via a caller-supplied
 * asset name) live in each client's own test, exercised through a live request path; this class
 * covers the shared helper in isolation.
 */
class SidecarUriUtilsTest {

    @Test
    void encodeSegmentLeavesUnreservedCharactersUntouched() {
        assertThat(SidecarUriUtils.encodeSegment("abcXYZ019-_~.jar"), equalTo("abcXYZ019-_~.jar"));
    }

    @Test
    void encodeSegmentEscapesASlashSoItCanNeverActAsAStructuralSeparator() {
        assertThat(SidecarUriUtils.encodeSegment("a/b"), equalTo("a%2Fb"));
    }

    @Test
    void encodeSegmentEscapesAPercentSoAnAlreadyEncodedLookingInputIsNotMistakenForOne() {
        assertThat(SidecarUriUtils.encodeSegment("100%done"), equalTo("100%25done"));
    }

    @Test
    void encodeSegmentEscapesASpaceAndOtherReservedCharacters() {
        assertThat(SidecarUriUtils.encodeSegment("a b#c?d"), equalTo("a%20b%23c%3Fd"));
    }

    @Test
    void encodeSegmentEscapesTheDotsOfABareSingleDotSegment() {
        assertThat(SidecarUriUtils.encodeSegment("."), equalTo("%2E"));
    }

    @Test
    void encodeSegmentEscapesTheDotsOfABareDoubleDotSegment() {
        assertThat(SidecarUriUtils.encodeSegment(".."), equalTo("%2E%2E"));
    }

    @Test
    void encodeSegmentLeavesDotsAloneWhenTheyAreNotTheWholeSegment() {
        assertThat(SidecarUriUtils.encodeSegment("1.0.1"), equalTo("1.0.1"));
    }

    @Test
    void combineAppendsTheEncodedPathToABareBaseUrl() {
        URI result = SidecarUriUtils.combine(URI.create("http://127.0.0.1:8080"), "/dom--repo/a-1.0.jar");

        assertThat(result, equalTo(URI.create("http://127.0.0.1:8080/dom--repo/a-1.0.jar")));
    }

    @Test
    void combinePreservesABaseUrlPathPrefix() {
        URI result = SidecarUriUtils.combine(URI.create("http://127.0.0.1:8080/reposilite"), "/dom--repo/a-1.0.jar");

        assertThat(result, equalTo(URI.create("http://127.0.0.1:8080/reposilite/dom--repo/a-1.0.jar")));
    }

    @Test
    void combineTrimsATrailingSlashFromTheBaseUrlPathPrefix() {
        URI result = SidecarUriUtils.combine(URI.create("http://127.0.0.1:8080/reposilite/"), "/dom--repo/a-1.0.jar");

        assertThat(result, equalTo(URI.create("http://127.0.0.1:8080/reposilite/dom--repo/a-1.0.jar")));
    }

    @Test
    void combinePreservesAPercentEncodedCharacterInTheBaseUrlPathPrefix() {
        URI result = SidecarUriUtils.combine(URI.create("http://127.0.0.1:8080/reposilite%2Fv1"),
                "/dom--repo/a-1.0.jar");

        assertThat(result.getRawPath(), equalTo("/reposilite%2Fv1/dom--repo/a-1.0.jar"));
    }

    @Test
    void combinePreservesAPercentEncodedCharacterInTheBaseUrlAuthority() {
        URI result = SidecarUriUtils.combine(URI.create("http://user%2Ftenant@example.com"), "/dom--repo/a-1.0.jar");

        assertThat(result.getRawAuthority(), equalTo("user%2Ftenant@example.com"));
    }

    @Test
    void transferTimeoutIsJustTheFixedFloorForATinyUpload() {
        assertThat(SidecarUriUtils.transferTimeout(0), equalTo(Duration.ofSeconds(30)));
    }

    @Test
    void transferTimeoutScalesWithContentLengthPastTheFixedFloor() {
        long twentyMebibytes = 20L * 1024 * 1024;

        assertThat(SidecarUriUtils.transferTimeout(twentyMebibytes), equalTo(Duration.ofSeconds(32)));
    }

    /**
     * {@link SidecarUriUtils#maxTransferTimeout()} is the worst-case sibling every real asset
     * download (every sidecar client's own streamed read, every external-connection client's real
     * registry fetch) uses instead of a flat, small timeout: confirms it is exactly {@link
     * SidecarUriUtils#transferTimeout} evaluated at the real 5 GB asset quota, not some other,
     * drifted value.
     */
    @Test
    void maxTransferTimeoutMatchesTransferTimeoutAtTheRealAssetQuota() {
        assertThat(SidecarUriUtils.maxTransferTimeout(),
                equalTo(SidecarUriUtils.transferTimeout(CodeArtifactService.MAX_ASSET_FILE_SIZE_BYTES)));
    }

    @Test
    void maxTransferTimeoutIsGenerousEnoughForARealMultiGigabyteDownload() {
        assertThat(SidecarUriUtils.maxTransferTimeout().compareTo(Duration.ofMinutes(5)) > 0, equalTo(true));
    }
}
