package io.github.hectorvent.floci.services.cloudformation.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stack is shared state: it lives in the service's {@code stacks} map and is handed to every
 * request that names it, while a background executor provisions it. Its collections are therefore
 * thread-safe by construction, and every one keeps insertion order because the wire responses
 * (DescribeStacks outputs and parameters, DescribeStackEvents, ListChangeSets) reproduce it:
 * {@link Collections#synchronizedMap} over a {@link LinkedHashMap} for the maps and a
 * {@link CopyOnWriteArrayList} for the append-mostly event log. A single get, put, remove or add
 * on the live collection is safe; iterating one is not (the synchronized-map contract), so
 * callers that walk or copy a collection use the {@code *Snapshot()} accessors, which hold the
 * map's own monitor while copying. Jackson serializes through those snapshots too, so persisting
 * a stack mid-deploy never races the deploy.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Stack {
    private String stackId;
    private String stackName;
    /** AWS account that owns this stack; absent only on legacy records. */
    private String accountId;
    private String region;
    /**
     * ExecuteChangeSet hands deployment to a background executor and returns without waiting for
     * it, so a caller (DescribeStacks) observes this field from a different thread than the one
     * that sets it. It is always the last field a deploy writes, after resources, exports and
     * outputs; {@code volatile} gives that ordering a happens-before edge, so a reader that
     * observes a terminal status is guaranteed to also see every write that preceded it, such as a
     * nested stack's resolved {@code Outputs.*} attributes.
     */
    private volatile String status = "CREATE_IN_PROGRESS";
    private String statusReason;
    private Instant creationTime = Instant.now();
    private Instant lastUpdatedTime;
    private String templateBody;
    private String originalTemplateBody;
    private List<String> capabilities = new ArrayList<>();
    private Map<String, String> parameters = orderedMap();
    // Parameters after AWS::SSM::Parameter::Value<String> resolution, as last applied by
    // executeTemplate — used to detect drift in the live SSM value between deploys even when the
    // referencing parameter (name) is unchanged, so change-set previews agree with execution.
    private Map<String, String> resolvedParameters = orderedMap();
    private Map<String, String> outputs = orderedMap();
    private Map<String, String> exports = orderedMap();
    // Maps output key to its export name (when Export.Name is defined on an output)
    private Map<String, String> outputExportNames = orderedMap();
    private Map<String, StackResource> resources = orderedMap();
    private List<StackEvent> events = new CopyOnWriteArrayList<>();
    private Map<String, ChangeSet> changeSets = orderedMap();
    private Map<String, String> tags = orderedMap();
    private boolean enableTerminationProtection = false;

    public String getStackId() { return stackId; }
    public void setStackId(String stackId) { this.stackId = stackId; }
    public String getStackName() { return stackName; }
    public void setStackName(String stackName) { this.stackName = stackName; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }
    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }
    public Instant getLastUpdatedTime() { return lastUpdatedTime; }
    public void setLastUpdatedTime(Instant lastUpdatedTime) { this.lastUpdatedTime = lastUpdatedTime; }
    public String getTemplateBody() { return templateBody; }
    public void setTemplateBody(String templateBody) { this.templateBody = templateBody; }
    public String getOriginalTemplateBody() { return originalTemplateBody; }
    public void setOriginalTemplateBody(String originalTemplateBody) { this.originalTemplateBody = originalTemplateBody; }
    public List<String> getCapabilities() { return capabilities; }
    public void setCapabilities(List<String> capabilities) { this.capabilities = capabilities; }
    @JsonIgnore
    public Map<String, String> getParameters() { return parameters; }
    @JsonProperty("parameters")
    public void setParameters(Map<String, String> parameters) { this.parameters = orderedMap(parameters); }
    @JsonProperty("parameters")
    public Map<String, String> parametersSnapshot() { return snapshot(parameters); }
    @JsonIgnore
    public Map<String, String> getResolvedParameters() { return resolvedParameters; }
    @JsonProperty("resolvedParameters")
    public void setResolvedParameters(Map<String, String> resolvedParameters) { this.resolvedParameters = orderedMap(resolvedParameters); }
    @JsonProperty("resolvedParameters")
    public Map<String, String> resolvedParametersSnapshot() { return snapshot(resolvedParameters); }
    @JsonIgnore
    public Map<String, String> getOutputs() { return outputs; }
    @JsonProperty("outputs")
    public void setOutputs(Map<String, String> outputs) { this.outputs = orderedMap(outputs); }
    @JsonProperty("outputs")
    public Map<String, String> outputsSnapshot() { return snapshot(outputs); }
    @JsonIgnore
    public Map<String, String> getExports() { return exports; }
    @JsonProperty("exports")
    public void setExports(Map<String, String> exports) { this.exports = orderedMap(exports); }
    @JsonProperty("exports")
    public Map<String, String> exportsSnapshot() { return snapshot(exports); }
    @JsonIgnore
    public Map<String, String> getOutputExportNames() { return outputExportNames; }
    @JsonProperty("outputExportNames")
    public void setOutputExportNames(Map<String, String> outputExportNames) { this.outputExportNames = orderedMap(outputExportNames); }
    @JsonProperty("outputExportNames")
    public Map<String, String> outputExportNamesSnapshot() { return snapshot(outputExportNames); }
    @JsonIgnore
    public Map<String, StackResource> getResources() { return resources; }
    @JsonProperty("resources")
    public void setResources(Map<String, StackResource> resources) { this.resources = orderedMap(resources); }
    @JsonProperty("resources")
    public Map<String, StackResource> resourcesSnapshot() { return snapshot(resources); }
    @JsonIgnore
    public List<StackEvent> getEvents() { return events; }
    @JsonProperty("events")
    public void setEvents(List<StackEvent> events) {
        this.events = new CopyOnWriteArrayList<>(events != null ? events : List.of());
    }
    /** The events in order; a copy-on-write list iterates a snapshot, so this is always consistent. */
    @JsonProperty("events")
    public List<StackEvent> eventsSnapshot() { return new ArrayList<>(events); }
    @JsonIgnore
    public Map<String, ChangeSet> getChangeSets() { return changeSets; }
    @JsonProperty("changeSets")
    public void setChangeSets(Map<String, ChangeSet> changeSets) { this.changeSets = orderedMap(changeSets); }
    @JsonProperty("changeSets")
    public Map<String, ChangeSet> changeSetsSnapshot() { return snapshot(changeSets); }
    @JsonIgnore
    public Map<String, String> getTags() { return tags; }
    @JsonProperty("tags")
    public void setTags(Map<String, String> tags) { this.tags = orderedMap(tags); }
    @JsonProperty("tags")
    public Map<String, String> tagsSnapshot() { return snapshot(tags); }

    /**
     * Replace a whole map as one step. {@code clear()} and {@code putAll()} are two separately
     * locked operations on a synchronized map, so a snapshot taken between them copies an empty
     * map: a DescribeStacks mid-update reporting no Outputs. Holding the map's monitor across the
     * pair makes the replace atomic to {@link #snapshot}, which takes the same monitor.
     */
    public void replaceParameters(Map<String, String> source) { replace(parameters, source); }
    public void replaceResolvedParameters(Map<String, String> source) { replace(resolvedParameters, source); }
    public void replaceOutputs(Map<String, String> source) { replace(outputs, source); }
    public void replaceExports(Map<String, String> source) { replace(exports, source); }
    public void replaceOutputExportNames(Map<String, String> source) { replace(outputExportNames, source); }

    private static <V> void replace(Map<String, V> target, Map<String, V> source) {
        synchronized (target) {
            target.clear();
            target.putAll(source);
        }
    }

    private static <V> Map<String, V> orderedMap() {
        return Collections.synchronizedMap(new LinkedHashMap<>());
    }

    private static <V> Map<String, V> orderedMap(Map<String, V> source) {
        Map<String, V> map = orderedMap();
        if (source != null) {
            map.putAll(source);
        }
        return map;
    }

    /** A consistent, insertion-ordered copy; the map's monitor guards the walk (synchronizedMap contract). */
    private static <V> Map<String, V> snapshot(Map<String, V> map) {
        synchronized (map) {
            return new LinkedHashMap<>(map);
        }
    }
    public boolean isEnableTerminationProtection() { return enableTerminationProtection; }
    public void setEnableTerminationProtection(boolean enableTerminationProtection) { this.enableTerminationProtection = enableTerminationProtection; }
}
