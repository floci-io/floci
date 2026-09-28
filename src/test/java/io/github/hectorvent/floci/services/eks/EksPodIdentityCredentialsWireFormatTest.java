package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.eks.model.EksPodIdentityCredentialsResponse;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EksPodIdentityCredentialsWireFormatTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void credentialsResponseMatchesAwsContainerCredentialsSchema() throws Exception {
        EksPodIdentityCredentialsResponse response = new EksPodIdentityCredentialsResponse(
                "ASIAEXAMPLEKEYID123",
                "secretAccessKey1234567890",
                "sessionToken1234567890",
                "123456789012",
                "2026-09-21T21:00:00Z"
        );

        String json = objectMapper.writeValueAsString(response);
        JsonNode node = objectMapper.readTree(json);

        assertEquals("ASIAEXAMPLEKEYID123", node.path("AccessKeyId").asText());
        assertEquals("secretAccessKey1234567890", node.path("SecretAccessKey").asText());
        assertEquals("sessionToken1234567890", node.path("Token").asText());
        assertEquals("123456789012", node.path("AccountId").asText());
        assertEquals("2026-09-21T21:00:00Z", node.path("Expiration").asText());

        Set<String> fieldNames = StreamSupport.stream(
                ((Iterable<String>) () -> node.fieldNames()).spliterator(), false
        ).collect(Collectors.toSet());

        assertEquals(Set.of("AccessKeyId", "SecretAccessKey", "Token", "AccountId", "Expiration"), fieldNames);
    }
}
