package io.floci.aws.services.codeartifact;

import io.floci.aws.core.common.auth.SigV4Canonicalization;

import java.net.URI;
import java.time.Duration;

/**
 * Shared helpers for the Maven, npm, pypi, and NuGet sidecar clients: URI construction out of
 * each one's own {@code baseUrl} plus one or more caller-supplied path segments (a repository
 * id, package coordinates, an asset filename), and a request timeout sized for forwarding a
 * large asset's real bytes rather than a small metadata-only request. Package-private: every
 * caller lives in this package, and {@link #combine} in particular requires its {@code
 * encodedPath} argument to already be fully percent-encoded, a precondition not worth exposing
 * to unrelated callers outside the four clients that know to uphold it.
 */
final class SidecarUriUtils {

    private SidecarUriUtils() {}

    /**
     * Combines {@code base}'s scheme, authority, and own path prefix with an already
     * percent-encoded {@code encodedPath}, built from {@link #encodeSegment}-encoded,
     * caller-supplied segments. {@code encodedPath} must be fully encoded going in: this hands
     * the combined string straight to {@link URI#create}, which only parses, rather than running
     * it through a constructor that would try to quote it a second time and corrupt any
     * {@code %} it finds (double-encoding, e.g. turning a deliberate {@code %2F} into
     * {@code %252F}).
     */
    static URI combine(URI base, String encodedPath) {
        // getRawPath(), not getPath(): the configured prefix's own encoding (e.g. a literal %2F
        // standing for an escaped slash within one opaque segment) must survive untouched. Decoding
        // it and handing it to the URI constructor alongside the new path would re-encode the whole
        // thing together and could turn that %2F into a literal /, changing what the prefix means to
        // whatever sits in front of the sidecar. getRawAuthority() below is the same reasoning
        // applied to userinfo/host, e.g. a percent-encoded character inside HTTP Basic credentials.
        String prefix = base.getRawPath();
        if (prefix == null || prefix.equals("/")) {
            prefix = "";
        } else if (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return URI.create(base.getScheme() + "://" + base.getRawAuthority() + prefix + encodedPath);
    }

    /**
     * Percent-encodes a single untrusted path segment so it can never be mistaken for a
     * structural separator or a dot-segment. Every byte outside the unreserved set is escaped,
     * including {@code /} (which the generic path-quoting {@link URI} constructor otherwise
     * leaves alone, since a real {@code /} is legal inside a path). A segment that is exactly
     * {@code .} or {@code ..} gets its dots escaped too: those are themselves otherwise-legal
     * unreserved characters, but a bare {@code ..} between two real {@code /} separators is a
     * dot-segment that something in front of the sidecar could resolve by walking back out of
     * the directory the caller intends to confine the request to.
     */
    static String encodeSegment(String segment) {
        if (segment.equals(".") || segment.equals("..")) {
            return segment.replace(".", "%2E");
        }
        return SigV4Canonicalization.uriEncode(segment);
    }

    /** A deliberately pessimistic floor: real transfers to a local sidecar container are far
     * faster than this in practice, but the point of a floor is to be safe against a slow one,
     * not to reflect a realistic average. */
    private static final long MIN_TRANSFER_BYTES_PER_SECOND = 10L * 1024 * 1024;

    /**
     * A request timeout long enough to cover forwarding {@code contentLength} bytes to a sidecar
     * container even at {@link #MIN_TRANSFER_BYTES_PER_SECOND}, plus a fixed 30-second grace
     * period for the sidecar's own processing time, not just the wire transfer. Every sidecar
     * client's own small, metadata-only request keeps its existing flat 30-second timeout
     * unchanged; this is only for the one call per format that actually forwards an asset's real
     * bytes, discovered by hand-testing a multi-GB upload end to end: a flat 30 seconds was never
     * enough once an asset is sized in gigabytes rather than the kilobytes every existing test
     * fixture uses, and every one of the four sidecar clients shared the exact same gap.
     */
    static Duration transferTimeout(long contentLength) {
        return Duration.ofSeconds(30 + contentLength / MIN_TRANSFER_BYTES_PER_SECOND);
    }

    /**
     * {@link #transferTimeout}'s own worst-case sibling, for a download whose real size isn't
     * known until the response itself arrives: a GET can't size its own request timeout off a
     * {@code contentLength} the way a PUT/POST already knows its own body's size upfront, so this
     * uses {@code CodeArtifactService.MAX_ASSET_FILE_SIZE_BYTES} itself as the worst case instead.
     * Every real asset-fetching GET, both the four sidecar clients' own streamed reads and the
     * four external-connection clients' real public-registry fetches, shares this same gap for the
     * identical reason the write side once did: a flat, small timeout sized for a metadata-only
     * request silently cancels a transfer still legitimately in progress once it is no longer a
     * few kilobytes but up to 5 GB.
     */
    static Duration maxTransferTimeout() {
        return transferTimeout(CodeArtifactService.MAX_ASSET_FILE_SIZE_BYTES);
    }
}
