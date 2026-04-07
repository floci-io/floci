package io.github.hectorvent.floci.services.ecr.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Reference to an image by tag, digest, or both.
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonECR/latest/APIReference/API_ImageIdentifier.html">AWS ECR ImageIdentifier</a>
 */
@RegisterForReflection
public class ImageIdentifier {
    private String imageTag;
    private String imageDigest;

    public ImageIdentifier() {}

    public ImageIdentifier(String imageTag, String imageDigest) {
        this.imageTag = imageTag;
        this.imageDigest = imageDigest;
    }

    public String getImageTag() { return imageTag; }
    public void setImageTag(String imageTag) { this.imageTag = imageTag; }

    public String getImageDigest() { return imageDigest; }
    public void setImageDigest(String imageDigest) { this.imageDigest = imageDigest; }
}
