package io.github.hectorvent.floci.services.applicationautoscaling.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One recorded scaling action, returned by {@code DescribeScalingActivities}.
 *
 * @see <a href="https://docs.aws.amazon.com/autoscaling/application/APIReference/API_ScalingActivity.html">ScalingActivity</a>
 */
@RegisterForReflection
public class ScalingActivity {

    private String activityId;
    private String serviceNamespace;
    private String resourceId;
    private String scalableDimension;
    private String description;
    private String cause;
    private double startTime;
    private double endTime;
    private String statusCode;
    private String statusMessage;

    public String getActivityId() { return activityId; }
    public void setActivityId(String v) { this.activityId = v; }

    public String getServiceNamespace() { return serviceNamespace; }
    public void setServiceNamespace(String v) { this.serviceNamespace = v; }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String v) { this.resourceId = v; }

    public String getScalableDimension() { return scalableDimension; }
    public void setScalableDimension(String v) { this.scalableDimension = v; }

    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }

    public String getCause() { return cause; }
    public void setCause(String v) { this.cause = v; }

    public double getStartTime() { return startTime; }
    public void setStartTime(double v) { this.startTime = v; }

    public double getEndTime() { return endTime; }
    public void setEndTime(double v) { this.endTime = v; }

    public String getStatusCode() { return statusCode; }
    public void setStatusCode(String v) { this.statusCode = v; }

    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String v) { this.statusMessage = v; }
}
