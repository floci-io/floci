package io.github.hectorvent.floci.services.kms.model;

import io.github.hectorvent.floci.core.common.AwsException;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

@RegisterForReflection
public enum KmsKeySpec {

    SYMMETRIC_DEFAULT(KeyType.SYMMETRIC, List.of(Algorithm.SYMMETRIC_DEFAULT)),
    RSA_2048(KeyType.RSA, getRsaAlgos()),
    RSA_3072(KeyType.RSA, getRsaAlgos()),
    RSA_4096(KeyType.RSA, getRsaAlgos()),

    ECC_NIST_P256(KeyType.ECC, Algorithm.ECDSA_SHA_256,"secp256r1"),
    ECC_NIST_P384(KeyType.ECC, Algorithm.ECDSA_SHA_384,"secp384r1"),
    ECC_NIST_P521(KeyType.ECC, Algorithm.ECDSA_SHA_512, "secp521r1"),
    ECC_NIST_EDWARDS25519(KeyType.ECC, List.of(Algorithm.ED25519_SHA_512, Algorithm.ED25519_PH_SHA_512),"secp521r1"),
    ECC_SECG_P256K1(KeyType.ECC, Algorithm.ECDSA_SHA_256,"secp256k1"),

    HMAC_224(KeyType.HMAC, Algorithm.HMAC_SHA_224),
    HMAC_256(KeyType.HMAC, Algorithm.HMAC_SHA_256),
    HMAC_384(KeyType.HMAC, Algorithm.HMAC_SHA_384),
    HMAC_512(KeyType.HMAC, Algorithm.HMAC_SHA_512),

    SM2(KeyType.SM2, Algorithm.SM2_DSA),

    ML_DSA_44(KeyType.ML_DSA, Algorithm.ML_DSA_SHAKE_256),
    ML_DSA_65(KeyType.ML_DSA, Algorithm.ML_DSA_SHAKE_256),
    ML_DSA_87(KeyType.ML_DSA, Algorithm.ML_DSA_SHAKE_256);

    KmsKeySpec(KeyType keyType, Algorithm algorithm) {
        this.keyType = keyType;
        this.algorithm = List.of(algorithm);
        this.curveName = null;
    }

    KmsKeySpec(KeyType keyType, Algorithm algorithm, String curveName) {
        this.keyType = keyType;
        this.algorithm = List.of(algorithm);
        this.curveName = curveName;
    }

    KmsKeySpec(KeyType keyType, List<Algorithm> algorithm) {
        this.keyType = keyType;
        this.algorithm = algorithm;
        this.curveName = null;
    }
    KmsKeySpec(KeyType keyType, List<Algorithm> algorithm, String curveName) {
        this.keyType = keyType;
        this.algorithm = algorithm;
        this.curveName = curveName;
    }

    private final KeyType keyType;
    private final List<Algorithm> algorithm;
    private final String curveName;

