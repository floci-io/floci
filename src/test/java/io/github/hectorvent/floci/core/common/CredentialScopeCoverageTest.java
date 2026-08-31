package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tripwire for the failure mode behind issue #1754's guard: Floci serves a service's routes but
 * never enumerates the name that service signs with, so the guard rejects requests to routes that
 * do work. The IoT Jobs Data Plane hit exactly this — {@code IotController} serves
 * {@code /things/{thing}/jobs} while the scope is {@code iot-jobs-data} — and only the SDK compat
 * suites caught it.
 *
 * <p>The check walks botocore's models: for every AWS signing name the catalog does not declare,
 * it measures how much of that service's REST surface Floci already routes. A service whose routes
 * Floci overwhelmingly serves is one Floci effectively implements, so its signing name belongs in
 * the catalog.
 *
 * <p>Thresholds are deliberately conservative. Small services share generic templates
 * ({@code /tags/{id}}, {@code /jobs/{id}}) with dozens of unrelated APIs, so this is a heuristic
 * tripwire rather than a proof of completeness — the SDK compat suites remain the real check.
 *
 * <p>Skips when the botocore checkout is absent, which is the case on CI runners: the reference
 * models live in the gitignored {@code local/aws/} tree, so this runs for maintainers who have it.
 */
@QuarkusTest
class CredentialScopeCoverageTest {

    private static final Path BOTOCORE = Path.of("local/aws/botocore/botocore/data");
    private static final Pattern PATH_PARAM = Pattern.compile("\\{[^}]+}");
    private static final Pattern PATH_ANNOTATION = Pattern.compile("@Path\\(\"([^\"]*)\"\\)");
    private static final Pattern CLASS_PATH_ANNOTATION =
            Pattern.compile("@Path\\(\"([^\"]*)\"\\)\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*public\\s+class");

    /** Below this many modelled routes a service is too small to tell coincidence from coverage. */
    private static final int MIN_ROUTES = 3;
    /** Fraction of a service's routes Floci must already serve before its scope is expected. */
    private static final double MIN_COVERAGE = 0.6;

    @Inject
    ResolvedServiceCatalog catalog;

    @Test
    void everyServiceWhoseRoutesFlociServesHasItsSigningNameDeclared() throws IOException {
        assumeTrue(Files.isDirectory(BOTOCORE),
                "botocore checkout not present (see CLAUDE.md, local/aws/) — skipping");

        Set<String> declared = catalog.all().stream()
                .flatMap(descriptor -> descriptor.credentialScopes().stream())
                .collect(Collectors.toSet());
        Set<String> flociRoutes = flociRouteTemplates();
        Map<String, Set<String>> modelled = routeTemplatesBySigningName();

        Map<String, String> undeclared = new TreeMap<>();
        modelled.forEach((signingName, routes) -> {
            if (declared.contains(signingName) || routes.size() < MIN_ROUTES) {
                return;
            }
            long served = routes.stream().filter(flociRoutes::contains).count();
            double coverage = (double) served / routes.size();
            if (coverage >= MIN_COVERAGE) {
                undeclared.put(signingName, served + "/" + routes.size() + " routes served");
            }
        });

        assertTrue(undeclared.isEmpty(),
                "Floci routes these services' operations but never declares the name they sign with, "
                        + "so the unknown-service guard rejects requests that would otherwise work. "
                        + "Add each to the owning descriptor's credential scopes in ResolvedServiceCatalog: "
                        + undeclared);
    }

    /** Path templates Floci's JAX-RS controllers declare, normalised to botocore's shape. */
    private static Set<String> flociRouteTemplates() throws IOException {
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            List<Path> controllers = sources
                    .filter(p -> p.getFileName().toString().endsWith("Controller.java"))
                    .toList();
            Set<String> templates = new HashSet<>();
            for (Path controller : controllers) {
                String source = Files.readString(controller);
                Matcher classMatcher = CLASS_PATH_ANNOTATION.matcher(source);
                String classPath = classMatcher.find() ? trimSlashes(classMatcher.group(1)) : "";
                Matcher methodMatcher = PATH_ANNOTATION.matcher(source);
                while (methodMatcher.find()) {
                    String methodPath = trimSlashes(methodMatcher.group(1));
                    if (methodPath.equals(classPath)) {
                        continue;
                    }
                    String full = classPath.isEmpty() ? "/" + methodPath : "/" + classPath + "/" + methodPath;
                    templates.add(normalise(full));
                }
            }
            return templates;
        }
    }

    /** Every AWS signing name mapped to the request-URI templates its operations declare. */
    private static Map<String, Set<String>> routeTemplatesBySigningName() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Set<String>> bySigningName = new HashMap<>();
        try (Stream<Path> services = Files.list(BOTOCORE)) {
            for (Path service : services.filter(Files::isDirectory).toList()) {
                Optional<Path> model = latestModel(service);
                if (model.isEmpty()) {
                    continue;
                }
                JsonNode root = mapper.readTree(model.get().toFile());
                JsonNode metadata = root.path("metadata");
                String signingName = metadata.path("signingName").asText(
                        metadata.path("endpointPrefix").asText(null));
                if (signingName == null || signingName.isBlank()) {
                    continue;
                }
                root.path("operations").forEach(operation -> {
                    String uri = operation.path("http").path("requestUri").asText(null);
                    if (uri == null) {
                        return;
                    }
                    String template = normalise(uri.split("\\?")[0]);
                    if (template.chars().filter(c -> c == '/').count() < 2) {
                        return;
                    }
                    bySigningName.computeIfAbsent(signingName, k -> new HashSet<>()).add(template);
                });
            }
        }
        return bySigningName;
    }

    private static Optional<Path> latestModel(Path serviceDir) throws IOException {
        try (Stream<Path> versions = Files.list(serviceDir)) {
            return versions.filter(Files::isDirectory)
                    .map(version -> version.resolve("service-2.json"))
                    .filter(Files::isRegularFile)
                    .max(Path::compareTo);
        }
    }

    private static String normalise(String path) {
        return PATH_PARAM.matcher(path).replaceAll("{}").replace("//", "/");
    }

    private static String trimSlashes(String path) {
        return path.replaceAll("^/+", "").replaceAll("/+$", "");
    }
}
