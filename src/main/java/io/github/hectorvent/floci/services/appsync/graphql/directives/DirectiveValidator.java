package io.github.hectorvent.floci.services.appsync.graphql.directives;

import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class DirectiveValidator {

    public void validate(GraphQLSchema schema) {
        Set<String> mutationFields = new HashSet<>();
        GraphQLObjectType mutationType = schema.getMutationType();
        if (mutationType != null) {
            for (GraphQLFieldDefinition field : mutationType.getFieldDefinitions()) {
                mutationFields.add(field.getName());
            }
        }

        for (GraphQLType type : schema.getAllTypesAsList()) {
            if (!(type instanceof GraphQLObjectType objectType)) continue;

            for (GraphQLFieldDefinition field : objectType.getFieldDefinitions()) {
                List<GraphQLDirective> directives = field.getDirectives();
                for (GraphQLDirective directive : directives) {
                    if ("aws_subscribe".equals(directive.getName())) {
                        var mutationArg = directive.getArgument("mutations");
                        if (mutationArg != null && mutationFields.isEmpty()) {
                            throw new AwsException("BadRequestException",
                                "@aws_subscribe on type " + objectType.getName() + "." + field.getName()
                                + " but no mutations are defined in the schema", 400);
                        }
                    }
                }
            }
        }
    }
}
