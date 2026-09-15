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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectionSetPathsTest {

    private static final String SDL = """
            type Query { getPost(id: ID): Post }
            type Post { postId: ID, title: String, content: String, author(id: ID): Author, comments: [Comment] }
            type Author { authorId: ID, name: String }
            type Comment { id: ID }
            """;

    private static DataFetchingFieldSelectionSet selectionSetFor(String query) {
        AtomicReference<DataFetchingFieldSelectionSet> captured = new AtomicReference<>();
        RuntimeWiring wiring = RuntimeWiring.newRuntimeWiring()
                .type("Query", t -> t.dataFetcher("getPost", env -> {
                    captured.set(env.getSelectionSet());
                    return null;
                }))
                .build();
        GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(SDL), wiring);
        ExecutionResult result = GraphQL.newGraphQL(schema).build().execute(query);
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        return captured.get();
    }

    @Test
    void matchesTheAwsDocumentedExample() {
        List<String> paths = SelectionSetPaths.of(selectionSetFor("""
                query {
                  getPost(id: "1") {
                    postId
                    title
                    secondTitle: title
                    content
                    author(id: "2") { authorId name }
                    secondAuthor: author(id: "789") { authorId }
                    ... on Post { inlineFragComments: comments { id } }
                    ... postFrag
                  }
                }
                fragment postFrag on Post { postFragComments: comments { id } }
                """));
        assertEquals(List.of(
                "postId", "title", "secondTitle", "content",
                "author", "author/authorId", "author/name",
                "secondAuthor", "secondAuthor/authorId",
                "inlineFragComments", "inlineFragComments/id",
                "postFragComments", "postFragComments/id"), paths);
    }

    @Test
    void emptyForScalarFieldsAndNullSets() {
        assertTrue(SelectionSetPaths.of(null).isEmpty());
    }
}
