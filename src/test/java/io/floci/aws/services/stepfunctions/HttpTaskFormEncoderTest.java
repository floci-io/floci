package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpTaskFormEncoderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void nestedArrayObjectsUseBracketPathsWithoutMutatingTheInput() throws Exception {
        JsonNode body = mapper.readTree("{\"items\":[{\"name\":\"café\",\"flags\":[true,false]}]}");
        JsonNode original = body.deepCopy();

        assertEquals("items%5B0%5D%5Bname%5D=caf%C3%A9&items%5B0%5D%5Bflags%5D%5B0%5D=true"
                + "&items%5B0%5D%5Bflags%5D%5B1%5D=false",
                HttpTaskFormEncoder.encode(body, MissingNode.getInstance()));
        assertEquals(original, body);
    }

    @Test
    void commaSeparatorsStayDistinctFromCommasInsideValues() throws Exception {
        JsonNode options = mapper.readTree("{\"ArrayFormat\":\"COMMAS\"}");
        assertEquals("v=a%2Cb,c", HttpTaskFormEncoder.encode(mapper.readTree("{\"v\":[\"a,b\",\"c\"]}"), options));
        assertEquals("v=a,b%2Cc", HttpTaskFormEncoder.encode(mapper.readTree("{\"v\":[\"a\",\"b,c\"]}"), options));
    }

    @Test
    void namesAndValuesCannotInjectAdditionalFields() throws Exception {
        assertEquals("a%26b%3Dc=%2B%25%26%3D%20%2A~%0D%0A",
                HttpTaskFormEncoder.encode(mapper.readTree("{\"a&b=c\":\"+%&= *~\\r\\n\"}"),
                        MissingNode.getInstance()));
    }

    @Test
    void emptyContainersAreOmittedWhileNullAndEmptyScalarsAreKept() throws Exception {
        assertEquals("null=&empty=", HttpTaskFormEncoder.encode(
                mapper.readTree("{\"array\":[],\"object\":{},\"null\":null,\"empty\":\"\"}"),
                MissingNode.getInstance()));
        assertEquals("", HttpTaskFormEncoder.encode(mapper.nullNode(), MissingNode.getInstance()));
        assertEquals("", HttpTaskFormEncoder.encode(MissingNode.getInstance(), MissingNode.getInstance()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "1", "\"INDICES\"", "{\"ArrayFormat\":null}",
            "{\"ArrayFormat\":{}}", "{\"ArrayFormat\":[]}", "{\"ArrayFormat\":7}",
            "{\"ArrayFormat\":\"INVALID\"}"})
    void malformedOptionsDoNotSilentlySelectTheDefault(String options) throws Exception {
        JsonNode parsed = mapper.readTree(options);
        assertThrows(IllegalArgumentException.class,
                () -> HttpTaskFormEncoder.encode(mapper.createObjectNode(), parsed));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"text\"", "[]", "123", "true"})
    void unverifiedNonObjectRootBodiesRemainExplicitlyUnsupported(String body) throws Exception {
        JsonNode parsed = mapper.readTree(body);
        assertThrows(IllegalArgumentException.class,
                () -> HttpTaskFormEncoder.encode(parsed, MissingNode.getInstance()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"v\":[{}]}", "{\"v\":[[]]}"})
    void unverifiedCompoundCommaArraysAreNotSilentlyCoerced(String body) throws Exception {
        JsonNode parsed = mapper.readTree(body);
        JsonNode options = mapper.readTree("{\"ArrayFormat\":\"COMMAS\"}");
        assertThrows(IllegalArgumentException.class, () -> HttpTaskFormEncoder.encode(parsed, options));
    }
}
