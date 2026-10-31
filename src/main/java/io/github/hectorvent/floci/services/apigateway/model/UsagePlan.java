package io.github.hectorvent.floci.services.apigateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class UsagePlan {
    private String id;
    private String name;
    private String description;
    private List<ApiStage> apiStages = new ArrayList<>();
    private Map<String, String> tags = new HashMap<>();
    private Throttle throttle;
    private Quota quota;

    public UsagePlan() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<ApiStage> getApiStages() { return apiStages; }
    public void setApiStages(List<ApiStage> apiStages) { this.apiStages = apiStages; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags != null ? tags : new HashMap<>(); }

    public Throttle getThrottle() { return throttle; }
    public void setThrottle(Throttle throttle) { this.throttle = throttle; }

    public Quota getQuota() { return quota; }
    public void setQuota(Quota quota) { this.quota = quota; }

    /**
     * The plan's stages, throttle and quota as one value, for comparing and replacing them together.
     * Synchronised with {@link #applyLimits} so a reader never sees parts of two revisions.
     */
    public synchronized Limits limits() {
        return new Limits(List.copyOf(apiStages), throttle, quota);
    }

    /** Synchronised with {@link #limits} because it writes three fields that are only valid together. */
    public synchronized void applyLimits(Limits limits) {
        this.apiStages = new ArrayList<>(limits.apiStages());
        this.throttle = limits.throttle();
        this.quota = limits.quota();
    }

    /** {@code throttle} maps {@code <resourcePath>/<httpMethod>} to its limits; null when the stage has none. */
    @RegisterForReflection
    public record ApiStage(String apiId, String stage, Map<String, Throttle> throttle) {
        /** A stage with no per-method throttle, which is what an {@code add /apiStages} patch creates. */
        public ApiStage(String apiId, String stage) {
            this(apiId, stage, null);
        }
    }

    @RegisterForReflection
    public record Throttle(int burstLimit, double rateLimit) {}

    @RegisterForReflection
    public record Quota(int limit, int offset, String period) {}

    /** A plan's stages, throttle and quota, which CloudFormation replaces as one. */
    @RegisterForReflection
    public record Limits(List<ApiStage> apiStages, Throttle throttle, Quota quota) {}
}
