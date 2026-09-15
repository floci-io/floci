package io.github.hectorvent.floci.services.appsync.graphql.execution;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.io.IOException;

/**
 * Parses evaluated mapping-template output the way AppSync does: the whole document must be a
 * single JSON value, duplicate object keys are rejected, and trailing content is rejected.
 */
public final class StrictJson {

    private static final ObjectReader READER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .readerFor(Object.class);

    private StrictJson() {}

    /**
     * @return the parsed value (Map, List, String, Number, Boolean or null)
     * @throws IOException when the text is not exactly one valid JSON document
     */
    public static Object parse(String text) throws IOException {
        return READER.readValue(text);
    }
}
