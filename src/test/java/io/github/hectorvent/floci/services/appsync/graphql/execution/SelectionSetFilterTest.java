package io.github.hectorvent.floci.services.appsync.graphql.execution;

import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SelectionSetFilterTest {

    private static final String SDL = """
            type Query { todo: Todo }
            type Todo { id: ID, title: String, owner: Owner, tags: [Tag] }
            type Owner { name: String, email: String }
            type Tag { label: String, weight: Int }
            """;

    private static DataFetchingFieldSelectionSet selectionSetFor(String query) {
        AtomicReference<DataFetchingFieldSelectionSet> captured = new AtomicReference<>();
        RuntimeWiring wiring = RuntimeWiring.newRuntimeWiring()
                .type("Query", t -> t.dataFetcher("todo", env -> {
                    captured.set(env.getSelectionSet());
                    return null;
                }))
                .build();
        GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(SDL), wiring);
        ExecutionResult result = GraphQL.newGraphQL(schema).build().execute(query);
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        return captured.get();
    }

    private static final Map<String, Object> DATA = Map.of(
            "id", "1",
            "title", "milk",
            "secret", "hidden",
            "owner", Map.of("name", "ann", "email", "a@x"),
            "tags", List.of(Map.of("label", "a", "weight", 1), Map.of("label", "b", "weight", 2)));

    @Test
    void keepsOnlySelectedFieldsRecursively() {
        Object filtered = SelectionSetFilter.filter(DATA, selectionSetFor("{ todo { id owner { name } tags { label } } }"));
        assertEquals(Map.of("id", "1", "owner", Map.of("name", "ann"),
                "tags", List.of(Map.of("label", "a"), Map.of("label", "b"))), filtered);
    }

    @Test
    void aliasesFilterByFieldNameNotResultKey() {
        Object filtered = SelectionSetFilter.filter(DATA, selectionSetFor("{ todo { theTitle: title } }"));
        assertEquals(Map.of("title", "milk"), filtered);
    }

    @Test
    void fragmentsAreExpanded() {
        Object filtered = SelectionSetFilter.filter(DATA,
                selectionSetFor("{ todo { ...F } } fragment F on Todo { id title }"));
        assertEquals(Map.of("id", "1", "title", "milk"), filtered);
    }

    @Test
    void nonMapDataPassesThrough() {
        DataFetchingFieldSelectionSet set = selectionSetFor("{ todo { id } }");
        assertEquals("text", SelectionSetFilter.filter("text", set));
        assertEquals(42, SelectionSetFilter.filter(42, set));
        assertNull(SelectionSetFilter.filter(null, set));
    }

    @Test
    void emptySelectionSetPassesThrough() {
        assertEquals(DATA, SelectionSetFilter.filter(DATA, null));
    }
}
