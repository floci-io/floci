package io.github.hectorvent.floci.services.lambda.durable.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum DurableOperationType {
    EXECUTION, CONTEXT, STEP, WAIT, CALLBACK, CHAINED_INVOKE
}
