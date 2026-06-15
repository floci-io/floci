package io.github.hectorvent.floci.services.wafv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsJson11Controller;
import io.github.hectorvent.floci.services.wafv2.model.IpSet;
import io.github.hectorvent.floci.services.wafv2.model.RegexPatternSet;
import io.github.hectorvent.floci.services.wafv2.model.RuleGroup;
import io.github.hectorvent.floci.services.wafv2.model.WebAcl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WAF v2 JSON 1.1 handler. Dispatched from {@link AwsJson11Controller} under the
 * {@code AWSWAF_20190729.} target prefix.
 */
@ApplicationScoped
public class WafV2Handler {

    private static final Logger LOG = Logger.getLogger(WafV2Handler.class);

    private final WafV2Service service;
    private final ObjectMapper objectMapper;

    @Inject
    public WafV2Handler(WafV2Service service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("WAFv2 action: {0}", action);
        try {
            return switch (action) {
                case "CreateWebACL" -> handleCreateWebAcl(request, region);
                case "GetWebACL" -> handleGetWebAcl(request);
                case "UpdateWebACL" -> handleUpdateWebAcl(request);
                case "DeleteWebACL" -> handleDeleteWebAcl(request);
                case "ListWebACLs" -> handleListWebAcls(request);
                case "CreateIPSet" -> handleCreateIpSet(request, region);
                case "GetIPSet" -> handleGetIpSet(request);
                case "UpdateIPSet" -> handleUpdateIpSet(request);
                case "DeleteIPSet" -> handleDeleteIpSet(request);
                case "ListIPSets" -> handleListIpSets(request);
                case "CreateRegexPatternSet" -> handleCreateRegexPatternSet(request, region);
                case "GetRegexPatternSet" -> handleGetRegexPatternSet(request);
                case "UpdateRegexPatternSet" -> handleUpdateRegexPatternSet(request);
                case "DeleteRegexPatternSet" -> handleDeleteRegexPatternSet(request);
                case "ListRegexPatternSets" -> handleListRegexPatternSets(request);
                case "CreateRuleGroup" -> handleCreateRuleGroup(request, region);
                case "GetRuleGroup" -> handleGetRuleGroup(request);
                case "UpdateRuleGroup" -> handleUpdateRuleGroup(request);
                case "DeleteRuleGroup" -> handleDeleteRuleGroup(request);
                case "ListRuleGroups" -> handleListRuleGroups(request);
                case "CheckCapacity" -> handleCheckCapacity(request);
                case "AssociateWebACL" -> handleAssociateWebAcl(request);
                case "DisassociateWebACL" -> handleDisassociateWebAcl(request);
                case "GetWebACLForResource" -> handleGetWebAclForResource(request);
                case "ListResourcesForWebACL" -> handleListResourcesForWebAcl(request);
                case "PutLoggingConfiguration" -> handlePutLoggingConfiguration(request);
                case "GetLoggingConfiguration" -> handleGetLoggingConfiguration(request);
                case "DeleteLoggingConfiguration" -> handleDeleteLoggingConfiguration(request);
                case "ListLoggingConfigurations" -> handleListLoggingConfigurations();
                case "PutPermissionPolicy" -> handlePutPermissionPolicy(request);
                case "GetPermissionPolicy" -> handleGetPermissionPolicy(request);
                case "DeletePermissionPolicy" -> handleDeletePermissionPolicy(request);
                case "TagResource" -> handleTagResource(request);
                case "UntagResource" -> handleUntagResource(request);
                case "ListTagsForResource" -> handleListTagsForResource(request);
                default -> Response.status(400)
                        .entity(new AwsErrorResponse("WAFInvalidOperationException",
                                "Operation " + action + " is not supported."))
                        .build();
            };
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus())
                    .entity(new AwsErrorResponse(e.jsonType(), e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.errorv("WAFv2 error processing action {0}: {1}", action, e.getMessage());
            return Response.status(500)
                    .entity(new AwsErrorResponse("WAFInternalErrorException", e.getMessage()))
                    .build();
        }
    }

    // ──────────────────────────── Web ACL ────────────────────────────

    private Response handleCreateWebAcl(JsonNode request, String region) {
        WebAcl acl = new WebAcl();
        acl.setDescription(text(request, "Description"));
        acl.setDefaultAction(rawObject(request.path("DefaultAction")));
        acl.setRules(rawArray(request.path("Rules")));
        acl.setVisibilityConfig(rawObject(request.path("VisibilityConfig")));
        acl.setCustomResponseBodies(rawObject(request.path("CustomResponseBodies")));
        acl.setCaptchaConfig(rawObject(request.path("CaptchaConfig")));
        acl.setChallengeConfig(rawObject(request.path("ChallengeConfig")));
        acl.setTokenDomains(stringList(request.path("TokenDomains")));
        acl.setAssociationConfig(rawObject(request.path("AssociationConfig")));
        acl.setDataProtectionConfig(rawObject(request.path("DataProtectionConfig")));
        acl.setTags(parseTags(request));
        WebAcl created = service.createWebAcl(acl, text(request, "Scope"), text(request, "Name"), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Summary", summaryNode(created.getName(), created.getId(),
                created.getDescription(), created.getLockToken(), created.getArn()));
        return Response.ok(response).build();
    }

