package io.github.hectorvent.floci.graphql;

import graphql.language.Document;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.OperationDefinition;
import graphql.language.UnionTypeDefinition;
import graphql.parser.Parser;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Direct unit tests of {@link QueryPlanner}'s selection-set walk: the logic the whole
 * {@code @aws_auth}/IAM field-authorization mechanism depends on (issue #2917). These build a
 * {@code GraphQLSchema}/{@code Document} with plain graphql-java (test-scope only, mirroring
 * how the sidecar itself builds schemas) rather than going through {@link GraphqlSidecarServer}'s
 * HTTP layer, so they can assert on the planner's output directly.
 */
class QueryPlannerTest {

    private static final String INTERFACE_SDL = """
            directive @aws_iam on OBJECT | FIELD_DEFINITION
            interface Animal { name: String }
            type Dog implements Animal { name: String secret: String @aws_iam }
            type Cat implements Animal { name: String }
            type Query { pet: Animal }
            """;

    @Test
    void interfaceFieldIsVisitedOnEveryConcreteType() {
        List<QueryPlanner.VisitedField> fields = plan(INTERFACE_SDL, "{ pet { name } }");

        assertCoordinates(fields, "Query.pet", "Cat.name", "Dog.name");
    }

    @Test
    void inlineFragmentNarrowsToTheNamedConcreteTypeOnly() {
        List<QueryPlanner.VisitedField> fields = plan(INTERFACE_SDL, "{ pet { name ... on Dog { secret } } }");

        assertCoordinates(fields, "Query.pet", "Cat.name", "Dog.name", "Dog.secret");
        QueryPlanner.VisitedField secret = fields.stream()
                .filter(field -> "Dog".equals(field.typeName()) && "secret".equals(field.fieldName()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, secret.directives().size());
        assertEquals("aws_iam", secret.directives().get(0).name());
    }

    @Test
    void namedFragmentSpreadNarrowsTheSameWayAsInline() {
        List<QueryPlanner.VisitedField> fields = plan(INTERFACE_SDL,
                "{ pet { name ...dogSecret } } fragment dogSecret on Dog { secret }");

        assertCoordinates(fields, "Query.pet", "Cat.name", "Dog.name", "Dog.secret");
    }

    @Test
    void unionMemberFieldsAreScopedToTheirOwnType() {
        String sdl = """
                type Dog { bark: String }
                type Cat { meow: String }
                union Pet = Dog | Cat
                type Query { pet: Pet }
                """;

        List<QueryPlanner.VisitedField> fields = plan(sdl,
                "{ pet { ... on Dog { bark } ... on Cat { meow } } }");

        assertCoordinates(fields, "Query.pet", "Dog.bark", "Cat.meow");
    }

    @Test
    void fragmentCycleThrowsRatherThanSilentlyReturningAnEmptyPlan() {
        GraphQLSchema schema = buildSchema("type Query { hello: String }");
        Document document = new Parser().parseDocument("{ hello ...a } fragment a on Query { ...a }");
        OperationDefinition operation = document.getDefinitionsOfType(OperationDefinition.class).get(0);

        // This is the invariant the fail-closed fix in GraphqlSidecarServer.plan() depends on:
        // a planner bug or an edge case like this must throw, not return an empty, "nothing to
        // authorize" plan that would let the query through unprotected.
        assertThrows(IllegalArgumentException.class, () -> QueryPlanner.plan(schema, document, operation));
    }

    private static List<QueryPlanner.VisitedField> plan(String sdl, String query) {
        GraphQLSchema schema = buildSchema(sdl);
        Document document = new Parser().parseDocument(query);
        OperationDefinition operation = document.getDefinitionsOfType(OperationDefinition.class).get(0);
        return QueryPlanner.plan(schema, document, operation);
    }

    private static void assertCoordinates(List<QueryPlanner.VisitedField> fields, String... expected) {
        Set<String> actual = fields.stream()
                .map(field -> field.typeName() + "." + field.fieldName())
                .collect(Collectors.toSet());
        assertEquals(Set.of(expected), actual);
    }

    /** Mirrors GraphqlSidecarServer.buildSchema()'s interface/union wiring, minus the scalar/deny concerns. */
    private static GraphQLSchema buildSchema(String sdl) {
        TypeDefinitionRegistry registry = new SchemaParser().parse(sdl);
        RuntimeWiring.Builder wiring = RuntimeWiring.newRuntimeWiring();
        registry.getTypes(InterfaceTypeDefinition.class)
                .forEach(type -> wiring.type(type.getName(), builder -> builder.typeResolver(env -> null)));
        registry.getTypes(UnionTypeDefinition.class)
                .forEach(type -> wiring.type(type.getName(), builder -> builder.typeResolver(env -> null)));
        return new SchemaGenerator().makeExecutableSchema(registry, wiring.build());
    }
}
