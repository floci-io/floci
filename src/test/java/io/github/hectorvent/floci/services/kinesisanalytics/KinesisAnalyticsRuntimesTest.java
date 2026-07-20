package io.github.hectorvent.floci.services.kinesisanalytics;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KinesisAnalyticsRuntimesTest {

    @Test
    void imageForMapsRuntimeToFlinkTag() {
        assertEquals("apache/flink:1.18", KinesisAnalyticsRuntimes.imageFor("FLINK-1_18"));
        assertEquals("apache/flink:1.19", KinesisAnalyticsRuntimes.imageFor("FLINK-1_19"));
        assertEquals("apache/flink:1.20", KinesisAnalyticsRuntimes.imageFor("FLINK-1_20"));
    }

    @Test
    void imageForFallsBackToDefaultWhenBlank() {
        assertEquals(KinesisAnalyticsRuntimes.imageFor(KinesisAnalyticsRuntimes.DEFAULT_RUNTIME),
                KinesisAnalyticsRuntimes.imageFor(null));
        assertEquals(KinesisAnalyticsRuntimes.imageFor(KinesisAnalyticsRuntimes.DEFAULT_RUNTIME),
                KinesisAnalyticsRuntimes.imageFor("  "));
    }

    @Test
    void imageForRejectsUnsupportedRuntime() {
        // SQL / ZEPPELIN studio runtimes and bogus values have no plain Flink image.
        assertThrows(AwsException.class, () -> KinesisAnalyticsRuntimes.imageFor("SQL-1_0"));
        assertThrows(AwsException.class, () -> KinesisAnalyticsRuntimes.imageFor("FLINK-9_9"));
    }

    @Test
    void validateAllowsSupportedAndBlankButRejectsUnknown() {
        KinesisAnalyticsRuntimes.validate("FLINK-1_20");
        KinesisAnalyticsRuntimes.validate(null);
        assertThrows(AwsException.class, () -> KinesisAnalyticsRuntimes.validate("ZEPPELIN-FLINK-3_0"));
    }

    @Test
    void resolveImageHonoursOperatorOverride() {
        // A configured override wins regardless of the requested runtime.
        assertEquals("registry.example.com/flink:pinned",
                KinesisAnalyticsRuntimes.resolveImage(
                        Optional.of("registry.example.com/flink:pinned"), "FLINK-1_18"));
        // Blank/absent override falls back to the runtime map.
        assertEquals("apache/flink:1.19",
                KinesisAnalyticsRuntimes.resolveImage(Optional.empty(), "FLINK-1_19"));
        assertEquals("apache/flink:1.19",
                KinesisAnalyticsRuntimes.resolveImage(Optional.of("  "), "FLINK-1_19"));
    }

    @Test
    void supportedRuntimesListsFlinkVersions() {
        assertTrue(KinesisAnalyticsRuntimes.supportedRuntimes().contains("FLINK-1_18"));
    }
}
