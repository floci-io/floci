package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The per-provision context every resource handler drew from: the template engine (for resolving
 * intrinsic functions in properties) plus the region/account/stack it is being created in. The
 * helpers are lifted verbatim from {@code CloudFormationResourceProvisioner}'s private methods
 * so extracted provisioners produce byte-identical physical ids and resolved values.
 */
public record ProvisionContext(CloudFormationTemplateEngine engine, String region,
                               String accountId, String stackName, String priorPhysicalId) {

    /** A context for a first-time create, with no prior physical id. */
    public ProvisionContext(CloudFormationTemplateEngine engine, String region,
                            String accountId, String stackName) {
        this(engine, region, accountId, stackName, null);
    }

    /**
     * Whether this logical resource already had a physical id from an earlier successful provision,
     * i.e. {@code provision} is running as the update path rather than the create path.
     *
     * <p>Captured when the context is built rather than read from the {@code StackResource},
     * because {@code provision} assigns the new physical id onto that resource as it works: a
     * resource-derived check flips from false to true mid-method and silently changes meaning.
     *
     * <p>Two things this does not mean. It is not "the stack is being updated" (a resource added by
     * an UpdateStack reads false, correctly). And it is not "AWS would update rather than replace"
     * — a replacing update still arrives here with the old physical id, so a provisioner treating
     * this as "reuse the existing entity" must still reject or replace on createOnly properties.
     */
    public boolean isUpdate() {
        return priorPhysicalId != null && !priorPhysicalId.isBlank();
    }

    /**
     * Resolves a CloudFormation {@code [{Key, Value}]} tag list to a map, preserving template
     * order. The whole node is resolved first so an {@code Fn::If} wrapping the list works, not
     * just intrinsics inside each entry. Entries whose key resolves blank are skipped and a
     * missing value becomes {@code ""}; an absent or non-array property yields an empty map, never
     * null.
     *
     * <p>Deliberately does not validate. Callers needing AWS's tag rules (the 50-tag cap, the
     * reserved {@code aws:} prefix) keep their own validating parse, and callers using null to mean
     * "the template said nothing, leave stored tags alone" must keep that distinction themselves.
     */
    public Map<String, String> resolveTags(JsonNode props, String name) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (props == null || !props.has(name)) {
            return tags;
        }
        JsonNode resolved = engine.resolveNode(props.get(name));
        if (resolved == null || !resolved.isArray()) {
            return tags;
        }
        for (JsonNode tag : resolved) {
            String key = engine.resolve(tag.path("Key"));
            if (key == null || key.isBlank()) {
                continue;
            }
            String value = engine.resolve(tag.path("Value"));
            tags.put(key, value == null ? "" : value);
        }
        return tags;
    }

    /** Resolves an optional property through the engine, or null when absent/explicitly null. */
    public String resolveOptional(JsonNode props, String name) {
        if (props == null || !props.has(name) || props.get(name).isNull()) {
            return null;
        }
        return engine.resolve(props.get(name));
    }

    /** Resolves an array property to its non-blank elements, or an empty list when absent. */
    public List<String> resolveStringList(JsonNode props, String name) {
        List<String> values = new ArrayList<>();
        if (props != null && props.has(name) && props.get(name).isArray()) {
            for (JsonNode element : props.get(name)) {
                String resolved = engine.resolve(element);
                if (resolved != null && !resolved.isBlank()) {
                    values.add(resolved);
                }
            }
        }
        return values;
    }

    /**
     * The physical name for a resource whose name is create-only: the template's name when it gives
     * one, otherwise the name this resource already had, and only failing both a freshly generated
     * one.
     *
     * <p>Keeping the prior name is the part that matters. {@code provision} runs again on every
     * {@code UpdateStack}, so generating unconditionally gives an unnamed resource a new random name
     * each time, creating a second resource and orphaning the first with its data. The schemas make
     * this explicit: for these types the name is a {@code createOnlyProperty}, so an unchanged
     * template must keep the same physical id.
     *
     * <p>Only for types whose physical id <em>is</em> the name. Where it is something derived, such
     * as an SNS topic's ARN, the prior name has to come from the stored attribute instead.
     */
    public String stablePhysicalName(String explicitName, String logicalId, int maxLength,
                                     boolean lowercase) {
        if (explicitName != null && !explicitName.isBlank()) {
            return explicitName;
        }
        if (priorPhysicalId != null && !priorPhysicalId.isBlank()) {
            return priorPhysicalId;
        }
        return generatePhysicalName(logicalId, maxLength, lowercase);
    }

    /**
     * Whether {@code name} identifies the entity this resource already had, so the provisioner must
     * update it rather than create it.
     *
     * <p>Deliberately not the same question as {@link #isUpdate()}. A <em>replacing</em> update also
     * arrives with a prior physical id, but the provisioner has derived a different name for it and
     * must create; treating that as an update would try to mutate a resource that does not exist.
     * Comparing the derived name against the prior id is what separates the two, and it is the
     * check a provisioner needs whenever its service rejects a duplicate name.
     *
     * <p>Pairs with {@link #stablePhysicalName}: that keeps the name steady across an update, and
     * this tells the caller what the steady name now implies. Without it, a stable name turns the
     * second UpdateStack into a duplicate-create against services that reject one.
     */
    public boolean reusesPriorEntity(String name) {
        return isUpdate() && priorPhysicalId.equals(name);
    }

    /**
     * Tag keys the stored resource carries that the template no longer declares, for the caller to
     * untag before applying the desired set. CloudFormation drives a resource's tags to the
     * template's desired state on update: a dropped key is untagged, and a template with no
     * {@code Tags} at all leaves the resource untagged. A service's tag call alone never removes
     * anything (ECR's TagResource, for one, documents that unspecified tags are left unchanged), so
     * the removal has to be computed here.
     *
     * <p>Iterates a copy, because some services hand back their live tag map and untagging while
     * walking it would modify the collection under iteration.
     */
    public static List<String> staleTagKeys(Map<String, String> current, Map<String, String> desired) {
        List<String> stale = new ArrayList<>();
        if (current == null) {
            return stale;
        }
        for (String key : List.copyOf(current.keySet())) {
            if (!desired.containsKey(key)) {
                stale.add(key);
            }
        }
        return stale;
    }

    /** Generates a CloudFormation-style physical name: {@code <stack>-<logicalId>-<suffix>}. */
    public String generatePhysicalName(String logicalId, int maxLength, boolean lowercase) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String base = stackName + "-" + logicalId;
        if (lowercase) {
            base = base.toLowerCase();
        }
        String name = base + "-" + suffix;
        if (maxLength > 0 && name.length() > maxLength) {
            // Truncate the descriptive prefix but always keep the trailing uniqueness token. When a
            // stack's name approaches the length limit, distinct logical resources still get distinct
            // physical names — CloudFormation preserves the random suffix when it shortens a generated
            // name. Truncating the whole string (suffix included) would collapse every such resource
            // onto one name and break Ref/GetAtt-based lookup (e.g. a custom resource's ServiceToken
            // resolving to the wrong Lambda).
            int keep = Math.max(0, maxLength - suffix.length() - 1);
            String prefix = base.length() > keep ? base.substring(0, keep) : base;
            while (prefix.endsWith("-")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            name = prefix.isEmpty() ? suffix : prefix + "-" + suffix;
        }
        return name;
    }
}
