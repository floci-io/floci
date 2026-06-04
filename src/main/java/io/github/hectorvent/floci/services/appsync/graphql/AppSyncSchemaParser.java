package io.github.hectorvent.floci.services.appsync.graphql;

import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appsync.graphql.scalars.AppSyncScalarRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class AppSyncSchemaParser {
    private final AppSyncScalarRegistry scalarRegistry;

    @Inject
    public AppSyncSchemaParser(AppSyncScalarRegistry scalarRegistry) {
        this.scalarRegistry = scalarRegistry;
    }

    public GraphQLSchema parse(String sdl) {
        String sdlWithDirectives = injectDirectiveDefinitions(sdl);
        SchemaParser parser = new SchemaParser();
        TypeDefinitionRegistry typeRegistry;
        validateNoUnknownDirectives(sdl);

        try {
            typeRegistry = parser.parse(sdlWithDirectives);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", "Invalid schema: " + e.getMessage(), 400);
        }

        RuntimeWiring.Builder wiringBuilder = RuntimeWiring.newRuntimeWiring();
        Map<String, graphql.schema.GraphQLScalarType> scalars = scalarRegistry.scalarMap();
        for (var entry : scalars.entrySet()) {
            wiringBuilder = wiringBuilder.scalar(entry.getValue());
        }

        try {
            return new SchemaGenerator().makeExecutableSchema(typeRegistry, wiringBuilder.build());
        } catch (RuntimeException e) {
            throw new AwsException("BadRequestException", "Invalid schema: " + e.getMessage(), 400);
        }
    }

    private void validateNoUnknownDirectives(String sdl) {
        Set<String> known = Set.of("skip", "include", "deprecated",
            "aws_api_key", "aws_iam", "aws_cognito_user_pools", "aws_oidc",
            "aws_lambda", "aws_subscribe", "aws_auth", "aws_delta_sync");
        Pattern directivePattern = Pattern.compile("@(\\w+)");
        Matcher matcher = directivePattern.matcher(sdl);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!known.contains(name)) {
                throw new AwsException("BadRequestException",
                    "Unknown directive: @" + name, 400);
            }
        }
    }

    private String injectDirectiveDefinitions(String sdl) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
            directive @aws_api_key on OBJECT | FIELD_DEFINITION
            directive @aws_iam on OBJECT | FIELD_DEFINITION
            directive @aws_cognito_user_pools(cognito_groups: [String!]!) on OBJECT | FIELD_DEFINITION
            directive @aws_oidc on OBJECT | FIELD_DEFINITION
            directive @aws_lambda on OBJECT | FIELD_DEFINITION
            directive @aws_subscribe(mutations: [String!]!) on FIELD_DEFINITION
            directive @aws_auth(cognito_groups: [String!]!) on OBJECT
            directive @aws_delta_sync(tableName: String!, deltaSyncTableTTL: Int!, baseTableTTL: Int!) on OBJECT

            """);
        for (String scalarName : scalarRegistry.scalarMap().keySet()) {
            sb.append("scalar ").append(scalarName).append("\n");
        }
        sb.append("\n");
        sb.append(sdl);
        return sb.toString();
    }
}
