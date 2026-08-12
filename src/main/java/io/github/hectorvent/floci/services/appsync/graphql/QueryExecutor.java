package io.github.hectorvent.floci.services.appsync.graphql;

import graphql.ErrorType;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphQL;
import graphql.GraphqlErrorBuilder;
import graphql.language.Document;
import graphql.language.OperationDefinition;
import graphql.parser.InvalidSyntaxException;
import graphql.parser.Parser;
import graphql.schema.GraphQLSchema;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class QueryExecutor {

    private final AppSyncErrorFormatter formatter;

    @Inject
    public QueryExecutor(AppSyncErrorFormatter formatter) {
        this.formatter = formatter;
    }

    public Map<String, Object> execute(GraphQLSchema schema, String query,
                                       Map<String, Object> variables, String operationName) {
        return execute(SchemaRegistry.buildGraphQL(schema), query, variables, operationName);
    }

    public Map<String, Object> execute(GraphQL graphQL, String query,
                                       Map<String, Object> variables, String operationName) {
        List<OperationDefinition> operations = parseOperations(query);
        if (operations.size() > 1 && (operationName == null || operationName.isBlank())) {
            throw new AppSyncTransportException(400, "BadRequestException",
                    AppSyncErrorFormatter.MSG_MISSING_OPERATION_NAME);
        }

        OperationDefinition selected = selectOperation(operations, operationName);
        if (selected != null && selected.getOperation() == OperationDefinition.Operation.SUBSCRIPTION) {
            ExecutionResult rejected = ExecutionResultImpl.newExecutionResult()
                    .addError(GraphqlErrorBuilder.newError()
                            .message("Subscriptions are not supported over HTTP")
                            .errorType(ErrorType.OperationNotSupported)
                            .build())
                    .build();
            return formatter.format(rejected);
        }

        ExecutionInput.Builder inputBuilder = ExecutionInput.newExecutionInput().query(query);
        if (variables != null) {
            inputBuilder.variables(variables);
        }
        if (operationName != null && !operationName.isBlank()) {
            inputBuilder.operationName(operationName);
        }

        ExecutionResult result = graphQL.execute(inputBuilder.build());
        return formatter.format(result);
    }

    private List<OperationDefinition> parseOperations(String query) {
        try {
            Document document = Parser.parse(query);
            List<OperationDefinition> ops = new ArrayList<>();
            for (var definition : document.getDefinitions()) {
                if (definition instanceof OperationDefinition operationDefinition) {
                    ops.add(operationDefinition);
                }
            }
            return ops;
        } catch (InvalidSyntaxException e) {
            return List.of();
        }
    }

    private OperationDefinition selectOperation(List<OperationDefinition> operations, String operationName) {
        if (operations.isEmpty()) {
            return null;
        }
        if (operationName == null || operationName.isBlank()) {
            return operations.get(0);
        }
        for (OperationDefinition op : operations) {
            if (operationName.equals(op.getName())) {
                return op;
            }
        }
        return null;
    }
}
