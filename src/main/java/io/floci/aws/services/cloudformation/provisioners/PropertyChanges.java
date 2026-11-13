package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.services.cloudformation.CfnCreateOnlyProperties;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * What an update did to each top-level property of one resource: added, changed, dropped or
 * unchanged, and whether the registry marks it create-only. The engine states it once per
 * resource so a provisioner reads it instead of deriving it from the two templates.
 *
 * <p>Declared-ness follows what the provisioner sees: a property whose {@code Fn::If} chose
 * {@code AWS::NoValue} is not declared on that side. Only the choice between changed and
 * unchanged can use the forms CloudFormation compares.
 *
 * <p>Names keep the previous template's field order for the ones it declared, then the rest in
 * the order of the current properties, so {@link #dropped()} keeps the order callers saw before
 * this type existed.
 *
 * <p>UNCHANGED means unchanged in the template. A property whose {@code Ref} or
 * {@code Fn::GetAtt} reads a resource this update replaces still reads UNCHANGED, so UNCHANGED
 * alone is no reason to skip a write.
 */
public record PropertyChanges(Map<String, PropertyChange> byName, boolean requiresReplacement) {

    public enum Kind { ADDED, CHANGED, DROPPED, UNCHANGED }

    /**
     * One property's fate. {@code prior} and {@code current} are the nodes as the previous and the
     * current properties hold them, null on the side that does not declare the name.
     */
    public record PropertyChange(String name, Kind kind, JsonNode prior, JsonNode current,
                                 boolean createOnly) {}

    public PropertyChanges {
        byName = byName == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(byName));
    }

    /** Changes that do not replace the resource. */
    public PropertyChanges(Map<String, PropertyChange> byName) {
        this(byName, false);
    }

    /** What the provisioner's own inputs say, values compared as written. */
    public static PropertyChanges between(String resourceType, boolean update,
                                          JsonNode priorProperties, JsonNode properties) {
        return between(resourceType, update, priorProperties, properties, null, null);
    }

    /**
     * The same, with changed against unchanged decided by the forms CloudFormation compares when
     * both are given and hold the name, and by the values as written otherwise.
     */
    public static PropertyChanges between(String resourceType, boolean update,
                                          JsonNode priorProperties, JsonNode properties,
                                          JsonNode comparedPrior, JsonNode comparedCurrent) {
        return between(resourceType, update, priorProperties, properties, comparedPrior, comparedCurrent, null);
    }

    /**
     * The same, with the engine's own decision whether the update replaces the resource: the
     * change set's {@code Replacement}, which sees resolved values and the resources replaced
     * earlier in the same update. Without it, the decision is
     * {@link CfnCreateOnlyProperties#createOnlyValueChanged} on the two property sets, the rule
     * the change set applies to a template diff.
     */
    public static PropertyChanges between(String resourceType, boolean update,
                                          JsonNode priorProperties, JsonNode properties,
                                          JsonNode comparedPrior, JsonNode comparedCurrent,
                                          Boolean engineReplacement) {
        boolean replaces = engineReplacement != null
                ? engineReplacement
                : update && resourceType != null && priorProperties != null && priorProperties.isObject()
                        && properties != null && properties.isObject()
                        && CfnCreateOnlyProperties.createOnlyValueChanged(resourceType, priorProperties, properties);
        Map<String, PropertyChange> changes = new LinkedHashMap<>();
        boolean currentIsObject = properties != null && properties.isObject();
        if (!update || priorProperties == null || !priorProperties.isObject()) {
            if (currentIsObject) {
                properties.fieldNames().forEachRemaining(name -> changes.put(name,
                        change(resourceType, name, Kind.ADDED, null, properties.get(name))));
            }
            return new PropertyChanges(changes, replaces);
        }
        priorProperties.fieldNames().forEachRemaining(name -> {
            JsonNode before = priorProperties.get(name);
            if (before.isNull()) {
                return;
            }
            if (currentIsObject && properties.has(name)) {
                JsonNode now = properties.get(name);
                Kind kind = unchanged(name, before, now, comparedPrior, comparedCurrent)
                        ? Kind.UNCHANGED : Kind.CHANGED;
                changes.put(name, change(resourceType, name, kind, before, now));
            } else {
                changes.put(name, change(resourceType, name, Kind.DROPPED, before, null));
            }
        });
        if (currentIsObject) {
            properties.fieldNames().forEachRemaining(name -> {
                if (!changes.containsKey(name)) {
                    changes.put(name, change(resourceType, name, Kind.ADDED, null, properties.get(name)));
                }
            });
        }
        return new PropertyChanges(changes, replaces);
    }

    private static PropertyChange change(String resourceType, String name, Kind kind,
                                         JsonNode prior, JsonNode current) {
        boolean createOnly = resourceType != null
                && CfnCreateOnlyProperties.isCreateOnly(resourceType, name);
        return new PropertyChange(name, kind, prior, current, createOnly);
    }

    private static boolean unchanged(String name, JsonNode before, JsonNode now,
                                     JsonNode comparedPrior, JsonNode comparedCurrent) {
        if (comparedPrior != null && comparedCurrent != null) {
            JsonNode comparedBefore = comparedPrior.path(name);
            JsonNode comparedNow = comparedCurrent.path(name);
            if (!comparedBefore.isMissingNode() && !comparedNow.isMissingNode()) {
                return comparedBefore.equals(comparedNow);
            }
        }
        return before.equals(now);
    }

    /** The kind of {@code name}, or null when it is in neither template. */
    public Kind kindOf(String name) {
        PropertyChange found = byName.get(name);
        return found == null ? null : found.kind();
    }

    public Set<String> added() {
        return names(Kind.ADDED);
    }

    public Set<String> changed() {
        return names(Kind.CHANGED);
    }

    public Set<String> dropped() {
        return names(Kind.DROPPED);
    }

    public Set<String> unchanged() {
        return names(Kind.UNCHANGED);
    }

    private Set<String> names(Kind kind) {
        Set<String> names = new LinkedHashSet<>();
        for (PropertyChange found : byName.values()) {
            if (found.kind() == kind) {
                names.add(found.name());
            }
        }
        return Collections.unmodifiableSet(names);
    }
}
