package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Pins which {@code Fn::GetAtt} attributes declared by the CloudFormation registry schemas Floci
 * does not set.
 *
 * <p>A missing attribute is invisible at runtime: {@code resolveGetAttParts} returns the literal
 * {@code "LogicalId.Attr"} when a resource has no such attribute, so a template referencing one
 * receives that string as a value instead of failing. Recording the gaps makes the set a ratchet:
 * closing one deletes a row, and introducing one has to add a row where a reviewer sees it.
 *
 * <p>Not a parity assertion. Several rows are attributes Floci cannot set because it does not
 * emulate the underlying behaviour, and each carries the reason.
 */
class CfnSchemaCoverageTest {

    private static final Path INVENTORY =
            Path.of("src/test/resources/cloudformation/supported-resource-types.tsv");
    private static final Path GAPS =
            Path.of("src/test/resources/cloudformation/getatt-attribute-gaps.tsv");
    private static final Path SCHEMA_DIR = Path.of("local/aws/cfn-resource-schemas/us-east-1");
    private static final Path PROVISIONERS = Path.of(
            "src/main/java/io/github/hectorvent/floci/services/cloudformation/provisioners");

    private static final Pattern PUT_ATTRIBUTE =
            Pattern.compile("getAttributes\\(\\)\\.put\\(\\s*\"([^\"]+)\"");
    /** {@code private static final String TOPIC = "AWS::SNS::Topic";} */
    private static final Pattern TYPE_CONSTANT =
            Pattern.compile("String\\s+(\\w+)\\s*=\\s*\"(AWS::[^\"]+)\"");
    /** A method declared at class level, captured so its body can be walked. */
    private static final Pattern METHOD_DECLARATION = Pattern.compile(
            "\\n    (?:public|private|protected)[^\\n=;]*?\\b(\\w+)\\s*\\([^)]*\\)\\s*\\{");
    private static final Pattern METHOD_CALL = Pattern.compile("\\b(\\w+)\\s*\\(");
    private static final Pattern RESOURCE_TYPE_SWITCH =
            Pattern.compile("switch\\s*\\(\\s*\\w+\\.getResourceType\\(\\)\\s*\\)\\s*\\{");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The check needs the schema corpus, which lives under the gitignored {@code local/}. Absent in
     * CI, so this is a local correctness aid; the checked-in gaps file is what CI reviews.
     */
    private static boolean schemasAvailable() {
        return Files.isDirectory(SCHEMA_DIR);
    }

    @Test
    void recordedGapsMatchWhatTheProvisionersActuallySet() {
        if (!schemasAvailable()) {
            return;
        }
        assertSameLines(render(recordedGaps()), render(actualGaps()));
    }

    /** A row that has been fixed, or was never real, must be deleted rather than left behind. */
    @Test
    void everyRecordedGapIsStillReal() {
        if (!schemasAvailable()) {
            return;
        }
        Map<String, String> actual = actualGaps();
        List<String> stale = recordedGaps().keySet().stream()
                .filter(key -> !actual.containsKey(key))
                .sorted()
                .toList();

        if (!stale.isEmpty()) {
            throw new AssertionError("These attributes are now set, so their rows in " + GAPS
                    + " are stale and should be deleted: " + stale);
        }
    }

    @Test
    void everyRecordedGapExplainsItself() {
        List<String> unexplained = recordedGaps().entrySet().stream()
                .filter(e -> e.getValue().isBlank() || e.getValue().startsWith("TODO"))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();

        if (!unexplained.isEmpty()) {
            throw new AssertionError("A recorded gap must say why Floci does not set it, so a "
                    + "reviewer can tell 'not emulated' from 'forgotten': " + unexplained);
        }
    }

