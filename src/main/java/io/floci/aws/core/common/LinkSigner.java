package io.floci.aws.core.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Signs the links to pages Floci serves in place of a service's UI, such as an EMR Serverless run's dashboard or an
 * EMR cluster's application UI: an HMAC-SHA256 over what a link names, with a key the signer holds. A signer made
 * with {@link #withNewKey()} holds a key of its own process, so its links open nothing after a restart.
 */
public final class LinkSigner {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int KEY_BYTES = 32;

    private final byte[] key;

    public LinkSigner(byte[] key) {
        this.key = key.clone();
    }

    /** A signer with a new random key, which no other signer shares. */
    public static LinkSigner withNewKey() {
        byte[] key = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(key);
        return new LinkSigner(key);
    }

    /** The signature over {@code fields}, in order, as hexadecimal. */
    public String sign(String... fields) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(String.join("\n", fields).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not sign a link", e);
        }
    }

    /** Whether {@code signature} is this signer's over {@code fields}, compared in constant time. */
    public boolean verifies(String signature, String... fields) {
        return signature != null && MessageDigest.isEqual(sign(fields).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }
}
