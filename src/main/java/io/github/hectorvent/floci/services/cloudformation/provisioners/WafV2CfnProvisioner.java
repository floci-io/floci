package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.wafv2.WafV2Service;
import io.github.hectorvent.floci.services.wafv2.model.WebAcl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class WafV2CfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(WafV2CfnProvisioner.class);
    private static final String WEB_ACL = "AWS::WAFv2::WebACL";

    private final WafV2Service wafV2Service;

    @Inject
    public WafV2CfnProvisioner(WafV2Service wafV2Service) {
        this.wafV2Service = wafV2Service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(WEB_ACL);
    }

    @Override
    public void provision(StackResource resource, JsonNode props, ProvisionContext ctx) {
        JsonNode resolved = ctx.engine().resolveNode(props);
        String name = text(resolved, "Name");
        WebAcl existing = findExisting(resource.getPhysicalId());
        if (name == null || name.isBlank()) {
            name = existing == null
                    ? ctx.generatePhysicalName(resource.getLogicalId(), 128, false)
                    : existing.getName();
        }
        String scope = text(resolved, "Scope");
        WebAcl desired = fromProperties(resolved);
        WebAcl acl;
        if (existing != null && existing.getName().equals(name) && existing.getScope().equals(scope)) {
            wafV2Service.updateWebAcl(desired, scope, existing.getId(), existing.getLockToken());
            acl = wafV2Service.getWebAcl(scope, existing.getId());
            reconcileTags(acl, desired.getTags());
        } else {
            acl = wafV2Service.createWebAcl(desired, scope, name, ctx.region());
            setReferences(resource, acl);
            if (existing != null) {
                try {
                    wafV2Service.deleteWebAcl(existing.getScope(), existing.getId(), existing.getLockToken());
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

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        WebAcl existing = findExisting(physicalId);
        if (existing != null) {
            wafV2Service.deleteWebAcl(existing.getScope(), existing.getId(), existing.getLockToken());
        }
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

    private void reconcileTags(WebAcl existing, Map<String, String> desired) {
        List<String> removed = existing.getTags().keySet().stream()
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
