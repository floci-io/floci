package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class InstanceProfile {

    private String instanceProfileId;
    private String instanceProfileName;
    private String path;
    private String arn;
    private Instant createDate;
    private List<String> roleNames = new CopyOnWriteArrayList<>();

    public InstanceProfile() {}

    public InstanceProfile(String instanceProfileId, String instanceProfileName,
                           String path, String arn) {
        this.instanceProfileId = instanceProfileId;
        this.instanceProfileName = instanceProfileName;
        this.path = path;
        this.arn = arn;
        this.createDate = Instant.now();
    }

    public String getInstanceProfileId() { return instanceProfileId; }
    public void setInstanceProfileId(String instanceProfileId) { this.instanceProfileId = instanceProfileId; }

    public String getInstanceProfileName() { return instanceProfileName; }
    public void setInstanceProfileName(String instanceProfileName) { this.instanceProfileName = instanceProfileName; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public Instant getCreateDate() { return createDate; }
    public void setCreateDate(Instant createDate) { this.createDate = createDate; }

    public List<String> getRoleNames() { return roleNames; }
    public void setRoleNames(List<String> roleNames) {
        this.roleNames = new CopyOnWriteArrayList<>(roleNames);
    }
}
