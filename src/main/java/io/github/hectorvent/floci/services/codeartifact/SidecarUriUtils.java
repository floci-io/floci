package io.github.hectorvent.floci.services.codeartifact;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Shared URI-construction helpers for the Maven, npm, and pypi sidecar clients, each of which
 * builds a request URI out of its own {@code baseUrl} plus one or more caller-supplied path
 * segments (a repository id, package coordinates, an asset filename). Package-private: every
 * caller lives in this package, and {@link #combine} in particular requires its {@code
 * encodedPath} argument to already be fully percent-encoded, a precondition not worth exposing
 * to unrelated callers outside the three clients that know to uphold it.
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
        StringBuilder encoded = new StringBuilder();
        for (byte b : segment.getBytes(StandardCharsets.UTF_8)) {
            int v = b & 0xFF;
            char c = (char) v;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '~' || c == '.') {
                encoded.append(c);
            } else {
                encoded.append('%').append(String.format("%02X", v));
            }
        }
        return encoded.toString();
    }
}
