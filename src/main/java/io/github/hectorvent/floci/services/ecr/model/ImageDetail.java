package io.github.hectorvent.floci.services.ecr.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Detailed image metadata returned by DescribeImages.
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonECR/latest/APIReference/API_ImageDetail.html">AWS ECR ImageDetail</a>
 */
@RegisterForReflection
public class ImageDetail {
    private String registryId;
    private String repositoryName;
    private String imageDigest;
    private List<String> imageTags = new ArrayList<>();
    private long imageSizeInBytes;
    private Instant imagePushedAt;
    private String imageManifestMediaType;
    private String artifactMediaType;

    public ImageDetail() {}

    public String getRegistryId() { return registryId; }
    public void setRegistryId(String registryId) { this.registryId = registryId; }

    public String getRepositoryName() { return repositoryName; }
    public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }

    public String getImageDigest() { return imageDigest; }
    public void setImageDigest(String imageDigest) { this.imageDigest = imageDigest; }

    public List<String> getImageTags() { return imageTags; }
    public void setImageTags(List<String> imageTags) { this.imageTags = imageTags == null ? new ArrayList<>() : imageTags; }

    public long getImageSizeInBytes() { return imageSizeInBytes; }
    public void setImageSizeInBytes(long imageSizeInBytes) { this.imageSizeInBytes = imageSizeInBytes; }

    public Instant getImagePushedAt() { return imagePushedAt; }
    public void setImagePushedAt(Instant imagePushedAt) { this.imagePushedAt = imagePushedAt; }

    public String getImageManifestMediaType() { return imageManifestMediaType; }
    public void setImageManifestMediaType(String imageManifestMediaType) { this.imageManifestMediaType = imageManifestMediaType; }

    public String getArtifactMediaType() { return artifactMediaType; }
    public void setArtifactMediaType(String artifactMediaType) { this.artifactMediaType = artifactMediaType; }
}
