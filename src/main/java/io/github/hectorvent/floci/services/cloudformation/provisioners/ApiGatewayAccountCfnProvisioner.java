package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::ApiGateway::Account}. The resource carries a single
 * account-level setting, CloudWatchRoleArn, which lands in {@link ApiGatewayService}'s account
 * store — the same state {@code GET /account} reads — so a template that sets the logging role is
 * observable afterwards instead of being recorded as a bare physical id.
 */
@ApplicationScoped
public class ApiGatewayAccountCfnProvisioner implements CfnResourceProvisioner {

    private final ApiGatewayService apiGatewayService;

    @Inject
    public ApiGatewayAccountCfnProvisioner(ApiGatewayService apiGatewayService) {
        this.apiGatewayService = apiGatewayService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::ApiGateway::Account");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String roleArn = ctx.resolveOptional(props, "CloudWatchRoleArn");
        if (roleArn != null && !roleArn.isBlank()) {
            apiGatewayService.updateAccount(ctx.region(), List.of(Map.of(
                    "op", "replace",
                    "path", "/cloudwatchRoleArn",
                    "value", roleArn)));
        }
        // The account is a singleton per region, so there is no generated id to hand back.
        r.setPhysicalId(r.getLogicalId());
        r.getAttributes().put("Id", r.getLogicalId());
    }

    // No delete: API Gateway has no DeleteAccount operation, and the account-level settings are
    // regional rather than stack-owned, so they survive DeleteStack exactly as in AWS.
}
