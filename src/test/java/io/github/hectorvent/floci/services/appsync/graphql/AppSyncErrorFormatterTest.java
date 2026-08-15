package io.github.hectorvent.floci.services.appsync.graphql;

import graphql.ErrorType;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.language.SourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppSyncErrorFormatterTest {

    private final AppSyncErrorFormatter formatter = new AppSyncErrorFormatter();

    @Test
    void invalidSyntaxMapsToTopLevelSyntaxError() {
        GraphQLError syntaxError = GraphqlErrorBuilder.newError()
                .message("Invalid syntax near '{'")
                .errorType(ErrorType.InvalidSyntax)
                .location(new SourceLocation(1, 1))
                .build();
        ExecutionResult result = ExecutionResultImpl.newExecutionResult()
                .addError(syntaxError)
                .build();

        Map<String, Object> response = formatter.format(result);

        assertFalse(response.containsKey("data"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) response.get("errors");
        assertEquals(1, errors.size());
        Map<String, Object> error = errors.get(0);
        assertEquals("Invalid syntax near '{'", error.get("message"));
        assertEquals("SyntaxError", error.get("errorType"));
        assertTrue(error.containsKey("errorInfo"));
        assertNull(error.get("errorInfo"));
        assertFalse(error.containsKey("extensions"));
    }

    @Test
    void validationErrorUsesTopLevelErrorTypeNotExtensionsOnly() {
        GraphQLError validationError = GraphqlErrorBuilder.newError()
                .message("Validation error of type FieldUndefined: Field 'nope' is undefined")
                .errorType(ErrorType.ValidationError)
                .location(new SourceLocation(1, 3))
                .path(List.of("nope"))
                .build();
        ExecutionResult result = ExecutionResultImpl.newExecutionResult()
                .data(null)
                .addError(validationError)
                .build();

        Map<String, Object> response = formatter.format(result);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) response.get("errors");
        Map<String, Object> error = errors.get(0);
        assertEquals("ValidationError", error.get("errorType"));
        assertTrue(error.containsKey("errorInfo"));
        assertNull(error.get("errorInfo"));
        assertEquals(List.of("nope"), error.get("path"));
        assertFalse(error.containsKey("extensions"),
                "AppSync wire uses top-level errorType, not extensions-only classification");
    }

    @Test
    void emptyBodyMessageMatchesAppSyncSample() {
        Map<String, Object> response = formatter.transportError(
                "MalformedHttpRequestException",
                AppSyncErrorFormatter.MSG_EMPTY_BODY);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) response.get("errors");
        assertEquals(1, errors.size());
        assertEquals("MalformedHttpRequestException", errors.get(0).get("errorType"));
        assertEquals("Request body is empty.", errors.get(0).get("message"));
    }

    @Test
    void unparseableBodyMessageMatchesAppSyncSample() {
        Map<String, Object> forBraces = formatter.transportError(
                "MalformedHttpRequestException",
                AppSyncErrorFormatter.MSG_UNABLE_TO_PARSE);
        Map<String, Object> forArray = formatter.transportError(
                "MalformedHttpRequestException",
                AppSyncErrorFormatter.MSG_UNABLE_TO_PARSE);

        assertEquals("Unable to parse GraphQL query.",
                ((List<?>) forBraces.get("errors")).isEmpty() ? null
                        : ((Map<?, ?>) ((List<?>) forBraces.get("errors")).get(0)).get("message"));
        assertEquals("Unable to parse GraphQL query.",
                ((Map<?, ?>) ((List<?>) forArray.get("errors")).get(0)).get("message"));
    }

    @Test
    void successfulDataIncludedWithoutErrors() {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("hello", null);
        ExecutionResult result = ExecutionResultImpl.newExecutionResult()
                .data(data)
                .build();

        Map<String, Object> response = formatter.format(result);

        @SuppressWarnings("unchecked")
        Map<String, Object> responseData = (Map<String, Object>) response.get("data");
        assertNull(responseData.get("hello"));
        assertTrue(responseData.containsKey("hello"));
        assertFalse(response.containsKey("errors"));
    }
}