    /** "AWS::SNS::Topic\tTopicArn" -> reason. */
    private static Map<String, String> recordedGaps() {
        Map<String, String> gaps = new TreeMap<>();
        try {
            for (String line : Files.readAllLines(GAPS, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\t", 3);
                if (parts.length < 3) {
                    throw new AssertionError(
                            "Expected 'type<TAB>attribute<TAB>reason' but found: " + line);
                }
                gaps.put(parts[0] + "\t" + parts[1], parts[2]);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + GAPS, e);
        }
        return gaps;
    }

    /** The gaps computed from the schemas and the provisioner sources, keyed the same way. */
    private static Map<String, String> actualGaps() {
        Map<String, ProvisionerScan> scans = scanProvisioners();
        Map<String, String> gaps = new TreeMap<>();
        for (Map.Entry<String, String> entry : inventory().entrySet()) {
            String type = entry.getKey();
            String owner = entry.getValue();
            if ("LEGACY_SWITCH".equals(owner)) {
                // The switch is being dismantled type by type; its arms are audited as they move.
                continue;
            }
            Set<String> declared = schemaReadOnlyAttributes(type);
            ProvisionerScan scan = scans.get(owner);
            Set<String> set = scan == null ? Set.of() : scan.attributesFor(type);
            for (String attribute : declared) {
                if (!set.contains(attribute)) {
                    gaps.put(type + "\t" + attribute, "");
                }
            }
        }
        return gaps;
    }

    /**
     * What one provisioner class sets, resolved per resource type rather than per class.
     *
     * <p>Per type is the whole point. A class serving several types writes each type's attributes in
     * its own arm of the {@code switch (r.getResourceType())} in {@code provision}, so folding the
     * whole file into one set credits every type with what any one arm sets. That is not a
     * conservative approximation, it is a false negative in the only direction that matters here:
     * the gap this file exists to record stops being reported. It hid a real one, {@code
     * AWS::EC2::NetworkAclEntry}'s {@code Id}, which {@code Ec2NetworkAclCfnProvisioner} sets for
     * {@code NetworkAcl} and never for {@code NetworkAclEntry}.
     *
     * <p>{@code byType} is empty when {@code provision} has no such switch, which means the class
     * has a single provisioning path shared by every type it owns (as
     * {@code Ec2SecurityGroupRuleCfnProvisioner} does, branching on a boolean instead). There
     * {@code classWide} is the accurate answer, not a fallback. When the switch does exist, every
     * type the class owns must have an arm, and {@link #attributesFor} fails loudly otherwise rather
     * than silently reverting to the class-wide reading.
     */
    private record ProvisionerScan(String className, Set<String> classWide,
                                   Map<String, Set<String>> byType) {

        Set<String> attributesFor(String resourceType) {
            if (byType.isEmpty()) {
                return classWide;
            }
            Set<String> attributes = byType.get(resourceType);
            if (attributes == null) {
                throw new AssertionError(className + " switches on getResourceType() but has no arm "
                        + "for " + resourceType + ", so its attributes cannot be attributed to that "
                        + "type. Add an arm, or give the class one shared provisioning path.");
            }
            return attributes;
        }
    }

    private static Map<String, ProvisionerScan> scanProvisioners() {
        Map<String, ProvisionerScan> scans = new TreeMap<>();
        try (var files = Files.list(PROVISIONERS)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith("CfnProvisioner.java"))
                    .sorted()
                    .toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                String className = file.getFileName().toString().replace(".java", "");
                scans.put(className, scan(className, source));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot scan " + PROVISIONERS, e);
        }
        return scans;
    }

    private static ProvisionerScan scan(String className, String source) {
        Set<String> classWide = new LinkedHashSet<>();
        Matcher put = PUT_ATTRIBUTE.matcher(source);
        while (put.find()) {
            classWide.add(put.group(1));
        }

        Map<String, String> methods = methodBodies(source);
        Map<String, String> constants = new HashMap<>();
        Matcher constant = TYPE_CONSTANT.matcher(source);
        while (constant.find()) {
            constants.put(constant.group(1), constant.group(2));
        }

        Map<String, Set<String>> byType = new TreeMap<>();
        String provision = methods.get("provision");
        if (provision != null) {
            Dispatch dispatch = dispatchIn(provision);
            if (dispatch != null) {
                // Anything provision() writes outside the switch is on the path every type takes,
                // so it belongs to all of them. Ec2SecurityGroupRule sets Id there.
                Set<String> shared = attributesReachableFrom(
                        provision.replace(dispatch.block(), ""), methods, new HashSet<>());
                for (Map.Entry<String, String> arm : dispatch.arms().entrySet()) {
                    for (String label : arm.getKey().split(",")) {
                        String token = label.trim();
                        String type = token.startsWith("\"")
                                ? token.substring(1, token.length() - 1)
                                : constants.get(token);
                        if (type != null) {
                            Set<String> attributes = byType.computeIfAbsent(
                                    type, t -> new LinkedHashSet<>());
                            attributes.addAll(shared);
                            attributes.addAll(attributesReachableFrom(
                                    arm.getValue(), methods, new HashSet<>()));
                        }
                    }
                }
            }
        }
        return new ProvisionerScan(className, Set.copyOf(classWide), Map.copyOf(byType));
    }

    /** The {@code getResourceType()} dispatch inside {@code provision}: its whole block, and its arms. */
    private record Dispatch(String block, Map<String, String> arms) { }

