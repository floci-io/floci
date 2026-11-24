package io.floci.aws.core.common;

import java.util.List;
import java.util.Map;

/**
 * The verified claims of a web-identity JWT.
 *
 * @param issuer   the {@code iss} claim
 * @param subject  the {@code sub} claim. For IRSA, {@code system:serviceaccount:<ns>:<sa>}
 * @param audiences the {@code aud} claim, normalized to a list (JWT allows string or array)
 * @param principalTags the session tags the token passes, from either of the two claim formats
 *                      AWS publishes for them, and empty when it passes none
 * @param transitiveTagKeys the keys among those tags the token asks to set as transitive, from
 *                          either format, and empty when it asks for none
 */
public record WebIdentityToken(String issuer, String subject, List<String> audiences,
                               Map<String, String> principalTags,
                               List<String> transitiveTagKeys) {

    public WebIdentityToken(String issuer, String subject, List<String> audiences) {
        this(issuer, subject, audiences, Map.of(), List.of());
    }
}
