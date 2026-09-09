package io.github.hectorvent.floci.services.appsync.graphql.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class JwtClaimsDecoder {

    private final ObjectMapper objectMapper;

    @Inject
    public JwtClaimsDecoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<Map<String, Object>> decode(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return Optional.empty();
        }
        String token = authorization;
        if (authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = authorization.substring(7).trim();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            byte[] json = Base64.getUrlDecoder().decode(pad(parts[1]));
            Map<String, Object> claims = objectMapper.readValue(json, new TypeReference<>() {});
            return Optional.of(claims);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String pad(String value) {
        int rem = value.length() % 4;
        if (rem == 0) {
            return value;
        }
        return value + "=".repeat(4 - rem);
    }

    static String encode(Map<String, Object> claims, ObjectMapper objectMapper) {
        try {
            String header = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String payload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(claims));
            return header + "." + payload + ".sig";
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
