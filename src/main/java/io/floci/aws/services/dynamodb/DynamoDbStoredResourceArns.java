package io.floci.aws.services.dynamodb;

import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.services.dynamodb.backend.DynamoDbTableAccess;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What IAM asks DynamoDB of the resources a request names: the tags of the table an ARN names, or of the table whose
 * index it names, from the selected backend.
 */
@ApplicationScoped
public class DynamoDbStoredResourceArns implements StoredResourceArns {

    private final DynamoDbTableAccess tables;

    @Inject
    public DynamoDbStoredResourceArns(DynamoDbTableAccess tables) {
        this.tables = tables;
    }

    @Override
    public String namespace() {
        return "dynamodb";
    }

    /** DynamoDB mints no id into an ARN a request names by its name, so there is nothing to find. */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    @Override
    public Map<String, String> storedTags(String arn) {
        return tables.storedTags(arn);
    }
}
