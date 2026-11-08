package io.floci.aws.services.acm.model;

import com.fasterxml.jackson.annotation.JsonValue;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.jboss.logging.Logger;

/**
 * Key algorithm for certificate key pair generation.
 *
 * @see <a href="https://docs.aws.amazon.com/acm/latest/APIReference/API_RequestCertificate.html">AWS ACM RequestCertificate</a>
 */
@RegisterForReflection
public enum KeyAlgorithm {
    RSA_1024("RSA-1024", "RSA", 1024),
    RSA_2048("RSA-2048", "RSA", 2048),
    RSA_3072("RSA-3072", "RSA", 3072),
    RSA_4096("RSA-4096", "RSA", 4096),
    EC_prime256v1("EC-prime256v1", "EC", 256),
    EC_secp384r1("EC-secp384r1", "EC", 384),
    EC_secp521r1("EC-secp521r1", "EC", 521);

    private static final Logger LOG = Logger.getLogger(KeyAlgorithm.class);

    private final String awsName;
    private final String algorithm;
    private final int keySize;

    KeyAlgorithm(String awsName, String algorithm, int keySize) {
        this.awsName = awsName;
        this.algorithm = algorithm;
        this.keySize = keySize;
    }

    @JsonValue
    public String getAwsName() {
        return awsName;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public int getKeySize() {
        return keySize;
    }

    public String getCurveName() {
        return switch (this) {
            case EC_prime256v1 -> "secp256r1";
            case EC_secp384r1 -> "secp384r1";
            case EC_secp521r1 -> "secp521r1";
            default -> null;
        };
    }

    public static KeyAlgorithm fromAwsName(String name) {
        if (name == null) return RSA_2048;
        // Normalize both directions: RSA_2048 ↔ RSA-2048
        String dashNormalized = name.replace("_", "-");
        String underscoreNormalized = name.replace("-", "_");
        for (KeyAlgorithm alg : values()) {
            // Match against awsName (dash format: "RSA-2048", "EC-prime256v1")
            if (alg.awsName.equalsIgnoreCase(name) || alg.awsName.equalsIgnoreCase(dashNormalized)) {
                return alg;
            }
            // Match against enum name (underscore format: "RSA_2048", "EC_prime256v1")
            if (alg.name().equalsIgnoreCase(name) || alg.name().equalsIgnoreCase(underscoreNormalized)) {
                return alg;
            }
        }
        LOG.warnv("Unknown key algorithm '{0}', defaulting to RSA_2048", name);
        return RSA_2048;
    }
}
