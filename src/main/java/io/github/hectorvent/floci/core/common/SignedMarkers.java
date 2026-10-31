package io.github.hectorvent.floci.core.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pagination markers that name a cursor, as AWS's opaque tokens do. Each marker is signed with a
 * key generated for this instance, so a fabricated or edited marker is not recognised, and one
 * from an earlier run is stale, as an expired AWS token would be.
 */
public final class SignedMarkers {

    private static final char SEPARATOR = '.';
    /** Opens a cursor that stands for a longer one by its digest. No key a list pages by holds a NUL. */
    private static final String DIGEST_PREFIX = "\u0000#";
    /** Long cursors held per instance; a marker whose digest was evicted is refused as unknown. */
    static final int MAX_LONG_CURSORS = 10_000;

    private final byte[] key = newKey();
    private final int maxLength;
    /**
     * The cursors too long to carry, by the digest a marker carries instead. An entry is the key of
     * an item a page ended on, so a marker resumes after that item even once it is deleted.
     * Bounded with LRU-style eviction, as an expired AWS token would be refused.
     */
    private final Map<String, String> longCursors = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_LONG_CURSORS;
                }
            });

    public SignedMarkers() {
        this(Integer.MAX_VALUE);
    }

    /** Markers no longer than {@code maxLength}, however long the cursor they name. */
    public SignedMarkers(int maxLength) {
        this.maxLength = maxLength;
    }

    public String issue(String cursor) {
        return Base64Url.encode(cursor + SEPARATOR + sign(cursor));
    }

    /**
     * A marker for {@code cursor} that only {@link #cursorOf(String, String)} with the same
     * {@code scope} recognises. The scope is signed rather than carried, so it costs the marker no
     * length. A cursor that would make the marker longer than this instance's limit is carried as
     * its digest instead and read back from the cursors this instance has issued.
     */
    public String issue(String scope, String cursor) {
        if (!cursor.startsWith(DIGEST_PREFIX)) {
            String marker = scopedMarker(scope, cursor);
            if (marker.length() <= maxLength) {
                return marker;
            }
        }
        String digest = DIGEST_PREFIX + digest(cursor);
        longCursors.put(digest, cursor);
        return scopedMarker(scope, digest);
    }

    private String scopedMarker(String scope, String cursor) {
        return Base64Url.encode(cursor + SEPARATOR + sign(scoped(scope, cursor)));
    }

    /** The cursor a marker names, or null when this instance did not issue it. */
    public String cursorOf(String marker) {
        return cursorOf(null, marker);
    }

    /** The cursor a marker names, or null when this instance did not issue it for {@code scope}. */
    public String cursorOf(String scope, String marker) {
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
        String signed = scope == null ? cursor : scoped(scope, cursor);
        if (!MessageDigest.isEqual(presented, sign(signed).getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        return cursor.startsWith(DIGEST_PREFIX) ? longCursors.get(cursor) : cursor;
    }

    private static String digest(String cursor) {
        try {
            return Base64Url.encode(MessageDigest.getInstance("SHA-256")
                    .digest(cursor.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to shorten pagination markers", e);
        }
    }

    /** Length-prefixed, so no scope and cursor pair signs the same text as another. */
    private static String scoped(String scope, String cursor) {
        return scope.length() + ":" + scope + cursor;
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
