package io.github.hectorvent.floci.services.kms.keytype;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.kms.model.KmsKeySpec;
import io.github.hectorvent.floci.services.kms.model.KmsMessageType;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.DigestInfo;
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.digests.SHA384Digest;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.engines.RSABlindedEngine;
import org.bouncycastle.crypto.params.ParametersWithRandom;
import org.bouncycastle.crypto.signers.PSSSigner;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.crypto.util.PublicKeyFactory;
import org.jboss.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;

import static io.github.hectorvent.floci.services.kms.model.KmsMessageType.DIGEST;

final class RsaKeyType implements KmsKeyType {

    private static final Logger LOG = Logger.getLogger(RsaKeyType.class);

    private final SecureRandom random;

    RsaKeyType(SecureRandom random) {
        this.random = random;
    }

    @Override
    public void generateKeyMaterial(KmsKey key, String region) throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(keySize(key.getKeySpec()));
        AsymmetricKeys.store(key, generator.generateKeyPair());
    }

    @Override
    public byte[] sign(KmsKey key, byte[] message, String algorithm, KmsMessageType messageType) throws Exception {
        PrivateKey privateKey = AsymmetricKeys.privateKey(key, "RSA");
        if (messageType == DIGEST) {
            if (isPss(algorithm)) {
                return signPssDigest(privateKey, message, algorithm);
            }
            // RFC 8017 9.2: PKCS#1 v1.5 signs DigestInfo{hashOID, digest}, not the
            // bare digest, so the signature validates with external verifiers and
            // real KMS (NONEwithRSA only pads the bytes it is given).
            return AsymmetricKeys.sign(privateKey, "NONEwithRSA", wrapInDigestInfo(message, algorithm));
        }
        return AsymmetricKeys.sign(privateKey, KmsKeySpec.getSignVerifyAlgorithm(algorithm).getJavaName(), message);
    }

    @Override
    public boolean verify(KmsKey key, byte[] message, byte[] signature, String algorithm,
                          KmsMessageType messageType) throws Exception {
        PublicKey publicKey = AsymmetricKeys.publicKey(key, "RSA");
        // An unknown algorithm name fails even for DIGEST.
        KmsKeySpec.Algorithm signingAlgorithm = KmsKeySpec.getSignVerifyAlgorithm(algorithm);
        if (messageType == DIGEST) {
            if (isPss(algorithm)) {
                return verifyPssDigest(publicKey, message, signature, algorithm);
            }
            // Mirror sign(): verify against DigestInfo{hashOID, digest} (RFC 8017 9.2).
            return AsymmetricKeys.verify(publicKey, "NONEwithRSA", wrapInDigestInfo(message, algorithm), signature);
        }
        return AsymmetricKeys.verify(publicKey, signingAlgorithm.getJavaName(), message, signature);
    }

    @Override
    public byte[] encrypt(KmsKey key, KmsKeySpec.Algorithm algorithm, byte[] plaintext) {
        validatePlaintextLength(plaintext, algorithm, key.getKeySpec());
        return oaep(Cipher.ENCRYPT_MODE, key, algorithm, plaintext);
    }

    @Override
    public byte[] decrypt(KmsKey key, KmsKeySpec.Algorithm algorithm, byte[] ciphertext) {
        return oaep(Cipher.DECRYPT_MODE, key, algorithm, ciphertext);
    }

    private static int keySize(KmsKeySpec spec) {
        return Integer.parseInt(spec.name().substring("RSA_".length()));
    }

    /** RFC 8017 7.1.1: OAEP holds at most k - 2*hLen - 2 bytes, k being the modulus length. */
    private static void validatePlaintextLength(byte[] plaintext, KmsKeySpec.Algorithm algorithm, KmsKeySpec spec) {
        int modulusBytes = keySize(spec) / 8;
        int digestBytes = algorithm == KmsKeySpec.Algorithm.RSAES_OAEP_SHA_1 ? 20 : 32;
        int maxBytes = modulusBytes - 2 * digestBytes - 2;
        if (plaintext.length > maxBytes) {
            throw new AwsException("ValidationException",
                    "Algorithm " + algorithm.getAlgName() + " and key spec " + spec.name()
                            + " cannot encrypt data larger than " + maxBytes + " bytes.", 400);
        }
    }

    /**
     * RSAES-OAEP with an explicit OAEPParameterSpec. The JDK's named OAEP transformations
     * default MGF1 to SHA-1 whatever the main digest is, while KMS RSAES_OAEP_SHA_256 uses
     * MGF1 over SHA-256, so the parameters are always spelled out.
     */
    private static byte[] oaep(int mode, KmsKey key, KmsKeySpec.Algorithm algorithm, byte[] input) {
        try {
            String digest = algorithm == KmsKeySpec.Algorithm.RSAES_OAEP_SHA_1 ? "SHA-1" : "SHA-256";
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            OAEPParameterSpec params = new OAEPParameterSpec(digest, "MGF1", new MGF1ParameterSpec(digest),
                    PSource.PSpecified.DEFAULT);
            if (mode == Cipher.ENCRYPT_MODE) {
                cipher.init(mode, AsymmetricKeys.publicKey(key, "RSA"), params);
            } else {
                cipher.init(mode, AsymmetricKeys.privateKey(key, "RSA"), params);
            }
            return cipher.doFinal(input);
        } catch (Exception e) {
            if (mode == Cipher.DECRYPT_MODE) {
                // Real KMS answers any OAEP failure the same way: the padding check hides
                // whether the bytes were garbage, the wrong length, or made for another key.
                // The log line keeps broken key material or a missing cipher diagnosable.
                LOG.debugv(e, "RSA OAEP decrypt failed for key {0}", key.getKeyId());
                throw new AwsException("InvalidCiphertextException", "The ciphertext is invalid.", 400);
            }
            LOG.warnv(e, "RSA OAEP encrypt failed for key {0}", key.getKeyId());
            throw new AwsException("InternalFailure", "Failed to encrypt: " + e.getMessage(), 500);
        }
    }

    private static boolean isPss(String algorithm) {
        return algorithm.startsWith("RSASSA_PSS");
    }

    /**
     * Wraps a pre-computed digest in the ASN.1 {@code DigestInfo} structure that PKCS#1
     * v1.5 signing prepends before padding (RFC 8017 9.2). Needed for {@code MessageType=DIGEST}
     * RSA signatures because {@code NONEwithRSA} pads only the bytes it is given.
     */
    private static byte[] wrapInDigestInfo(byte[] digest, String algorithm) {
        ASN1ObjectIdentifier hashOid;
        if (algorithm.endsWith("SHA_256")) {
            hashOid = NISTObjectIdentifiers.id_sha256;
        } else if (algorithm.endsWith("SHA_384")) {
            hashOid = NISTObjectIdentifiers.id_sha384;
        } else if (algorithm.endsWith("SHA_512")) {
            hashOid = NISTObjectIdentifiers.id_sha512;
        } else {
            throw new AwsException("InvalidSigningAlgorithmException", "Unsupported algorithm: " + algorithm, 400);
        }
        try {
            return new DigestInfo(new AlgorithmIdentifier(hashOid, DERNull.INSTANCE), digest).getEncoded();
        } catch (IOException e) {
            throw new AwsException("InternalFailure", "Failed to encode DigestInfo: " + e.getMessage(), 500);
        }
    }

    /**
     * Signs a pre-computed digest with RSASSA-PSS.
     *
     * <p>Real KMS applies the PSS encoding directly to the digest a {@code MessageType=DIGEST}
     * caller sends. The JDK's {@code RSASSA-PSS} Signature always hashes its input first, so
     * this uses BouncyCastle's lightweight raw PSS signer, instantiated directly like the
     * secp256k1 and Ed25519ph paths. The raw signer defaults to MGF1 over the same digest with
     * a salt as long as that digest, matching the RAW path, so RAW and DIGEST signatures verify
     * against each other.
     */
    private byte[] signPssDigest(PrivateKey privateKey, byte[] digest, String algorithm) throws Exception {
        PSSSigner signer = rawPssSigner(algorithm);
        signer.init(true, new ParametersWithRandom(PrivateKeyFactory.createKey(privateKey.getEncoded()), random));
        signer.update(digest, 0, digest.length);
        return signer.generateSignature();
    }

    private static boolean verifyPssDigest(PublicKey publicKey, byte[] digest, byte[] signature, String algorithm)
            throws IOException {
        PSSSigner signer = rawPssSigner(algorithm);
        signer.init(false, PublicKeyFactory.createKey(publicKey.getEncoded()));
        signer.update(digest, 0, digest.length);
        return signer.verifySignature(signature);
    }

    private static PSSSigner rawPssSigner(String algorithm) {
        Digest digest = switch (algorithm) {
            case "RSASSA_PSS_SHA_256" -> new SHA256Digest();
            case "RSASSA_PSS_SHA_384" -> new SHA384Digest();
            case "RSASSA_PSS_SHA_512" -> new SHA512Digest();
            default -> throw new AwsException("InvalidSigningAlgorithmException", "Unsupported algorithm: " + algorithm, 400);
        };
        return PSSSigner.createRawSigner(new RSABlindedEngine(), digest);
    }
}
