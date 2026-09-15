package io.github.hectorvent.floci.graphql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.execution.DataFetcherResult;
import graphql.language.Document;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.OperationDefinition;
import graphql.language.SourceLocation;
import graphql.language.UnionTypeDefinition;
import graphql.parser.Parser;
import graphql.schema.Coercing;
import graphql.schema.DataFetcher;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLUnionType;
import graphql.schema.TypeResolver;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.errors.SchemaProblem;
import io.github.hectorvent.floci.graphql.scalars.AppSyncScalarRegistry;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Stateless HTTP boundary around graphql-java for Floci AppSync (issue #2917).
 *
 * <p>This wraps the upstream library only: SDL parsing/schema generation and query execution.
 * It has no AppSync-specific knowledge: no {@code @aws_auth}, no IAM field enforcement, no
 * AWS custom-scalar semantics. Every call sends the full SDL plus the request; nothing is
 * cached or registered across calls, matching the Cedar sidecar's stateless design (#3128).
 *
 * <p>Scalars declared in the SDL get their real AWS coercion (AWSDateTime, AWSJSON, etc.) via
 * {@link AppSyncScalarRegistry}, the same 17 scalar types AppSync itself defines, unchanged
 * from what used to run in Floci's own process. Any other non-built-in scalar name falls back
 * to pass-through (identity) coercion so schema generation still succeeds. This is the one
 * exception to "no AWS-specific behavior here": scalar (de)serialization runs inline as part of
 * graphql-java's own execution, so there's no clean way to apply it before or after a remote
 * call the way {@code @aws_auth} redaction is; see {@code /v1/plan}/{@code denyFields} below.
 *
 * <p>{@code @aws_auth}/IAM field authorization never runs here either: {@code /v1/plan} tells
 * Floci which {@code (typeName, fieldName)} coordinates a query would visit and what directives
 * are on each, with zero interpretation of what those directives mean. Floci computes the
 * actual allow/deny decision (using its own IAM/Cognito/Lambda-authorizer logic) and passes the
 * result back as an opaque {@code denyFields} list on {@code /v1/execute}; this sidecar just
 * nulls those coordinates out with the given error, never knowing why.
 */
public final class GraphqlSidecarServer {
    private static final Logger LOG = Logger.getLogger(GraphqlSidecarServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AppSyncScalarRegistry SCALARS = new AppSyncScalarRegistry();
    /** Scalars graphql-java's RuntimeWiring registers by default; anything else needs a wiring entry. */
    private static final List<String> BUILTIN_SCALAR_NAMES = List.of("Int", "Float", "String", "Boolean", "ID");

    private GraphqlSidecarServer() {
    }

    public static void main(String[] args) throws IOException {
        String portEnv = System.getenv().getOrDefault("PORT", "8181");
        int port;
        try {
            port = Integer.parseInt(portEnv);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("PORT must be a valid integer, got: " + portEnv, e);
        }
        start(port);
    }

    public static HttpServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/health", exchange -> respondText(exchange, 200, "ok"));
        server.createContext("/v1/schema/validate", exchange -> handleJson(exchange, GraphqlSidecarServer::validateSchema));
        server.createContext("/v1/plan", exchange -> handleJson(exchange, GraphqlSidecarServer::plan));
        server.createContext("/v1/execute", exchange -> handleJson(exchange, GraphqlSidecarServer::execute));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        LOG.infov("Floci GraphQL sidecar listening on port {0}", port);
        return server;
    }

    private static JsonNode validateSchema(JsonNode body) {
        String sdl = requiredText(body, "sdl");
        buildSchema(sdl);
        return MAPPER.createObjectNode().put("valid", true);
    }

    /**
     * Lists every {@code (typeName, fieldName)} coordinate the query would visit, with the
     * directives applied to each, with no interpretation of what those directives mean, plus the
     * selected operation's type (query/mutation/subscription), so Floci can reject subscriptions
     * over HTTP itself. Lets Floci precompute field-level authorization (e.g. {@code
     * @aws_auth}/IAM) before calling {@code /v1/execute}. See {@link QueryPlanner}.
     *
     * <p>Only a query that doesn't parse, or names an operation that doesn't exist, gets an
     * empty plan (no fields, no operation type) instead of an error: that case is real GraphQL
     * execution's problem to report, not planning's: {@code /v1/execute} runs the same query
     * through graphql-java's own engine, which already produces the correct spec-compliant
     * 200-with-errors response for it. Once the query parses and names a real operation, though,
     * it WILL go on to execute real fields unless something stops it, so from that point on,
     * a {@link QueryPlanner} failure is deliberately left to propagate as a 500 rather than
     * being swallowed into an empty plan: a planner bug must refuse the request, not silently
     * authorize everything by reporting zero fields to redact.
     */
    private static JsonNode plan(JsonNode body) {
        String sdl = requiredText(body, "sdl");
        String query = requiredText(body, "query");
        String operationName = body.path("operationName").asText(null);

        GraphQLSchema schema = buildSchema(sdl);
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode array = result.putArray("fields");

        Document document;
        OperationDefinition operation;
        try {
            document = new Parser().parseDocument(query);
            operation = selectOperation(document, operationName);
        } catch (RuntimeException e) {
            result.putNull("operationType");
            return result;
        }

        result.put("operationType", operation.getOperation().name());
        // Deliberately uncaught from here; see the class-level reasoning above.
        List<QueryPlanner.VisitedField> fields = QueryPlanner.plan(schema, document, operation);
        for (QueryPlanner.VisitedField field : fields) {
            ObjectNode node = array.addObject();
            node.put("typeName", field.typeName());
            node.put("fieldName", field.fieldName());
            writeDirectives(node.putArray("directives"), field.directives());
            writeDirectives(node.putArray("typeDirectives"), field.typeDirectives());
        }
        return result;
    }

    private static void writeDirectives(ArrayNode array, List<QueryPlanner.AppliedDirective> directives) {
        for (QueryPlanner.AppliedDirective directive : directives) {
            ObjectNode directiveNode = array.addObject();
            directiveNode.put("name", directive.name());
            directiveNode.set("args", MAPPER.valueToTree(directive.args()));
        }
    }

    private static JsonNode execute(JsonNode body) {
        String sdl = requiredText(body, "sdl");
        String query = requiredText(body, "query");
        Map<String, Object> variables = body.has("variables") && !body.get("variables").isNull()
                ? MAPPER.convertValue(body.get("variables"), Map.class) : Map.of();
        String operationName = body.path("operationName").asText(null);
        List<DenyEntry> denyFields = parseDenyFields(body.get("denyFields"));

        GraphQLSchema schema = buildSchema(sdl);
        if (!denyFields.isEmpty()) {
            schema = applyDenyFields(schema, denyFields);
        }
        GraphQL graphQL = GraphQL.newGraphQL(schema).build();
        ExecutionInput.Builder input = ExecutionInput.newExecutionInput()
                .query(query)
                .variables(variables);
        if (operationName != null && !operationName.isBlank()) {
            input.operationName(operationName);
        }
        ExecutionResult result = graphQL.execute(input.build());
        return MAPPER.valueToTree(result.toSpecification());
    }

    private static OperationDefinition selectOperation(Document document, String operationName) {
        List<OperationDefinition> operations = document.getDefinitionsOfType(OperationDefinition.class);
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("Query contains no operations.");
        }
        if (operationName == null || operationName.isBlank()) {
            return operations.get(0);
        }
        return operations.stream()
                .filter(op -> operationName.equals(op.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown operation: " + operationName));
    }

    /** {@code errorType}/{@code message} are opaque to this sidecar: Floci decides what they say. */
    private record DenyEntry(String typeName, String fieldName, String errorType, String message) {
    }

    private static List<DenyEntry> parseDenyFields(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<DenyEntry> entries = new ArrayList<>();
        for (JsonNode entry : node) {
            entries.add(new DenyEntry(
                    requiredText(entry, "typeName"),
                    requiredText(entry, "fieldName"),
                    entry.path("errorType").asText("FieldUnauthorized"),
                    entry.path("message").asText("Not authorized")));
        }
        return entries;
    }

    /** Wraps only the denied coordinates' DataFetchers; every other field executes normally. */
    private static GraphQLSchema applyDenyFields(GraphQLSchema schema, List<DenyEntry> denyFields) {
        // FieldCoordinates is just a (typeName, fieldName) pair, so there's no need to look up the actual
        // GraphQLFieldDefinition, so no need to scan the schema's types/fields to find it either.
        // denyFields is always schema-derived (it comes from this same schema's own plan output),
        // so a coordinate that doesn't exist here can't happen.
        GraphQLCodeRegistry.Builder code = GraphQLCodeRegistry.newCodeRegistry(schema.getCodeRegistry());
        for (DenyEntry entry : denyFields) {
            code.dataFetcher(FieldCoordinates.coordinates(entry.typeName(), entry.fieldName()), deniedDataFetcher(entry));
        }
        GraphQLCodeRegistry registry = code.build();
        return schema.transform(builder -> builder.codeRegistry(registry));
    }

    private static DataFetcher<Object> deniedDataFetcher(DenyEntry deny) {
        return env -> DataFetcherResult.newResult()
                .data(null)
                .error(GraphqlErrorBuilder.newError()
                        .message(deny.message())
                        // Same "classification" key graphql-java's own toSpecification() uses for
                        // its built-in errors, so Floci's error formatter has one code path, not two.
                        .extensions(Map.of("classification", deny.errorType()))
                        .path(env.getExecutionStepInfo().getPath().toList())
                        .build())
                .build();
    }

    private static GraphQLSchema buildSchema(String sdl) {
        TypeDefinitionRegistry registry;
        try {
            // SchemaParser.parse() documents itself as throwing SchemaProblem, not
            // InvalidSyntaxException directly; it catches the parser's own InvalidSyntaxException
            // internally and wraps it into a SchemaProblem carrying an InvalidSyntaxError.
            registry = new SchemaParser().parse(sdl);
        } catch (SchemaProblem e) {
            throw schemaValidationException(issuesFrom(e, "PARSER_ERROR"));
        }

        RuntimeWiring.Builder wiring = RuntimeWiring.newRuntimeWiring();
        registry.scalars().keySet().stream()
                .filter(name -> !BUILTIN_SCALAR_NAMES.contains(name))
                .forEach(name -> wiring.scalar(SCALARS.getScalar(name).orElseGet(() -> passThroughScalar(name))));
        registry.getTypes(InterfaceTypeDefinition.class)
                .forEach(type -> wiring.type(type.getName(), builder -> builder.typeResolver(firstPossibleType(type.getName()))));
        registry.getTypes(UnionTypeDefinition.class)
                .forEach(type -> wiring.type(type.getName(), builder -> builder.typeResolver(firstPossibleType(type.getName()))));

        try {
            return new SchemaGenerator().makeExecutableSchema(registry, wiring.build());
        } catch (SchemaProblem e) {
            throw schemaValidationException(issuesFrom(e, "VALIDATION_ERROR"));
        } catch (RuntimeException e) {
            // Covers any other graphql-java unchecked failure this SDL could trigger.
            throw schemaValidationException(List.of(new SchemaIssue("VALIDATION_ERROR", safeMessage(e), 0, 0)));
        }
    }

    private static List<SchemaIssue> issuesFrom(SchemaProblem problem, String category) {
        List<SchemaIssue> issues = new ArrayList<>();
        for (GraphQLError error : problem.getErrors()) {
            List<SourceLocation> locations = error.getLocations();
            SourceLocation first = locations == null || locations.isEmpty() ? null : locations.get(0);
            issues.add(new SchemaIssue(category, error.getMessage(), lineOf(first), columnOf(first)));
        }
        return issues;
    }

    private static int lineOf(SourceLocation location) {
        return location == null ? 0 : location.getLine();
    }

    private static int columnOf(SourceLocation location) {
        return location == null ? 0 : location.getColumn();
    }

    private static SchemaValidationException schemaValidationException(List<SchemaIssue> issues) {
        String message = issues.isEmpty() ? "Invalid GraphQL schema."
                : "Invalid GraphQL schema: " + issues.get(0).message();
        return new SchemaValidationException(message, issues);
    }

    /** One structured problem in an SDL: carries what {@code StartSchemaCreation}'s codeErrors need. */
    private record SchemaIssue(String category, String message, int line, int column) {
    }

    /** Distinct from a plain {@link IllegalArgumentException} only so {@code /v1/schema/validate} can return {@link #issues}. */
    private static final class SchemaValidationException extends IllegalArgumentException {
        private final List<SchemaIssue> issues;

        SchemaValidationException(String message, List<SchemaIssue> issues) {
            super(message);
            this.issues = issues;
        }

        List<SchemaIssue> issues() {
            return issues;
        }
    }

    /** No AppSync scalar semantics live here beyond the AWS scalars (see class doc); this is just a fallback. */
    private static GraphQLScalarType passThroughScalar(String name) {
        return GraphQLScalarType.newScalar()
                .name(name)
                .coercing(new Coercing<Object, Object>() {
                    @Override
                    public Object serialize(Object input) {
                        return input;
                    }

                    @Override
                    public Object parseValue(Object input) {
                        return input;
                    }

                    @Override
                    public Object parseLiteral(Object input) {
                        return input;
                    }
                })
                .build();
    }

    /** Placeholder until real resolver -> data-source dispatch exists (out of scope for #2917). */
    private static TypeResolver firstPossibleType(String typeName) {
        return env -> {
            GraphQLSchema schema = env.getSchema();
            GraphQLType type = schema.getType(typeName);
            List<GraphQLObjectType> candidates;
            if (type instanceof GraphQLInterfaceType interfaceType) {
                candidates = schema.getImplementations(interfaceType);
            } else if (type instanceof GraphQLUnionType unionType) {
                candidates = unionType.getTypes().stream()
                        .filter(GraphQLObjectType.class::isInstance)
                        .map(GraphQLObjectType.class::cast)
                        .toList();
            } else {
                candidates = List.of();
            }
            return candidates.isEmpty() ? null : candidates.get(0);
        };
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.asText().isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return value.asText();
    }

    private static void handleJson(HttpExchange exchange, JsonHandler handler) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, error("Method not allowed"));
            return;
        }
        try {
            JsonNode body = MAPPER.readTree(exchange.getRequestBody());
            respondJson(exchange, 200, handler.handle(body));
        } catch (SchemaValidationException e) {
            respondJson(exchange, 400, schemaValidationError(e));
        } catch (IllegalArgumentException e) {
            respondJson(exchange, 400, error(safeMessage(e)));
        } catch (Exception e) {
            respondJson(exchange, 500, error(safeMessage(e)));
        }
    }

    private static ObjectNode error(String message) {
        return MAPPER.createObjectNode().put("error", message);
    }

    /** {@code /v1/schema/validate} uses {@code issues} for structured, per-problem detail; the other endpoints just read {@code error}. */
    private static ObjectNode schemaValidationError(SchemaValidationException e) {
        ObjectNode node = error(safeMessage(e));
        ArrayNode issues = node.putArray("issues");
        for (SchemaIssue issue : e.issues()) {
            ObjectNode issueNode = issues.addObject();
            issueNode.put("category", issue.category());
            issueNode.put("message", issue.message());
            issueNode.put("line", issue.line());
            issueNode.put("column", issue.column());
        }
        return node;
    }

    private static void respondJson(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void respondText(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    private interface JsonHandler {
        JsonNode handle(JsonNode body) throws Exception;
    }
}
