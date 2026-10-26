package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.container.ContainerRequestContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *
 * <p>The tags are read as the Floci handler that applies them reads them, so the check sees the tags the
 * resource gets: a form's tag list from 1 to the first number with no key, under the spellings the
 * handler reads, and a body's member under its own name. Each resource's tags are a set of their own,
 * checked apart: a member's, or a tag specification's for each type of resource it names.
 */
final class TagOnCreate {

    /** Operations the enforcement filter checks itself: CloudWatch Logs takes either of two tagging actions. */
    static final Set<String> BY_HAND = Set.of("logs:CreateLogGroup");

    /** CloudWatch's handler reads an alarm's tags from {@code tags} when the body has no {@code Tags}. */
    private static final Set<String> LOWER_CASE_TAGS = Set.of("cloudwatch:PutMetricAlarm", "cloudwatch:PutCompositeAlarm");

    /**
     * CodeDeploy's handler reads a tag as {@code Key} and {@code Value}, or as {@code key} and {@code value} when it
     * has no {@code Key}.
     */
    private static final Set<String> EITHER_CASE_TAGS = Set.of("codedeploy");

    /**
     * The tags one resource gets, and the type of that resource where a tag specification names it, else null.
     */
    record TagSet(String resourceType, Map<String, String> tags) {
    }

    /**
     * The tagging action a request needs, each set of tags it attaches as its handler reads them, the further
     * sets it carries under spellings another handler might read, whether the action takes the CreateAction key,
     * and whether the operation's own action takes the tags as well.
     */
    record Tagging(String action, List<TagSet> sets, List<TagSet> otherSpellings, boolean createAction,
                   boolean requestTags) {

        /** Every set the tagging action is checked for, so no tag a handler might apply goes unchecked. */
        List<TagSet> checked() {
            List<TagSet> checked = new ArrayList<>(sets);
            checked.addAll(otherSpellings);
            return checked;
        }
    }

    /**
     * How a Floci Query handler numbers a tag list: from 1 to the first number with no key, every number there
     * is, or from 1 to the first number at which none of the prefixes has a key or a value.
     */
    private enum Numbering { TO_FIRST_GAP, EVERY, EITHER_AT_EACH }

    /** The prefixes a handler numbers a tag list under, in the order it reads them, and its tags' key and value. */
    private record Reading(List<String> prefixes, Numbering numbering, String key, String value) {
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
        Set<TagSet> sets = new LinkedHashSet<>();
        Set<TagSet> otherSpellings = new LinkedHashSet<>();
        for (TagOnCreateMembers.Member member : entry.members()) {
            if (!form.isEmpty()) {
                if (member.inForm()) {
                    sets.addAll(fromForm(form, operation, member));
                }
                otherSpellings.addAll(anySpellingInForm(form, member));
            } else if (body != null && body.isObject()) {
                Map<String, Map<String, String>> byType = new LinkedHashMap<>();
                collect(topLevel(body, operation, member.steps().getFirst().name()), member,
                        EITHER_CASE_TAGS.contains(operation.substring(0, operation.indexOf(':'))), 0, null, byType);
                byType.forEach((type, tags) -> add(sets, type, tags));
                otherSpellings.addAll(anySpellingInBody(body, member));
            }
        }
        // A set already in one the handler reads, of the same resource type, adds nothing to check.
        otherSpellings.removeIf(other -> sets.stream().anyMatch(set -> Objects.equals(set.resourceType(),
                other.resourceType()) && set.tags().entrySet().containsAll(other.tags().entrySet())));
        return sets.isEmpty() && otherSpellings.isEmpty() ? Optional.empty()
                : Optional.of(new Tagging(entry.tagging(), List.copyOf(sets), List.copyOf(otherSpellings),
                        entry.createAction(), entry.requestTags()));
    }

