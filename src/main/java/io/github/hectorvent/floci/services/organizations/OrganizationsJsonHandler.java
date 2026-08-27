package io.github.hectorvent.floci.services.organizations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.services.organizations.model.CreateAccountStatus;
import io.github.hectorvent.floci.services.organizations.model.Handshake;
import io.github.hectorvent.floci.services.organizations.model.HandshakeParty;
import io.github.hectorvent.floci.services.organizations.model.HandshakeResource;
import io.github.hectorvent.floci.services.organizations.model.Organization;
import io.github.hectorvent.floci.services.organizations.model.OrganizationAccount;
import io.github.hectorvent.floci.services.organizations.model.OrganizationPolicy;
import io.github.hectorvent.floci.services.organizations.model.OrganizationalUnit;
import io.github.hectorvent.floci.services.organizations.model.PolicyTypeSummary;
import io.github.hectorvent.floci.services.organizations.model.Root;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * AWS Organizations JSON 1.1 handler. Dispatched from
 * {@link io.github.hectorvent.floci.core.common.AwsJson11Controller} under the
 * {@code AWSOrganizationsV20161128.} target prefix.
 *
 * <p>The handler is deliberately thin: it parses the request, delegates to
 * {@link OrganizationsService}, and renders the AWS response shape. All validation and
 * authorization lives in the service.
 */
@ApplicationScoped
public class OrganizationsJsonHandler {

    private static final Logger LOG = Logger.getLogger(OrganizationsJsonHandler.class);

    /** AWS caps MaxResults at 20 across the Organizations list operations. */
    private static final int MAX_PAGE = 20;

    private static final String PAGINATION_ERROR = "InvalidInputException";

    private final OrganizationsService service;
    private final ObjectMapper objectMapper;

