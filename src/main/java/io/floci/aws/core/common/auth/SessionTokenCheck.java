package io.floci.aws.core.common.auth;

import io.floci.aws.services.iam.IamService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Whether the session token on a SigV4 request fits the access key that signed it. A temporary key
 * needs the token it was issued with, and a long-term key must come without one, as on AWS. The
 * token counts whether it is inside the signature or not, and an empty one counts too.
 */
public enum SessionTokenCheck {
    VALID,
    /** A temporary key with no token, or an empty token with any key. */
    MISSING,
    /** A token that was not issued with the key, or any token with a long-term key. */
    INVALID;

    /** {@code sessionToken} is null when the request carries none. */
    public static SessionTokenCheck of(IamService iamService, String accessKeyId, String sessionToken) {
        boolean temporary = IamService.isTemporaryAccessKey(accessKeyId);
        if (sessionToken == null) {
            return temporary ? MISSING : VALID;
        }
        if (sessionToken.isBlank()) {
            return MISSING;
        }
        if (!temporary) {
            return INVALID;
        }
        String issued = iamService.findSessionToken(accessKeyId).orElse(null);
        // A session stored before Floci recorded tokens has none to compare.
        if (issued == null || MessageDigest.isEqual(issued.getBytes(StandardCharsets.UTF_8),
                sessionToken.getBytes(StandardCharsets.UTF_8))) {
            return VALID;
        }
        return INVALID;
    }
}
