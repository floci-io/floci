package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AslExecutorIntrinsicEscapesTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private AslExecutor executor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        executor = AslExecutorFixture.builder()
                .objectMapper(mapper)
                .customResourceLiveness(null)
                .build();
    }

    @Test
    void escapedApostropheDoesNotEndTheFormatLiteral() throws Exception {
        JsonNode input = mapper.readTree("{\"firstName\":\"Ann\",\"lastName\":\"Lee\"}");

        JsonNode result = executor.resolvePath(
                "States.Format('Welcome to {} {}\\'s playlist.', $.firstName, $.lastName)", input);

        assertEquals("Welcome to Ann Lee's playlist.", result.asText());
    }

    @Test
    void escapedBracesAreLiteralTextNotPlaceholders() throws Exception {
        JsonNode input = mapper.readTree("{\"name\":\"Ann\"}");

        assertEquals("{ Ann }", executor.resolvePath(
                "States.Format('\\{ {} \\}', $.name)", input).asText());
        assertEquals("{} Ann", executor.resolvePath(
                "States.Format('\\{\\} {}', $.name)", input).asText());
    }

    @Test
    void escapedApostropheAndBackslashWorkInOtherIntrinsics() {
        JsonNode result = executor.resolvePath("States.Array('it\\'s', 'C:\\\\', 'x')",
                mapper.createObjectNode());

        assertEquals(3, result.size());
        assertEquals("it's", result.get(0).asText());
        assertEquals("C:\\", result.get(1).asText());
        assertEquals("x", result.get(2).asText());
    }

    @Test
    void pathValuesAreNotDecodedAsIntrinsicLiterals() throws Exception {
        JsonNode input = mapper.readTree("{\"value\":\"\\\\{\\\\}\\\\'\"}");

        assertEquals("\\{\\}\\'", executor.resolvePath("States.Array($.value)", input).get(0).asText());
    }

    @Test
    void unreservedBackslashSequenceKeepsItsExistingMeaning() throws Exception {
        JsonNode input = mapper.readTree("{\"name\":\"Ann\"}");

        assertEquals("a\\q", executor.resolvePath("States.Array('a\\q')", input).get(0).asText());
        assertEquals("\\q Ann", executor.resolvePath("States.Format('\\q {}', $.name)", input).asText());
    }
}
