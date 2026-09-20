package io.github.hectorvent.floci.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the GraalVM native-image build against a classpath resource that is read at runtime but
 * never packaged.
 *
 * <p>Quarkus's static analysis finds an inline literal such as
 * {@code getResourceAsStream("/redshift/bootstrap-catalog.sql")}, so those need no registration.
 * It cannot follow a constant, so {@code getResourceAsStream(CATALOG_RESOURCE_NAME)} leaves the
 * file out of the image and the first read fails at runtime with a missing-resource error. The
 * remedy is a matching glob under {@code quarkus.native.resources.includes} in
 * {@code application.yml}.
 *
 * <p>Like {@link NativeImageRuntimeInitializationTest}, this failure only surfaces in the slow
 * native build and never in the JVM CI jobs, so it moves the check into ordinary CI. Regression
 * context: the SageMaker instance type catalog shipped reading
 * {@code sagemaker/instance-type-catalog.yaml} through a constant without the matching include,
 * which would have failed every GPU training job in the native image while passing every JVM test.
 */
class NativeImageResourceIncludesTest {

    private static final Path MAIN_SOURCE_ROOT = Path.of("src", "main", "java");
    private static final Path APPLICATION_YML = Path.of("src", "main", "resources", "application.yml");
    private static final Path MAIN_RESOURCE_ROOT = Path.of("src", "main", "resources");

    private static final String JAVA_SUFFIX = ".java";

    /** A {@code static final String NAME = "some/path.ext";} declaration holding a resource path. */
    private static final Pattern RESOURCE_CONSTANT = Pattern.compile(
            "static\\s+final\\s+String\\s+(\\w+)\\s*=\\s*\"(/?[\\w.\\-]+(?:/[\\w.\\-]+)+\\.[A-Za-z0-9]+)\"");

    /**
     * Read while the image is built, not at runtime. {@code AwsManagedPolicies} parses the catalog
     * during the build and keeps the parsed form in the image heap, so including the 3.5 MB of
     * source files would only be a second copy. {@code application.yml} says the same.
     */
    private static final Set<String> BUILD_TIME_ONLY = Set.of(
            "iam/managed-policies.yaml",
            "iam/managed-policy-documents.json",
            "iam/managed-policy-versions.json");

    @Test
    void everyResourceReadThroughAConstantIsIncludedInTheNativeImage() throws IOException {
        List<PathMatcher> includes = parseIncludes();
        Map<String, String> offenders = new LinkedHashMap<>();

        try (Stream<Path> sources = Files.walk(MAIN_SOURCE_ROOT)) {
            sources.filter(p -> p.toString().endsWith(JAVA_SUFFIX)).forEach(source -> {
                for (String resource : resourcesReadThroughAConstant(source)) {
                    if (BUILD_TIME_ONLY.contains(resource)) {
                        continue;
                    }
                    // Only a file this repository actually ships can be registered. A constant
                    // naming something from a dependency is that dependency's problem.
                    if (!Files.exists(MAIN_RESOURCE_ROOT.resolve(resource))) {
                        continue;
                    }
                    if (includes.stream().noneMatch(m -> m.matches(Path.of(resource)))) {
                        offenders.put(resource, source.toString());
                    }
                }
            });
        }

        assertTrue(offenders.isEmpty(),
                "These resources are read at runtime through a constant, which Quarkus's static analysis "
                        + "cannot follow, but no glob in " + APPLICATION_YML + " under "
                        + "quarkus.native.resources.includes matches them. The native image will not contain "
                        + "them and the first read will fail at runtime. Add an include for each:\n  "
                        + offenders.entrySet().stream()
                        .map(e -> e.getKey() + "  (read by " + e.getValue() + ")")
                        .reduce((a, b) -> a + "\n  " + b).orElse(""));
    }

    /** Resource paths declared as a constant in {@code javaFile} and passed to getResourceAsStream there. */
    private List<String> resourcesReadThroughAConstant(Path javaFile) {
        String source;
        try {
            source = Files.readString(javaFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read source file " + javaFile, e);
        }
        List<String> found = new ArrayList<>();
        if (!source.contains("getResourceAsStream")) {
            return found;
        }
        Matcher constants = RESOURCE_CONSTANT.matcher(source);
        while (constants.find()) {
            String name = constants.group(1);
            String value = constants.group(2);
            if (source.contains("getResourceAsStream(" + name + ")")
                    || source.contains("getResourceAsStream(\n")
                    && source.contains(name + ")")) {
                found.add(value.startsWith("/") ? value.substring(1) : value);
            }
        }
        return found;
    }

    /** The {@code quarkus.native.resources.includes} globs, as matchers over classpath-relative paths. */
    private List<PathMatcher> parseIncludes() throws IOException {
        List<String> lines = Files.readAllLines(APPLICATION_YML);
        List<PathMatcher> matchers = new ArrayList<>();
        boolean inIncludes = false;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.startsWith("includes:")) {
                inIncludes = true;
                continue;
            }
            if (inIncludes) {
                if (!line.startsWith("- ")) {
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        break;
                    }
                    continue;
                }
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + line.substring(2).strip()));
            }
        }
        assertTrue(!matchers.isEmpty(), "Found no quarkus.native.resources.includes entries in " + APPLICATION_YML);
        return matchers;
    }
}
