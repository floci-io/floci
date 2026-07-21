package com.floci.test;

import org.junit.jupiter.api.DisplayName;

/** Runs the Managed Flink lifecycle against the Flink 1.x line (FLINK-1_18). */
@DisplayName("Managed Service for Apache Flink — Flink 1.18")
class KinesisAnalyticsV2Flink118Test extends AbstractKinesisAnalyticsV2LifecycleTest {

    @Override
    protected String runtime() {
        return "FLINK-1_18";
    }
}
