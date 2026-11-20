package io.floci.aws.services.emrserverless.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/** One of ListSessions' sessions. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionSummary {
    private String applicationId;
    private String sessionId;
    private String arn;
    private String name;
    private String state;
    private String stateDetails;
    private String releaseLabel;
    private String executionRoleArn;
    private String createdBy;
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant createdAt;
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant updatedAt;

    public static SessionSummary of(Session session) {
        SessionSummary summary = new SessionSummary();
        summary.applicationId = session.getApplicationId();
        summary.sessionId = session.getSessionId();
        summary.arn = session.getArn();
        summary.name = session.getName();
        summary.state = session.getState();
        summary.stateDetails = session.getStateDetails();
        summary.releaseLabel = session.getReleaseLabel();
        summary.executionRoleArn = session.getExecutionRoleArn();
        summary.createdBy = session.getCreatedBy();
        summary.createdAt = session.getCreatedAt();
        summary.updatedAt = session.getUpdatedAt();
        return summary;
    }

    public String getApplicationId() { return applicationId; }
    public String getSessionId() { return sessionId; }
    public String getArn() { return arn; }
    public String getName() { return name; }
    public String getState() { return state; }
    public String getStateDetails() { return stateDetails; }
    public String getReleaseLabel() { return releaseLabel; }
    public String getExecutionRoleArn() { return executionRoleArn; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
