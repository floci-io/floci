package io.github.hectorvent.floci.services.appsync.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiAssociation {
    private String apiId;
    private String associationStatus;
    private String deploymentDetail;
    private String domainName;

    public String getApiId() { return apiId; }
    public void setApiId(String apiId) { this.apiId = apiId; }

    public String getAssociationStatus() { return associationStatus; }
    public void setAssociationStatus(String associationStatus) { this.associationStatus = associationStatus; }

    public String getDeploymentDetail() { return deploymentDetail; }
    public void setDeploymentDetail(String deploymentDetail) { this.deploymentDetail = deploymentDetail; }

    public String getDomainName() { return domainName; }
    public void setDomainName(String domainName) { this.domainName = domainName; }
}
