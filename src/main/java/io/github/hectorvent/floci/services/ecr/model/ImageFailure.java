package io.github.hectorvent.floci.services.ecr.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Per-item failure entry for batch image operations.
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonECR/latest/APIReference/API_ImageFailure.html">AWS ECR ImageFailure</a>
 */
@RegisterForReflection
public class ImageFailure {
    private ImageIdentifier imageId;
    private String failureCode;
    private String failureReason;

    public ImageFailure() {}

    public ImageFailure(ImageIdentifier imageId, String failureCode, String failureReason) {
        this.imageId = imageId;
        this.failureCode = failureCode;
        this.failureReason = failureReason;
    }

    public ImageIdentifier getImageId() { return imageId; }
    public void setImageId(ImageIdentifier imageId) { this.imageId = imageId; }

    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
}
