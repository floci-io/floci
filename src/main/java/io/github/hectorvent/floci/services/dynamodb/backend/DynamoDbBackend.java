package io.github.hectorvent.floci.services.dynamodb.backend;

/** One selectable DynamoDB engine: every seam interface from a single object. */
public interface DynamoDbBackend extends DynamoDbOperations, DynamoDbItemAccess, DynamoDbTableAccess,
        DynamoDbBackendLifecycle {}
