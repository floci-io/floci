package io.github.hectorvent.floci.services.appsync.graphql.auth;

import graphql.schema.DataFetcher;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AuthFieldWrapper {

    private final IamAuthValidator iamAuthValidator;

    @Inject
    public AuthFieldWrapper(IamAuthValidator iamAuthValidator) {
        this.iamAuthValidator = iamAuthValidator;
    }

    public GraphQLSchema wrap(GraphQLSchema schema) {
        GraphQLCodeRegistry.Builder code = GraphQLCodeRegistry.newCodeRegistry(schema.getCodeRegistry());
        for (GraphQLNamedType type : schema.getAllTypesAsList()) {
            if (!(type instanceof GraphQLObjectType objectType)) {
                continue;
            }
            String typeName = objectType.getName();
            if (typeName.startsWith("__")) {
                continue;
            }
            for (GraphQLFieldDefinition field : objectType.getFieldDefinitions()) {
                String fieldName = field.getName();
                if (fieldName.startsWith("__")) {
                    continue;
                }
                FieldCoordinates coords = FieldCoordinates.coordinates(typeName, fieldName);
                DataFetcher<?> existing = schema.getCodeRegistry().getDataFetcher(coords, field);
                if (existing instanceof AuthorizationDataFetcher) {
                    continue;
                }
                code.dataFetcher(coords, new AuthorizationDataFetcher(existing, typeName, fieldName, iamAuthValidator));
            }
        }
        GraphQLCodeRegistry registry = code.build();
        return schema.transform(builder -> builder.codeRegistry(registry));
    }
}
