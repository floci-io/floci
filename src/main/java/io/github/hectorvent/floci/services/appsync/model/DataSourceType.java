package io.github.hectorvent.floci.services.appsync.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum DataSourceType {
    NONE,
    AWS_LAMBDA,
    AMAZON_DYNAMODB,
    HTTP,
    AMAZON_EVENTBRIDGE,
    RELATIONAL_DATABASE,
    AMAZON_OPENSEARCH_SERVICE,
    AMAZON_BEDROCK_RUNTIME
}
