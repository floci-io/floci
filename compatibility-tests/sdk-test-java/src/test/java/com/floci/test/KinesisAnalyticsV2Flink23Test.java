package com.floci.test;

import org.junit.jupiter.api.DisplayName;

/** Runs the Managed Flink lifecycle against the Flink 2.x line (FLINK-2_3). */
@DisplayName("Managed Service for Apache Flink — Flink 2.3")
class KinesisAnalyticsV2Flink23Test extends AbstractKinesisAnalyticsV2LifecycleTest {

    @Override
    protected String runtime() {
        return "FLINK-2_3";
    }
}
