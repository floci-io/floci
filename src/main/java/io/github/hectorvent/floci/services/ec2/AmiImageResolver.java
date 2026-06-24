package io.github.hectorvent.floci.services.ec2;

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
        if (imageId == null || imageId.isBlank()) {
            LOG.warnv("No imageId provided; using default image {0}", imageCatalog.defaultDockerImage());
            return imageCatalog.defaultDockerImage();
        }

        return imageCatalog.findByIdOrAlias(imageId)
                .map(image -> image.dockerImage)
                .orElseGet(() -> {
                    LOG.warnv("Unknown AMI ID {0}; falling back to default image {1}",
                            imageId, imageCatalog.defaultDockerImage());
                    return imageCatalog.defaultDockerImage();
                });
    }
}
