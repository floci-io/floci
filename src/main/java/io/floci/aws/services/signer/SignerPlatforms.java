package io.floci.aws.services.signer;

import java.util.List;
import java.util.Optional;

/**
 * The signing platforms AWS publishes, with the configuration and image formats each accepts.
 *
 * <p>Signer does not discover platforms from AWS: the list is fixed for a given API version and the
 * AWS SDK never sends it, so a profile names one of these ids and Floci answers the matching
 * description. The values follow the published platform descriptions; only the three platforms an
 * emulated workflow can reach are listed.
 */
public final class SignerPlatforms {

    /** One published signing platform and the options a profile may override. */
    public record Platform(
            String platformId,
            String displayName,
            String partner,
            String target,
            String category,
            List<String> hashAlgorithms,
            String defaultHashAlgorithm,
            List<String> encryptionAlgorithms,
            String defaultEncryptionAlgorithm,
            List<String> imageFormats,
            String defaultImageFormat,
            int maxSizeInMB,
            boolean revocationSupported,
            /**
             * The period Signer applies when a profile sets none: 135 months on the platforms that
             * expire signatures. Zero on a platform whose signatures do not carry a validity.
             */
            int defaultValidityMonths) {
    }

    public static final Platform AWS_LAMBDA = new Platform(
            "AWSLambda-SHA384-ECDSA",
            "AWS Lambda",
            "AWS",
            "Lambda",
            "AWSLambda",
            List.of("SHA-256", "SHA-384"),
            "SHA-384",
            List.of("ECDSA"),
            "ECDSA",
            List.of("JSON"),
            "JSON",
            50,
            true,
            135);

    public static final Platform NOTATION_OCI = new Platform(
            "Notation-OCI-SHA384-ECDSA",
            "Notation OCI",
            "AWS",
            "OCI",
            "Notation-OCI",
            List.of("SHA-384"),
            "SHA-384",
            List.of("ECDSA"),
            "ECDSA",
            List.of("JSON"),
            "JSON",
            500,
            true,
            135);

    public static final Platform IOT_SHA256 = new Platform(
            "AWSIoTDeviceManagement-SHA256-ECDSA",
            "AWS IoT Device Management SHA256 ECDSA",
            "AWS",
            "IoT",
            "AWSIoT",
            List.of("SHA-256"),
            "SHA-256",
            List.of("ECDSA"),
            "ECDSA",
            List.of("JSONEmbedded", "JSONDetached"),
            "JSONEmbedded",
            50,
            false,
            0);

    private static final List<Platform> ALL = List.of(AWS_LAMBDA, NOTATION_OCI, IOT_SHA256);

    private SignerPlatforms() {
    }

    public static List<Platform> all() {
        return ALL;
    }

    public static Optional<Platform> find(String platformId) {
        if (platformId == null) {
            return Optional.empty();
        }
        return ALL.stream().filter(platform -> platform.platformId().equals(platformId)).findFirst();
    }
}
