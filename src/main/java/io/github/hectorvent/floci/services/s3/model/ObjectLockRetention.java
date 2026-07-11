package io.github.hectorvent.floci.services.s3.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record ObjectLockRetention(String mode, String unit, int value) {}