    @Inject
    public OrganizationsJsonHandler(OrganizationsService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String callerAccountId) {
        LOG.debugv("Organizations action: {0}", action);
        try {
            return switch (action) {
                case "CreateOrganization" -> createOrganization(request, callerAccountId);
                case "DescribeOrganization" -> describeOrganization(callerAccountId);
                case "DeleteOrganization" -> deleteOrganization(callerAccountId);
                case "EnableAllFeatures" -> enableAllFeatures(callerAccountId);
                case "ListRoots" -> listRoots(request, callerAccountId);
                case "CreateOrganizationalUnit" -> createOrganizationalUnit(request, callerAccountId);
                case "UpdateOrganizationalUnit" -> updateOrganizationalUnit(request, callerAccountId);
                case "DeleteOrganizationalUnit" -> deleteOrganizationalUnit(request, callerAccountId);
                case "DescribeOrganizationalUnit" -> describeOrganizationalUnit(request, callerAccountId);
                case "ListOrganizationalUnitsForParent" -> listOrganizationalUnitsForParent(request, callerAccountId);
                case "ListParents" -> listParents(request, callerAccountId);
                case "ListChildren" -> listChildren(request, callerAccountId);
                case "CreateAccount" -> createAccount(request, callerAccountId, false);
                case "CreateGovCloudAccount" -> createAccount(request, callerAccountId, true);
                case "DescribeCreateAccountStatus" -> describeCreateAccountStatus(request, callerAccountId);
                case "ListCreateAccountStatus" -> listCreateAccountStatus(request, callerAccountId);
                case "DescribeAccount" -> describeAccount(request, callerAccountId);
                case "ListAccounts" -> listAccounts(request, callerAccountId);
                case "ListAccountsForParent" -> listAccountsForParent(request, callerAccountId);
                case "ListAccountsWithInvalidEffectivePolicy" ->
                        listAccountsWithInvalidEffectivePolicy(request, callerAccountId);
                case "MoveAccount" -> moveAccount(request, callerAccountId);
                case "RemoveAccountFromOrganization" -> removeAccountFromOrganization(request, callerAccountId);
                case "LeaveOrganization" -> leaveOrganization(callerAccountId);
                case "CloseAccount" -> closeAccount(request, callerAccountId);
                case "CreatePolicy" -> createPolicy(request, callerAccountId);
                case "UpdatePolicy" -> updatePolicy(request, callerAccountId);
                case "DeletePolicy" -> deletePolicy(request, callerAccountId);
                case "DescribePolicy" -> describePolicy(request, callerAccountId);
                case "ListPolicies" -> listPolicies(request, callerAccountId);
                case "AttachPolicy" -> attachPolicy(request, callerAccountId);
                case "DetachPolicy" -> detachPolicy(request, callerAccountId);
                case "ListPoliciesForTarget" -> listPoliciesForTarget(request, callerAccountId);
                case "ListTargetsForPolicy" -> listTargetsForPolicy(request, callerAccountId);
                case "EnablePolicyType" -> enablePolicyType(request, callerAccountId);
                case "DisablePolicyType" -> disablePolicyType(request, callerAccountId);
                case "DescribeEffectivePolicy" -> describeEffectivePolicy(request, callerAccountId);
                case "TagResource" -> tagResource(request, callerAccountId);
                case "UntagResource" -> untagResource(request, callerAccountId);
                case "ListTagsForResource" -> listTagsForResource(request, callerAccountId);
                case "EnableAWSServiceAccess" -> enableAwsServiceAccess(request, callerAccountId);
                case "DisableAWSServiceAccess" -> disableAwsServiceAccess(request, callerAccountId);
                case "ListAWSServiceAccessForOrganization" ->
                        listAwsServiceAccessForOrganization(request, callerAccountId);
                case "RegisterDelegatedAdministrator" -> registerDelegatedAdministrator(request, callerAccountId);
                case "DeregisterDelegatedAdministrator" -> deregisterDelegatedAdministrator(request, callerAccountId);
                case "ListDelegatedAdministrators" -> listDelegatedAdministrators(request, callerAccountId);
                case "ListDelegatedServicesForAccount" -> listDelegatedServicesForAccount(request, callerAccountId);
                case "PutResourcePolicy" -> putResourcePolicy(request, callerAccountId);
                case "DescribeResourcePolicy" -> describeResourcePolicy(callerAccountId);
                case "DeleteResourcePolicy" -> deleteResourcePolicy(callerAccountId);
                case "InviteAccountToOrganization" -> inviteAccountToOrganization(request, callerAccountId);
                case "AcceptHandshake" -> acceptHandshake(request, callerAccountId);
                case "DeclineHandshake" -> declineHandshake(request, callerAccountId);
                case "CancelHandshake" -> cancelHandshake(request, callerAccountId);
                case "DescribeHandshake" -> describeHandshake(request, callerAccountId);
                case "ListHandshakesForAccount" -> listHandshakesForAccount(request, callerAccountId);
                case "ListHandshakesForOrganization" -> listHandshakesForOrganization(request, callerAccountId);
                default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(
                        "AWSOrganizationsV20161128." + action);
            };
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        } catch (Exception e) {
            LOG.errorf(e, "Organizations error processing action %s", action);
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }

    // ──────────────────────────── Organization lifecycle ────────────────────────────

    private Response createOrganization(JsonNode request, String caller) {
        Organization organization = service.createOrganization(caller, text(request, "FeatureSet"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Organization", organizationNode(organization));
        return Response.ok(response).build();
    }

    private Response describeOrganization(String caller) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Organization", organizationNode(service.describeOrganization(caller)));
        return Response.ok(response).build();
    }

    private Response deleteOrganization(String caller) {
        service.deleteOrganization(caller);
        return empty();
    }

    private Response enableAllFeatures(String caller) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Handshake", handshakeNode(service.enableAllFeatures(caller)));
        return Response.ok(response).build();
    }

    private Response listRoots(JsonNode request, String caller) {
        PaginatedResult<Root> page = page(service.listRoots(caller), Root::getId, request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode roots = response.putArray("Roots");
        page.items().forEach(root -> roots.add(rootNode(root)));
        putNextToken(response, page.nextToken());
        return Response.ok(response).build();
    }

    // ──────────────────────────── Organizational units ────────────────────────────

    private Response createOrganizationalUnit(JsonNode request, String caller) {
        OrganizationalUnit unit = service.createOrganizationalUnit(caller,
                text(request, "ParentId"), text(request, "Name"), parseTags(request.path("Tags")));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("OrganizationalUnit", organizationalUnitNode(unit));
        return Response.ok(response).build();
    }

    private Response updateOrganizationalUnit(JsonNode request, String caller) {
        OrganizationalUnit unit = service.updateOrganizationalUnit(caller,
                text(request, "OrganizationalUnitId"), text(request, "Name"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("OrganizationalUnit", organizationalUnitNode(unit));
        return Response.ok(response).build();
    }

    private Response deleteOrganizationalUnit(JsonNode request, String caller) {
        service.deleteOrganizationalUnit(caller, text(request, "OrganizationalUnitId"));
        return empty();
    }

    private Response describeOrganizationalUnit(JsonNode request, String caller) {
        OrganizationalUnit unit = service.describeOrganizationalUnit(caller, text(request, "OrganizationalUnitId"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("OrganizationalUnit", organizationalUnitNode(unit));
        return Response.ok(response).build();
    }

    private Response listOrganizationalUnitsForParent(JsonNode request, String caller) {
        List<OrganizationalUnit> all = service.listOrganizationalUnitsForParent(caller, text(request, "ParentId"));
        PaginatedResult<OrganizationalUnit> page = page(all, OrganizationalUnit::getId, request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode units = response.putArray("OrganizationalUnits");
        page.items().forEach(unit -> units.add(organizationalUnitNode(unit)));
        putNextToken(response, page.nextToken());
        return Response.ok(response).build();
    }

    private Response listParents(JsonNode request, String caller) {
        List<OrganizationsService.ParentRef> all = service.listParents(caller, text(request, "ChildId"));
        PaginatedResult<OrganizationsService.ParentRef> page =
                page(all, OrganizationsService.ParentRef::id, request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode parents = response.putArray("Parents");
        page.items().forEach(parent -> {
            ObjectNode node = parents.addObject();
            node.put("Id", parent.id());
            node.put("Type", parent.type());
        });
        putNextToken(response, page.nextToken());
        return Response.ok(response).build();
    }

    private Response listChildren(JsonNode request, String caller) {
        List<OrganizationsService.ChildRef> all =
                service.listChildren(caller, text(request, "ParentId"), text(request, "ChildType"));
        PaginatedResult<OrganizationsService.ChildRef> page = page(all, OrganizationsService.ChildRef::id, request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode children = response.putArray("Children");
        page.items().forEach(child -> {
            ObjectNode node = children.addObject();
            node.put("Id", child.id());
            node.put("Type", child.type());
        });
        putNextToken(response, page.nextToken());
        return Response.ok(response).build();
    }

    // ──────────────────────────── Accounts ────────────────────────────

    private Response createAccount(JsonNode request, String caller, boolean govCloud) {
        CreateAccountStatus status = service.createAccount(caller,
                text(request, "Email"), text(request, "AccountName"), parseTags(request.path("Tags")), govCloud);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("CreateAccountStatus", createAccountStatusNode(status));
        return Response.ok(response).build();
    }

    private Response describeCreateAccountStatus(JsonNode request, String caller) {
        CreateAccountStatus status =
                service.describeCreateAccountStatus(caller, text(request, "CreateAccountRequestId"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("CreateAccountStatus", createAccountStatusNode(status));
        return Response.ok(response).build();
    }

    private Response listCreateAccountStatus(JsonNode request, String caller) {
        List<CreateAccountStatus> all = service.listCreateAccountStatus(caller, parseStringList(request.path("States")));
        PaginatedResult<CreateAccountStatus> page = page(all, CreateAccountStatus::getId, request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode statuses = response.putArray("CreateAccountStatuses");
        page.items().forEach(status -> statuses.add(createAccountStatusNode(status)));
        putNextToken(response, page.nextToken());
        return Response.ok(response).build();
    }

    private Response describeAccount(JsonNode request, String caller) {
        OrganizationAccount account = service.describeAccount(caller, text(request, "AccountId"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Account", accountNode(account));
        return Response.ok(response).build();
    }

    private Response listAccounts(JsonNode request, String caller) {
        PaginatedResult<OrganizationAccount> page =
                page(service.listAccounts(caller), OrganizationAccount::getId, request);
        return accountsResponse(page);
    }

    private Response listAccountsForParent(JsonNode request, String caller) {
        List<OrganizationAccount> all = service.listAccountsForParent(caller, text(request, "ParentId"));
        return accountsResponse(page(all, OrganizationAccount::getId, request));
    }

    /**
     * Effective-policy validation is not modeled in Floci; there is no way to evaluate
     * which accounts have invalid effective policies. Return an explicit empty list to
     * match the wire contract without fabricating data — but only once the required
     * PolicyType has been checked against the enum, so a bogus type is not answered with
     * a reassuring "no invalid accounts".
     */
    private Response listAccountsWithInvalidEffectivePolicy(JsonNode request, String caller) {
        String policyType = text(request, "PolicyType");
        List<OrganizationAccount> accounts =
                service.listAccountsWithInvalidEffectivePolicy(caller, policyType);
        PaginatedResult<OrganizationAccount> page = page(accounts, OrganizationAccount::getId, request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode accountsNode = response.putArray("Accounts");
        page.items().forEach(account -> accountsNode.add(accountNode(account)));
        putNextToken(response, page.nextToken());
        // PolicyType is the only field this operation's response can carry any
        // information in - Accounts is always empty by design (see javadoc above) - so
        // echoing the validated request value back is required, not cosmetic.
        response.put("PolicyType", policyType);
        return Response.ok(response).build();
    }

    private Response accountsResponse(PaginatedResult<OrganizationAccount> page) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode accounts = response.putArray("Accounts");
        page.items().forEach(account -> accounts.add(accountNode(account)));
        putNextToken(response, page.nextToken());
        return Response.ok(response).build();
    }

    private Response moveAccount(JsonNode request, String caller) {
        service.moveAccount(caller, text(request, "AccountId"),
                text(request, "SourceParentId"), text(request, "DestinationParentId"));
        return empty();
    }

    private Response removeAccountFromOrganization(JsonNode request, String caller) {
        service.removeAccountFromOrganization(caller, text(request, "AccountId"));
        return empty();
    }

    private Response leaveOrganization(String caller) {
        service.leaveOrganization(caller);
        return empty();
    }

    private Response closeAccount(JsonNode request, String caller) {
        service.closeAccount(caller, text(request, "AccountId"));
        return empty();
    }

    // ──────────────────────────── Policies ────────────────────────────

    private Response createPolicy(JsonNode request, String caller) {
        OrganizationPolicy policy = service.createPolicy(caller,
                text(request, "Content"), text(request, "Description"), text(request, "Name"),
                text(request, "Type"), parseTags(request.path("Tags")));
        return policyResponse(policy);
    }

    private Response updatePolicy(JsonNode request, String caller) {
        OrganizationPolicy policy = service.updatePolicy(caller, text(request, "PolicyId"),
                text(request, "Name"), text(request, "Description"), text(request, "Content"));
        return policyResponse(policy);
    }

    private Response deletePolicy(JsonNode request, String caller) {
        service.deletePolicy(caller, text(request, "PolicyId"));
        return empty();
    }

    private Response describePolicy(JsonNode request, String caller) {
        return policyResponse(service.describePolicy(caller, text(request, "PolicyId")));
    }

    private Response policyResponse(OrganizationPolicy policy) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode node = response.putObject("Policy");
        node.set("PolicySummary", policySummaryNode(policy));
        node.put("Content", policy.getContent());
        return Response.ok(response).build();
    }

    private Response listPolicies(JsonNode request, String caller) {
        List<OrganizationPolicy> all = service.listPolicies(caller, text(request, "Filter"));
        return policiesResponse(page(all, OrganizationPolicy::getId, request));
    }

    private Response listPoliciesForTarget(JsonNode request, String caller) {
        List<OrganizationPolicy> all =
                service.listPoliciesForTarget(caller, text(request, "TargetId"), text(request, "Filter"));
        return policiesResponse(page(all, OrganizationPolicy::getId, request));
    }

    private Response policiesResponse(PaginatedResult<OrganizationPolicy> page) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode policies = response.putArray("Policies");
        page.items().forEach(policy -> policies.add(policySummaryNode(policy)));
        putNextToken(response, page.nextToken());
        return Response.ok(response).build();
    }

    private Response attachPolicy(JsonNode request, String caller) {
        service.attachPolicy(caller, text(request, "PolicyId"), text(request, "TargetId"));
        return empty();
    }

    private Response detachPolicy(JsonNode request, String caller) {
        service.detachPolicy(caller, text(request, "PolicyId"), text(request, "TargetId"));
        return empty();
    }

    private Response listTargetsForPolicy(JsonNode request, String caller) {
        List<OrganizationsService.PolicyTarget> all =
                service.listTargetsForPolicy(caller, text(request, "PolicyId"));
        PaginatedResult<OrganizationsService.PolicyTarget> page =
                page(all, OrganizationsService.PolicyTarget::targetId, request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode targets = response.putArray("Targets");
        page.items().forEach(target -> {
            ObjectNode node = targets.addObject();
            node.put("TargetId", target.targetId());
            node.put("Arn", target.arn());
            node.put("Name", target.name());
            node.put("Type", target.type());
        });
        putNextToken(response, page.nextToken());
        return Response.ok(response).build();
    }

    private Response enablePolicyType(JsonNode request, String caller) {
        Root root = service.enablePolicyType(caller, text(request, "RootId"), text(request, "PolicyType"));
        return rootResponse(root);
    }

    private Response disablePolicyType(JsonNode request, String caller) {
        Root root = service.disablePolicyType(caller, text(request, "RootId"), text(request, "PolicyType"));
        return rootResponse(root);
    }

    private Response rootResponse(Root root) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Root", rootNode(root));
        return Response.ok(response).build();
    }

    private Response describeEffectivePolicy(JsonNode request, String caller) {
        OrganizationsService.EffectivePolicy effective =
                service.describeEffectivePolicy(caller, text(request, "PolicyType"), text(request, "TargetId"));
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode node = response.putObject("EffectivePolicy");
        node.put("PolicyContent", effective.policyContent());
        node.put("PolicyType", effective.policyType());
        node.put("TargetId", effective.targetId());
        putTimestamp(node, "LastUpdatedTimestamp", effective.lastUpdatedTimestamp());
        return Response.ok(response).build();
    }

    // ──────────────────────────── Tags ────────────────────────────

    private Response tagResource(JsonNode request, String caller) {
        service.tagResource(caller, text(request, "ResourceId"), parseTags(request.path("Tags")));
        return empty();
    }

    private Response untagResource(JsonNode request, String caller) {
        service.untagResource(caller, text(request, "ResourceId"), parseStringList(request.path("TagKeys")));
        return empty();
    }

    private Response listTagsForResource(JsonNode request, String caller) {
        Map<String, String> tags = service.listTagsForResource(caller, text(request, "ResourceId"));
        List<Map.Entry<String, String>> entries = new ArrayList<>(tags.entrySet());
        PaginatedResult<Map.Entry<String, String>> page = page(entries, Map.Entry::getKey, request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("Tags");
        page.items().forEach(entry -> {
            ObjectNode node = array.addObject();
            node.put("Key", entry.getKey());
            node.put("Value", entry.getValue());
        });
        putNextToken(response, page.nextToken());
        return Response.ok(response).build();
    }

    // ──────────────────────────── Trusted access and delegation ────────────────────────────

    private Response enableAwsServiceAccess(JsonNode request, String caller) {
        service.enableAWSServiceAccess(caller, text(request, "ServicePrincipal"));
        return empty();
    }

    private Response disableAwsServiceAccess(JsonNode request, String caller) {
        service.disableAWSServiceAccess(caller, text(request, "ServicePrincipal"));
        return empty();
    }

    private Response listAwsServiceAccessForOrganization(JsonNode request, String caller) {
        List<OrganizationsService.EnabledServicePrincipal> all =
                service.listAWSServiceAccessForOrganization(caller);
        PaginatedResult<OrganizationsService.EnabledServicePrincipal> page =
                page(all, OrganizationsService.EnabledServicePrincipal::servicePrincipal, request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode principals = response.putArray("EnabledServicePrincipals");
        page.items().forEach(principal -> {
            ObjectNode node = principals.addObject();
            node.put("ServicePrincipal", principal.servicePrincipal());
            putTimestamp(node, "DateEnabled", principal.dateEnabled());
        });
        putNextToken(response, page.nextToken());
        return Response.ok(response).build();
    }

    private Response registerDelegatedAdministrator(JsonNode request, String caller) {
        service.registerDelegatedAdministrator(caller, text(request, "AccountId"), text(request, "ServicePrincipal"));
        return empty();
    }

    private Response deregisterDelegatedAdministrator(JsonNode request, String caller) {
        service.deregisterDelegatedAdministrator(caller, text(request, "AccountId"), text(request, "ServicePrincipal"));
        return empty();
    }

    private Response listDelegatedAdministrators(JsonNode request, String caller) {
        String servicePrincipal = text(request, "ServicePrincipal");
        List<OrganizationAccount> all = service.listDelegatedAdministrators(caller, servicePrincipal);
        PaginatedResult<OrganizationAccount> page = page(all, OrganizationAccount::getId, request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode administrators = response.putArray("DelegatedAdministrators");
        page.items().forEach(account -> {
            ObjectNode node = accountNode(account);
            Instant enabled = servicePrincipal != null
                    ? account.getDelegatedServices().get(servicePrincipal)
                    : account.getDelegatedServices().values().stream().findFirst().orElse(null);
            putTimestamp(node, "DelegationEnabledDate", enabled);
            administrators.add(node);
        });
        putNextToken(response, page.nextToken());
        return Response.ok(response).build();
    }

    private Response listDelegatedServicesForAccount(JsonNode request, String caller) {
        List<OrganizationsService.DelegatedService> all =
                service.listDelegatedServicesForAccount(caller, text(request, "AccountId"));
        PaginatedResult<OrganizationsService.DelegatedService> page =
                page(all, OrganizationsService.DelegatedService::servicePrincipal, request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode services = response.putArray("DelegatedServices");
        page.items().forEach(delegated -> {
            ObjectNode node = services.addObject();
            node.put("ServicePrincipal", delegated.servicePrincipal());
            putTimestamp(node, "DelegationEnabledDate", delegated.delegationEnabledDate());
        });
        putNextToken(response, page.nextToken());
        return Response.ok(response).build();
    }

    // ──────────────────────────── Resource policy ────────────────────────────

    private Response putResourcePolicy(JsonNode request, String caller) {
        OrganizationsService.ResourcePolicyView policy =
                service.putResourcePolicy(caller, text(request, "Content"), parseTags(request.path("Tags")));
        return resourcePolicyResponse(policy);
    }

    private Response describeResourcePolicy(String caller) {
        return resourcePolicyResponse(service.describeResourcePolicy(caller));
    }

    private Response deleteResourcePolicy(String caller) {
        service.deleteResourcePolicy(caller);
        return empty();
    }

    private Response resourcePolicyResponse(OrganizationsService.ResourcePolicyView policy) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode node = response.putObject("ResourcePolicy");
        ObjectNode summary = node.putObject("ResourcePolicySummary");
        summary.put("Id", policy.id());
        summary.put("Arn", policy.arn());
        node.put("Content", policy.content());
        return Response.ok(response).build();
    }

    // ──────────────────────────── Handshakes ────────────────────────────

    private Response inviteAccountToOrganization(JsonNode request, String caller) {
        JsonNode target = request.path("Target");
        Handshake handshake = service.inviteAccountToOrganization(caller,
                text(target, "Id"), text(target, "Type"), text(request, "Notes"));
        return handshakeResponse(handshake);
    }

    private Response acceptHandshake(JsonNode request, String caller) {
        return handshakeResponse(service.acceptHandshake(caller, text(request, "HandshakeId")));
    }

    private Response declineHandshake(JsonNode request, String caller) {
        return handshakeResponse(service.declineHandshake(caller, text(request, "HandshakeId")));
    }

    private Response cancelHandshake(JsonNode request, String caller) {
        return handshakeResponse(service.cancelHandshake(caller, text(request, "HandshakeId")));
    }

    private Response describeHandshake(JsonNode request, String caller) {
        return handshakeResponse(service.describeHandshake(caller, text(request, "HandshakeId")));
    }

    private Response handshakeResponse(Handshake handshake) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Handshake", handshakeNode(handshake));
        return Response.ok(response).build();
    }

    private Response listHandshakesForAccount(JsonNode request, String caller) {
        JsonNode filter = request.path("Filter");
        List<Handshake> all = service.listHandshakesForAccount(caller,
                parseStringList(filter.path("States")), text(filter, "ActionType"));
        return handshakesResponse(page(all, Handshake::getId, request));
    }

    private Response listHandshakesForOrganization(JsonNode request, String caller) {
        JsonNode filter = request.path("Filter");
        List<Handshake> all = service.listHandshakesForOrganization(caller,
                parseStringList(filter.path("States")), text(filter, "ActionType"));
        return handshakesResponse(page(all, Handshake::getId, request));
    }

    private Response handshakesResponse(PaginatedResult<Handshake> page) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode handshakes = response.putArray("Handshakes");
        page.items().forEach(handshake -> handshakes.add(handshakeNode(handshake)));
        putNextToken(response, page.nextToken());
        return Response.ok(response).build();
    }

    // ──────────────────────────── Response shapes ────────────────────────────

    private ObjectNode organizationNode(Organization organization) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", organization.getId());
        node.put("Arn", organization.getArn());
        node.put("FeatureSet", organization.getFeatureSet());
        node.put("MasterAccountArn", organization.getMasterAccountArn());
        node.put("MasterAccountId", organization.getMasterAccountId());
        node.put("MasterAccountEmail", organization.getMasterAccountEmail());
        ArrayNode policyTypes = node.putArray("AvailablePolicyTypes");
        organization.getRoot().getPolicyTypes().forEach(summary -> policyTypes.add(policyTypeNode(summary)));
        return node;
    }

    private ObjectNode rootNode(Root root) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", root.getId());
        node.put("Arn", root.getArn());
        node.put("Name", root.getName());
        ArrayNode policyTypes = node.putArray("PolicyTypes");
        root.getPolicyTypes().forEach(summary -> policyTypes.add(policyTypeNode(summary)));
        return node;
    }

    private ObjectNode policyTypeNode(PolicyTypeSummary summary) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Type", summary.getType());
        node.put("Status", summary.getStatus());
        return node;
    }

    private ObjectNode organizationalUnitNode(OrganizationalUnit unit) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", unit.getId());
        node.put("Arn", unit.getArn());
        node.put("Name", unit.getName());
        return node;
    }

    private ObjectNode accountNode(OrganizationAccount account) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", account.getId());
        node.put("Arn", account.getArn());
        node.put("Email", account.getEmail());
        node.put("Name", account.getName());
        node.put("Status", account.getStatus());
        node.put("JoinedMethod", account.getJoinedMethod());
        putTimestamp(node, "JoinedTimestamp", account.getJoinedTimestamp());
        return node;
    }

    private ObjectNode policySummaryNode(OrganizationPolicy policy) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", policy.getId());
        node.put("Arn", policy.getArn());
        node.put("Name", policy.getName());
        node.put("Description", policy.getDescription() == null ? "" : policy.getDescription());
        node.put("Type", policy.getType());
        node.put("AwsManaged", policy.isAwsManaged());
        return node;
    }

    private ObjectNode createAccountStatusNode(CreateAccountStatus status) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", status.getId());
        node.put("AccountName", status.getAccountName());
        node.put("State", status.getState());
        putTimestamp(node, "RequestedTimestamp", status.getRequestedTimestamp());
        putTimestamp(node, "CompletedTimestamp", status.getCompletedTimestamp());
        if (status.getAccountId() != null) {
            node.put("AccountId", status.getAccountId());
        }
        if (status.getGovCloudAccountId() != null) {
            node.put("GovCloudAccountId", status.getGovCloudAccountId());
        }
        if (status.getFailureReason() != null) {
            node.put("FailureReason", status.getFailureReason());
        }
        return node;
    }

    private ObjectNode handshakeNode(Handshake handshake) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", handshake.getId());
        node.put("Arn", handshake.getArn());
        node.put("State", handshake.getState());
        node.put("Action", handshake.getAction());
        putTimestamp(node, "RequestedTimestamp", handshake.getRequestedTimestamp());
        putTimestamp(node, "ExpirationTimestamp", handshake.getExpirationTimestamp());
        ArrayNode parties = node.putArray("Parties");
        for (HandshakeParty party : handshake.getParties()) {
            ObjectNode partyNode = parties.addObject();
            partyNode.put("Id", party.getId());
            partyNode.put("Type", party.getType());
        }
        ArrayNode resources = node.putArray("Resources");
        handshake.getResources().forEach(resource -> resources.add(handshakeResourceNode(resource)));
        return node;
    }

    private ObjectNode handshakeResourceNode(HandshakeResource resource) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Value", resource.getValue());
        node.put("Type", resource.getType());
        if (!resource.getResources().isEmpty()) {
            ArrayNode nested = node.putArray("Resources");
            resource.getResources().forEach(child -> nested.add(handshakeResourceNode(child)));
        }
        return node;
    }

    // ──────────────────────────── Parsing helpers ────────────────────────────

    private <T> PaginatedResult<T> page(List<T> all, Function<T, String> cursorOf, JsonNode request) {
        Integer maxResults = request.hasNonNull("MaxResults") ? request.get("MaxResults").asInt() : null;
        return Pagination.paginate(all, cursorOf, maxResults, text(request, "NextToken"), MAX_PAGE, PAGINATION_ERROR);
    }

    private void putNextToken(ObjectNode response, String nextToken) {
        if (nextToken != null) {
            response.put("NextToken", nextToken);
        }
    }

    private void putTimestamp(ObjectNode node, String field, Instant timestamp) {
        if (timestamp != null) {
            node.put(field, timestamp.toEpochMilli() / 1000.0);
        }
    }

    private Response empty() {
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    /** Organizations sends tags as a list of {@code {Key, Value}} objects, not a map. */
    private static Map<String, String> parseTags(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        Map<String, String> tags = new LinkedHashMap<>();
        node.forEach(entry -> tags.put(entry.path("Key").asText(), entry.path("Value").asText("")));
        return tags;
    }

    private static List<String> parseStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        node.forEach(entry -> values.add(entry.asText()));
        return values;
    }
}
