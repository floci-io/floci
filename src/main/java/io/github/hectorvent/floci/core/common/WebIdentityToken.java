package io.github.hectorvent.floci.core.common;

import java.util.List;

/**
 * The verified claims of a web-identity JWT.
 *
 * @param issuer   the {@code iss} claim
 * @param subject  the {@code sub} claim. For IRSA, {@code system:serviceaccount:<ns>:<sa>}
 * @param audiences the {@code aud} claim, normalized to a list (JWT allows string or array)
 */
public record WebIdentityToken(String issuer, String subject, List<String> audiences) {
}
