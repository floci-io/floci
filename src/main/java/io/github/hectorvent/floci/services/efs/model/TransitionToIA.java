package io.github.hectorvent.floci.services.efs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum TransitionToIA {
    AFTER_7_DAYS, AFTER_14_DAYS, AFTER_30_DAYS, AFTER_60_DAYS, AFTER_90_DAYS, AFTER_1_DAY, AFTER_180_DAYS, AFTER_270_DAYS, AFTER_365_DAYS
}
