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
 * index it names, from the selected backend, and the verb, table and index of a PartiQL statement, as execution
 * parses it.
 */
@ApplicationScoped
public class DynamoDbStoredResourceArns implements StoredResourceArns {

    /**
     * What IAM reads of a request checked apart from the tables the backend holds, as a state machine's task: the verb,
     * table and index of each PartiQL statement it carries, as execution parses them. DynamoDB mints no id into an ARN
     * a request names by its name, so it finds nothing this class would.
     */
    public static final StoredResourceArns STATEMENTS = new StoredResourceArns() {
        @Override
        public String namespace() {
            return "dynamodb";
        }

        @Override
        public Optional<String> find(String type, List<String> names, String region) {
            return Optional.empty();
        }

        @Override
        public Optional<Statement> statement(String statement) {
            return parsed(statement);
        }
    };

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

    /** A PartiQL statement's verb and the table and index it names, as execution parses it. */
    @Override
    public Optional<Statement> statement(String statement) {
        return parsed(statement);
    }

    private static Optional<Statement> parsed(String statement) {
        return DynamoDbPartiQLParser.target(statement)
                .map(target -> new Statement(target.verb(), target.index() == null
                        ? Map.of("table", target.table()) : Map.of("table", target.table(), "index", target.index())));
    }
}
