package io.github.hectorvent.floci.core.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Pagination markers that name a cursor, as AWS's opaque tokens do. Each marker is signed with a
 * key generated for this instance, so a fabricated or edited marker is not recognised, and one
 * from an earlier run is stale, as an expired AWS token would be.
 */
public final class SignedMarkers {

    private static final char SEPARATOR = '.';

    private final byte[] key = newKey();

    public String issue(String cursor) {
        return Base64Url.encode(cursor + SEPARATOR + sign(cursor));
    }

    /** The cursor a marker names, or null when this instance did not issue it. */
    public String cursorOf(String marker) {
        String decoded;
        try {
            decoded = Base64Url.decode(marker);
        } catch (IllegalArgumentException notIssued) {
            // Not base64url, so not a marker this instance issued.
            return null;
        }
        int separator = decoded.lastIndexOf(SEPARATOR);
        if (separator < 0) {
            return null;
        }
        String cursor = decoded.substring(0, separator);
        byte[] presented = decoded.substring(separator + 1).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(presented, sign(cursor).getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        return cursor;
    }

    private String sign(String cursor) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64Url.encode(mac.doFinal(cursor.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required to sign pagination markers", e);
        }
    }

    private static byte[] newKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
