package io.github.hectorvent.floci.services.sagemaker.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * A SageMaker machine learning algorithm registered via {@code CreateAlgorithm}.
 * The nested specification blocks ({@code TrainingSpecification},
 * {@code InferenceSpecification}, {@code ValidationSpecification}) are stored as the raw
 * request JSON and echoed back verbatim on describe, since the caller already sent them in
 * the exact AWS wire shape.
 */
@RegisterForReflection
public class SageMakerAlgorithm {

    private String algorithmArn;
    private String algorithmName;
    private String algorithmDescription;
    private JsonNode trainingSpecification;
    private JsonNode inferenceSpecification;
    private JsonNode validationSpecification;
    private boolean certifyForMarketplace;
    private String productId;
    private String algorithmStatus = "Completed";
    private Instant creationTime;
    private Map<String, String> tags = new HashMap<>();

    public SageMakerAlgorithm() {
    }

    public String getAlgorithmArn() {
        return algorithmArn;
    }

    public void setAlgorithmArn(String algorithmArn) {
        this.algorithmArn = algorithmArn;
    }

    public String getAlgorithmName() {
        return algorithmName;
    }

    public void setAlgorithmName(String algorithmName) {
        this.algorithmName = algorithmName;
    }

    public String getAlgorithmDescription() {
        return algorithmDescription;
    }

    public void setAlgorithmDescription(String algorithmDescription) {
        this.algorithmDescription = algorithmDescription;
    }

    public JsonNode getTrainingSpecification() {
        return trainingSpecification;
    }

    public void setTrainingSpecification(JsonNode trainingSpecification) {
        this.trainingSpecification = trainingSpecification;
    }

    public JsonNode getInferenceSpecification() {
        return inferenceSpecification;
    }

    public void setInferenceSpecification(JsonNode inferenceSpecification) {
        this.inferenceSpecification = inferenceSpecification;
    }

    public JsonNode getValidationSpecification() {
        return validationSpecification;
    }

    public void setValidationSpecification(JsonNode validationSpecification) {
        this.validationSpecification = validationSpecification;
    }

    public boolean isCertifyForMarketplace() {
        return certifyForMarketplace;
    }

    public void setCertifyForMarketplace(boolean certifyForMarketplace) {
        this.certifyForMarketplace = certifyForMarketplace;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getAlgorithmStatus() {
        return algorithmStatus;
    }

    public void setAlgorithmStatus(String algorithmStatus) {
        this.algorithmStatus = algorithmStatus;
    }

    public Instant getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(Instant creationTime) {
        this.creationTime = creationTime;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }
}
