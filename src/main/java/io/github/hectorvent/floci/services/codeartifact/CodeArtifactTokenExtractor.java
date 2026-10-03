package io.github.hectorvent.floci.services.codeartifact;

import jakarta.ws.rs.core.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Extracts a CodeArtifact authorization token from an incoming request's {@code Authorization}
 * header, accepting either scheme real package-manager clients use: {@code Bearer <token>}
 * directly, or HTTP Basic with the token as the password field and any username. AWS documents
 * only Basic for Maven's {@code settings.xml} and pip/twine's {@code username=aws} convention;
 * accepting Bearer too costs nothing and matches what a Bearer-only format like npm sends.
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
     * {@code null} when neither scheme is present, malformed, or the Basic credentials have no
     * password field; {@link CodeArtifactService#resolveAuthorizationToken} treats a null token
     * the same as any other invalid one.
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
        return null;
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
