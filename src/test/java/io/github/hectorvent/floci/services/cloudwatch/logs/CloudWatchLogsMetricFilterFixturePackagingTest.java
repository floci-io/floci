package io.github.hectorvent.floci.services.cloudwatch.logs;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CloudWatchLogsMetricFilterFixturePackagingTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "compatibility-tests/sdk-test-java/src/test/resources/cloudwatchlogs/metric-filter-publishing-aws.json",
            "compatibility-tests/sdk-test-python/tests/fixtures/metric-filter-publishing-aws.json"
    })
    void sdkModuleFixtureIsAnExactCopyOfTheCanonicalAwsOracle(String modulePath) throws Exception {
        Path copy = Path.of(modulePath);
        assertTrue(Files.isRegularFile(copy), "SDK module must package its own fixture: " + modulePath);
        try (InputStream canonical = getClass().getResourceAsStream("/cloudwatchlogs/metric-filter-publishing-aws.json")) {
            assertNotNull(canonical);
            assertArrayEquals(canonical.readAllBytes(), Files.readAllBytes(copy),
                    "Copy canonical bytes; never regenerate expected values from emulator output");
        }
    }
}
