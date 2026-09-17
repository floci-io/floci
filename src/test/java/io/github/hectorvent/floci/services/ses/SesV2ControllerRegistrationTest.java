package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.ResolvedServiceCatalog;
import io.github.hectorvent.floci.core.common.ServiceDescriptor;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tripwire for the SES v2 controller split: every JAX-RS resource class in this package that
 * serves {@code /v2/email} must be listed in the SES descriptor's resource classes in
 * {@code ResolvedServiceCatalog}. {@code ServiceEnabledFilter} and
 * {@code AwsRestJsonErrorHeaderFilter} resolve the service by resource class first and only fall
 * back to the SigV4 credential scope, so a controller missing from the catalog still works for
 * signed SDK calls and silently loses the service-disabled gate and the {@code x-amzn-errortype}
 * header for unsigned ones. The internal {@code /_aws/ses} controllers are outside the AWS surface
 * and stay unregistered on purpose.
 */
@QuarkusTest
class SesV2ControllerRegistrationTest {

    private static final String V2_PREFIX = "/v2/email";

    @Inject
    ResolvedServiceCatalog catalog;

    @Test
    void everyV2EmailResourceClassIsRegisteredOnTheSesDescriptor() throws Exception {
        List<Class<?>> v2Controllers = v2EmailResourceClasses();
        assertTrue(v2Controllers.contains(SesController.class),
                "scan must at least find SesController; found " + v2Controllers);

        List<String> unregistered = new ArrayList<>();
        for (Class<?> controller : v2Controllers) {
            String owner = catalog.byResourceClass(controller)
                    .map(ServiceDescriptor::externalKey).orElse(null);
            if (!"email".equals(owner)) {
                unregistered.add(controller.getSimpleName() + " (resolved to " + owner + ")");
            }
        }
        assertEquals(List.of(), unregistered,
                "add these to the SES descriptor's resource classes in ResolvedServiceCatalog");
    }

    @Test
    void scanLeavesTheInternalControllersOutOfTheRule() throws Exception {
        // /_aws/ses is Floci's own inspection surface, not an AWS route, and is unregistered on
        // purpose; the prefix filter must not pull it into the registration requirement.
        List<Class<?>> v2Controllers = v2EmailResourceClasses();
        assertFalse(v2Controllers.contains(SesInspectionController.class));
        assertFalse(v2Controllers.contains(SesUnsubscribeController.class));
        assertTrue(catalog.byResourceClass(SesInspectionController.class).isEmpty());
        assertTrue(catalog.byResourceClass(SesUnsubscribeController.class).isEmpty());
    }

    private List<Class<?>> v2EmailResourceClasses() throws Exception {
        URI codeSource = SesController.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI();
        Path classesDir = Path.of(codeSource);
        String packagePrefix = SesController.class.getPackageName() + ".";
        List<Class<?>> found = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(classesDir)) {
            List<Path> classFiles = paths.filter(p -> p.toString().endsWith(".class")).toList();
            for (Path p : classFiles) {
                String name = classesDir.relativize(p).toString()
                        .replace(File.separatorChar, '.')
                        .replaceAll("\\.class$", "");
                if (!name.startsWith(packagePrefix) || name.contains("$")) {
                    continue;
                }
                Class<?> type = Class.forName(name, false, getClass().getClassLoader());
                // jakarta.ws.rs.Path clashes with the java.nio.file.Path this scan is built on.
                jakarta.ws.rs.Path route = type.getAnnotation(jakarta.ws.rs.Path.class);
                if (route != null && route.value().startsWith(V2_PREFIX)) {
                    found.add(type);
                }
            }
        }
        return found;
    }
}
