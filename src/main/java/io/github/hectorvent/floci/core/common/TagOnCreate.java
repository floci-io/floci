package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.container.ContainerRequestContext;

import java.math.BigInteger;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;

/**
 * The tagging action a request that tags what it creates also needs, and the tags it attaches.
 *
 * <p>AWS checks the service's tagging action as well as the operation's own when the request carries
 * tags: the SNS API reference has "To be able to tag a topic on creation, you must have the
 * {@code sns:CreateTopic} and {@code sns:TagResource} permissions." Which action each operation needs and
 * where its request carries the tags come from {@link TagOnCreateMembers}, generated from AWS's Service
 * Reference and botocore. The operation's own action is checked with the tags too, where the Service
 * Reference lists {@code aws:RequestTag} or {@code aws:TagKeys} for it. The operations in {@link #BY_HAND}
 * need one of several tagging actions, and {@code IamEnforcementFilter} checks them itself.
 */
final class TagOnCreate {

    /** Operations the enforcement filter checks itself: CloudWatch Logs takes either of two tagging actions. */
    static final Set<String> BY_HAND = Set.of("logs:CreateLogGroup");

    /**
     * The tagging action a request needs, the tags it attaches, whether the action takes the CreateAction key,
     * and whether the operation's own action takes the tags as well.
     */
    record Tagging(String action, Map<String, String> tags, boolean createAction, boolean requestTags) {
    }

    private TagOnCreate() {
    }

    /**
     * What a request for {@code operation}, as {@code <namespace>:<Operation>}, needs to tag what it creates:
     * empty when the table does not read the operation or the request carries no tags. A tag's key given
     * twice keeps its last value, as the services keep it.
     */
    static Optional<Tagging> of(String operation, ContainerRequestContext ctx) {
        TagOnCreateMembers.Entry entry = TagOnCreateMembers.of(operation);
        if (entry == null) {
            return Optional.empty();
        }
        Map<String, String> form = IamConditionContextResolver.formParameters(ctx);
        JsonNode body = form.isEmpty() ? PassedRoles.jsonBody(ctx) : null;
        Map<String, String> tags = new LinkedHashMap<>();
        for (TagOnCreateMembers.Member member : entry.members()) {
            if (!form.isEmpty()) {
                fromForm(form, member, tags);
            } else {
                collect(body, member, 0, tags);
            }
        }
        return tags.isEmpty() ? Optional.empty()
                : Optional.of(new Tagging(entry.tagging(), Collections.unmodifiableMap(tags), entry.createAction(),
                        entry.requestTags()));
    }

    /** The tags a form sends, each a numbered tag's key and value parameters, in the order they are numbered. */
    private static void fromForm(Map<String, String> form, TagOnCreateMembers.Member member, Map<String, String> tags) {
        if (member.queryTags() == null) {
            return;
        }
        Map<String, String[]> numbered = new TreeMap<>(TagOnCreate::byNumbers);
        form.forEach((name, value) -> {
            Matcher matcher = member.queryTags().matcher(name);
            if (matcher.matches()) {
                String[] tag = numbered.computeIfAbsent(matcher.group("tag"), ignored -> new String[2]);
                tag[isKey(member, matcher.group("field")) ? 0 : 1] = value;
            }
        });
        numbered.values().forEach(tag -> {
            if (tag[0] != null) {
                tags.put(tag[0], tag[1] == null ? "" : tag[1]);
            }
        });
    }

    private static boolean isKey(TagOnCreateMembers.Member member, String field) {
        return member.key() == null ? "key".equalsIgnoreCase(field) : member.key().equals(field);
    }

    /**
     * Tag parameter prefixes in the order of the numbers in them, so {@code Tags.member.10} follows {@code .9},
     * however many digits a number has.
     */
    private static int byNumbers(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            int compared = left[i].matches("\\d+") && right[i].matches("\\d+")
                    ? new BigInteger(left[i]).compareTo(new BigInteger(right[i]))
                    : left[i].compareTo(right[i]);
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    private static void collect(JsonNode node, TagOnCreateMembers.Member member, int index, Map<String, String> tags) {
        if (node == null || !node.isObject()) {
            return;
        }
        List<PassedRoleMembers.Step> steps = member.steps();
        PassedRoleMembers.Step step = steps.get(index);
        JsonNode value = index == 0 ? topLevel(node, step.name()) : node.get(step.name());
        if (index == steps.size() - 1) {
            if (step.into() == PassedRoleMembers.Into.LIST && value != null && value.isArray()) {
                for (JsonNode tag : value) {
                    JsonNode key = tag.get(member.key());
                    if (key != null && key.isTextual()) {
                        JsonNode tagValue = tag.get(member.value());
                        tags.put(key.asText(), tagValue == null || tagValue.isNull() ? "" : tagValue.asText());
                    }
                }
            } else if (step.into() == PassedRoleMembers.Into.MAP && value != null && value.isObject()) {
                value.properties().forEach(tag -> tags.put(tag.getKey(), tag.getValue().isNull() ? ""
                        : tag.getValue().asText()));
            }
            return;
        }
        switch (step.into()) {
            case NONE -> collect(value, member, index + 1, tags);
            case KEY -> collect(value == null ? null : value.get(step.key()), member, index + 1, tags);
            case LIST, MAP -> {
                if (value != null && (step.into() == PassedRoleMembers.Into.LIST ? value.isArray() : value.isObject())) {
                    for (JsonNode element : value) {
                        collect(element, member, index + 1, tags);
                    }
                }
            }
        }
    }

    /**
     * The body's member {@code name}, or else one whose name differs only in case: some handlers read either,
     * CloudWatch's {@code PutMetricAlarm} takes {@code tags} for {@code Tags}, and tags they apply must not go
     * unchecked.
     */
    private static JsonNode topLevel(JsonNode body, String name) {
        JsonNode exact = body.get(name);
        if (exact != null) {
            return exact;
        }
        return body.propertyStream().filter(member -> member.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    /**
     * The condition keys a tagging check is evaluated with: each tag as {@code aws:RequestTag/<key>}, their
     * keys as {@code aws:TagKeys}, and, when the tagging action takes it, the operation as its namespace's
     * {@code CreateAction} key.
     */
    static Map<String, List<String>> conditionContext(Tagging tagging, String operation) {
        Map<String, List<String>> context = requestTagContext(tagging);
        if (tagging.createAction()) {
            String namespace = tagging.action().substring(0, tagging.action().indexOf(':'));
            context.put(namespace + ":CreateAction", List.of(operation.substring(operation.indexOf(':') + 1)));
        }
        return context;
    }

    /**
     * The request's tags as condition keys: each as {@code aws:RequestTag/<key>} and their keys as
     * {@code aws:TagKeys}. The tagging action is checked with them, and so is the operation's own action when
     * it takes them, as {@link Tagging#requestTags()} says.
     */
    static Map<String, List<String>> requestTagContext(Tagging tagging) {
        Map<String, List<String>> context = new HashMap<>();
        tagging.tags().forEach((key, value) -> context.put("aws:RequestTag/" + key, List.of(value)));
        context.put("aws:TagKeys", List.copyOf(tagging.tags().keySet()));
        return context;
    }
}
