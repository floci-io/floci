package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.wafv2.WafV2Service;
import io.github.hectorvent.floci.services.wafv2.model.WebAcl;
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

    private final WafV2Service wafV2Service;

    @Inject
    public WafV2CfnProvisioner(WafV2Service wafV2Service) {
        this.wafV2Service = wafV2Service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(WEB_ACL, WEB_ACL_ASSOCIATION);
    }

    @Override
    public void provision(StackResource resource, JsonNode props, ProvisionContext ctx) {
        if (WEB_ACL_ASSOCIATION.equals(resource.getResourceType())) {
            provisionAssociation(resource, props, ctx);
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
            reconcileTags(acl, desired.getTags());
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

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (WEB_ACL_ASSOCIATION.equals(resourceType)) {
            deleteAssociation(physicalId);
            return;
        }
        WebAcl existing = findExisting(physicalId);
        if (existing != null) {
            wafV2Service.deleteWebAcl(existing.getScope(), existing.getId(), existing.getName(),
                    existing.getLockToken());
        }
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
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        ReplacementCleanup.clear(resource);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (!WEB_ACL_ASSOCIATION.equals(resource.getResourceType())) {
            return false;
        }
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            // The replacement overwrote this association on the same resource, then its delete disassociated it.
            String[] pair = associationPair(resource.getPhysicalId());
            if (pair != null) {
                wafV2Service.associateWebAcl(pair[1], pair[0]);
            }
        }
        return true;
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
        acl.setTags(tags);
        return acl;
    }

    private static String required(String value, String resourceType, String property) {
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationError", resourceType + " requires " + property, 400);
        }
        return value;
    }

    private void reconcileTags(WebAcl existing, Map<String, String> desired) {
        List<String> removed = wafV2Service.listTagsForResource(existing.getArn()).keySet().stream()
                .filter(key -> !desired.containsKey(key)).toList();
        if (!removed.isEmpty()) {
            wafV2Service.untagResource(existing.getArn(), removed);
        }
        if (!desired.isEmpty()) {
            wafV2Service.tagResource(existing.getArn(), desired);
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

    private void setReferences(StackResource resource, WebAcl acl) {
        resource.setPhysicalId(acl.getName() + "|" + acl.getId() + "|" + acl.getScope());
        resource.getAttributes().put("Arn", acl.getArn());
        resource.getAttributes().put("Capacity", Long.toString(acl.getCapacity()));
        resource.getAttributes().put("Id", acl.getId());
        resource.getAttributes().put("LabelNamespace", acl.getLabelNamespace());
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
