package io.floci.aws.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * An instance's {@code Operator}: whether a service provider manages it, which one, and whether EC2 hides it from
 * list and filter calls by default, as it does an EKS Auto Mode node.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class InstanceOperator {

    private boolean managed;
    private String principal;
    private boolean hiddenByDefault;

    public InstanceOperator() {}

    public InstanceOperator(boolean managed, String principal, boolean hiddenByDefault) {
        this.managed = managed;
        this.principal = principal;
        this.hiddenByDefault = hiddenByDefault;
    }

    public boolean isManaged() { return managed; }
    public void setManaged(boolean managed) { this.managed = managed; }

    public String getPrincipal() { return principal; }
    public void setPrincipal(String principal) { this.principal = principal; }

    public boolean isHiddenByDefault() { return hiddenByDefault; }
    public void setHiddenByDefault(boolean hiddenByDefault) { this.hiddenByDefault = hiddenByDefault; }
}
