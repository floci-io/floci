package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnResourceProvisioner;
import io.github.hectorvent.floci.testing.MainSources;
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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Pins the two halves of what the CloudFormation registry schemas declare and Floci does not
 * implement: the {@code Fn::GetAtt} attributes it does not set, and the writable properties its
 * provisioners never read.
 *
 * <p>A missing attribute is invisible at runtime: {@code resolveGetAttParts} returns the literal
 * {@code "LogicalId.Attr"} when a resource has no such attribute, so a template referencing one
 * receives that string as a value instead of failing. An ignored property is invisible the same
 * way: the template validates and the value goes nowhere. Recording the gaps makes each set a
 * ratchet: closing one deletes a row, and introducing one has to add a row where a reviewer sees it.
 *
 * <p>Not a parity assertion. Several rows are attributes Floci cannot set because it does not
 * emulate the underlying behaviour, and each carries the reason. The property rows start as
 * {@code accepted and ignored} unless the service docs already describe the omission, and only
 * shrink from there.
 *
 * <p>Known limit: a property counts as read when its name appears as a literal in a read call or in
 * a helper's arguments in the type's reachable code, so the scan can credit a property that is not
 * applied. It is a ratchet on what the sources mention, not proof that a value takes effect.
 */
class CfnSchemaCoverageTest {

    private static final Path INVENTORY =
            Path.of("src/test/resources/cloudformation/supported-resource-types.tsv");
    private static final Path GAPS =
            Path.of("src/test/resources/cloudformation/getatt-attribute-gaps.tsv");
    private static final Path PROPERTY_GAPS =
            Path.of("src/test/resources/cloudformation/writable-property-gaps.tsv");
    private static final Path SCHEMA_DIR = Path.of("local/aws/cfn-resource-schemas/us-east-1");
    private static final Path PROVISIONERS = MainSources.packageDir(CfnResourceProvisioner.class);

    /**
     * Inventory types with no registry schema, the same set as {@code TYPES_WITHOUT_SCHEMA} in
     * {@link CfnResourceInventoryTest} plus {@code Custom::DynamoDBReplica}, a {@code Custom::} type
     * with no registry schema. Any other type without a schema file fails the scan by name.
     */
    private static final Set<String> TYPES_WITHOUT_SCHEMA =
            Set.of("AWS::CDK::Metadata", "Custom::DynamoDBReplica");

