package io.floci.aws.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An RDS blue/green deployment: a production DB instance (blue), a staged copy of it (green) and
 * the switchover that makes the green one production. Source and Target are the two instances'
 * ARNs, which follow the renames a switchover makes.
 */
@RegisterForReflection
public class BlueGreenDeployment {

    public static final String PROVISIONING = "PROVISIONING";
    public static final String AVAILABLE = "AVAILABLE";
    public static final String SWITCHOVER_IN_PROGRESS = "SWITCHOVER_IN_PROGRESS";
    public static final String SWITCHOVER_COMPLETED = "SWITCHOVER_COMPLETED";
    public static final String INVALID_CONFIGURATION = "INVALID_CONFIGURATION";
    public static final String DELETING = "DELETING";

    private String blueGreenDeploymentIdentifier;
    private String blueGreenDeploymentName;
    private String blueGreenDeploymentArn;
    private String source;
    private String target;
    private String status;
    private String statusDetails;
    private List<SwitchoverDetail> switchoverDetails = new ArrayList<>();
    private List<Task> tasks = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();
    private Instant createTime;
    private Instant deleteTime;

    public BlueGreenDeployment() {}

    public String getBlueGreenDeploymentIdentifier() { return blueGreenDeploymentIdentifier; }
    public void setBlueGreenDeploymentIdentifier(String id) { this.blueGreenDeploymentIdentifier = id; }

    public String getBlueGreenDeploymentName() { return blueGreenDeploymentName; }
    public void setBlueGreenDeploymentName(String name) { this.blueGreenDeploymentName = name; }

    public String getBlueGreenDeploymentArn() { return blueGreenDeploymentArn; }
    public void setBlueGreenDeploymentArn(String arn) { this.blueGreenDeploymentArn = arn; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStatusDetails() { return statusDetails; }
    public void setStatusDetails(String statusDetails) { this.statusDetails = statusDetails; }

    public List<SwitchoverDetail> getSwitchoverDetails() { return switchoverDetails; }
    public void setSwitchoverDetails(List<SwitchoverDetail> switchoverDetails) {
        this.switchoverDetails = switchoverDetails != null ? new ArrayList<>(switchoverDetails) : new ArrayList<>();
    }

    public List<Task> getTasks() { return tasks; }
    public void setTasks(List<Task> tasks) {
        this.tasks = tasks != null ? new ArrayList<>(tasks) : new ArrayList<>();
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>();
    }

    public Instant getCreateTime() { return createTime; }
    public void setCreateTime(Instant createTime) { this.createTime = createTime; }

    public Instant getDeleteTime() { return deleteTime; }
    public void setDeleteTime(Instant deleteTime) { this.deleteTime = deleteTime; }

    /** One blue resource, the green resource paired with it, and where that pair stands. */
    @RegisterForReflection
    public static class SwitchoverDetail {
        private String sourceMember;
        private String targetMember;
        private String status;

        public SwitchoverDetail() {}

        public SwitchoverDetail(String sourceMember, String targetMember, String status) {
            this.sourceMember = sourceMember;
            this.targetMember = targetMember;
            this.status = status;
        }

        public String getSourceMember() { return sourceMember; }
        public void setSourceMember(String sourceMember) { this.sourceMember = sourceMember; }

        public String getTargetMember() { return targetMember; }
        public void setTargetMember(String targetMember) { this.targetMember = targetMember; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    /** A step RDS ran on the green environment, such as creating the replica or upgrading it. */
    @RegisterForReflection
    public static class Task {
        private String name;
        private String status;

        public Task() {}

        public Task(String name, String status) {
            this.name = name;
            this.status = status;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }
}