    private Response handleGetWebAcl(JsonNode request) {
        WebAcl acl = service.getWebAcl(text(request, "Scope"), text(request, "Id"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("WebACL", webAclNode(acl));
        response.put("LockToken", acl.getLockToken());
        return Response.ok(response).build();
    }

    private Response handleUpdateWebAcl(JsonNode request) {
        WebAcl changes = new WebAcl();
        changes.setDescription(text(request, "Description"));
        changes.setDefaultAction(rawObject(request.path("DefaultAction")));
        changes.setRules(rawArray(request.path("Rules")));
        changes.setVisibilityConfig(rawObject(request.path("VisibilityConfig")));
        changes.setCustomResponseBodies(rawObject(request.path("CustomResponseBodies")));
        changes.setCaptchaConfig(rawObject(request.path("CaptchaConfig")));
        changes.setChallengeConfig(rawObject(request.path("ChallengeConfig")));
        changes.setTokenDomains(stringList(request.path("TokenDomains")));
        changes.setAssociationConfig(rawObject(request.path("AssociationConfig")));
        changes.setDataProtectionConfig(rawObject(request.path("DataProtectionConfig")));
        String next = service.updateWebAcl(changes, text(request, "Scope"),
                text(request, "Id"), text(request, "LockToken"));
        return Response.ok(objectMapper.createObjectNode().put("NextLockToken", next)).build();
    }

    private Response handleDeleteWebAcl(JsonNode request) {
        service.deleteWebAcl(text(request, "Scope"), text(request, "Id"), text(request, "LockToken"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListWebAcls(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("WebACLs");
        for (WebAcl acl : service.listWebAcls(text(request, "Scope"))) {
            arr.add(summaryNode(acl.getName(), acl.getId(), acl.getDescription(),
                    acl.getLockToken(), acl.getArn()));
        }
        return Response.ok(response).build();
    }

    // ──────────────────────────── IP set ────────────────────────────

    private Response handleCreateIpSet(JsonNode request, String region) {
        IpSet ipSet = new IpSet();
        ipSet.setDescription(text(request, "Description"));
        ipSet.setIpAddressVersion(text(request, "IPAddressVersion"));
        ipSet.setAddresses(stringList(request.path("Addresses")));
        ipSet.setTags(parseTags(request));
        IpSet created = service.createIpSet(ipSet, text(request, "Scope"), text(request, "Name"), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Summary", summaryNode(created.getName(), created.getId(),
                created.getDescription(), created.getLockToken(), created.getArn()));
        return Response.ok(response).build();
    }

    private Response handleGetIpSet(JsonNode request) {
        IpSet ipSet = service.getIpSet(text(request, "Scope"), text(request, "Id"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("IPSet", ipSetNode(ipSet));
        response.put("LockToken", ipSet.getLockToken());
        return Response.ok(response).build();
    }

    private Response handleUpdateIpSet(JsonNode request) {
        String next = service.updateIpSet(text(request, "Scope"), text(request, "Id"),
                text(request, "Description"), stringList(request.path("Addresses")),
                text(request, "LockToken"));
        return Response.ok(objectMapper.createObjectNode().put("NextLockToken", next)).build();
    }

    private Response handleDeleteIpSet(JsonNode request) {
        service.deleteIpSet(text(request, "Scope"), text(request, "Id"), text(request, "LockToken"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListIpSets(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("IPSets");
        for (IpSet ipSet : service.listIpSets(text(request, "Scope"))) {
            arr.add(summaryNode(ipSet.getName(), ipSet.getId(), ipSet.getDescription(),
                    ipSet.getLockToken(), ipSet.getArn()));
        }
        return Response.ok(response).build();
    }

    // ──────────────────────────── Regex pattern set ────────────────────────────

    private Response handleCreateRegexPatternSet(JsonNode request, String region) {
        RegexPatternSet set = new RegexPatternSet();
        set.setDescription(text(request, "Description"));
        set.setRegularExpressionList(regexList(request.path("RegularExpressionList")));
        set.setTags(parseTags(request));
        RegexPatternSet created = service.createRegexPatternSet(set, text(request, "Scope"),
                text(request, "Name"), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Summary", summaryNode(created.getName(), created.getId(),
                created.getDescription(), created.getLockToken(), created.getArn()));
        return Response.ok(response).build();
    }

    private Response handleGetRegexPatternSet(JsonNode request) {
        RegexPatternSet set = service.getRegexPatternSet(text(request, "Scope"), text(request, "Id"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("RegexPatternSet", regexSetNode(set));
        response.put("LockToken", set.getLockToken());
        return Response.ok(response).build();
    }

    private Response handleUpdateRegexPatternSet(JsonNode request) {
        String next = service.updateRegexPatternSet(text(request, "Scope"), text(request, "Id"),
                text(request, "Description"), regexList(request.path("RegularExpressionList")),
                text(request, "LockToken"));
        return Response.ok(objectMapper.createObjectNode().put("NextLockToken", next)).build();
    }

    private Response handleDeleteRegexPatternSet(JsonNode request) {
        service.deleteRegexPatternSet(text(request, "Scope"), text(request, "Id"), text(request, "LockToken"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListRegexPatternSets(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("RegexPatternSets");
        for (RegexPatternSet set : service.listRegexPatternSets(text(request, "Scope"))) {
            arr.add(summaryNode(set.getName(), set.getId(), set.getDescription(),
                    set.getLockToken(), set.getArn()));
        }
        return Response.ok(response).build();
    }

    // ──────────────────────────── Rule group ────────────────────────────

    private Response handleCreateRuleGroup(JsonNode request, String region) {
        RuleGroup group = new RuleGroup();
        group.setDescription(text(request, "Description"));
        group.setCapacity(request.path("Capacity").asLong(0));
        group.setRules(rawArray(request.path("Rules")));
        group.setVisibilityConfig(rawObject(request.path("VisibilityConfig")));
        group.setCustomResponseBodies(rawObject(request.path("CustomResponseBodies")));
        group.setTags(parseTags(request));
        RuleGroup created = service.createRuleGroup(group, text(request, "Scope"), text(request, "Name"), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Summary", summaryNode(created.getName(), created.getId(),
                created.getDescription(), created.getLockToken(), created.getArn()));
        return Response.ok(response).build();
    }

    private Response handleGetRuleGroup(JsonNode request) {
        RuleGroup group = service.getRuleGroup(text(request, "Scope"), text(request, "Id"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("RuleGroup", ruleGroupNode(group));
        response.put("LockToken", group.getLockToken());
        return Response.ok(response).build();
    }

    private Response handleUpdateRuleGroup(JsonNode request) {
        RuleGroup changes = new RuleGroup();
        changes.setDescription(text(request, "Description"));
        changes.setRules(rawArray(request.path("Rules")));
        changes.setVisibilityConfig(rawObject(request.path("VisibilityConfig")));
        changes.setCustomResponseBodies(rawObject(request.path("CustomResponseBodies")));
        String next = service.updateRuleGroup(changes, text(request, "Scope"),
                text(request, "Id"), text(request, "LockToken"));
        return Response.ok(objectMapper.createObjectNode().put("NextLockToken", next)).build();
    }

    private Response handleDeleteRuleGroup(JsonNode request) {
        service.deleteRuleGroup(text(request, "Scope"), text(request, "Id"), text(request, "LockToken"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListRuleGroups(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("RuleGroups");
        for (RuleGroup group : service.listRuleGroups(text(request, "Scope"))) {
            arr.add(summaryNode(group.getName(), group.getId(), group.getDescription(),
                    group.getLockToken(), group.getArn()));
        }
        return Response.ok(response).build();
    }

    private Response handleCheckCapacity(JsonNode request) {
        long capacity = service.checkCapacity(rawArray(request.path("Rules")));
        return Response.ok(objectMapper.createObjectNode().put("Capacity", capacity)).build();
    }

    // ──────────────────────────── Association ────────────────────────────

    private Response handleAssociateWebAcl(JsonNode request) {
        service.associateWebAcl(text(request, "WebACLArn"), text(request, "ResourceArn"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDisassociateWebAcl(JsonNode request) {
        service.disassociateWebAcl(text(request, "ResourceArn"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleGetWebAclForResource(JsonNode request) {
        WebAcl acl = service.getWebAclForResource(text(request, "ResourceArn"));
        ObjectNode response = objectMapper.createObjectNode();
        if (acl != null) {
            response.set("WebACL", webAclNode(acl));
        }
        return Response.ok(response).build();
    }

    private Response handleListResourcesForWebAcl(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("ResourceArns");
        service.listResourcesForWebAcl(text(request, "WebACLArn")).forEach(arr::add);
        return Response.ok(response).build();
    }

    // ──────────────────────────── Logging ────────────────────────────

    private Response handlePutLoggingConfiguration(JsonNode request) {
        JsonNode config = request.path("LoggingConfiguration");
        String resourceArn = config.path("ResourceArn").asText(null);
        if (resourceArn == null) {
            throw new AwsException("WAFInvalidParameterException", "ResourceArn is required.", 400);
        }
        String stored = service.putLoggingConfiguration(resourceArn, config.toString());
        ObjectNode response = objectMapper.createObjectNode();
        setRawJson(response, "LoggingConfiguration", stored);
        return Response.ok(response).build();
    }

    private Response handleGetLoggingConfiguration(JsonNode request) {
        String stored = service.getLoggingConfiguration(text(request, "ResourceArn"));
        ObjectNode response = objectMapper.createObjectNode();
        setRawJson(response, "LoggingConfiguration", stored);
        return Response.ok(response).build();
    }

    private Response handleDeleteLoggingConfiguration(JsonNode request) {
        service.deleteLoggingConfiguration(text(request, "ResourceArn"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListLoggingConfigurations() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("LoggingConfigurations");
        for (String stored : service.listLoggingConfigurations()) {
            try {
                arr.add(objectMapper.readTree(stored));
            } catch (Exception ignored) {
                // skip unparseable stored config
            }
        }
        return Response.ok(response).build();
    }

    // ──────────────────────────── Permission policy ────────────────────────────

    private Response handlePutPermissionPolicy(JsonNode request) {
        service.putPermissionPolicy(text(request, "ResourceArn"), text(request, "Policy"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleGetPermissionPolicy(JsonNode request) {
        String policy = service.getPermissionPolicy(text(request, "ResourceArn"));
        return Response.ok(objectMapper.createObjectNode().put("Policy", policy)).build();
    }

    private Response handleDeletePermissionPolicy(JsonNode request) {
        service.deletePermissionPolicy(text(request, "ResourceArn"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    // ──────────────────────────── Tags ────────────────────────────

    private Response handleTagResource(JsonNode request) {
        service.tagResource(text(request, "ResourceARN"), parseTagList(request.path("Tags")));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagResource(JsonNode request) {
        List<String> keys = new ArrayList<>();
        request.path("TagKeys").forEach(k -> keys.add(k.asText()));
        service.untagResource(text(request, "ResourceARN"), keys);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListTagsForResource(JsonNode request) {
        String resourceArn = text(request, "ResourceARN");
        Map<String, String> tags = service.listTagsForResource(resourceArn);
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode info = response.putObject("TagInfoForResource");
        info.put("ResourceARN", resourceArn);
        ArrayNode arr = info.putArray("TagList");
        tags.forEach((k, v) -> {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("Key", k);
            node.put("Value", v);
            arr.add(node);
        });
        return Response.ok(response).build();
    }

    // ──────────────────────────── Builders ────────────────────────────

    private ObjectNode summaryNode(String name, String id, String description,
                                   String lockToken, String arn) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", name);
        node.put("Id", id);
        if (description != null) {
            node.put("Description", description);
        }
        node.put("LockToken", lockToken);
        node.put("ARN", arn);
        return node;
    }

    private ObjectNode webAclNode(WebAcl acl) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", acl.getName());
        node.put("Id", acl.getId());
        node.put("ARN", acl.getArn());
        setRawJson(node, "DefaultAction", acl.getDefaultAction());
        if (acl.getDescription() != null) {
            node.put("Description", acl.getDescription());
        }
        setRawJson(node, "Rules", acl.getRules());
        setRawJson(node, "VisibilityConfig", acl.getVisibilityConfig());
        node.put("Capacity", acl.getCapacity());
        setRawJson(node, "CustomResponseBodies", acl.getCustomResponseBodies());
        setRawJson(node, "CaptchaConfig", acl.getCaptchaConfig());
        setRawJson(node, "ChallengeConfig", acl.getChallengeConfig());
        setRawJson(node, "AssociationConfig", acl.getAssociationConfig());
        if (acl.getLabelNamespace() != null) {
            node.put("LabelNamespace", acl.getLabelNamespace());
        }
        if (!acl.getTokenDomains().isEmpty()) {
            ArrayNode domains = node.putArray("TokenDomains");
            acl.getTokenDomains().forEach(domains::add);
        }
        node.put("ManagedByFirewallManager", false);
        return node;
    }

    private ObjectNode ipSetNode(IpSet ipSet) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", ipSet.getName());
        node.put("Id", ipSet.getId());
        node.put("ARN", ipSet.getArn());
        if (ipSet.getDescription() != null) {
            node.put("Description", ipSet.getDescription());
        }
        node.put("IPAddressVersion", ipSet.getIpAddressVersion());
        ArrayNode addresses = node.putArray("Addresses");
        ipSet.getAddresses().forEach(addresses::add);
        return node;
    }

    private ObjectNode regexSetNode(RegexPatternSet set) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", set.getName());
        node.put("Id", set.getId());
        node.put("ARN", set.getArn());
        if (set.getDescription() != null) {
            node.put("Description", set.getDescription());
        }
        ArrayNode list = node.putArray("RegularExpressionList");
        set.getRegularExpressionList().forEach(r -> list.addObject().put("RegexString", r));
        return node;
    }

    private ObjectNode ruleGroupNode(RuleGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", group.getName());
        node.put("Id", group.getId());
        node.put("ARN", group.getArn());
        node.put("Capacity", group.getCapacity());
        if (group.getDescription() != null) {
            node.put("Description", group.getDescription());
        }
        setRawJson(node, "Rules", group.getRules());
        setRawJson(node, "VisibilityConfig", group.getVisibilityConfig());
        setRawJson(node, "CustomResponseBodies", group.getCustomResponseBodies());
        if (group.getLabelNamespace() != null) {
            node.put("LabelNamespace", group.getLabelNamespace());
        }
        return node;
    }

    // ──────────────────────────── Parsing ────────────────────────────

    private Map<String, String> parseTags(JsonNode request) {
        return parseTagList(request.path("Tags"));
    }

    private Map<String, String> parseTagList(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                String key = tag.path("Key").asText(null);
                if (key != null) {
                    tags.put(key, tag.path("Value").asText(null));
                }
            }
        }
        return tags;
    }

    private List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(v -> values.add(v.asText()));
        }
        return values;
    }

    private List<String> regexList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(v -> values.add(v.path("RegexString").asText()));
        }
        return values;
    }

    private String text(JsonNode request, String field) {
        JsonNode node = request.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText(null);
    }

    private String rawObject(JsonNode node) {
        return (node != null && node.isObject() && !node.isEmpty()) ? node.toString() : null;
    }

    private String rawArray(JsonNode node) {
        return (node != null && node.isArray() && !node.isEmpty()) ? node.toString() : null;
    }

    private void setRawJson(ObjectNode target, String field, String rawJson) {
        if (rawJson == null) {
            return;
        }
        try {
            target.set(field, objectMapper.readTree(rawJson));
        } catch (Exception e) {
            LOG.warnv("Failed to parse stored {0} JSON; field omitted. Value: {1}", field, rawJson);
        }
    }
}
