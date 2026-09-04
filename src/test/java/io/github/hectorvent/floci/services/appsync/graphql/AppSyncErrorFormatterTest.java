package io.github.hectorvent.floci.services.appsync.graphql;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Only the transport-error half of {@code AppSyncErrorFormatter} lives in Floci now — the
 * {@code format(ExecutionResult)} half (graphql-java-dependent) moved to floci-app-sync
 * (issue #2917) and is covered by its own test suite there.
 */
class AppSyncErrorFormatterTest {

    private final AppSyncErrorFormatter formatter = new AppSyncErrorFormatter();

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
        Map<String, Object> response = formatter.transportError(
                "MalformedHttpRequestException",
                AppSyncErrorFormatter.MSG_UNABLE_TO_PARSE);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) response.get("errors");
        assertEquals("Unable to parse GraphQL query.", errors.get(0).get("message"));
    }
}
