package io.floci.aws.core.common.auth;

import io.floci.aws.core.common.Digests;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.util.encoders.DecoderException;
import org.bouncycastle.util.encoders.Hex;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Locale;

/**
 * Verification of a SigV4A ({@code AWS4-ECDSA-P256-SHA256}) signature.
 *
 * <p>SigV4A signs with an ECDSA P-256 key pair derived from the caller's secret access key, so
 * a service that holds the secret derives the same pair and checks the signature against the
 * public half. The derivation is the NIST SP 800-108 counter-mode KDF over HMAC-SHA256 that the
 * IAM User Guide describes: the input key is {@code "AWS4A" + secretKey}, the fixed input is
 * {@code 0x00000001 || "AWS4-ECDSA-P256-SHA256" || 0x00 || accessKeyId || counter || 0x00000100},
 * and the first counter (from 1 to 254) whose output is at most {@code N - 2} yields the private
 * scalar {@code output + 1}. The signature is the DER-encoded ECDSA signature over the SHA-256 of
 * the string to sign, hex encoded.
 */
public final class SigV4aSignature {

    public static final String ALGORITHM = "AWS4-ECDSA-P256-SHA256";

    private static final String KDF_LABEL = "AWS4-ECDSA-P256-SHA256";
    private static final int MAX_COUNTER = 254;
    private static final X9ECParameters CURVE = CustomNamedCurves.getByName("secp256r1");
    private static final ECDomainParameters DOMAIN = new ECDomainParameters(
            CURVE.getCurve(), CURVE.getG(), CURVE.getN(), CURVE.getH());

    private SigV4aSignature() {
    }

    /**
     * Whether {@code signatureHex} is a valid SigV4A signature of {@code stringToSign} by the key
     * pair derived from {@code secretKey} and {@code accessKeyId}. Malformed hex or DER is simply
     * not a match.
     */
    public static boolean verify(String secretKey, String accessKeyId, String stringToSign,
                                 String signatureHex) {
        BigInteger[] rs = decodeSignature(signatureHex);
        if (rs == null) {
            return false;
        }
        BigInteger privateScalar = derivePrivateScalar(secretKey, accessKeyId);
        ECPoint publicPoint = DOMAIN.getG().multiply(privateScalar).normalize();
        ECDSASigner verifier = new ECDSASigner();
        verifier.init(false, new ECPublicKeyParameters(publicPoint, DOMAIN));
        byte[] digest = Digests.sha256(stringToSign.getBytes(StandardCharsets.UTF_8));
        return verifier.verifySignature(digest, rs[0], rs[1]);
    }

    /** The P-256 private scalar the KDF derives for a secret and access key id. */
    public static BigInteger derivePrivateScalar(String secretKey, String accessKeyId) {
        byte[] inputKey = ("AWS4A" + secretKey).getBytes(StandardCharsets.UTF_8);
        byte[] label = KDF_LABEL.getBytes(StandardCharsets.UTF_8);
        byte[] accessKey = accessKeyId.getBytes(StandardCharsets.UTF_8);
        BigInteger limit = CURVE.getN().subtract(BigInteger.TWO);
        for (int counter = 1; counter <= MAX_COUNTER; counter++) {
            ByteBuffer fixedInput = ByteBuffer.allocate(4 + label.length + 1 + accessKey.length + 1 + 4);
            fixedInput.putInt(1).put(label).put((byte) 0).put(accessKey).put((byte) counter).putInt(256);
            BigInteger candidate = new BigInteger(1, hmacSha256(inputKey, fixedInput.array()));
            if (candidate.compareTo(limit) <= 0) {
                return candidate.add(BigInteger.ONE);
            }
        }
        throw new IllegalStateException("SigV4A key derivation found no candidate in " + MAX_COUNTER + " rounds");
    }

    private static BigInteger[] decodeSignature(String signatureHex) {
        if (signatureHex == null || signatureHex.isEmpty()) {
            return null;
        }
        try {
            ASN1Sequence sequence = ASN1Sequence.getInstance(Hex.decode(signatureHex.toLowerCase(Locale.ROOT)));
            if (sequence.size() != 2) {
                return null;
            }
            return new BigInteger[] {
                    ASN1Integer.getInstance(sequence.getObjectAt(0)).getPositiveValue(),
                    ASN1Integer.getInstance(sequence.getObjectAt(1)).getPositiveValue()
            };
        } catch (DecoderException | IllegalArgumentException expected) {
            // Non-hex or non-DER input is a bad signature from the caller, not a server fault.
            return null;
        }
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