    /**
     * The tags a form carries at the member under any spelling a handler might read: its names in any case and
     * a trailing {@code s} dropped, any one word between a list's name and its numbers, every number, and the
     * tag's key and value in any case. One set for each spelling, and for a tag specification each resource type.
     */
    private static List<TagSet> anySpellingInForm(Map<String, String> form, TagOnCreateMembers.Member member) {
        List<PassedRoleMembers.Step> steps = member.querySteps();
        if (!member.inForm()) {
            return List.of();
        }
        String key = member.key() == null ? "key" : member.key();
        String value = member.value() == null ? "value" : member.value();
        String list = "(?:\\.[A-Za-z]+)?\\.\\d+";
        Pattern name = Pattern.compile(steps.size() == 1
                ? "(?<tag>(?<spelling>" + anyCase(steps.getFirst().name()) + "(?:\\.[A-Za-z]+)?)\\.\\d+)\\.(?<field>(?i:"
                        + Pattern.quote(key) + "|" + Pattern.quote(value) + "))"
                : "(?<tag>(?<specification>" + anyCase(steps.getFirst().name()) + list + ")\\.(?<spelling>"
                        + anyCase(steps.get(1).name()) + "(?:\\.[A-Za-z]+)?)\\.\\d+)\\.(?<field>(?i:"
                        + Pattern.quote(key) + "|" + Pattern.quote(value) + "))");
        Map<String, Map<String, String>> sets = new LinkedHashMap<>();
        Map<String, String[]> tags = new LinkedHashMap<>();
        Map<String, String> setOfTag = new HashMap<>();
        form.forEach((parameter, given) -> {
            Matcher matcher = name.matcher(parameter);
            if (!matcher.matches()) {
                return;
            }
            String set = matcher.group("spelling").toLowerCase(Locale.ROOT);
            if (steps.size() == 2) {
                String specification = matcher.group("specification");
                String type = member.resourceType() == null ? null : form.entrySet().stream()
                        .filter(e -> e.getKey().equalsIgnoreCase(specification + "." + member.resourceType()))
                        .map(Map.Entry::getValue).findFirst().orElse(null);
                set = specification.replaceAll("\\.\\d+$", "").toLowerCase(Locale.ROOT) + "|" + set + "|" + type;
            }
            String tag = matcher.group("tag").toLowerCase(Locale.ROOT);
            setOfTag.put(tag, set);
            tags.computeIfAbsent(tag, ignored -> new String[2])[matcher.group("field").equalsIgnoreCase(key) ? 0 : 1] = given;
        });
        tags.forEach((tag, pair) -> {
            if (pair[0] != null) {
                sets.computeIfAbsent(setOfTag.get(tag), ignored -> new LinkedHashMap<>())
                        .put(pair[0], pair[1] == null ? "" : pair[1]);
            }
        });
        List<TagSet> found = new ArrayList<>();
        sets.forEach((set, read) -> {
            String type = steps.size() == 2 ? set.substring(set.lastIndexOf('|') + 1) : null;
            found.add(new TagSet("null".equals(type) ? null : type, Collections.unmodifiableMap(read)));
        });
        return found;
    }

    /** The name in any case, and without a trailing {@code s} too, as {@code Tag.N} stands for {@code Tags}. */
    private static String anyCase(String name) {
        String stem = name.endsWith("s") ? name.substring(0, name.length() - 1) : name;
        return "(?i:" + Pattern.quote(name) + (stem.equals(name) ? "" : "|" + Pattern.quote(stem)) + ")";
    }

    /**
     * The tags a body carries at the member under any spelling a handler might read: its names, and a tag's
     * key and value, in any case, and a list or a map whichever the member is. One set for each top-level
     * spelling, and for a tag specification each resource type.
     */
    private static List<TagSet> anySpellingInBody(JsonNode body, TagOnCreateMembers.Member member) {
        List<TagSet> found = new ArrayList<>();
        body.properties().forEach(top -> {
            if (!top.getKey().equalsIgnoreCase(member.steps().getFirst().name())) {
                return;
            }
            Map<String, Map<String, String>> byType = new LinkedHashMap<>();
            anySpelling(top.getValue(), member, 0, null, byType);
            byType.forEach((type, tags) -> {
                if (!tags.isEmpty()) {
                    found.add(new TagSet(type, Collections.unmodifiableMap(tags)));
                }
            });
        });
        return found;
    }