    /**
     * The dispatch switch in {@code provision}, or null when there is none.
     *
     * <p>Only a switch in <em>statement</em> position counts. A switch <em>expression</em> such as
     * {@code boolean ingress = switch (r.getResourceType()) { ... }} selects a value on a single
     * shared path, it does not split the method into a path per type, and reading its arms as
     * per-type paths attributes nothing to either type. That is not hypothetical:
     * {@code Ec2SecurityGroupRuleCfnProvisioner} does exactly this and sets {@code Id} after the
     * switch, so treating it as a dispatch reported two gaps that are not real.
     */
    private static Dispatch dispatchIn(String provisionBody) {
        Matcher header = RESOURCE_TYPE_SWITCH.matcher(provisionBody);
        while (header.find()) {
            String before = provisionBody.substring(0, header.start()).stripTrailing();
            if (!before.isEmpty() && ";{}".indexOf(before.charAt(before.length() - 1)) < 0) {
                continue;
            }
            String body = balancedBlock(provisionBody, header.end() - 1);
            return new Dispatch(body, armsOf(body));
        }
        return null;
    }

    private static Map<String, String> armsOf(String body) {

        Map<String, String> arms = new LinkedHashMap<>();
        Matcher label = Pattern.compile("\\bcase\\s+([^>]+?)\\s*->").matcher(body);
        List<int[]> starts = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        while (label.find()) {
            starts.add(new int[] {label.start(), label.end()});
            labels.add(label.group(1));
        }
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i)[1];
            int to = i + 1 < starts.size() ? starts.get(i + 1)[0] : body.length();
            arms.put(labels.get(i), body.substring(from, to));
        }
        return arms;
    }

    /** Attributes written in {@code code}, plus those written by same-class methods it calls. */
    private static Set<String> attributesReachableFrom(String code, Map<String, String> methods,
                                                       Set<String> visited) {
        Set<String> attributes = new LinkedHashSet<>();
        Matcher put = PUT_ATTRIBUTE.matcher(code);
        while (put.find()) {
            attributes.add(put.group(1));
        }
        Matcher call = METHOD_CALL.matcher(code);
        while (call.find()) {
            String callee = call.group(1);
            if (methods.containsKey(callee) && visited.add(callee)) {
                attributes.addAll(attributesReachableFrom(methods.get(callee), methods, visited));
            }
        }
        return attributes;
    }

    private static Map<String, String> methodBodies(String source) {
        Map<String, String> bodies = new HashMap<>();
        Matcher declaration = METHOD_DECLARATION.matcher(source);
        while (declaration.find()) {
            bodies.put(declaration.group(1), balancedBlock(source, declaration.end() - 1));
        }
        return bodies;
    }

    /** The text between {@code source[open]}, which must be '{', and its matching brace. */
    private static String balancedBlock(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(open + 1, i);
            }
        }
        return source.substring(open);
    }

    /** Top-level read-only properties only: a nested pointer is not a GetAtt attribute name. */
    private static Set<String> schemaReadOnlyAttributes(String resourceType) {
        Path schema = SCHEMA_DIR.resolve(
                resourceType.replace("::", "-").toLowerCase(Locale.ROOT) + ".json");
        if (!Files.exists(schema)) {
            return Set.of();
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(schema, StandardCharsets.UTF_8));
            Set<String> attributes = new LinkedHashSet<>();
            for (JsonNode pointer : root.path("readOnlyProperties")) {
                String[] parts = pointer.asText().split("/");
                if (parts.length == 3) {
                    attributes.add(parts[2]);
                }
            }
            return attributes;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + schema, e);
        }
    }

    private static Map<String, String> inventory() {
        Map<String, String> types = new TreeMap<>();
        try {
            for (String line : Files.readAllLines(INVENTORY, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    String[] parts = line.split("\t", 2);
                    types.put(parts[0], parts[1]);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + INVENTORY, e);
        }
        return types;
    }

    private static String render(Map<String, String> gaps) {
        return gaps.keySet().stream().sorted().collect(Collectors.joining("\n"));
    }

    private static void assertSameLines(String recorded, String actual) {
        if (recorded.equals(actual)) {
            return;
        }
        List<String> recordedLines = List.of(recorded.split("\n"));
        List<String> actualLines = List.of(actual.split("\n"));
        List<String> added = new ArrayList<>(actualLines);
        added.removeAll(recordedLines);
        List<String> removed = new ArrayList<>(recordedLines);
        removed.removeAll(actualLines);
        throw new AssertionError("The set of unset schema attributes changed.\n"
                + "  New gaps (a provisioner stopped setting a declared attribute, or a new type "
                + "arrived with one unset) — set them, or add a row with a reason to " + GAPS + ":\n    "
                + String.join("\n    ", added.isEmpty() ? List.of("(none)") : added)
                + "\n  Closed gaps (now set) — delete their rows from " + GAPS + ":\n    "
                + String.join("\n    ", removed.isEmpty() ? List.of("(none)") : removed));
    }
}
