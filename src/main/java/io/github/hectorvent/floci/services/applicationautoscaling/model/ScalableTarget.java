package io.github.hectorvent.floci.services.applicationautoscaling.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A registered scalable target.
 *
 * <p>Identity is the triple (serviceNamespace, resourceId, scalableDimension); there is no
 * separate identifier. {@code scalableTargetArn} is the tagging identifier that the
 * Terraform provider reads from DescribeScalableTargets and then passes to
 * ListTagsForResource, so it must always be populated.</p>
 *
 * @see <a href="https://docs.aws.amazon.com/autoscaling/application/APIReference/API_ScalableTarget.html">ScalableTarget</a>
 */
@RegisterForReflection
public class ScalableTarget {

    private String serviceNamespace;
    private String resourceId;
    private String scalableDimension;
    private Integer minCapacity;
    private Integer maxCapacity;
    private String roleArn;
    private String scalableTargetArn;
    private double creationTime;
    private SuspendedState suspendedState = new SuspendedState();
    private Map<String, String> tags = new HashMap<>();

    public String getServiceNamespace() { return serviceNamespace; }
    public void setServiceNamespace(String v) { this.serviceNamespace = v; }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String v) { this.resourceId = v; }

    public String getScalableDimension() { return scalableDimension; }
    public void setScalableDimension(String v) { this.scalableDimension = v; }

    public Integer getMinCapacity() { return minCapacity; }
    public void setMinCapacity(Integer v) { this.minCapacity = v; }

    public Integer getMaxCapacity() { return maxCapacity; }
    public void setMaxCapacity(Integer v) { this.maxCapacity = v; }

    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String v) { this.roleArn = v; }

    public String getScalableTargetArn() { return scalableTargetArn; }
    public void setScalableTargetArn(String v) { this.scalableTargetArn = v; }

    public double getCreationTime() { return creationTime; }
    public void setCreationTime(double v) { this.creationTime = v; }

    public SuspendedState getSuspendedState() { return suspendedState; }
    public void setSuspendedState(SuspendedState v) { this.suspendedState = v == null ? new SuspendedState() : v; }

    /** Read-only view; mutate through {@link #putTags} and {@link #removeTags}. */
    public Map<String, String> getTags() { return Collections.unmodifiableMap(tags); }

    /** Copies defensively so that a caller passing an immutable map stays mutable here. */
    public void setTags(Map<String, String> v) { this.tags = v == null ? new HashMap<>() : new HashMap<>(v); }

    public void putTags(Map<String, String> additional) {
        if (additional != null) {
            tags.putAll(additional);
        }
    }

    public void removeTags(Collection<String> tagKeys) {
        if (tagKeys != null) {
            tagKeys.forEach(tags::remove);
        }
    }
}
