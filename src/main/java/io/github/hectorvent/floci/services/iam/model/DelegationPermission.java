package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * The permissions a delegation request asks for, the {@code DelegationPermission} shape.
 *
 * <p>{@code Permissions} is required on {@code CreateDelegationRequest} while both of its members
 * are optional, so a request may ask for a template, for parameters, or for neither. The template
 * itself is registered out of band: AWS onboards a partner by email and hands back the ARN, with no
 * API that creates one, so Floci resolves nothing here and keeps the ARN as the caller gave it.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DelegationPermission {

    /** {@code delegationPolicyParameterListType} caps the list at 50. */
    public static final int MAX_PARAMETERS = 50;

    private String policyTemplateArn;
    private List<PolicyParameter> parameters = new ArrayList<>();

    public DelegationPermission() {}

    public DelegationPermission(String policyTemplateArn, List<PolicyParameter> parameters) {
        this.policyTemplateArn = policyTemplateArn;
        setParameters(parameters);
    }

    public String getPolicyTemplateArn() { return policyTemplateArn; }
    public void setPolicyTemplateArn(String policyTemplateArn) { this.policyTemplateArn = policyTemplateArn; }

    public List<PolicyParameter> getParameters() { return parameters; }

    public void setParameters(List<PolicyParameter> parameters) {
        this.parameters = parameters == null ? new ArrayList<>() : new ArrayList<>(parameters);
    }
}