    private static void anySpelling(JsonNode value, TagOnCreateMembers.Member member, int index, String type,
                                    Map<String, Map<String, String>> byType) {
        List<PassedRoleMembers.Step> steps = member.steps();
        if (value == null) {
            return;
        }
        if (index == steps.size() - 1) {
            Map<String, String> tags = byType.computeIfAbsent(type, ignored -> new LinkedHashMap<>());
            // Every element is a tag, its key as text whatever it holds and a missing one empty, as a handler that
            // reads it with asText takes it; an object's values that are structures are elements too, as a handler
            // iterating it reads them, and its members are a map's tags.
            for (JsonNode tag : value) {
                if (value.isObject() && !tag.isObject()) {
                    continue;
                }
                JsonNode key = field(tag, member.key() == null ? "key" : member.key());
                tags.put(key == null || key.isNull() ? "" : key.asText(),
                        text(field(tag, member.value() == null ? "value" : member.value())));
            }
            if (value.isObject()) {
                value.properties().forEach(tag -> tags.put(tag.getKey(), tag.getValue().isNull() ? ""
                        : tag.getValue().asText()));
            }
            return;
        }
        String next = steps.get(index + 1).name();
        if (value.isArray()) {
            for (JsonNode element : value) {
                JsonNode kind = member.resourceType() == null ? null : field(element, member.resourceType());
                anySpelling(field(element, next), member, index + 1,
                        kind != null && kind.isTextual() ? kind.asText() : type, byType);
            }
        } else if (value.isObject()) {
            anySpelling(field(step(value, steps.get(index)), next), member, index + 1, type, byType);
        }
    }

    /** A structure under a step that names a map's key, or the structure itself. */
    private static JsonNode step(JsonNode value, PassedRoleMembers.Step step) {
        return step.into() == PassedRoleMembers.Into.KEY ? field(value, step.key()) : value;
    }

    /** A tag's value as text, empty when it has none. */
    private static String text(JsonNode value) {
        return value == null || value.isNull() ? "" : value.asText();
    }

