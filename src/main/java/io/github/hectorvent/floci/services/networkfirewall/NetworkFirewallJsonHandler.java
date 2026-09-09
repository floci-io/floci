package io.github.hectorvent.floci.services.networkfirewall;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class NetworkFirewallJsonHandler {

    private final NetworkFirewallService service;

    @Inject
    public NetworkFirewallJsonHandler(NetworkFirewallService service) {
        this.service = service;
    }

    public Response handle(String action, JsonNode request, String region, String accountId) {
        return switch (action) {
            case "CreateRuleGroup" -> ok(service.createRuleGroup(request, region, accountId));
            case "DescribeRuleGroup" -> ok(service.describeRuleGroup(
                    textOrNull(request, "RuleGroupArn"), textOrNull(request, "RuleGroupName")));
            case "UpdateRuleGroup" -> ok(service.updateRuleGroup(request, region, accountId));
            case "DeleteRuleGroup" -> ok(service.deleteRuleGroup(
                    textOrNull(request, "RuleGroupArn"), textOrNull(request, "RuleGroupName")));
            case "ListRuleGroups" -> ok(service.listRuleGroups(textOrNull(request, "Type")));
            case "CreateFirewallPolicy" -> ok(service.createFirewallPolicy(request, region, accountId));
            case "DescribeFirewallPolicy" -> ok(service.describeFirewallPolicy(
                    textOrNull(request, "FirewallPolicyArn"), textOrNull(request, "FirewallPolicyName")));
            case "UpdateFirewallPolicy" -> ok(service.updateFirewallPolicy(request, region, accountId));
            case "DeleteFirewallPolicy" -> ok(service.deleteFirewallPolicy(
                    textOrNull(request, "FirewallPolicyArn"), textOrNull(request, "FirewallPolicyName")));
            case "ListFirewallPolicies" -> ok(service.listFirewallPolicies());
            case "CreateFirewall" -> ok(service.createFirewall(request, region, accountId));
            case "DescribeFirewall" -> Response.ok(service.describeFirewall(
                    textOrNull(request, "FirewallArn"),
                    textOrNull(request, "FirewallName"),
                    region,
                    accountId)).build();
            case "DeleteFirewall" -> ok(service.deleteFirewall(
                    textOrNull(request, "FirewallArn"), textOrNull(request, "FirewallName"), region));
            case "ListFirewalls" -> ok(service.listFirewalls(request));
            case "UpdateFirewallDeleteProtection", "UpdateFirewallPolicyChangeProtection",
                 "UpdateSubnetChangeProtection", "UpdateAvailabilityZoneChangeProtection",
                 "UpdateFirewallDescription", "UpdateFirewallAnalysisSettings"
                    -> ok(service.updateFirewall(action, request, region, accountId));
            case "AssociateSubnets" -> ok(service.associateSubnets(request));
            case "DisassociateSubnets" -> ok(service.disassociateSubnets(request));
            case "AssociateAvailabilityZones" -> ok(service.associateAvailabilityZones(request));
            case "DisassociateAvailabilityZones" -> ok(service.disassociateAvailabilityZones(request));
            case "UpdateLoggingConfiguration" -> ok(service.putLoggingConfiguration(request));
            case "DescribeLoggingConfiguration" -> ok(service.describeLoggingConfiguration(
                    textOrNull(request, "FirewallArn"), textOrNull(request, "FirewallName")));
            case "AssociateFirewallPolicy" -> ok(service.associateFirewallPolicy(request, region, accountId));
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: NetworkFirewall_20201112." + action))
                    .build();
        };
    }

    private static Response ok(JsonNode entity) {
        return Response.ok(entity).build();
    }

    private static String textOrNull(JsonNode request, String field) {
        JsonNode value = request == null ? null : request.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
