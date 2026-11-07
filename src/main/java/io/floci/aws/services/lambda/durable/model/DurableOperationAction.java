package io.floci.aws.services.lambda.durable.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum DurableOperationAction {
    START, SUCCEED, FAIL, RETRY, CANCEL
}
