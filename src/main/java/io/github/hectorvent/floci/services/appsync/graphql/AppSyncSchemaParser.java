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

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class AppSyncSchemaParser {
    private final AppSyncScalarRegistry scalarRegistry;

    @Inject
    public AppSyncSchemaParser(AppSyncScalarRegistry scalarRegistry) {
        this.scalarRegistry = scalarRegistry;
    }

    public GraphQLSchema parse(String sdl) {
        String sdlWithDirectives = injectDirectiveDefinitions(sdl);
        validateNoUnknownDirectives(sdl);

        SchemaParser parser = new SchemaParser();
        TypeDefinitionRegistry typeRegistry;
        try {
            typeRegistry = parser.parse(sdlWithDirectives);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", "Invalid schema: " + e.getMessage(), 400);
        }

        RuntimeWiring.Builder wiringBuilder = RuntimeWiring.newRuntimeWiring();
        for (var entry : scalarRegistry.scalarMap().entrySet()) {
            wiringBuilder = wiringBuilder.scalar(entry.getValue());
        }

        try {
            return new SchemaGenerator().makeExecutableSchema(typeRegistry, wiringBuilder.build());
        } catch (RuntimeException e) {
            throw new AwsException("BadRequestException", "Invalid schema: " + e.getMessage(), 400);
        }
    }

    private void validateNoUnknownDirectives(String sdl) {
        String clean = sdl
            .replaceAll("\"\"\"[\\s\\S]*?\"\"\"", "")
            .replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "")
            .replaceAll("#[^\n]*", "");
        Pattern directivePattern = Pattern.compile("@(\\w+)");
        Matcher matcher = directivePattern.matcher(clean);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!AppSyncDirective.isKnown(name)) {
                throw new AwsException("BadRequestException",
                    "Unknown directive: @" + name, 400);
            }
        }
    }

    private String injectDirectiveDefinitions(String sdl) {
        StringBuilder sb = new StringBuilder();
        for (AppSyncDirective directive : AppSyncDirective.values()) {
            sb.append(directive.sdl()).append("\n");
        }
        sb.append("\n");
        for (String scalarName : scalarRegistry.allScalars().stream()
                .map(s -> s.getName()).collect(Collectors.toList())) {
            sb.append("scalar ").append(scalarName).append("\n");
        }
        sb.append("\n");
        sb.append(sdl);
        return sb.toString();
    }
}
