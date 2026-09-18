package io.github.hectorvent.floci.services.appsync.graphql;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AppSyncErrorFormatter} now formats the plain {@code Map}/{@code List} JSON the GraphQL
 * sidecar's {@code /v1/execute} returns (graphql-java's own {@code toSpecification()} shape),
 * not a live graphql-java {@code ExecutionResult} (issue #2917); these build that shape by hand.
 */
class AppSyncErrorFormatterTest {

    private final AppSyncErrorFormatter formatter = new AppSyncErrorFormatter();

    @Test
    void invalidSyntaxMapsToTopLevelSyntaxError() {
        Map<String, Object> sidecarResult = Map.of(
                "errors", List.of(sidecarError("Invalid syntax near '{'", List.of(Map.of("line", 1, "column", 1)),
                        null, "InvalidSyntax")));

        Map<String, Object> response = formatter.format(sidecarResult);

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
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nope", null);
        Map<String, Object> sidecarResult = Map.of(
                "data", data,
                "errors", List.of(sidecarError("Validation error of type FieldUndefined: Field 'nope' is undefined",
                        List.of(Map.of("line", 1, "column", 3)), List.of("nope"), "ValidationError")));

        Map<String, Object> response = formatter.format(sidecarResult);

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

        assertEquals("Unable to parse GraphQL query.",
                ((Map<?, ?>) ((List<?>) forBraces.get("errors")).get(0)).get("message"));
    }

    @Test
    void successfulDataIncludedWithoutErrors() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hello", null);
        Map<String, Object> sidecarResult = Map.of("data", data);

        Map<String, Object> response = formatter.format(sidecarResult);

        @SuppressWarnings("unchecked")
        Map<String, Object> responseData = (Map<String, Object>) response.get("data");
        assertNull(responseData.get("hello"));
        assertTrue(responseData.containsKey("hello"));
        assertFalse(response.containsKey("errors"));
    }

    @Test
    void unauthorizedClassificationMapsToUnauthorized() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hello", null);
        Map<String, Object> sidecarResult = Map.of(
                "data", data,
                "errors", List.of(sidecarError("Not Authorized to access hello on type Query",
                        null, List.of("hello"), "Unauthorized")));

        Map<String, Object> response = formatter.format(sidecarResult);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) response.get("errors");
        assertEquals("Unauthorized", errors.get(0).get("errorType"));
        assertEquals("Not Authorized to access hello on type Query", errors.get(0).get("message"));
        assertEquals(List.of("hello"), errors.get(0).get("path"));
        assertNull(errors.get(0).get("errorInfo"));
    }

    private static Map<String, Object> sidecarError(String message, List<Map<String, Object>> locations,
                                                     List<String> path, String classification) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message);
        if (locations != null) {
            error.put("locations", locations);
        }
        if (path != null) {
            error.put("path", path);
        }
        if (classification != null) {
            error.put("extensions", Map.of("classification", classification));
        }
        return error;
    }
}
