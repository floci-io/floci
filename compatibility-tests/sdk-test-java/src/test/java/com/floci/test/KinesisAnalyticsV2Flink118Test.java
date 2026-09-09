package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.DisabledOnOs;

/**
 * Runs the Managed Flink lifecycle against the Flink 1.x line (FLINK-1_18).
 *
 * <p>Disabled on arm64: apache/flink 1.15/1.18/1.19 publish amd64-only images, so on an
 * arm64 host (the CI runners) the JobManager can never become healthy and the RUNNING
 * wait burns its full 300s budget before skipping. Flink 1.x coverage on arm64 would
 * need a multi-arch mirror; until then the 2.x line ({@link KinesisAnalyticsV2Flink23Test})
 * keeps the real-cluster path covered there.
 */
@DisabledOnOs(architectures = "aarch64",
        disabledReason = "apache/flink:1.18 has no arm64 image; the cluster cannot start")
@DisplayName("Managed Service for Apache Flink — Flink 1.18")
class KinesisAnalyticsV2Flink118Test extends AbstractKinesisAnalyticsV2LifecycleTest {

    @Override
    protected String runtime() {
        return "FLINK-1_18";
    }
}
