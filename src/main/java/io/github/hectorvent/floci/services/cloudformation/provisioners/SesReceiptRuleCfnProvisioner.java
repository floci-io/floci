package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ses.SesIdentityService;
import io.github.hectorvent.floci.services.ses.SesReceiptRuleService;
import io.github.hectorvent.floci.services.ses.model.ReceiptAction;
import io.github.hectorvent.floci.services.ses.model.ReceiptRule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class SesReceiptRuleCfnProvisioner implements CfnResourceProvisioner {
    private static final String TYPE = "AWS::SES::ReceiptRule";
    private static final String SET_ATTR = "__FlociSesReceiptRuleSet";
    private static final String MODE_ATTR = "__FlociSesReceiptRuleNameMode";
    private static final String UPDATE_ATTR = "__FlociSesReceiptRuleUpdate";
    private static final String CLEANUP_ATTR = "__FlociSesReceiptRuleCleanup";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final SesReceiptRuleService rules;
    private final SesIdentityService identities;

    @Inject
    public SesReceiptRuleCfnProvisioner(SesReceiptRuleService rules, SesIdentityService identities) {
        this.rules = rules;
        this.identities = identities;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource resource, JsonNode properties, ProvisionContext ctx) {
        // The dispatcher reconstructs resource metadata without its prior UPDATE_FAILED status.
        if (resource.getAttributes().containsKey(UPDATE_ATTR)) {
            rollbackUpdate(resource);
        }
        JsonNode props = ctx.engine().resolveNodeOmittingNoValue(properties);
        if (props == null || !props.isObject()) {
            throw invalid("Properties must be an object.");
        }
        validateKeys(props, Set.of("RuleSetName", "Rule", "After"));
        String ruleSet = text(props, "RuleSetName", true);
        String after = text(props, "After", false);
        JsonNode ruleNode = props.path("Rule");
        if (!ruleNode.isObject()) {
            throw invalid("Rule must be an object.");
        }
        ReceiptRule rule = parseRule(ruleNode);
        String explicitName = rule.getName();
        Identity prior = ctx.isUpdate() && resource.getAttributes().containsKey(SET_ATTR)
                ? identity(resource) : null;
        if (ctx.isUpdate() && prior == null
                && !CfnResourceDispatcher.isStub(ctx.priorPhysicalId(), resource.getAttributes())) {
            throw new IllegalStateException("Missing SES receipt rule identity for " + resource.getLogicalId());
        }
        String name = explicitName != null ? explicitName
                : prior != null && "generated".equals(resource.getAttributes().get(MODE_ATTR)) ? prior.name()
                : ctx.generatePhysicalName(resource.getLogicalId(), 64, false);
        rule.setName(name);
        Identity target = new Identity(ruleSet, name);
        boolean replacement = prior == null || !prior.equals(target);
        deletePending(resource);
        ObjectNode snapshot = null;
        if (ctx.isUpdate()) {
            snapshot = prior == null ? MAPPER.createObjectNode() : address(prior, ctx.region());
            snapshot.put("physicalId", resource.getPhysicalId());
            snapshot.put("region", ctx.region());
            snapshot.put("replacement", replacement);
            snapshot.set("attributes", MAPPER.valueToTree(resource.getAttributes()));
            snapshot.set("target", address(target, ctx.region()));
        }
        if (!replacement) {
            ReceiptRule previous = rules.describeReceiptRule(ruleSet, name, ctx.region()).copy();
            snapshot.set("rule", MAPPER.valueToTree(previous));
            snapshot.put("after", previousRule(prior, ctx.region()));
            resource.getAttributes().put(UPDATE_ATTR, snapshot.toString());
            rules.updateReceiptRule(ruleSet, rule, ctx.region(),
                    sender -> identities.isVerifiedSender(sender, ctx.region()));
            rules.setReceiptRulePosition(ruleSet, name, after, ctx.region());
        } else {
            rules.createReceiptRule(ruleSet, rule, after, ctx.region(),
                    sender -> identities.isVerifiedSender(sender, ctx.region()));
            if (snapshot != null) {
                resource.getAttributes().put(UPDATE_ATTR, snapshot.toString());
                if (prior != null) {
                    setCleanup(resource, prior, ctx.region(), true);
                }
            }
        }
        setIdentity(resource, target);
        resource.getAttributes().put(MODE_ATTR, explicitName == null ? "generated" : "explicit");
        resource.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
    }

    @Override
    public void delete(StackResource resource, String region) {
        deletePending(resource);
        if (resource.getAttributes().containsKey(SET_ATTR)) {
            delete(identity(resource), region);
        }
        resource.getAttributes().remove(UPDATE_ATTR);
    }

    private record Identity(String set, String name) {}

    private static Identity identity(StackResource resource) {
        return new Identity(resource.getAttributes().get(SET_ATTR), resource.getPhysicalId());
    }

    private static Identity identity(JsonNode address) {
        return new Identity(address.path("set").asText(), address.path("name").asText());
    }

    private static ObjectNode address(Identity identity, String region) {
        return MAPPER.createObjectNode().put("set", identity.set()).put("name", identity.name())
                .put("region", region);
    }

    private static void setIdentity(StackResource resource, Identity identity) {
        resource.setPhysicalId(identity.name());
        resource.getAttributes().put("RuleName", identity.name());
        resource.getAttributes().put(SET_ATTR, identity.set());
    }

    private String previousRule(Identity identity, String region) {
        List<ReceiptRule> stored = rules.describeReceiptRuleSet(identity.set(), region).getRules();
        String previous = null;
        for (ReceiptRule candidate : stored) {
            if (identity.name().equals(candidate.getName())) {
                return previous;
            }
            previous = candidate.getName();
        }
        throw new IllegalStateException("SES receipt rule disappeared: " + identity.name());
    }

    private void delete(Identity identity, String region) {
        CfnDeletes.safeDelete("SES receipt rule", identity.name(),
                () -> rules.deleteReceiptRule(identity.set(), identity.name(), region), "RuleSetDoesNotExist");
    }

    private static void setCleanup(StackResource resource, Identity identity, String region, boolean retainable) {
        resource.getAttributes().put(CLEANUP_ATTR,
                address(identity, region).put("retainable", retainable).put("attempts", 0).toString());
    }

    private void deletePending(StackResource resource) {
        ObjectNode pending = read(resource, CLEANUP_ATTR);
        if (pending != null) {
            if (!retained(resource, pending)) {
                delete(identity(pending), pending.path("region").asText());
            }
            resource.getAttributes().remove(CLEANUP_ATTR);
        }
    }

    private static boolean retained(StackResource resource, JsonNode pending) {
        return pending.path("retainable").asBoolean() && "Retain".equals(resource.getUpdateReplacePolicy());
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        ObjectNode snapshot = read(resource, UPDATE_ATTR);
        if (snapshot == null) {
            return false;
        }
        String region = snapshot.path("region").asText();
        if (snapshot.path("replacement").asBoolean()) {
            // Ref is name-only, so both addresses must survive even when their names are equal.
            if (!snapshot.path("rollbackStarted").asBoolean()) {
                setCleanup(resource, identity(snapshot.path("target")), region, false);
                snapshot.put("rollbackStarted", true);
                resource.getAttributes().put(UPDATE_ATTR, snapshot.toString());
            }
            resource.setPhysicalId(snapshot.path("physicalId").asText());
            if (snapshot.has("set")) {
                setIdentity(resource, identity(snapshot));
            } else {
                resource.getAttributes().remove(SET_ATTR);
            }
            deletePending(resource);
        } else {
            ReceiptRule previous;
            try {
                previous = MAPPER.treeToValue(snapshot.path("rule"), ReceiptRule.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Invalid SES receipt rule snapshot", e);
            }
            rules.updateReceiptRule(snapshot.path("set").asText(), previous, region,
                    sender -> identities.isVerifiedSender(sender, region));
            rules.setReceiptRulePosition(snapshot.path("set").asText(), previous.getName(),
                    snapshot.path("after").asText(null), region);
        }
        resource.getAttributes().clear();
        snapshot.path("attributes").fields().forEachRemaining(e ->
                resource.getAttributes().put(e.getKey(), e.getValue().asText()));
        return true;
    }

    @Override
    public boolean retainsFailedUpdateState(StackResource resource) {
        return resource.getAttributes().containsKey(UPDATE_ATTR);
    }

    @Override
    public boolean requiresUpdateRecovery(StackResource resource) {
        return "UPDATE_FAILED".equals(resource.getStatus()) && retainsFailedUpdateState(resource);
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return resource.getAttributes().containsKey(CLEANUP_ATTR);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        ObjectNode pending = read(resource, CLEANUP_ATTR);
        return pending == null || retained(resource, pending) ? null : pending.path("name").asText();
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        if (requiresUpdateRecovery(resource)) {
            return new UpdateCleanupResult(true, false, resource.getPhysicalId(), 0, "Rule rollback is pending.");
        }
        return completeDeleteCleanup(resource);
    }

    @Override
    public UpdateCleanupResult completeDeleteCleanup(StackResource resource) {
        ObjectNode pending = read(resource, CLEANUP_ATTR);
        if (pending == null) {
            return retainsFailedUpdateState(resource) ? new UpdateCleanupResult(true, true, null, 0, null)
                    : UpdateCleanupResult.notApplicable();
        }
        String name = pending.path("name").asText();
        try {
            deletePending(resource);
            return new UpdateCleanupResult(true, true, name, 0, null);
        } catch (RuntimeException failure) {
            int attempts = pending.path("attempts").asInt() + 1;
            pending.put("attempts", attempts);
            resource.getAttributes().put(CLEANUP_ATTR, pending.toString());
            return new UpdateCleanupResult(true, false, name, attempts, failure.getMessage());
        }
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(UPDATE_ATTR);
    }

    private static ObjectNode read(StackResource resource, String key) {
        String raw = resource.getAttributes().get(key);
        if (raw == null) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            if (node.isObject()) {
                return (ObjectNode) node;
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid SES receipt rule lifecycle record", e);
        }
        throw new IllegalStateException("Invalid SES receipt rule lifecycle record");
    }

    private static ReceiptRule parseRule(JsonNode node) {
        validateKeys(node, Set.of("Name", "Enabled", "TlsPolicy", "Recipients", "ScanEnabled", "Actions"));
        ReceiptRule rule = new ReceiptRule();
        rule.setName(text(node, "Name", false));
        rule.setEnabled(bool(node, "Enabled"));
        rule.setScanEnabled(bool(node, "ScanEnabled"));
        rule.setTlsPolicy(text(node, "TlsPolicy", false));
        JsonNode recipients = node.get("Recipients");
        if (recipients != null && !recipients.isNull()) {
            if (!recipients.isArray()) {
                throw invalid("Recipients must be an array of strings.");
            }
            for (JsonNode recipient : recipients) {
                if (!recipient.isTextual()) {
                    throw invalid("Recipients must be an array of strings.");
                }
                rule.getRecipients().add(recipient.textValue());
            }
        }
        JsonNode actions = node.get("Actions");
        if (actions != null && !actions.isNull()) {
            if (!actions.isArray()) {
                throw invalid("Actions must be an array.");
            }
            List<ReceiptAction> parsed = new ArrayList<>();
            for (JsonNode action : actions) {
                if (!action.isObject() || action.size() > 1) {
                    throw invalid("Each action may contain at most one action type.");
                }
                if (action.isEmpty()) {
                    parsed.add(new ReceiptAction());
                    continue;
                }
                Map.Entry<String, JsonNode> entry = action.fields().next();
                List<String> fields = ReceiptAction.ACTION_FIELDS.get(entry.getKey());
                if (fields == null || !entry.getValue().isObject()) {
                    throw invalid("Unsupported receipt action: " + entry.getKey());
                }
                ReceiptAction parsedAction = new ReceiptAction(entry.getKey());
                entry.getValue().fields().forEachRemaining(member -> {
                    if (!fields.contains(member.getKey()) || !member.getValue().isTextual()) {
                        throw invalid("Invalid " + entry.getKey() + " property: " + member.getKey());
                    }
                    parsedAction.getProperties().put(member.getKey(), member.getValue().textValue());
                });
                parsed.add(parsedAction);
            }
            rule.setActions(parsed);
        }
        return rule;
    }

    private static String text(JsonNode node, String key, boolean required) {
        JsonNode value = node == null ? null : node.get(key);
        if (value == null || value.isNull() || value.isMissingNode()) {
            if (required) {
                throw invalid(key + " is required.");
            }
            return null;
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw invalid(key + " must be a nonempty string.");
        }
        return value.textValue();
    }

    private static boolean bool(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return false;
        }
        if (!value.isBoolean() && !(value.isTextual()
                && Set.of("true", "false").contains(value.textValue()))) {
            throw invalid(key + " must be a boolean.");
        }
        return value.asBoolean();
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationError", message, 400);
    }

    private static void validateKeys(JsonNode node, Set<String> allowed) {
        node.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) {
                throw invalid("Unknown property: " + key);
            }
        });
    }
}
