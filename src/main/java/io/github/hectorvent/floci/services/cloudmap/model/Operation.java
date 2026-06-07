package io.github.hectorvent.floci.services.cloudmap.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Async operation record. {@code type} is an OperationType (CREATE_NAMESPACE, …),
 * {@code status} an OperationStatus (SUBMITTED|PENDING|SUCCESS|FAIL). {@code targets}
 * maps an OperationTargetType (NAMESPACE|SERVICE|INSTANCE) to the affected resource id.
 */
@RegisterForReflection
public class Operation {

    private String id;
    private String type;
    private String status;
    private String errorMessage;
    private String errorCode;
    private Instant createDate;
    private Instant updateDate;
    private String region;
    private Map<String, String> targets = new HashMap<>();

    public Operation() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public Instant getCreateDate() { return createDate; }
    public void setCreateDate(Instant createDate) { this.createDate = createDate; }

    public Instant getUpdateDate() { return updateDate; }
    public void setUpdateDate(Instant updateDate) { this.updateDate = updateDate; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public Map<String, String> getTargets() { return targets; }
    public void setTargets(Map<String, String> targets) { this.targets = targets; }
}
