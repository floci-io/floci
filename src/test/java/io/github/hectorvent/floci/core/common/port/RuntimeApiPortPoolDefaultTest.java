package io.github.hectorvent.floci.core.common.port;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.smallrye.config.WithDefault;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Lambda Runtime API port pool's *default* width is a capacity ceiling on concurrent Lambda
 * executions, because one port is held for the lifetime of each running Lambda container.
 *
 * <p>The original default of 9200-9299 was exactly 100 ports, which cannot run a multi-account
 * landing zone: LZA's LoggingStack alone fans out roughly 8 custom-resource Lambdas per account,
 * so a 14-account org needs ~112 concurrently and exhausts the pool (issue #2206). Worse, the
 * failure does not present as a capacity limit — the custom resource reports FAILED and
 * CloudFormation rolls the stack back, so the operator sees an unrelated-looking CFN error.
 *
 * <p>These assertions pin the two properties that made the old default wrong, so a future edit
 * cannot quietly reintroduce either. They read the annotation rather than booting the config,
 * which keeps the guard fast and dependency-free.
 */
class RuntimeApiPortPoolDefaultTest {

    /** Enough for a ~14-account landing zone (~112 concurrent) with headroom for Deploy. */
    private static final int MIN_USABLE_POOL_WIDTH = 500;

    /**
     * The value that actually takes effect. {@code application.yml} is a config SOURCE, not a
     * set of defaults, so any key it sets WINS over the interface's {@code @WithDefault} — the
     * same trap that made {@code storage.mode} ignore its annotation (issue #2225). Reading only
     * the annotation would let a widened default pass this suite while the running emulator kept
     * the old 100-port pool, so resolve the yaml first and fall back to the annotation.
     */
    private static int defaultOf(String method) throws Exception {
        Integer fromYaml = yamlValue(kebab(method));
        return fromYaml != null ? fromYaml : annotationDefaultOf(method);
    }

    private static int annotationDefaultOf(String method) throws NoSuchMethodException {
        Method m = EmulatorConfig.LambdaServiceConfig.class.getMethod(method);
        WithDefault d = m.getAnnotation(WithDefault.class);
        assertNotNull(d, method + "() must carry an explicit @WithDefault");
        return Integer.parseInt(d.value());
    }

    /** {@code runtimeApiBasePort} -> {@code runtime-api-base-port}. */
    private static String kebab(String method) {
        return method.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(java.util.Locale.ROOT);
    }

    /** The value under {@code floci.services.lambda.<key>} in application.yml, or null. */
    private static Integer yamlValue(String key) throws Exception {
        java.nio.file.Path yml = java.nio.file.Path.of("src/main/resources/application.yml");
        assertTrue(java.nio.file.Files.exists(yml), "application.yml not found at " + yml.toAbsolutePath());
        java.util.regex.Pattern p =
                java.util.regex.Pattern.compile("^\\s*" + java.util.regex.Pattern.quote(key) + ":\\s*(\\d+)\\s*$");
        for (String line : java.nio.file.Files.readAllLines(yml)) {
            java.util.regex.Matcher m = p.matcher(line);
            if (m.matches()) {
                return Integer.valueOf(m.group(1));
            }
        }
        return null;
    }

    @Test
    void annotationDefaultAndApplicationYamlAgree() throws Exception {
        for (String method : new String[] {"runtimeApiBasePort", "runtimeApiMaxPort"}) {
            Integer yaml = yamlValue(kebab(method));
            if (yaml != null) {
                assertEquals(annotationDefaultOf(method), yaml.intValue(),
                        kebab(method) + " differs between @WithDefault and application.yml; the "
                                + "yaml wins at runtime, so the annotation would be a lie");
            }
        }
    }

    @Test
    void defaultPoolIsWideEnoughForAMultiAccountLandingZone() throws Exception {
        int base = defaultOf("runtimeApiBasePort");
        int max = defaultOf("runtimeApiMaxPort");
        int width = max - base + 1;

        assertTrue(width >= MIN_USABLE_POOL_WIDTH,
                "default Runtime API pool is " + width + " ports (" + base + "-" + max + "); "
                        + "a multi-account LZA needs at least " + MIN_USABLE_POOL_WIDTH
                        + " or it exhausts mid-deploy and surfaces as a CloudFormation rollback");
    }

    @Test
    void defaultPoolDoesNotOverlapPortsFlociAlreadyUses() throws Exception {
        int base = defaultOf("runtimeApiBasePort");
        int max = defaultOf("runtimeApiMaxPort");

        // A pool this wide stops being obvious about what it covers, so the overlaps that matter
        // are asserted rather than reasoned about: 9200 OpenSearch, 9092 Kafka, 9644 Redpanda
        // admin, 9400-9499 the ECS proxy pool, 4566 the emulator's own port, 6379-6399 Redis.
        int[][] forbidden = {
                {4566, 4566}, {6379, 6399}, {9092, 9092},
                {9200, 9200}, {9400, 9499}, {9644, 9644},
        };
        for (int[] range : forbidden) {
            boolean overlaps = base <= range[1] && range[0] <= max;
            assertFalse(overlaps, "default Runtime API pool " + base + "-" + max
                    + " overlaps reserved ports " + range[0] + "-" + range[1]);
        }
    }

    @Test
    void basePortPrecedesMaxPort() throws Exception {
        assertTrue(defaultOf("runtimeApiBasePort") < defaultOf("runtimeApiMaxPort"),
                "base port must be below max port or the pool is empty");
    }

    @Test
    void poolHonoursTheConfiguredWidthAtRuntime() throws Exception {
        // The annotation is only a default; prove the allocator actually hands out the whole
        // configured range, so widening the default genuinely raises the concurrency ceiling.
        int base = defaultOf("runtimeApiBasePort");
        int max = defaultOf("runtimeApiMaxPort");
        PortAllocator allocator = new PortAllocator(base, max);

        java.util.Set<Integer> handed = new java.util.HashSet<>();
        for (int i = 0; i < MIN_USABLE_POOL_WIDTH; i++) {
            assertTrue(handed.add(allocator.allocate()),
                    "allocator handed out a duplicate port after " + handed.size() + " allocations");
        }

        assertEquals(MIN_USABLE_POOL_WIDTH, handed.size(),
                "allocator must yield at least " + MIN_USABLE_POOL_WIDTH
                        + " concurrent ports across the full configured range");
    }
}
