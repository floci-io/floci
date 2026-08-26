package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import io.github.hectorvent.floci.services.organizations.model.CreateAccountStatus;
import io.github.hectorvent.floci.services.organizations.model.Organization;
import io.github.hectorvent.floci.services.organizations.model.OrganizationAccount;
import io.github.hectorvent.floci.services.organizations.model.OrganizationPolicy;
import io.github.hectorvent.floci.services.organizations.model.OrganizationalUnit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for AWS Organizations: {@code AWS::Organizations::Organization},
 * {@code OrganizationalUnit}, {@code Account}, {@code Policy} and {@code ResourcePolicy}.
 *
 * <p>Attribute names come from the registry schemas in
 * {@code aws-cloudformation-resource-providers-organizations} ({@code readOnlyProperties}), so
 * {@code Fn::GetAtt} resolves the same keys AWS publishes.
 */
@ApplicationScoped
public class OrganizationsCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(OrganizationsCfnProvisioner.class);

    private static final String ORGANIZATION = "AWS::Organizations::Organization";
    private static final String ORGANIZATIONAL_UNIT = "AWS::Organizations::OrganizationalUnit";
    private static final String ACCOUNT = "AWS::Organizations::Account";
    private static final String POLICY = "AWS::Organizations::Policy";
    private static final String RESOURCE_POLICY = "AWS::Organizations::ResourcePolicy";

    private final OrganizationsService organizationsService;

    @Inject
    public OrganizationsCfnProvisioner(OrganizationsService organizationsService) {
        this.organizationsService = organizationsService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(ORGANIZATION, ORGANIZATIONAL_UNIT, ACCOUNT, POLICY, RESOURCE_POLICY);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case ORGANIZATION -> provisionOrganization(r, props, ctx);
            case ORGANIZATIONAL_UNIT -> provisionOrganizationalUnit(r, props, ctx);
            case ACCOUNT -> provisionAccount(r, props, ctx);
            case POLICY -> provisionPolicy(r, props, ctx);
            case RESOURCE_POLICY -> provisionResourcePolicy(r, props, ctx);
            default -> throw new IllegalStateException(
                    "OrganizationsCfnProvisioner cannot handle " + r.getResourceType());
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        // The delete path carries no caller identity, so recover the owning management account
        // from the resource id. Nothing matching means the resource is already gone.
        String caller = organizationsService.findManagementAccountForResource(physicalId).orElse(null);
        if (caller == null) {
            LOG.debugv("No organization owns {0}; treating {1} as already deleted",
                    physicalId, resourceType);
            return;
        }
        try {
            switch (resourceType) {
                case ORGANIZATION -> organizationsService.deleteOrganization(caller);
                case ORGANIZATIONAL_UNIT -> organizationsService.deleteOrganizationalUnit(caller, physicalId);
                case ACCOUNT -> organizationsService.removeAccountFromOrganization(caller, physicalId);
                case POLICY -> deletePolicy(caller, physicalId);
                case RESOURCE_POLICY -> organizationsService.deleteResourcePolicy(caller);
                default -> LOG.debugv("No Organizations delete for {0}", resourceType);
            }
        } catch (AwsException e) {
            // Deleting something already gone is tolerated; anything else is a real failure.
            if (!isNotFound(e)) {
                throw e;
            }
            LOG.debugv(e, "{0} {1} was already deleted", resourceType, physicalId);
        }
    }

    // ──────────────────────────── Organization ────────────────────────────

    private void provisionOrganization(StackResource r, JsonNode props, ProvisionContext ctx) {
        String featureSet = ctx.resolveOptional(props, "FeatureSet");
        Organization organization;
        if (r.getPhysicalId() != null) {
            // UpdateStack re-invokes provision with the previous physical id. FeatureSet is the
            // only settable property, and the only legal transition is the one-way upgrade to ALL.
            organization = organizationsService.describeOrganization(ctx.accountId());
            if ("ALL".equals(featureSet) && !"ALL".equals(organization.getFeatureSet())) {
                organizationsService.enableAllFeatures(ctx.accountId());
                organization = organizationsService.describeOrganization(ctx.accountId());
            }
        } else {
            organization = organizationsService.createOrganization(ctx.accountId(), featureSet);
        }

        // AWS documents Organization as a special case: Ref returns the management account id,
        // not the organization id. Fn::GetAtt Org.Id below still returns the organization id.
        r.setPhysicalId(organization.getMasterAccountId());
        r.getAttributes().put("Id", organization.getId());
        r.getAttributes().put("Arn", organization.getArn());
        r.getAttributes().put("ManagementAccountArn", organization.getMasterAccountArn());
        r.getAttributes().put("ManagementAccountId", organization.getMasterAccountId());
        r.getAttributes().put("ManagementAccountEmail", organization.getMasterAccountEmail());
        r.getAttributes().put("RootId", organization.getRoot().getId());
    }

    // ──────────────────────────── Organizational unit ────────────────────────────

    private void provisionOrganizationalUnit(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = ctx.resolveOptional(props, "Name");
        String parentId = ctx.resolveOptional(props, "ParentId");
        Map<String, String> tags = resolveTags(props, ctx);

        OrganizationalUnit unit;
        if (r.getPhysicalId() != null) {
            // ParentId is createOnly in the registry schema, so an update only renames.
            unit = organizationsService.updateOrganizationalUnit(ctx.accountId(), r.getPhysicalId(), name);
            replaceTags(ctx.accountId(), unit.getId(), tags);
        } else {
            unit = organizationsService.createOrganizationalUnit(ctx.accountId(), parentId, name, tags);
            // The OU is real once createOrganizationalUnit returns, so mark it stack-owned before
            // the Path lookup below gets a chance to throw. CloudFormationService only deletes a
            // CREATE_FAILED resource that is both marked owned and carries a physical id.
            r.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
        }

        r.setPhysicalId(unit.getId());
        r.getAttributes().put("Id", unit.getId());
        r.getAttributes().put("Arn", unit.getArn());
        r.getAttributes().put("Path", organizationsService.organizationPath(ctx.accountId(), unit.getId()));
    }

    // ──────────────────────────── Account ────────────────────────────

    private void provisionAccount(StackResource r, JsonNode props, ProvisionContext ctx) {
        String accountName = ctx.resolveOptional(props, "AccountName");
        String email = ctx.resolveOptional(props, "Email");
        Map<String, String> tags = resolveTags(props, ctx);
        // ParentIds is a list in the schema but AWS accepts at most one entry.
        List<String> parentIds = ctx.resolveStringList(props, "ParentIds");
        String desiredParent = parentIds.isEmpty() ? null : parentIds.get(0);

        OrganizationAccount account;
        if (r.getPhysicalId() != null) {
            account = organizationsService.describeAccount(ctx.accountId(), r.getPhysicalId());
            replaceTags(ctx.accountId(), account.getId(), tags);
        } else {
            CreateAccountStatus status =
                    organizationsService.createAccount(ctx.accountId(), email, accountName, tags, false);
            if (!"SUCCEEDED".equals(status.getState())) {
                throw new AwsException("ConstraintViolationException",
                        "CreateAccount for " + accountName + " failed: " + status.getFailureReason(), 400);
            }
            // CreateAccount has already made this a real member of the organization, so claim it
            // for the stack before anything else can throw. CloudFormationService only deletes a
            // CREATE_FAILED resource that is both marked owned and carries a physical id, and the
            // move below raises DestinationParentNotFoundException whenever ParentIds names an OU
            // that isn't there — a broken Ref or a stale literal. Recording either marker later
            // would leave the account behind, unmanaged, after the stack reported its rollback.
            r.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
            r.setPhysicalId(status.getAccountId());
            account = organizationsService.describeAccount(ctx.accountId(), status.getAccountId());
        }

        if (desiredParent != null && !desiredParent.equals(account.getParentId())) {
            organizationsService.moveAccount(
                    ctx.accountId(), account.getId(), account.getParentId(), desiredParent);
            account = organizationsService.describeAccount(ctx.accountId(), account.getId());
        }

        r.getAttributes().put("AccountId", account.getId());
        r.getAttributes().put("Arn", account.getArn());
        r.getAttributes().put("Status", account.getStatus());
        // State supersedes Status in the Organizations API, which retires Status on 2026-09-09.
        // Floci only ever puts an account in ACTIVE or PENDING_CLOSURE, and both are AccountState
        // values too, so the two agree; the phases State adds — PENDING_ACTIVATION and CLOSED —
        // are ones the emulator does not model.
        r.getAttributes().put("State", account.getStatus());
        r.getAttributes().put("JoinedMethod", account.getJoinedMethod());
        if (account.getJoinedTimestamp() != null) {
            r.getAttributes().put("JoinedTimestamp", account.getJoinedTimestamp().toString());
        }
        // Paths is a list in the schema, but an account has exactly one parent and so exactly one
        // path. Attributes are string-valued, and the engine's Fn::Select splits a comma-delimited
        // scalar back into a list, so a one-element list round-trips as itself. Read this after the
        // move above: the path has to name the parent the account actually ended up in.
        r.getAttributes().put("Paths",
                organizationsService.organizationPath(ctx.accountId(), account.getId()));
    }

    // ──────────────────────────── Policy ────────────────────────────

    private void provisionPolicy(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = ctx.resolveOptional(props, "Name");
        String type = ctx.resolveOptional(props, "Type");
        String description = ctx.resolveOptional(props, "Description");
        String content = resolveContent(props, ctx);
        Map<String, String> tags = resolveTags(props, ctx);
        Set<String> desiredTargets = new LinkedHashSet<>(ctx.resolveStringList(props, "TargetIds"));

        OrganizationPolicy policy;
        if (r.getPhysicalId() != null) {
            policy = organizationsService.updatePolicy(
                    ctx.accountId(), r.getPhysicalId(), name, description, content);
            replaceTags(ctx.accountId(), policy.getId(), tags);
        } else {
            policy = organizationsService.createPolicy(ctx.accountId(), content, description, name, type, tags);
            // As above: the policy is real once createPolicy returns, so mark it stack-owned before
            // reconcileTargets gets a chance to throw.
            r.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
        }
        // Same ordering as provisionAccount: the policy already exists once createPolicy returns,
        // so claim the id before reconcileTargets, whose attach/detach calls can throw.
        r.setPhysicalId(policy.getId());

        reconcileTargets(ctx.accountId(), policy, desiredTargets);

        r.getAttributes().put("Id", policy.getId());
        r.getAttributes().put("Arn", policy.getArn());
        r.getAttributes().put("AwsManaged", String.valueOf(policy.isAwsManaged()));
    }

    /** Attaches targets the template added and detaches the ones it dropped. */
    private void reconcileTargets(String caller, OrganizationPolicy policy, Set<String> desired) {
        Set<String> current = new LinkedHashSet<>(policy.getTargets());
        for (String target : desired) {
            if (!current.contains(target)) {
                organizationsService.attachPolicy(caller, policy.getId(), target);
            }
        }
        for (String target : current) {
            if (!desired.contains(target)) {
                organizationsService.detachPolicy(caller, policy.getId(), target);
            }
        }
    }

    private void deletePolicy(String caller, String policyId) {
        // DeletePolicy refuses while the policy is still attached, so drop the attachments first.
        organizationsService.listTargetsForPolicy(caller, policyId)
                .forEach(target -> organizationsService.detachPolicy(caller, policyId, target.targetId()));
        organizationsService.deletePolicy(caller, policyId);
    }

    // ──────────────────────────── Resource policy ────────────────────────────

    private void provisionResourcePolicy(StackResource r, JsonNode props, ProvisionContext ctx) {
        // PutResourcePolicy is already create-or-update, so the same call serves both paths.
        OrganizationsService.ResourcePolicyView policy = organizationsService.putResourcePolicy(
                ctx.accountId(), resolveContent(props, ctx), resolveTags(props, ctx));

        r.setPhysicalId(policy.id());
        r.getAttributes().put("Id", policy.id());
        r.getAttributes().put("Arn", policy.arn());
    }

    // ──────────────────────────── Helpers ────────────────────────────

    /**
     * {@code Content} is a policy document that templates write either as an inline JSON object or
     * as a string built with {@code Fn::Sub}/{@code Fn::Join}. Both have to reach the service as
     * the serialized string it stores.
     */
    private String resolveContent(JsonNode props, ProvisionContext ctx) {
        if (props == null || !props.has("Content") || props.get("Content").isNull()) {
            return null;
        }
        return ctx.engine().resolveJsonAttribute(props.get("Content"));
    }

    /** CloudFormation tags are a list of {@code {Key, Value}} objects. */
    private Map<String, String> resolveTags(JsonNode props, ProvisionContext ctx) {
        if (props == null || !props.has("Tags") || !props.get("Tags").isArray()) {
            return null;
        }
        Map<String, String> tags = new LinkedHashMap<>();
        for (JsonNode tag : ctx.engine().resolveNode(props.get("Tags"))) {
            String key = tag.path("Key").asText(null);
            if (key != null && !key.isBlank()) {
                tags.put(key, tag.path("Value").asText(""));
            }
        }
        return tags;
    }

    /**
     * Makes the stored tags match the template on update: tag keys the template dropped are
     * removed rather than left behind from the previous revision.
     */
    private void replaceTags(String caller, String resourceId, Map<String, String> tags) {
        if (tags == null) {
            return;
        }
        List<String> stale = organizationsService.listTagsForResource(caller, resourceId).keySet().stream()
                .filter(key -> !tags.containsKey(key))
                .toList();
        if (!stale.isEmpty()) {
            organizationsService.untagResource(caller, resourceId, stale);
        }
        if (!tags.isEmpty()) {
            organizationsService.tagResource(caller, resourceId, tags);
        }
    }

    private static boolean isNotFound(AwsException e) {
        return switch (e.getErrorCode()) {
            case "AWSOrganizationsNotInUseException", "AccountNotFoundException",
                 "OrganizationalUnitNotFoundException", "PolicyNotFoundException",
                 "ResourcePolicyNotFoundException" -> true;
            default -> false;
        };
    }
}