    private static final Pattern PUT_ATTRIBUTE =
            Pattern.compile("getAttributes\\(\\)\\.put\\(\\s*\"([^\"]+)\"");
    /** {@code private static final String TOPIC = "AWS::SNS::Topic";} */
    private static final Pattern TYPE_CONSTANT =
            Pattern.compile("String\\s+(\\w+)\\s*=\\s*\"(AWS::[^\"]+)\"");
    /** A method declared at class level, captured so its body can be walked. */
    private static final Pattern METHOD_DECLARATION = Pattern.compile(
            "\\n    (?:public|private|protected)[^\\n=;]*?\\b(\\w+)\\s*\\([^)]*\\)\\s*\\{");
    private static final Pattern METHOD_CALL = Pattern.compile("\\b(\\w+)\\s*\\(");
    /** A call on the class itself: not {@code other.method(}, which may be a same-named method elsewhere. */
    private static final Pattern OWN_METHOD_CALL = Pattern.compile("(?<![\\w.])(\\w+)\\s*\\(");
    private static final Pattern RESOURCE_TYPE_SWITCH =
            Pattern.compile("switch\\s*\\(\\s*\\w+\\.getResourceType\\(\\)\\s*\\)\\s*\\{");
    /**
     * A property name given as a string literal to a call that reads it: {@code props.get("X")},
     * {@code has}/{@code hasNonNull}/{@code path}, any {@code ProvisionContext} {@code resolve*}
     * method ({@code ctx.resolveOptional(props, "X")}), or a helper of the provisioner handed a
     * node first, whose literal arguments are names ({@code parseIntProp(props, "MinSize",
     * ctx, 0)}, {@code putResolvedText(req, "state", props, "State", ctx)}). A name, or a list of
     * names, held in a {@code static final} constant of the class is substituted first, see
     * {@link #inlineConstants}. Names listed in a {@link #PROPERTY_LOOP} count too: a property the
     * loop rejects is handled, not silently ignored.
     */
    private static final Pattern PROPERTY_READ = Pattern.compile(
            "\\b(?:resolve\\w*|has|hasNonNull|get|path)\\("
                    + "\\s*(?:[\\w.]+(?:\\([^()]*\\))?\\s*,\\s*)?\"(\\w+)\"");
    /** A call of a lowercase-named helper whose first argument is a node or variable, not a literal. */
    private static final Pattern PROPERTY_HELPER_CALL = Pattern.compile(
            "\\b[a-z]\\w*\\((?=\\s*[\\w.]+(?:\\([^()]*\\))?\\s*,)");
    /** {@code for (String name : List.of("BatchSize", "FilterCriteria")) { ... props.has(name) ... }} */
    private static final Pattern PROPERTY_LOOP = Pattern.compile(
            "for\\s*\\(\\s*String\\s+\\w+\\s*:\\s*(?:List|Set)\\.of\\(([^)]*)\\)");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(\\w+)\"");
    /** {@code private static final String API_ID = "ApiId";} */
    private static final Pattern NAME_CONSTANT =
            Pattern.compile("static\\s+final\\s+String\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*(\"[A-Za-z0-9]+\")\\s*;");
    /**
     * {@code private static final List<String> SCALAR_ATTRIBUTES = List.of("DelaySeconds", ...);}
     * A list the class itself names {@code UNSUPPORTED} or {@code NOT_STORED} is a list of properties
     * it warns about and drops, so it is not read credit.
     */
    private static final Pattern NAME_LIST_CONSTANT = Pattern.compile(
            "static\\s+final\\s+(?:List|Set)<String>\\s+"
                    + "((?![A-Z0-9_]*(?:UNSUPPORTED|NOT_STORED))[A-Z][A-Z0-9_]*)\\s*=\\s*"
                    + "((?:List|Set)\\.of\\([^;]*\\))\\s*;");
    /**
     * Properties read by a helper class outside the provisioner source, credited to every type whose
     * reachable code calls the helper. Explicit on purpose: a row must never say "read elsewhere".
     * {@code OpenApiDocuments.resolve} reads {@code Body} and {@code BodyS3Location}.
     */
    private static final Map<String, Set<String>> HELPER_READS = Map.of(
            "OpenApiDocuments.resolve(", Set.of("Body", "BodyS3Location"));
    /**
     * Calls that hand the whole resolved property object to the service request, so every writable
     * property of the type counts as read without a name appearing in the source. Explicit, one entry
     * per helper: {@code camelisedProperties} (AppSync), {@code lowerKeys} over the resolved node
     * (CodeBuild, CodePipeline), {@code jsonObjectToMap} over it (Cognito user pool), {@code
     * toCamelCase} over it (Lambda web), {@code requestBody} (IoT domain configuration).
     */
    private static final Set<String> WHOLESALE_CALLS = Set.of(
            "camelisedProperties(props, ctx)",
            "lowerKeys(ctx.engine().resolveNode(props))",
            "jsonObjectToMap(ctx.engine().resolveNode(props))",
            "toCamelCase(ctx.engine().resolveNodeOmittingNoValue(props)",
            "toCamelCase(resolved,",
            "requestBody(props, ctx)");
    private static final String ALL_PROPERTIES = "*";
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

    @Test
    void recordedPropertyGapsMatchWhatTheProvisionersActuallyRead() {
        if (!schemasAvailable()) {
            return;
        }
        assertSameLines(render(recordedPropertyGaps()), render(actualPropertyGaps()),
                "The set of ignored writable schema properties changed.",
                "(a provisioner stopped reading a declared property, or a new type arrived with one "
                        + "it ignores): read it, or add a row with a reason to " + PROPERTY_GAPS,
                "(now read): delete their rows from " + PROPERTY_GAPS);
    }

    /** A property a provisioner now reads must lose its row rather than keep claiming to be ignored. */
    @Test
    void everyRecordedPropertyGapIsStillReal() {
        if (!schemasAvailable()) {
            return;
        }
        Map<String, String> actual = actualPropertyGaps();
        List<String> stale = recordedPropertyGaps().keySet().stream()
                .filter(key -> !actual.containsKey(key))
                .sorted()
                .toList();

        if (!stale.isEmpty()) {
            throw new AssertionError("These properties are now read, so their rows in " + PROPERTY_GAPS
                    + " are stale and should be deleted: " + stale);
        }
    }

    @Test
    void everyRecordedPropertyGapExplainsItself() {
        List<String> unexplained = recordedPropertyGaps().entrySet().stream()
                .filter(e -> e.getValue().isBlank() || e.getValue().startsWith("TODO"))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();

        if (!unexplained.isEmpty()) {
            throw new AssertionError("A recorded property gap must say why Floci ignores it, so a "
                    + "reviewer can tell 'not emulated' from 'forgotten': " + unexplained);
        }
    }

    /** An entry no provisioner contains credits nothing: a renamed helper must not leave it behind. */
    @Test
    void everyWholesaleCallAndHelperReadStillOccursInAProvisioner() {
        List<String> sources = new ArrayList<>();
        try (Stream<Path> files = Files.list(PROVISIONERS)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith("CfnProvisioner.java"))
                    .toList()) {
                sources.add(withoutComments(Files.readString(file, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot scan " + PROVISIONERS, e);
        }
        List<String> dead = Stream.concat(WHOLESALE_CALLS.stream(), HELPER_READS.keySet().stream())
                .filter(entry -> sources.stream().noneMatch(source -> source.contains(entry)))
                .sorted()
                .toList();

        if (!dead.isEmpty()) {
            throw new AssertionError("These WHOLESALE_CALLS / HELPER_READS entries occur in no "
                    + "provisioner source, so they credit nothing and should be updated or deleted: " + dead);
        }
    }

    @Test
    void sesDkimDnsAttributesAreVisibleToTheCoverageScan() {
        Set<String> expected = Set.of("DkimDNSTokenName1", "DkimDNSTokenName2", "DkimDNSTokenName3",
                "DkimDNSTokenValue1", "DkimDNSTokenValue2", "DkimDNSTokenValue3");
        Set<String> actual = scanProvisioners().get("SesCfnProvisioner")
                .attributesFor("AWS::SES::EmailIdentity");
        Set<String> missing = new HashSet<>(expected);
        missing.removeAll(actual);
        if (!missing.isEmpty()) {
            throw new AssertionError("SES DKIM DNS attributes are invisible to schema coverage: " + missing);
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

    /** "AWS::SNS::Topic\tDisplayName" -> reason. */
    private static Map<String, String> recordedPropertyGaps() {
        Map<String, String> gaps = new TreeMap<>();
        try {
            for (String line : Files.readAllLines(PROPERTY_GAPS, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\t", 3);
                if (parts.length < 3) {
                    throw new AssertionError(
                            "Expected 'type<TAB>property<TAB>reason' but found: " + line);
                }
                gaps.put(parts[0] + "\t" + parts[1], parts[2]);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + PROPERTY_GAPS, e);
        }
        return gaps;
    }

    /** The writable properties no provisioner reads, computed from the schemas and the sources. */
    private static Map<String, String> actualPropertyGaps() {
        Map<String, ProvisionerScan> scans = scanProvisioners();
        Map<String, String> gaps = new TreeMap<>();
        for (Map.Entry<String, String> entry : inventory().entrySet()) {
            String type = entry.getKey();
            ProvisionerScan scan = scans.get(entry.getValue());
            Set<String> read = scan == null ? Set.of() : scan.propertiesReadBy(type);
            for (String property : schemaWritableProperties(type)) {
                if (!read.contains(ALL_PROPERTIES) && !read.contains(property)) {
                    gaps.put(type + "\t" + property, "");
                }
            }
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
                                   Map<String, Set<String>> byType, String classCode,
                                   Map<String, String> codeByType) {

        /**
         * The properties read by the code reachable from the type's arm, attributed like
         * {@link #attributesFor}, plus those a called helper reads (see {@link #HELPER_READS}).
         * {@link #ALL_PROPERTIES} when the arm hands the whole property object to the service
         * request (see {@link #WHOLESALE_CALLS}).
         */
        Set<String> propertiesReadBy(String resourceType) {
            String code = codeByType.isEmpty() ? classCode : codeByType.get(resourceType);
            if (code == null) {
                throw new AssertionError(className + " switches on getResourceType() but has no arm "
                        + "for " + resourceType + ", so its properties cannot be attributed to that "
                        + "type. Add an arm, or give the class one shared provisioning path.");
            }
            if (WHOLESALE_CALLS.stream().anyMatch(code::contains)) {
                return Set.of(ALL_PROPERTIES);
            }
            Set<String> read = new LinkedHashSet<>();
            Matcher call = PROPERTY_READ.matcher(code);
            while (call.find()) {
                read.add(call.group(1));
            }
            Matcher helperCall = PROPERTY_HELPER_CALL.matcher(code);
            while (helperCall.find()) {
                Matcher literal = STRING_LITERAL.matcher(argumentsOf(code, helperCall.end() - 1));
                while (literal.find()) {
                    read.add(literal.group(1));
                }
            }
            Matcher loop = PROPERTY_LOOP.matcher(code);
            while (loop.find()) {
                Matcher literal = STRING_LITERAL.matcher(loop.group(1));
                while (literal.find()) {
                    read.add(literal.group(1));
                }
            }
            HELPER_READS.forEach((helper, properties) -> {
                if (code.contains(helper)) {
                    read.addAll(properties);
                }
            });
            return read;
        }

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
        try (Stream<Path> files = Files.list(PROVISIONERS)) {
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
        Map<String, String> codeByType = new TreeMap<>();
        String provision = methods.get("provision");
        if (provision == null) {
            throw new AssertionError(className + " has no provision method body, but every provisioner "
                    + "implements provision: the method pattern of this scan has broken.");
        }
        Dispatch dispatch = dispatchIn(provision);
        if (dispatch != null) {
            // Anything provision() writes outside the switch is on the path every type takes,
            // so it belongs to all of them. Ec2SecurityGroupRule sets Id there.
            String sharedPath = provision.replace(dispatch.block(), "");
            Set<String> shared = attributesReachableFrom(sharedPath, methods, new HashSet<>());
            String sharedCode = codeReachableFrom(sharedPath, methods, new HashSet<>());
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
                        codeByType.merge(type, inlineConstants(sharedCode + codeReachableFrom(
                                arm.getValue(), methods, new HashSet<>()), source), String::concat);
                    }
                }
            }
        }
        return new ProvisionerScan(className, Set.copyOf(classWide), Map.copyOf(byType),
                inlineConstants(withoutComments(source), source), Map.copyOf(codeByType));
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

    /** {@code code} with each name or name-list constant that {@code source} declares replaced by its value. */
    private static String inlineConstants(String code, String source) {
        Matcher constant = NAME_CONSTANT.matcher(source);
        String inlined = code;
        while (constant.find()) {
            inlined = inlined.replaceAll("(?<![\\w\"])" + constant.group(1) + "(?![\\w\"])",
                    Matcher.quoteReplacement(constant.group(2)));
        }
        Matcher list = NAME_LIST_CONSTANT.matcher(source);
        while (list.find()) {
            inlined = inlined.replaceAll("(?<![\\w\"])" + list.group(1) + "(?![\\w\"])",
                    Matcher.quoteReplacement(list.group(2)));
        }
        return inlined;
    }

    /** The text between {@code code[open]}, which must be '(', and its matching parenthesis. */
    private static String argumentsOf(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return code.substring(open + 1, i);
            }
        }
        return code.substring(open + 1);
    }

    /**
     * {@code code} plus the bodies of the same-class methods it calls, transitively. Comments are
     * dropped first, only unqualified calls are followed, and {@code provision} itself never is: a
     * method that mentions {@code provision(} in prose or logs, or calls {@code other.provision(},
     * would otherwise reach every other arm of the class.
     */
    private static String codeReachableFrom(String code, Map<String, String> methods,
                                            Set<String> visited) {
        String live = withoutComments(code);
        StringBuilder reachable = new StringBuilder(live);
        Matcher call = OWN_METHOD_CALL.matcher(live);
        while (call.find()) {
            String callee = call.group(1);
            if (!"provision".equals(callee) && methods.containsKey(callee) && visited.add(callee)) {
                reachable.append('\n').append(codeReachableFrom(methods.get(callee), methods, visited));
            }
        }
        return reachable.toString();
    }

    private static String withoutComments(String code) {
        return code.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)(^|\\s)//[^\\n]*", "$1");
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

    /** No schema file: fine for a declared schema-less type, a failure naming the type otherwise. */
    private static Set<String> missingSchema(String resourceType) {
        if (!TYPES_WITHOUT_SCHEMA.contains(resourceType)) {
            throw new AssertionError("No registry schema file for " + resourceType + " in " + SCHEMA_DIR
                    + ", so its attributes and properties would go unchecked. Fix the type name, or "
                    + "declare it in TYPES_WITHOUT_SCHEMA.");
        }
        return Set.of();
    }

    /** Top-level read-only properties only: a nested pointer is not a GetAtt attribute name. */
    private static Set<String> schemaReadOnlyAttributes(String resourceType) {
        Path schema = SCHEMA_DIR.resolve(
                resourceType.replace("::", "-").toLowerCase(Locale.ROOT) + ".json");
        if (!Files.exists(schema)) {
            return missingSchema(resourceType);
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

    /**
     * Writable top-level properties: every {@code properties} key that is neither a top-level
     * read-only pointer nor deprecated. Write-only properties are writable. Nested members are out,
     * as nested pointers are for the attributes.
     */
    private static Set<String> schemaWritableProperties(String resourceType) {
        Path schema = SCHEMA_DIR.resolve(
                resourceType.replace("::", "-").toLowerCase(Locale.ROOT) + ".json");
        if (!Files.exists(schema)) {
            return missingSchema(resourceType);
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(schema, StandardCharsets.UTF_8));
            Set<String> excluded = new HashSet<>(schemaReadOnlyAttributes(resourceType));
            for (JsonNode pointer : root.path("deprecatedProperties")) {
                String[] parts = pointer.asText().split("/");
                if (parts.length == 3) {
                    excluded.add(parts[2]);
                }
            }
            Set<String> writable = new TreeSet<>();
            root.path("properties").fieldNames().forEachRemaining(name -> {
                if (!excluded.contains(name)) {
                    writable.add(name);
                }
            });
            return writable;
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
        assertSameLines(recorded, actual,
                "The set of unset schema attributes changed.",
                "(a provisioner stopped setting a declared attribute, or a new type arrived with one "
                        + "unset): set them, or add a row with a reason to " + GAPS,
                "(now set): delete their rows from " + GAPS);
    }

    private static void assertSameLines(String recorded, String actual, String headline,
                                        String newHint, String closedHint) {
        if (recorded.equals(actual)) {
            return;
        }
        List<String> recordedLines = List.of(recorded.split("\n"));
        List<String> actualLines = List.of(actual.split("\n"));
        List<String> added = new ArrayList<>(actualLines);
        added.removeAll(recordedLines);
        List<String> removed = new ArrayList<>(recordedLines);
        removed.removeAll(actualLines);
        throw new AssertionError(headline + "\n"
                + "  New gaps " + newHint + ":\n    "
                + String.join("\n    ", added.isEmpty() ? List.of("(none)") : added)
                + "\n  Closed gaps " + closedHint + ":\n    "
                + String.join("\n    ", removed.isEmpty() ? List.of("(none)") : removed));
    }
}
