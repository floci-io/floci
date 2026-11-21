package io.floci.aws.services.eks;

import io.floci.aws.services.ec2.Ec2ImageCatalog;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The release of an EKS optimized AMI, {@code <Kubernetes patch version>-<build date>} as AWS
 * writes it ({@code 1.14.9-20201007}): the patch version Floci runs for the Kubernetes version and
 * the catalog image's creation date. SSM publishes it as {@code recommended/release_version} and a
 * managed node group reports it as its {@code releaseVersion}, so both build it here and agree.
 */
public final class EksAmiReleaseVersions {

    public static final String OPTIMIZED_AMI_PARAMETER_PREFIX = "/aws/service/eks/optimized-ami/";

    private static final DateTimeFormatter BUILD_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US).withZone(ZoneOffset.UTC);

    /** The recommended-AMI path under {@link #OPTIMIZED_AMI_PARAMETER_PREFIX} of each AMI type the catalog serves. */
    private static final Map<String, String> RECOMMENDED_AMI_PATHS = Map.of(
            "AL2_x86_64", "amazon-linux-2",
            "AL2023_x86_64_STANDARD", "amazon-linux-2023/x86_64/standard",
            "AL2023_ARM_64_STANDARD", "amazon-linux-2023/arm64/standard");

    private EksAmiReleaseVersions() {
    }

    /** Whether Floci runs a node image for the Kubernetes version, and so has a patch version for it. */
    public static boolean supports(String kubernetesVersion) {
        return EksClusterManager.SUPPORTED_K8S_VERSIONS.containsKey(kubernetesVersion);
    }

    /** The release of an image built at {@code imageCreationDate}, for a version {@link #supports} accepts. */
    public static String of(String kubernetesVersion, String imageCreationDate) {
        String nodeImage = EksClusterManager.SUPPORTED_K8S_VERSIONS.get(kubernetesVersion);
        String tag = nodeImage.substring(nodeImage.indexOf(":v") + 2);
        return tag.substring(0, tag.indexOf('-')) + "-" + BUILD_DATE.format(Instant.parse(imageCreationDate));
    }

    /**
     * The release of the recommended AMI for a node group's Kubernetes version and AMI type, the
     * value SSM publishes for it, or empty when the catalog has no such AMI.
     */
    public static Optional<String> recommended(Ec2ImageCatalog catalog, String kubernetesVersion, String amiType) {
        String amiPath = RECOMMENDED_AMI_PATHS.get(amiType);
        if (catalog == null || amiPath == null || !supports(kubernetesVersion)) {
            return Optional.empty();
        }
        return catalog.findByPublicParameterName(OPTIMIZED_AMI_PARAMETER_PREFIX + kubernetesVersion + "/" + amiPath
                        + "/recommended/image_id")
                .map(image -> of(kubernetesVersion, image.creationDate));
    }
}
