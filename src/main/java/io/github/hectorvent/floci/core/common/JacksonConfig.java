package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;

/**
 * Raises this application's Jackson string-length limit so that large inline payloads
 * (e.g. base64-encoded Lambda ZipFile up to ~67 MB) are accepted.
 * AWS allows 50 MB direct upload; base64 expands that by ~33%.
 */
@Singleton
public class JacksonConfig implements ObjectMapperCustomizer {

    private static final int MAX_STRING_LENGTH = 100_000_000; // 100 MB

    @Override
    public void customize(ObjectMapper mapper) {
        mapper.getFactory().setStreamReadConstraints(
                StreamReadConstraints.builder()
                        .maxStringLength(MAX_STRING_LENGTH)
                        .build());
    }
}