    public static KmsKeySpec fromString(String keySpec) {
        try {
            return KmsKeySpec.valueOf(keySpec);
        } catch (IllegalArgumentException _) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + keySpec + "' at 'KeySpec' failed to satisfy constraint", 400);
        }
    }

    public KeyType getKeyType() {
        return keyType;
    }

    public List<Algorithm> getAlgorithm() {
        return algorithm;
    }

    public String curveName() {
        return curveName;
    }
    public enum KeyType {
        RSA, ECC, SYMMETRIC, HMAC, ML_DSA, SM2
    }

    private static List<Algorithm> getRsaAlgos() {
        return List.of(Algorithm.RSASSA_PKCS1_V1_5_SHA_256, Algorithm.RSASSA_PKCS1_V1_5_SHA_384, Algorithm.RSASSA_PKCS1_V1_5_SHA_512,
                Algorithm.RSASSA_PSS_SHA_256, Algorithm.RSASSA_PSS_SHA_384, Algorithm.RSASSA_PSS_SHA_512,
                Algorithm.RSAES_OAEP_SHA_1, Algorithm.RSAES_OAEP_SHA_256);
    }

    public static Algorithm getSignVerifyAlgorithm(String signingAlgorithm) {
        try {
           return Algorithm.valueOf(signingAlgorithm);
        } catch (IllegalArgumentException _) {
            throw new AwsException("InvalidSigningAlgorithmException", "Unsupported algorithm: " + signingAlgorithm, 400);
        }
    }

    public enum Algorithm  {
        SYMMETRIC_DEFAULT("SYMMETRIC_DEFAULT", "", KmsKeyUsage.ENCRYPT_DECRYPT),
        RSASSA_PSS_SHA_256("RSASSA_PSS_SHA_256","SHA256withRSA/PSS", KmsKeyUsage.SIGN_VERIFY),
        RSASSA_PSS_SHA_384("RSASSA_PSS_SHA_384","SHA384withRSA/PSS", KmsKeyUsage.SIGN_VERIFY),
        RSASSA_PSS_SHA_512("RSASSA_PSS_SHA_512", "SHA512withRSA/PSS", KmsKeyUsage.SIGN_VERIFY),
        RSASSA_PKCS1_V1_5_SHA_256("RSASSA_PKCS1_V1_5_SHA_256","SHA256withRSA", KmsKeyUsage.SIGN_VERIFY),
        RSASSA_PKCS1_V1_5_SHA_384("RSASSA_PKCS1_V1_5_SHA_384", "SHA384withRSA", KmsKeyUsage.SIGN_VERIFY),
        RSASSA_PKCS1_V1_5_SHA_512("RSASSA_PKCS1_V1_5_SHA_512", "SHA512withRSA", KmsKeyUsage.SIGN_VERIFY),
        RSAES_OAEP_SHA_1("RSAES_OAEP_SHA_1", "", KmsKeyUsage.ENCRYPT_DECRYPT),
        RSAES_OAEP_SHA_256("RSAES_OAEP_SHA_256", "", KmsKeyUsage.ENCRYPT_DECRYPT),
        ECDSA_SHA_256("ECDSA_SHA_256", "SHA256withECDSA", KmsKeyUsage.SIGN_VERIFY),
        ECDSA_SHA_384("ECDSA_SHA_384","SHA384withECDSA", KmsKeyUsage.SIGN_VERIFY),
        ECDSA_SHA_512("ECDSA_SHA_512", "SHA512withECDSA", KmsKeyUsage.SIGN_VERIFY),
        ED25519_SHA_512("ED25519_SHA_512","Ed25519", KmsKeyUsage.SIGN_VERIFY),
        ED25519_PH_SHA_512("ED25519_PH_SHA_512", "Ed25519", KmsKeyUsage.SIGN_VERIFY),
        SM2_DSA("SM2DSA","", KmsKeyUsage.SIGN_VERIFY),
        ML_DSA_SHAKE_256("ML_DSA_SHAKE_256","", KmsKeyUsage.SIGN_VERIFY),
        HMAC_SHA_224("HMAC_SHA_224",""),
        HMAC_SHA_256("HMAC_SHA_256",""),
        HMAC_SHA_384("HMAC_SHA_384",""),
        HMAC_SHA_512("HMAC_SHA_512","");
        private final String algName;
        private final String javaName;
        private KmsKeyUsage keyUsage;
        Algorithm(String algName, String javaName) {
            this.algName = algName;
            this.javaName = javaName;
        }

        Algorithm(String algName, String javaName, KmsKeyUsage keyUsage) {
            this.algName = algName;
            this.javaName = javaName;
            this.keyUsage = keyUsage;
        }

        public String getAlgName() {
            return algName;
        }

        public String getJavaName() {
            return javaName;
        }

        public KmsKeyUsage getKeyUsage() {
            return keyUsage;
        }

        public static List<Algorithm> getAll() {
            return new ArrayList<>(EnumSet.allOf(Algorithm.class));
        }
    }
}
