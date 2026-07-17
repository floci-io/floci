package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudfront.model.ResponseHeadersPolicy;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResponseHeadersPolicyValidatorTest {

    @Test
    void acceptsCompleteCorsSecurityAndServerTimingConfiguration() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("CorsConfig", validCors());
        config.put("SecurityHeadersConfig", Map.of(
                "StrictTransportSecurity", Map.of(
                        "AccessControlMaxAgeSec", "31536000",
                        "IncludeSubdomains", "true",
                        "Preload", "false",
                        "Override", "true"),
                "FrameOptions", Map.of("FrameOption", "DENY", "Override", "true"),
                "ReferrerPolicy", Map.of(
                        "ReferrerPolicy", "same-origin", "Override", "true"),
                "ContentTypeOptions", Map.of("Override", "true"),
                "XSSProtection", Map.of(
                        "Protection", "true", "ModeBlock", "true", "Override", "true")));
        config.put("ServerTimingHeadersConfig", Map.of("Enabled", "true"));

        assertDoesNotThrow(() -> ResponseHeadersPolicyValidator.validate(policy("valid-policy", config)));
        assertDoesNotThrow(() -> ResponseHeadersPolicyValidator.validate(policy(
                "optional-hsts-flags", Map.of("SecurityHeadersConfig", Map.of(
                        "StrictTransportSecurity", Map.of(
                                "AccessControlMaxAgeSec", "31536000", "Override", "false"))))));
    }

    @Test
    void rejectsMissingCorsFieldsAndInvalidMethods() {
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("AccessControlAllowOrigins", List.of("*"));
        missing.put("AccessControlAllowMethods", List.of("GET"));
        missing.put("AccessControlAllowCredentials", "false");
        missing.put("OriginOverride", "false");

        assertError("InvalidArgument", policy("missing-cors-field", Map.of("CorsConfig", missing)));

        Map<String, Object> invalidMethod = validCors();
        invalidMethod.put("AccessControlAllowMethods", List.of("TRACE"));
        assertError("InvalidArgument",
                policy("invalid-cors-method", Map.of("CorsConfig", invalidMethod)));

        Map<String, Object> lowercaseMethod = validCors();
        lowercaseMethod.put("AccessControlAllowMethods", List.of("get"));
        assertError("InvalidArgument",
                policy("lowercase-cors-method", Map.of("CorsConfig", lowercaseMethod)));
    }

    @Test
    void rejectsUnsafeDuplicateAndForbiddenHeaders() {
        assertError("InvalidArgument", policy("unsafe-header", Map.of(
                "CustomHeadersConfig", List.of(Map.of(
                        "Header", "X-Test", "Value", "safe\r\nInjected: true", "Override", "true")))));

        assertError("InvalidArgument", policy("duplicate-header", Map.of(
                "CustomHeadersConfig", List.of(
                        Map.of("Header", "X-Test", "Value", "a", "Override", "true"),
                        Map.of("Header", "x-test", "Value", "b", "Override", "false")))));

        assertError("InvalidArgument", policy("forbidden-removal", Map.of(
                "RemoveHeadersConfig", List.of("Content-Encoding"))));
    }

    @Test
    void enforcesHeaderCountAndCspLengthSpecificErrors() {
        List<Map<String, String>> custom = IntStream.range(0, 11)
                .mapToObj(index -> Map.of(
                        "Header", "X-Test-" + index,
                        "Value", "value",
                        "Override", "true"))
                .toList();
        assertError("TooManyCustomHeadersInResponseHeadersPolicy",
                policy("too-many-custom", Map.of("CustomHeadersConfig", custom)));

        ResponseHeadersPolicy tooLongCsp = policy("too-long-csp", Map.of(
                "SecurityHeadersConfig", Map.of(
                        "ContentSecurityPolicy", Map.of(
                                "ContentSecurityPolicy", "a".repeat(1_784),
                                "Override", "true"))));
        assertError("TooLongCSPInResponseHeadersPolicy", tooLongCsp);
    }

    @Test
    void validatesPolicyMetadataAndSecurityEnums() {
        assertError("InvalidArgument", policy("spaces are invalid", Map.of()));
        assertError("InvalidArgument", policy("bad-frame", Map.of(
                "SecurityHeadersConfig", Map.of(
                        "FrameOptions", Map.of("FrameOption", "ALLOW", "Override", "true")))));
        assertError("InvalidArgument", policy("lowercase-frame", Map.of(
                "SecurityHeadersConfig", Map.of(
                        "FrameOptions", Map.of("FrameOption", "deny", "Override", "true")))));
        assertError("InvalidArgument", policy("invalid-origin", Map.of(
                "CorsConfig", corsWith("AccessControlAllowOrigins", List.of("example.*")))));
        assertError("InvalidArgument", policy("invalid-expose-wildcard", Map.of(
                "CorsConfig", corsWith("AccessControlExposeHeaders", List.of("X-*")))));
        assertError("InvalidArgument", policy("sampling-precision", Map.of(
                "ServerTimingHeadersConfig", Map.of(
                        "Enabled", "true", "SamplingRate", "12.34567"))));
        assertError("InvalidArgument", policy("max-age-overflow", Map.of(
                "CorsConfig", corsWith(
                        "AccessControlMaxAgeSec", Long.toString((long) Integer.MAX_VALUE + 1)))));
    }

    private static Map<String, Object> validCors() {
        Map<String, Object> cors = new LinkedHashMap<>();
        cors.put("AccessControlAllowCredentials", "false");
        cors.put("AccessControlAllowHeaders", List.of("*", "Authorization"));
        cors.put("AccessControlAllowMethods", List.of("GET", "HEAD", "OPTIONS"));
        cors.put("AccessControlAllowOrigins", List.of("https://*.example.com"));
        cors.put("AccessControlExposeHeaders", List.of("ETag"));
        cors.put("AccessControlMaxAgeSec", "600");
        cors.put("OriginOverride", "true");
        return cors;
    }

    private static Map<String, Object> corsWith(String field, Object value) {
        Map<String, Object> cors = validCors();
        cors.put(field, value);
        return cors;
    }

    private static ResponseHeadersPolicy policy(String name, Map<String, Object> config) {
        ResponseHeadersPolicy policy = new ResponseHeadersPolicy();
        policy.setName(name);
        policy.setConfig(config);
        return policy;
    }

    private static void assertError(String expectedCode, ResponseHeadersPolicy policy) {
        AwsException error = assertThrows(AwsException.class,
                () -> ResponseHeadersPolicyValidator.validate(policy));
        assertEquals(expectedCode, error.getErrorCode());
    }
}
