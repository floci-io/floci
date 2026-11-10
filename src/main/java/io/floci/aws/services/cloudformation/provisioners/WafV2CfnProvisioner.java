package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.wafv2.WafV2Service;
import io.floci.aws.services.wafv2.model.IpSet;
import io.floci.aws.services.wafv2.model.WebAcl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class WafV2CfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(WafV2CfnProvisioner.class);
    private static final String WEB_ACL = "AWS::WAFv2::WebACL";
    private static final String WEB_ACL_ASSOCIATION = "AWS::WAFv2::WebACLAssociation";
    private static final String IP_SET = "AWS::WAFv2::IPSet";
    private static final String LOGGING_CONFIGURATION = "AWS::WAFv2::LoggingConfiguration";

    private final WafV2Service wafV2Service;

    @Inject
    public WafV2CfnProvisioner(WafV2Service wafV2Service) {
        this.wafV2Service = wafV2Service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(WEB_ACL, WEB_ACL_ASSOCIATION, IP_SET, LOGGING_CONFIGURATION);
    }

    /**
     * A web ACL or IP set id is its own {@code name|id|scope}, which names no parent. An
     * association's, {@code resourceArn|webAclArn}, starts with the resource it is attached to.
     */
    @Override
    public boolean idPrefixCanNameParent(String resourceType) {
        return WEB_ACL_ASSOCIATION.equals(resourceType);
    }

    @Override
    public void provision(StackResource resource, JsonNode props, ProvisionContext ctx) {
        if (WEB_ACL_ASSOCIATION.equals(resource.getResourceType())) {
            provisionAssociation(resource, props, ctx);
            return;
        }
        if (IP_SET.equals(resource.getResourceType())) {
            provisionIpSet(resource, props, ctx);
            return;
        }
        if (LOGGING_CONFIGURATION.equals(resource.getResourceType())) {
            provisionLoggingConfiguration(resource, props, ctx);
            return;
        }
        JsonNode resolved = ctx.engine().resolveNode(props);
        String scope = required(text(resolved, "Scope"), WEB_ACL, "Scope");
        required(raw(resolved, "DefaultAction"), WEB_ACL, "DefaultAction");
        required(raw(resolved, "VisibilityConfig"), WEB_ACL, "VisibilityConfig");
        String name = text(resolved, "Name");
        WebAcl existing = findExisting(resource.getPhysicalId());
        if (name == null || name.isBlank()) {
            name = existing == null
                    ? ctx.generatePhysicalName(resource.getLogicalId(), 128, false)
                    : existing.getName();
        }
        WebAcl desired = fromProperties(resolved);
        WebAcl acl;
        if (existing != null && existing.getName().equals(name) && existing.getScope().equals(scope)) {
            wafV2Service.updateWebAcl(desired, scope, existing.getId(), name, existing.getLockToken());
            acl = wafV2Service.getWebAcl(scope, existing.getId(), name);
            reconcileTags(acl.getArn(), desired.getTags());
        } else {
            acl = wafV2Service.createWebAcl(desired, scope, name, ctx.region());
            setReferences(resource, acl);
            if (existing != null) {
                try {
                    wafV2Service.deleteWebAcl(existing.getScope(), existing.getId(), existing.getName(),
                            existing.getLockToken());
                } catch (RuntimeException e) {
                    // old ACL may still be associated with a resource; new ACL is already tracked
                    LOG.warnv("WAFv2 CFN replacement cleanup of {0}|{1}|{2} tolerated: {3}",
                            existing.getName(), existing.getId(), existing.getScope(), e.getMessage());
                }
            }
            return;
        }
        setReferences(resource, acl);
    }

    /**
     * Every property is createOnly, so a changed one leaves a new {@code ResourceArn|WebACLArn} id
     * and the prior pair is cleaned up or rolled back to through {@link ReplacementCleanup}.
     */
    private void provisionAssociation(StackResource resource, JsonNode props, ProvisionContext ctx) {
        JsonNode resolved = ctx.engine().resolveNode(props);
        String resourceArn = required(text(resolved, "ResourceArn"), WEB_ACL_ASSOCIATION, "ResourceArn");
        String webAclArn = required(text(resolved, "WebACLArn"), WEB_ACL_ASSOCIATION, "WebACLArn");
        Map<String, String> attributesBefore = new HashMap<>(resource.getAttributes());
        String physicalId = resourceArn + "|" + webAclArn;
        if (!ctx.reusesPriorEntity(physicalId)) {
            wafV2Service.associateWebAcl(webAclArn, resourceArn);
        }
        resource.setPhysicalId(physicalId);
        ReplacementCleanup.record(resource, ctx, attributesBefore);
    }

    /**
     * {@code Name} is optional and AWS generates one when the template omits it, so the Web ACL arm's
     * rule applies: reuse the existing set's name on an update, generate one only on a create.
     * {@code Name} and {@code Scope} are create-only, so a changed one leaves a new
     * {@code name|id|scope} id and the prior set goes to {@link ReplacementCleanup}, which deletes it
     * once the update commits or points the resource back at it on a rollback. {@code Addresses},
     * {@code Description} and {@code Tags} update in place, and {@code IPAddressVersion} is never
     * passed to the update, which cannot change it on AWS either.
     */
    private void provisionIpSet(StackResource resource, JsonNode props, ProvisionContext ctx) {
        JsonNode resolved = ctx.engine().resolveNode(props);
        String scope = required(text(resolved, "Scope"), IP_SET, "Scope");
        String ipAddressVersion = ipAddressVersion(resolved);
        List<String> addresses = requiredAddresses(resolved);
        Map<String, String> attributesBefore = new HashMap<>(resource.getAttributes());
        IpSet existing = findExistingIpSet(resource.getPhysicalId());
        String name = text(resolved, "Name");
        if (name == null || name.isBlank()) {
            name = existing == null
                    ? ctx.generatePhysicalName(resource.getLogicalId(), 128, false)
                    : existing.getName();
        }
        if (existing != null && existing.getName().equals(name) && existing.getScope().equals(scope)) {
            wafV2Service.updateIpSet(scope, existing.getId(), text(resolved, "Description"), addresses,
                    name, existing.getLockToken());
            IpSet updated = wafV2Service.getIpSet(scope, existing.getId(), name);
            reconcileTags(updated.getArn(), tags(resolved));
            setIpSetReferences(resource, updated);
            return;
        }
        IpSet desired = new IpSet();
        desired.setDescription(text(resolved, "Description"));
        desired.setIpAddressVersion(ipAddressVersion);
        desired.setAddresses(addresses);
        desired.setTags(tags(resolved));
        setIpSetReferences(resource, wafV2Service.createIpSet(desired, scope, name, ctx.region()));
        ReplacementCleanup.record(resource, ctx, attributesBefore);
    }

    private void provisionLoggingConfiguration(StackResource resource, JsonNode props, ProvisionContext ctx) {
        resource.getAttributes().remove(CfnRollback.WAF_LOGGING_UPDATE_SNAPSHOT_ATTR);
        resource.getAttributes().remove(CfnRollback.WAF_LOGGING_UPDATE_ABSENT_ATTR);
        JsonNode resolved = ctx.engine().resolveNode(props);
        String resourceArn = required(text(resolved, "ResourceArn"), LOGGING_CONFIGURATION, "ResourceArn");
        JsonNode destinations = resolved.path("LogDestinationConfigs");
        if (!destinations.isArray() || destinations.isEmpty()) {
            throw new AwsException("ValidationError", LOGGING_CONFIGURATION + " requires LogDestinationConfigs", 400);
        }
        Map<String, String> attributesBefore = new HashMap<>(resource.getAttributes());
        if (ctx.isUpdate() && resourceArn.equals(ctx.priorPhysicalId())) {
            try {
                String previous = wafV2Service.getLoggingConfiguration(resourceArn);
                if (previous == null) {
                    resource.getAttributes().put(CfnRollback.WAF_LOGGING_UPDATE_ABSENT_ATTR, "true");
                } else {
                    resource.getAttributes().put(CfnRollback.WAF_LOGGING_UPDATE_SNAPSHOT_ATTR, previous);
                }
            } catch (AwsException e) {
                if (!"WAFNonexistentItemException".equals(e.getErrorCode())) {
                    throw e;
                }
                resource.getAttributes().put(CfnRollback.WAF_LOGGING_UPDATE_ABSENT_ATTR, "true");
            }
        } else {
            rejectExistingLoggingConfiguration(resourceArn);
        }
        wafV2Service.putLoggingConfiguration(resourceArn, resolved.toString());
        resource.setPhysicalId(resourceArn);
        resource.getAttributes().put("ManagedByFirewallManager", "false");
        ReplacementCleanup.record(resource, ctx, attributesBefore);
    }

    private void rejectExistingLoggingConfiguration(String resourceArn) {
        try {
            if (wafV2Service.getLoggingConfiguration(resourceArn) == null) {
                return;
            }
        } catch (AwsException e) {
            if ("WAFNonexistentItemException".equals(e.getErrorCode())) {
                return;
            }
            throw e;
        }
        throw new AwsException("WAFDuplicateItemException",
                "Logging configuration already exists for " + resourceArn, 400);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (WEB_ACL_ASSOCIATION.equals(resourceType)) {
            deleteAssociation(physicalId);
            return;
        }
        if (IP_SET.equals(resourceType)) {
            deleteIpSet(physicalId);
            return;
        }
        if (LOGGING_CONFIGURATION.equals(resourceType)) {
            wafV2Service.deleteLoggingConfiguration(physicalId);
            return;
        }
        WebAcl existing = findExisting(physicalId);
        if (existing != null) {
            wafV2Service.deleteWebAcl(existing.getScope(), existing.getId(), existing.getName(),
                    existing.getLockToken());
        }
    }

    private void deleteIpSet(String physicalId) {
        IpSet existing = findExistingIpSet(physicalId);
        if (existing == null) {
            return;
        }
        CfnDeletes.safeDelete("WAFv2 IP set", physicalId,
                () -> wafV2Service.deleteIpSet(existing.getScope(), existing.getId(), existing.getName(),
                        existing.getLockToken()),
                "WAFNonexistentItemException");
    }

    /**
     * Disassociates only while the resource still uses this pair's ACL: a replacement on the same
     * resource has already re-pointed it, and AWS keeps that newer association when the old pair is
     * cleaned up. A resource already disassociated is a no-op.
     */
    private void deleteAssociation(String physicalId) {
        String[] pair = associationPair(physicalId);
        if (pair == null) {
            return;
        }
        WebAcl current = wafV2Service.getWebAclForResource(pair[0]);
        if (current != null && pair[1].equals(current.getArn())) {
            wafV2Service.disassociateWebAcl(pair[0]);
        }
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        UpdateCleanupResult cleanup = ReplacementCleanup.complete(resource, this::delete);
        if (!cleanup.applicable() && (resource.getAttributes().containsKey(CfnRollback.WAF_LOGGING_UPDATE_SNAPSHOT_ATTR)
                || resource.getAttributes().containsKey(CfnRollback.WAF_LOGGING_UPDATE_ABSENT_ATTR))) {
            return new UpdateCleanupResult(true, true, null, 0, null);
        }
        return cleanup;
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(CfnRollback.WAF_LOGGING_UPDATE_SNAPSHOT_ATTR);
        resource.getAttributes().remove(CfnRollback.WAF_LOGGING_UPDATE_ABSENT_ATTR);
        ReplacementCleanup.clear(resource);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (WEB_ACL_ASSOCIATION.equals(resource.getResourceType())) {
            if (ReplacementCleanup.rollback(resource, this::delete)) {
                // The replacement overwrote this association on the same resource, then its delete disassociated it.
                String[] pair = associationPair(resource.getPhysicalId());
                if (pair != null) {
                    wafV2Service.associateWebAcl(pair[1], pair[0]);
                }
            }
            return true;
        }
        if (IP_SET.equals(resource.getResourceType())) {
            return ReplacementCleanup.rollback(resource, this::delete);
        }
        if (LOGGING_CONFIGURATION.equals(resource.getResourceType())) {
            if (ReplacementCleanup.rollback(resource, this::delete)) {
                return true;
            }
            if (resource.getAttributes().containsKey(CfnRollback.WAF_LOGGING_UPDATE_ABSENT_ATTR)) {
                wafV2Service.deleteLoggingConfiguration(resource.getPhysicalId());
                resource.getAttributes().remove(CfnRollback.WAF_LOGGING_UPDATE_ABSENT_ATTR);
                return true;
            }
            String previous = resource.getAttributes().get(CfnRollback.WAF_LOGGING_UPDATE_SNAPSHOT_ATTR);
            if (previous != null) {
                wafV2Service.putLoggingConfiguration(resource.getPhysicalId(), previous);
                resource.getAttributes().remove(CfnRollback.WAF_LOGGING_UPDATE_SNAPSHOT_ATTR);
            }
            return true;
        }
        return false;
    }

    private static String[] associationPair(String physicalId) {
        String[] parts = physicalId == null ? new String[0] : physicalId.split("\\|", -1);
        return parts.length == 2 ? parts : null;
    }

    private WebAcl fromProperties(JsonNode props) {
        WebAcl acl = new WebAcl();
        acl.setDescription(text(props, "Description"));
        acl.setDefaultAction(raw(props, "DefaultAction"));
        acl.setRules(raw(props, "Rules"));
        acl.setVisibilityConfig(raw(props, "VisibilityConfig"));
        acl.setCustomResponseBodies(raw(props, "CustomResponseBodies"));
        acl.setCaptchaConfig(raw(props, "CaptchaConfig"));
        acl.setChallengeConfig(raw(props, "ChallengeConfig"));
        acl.setAssociationConfig(raw(props, "AssociationConfig"));
        acl.setDataProtectionConfig(raw(props, "DataProtectionConfig"));
        List<String> tokenDomains = new ArrayList<>();
        JsonNode tokens = props.path("TokenDomains");
        if (tokens.isArray()) {
            tokens.forEach(token -> tokenDomains.add(token.asText()));
        }
        acl.setTokenDomains(tokenDomains);
        acl.setTags(tags(props));
        return acl;
    }

    private Map<String, String> tags(JsonNode props) {
        Map<String, String> tags = new LinkedHashMap<>();
        JsonNode tagNodes = props.path("Tags");
        if (tagNodes.isArray()) {
            for (JsonNode tag : tagNodes) {
                String key = text(tag, "Key");
                if (key != null) {
                    tags.put(key, text(tag, "Value"));
                }
            }
        }
        return tags;
    }

    private List<String> requiredAddresses(JsonNode props) {
        JsonNode node = props.get("Addresses");
        if (node == null || node.isNull() || !node.isArray()) {
            throw new AwsException("ValidationError", IP_SET + " requires Addresses", 400);
        }
        List<String> addresses = new ArrayList<>();
        node.forEach(address -> addresses.add(address.asText()));
        return addresses;
    }

    private String ipAddressVersion(JsonNode props) {
        String version = required(text(props, "IPAddressVersion"), IP_SET, "IPAddressVersion");
        WafV2Service.requireIpAddressVersion(version);
        return version;
    }

    private static String required(String value, String resourceType, String property) {
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationError", resourceType + " requires " + property, 400);
        }
        return value;
    }

    private void reconcileTags(String resourceArn, Map<String, String> desired) {
        List<String> removed = wafV2Service.listTagsForResource(resourceArn).keySet().stream()
                .filter(key -> !desired.containsKey(key)).toList();
        if (!removed.isEmpty()) {
            wafV2Service.untagResource(resourceArn, removed);
        }
        if (!desired.isEmpty()) {
            wafV2Service.tagResource(resourceArn, desired);
        }
    }

    private WebAcl findExisting(String physicalId) {
        String[] parts = physicalId == null ? new String[0] : physicalId.split("\\|", -1);
        if (parts.length != 3) {
            return null;
        }
        return wafV2Service.listWebAcls(parts[2]).stream()
                .filter(acl -> parts[1].equals(acl.getId()))
                .findFirst()
                .orElse(null);
    }

    private IpSet findExistingIpSet(String physicalId) {
        String[] parts = physicalId == null ? new String[0] : physicalId.split("\\|", -1);
        if (parts.length != 3) {
            return null;
        }
        return wafV2Service.listIpSets(parts[2]).stream()
                .filter(ipSet -> parts[1].equals(ipSet.getId()))
                .findFirst()
                .orElse(null);
    }

    private void setReferences(StackResource resource, WebAcl acl) {
        resource.setPhysicalId(acl.getName() + "|" + acl.getId() + "|" + acl.getScope());
        resource.getAttributes().put("Arn", acl.getArn());
        resource.getAttributes().put("Capacity", Long.toString(acl.getCapacity()));
        resource.getAttributes().put("Id", acl.getId());
        resource.getAttributes().put("LabelNamespace", acl.getLabelNamespace());
    }

    private void setIpSetReferences(StackResource resource, IpSet ipSet) {
        resource.setPhysicalId(ipSet.getName() + "|" + ipSet.getId() + "|" + ipSet.getScope());
        resource.getAttributes().put("Arn", ipSet.getArn());
        resource.getAttributes().put("Id", ipSet.getId());
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String raw(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return value.asText();
        }
        return value.isEmpty() ? null : value.toString();
    }
}
