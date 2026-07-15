package io.github.hectorvent.floci.services.appsync.graphql;

import graphql.GraphQLError;
import graphql.language.SourceLocation;
import graphql.parser.InvalidSyntaxException;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.errors.SchemaProblem;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appsync.graphql.scalars.AppSyncScalarRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        } catch (SchemaProblem e) {
            List<Map<String, Object>> codeErrors = new ArrayList<>();
            for (GraphQLError ge : e.getErrors()) {
                codeErrors.add(toCodeErrorFromGraphQL("PARSER_ERROR", ge));
            }
            throw new AwsException("BadRequestException",
                    "Invalid schema: " + e.getMessage(), 400,
                    buildExtendedData(codeErrors));
        } catch (InvalidSyntaxException e) {
            throw new AwsException("BadRequestException",
                    "Invalid schema: " + e.getMessage(), 400,
                    buildExtendedData(List.of(toCodeError("PARSER_ERROR", e.getMessage(), 0, 0))));
        }

        RuntimeWiring.Builder wiringBuilder = RuntimeWiring.newRuntimeWiring();
        for (var entry : scalarRegistry.scalarMap().entrySet()) {
            wiringBuilder = wiringBuilder.scalar(entry.getValue());
        }

        try {
            return new SchemaGenerator().makeExecutableSchema(typeRegistry, wiringBuilder.build());
        } catch (SchemaProblem e) {
            List<Map<String, Object>> codeErrors = new ArrayList<>();
            for (GraphQLError ge : e.getErrors()) {
                codeErrors.add(toCodeErrorFromGraphQL("VALIDATION_ERROR", ge));
            }
            throw new AwsException("BadRequestException",
                    "Invalid schema: " + e.getMessage(), 400,
                    buildExtendedData(codeErrors));
        } catch (RuntimeException e) {
            throw new AwsException("BadRequestException",
                    "Invalid schema: " + e.getMessage(), 400);
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
                        "Unknown directive: @" + name, 400,
                        buildExtendedData(List.of(
                                toCodeError("VALIDATION_ERROR", "Unknown directive: @" + name, 0, 0))));
            }
        }
    }

    private Map<String, Object> toCodeErrorFromGraphQL(String errorType, GraphQLError ge) {
        List<SourceLocation> locs = ge.getLocations();
        if (locs != null && !locs.isEmpty()) {
            SourceLocation first = locs.get(0);
            return toCodeError(errorType, ge.getMessage(), first.getLine(), first.getColumn());
        }
        return toCodeError(errorType, ge.getMessage(), 0, 0);
    }

    private Map<String, Object> toCodeError(String errorType, String value, int line, int column) {
        Map<String, Object> codeError = new LinkedHashMap<>();
        codeError.put("errorType", errorType);
        codeError.put("value", value);
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("line", line);
        location.put("column", column);
        location.put("span", -1);
        codeError.put("location", location);
        return codeError;
    }

    private Map<String, Object> buildExtendedData(List<Map<String, Object>> codeErrors) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("codeErrors", codeErrors);
        Map<String, Object> extendedData = new LinkedHashMap<>();
        extendedData.put("reason", "CODE_ERROR");
        extendedData.put("detail", detail);
        return extendedData;
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
