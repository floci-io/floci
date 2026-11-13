package io.floci.aws.services.ec2;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.docker.DockerPlatforms;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Resolves EC2 AMI IDs to Docker image URIs.
 *
 * Floci-local AMI IDs (e.g. "ami-amazonlinux2023") map to public Docker images.
 * Real AWS AMI IDs (e.g. "ami-0abc12345678") fall back to the catalog default.
 */
@ApplicationScoped
public class AmiImageResolver {

    private static final Logger LOG = Logger.getLogger(AmiImageResolver.class);

    private final Ec2ImageCatalog imageCatalog;

    @Inject
    public AmiImageResolver(Ec2ImageCatalog imageCatalog) {
        this.imageCatalog = imageCatalog;
    }

    /**
     * Resolves an AMI ID to a Docker image URI.
     * Falls back to the catalog default image for unrecognised IDs.
     */
    public String resolve(String imageId) {
        return resolveImage(imageId).dockerImage();
    }

    public ResolvedAmiImage resolveImage(String imageId) {
        if (imageId == null || imageId.isBlank()) {
            LOG.warnv("No imageId provided; using default image {0}", imageCatalog.defaultDockerImage());
            return ResolvedAmiImage.minimal(imageCatalog.defaultDockerImage());
        }

        return imageCatalog.findByIdOrAlias(imageId)
                .map(image -> resolveCatalogImage(image))
                .orElseGet(() -> {
                    LOG.warnv("Unknown AMI ID {0}; falling back to default image {1}",
                            imageId, imageCatalog.defaultDockerImage());
                    return ResolvedAmiImage.minimal(imageCatalog.defaultDockerImage());
                });
    }

    private ResolvedAmiImage resolveCatalogImage(Ec2ImageCatalog.CatalogImage image) {
        if ("windows".equalsIgnoreCase(image.platform)) {
            throw new AwsException("UnsupportedOperation",
                    "Windows AMI execution is not supported by Floci's Linux container runtime.", 400);
        }
        return new ResolvedAmiImage(
                image.dockerImage,
                image.guestRuntime == null || image.guestRuntime.isBlank()
                        ? ResolvedAmiImage.DEFAULT_RUNTIME
                        : image.guestRuntime,
                Boolean.TRUE.equals(image.cloudInit),
                dockerPlatform(image.architecture));
    }

    // EC2's i386, which Docker runs as linux/386, beside the two architectures every service names.
    private static String dockerPlatform(String architecture) {
        return "i386".equals(architecture) ? "linux/386" : DockerPlatforms.forArchitecture(architecture);
    }
}
