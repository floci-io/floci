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
        return part(authorization, 1).flatMap(this::readMap);
    }

    /** The JWT header segment ({@code alg}, {@code kid}, ...), decoded the same way as the claims. */
    public Optional<Map<String, Object>> decodeHeader(String authorization) {
        return part(authorization, 0).flatMap(this::readMap);
    }

    /** The bare JWT (no {@code Bearer } prefix), only when it has the three dot-separated segments. */
    public Optional<String> rawToken(String authorization) {
        return bearerToken(authorization).filter(token -> token.split("\\.", -1).length == 3);
    }

    private Optional<Map<String, Object>> readMap(String base64UrlSegment) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(pad(base64UrlSegment));
            return Optional.of(objectMapper.readValue(json, new TypeReference<>() {}));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<String> part(String authorization, int index) {
        return bearerToken(authorization).map(token -> token.split("\\.", -1))
                .filter(parts -> parts.length == 3)
                .map(parts -> parts[index]);
    }

    private static Optional<String> bearerToken(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return Optional.empty();
        }
        String token = authorization;
        if (authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = authorization.substring(7).trim();
        }
        return Optional.of(token);
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
