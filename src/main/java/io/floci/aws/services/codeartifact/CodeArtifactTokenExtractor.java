package io.floci.aws.services.codeartifact;

import jakarta.ws.rs.core.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Extracts a CodeArtifact authorization token from an incoming request's {@code Authorization}
 * header, accepting every scheme a real package-manager client uses: {@code Bearer <token>}
 * directly, HTTP Basic with the token as the password field and any username, or a bare token
 * with no scheme prefix at all. AWS documents Basic for Maven's {@code settings.xml} and
 * pip/twine's {@code username=aws} convention; Bearer for npm's {@code _authToken}; and a bare
 * token for Cargo (confirmed directly from the Cargo Book's own registry-authentication page:
 * "the header value is the API token", no prefix). Accepting all three costs nothing: a real
 * client of one format never happens to send another format's own scheme.
 *
 * <p>Pulled out of {@code CodeArtifactMavenController} once {@code CodeArtifactPypiController}
 * needed the identical extraction: pip and twine authenticate exactly the way Maven's HTTP wagon
 * does (verified against AWS's own pip and twine configuration docs, both showing
 * {@code username=aws, password=<token>}), not the Bearer-only convention npm's {@code _authToken}
 * uses.
 */
final class CodeArtifactTokenExtractor {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String BASIC_PREFIX = "Basic ";

    private CodeArtifactTokenExtractor() {
    }

    /**
     * {@code null} when the header is absent, or present but malformed (a declared Basic payload
     * that doesn't decode, or with no password field); {@link
     * CodeArtifactService#resolveAuthorizationToken} treats a null token the same as any other
     * invalid one. A header that starts with neither known scheme prefix is trusted as a bare
     * token verbatim (trimmed) rather than rejected outright: a malformed value reaching this
     * fallback is still just as safely rejected downstream, by {@code resolveAuthorizationToken}
     * failing to recognize it as a real, live-issued token.
     */
    static String extractToken(HttpHeaders headers) {
        String authorization = headers.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (authorization == null) {
            return null;
        }
        if (authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length());
        }
        if (authorization.startsWith(BASIC_PREFIX)) {
            return passwordFromBasicCredentials(authorization.substring(BASIC_PREFIX.length()));
        }
        return authorization.strip();
    }

    private static String passwordFromBasicCredentials(String base64Credentials) {
        try {
            String decoded = new String(Base64.getDecoder().decode(base64Credentials), StandardCharsets.UTF_8);
            int separator = decoded.indexOf(':');
            return separator >= 0 ? decoded.substring(separator + 1) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
