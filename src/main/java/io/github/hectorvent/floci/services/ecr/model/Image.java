package io.github.hectorvent.floci.services.ecr.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * An image manifest returned by BatchGetImage.
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonECR/latest/APIReference/API_Image.html">AWS ECR Image</a>
 */
@RegisterForReflection
public class Image {
    private String registryId;
    private String repositoryName;
    private ImageIdentifier imageId;
    private String imageManifest;
    private String imageManifestMediaType;

    public Image() {}

    public String getRegistryId() { return registryId; }
    public void setRegistryId(String registryId) { this.registryId = registryId; }

    public String getRepositoryName() { return repositoryName; }
    public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }

    public ImageIdentifier getImageId() { return imageId; }
    public void setImageId(ImageIdentifier imageId) { this.imageId = imageId; }

    public String getImageManifest() { return imageManifest; }
    public void setImageManifest(String imageManifest) { this.imageManifest = imageManifest; }

    public String getImageManifestMediaType() { return imageManifestMediaType; }
    public void setImageManifestMediaType(String imageManifestMediaType) { this.imageManifestMediaType = imageManifestMediaType; }
}
