package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.ses.model.BulkEmailEntryResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.ParameterizedTest;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for the static helpers left on the {@link SesService} facade: the bulk-send
 * template-data merge and the bulk error-code mapping.
 */
class SesServiceHelperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void bothNull_returnsNull() {
        assertNull(SesService.mergeTemplateData(null, null));
    }

    @Test
    void emptyReplacement_returnsDefaultsWithoutCopy() {
        JsonNode defaults = MAPPER.createObjectNode().put("team", "floci");
        JsonNode replacement = MAPPER.createObjectNode();
        assertSame(defaults, SesService.mergeTemplateData(defaults, replacement));
    }

    @Test
    void emptyDefaults_returnsReplacementWithoutCopy() {
        JsonNode defaults = MAPPER.createObjectNode();
        JsonNode replacement = MAPPER.createObjectNode().put("name", "Alice");
        assertSame(replacement, SesService.mergeTemplateData(defaults, replacement));
    }

    @Test
    void bothNonEmpty_replacementOverridesDefaults() {
        JsonNode defaults = MAPPER.createObjectNode().put("team", "floci").put("name", "default");
        JsonNode replacement = MAPPER.createObjectNode().put("name", "Alice");
        JsonNode merged = SesService.mergeTemplateData(defaults, replacement);
        assertEquals("Alice", merged.path("name").asText());
        assertEquals("floci", merged.path("team").asText());
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("mapErrorCodeToBulkStatusCases")
    void mapErrorCodeToBulkStatus_returnsExpected(String errorCode, BulkEmailEntryResult.Status expected) {
        assertEquals(expected, SesService.mapErrorCodeToBulkStatus(errorCode));
    }

    static Stream<Arguments> mapErrorCodeToBulkStatusCases() {
        return Stream.of(
                Arguments.of("InvalidParameterValue",     BulkEmailEntryResult.Status.INVALID_PARAMETER),
                Arguments.of("MissingRenderingAttribute", BulkEmailEntryResult.Status.INVALID_PARAMETER),
                Arguments.of("InvalidRenderingParameter", BulkEmailEntryResult.Status.INVALID_PARAMETER),
                Arguments.of("SomethingElse",             BulkEmailEntryResult.Status.FAILED)
        );
    }
}
