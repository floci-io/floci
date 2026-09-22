package io.github.hectorvent.floci.services.kms.keytype;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.kms.CipherUtils;
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
import java.security.*;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;

import static io.github.hectorvent.floci.services.kms.model.KmsMessageType.RAW;

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
    public byte[] sign(KmsKey key, byte[] message, KmsKeySpec.Algorithm algorithm, KmsMessageType messageType)
            throws Exception {
        PrivateKey privateKey = AsymmetricKeys.privateKey(key, "RSA");
        if (messageType == RAW) {
            return AsymmetricKeys.sign(privateKey, algorithm.getJavaName(), message);
        }
        if (isPss(algorithm)) {
            return signPssDigest(privateKey, message, algorithm);
        }
        return AsymmetricKeys.sign(privateKey, "NONEwithRSA", wrapInDigestInfo(message, algorithm));
    }

    @Override
    public boolean verify(KmsKey key, byte[] message, byte[] signature, KmsKeySpec.Algorithm algorithm,
                          KmsMessageType messageType) throws Exception {
        PublicKey publicKey = AsymmetricKeys.publicKey(key, "RSA");
        if (messageType == RAW) {
            return AsymmetricKeys.verify(publicKey, algorithm.getJavaName(), message, signature);
        }
        if (isPss(algorithm)) {
            return verifyPssDigest(publicKey, message, signature, algorithm);
        }
        return AsymmetricKeys.verify(publicKey, "NONEwithRSA", wrapInDigestInfo(message, algorithm), signature);
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

    @Override
    public void importKeyMaterial(KmsKey key, byte[] material) {
        PrivateKey privateKey = parsePrivateKey(material);

        if (!(privateKey instanceof RSAPrivateCrtKey rsaPrivateKey)) {
            throw new AwsException("IncorrectKeyMaterialException",
                    "Imported RSA key material must be a PKCS#8-encoded two-prime RSA private key.", 400);
        }

        requireExpectedModulusSize(key.getKeySpec(), rsaPrivateKey);

        PublicKey publicKey = generatePublicKey(rsaPrivateKey);

        Base64.Encoder encoder = Base64.getEncoder();
        key.setPrivateKeyEncoded(encoder.encodeToString(material));
        key.setPublicKeyEncoded(encoder.encodeToString(publicKey.getEncoded()));
    }

    private static PrivateKey parsePrivateKey(byte[] material) {
        try {
            return CipherUtils.generateRsaPrivateKey(material);
        } catch (GeneralSecurityException e) {
            throw new AwsException("IncorrectKeyMaterialException",
                    "Imported RSA key material is not a valid PKCS#8 RSA private key.", 400);
        }
    }

    private static PublicKey generatePublicKey(RSAPrivateCrtKey privateKey) {
        try {
            return CipherUtils.generateRsaPublicKey(privateKey);
        } catch (GeneralSecurityException e) {
            throw new AwsException("InternalFailure",
                    "Failed to derive RSA public key: " + e.getMessage(), 500);
        }
    }

    private static void requireExpectedModulusSize(KmsKeySpec spec, RSAPrivateCrtKey privateKey) {
        int expectedBits = keySize(spec);
        int actualBits = privateKey.getModulus().bitLength();
        if (actualBits != expectedBits) {
            throw new AwsException("IncorrectKeyMaterialException",
                    "Key material for key spec " + spec + " must have a " + expectedBits
                            + "-bit RSA modulus but was " + actualBits + " bits.", 400);
        }
    }

    private static int keySize(KmsKeySpec spec) {
        return Integer.parseInt(spec.name().substring("RSA_".length()));
    }

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

    // The JDK OAEP transformations default MGF1 to SHA-1. KMS RSAES_OAEP_SHA_256 uses MGF1 over SHA-256.
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
                LOG.debugv(e, "RSA OAEP decrypt failed for key {0}", key.getKeyId());
                throw new AwsException("InvalidCiphertextException", null, 400);
            }
            LOG.warnv(e, "RSA OAEP encrypt failed for key {0}", key.getKeyId());
            throw new AwsException("InternalFailure", "Failed to encrypt: " + e.getMessage(), 500);
        }
    }

    private static boolean isPss(KmsKeySpec.Algorithm algorithm) {
        return switch (algorithm) {
            case RSASSA_PSS_SHA_256, RSASSA_PSS_SHA_384, RSASSA_PSS_SHA_512 -> true;
            default -> false;
        };
    }

    // NONEwithRSA pads only the bytes it gets, so PKCS#1 v1.5 needs the DigestInfo wrapper (RFC 8017 9.2).
    private static byte[] wrapInDigestInfo(byte[] digest, KmsKeySpec.Algorithm algorithm) {
        ASN1ObjectIdentifier hashOid = switch (algorithm) {
            case RSASSA_PKCS1_V1_5_SHA_256 -> NISTObjectIdentifiers.id_sha256;
            case RSASSA_PKCS1_V1_5_SHA_384 -> NISTObjectIdentifiers.id_sha384;
            case RSASSA_PKCS1_V1_5_SHA_512 -> NISTObjectIdentifiers.id_sha512;
            default -> throw new IllegalStateException("Not a PKCS#1 v1.5 algorithm: " + algorithm);
        };
        try {
            return new DigestInfo(new AlgorithmIdentifier(hashOid, DERNull.INSTANCE), digest).getEncoded();
        } catch (IOException e) {
            throw new AwsException("InternalFailure", "Failed to encode DigestInfo: " + e.getMessage(), 500);
        }
    }

    // The JDK RSASSA-PSS Signature always hashes its input, so a DIGEST request needs BC's raw PSS signer.
    private byte[] signPssDigest(PrivateKey privateKey, byte[] digest, KmsKeySpec.Algorithm algorithm)
            throws Exception {
        PSSSigner signer = rawPssSigner(algorithm);
        signer.init(true, new ParametersWithRandom(PrivateKeyFactory.createKey(privateKey.getEncoded()), random));
        signer.update(digest, 0, digest.length);
        return signer.generateSignature();
    }

    private static boolean verifyPssDigest(PublicKey publicKey, byte[] digest, byte[] signature,
                                           KmsKeySpec.Algorithm algorithm) throws IOException {
        PSSSigner signer = rawPssSigner(algorithm);
        signer.init(false, PublicKeyFactory.createKey(publicKey.getEncoded()));
        signer.update(digest, 0, digest.length);
        return signer.verifySignature(signature);
    }

    private static PSSSigner rawPssSigner(KmsKeySpec.Algorithm algorithm) {
        Digest digest = switch (algorithm) {
            case RSASSA_PSS_SHA_256 -> new SHA256Digest();
            case RSASSA_PSS_SHA_384 -> new SHA384Digest();
            case RSASSA_PSS_SHA_512 -> new SHA512Digest();
            default -> throw new IllegalStateException("Not a PSS algorithm: " + algorithm);
        };
        return PSSSigner.createRawSigner(new RSABlindedEngine(), digest);
    }
}
