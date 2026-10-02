package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ses.SesIdentityService;
import io.github.hectorvent.floci.services.ses.SesService;
import io.github.hectorvent.floci.services.ses.model.Identity;
import io.github.hectorvent.floci.services.ses.model.Tag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class SesCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::SES::EmailIdentity";
    private static final String DEFAULT_KEY_LENGTH = "RSA_2048_BIT";
    private static final String MANAGED_TAGS_ATTR = "__FlociSesManagedTags";
    private static final String UPDATE_SNAPSHOT_ATTR = "__FlociSesUpdateSnapshot";
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private final SesService sesService;
    private final SesIdentityService identityService;

    @Inject
    public SesCfnProvisioner(SesService sesService, SesIdentityService identityService) {
        this.sesService = sesService;
        this.identityService = identityService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource resource, JsonNode props, ProvisionContext ctx) {
        Properties desired = readProperties(props, ctx);
        if (resource.getAttributes().containsKey(UPDATE_SNAPSHOT_ATTR)) {
            // A previous rollback may have failed to persist the last successful identity. Restore
            // it before either an in-place update or replacement can overwrite its snapshot.
            rollbackUpdate(resource);
            ctx = new ProvisionContext(ctx.engine(), ctx.region(), ctx.accountId(), ctx.stackName(),
                    resource.getPhysicalId(), ctx.progress());
        }
        String identityName = desired.identity();
        boolean updating = ctx.reusesPriorEntity(identityName);
        Map<String, String> attributesBefore = new HashMap<>(resource.getAttributes());
        Identity identity;
        if (updating) {
            identity = identityService.getIdentityVerificationAttributes(identityName, ctx.region());
            if (identity == null) {
                throw new AwsException("NotFoundException", "Email identity " + identityName + " does not exist.", 404);
            }
            snapshotUpdate(resource, identity, ctx);
        } else {
            identity = sesService.createEmailIdentity(identityName, desired.configurationSet(),
                    desired.tags(), ctx.region());
        }

        try {
            reconcile(resource, identity, desired, ctx);
            Identity current = identityService.getIdentityVerificationAttributes(identityName, ctx.region());
            if (current == null) {
                throw new AwsException("NotFoundException", "Email identity " + identityName + " does not exist.", 404);
            }
            resource.setPhysicalId(identityName);
            populateDkimAttributes(resource, current);
            resource.getAttributes().put(MANAGED_TAGS_ATTR, MAPPER.valueToTree(desired.tags()).toString());
            ReplacementCleanup.record(resource, ctx, attributesBefore);
            if (ctx.isUpdate() && !updating) {
                ObjectNode snapshot = MAPPER.createObjectNode();
                snapshot.put("managedTags", attributesBefore.get(MANAGED_TAGS_ATTR));
                resource.getAttributes().put(UPDATE_SNAPSHOT_ATTR, snapshot.toString());
            }
        } catch (RuntimeException failure) {
            if (!updating) {
                try {
                    sesService.deleteIdentity(identityName, ctx.region());
                    resource.setPhysicalId(ctx.priorPhysicalId());
                    resource.getAttributes().clear();
                    resource.getAttributes().putAll(attributesBefore);
                } catch (RuntimeException cleanupFailure) {
                    if (!ctx.isUpdate()) {
                        resource.setPhysicalId(identityName);
                        resource.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
                    }
                    ReplacementCleanup.recordOrphan(resource, identityName, TYPE, ctx.region());
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        sesService.deleteIdentity(physicalId, region);
    }

    @Override
    public void delete(StackResource resource, String region) {
        UpdateCleanupResult cleanup = ReplacementCleanup.complete(resource, this::delete);
        if (cleanup.applicable() && !cleanup.complete()) {
            throw new AwsException("InternalFailure",
                    "Could not delete displaced SES identity " + cleanup.previousPhysicalId()
                            + ": " + cleanup.failureReason(), 500);
        }
        delete(resource.getResourceType(), resource.getPhysicalId(), region);
        resource.getAttributes().remove(UPDATE_SNAPSHOT_ATTR);
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
        if ("UPDATE_FAILED".equals(resource.getStatus())
                && resource.getAttributes().containsKey(UPDATE_SNAPSHOT_ATTR)) {
            throw new AwsException("InternalFailure",
                    "SES identity rollback is still pending for " + resource.getPhysicalId(), 500);
        }
        UpdateCleanupResult cleanup = ReplacementCleanup.complete(resource, this::delete);
        if (!cleanup.applicable() && resource.getAttributes().containsKey(UPDATE_SNAPSHOT_ATTR)) {
            return new UpdateCleanupResult(true, true, null, 0, null);
        }
        return cleanup;
    }

    @Override
    public UpdateCleanupResult completeDeleteCleanup(StackResource resource) {
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearDeleteCleanup(StackResource resource) {
        ReplacementCleanup.clear(resource);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        ReplacementCleanup.clear(resource);
        resource.getAttributes().remove(UPDATE_SNAPSHOT_ATTR);
    }

    @Override
    public boolean retainsFailedUpdateState(StackResource resource) {
        // Orphan cleanup is additive ownership, not a mutation of the prior identity. Without a
        // current snapshot, let the engine restore the prior resource and carry that ownership over.
        return resource.getAttributes().containsKey(UPDATE_SNAPSHOT_ATTR);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            restoreManagedTags(resource, readSnapshot(resource));
            resource.getAttributes().remove(UPDATE_SNAPSHOT_ATTR);
            return true;
        }
        JsonNode snapshot = readSnapshot(resource);
        if (snapshot == null) {
            return false;
        }
        if (!snapshot.has("identity")) {
            restoreManagedTags(resource, snapshot);
            resource.getAttributes().remove(UPDATE_SNAPSHOT_ATTR);
            return true;
        }
        String region = snapshot.path("region").asText();
        try {
            Identity previous = MAPPER.treeToValue(snapshot.path("identity"), Identity.class);
            identityService.save(previous, region);
            populateDkimAttributes(resource, previous);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not restore SES identity update snapshot", failure);
        }
        restoreManagedTags(resource, snapshot);
        resource.getAttributes().remove(UPDATE_SNAPSHOT_ATTR);
        return true;
    }

    private void restoreManagedTags(StackResource resource, JsonNode snapshot) {
        JsonNode managedTags = snapshot == null ? null : snapshot.path("managedTags");
        if (managedTags != null && managedTags.isTextual()) {
            resource.getAttributes().put(MANAGED_TAGS_ATTR, managedTags.textValue());
        } else {
            resource.getAttributes().remove(MANAGED_TAGS_ATTR);
        }
    }

    private void snapshotUpdate(StackResource resource, Identity identity, ProvisionContext ctx) {
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.set("identity", MAPPER.valueToTree(identity));
        snapshot.put("region", ctx.region());
        snapshot.put("managedTags", resource.getAttributes().get(MANAGED_TAGS_ATTR));
        resource.getAttributes().put(UPDATE_SNAPSHOT_ATTR, snapshot.toString());
    }

    private JsonNode readSnapshot(StackResource resource) {
        String raw = resource.getAttributes().get(UPDATE_SNAPSHOT_ATTR);
        if (raw == null) {
            return null;
        }
        try {
            return MAPPER.readTree(raw);
        } catch (Exception failure) {
            throw new IllegalStateException("Invalid SES identity update snapshot", failure);
        }
    }

    private void reconcile(StackResource resource, Identity current, Properties desired, ProvisionContext ctx) {
        String identityName = desired.identity();
        String region = ctx.region();
        if (!same(current.getConfigurationSetName(), desired.configurationSet())) {
            sesService.setEmailIdentityConfigurationSet(identityName, desired.configurationSet(), region);
        }

        if ("Domain".equals(current.getIdentityType())) {
            String keyLength = desired.nextSigningKeyLength() == null
                    ? DEFAULT_KEY_LENGTH : desired.nextSigningKeyLength();
            if (!keyLength.equals(current.getDkimNextSigningKeyLength())) {
                identityService.putDkimSigningAttributes(identityName, "AWS_SES", null, keyLength, region);
            }
        }

        boolean signingEnabled = desired.signingEnabled() != null
                ? desired.signingEnabled() : "Domain".equals(current.getIdentityType());
        if (current.isDkimEnabled() != signingEnabled) {
            identityService.setDkimAttributes(identityName, signingEnabled, region);
        }

        String mailFromDomain = desired.mailFromDomain();
        String currentMailFromDomain = current.getMailFromDomain();
        String behavior = desired.behaviorOnMxFailure() == null
                ? "UseDefaultValue" : desired.behaviorOnMxFailure();
        if (!same(currentMailFromDomain, mailFromDomain)
                || (mailFromDomain != null && !behavior.equals(current.getBehaviorOnMxFailure()))) {
            identityService.setMailFromDomain(identityName,
                    mailFromDomain == null ? "" : mailFromDomain, behavior, region);
        }

        boolean forwarding = desired.emailForwardingEnabled() == null || desired.emailForwardingEnabled();
        if (current.isFeedbackForwardingEnabled() != forwarding) {
            identityService.setFeedbackForwardingEnabled(identityName, forwarding, region);
        }

        reconcileTags(resource, identityName, desired.tags(), ctx);
    }

    private void reconcileTags(StackResource resource, String identityName, List<Tag> desired, ProvisionContext ctx) {
        String arn = identityArn(identityName, ctx.region(), ctx.accountId());
        List<Tag> current = sesService.listResourceTags(arn, ctx.region());
        List<Tag> previouslyManaged = managedTags(resource, ctx.reusesPriorEntity(identityName), current);
        Map<String, String> desiredByKey = new HashMap<>();
        for (Tag tag : desired) {
            desiredByKey.put(tag.key(), tag.value());
        }
        List<String> staleKeys = new ArrayList<>();
        Map<String, String> currentByKey = new HashMap<>();
        for (Tag tag : current) {
            currentByKey.put(tag.key(), tag.value());
        }
        for (Tag tag : previouslyManaged) {
            if (!desiredByKey.containsKey(tag.key()) && currentByKey.containsKey(tag.key())) {
                staleKeys.add(tag.key());
            }
        }
        if (!staleKeys.isEmpty()) {
            sesService.untagResource(arn, ctx.region(), staleKeys);
        }
        List<Tag> changed = new ArrayList<>();
        for (Tag tag : desired) {
            if (!tag.value().equals(currentByKey.get(tag.key()))) {
                changed.add(tag);
            }
        }
        if (!changed.isEmpty()) {
            sesService.tagResource(arn, ctx.region(), changed);
        }
    }

    private List<Tag> managedTags(StackResource resource, boolean updating, List<Tag> current) {
        if (!updating) {
            return current;
        }
        String raw = resource.getAttributes().get(MANAGED_TAGS_ATTR);
        if (raw == null) {
            return List.of();
        }
        try {
            JsonNode stored = MAPPER.readTree(raw);
            List<Tag> tags = new ArrayList<>();
            for (JsonNode tag : stored) {
                tags.add(new Tag(tag.path("Key").asText(), tag.path("Value").asText()));
            }
            return tags;
        } catch (Exception failure) {
            throw new IllegalStateException("Invalid SES identity managed tags", failure);
        }
    }

    private String identityArn(String identityName, String region, String accountId) {
        return AwsArnUtils.Arn.of("ses", region, accountId, "identity/" + identityName).toString();
    }

    private void populateDkimAttributes(StackResource resource, Identity identity) {
        List<String> tokens = identity != null && "Domain".equals(identity.getIdentityType())
                && "AWS_SES".equals(identity.getDkimSigningAttributesOrigin())
                && identity.getDkimTokens() != null ? identity.getDkimTokens() : List.of();
        String[] names = new String[3];
        String[] values = new String[3];
        for (int index = 0; index < 3; index++) {
            String token = index < tokens.size() ? tokens.get(index) : null;
            names[index] = token == null ? "" : token + "._domainkey." + identity.getIdentity();
            values[index] = token == null ? "" : token + ".dkim.amazonses.com"; // partition-literal: SES DKIM target
        }
        resource.getAttributes().put("DkimDNSTokenName1", names[0]);
        resource.getAttributes().put("DkimDNSTokenName2", names[1]);
        resource.getAttributes().put("DkimDNSTokenName3", names[2]);
        resource.getAttributes().put("DkimDNSTokenValue1", values[0]);
        resource.getAttributes().put("DkimDNSTokenValue2", values[1]);
        resource.getAttributes().put("DkimDNSTokenValue3", values[2]);
    }

    private Properties readProperties(JsonNode props, ProvisionContext ctx) {
        if (props == null || !props.isObject()) {
            throw invalid("EmailIdentity is required.");
        }
        validateKeys(props, Set.of("EmailIdentity", "ConfigurationSetAttributes", "DkimSigningAttributes",
                "DkimAttributes", "MailFromAttributes", "FeedbackAttributes", "Tags"), "Properties");
        String identity = requiredText(props.get("EmailIdentity"), "EmailIdentity", ctx);
        JsonNode config = object(props, "ConfigurationSetAttributes", Set.of("ConfigurationSetName"), ctx);
        String configurationSet = optionalText(config, "ConfigurationSetName", ctx);
        if (configurationSet != null && configurationSet.isEmpty()) {
            configurationSet = null;
        }

        JsonNode signing = object(props, "DkimSigningAttributes",
                Set.of("DomainSigningSelector", "DomainSigningPrivateKey", "NextSigningKeyLength"), ctx);
        if (optionalText(signing, "DomainSigningSelector", ctx) != null
                || optionalText(signing, "DomainSigningPrivateKey", ctx) != null) {
            throw invalid("BYODKIM is not supported by Floci's SES implementation.");
        }
        String keyLength = optionalText(signing, "NextSigningKeyLength", ctx);
        if (keyLength != null && !Set.of("RSA_1024_BIT", DEFAULT_KEY_LENGTH).contains(keyLength)) {
            throw invalid("NextSigningKeyLength must be RSA_1024_BIT or RSA_2048_BIT.");
        }
        if (identity.contains("@") && keyLength != null) {
            throw invalid("DkimSigningAttributes requires a domain identity.");
        }

        JsonNode dkim = object(props, "DkimAttributes", Set.of("SigningEnabled"), ctx);
        Boolean signingEnabled = optionalBoolean(dkim, "SigningEnabled", ctx);
        JsonNode mailFrom = object(props, "MailFromAttributes",
                Set.of("MailFromDomain", "BehaviorOnMxFailure"), ctx);
        String mailFromDomain = optionalText(mailFrom, "MailFromDomain", ctx);
        if (mailFromDomain != null && mailFromDomain.isEmpty()) {
            mailFromDomain = null;
        }
        String behavior = optionalText(mailFrom, "BehaviorOnMxFailure", ctx);
        if (behavior != null) {
            behavior = switch (behavior) {
                case "USE_DEFAULT_VALUE" -> "UseDefaultValue";
                case "REJECT_MESSAGE" -> "RejectMessage";
                default -> throw invalid("BehaviorOnMxFailure must be USE_DEFAULT_VALUE or REJECT_MESSAGE.");
            };
            if (mailFromDomain == null) {
                throw invalid("MailFromDomain is required when BehaviorOnMxFailure is set.");
            }
        }
        JsonNode feedback = object(props, "FeedbackAttributes", Set.of("EmailForwardingEnabled"), ctx);
        Boolean forwarding = optionalBoolean(feedback, "EmailForwardingEnabled", ctx);
        return new Properties(identity, configurationSet, keyLength, signingEnabled, mailFromDomain,
                behavior, forwarding, readTags(props, ctx));
    }

    private List<Tag> readTags(JsonNode props, ProvisionContext ctx) {
        if (!props.has("Tags")) {
            return List.of();
        }
        JsonNode tags = ctx.engine().resolveNode(props.get("Tags"));
        if (tags == null || tags.isNull()) {
            return List.of();
        }
        if (!tags.isArray()) {
            throw invalid("Tags must be an array.");
        }
        List<Tag> parsed = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (JsonNode tag : tags) {
            if (!tag.isObject()) {
                throw invalid("Each tag must be an object.");
            }
            validateKeys(tag, Set.of("Key", "Value"), "Tag");
            String key = requiredText(tag.get("Key"), "Tag.Key", ctx);
            String value = text(tag.get("Value"), "Tag.Value", ctx);
            if (!keys.add(key)) {
                throw invalid("Duplicate tag key: " + key);
            }
            parsed.add(new Tag(key, value));
        }
        return parsed;
    }

    private JsonNode object(JsonNode props, String name, Set<String> allowed, ProvisionContext ctx) {
        if (props == null || !props.has(name)) {
            return null;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get(name));
        if (resolved == null || resolved.isNull()) {
            return null;
        }
        if (!resolved.isObject()) {
            throw invalid(name + " must be an object.");
        }
        validateKeys(resolved, allowed, name);
        return resolved;
    }

    private void validateKeys(JsonNode object, Set<String> allowed, String name) {
        Iterator<String> fields = object.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field)) {
                throw invalid(name + " has unsupported property " + field + ".");
            }
        }
    }

    private String optionalText(JsonNode props, String name, ProvisionContext ctx) {
        return props == null || !props.has(name) || props.get(name).isNull()
                ? null : text(props.get(name), name, ctx);
    }

    private String requiredText(JsonNode node, String name, ProvisionContext ctx) {
        String value = text(node, name, ctx);
        if (value.isBlank()) {
            throw invalid(name + " must not be empty.");
        }
        return value;
    }

    private String text(JsonNode node, String name, ProvisionContext ctx) {
        JsonNode resolved = ctx.engine().resolveNode(node);
        if (resolved == null || !resolved.isTextual()) {
            throw invalid(name + " must be a string.");
        }
        return resolved.textValue();
    }

    private Boolean optionalBoolean(JsonNode props, String name, ProvisionContext ctx) {
        if (props == null || !props.has(name) || props.get(name).isNull()) {
            return null;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get(name));
        if (resolved == null || !resolved.isBoolean()) {
            throw invalid(name + " must be a boolean.");
        }
        return resolved.booleanValue();
    }

    private boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private AwsException invalid(String message) {
        return new AwsException("ValidationError", message, 400);
    }

    private record Properties(String identity, String configurationSet, String nextSigningKeyLength,
                              Boolean signingEnabled, String mailFromDomain, String behaviorOnMxFailure,
                              Boolean emailForwardingEnabled, List<Tag> tags) {}
}
