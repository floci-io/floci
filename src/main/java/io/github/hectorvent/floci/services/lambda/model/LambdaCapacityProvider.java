package io.github.hectorvent.floci.services.lambda.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.Map;

/**
 * A Lambda capacity provider (API version 2025-11-30).
 *
 * <p>The wire shape carries only the ARN, so the name is kept here for storage keying and
 * ARN construction. Configuration blocks are held as {@link JsonNode} so a describe returns
 * exactly what the create was given.
 */
@RegisterForReflection
public class LambdaCapacityProvider {

    private String name;
    private String arn;
    private String state;
    private JsonNode vpcConfig;
    private JsonNode permissionsConfig;
    private JsonNode instanceRequirements;
    private JsonNode scalingConfig;
    private String kmsKeyArn;
    private String lastModified;
    private String region;
    private Map<String, String> tags = new HashMap<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public JsonNode getVpcConfig() {
        return vpcConfig;
    }

    public void setVpcConfig(JsonNode vpcConfig) {
        this.vpcConfig = vpcConfig;
    }

    public JsonNode getPermissionsConfig() {
        return permissionsConfig;
    }

    public void setPermissionsConfig(JsonNode permissionsConfig) {
        this.permissionsConfig = permissionsConfig;
    }

    public JsonNode getInstanceRequirements() {
        return instanceRequirements;
    }

    public void setInstanceRequirements(JsonNode instanceRequirements) {
        this.instanceRequirements = instanceRequirements;
    }

    public JsonNode getScalingConfig() {
        return scalingConfig;
    }

    public void setScalingConfig(JsonNode scalingConfig) {
        this.scalingConfig = scalingConfig;
    }

    public String getKmsKeyArn() {
        return kmsKeyArn;
    }

    public void setKmsKeyArn(String kmsKeyArn) {
        this.kmsKeyArn = kmsKeyArn;
    }

    public String getLastModified() {
        return lastModified;
    }

    public void setLastModified(String lastModified) {
        this.lastModified = lastModified;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new HashMap<>();
    }
}