    /** The object's member {@code name} in any case; null for no such member, or no object. */
    private static JsonNode field(JsonNode node, String name) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode exact = node.get(name);
        return exact != null ? exact : node.propertyStream().filter(member -> member.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private static void add(Set<TagSet> sets, String type, Map<String, String> tags) {
        if (!tags.isEmpty()) {
            sets.add(new TagSet(type, Collections.unmodifiableMap(tags)));
        }
    }

    /**
     * The sets of tags a form carries at the member, as each handler that may serve the operation reads it: a
     * tag list's tags, or each tag specification's, from 1 to the first without a resource type, by its type.
     */
    private static List<TagSet> fromForm(Map<String, String> form, String operation, TagOnCreateMembers.Member member) {
        boolean ec2 = operation.startsWith("ec2:");
        List<PassedRoleMembers.Step> steps = member.querySteps();
        Set<TagSet> sets = new LinkedHashSet<>();
        for (Reading reading : readings(operation, member, ec2)) {
            if (steps.size() == 1) {
                Map<String, String> tags = new LinkedHashMap<>();
                read(form, reading, tags);
                add(sets, null, tags);
                continue;
            }
            Map<String, Map<String, String>> byType = new LinkedHashMap<>();
            for (String specifications : reading.prefixes()) {
                for (int i = 1; ; i++) {
                    String specification = specifications + "." + i;
                    String type = member.resourceType() == null ? null
                            : form.get(specification + "." + member.resourceType());
                    if (member.resourceType() != null ? type == null : noneUnder(form, specification)) {
                        break;
                    }
                    read(form, gap(member, specification + "." + sdkPrefix(steps.get(1), ec2)),
                            byType.computeIfAbsent(type, ignored -> new LinkedHashMap<>()));
                }
            }
            byType.forEach((type, tags) -> add(sets, type, tags));
        }
        return List.copyOf(sets);
    }

    /**
     * How the Floci Query handler that serves the operation reads the member's tags. Most read the SDK's own
     * spelling, from 1 to the first number with no key; these read more, each as its handler does. Several
     * handlers serve the {@code rds} namespace and read its spellings in different orders, so each order is
     * read, and each set it gives is checked.
     */
    private static List<Reading> readings(String operation, TagOnCreateMembers.Member member, boolean ec2) {
        PassedRoleMembers.Step first = member.querySteps().getFirst();
        return switch (operation.substring(0, operation.indexOf(':') + 1) + first.name()) {
            // Ec2QueryHandler.parseTagsForResource
            case "ec2:TagSpecification", "ec2:TagSpecifications" ->
                    List.of(gap(member, "TagSpecification", "TagSpecifications"));
            // SqsQueryHandler
            case "sqs:Tag" -> List.of(new Reading(List.of("Tag"), Numbering.TO_FIRST_GAP, "Key", "Value"));
            // RdsQueryHandler and DocDbQueryHandler, then NeptuneQueryHandler
            case "rds:Tags" -> List.of(gap(member, "Tags.member", "Tags.Tag", "Tag"),
                    gap(member, "Tags.Tag", "Tags.member", "Tag"));
            // ElastiCacheServerlessQueryHandler, else ElastiCacheQueryHandler
            case "elasticache:Tags" -> "elasticache:CreateServerlessCache".equals(operation)
                    ? List.of(new Reading(List.of("Tags.Tag", "Tags.member"), Numbering.EITHER_AT_EACH,
                            member.key(), member.value()))
                    : List.of(gap(member, "Tags.Tag", "Tags.member", "Tag"));
            // RedshiftQueryHandler
            case "redshift:Tags" -> List.of(gap(member, "Tags.member", "Tags.Tag"));
            case "redshift:TagList" -> List.of(new Reading(List.of("TagList.Tag"), Numbering.EVERY,
                    member.key(), member.value()));
            default -> List.of(gap(member, sdkPrefix(first, ec2)));
        };
    }

    private static Reading gap(TagOnCreateMembers.Member member, String... prefixes) {
        return new Reading(List.of(prefixes), Numbering.TO_FIRST_GAP,
                member.key() == null ? "key" : member.key(), member.value() == null ? "value" : member.value());
    }

    /**
     * The prefix an SDK numbers a list or a map under: EC2 numbers a list under its name, the Query protocol
     * under the name its shape gives elements, or {@code member}, and a map's entries under {@code entry}.
     */
    private static String sdkPrefix(PassedRoleMembers.Step step, boolean ec2) {
        if (step.into() == PassedRoleMembers.Into.MAP) {
            return step.name() + ".entry";
        }
        return ec2 ? step.name() : step.name() + "." + (step.key().isEmpty() ? "member" : step.key());
    }

    private static boolean noneUnder(Map<String, String> form, String prefix) {
        return form.keySet().stream().noneMatch(name -> name.startsWith(prefix + "."));
    }

    /** The tags the form numbers under the reading's prefixes, a tag with no value taking an empty one. */
    private static void read(Map<String, String> form, Reading reading, Map<String, String> tags) {
        switch (reading.numbering()) {
            case TO_FIRST_GAP -> {
                for (String prefix : reading.prefixes()) {
                    for (int i = 1; ; i++) {
                        String key = form.get(prefix + "." + i + "." + reading.key());
                        if (key == null) {
                            break;
                        }
                        tags.put(key, form.getOrDefault(prefix + "." + i + "." + reading.value(), ""));
                    }
                }
            }
            case EVERY -> {
                for (String prefix : reading.prefixes()) {
                    Pattern name = Pattern.compile(Pattern.quote(prefix) + "\\.\\d+\\." + Pattern.quote(reading.key()));
                    form.forEach((parameter, key) -> {
                        if (name.matcher(parameter).matches() && key != null && !key.isBlank()) {
                            String stem = parameter.substring(0, parameter.length() - reading.key().length());
                            tags.put(key, form.getOrDefault(stem + reading.value(), ""));
                        }
                    });
                }
            }
            case EITHER_AT_EACH -> {
                for (int i = 1; ; i++) {
                    String key = null;
                    String value = null;
                    for (String prefix : reading.prefixes()) {
                        key = form.get(prefix + "." + i + "." + reading.key());
                        value = form.get(prefix + "." + i + "." + reading.value());
                        if (key != null || value != null) {
                            break;
                        }
                    }
                    if (key == null || key.isBlank()) {
                        break;
                    }
                    tags.put(key, value == null ? "" : value);
                }
            }
        }
    }

    /**
     * The tags a body carries at the member, by the resource type of the tag specification they are in, null
     * outside one.
     */
    private static void collect(JsonNode value, TagOnCreateMembers.Member member, boolean eitherCase, int index,
                                String type, Map<String, Map<String, String>> byType) {
        List<PassedRoleMembers.Step> steps = member.steps();
        PassedRoleMembers.Step step = steps.get(index);
        if (index == steps.size() - 1) {
            Map<String, String> tags = byType.computeIfAbsent(type, ignored -> new LinkedHashMap<>());
            // CodeDeployJsonHandler iterates the member whatever it holds, which for an object is its values.
            if (step.into() == PassedRoleMembers.Into.LIST && value != null
                    && (value.isArray() || eitherCase && value.isObject())) {
                for (JsonNode tag : value) {
                    if (eitherCase) {
                        // As CodeDeployJsonHandler reads it: as text whatever it holds, a missing key an empty one.
                        boolean lower = !tag.has(member.key());
                        tags.put(tag.path(lower ? member.key().toLowerCase(Locale.ROOT) : member.key()).asText(),
                                tag.path(lower ? member.value().toLowerCase(Locale.ROOT) : member.value()).asText());
                        continue;
                    }
                    JsonNode key = tag.get(member.key());
                    if (key != null && key.isValueNode() && !key.isNull()) {
                        tags.put(key.asText(), text(tag.get(member.value())));
                    }
                }
            } else if (step.into() == PassedRoleMembers.Into.MAP && value != null && value.isObject()) {
                value.properties().forEach(tag -> tags.put(tag.getKey(), tag.getValue().isNull() ? ""
                        : tag.getValue().asText()));
            }
            return;
        }
        if (value == null || !value.isContainerNode()) {
            return;
        }
        String next = steps.get(index + 1).name();
        switch (step.into()) {
            case NONE -> collect(value.get(next), member, eitherCase, index + 1, type, byType);
            case KEY -> collect(value.path(step.key()).get(next), member, eitherCase, index + 1, type, byType);
            case LIST, MAP -> {
                if (step.into() == PassedRoleMembers.Into.LIST ? value.isArray() : value.isObject()) {
                    for (JsonNode element : value) {
                        JsonNode kind = member.resourceType() == null ? null : element.get(member.resourceType());
                        collect(element.get(next), member, eitherCase, index + 1,
                                kind != null && kind.isTextual() ? kind.asText() : type, byType);
                    }
                }
            }
        }
    }

    /** The body's member {@code name}, or CloudWatch's {@code tags} where its handler reads an alarm's tags from it. */
    private static JsonNode topLevel(JsonNode body, String operation, String name) {
        if (!body.has(name) && "Tags".equals(name) && LOWER_CASE_TAGS.contains(operation)) {
            return body.get("tags");
        }
        return body.get(name);
    }

    /**
     * The condition keys a set of tags is checked with on the tagging action: each tag as
     * {@code aws:RequestTag/<key>}, their keys as {@code aws:TagKeys}, and, when the tagging action takes it,
     * the operation as its namespace's {@code CreateAction} key.
     */
    static Map<String, List<String>> conditionContext(Tagging tagging, TagSet set, String operation) {
        Map<String, List<String>> context = requestTagContext(set);
        if (tagging.createAction()) {
            String namespace = tagging.action().substring(0, tagging.action().indexOf(':'));
            context.put(namespace + ":CreateAction", List.of(operation.substring(operation.indexOf(':') + 1)));
        }
        return context;
    }

    /**
     * The request's tags as condition keys, once for each reading of them: each tag as {@code aws:RequestTag/<key>}
     * and their keys as {@code aws:TagKeys}. The operation's own action is checked with each when it takes them, as
     * {@link Tagging#requestTags()} says, and refused if any is denied: the sets its handler reads, the sets under
     * any other spelling, and no tags where its handler reads none. So a deny on a tag holds whichever reading a
     * handler applies, and a create that requires a tag gets it only where the handler applies it.
     */
    static List<Map<String, List<String>>> requestTagContexts(Tagging tagging) {
        List<Map<String, List<String>>> contexts = new ArrayList<>();
        if (tagging.sets().isEmpty() && !tagging.otherSpellings().isEmpty()) {
            // The handler applies none of the tags it reads, so a create that requires a tag does not get it.
            contexts.add(new HashMap<>());
        }
        for (TagSet set : tagging.checked()) {
            contexts.add(requestTagContext(set));
        }
        return contexts;
    }

    private static Map<String, List<String>> requestTagContext(TagSet set) {
        Map<String, List<String>> context = new HashMap<>();
        set.tags().forEach((key, value) -> context.put("aws:RequestTag/" + key, List.of(value)));
        context.put("aws:TagKeys", List.copyOf(set.tags().keySet()));
        return context;
    }

    /**
     * The resources a set of tags goes to: for a tag specification's, the ARN the table gives its type, in the
     * request's partition, Region and account, as AWS checks the tagging action on the resource being tagged;
     * otherwise {@code resources}, the ones the operation itself is checked on.
     */
    static List<String> resources(String operation, TagSet set, String partition, String region, String accountId,
                                  List<String> resources) {
        if (set.resourceType() == null) {
            return resources;
        }
        String arn = TagOnCreateMembers.table().resourceArns()
                .getOrDefault(operation.substring(0, operation.indexOf(':')), Map.of()).get(set.resourceType());
        return arn == null ? resources : List.of(arn.replace("${Partition}", partition)
                .replace("${Region}", region).replace("${Account}", accountId));
    }
}
