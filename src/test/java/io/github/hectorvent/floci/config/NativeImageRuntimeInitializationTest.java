package io.github.hectorvent.floci.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the GraalVM native-image build against the "Random/SecureRandom in the
 * image heap" failure mode.
 *
 * <p>A {@code static} {@link java.security.SecureRandom}/{@link java.util.Random}
 * field is initialized at <em>build</em> time, so native-image captures the
 * instance — with a frozen seed — into the image heap and aborts with:
 *
 * <pre>
 * Detected an instance of Random/SplittableRandom class in the image heap.
 * </pre>
 *
 * The remedy is to list the owning class under
 * {@code quarkus.native.additional-build-args → --initialize-at-run-time} in
 * {@code application.yml} so its static initializer runs at image runtime.
 *
 * <p>This failure only surfaces in the (slow, nightly) native build, never in
 * the JVM CI jobs. This test moves the check into ordinary CI: every main-source
 * class declaring such a static field MUST be registered for run-time
 * initialization. Regression context: CloudMapService (#1217) added a static
 * {@code SecureRandom} without the registration and broke the nightly (#1234).
 */
class NativeImageRuntimeInitializationTest {

    private static final Path MAIN_SOURCE_ROOT = Path.of("src", "main", "java");
    private static final Path APPLICATION_YML = Path.of("src", "main", "resources", "application.yml");

    private static final String JAVA_SUFFIX = ".java";
    private static final String INIT_AT_RUN_TIME_FLAG = "--initialize-at-run-time=";

    /** A {@code static [final] [pkg.]SecureRandom|Random|SplittableRandom <name> ;|=} field declaration. */
    private static final Pattern STATIC_RANDOM_FIELD = Pattern.compile(
            "\\bstatic\\s+(?:final\\s+)?(?:[\\w.]+\\.)?\\b(?:SecureRandom|SplittableRandom|Random)\\b\\s+\\w+\\s*[;=]");

    private static final Pattern PACKAGE_DECLARATION = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    @Test
    void everyClassWithAStaticRandomFieldIsInitializedAtRunTime() throws IOException {
        Set<String> runtimeInitialized = parseRuntimeInitializedClasses();
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> sources = Files.walk(MAIN_SOURCE_ROOT)) {
            sources.filter(p -> p.toString().endsWith(JAVA_SUFFIX))
                    .forEach(p -> {
                        String fqcn = fullyQualifiedNameIfHoldsStaticRandom(p);
                        if (fqcn != null && !runtimeInitialized.contains(fqcn)) {
                            offenders.add(fqcn);
                        }
                    });
        }

        assertTrue(offenders.isEmpty(),
                "These classes declare a static Random/SecureRandom field but are NOT registered under "
                        + INIT_AT_RUN_TIME_FLAG + " in " + APPLICATION_YML + ". GraalVM native builds will fail "
                        + "(instance captured in the image heap). Add an --initialize-at-run-time entry for each:\n  "
                        + String.join("\n  ", offenders));
    }

    /** @return the top-level FQCN if the source file declares a static Random field, otherwise {@code null}. */
    private String fullyQualifiedNameIfHoldsStaticRandom(Path javaFile) {
        String source;
        try {
            source = Files.readString(javaFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read source file " + javaFile, e);
        }
        if (!STATIC_RANDOM_FIELD.matcher(source).find()) {
            return null;
        }
        Matcher pkg = PACKAGE_DECLARATION.matcher(source);
        String packageName = pkg.find() ? pkg.group(1) : "";
        String fileName = javaFile.getFileName().toString();
        String simpleName = fileName.substring(0, fileName.length() - JAVA_SUFFIX.length());
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    private Set<String> parseRuntimeInitializedClasses() throws IOException {
        return Files.readAllLines(APPLICATION_YML).stream()
                .map(String::trim)
                .filter(line -> line.contains(INIT_AT_RUN_TIME_FLAG))
                .map(line -> line.substring(line.indexOf(INIT_AT_RUN_TIME_FLAG) + INIT_AT_RUN_TIME_FLAG.length()).trim())
                .collect(Collectors.toSet());
    }
}
