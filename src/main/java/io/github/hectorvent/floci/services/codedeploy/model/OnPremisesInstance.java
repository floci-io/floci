package io.github.hectorvent.floci.services.codedeploy.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OnPremisesInstance {

    private String instanceName;
    private String instanceArn;
    private String iamSessionArn;
    private String iamUserArn;
    private Double registerTime;
    private Double deregisterTime;
    private List<Map<String, String>> tags = new ArrayList<>();
    private String registrationStatus;

    public OnPremisesInstance() {}

    public String getInstanceName() { return instanceName; }
    public void setInstanceName(String instanceName) { this.instanceName = instanceName; }

    public String getInstanceArn() { return instanceArn; }
    public void setInstanceArn(String instanceArn) { this.instanceArn = instanceArn; }

    public String getIamSessionArn() { return iamSessionArn; }
    public void setIamSessionArn(String iamSessionArn) { this.iamSessionArn = iamSessionArn; }

    public String getIamUserArn() { return iamUserArn; }
    public void setIamUserArn(String iamUserArn) { this.iamUserArn = iamUserArn; }

    public Double getRegisterTime() { return registerTime; }
    public void setRegisterTime(Double registerTime) { this.registerTime = registerTime; }

    public Double getDeregisterTime() { return deregisterTime; }
    public void setDeregisterTime(Double deregisterTime) { this.deregisterTime = deregisterTime; }

    public List<Map<String, String>> getTags() { return tags; }
    public void setTags(List<Map<String, String>> tags) { this.tags = tags; }

    public String getRegistrationStatus() { return registrationStatus; }
    public void setRegistrationStatus(String registrationStatus) { this.registrationStatus = registrationStatus; }
}
