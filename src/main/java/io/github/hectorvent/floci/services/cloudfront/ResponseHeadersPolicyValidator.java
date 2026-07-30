package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudfront.model.ResponseHeadersPolicy;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** AWS-compatible validation shared by response-headers policy create and update operations. */
final class ResponseHeadersPolicyValidator {

    static final int MAX_CUSTOM_HEADERS = 10;
    static final int MAX_REMOVE_HEADERS = 10;
    static final int MAX_HEADER_NAME_LENGTH = 256;
    static final int MAX_HEADER_VALUE_LENGTH = 1_783;
    static final int MAX_CUSTOM_HEADER_BYTES = 10_240;

    private static final Pattern POLICY_NAME = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Set<String> CORS_METHODS = Set.of(
            "GET", "DELETE", "HEAD", "OPTIONS", "PATCH", "POST", "PUT", "ALL");
    private static final Set<String> FRAME_OPTIONS = Set.of("DENY", "SAMEORIGIN");
    private static final Set<String> REFERRER_POLICIES = Set.of(
            "no-referrer", "no-referrer-when-downgrade", "origin", "origin-when-cross-origin",
            "same-origin", "strict-origin", "strict-origin-when-cross-origin", "unsafe-url");
    private static final Set<String> FORBIDDEN_REMOVALS = Set.of(
            "connection", "content-encoding", "content-length", "expect", "host", "keep-alive",
            "proxy-authenticate", "proxy-authorization", "proxy-connection", "trailer",
            "transfer-encoding", "upgrade", "via", "warning", "x-accel-buffering",
            "x-accel-charset", "x-accel-limit-rate", "x-accel-redirect", "x-amzn-auth",
            "x-amzn-cf-billing", "x-amzn-cf-id", "x-amzn-cf-xff", "x-amzn-errortype",
            "x-amzn-fle-profile", "x-amzn-header-count", "x-amzn-header-order",
            "x-amzn-lambda-integration-tag", "x-amzn-requestid", "x-cache",
            "x-forwarded-proto", "x-real-ip");
    private static final Set<String> FORBIDDEN_CUSTOM_HEADERS = Set.of(
            "connection", "content-length", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "proxy-connection", "te", "trailer",
            "transfer-encoding", "upgrade", "via");

    private ResponseHeadersPolicyValidator() {
    }

    static void validate(ResponseHeadersPolicy policy) {
        if (policy == null || policy.getName() == null
                || !POLICY_NAME.matcher(policy.getName()).matches()) {
            throw invalid("Response headers policy Name must contain 1-128 letters, digits, hyphens, or underscores.");
        }
        if (policy.getComment() != null && policy.getComment().length() > 128) {
            throw invalid("Response headers policy Comment cannot exceed 128 characters.");
        }
        Map<String, Object> config = policy.getConfig();
        if (config == null) {
            policy.setConfig(Map.of());
            return;
        }
        validateCors(config.get("CorsConfig"));
        validateCustomHeaders(config.get("CustomHeadersConfig"));
        validateRemoveHeaders(config.get("RemoveHeadersConfig"));
        validateSecurityHeaders(config.get("SecurityHeadersConfig"));
        validateServerTiming(config.get("ServerTimingHeadersConfig"));
    }

    private static void validateCors(Object value) {
        if (value == null) {
            return;
        }
        Map<String, Object> cors = objectMap(value, "CorsConfig");
        requireBoolean(cors, "AccessControlAllowCredentials");
        requireBoolean(cors, "OriginOverride");
        List<?> headers = requiredList(cors, "AccessControlAllowHeaders", false);
        List<?> methods = requiredList(cors, "AccessControlAllowMethods", true);
        List<?> origins = requiredList(cors, "AccessControlAllowOrigins", true);

        validateCorsHeaderPatterns(headers, "AccessControlAllowHeaders entry", true);
        Set<String> normalizedMethods = new HashSet<>();
        for (Object method : methods) {
            String configured = requireSafeText(method, "AccessControlAllowMethods entry", 16);
            if (!CORS_METHODS.contains(configured)) {
                throw invalid("Unsupported CORS method: " + configured);
            }
            if (!normalizedMethods.add(configured)) {
                throw invalid("AccessControlAllowMethods entries must be unique.");
            }
        }
        validateOrigins(origins);
        Object expose = cors.get("AccessControlExposeHeaders");
        if (expose != null) {
            validateCorsHeaderPatterns(objectList(expose, "AccessControlExposeHeaders"),
                    "AccessControlExposeHeaders entry", false);
        }
        Object maxAge = cors.get("AccessControlMaxAgeSec");
        if (maxAge != null) {
            requireNonNegativeLong(maxAge, "AccessControlMaxAgeSec");
        }
    }

    private static void validateCustomHeaders(Object value) {
        if (value == null) {
            return;
        }
        List<?> headers = objectList(value, "CustomHeadersConfig");
        if (headers.size() > MAX_CUSTOM_HEADERS) {
            throw new AwsException("TooManyCustomHeadersInResponseHeadersPolicy",
                    "A response headers policy can contain at most " + MAX_CUSTOM_HEADERS
                            + " custom headers.", 400);
        }
        Set<String> seen = new HashSet<>();
        int combinedLength = 0;
        for (Object item : headers) {
            Map<String, Object> header = objectMap(item, "CustomHeadersConfig item");
            String name = requireHeaderName(header.get("Header"));
            String normalized = name.toLowerCase(Locale.ROOT);
            if (!seen.add(normalized)) {
                throw invalid("Custom response header names must be unique.");
            }
            if (FORBIDDEN_CUSTOM_HEADERS.contains(normalized)) {
                throw invalid("Custom response header is not allowed: " + name);
            }
            if (!header.containsKey("Value")) {
                throw invalid("Custom response header Value is required.");
            }
            String headerValue = requireHeaderValue(header.get("Value"));
            requireBoolean(header, "Override");
            combinedLength += name.length() + headerValue.length();
        }
        if (combinedLength > MAX_CUSTOM_HEADER_BYTES) {
            throw invalid("Combined custom response header names and values cannot exceed "
                    + MAX_CUSTOM_HEADER_BYTES + " characters.");
        }
    }

    private static void validateRemoveHeaders(Object value) {
        if (value == null) {
            return;
        }
        List<?> headers = objectList(value, "RemoveHeadersConfig");
        if (headers.size() > MAX_REMOVE_HEADERS) {
            throw new AwsException("TooManyRemoveHeadersInResponseHeadersPolicy",
                    "A response headers policy can remove at most " + MAX_REMOVE_HEADERS
                            + " headers.", 400);
        }
        Set<String> seen = new HashSet<>();
        for (Object valueItem : headers) {
            String name = requireHeaderName(valueItem);
            String normalized = name.toLowerCase(Locale.ROOT);
            if (!seen.add(normalized)) {
                throw invalid("Remove response header names must be unique.");
            }
            if (isForbiddenRemoval(normalized)) {
                throw invalid("Response header cannot be removed: " + name);
            }
        }
    }

    private static void validateSecurityHeaders(Object value) {
        if (value == null) {
            return;
        }
        Map<String, Object> security = objectMap(value, "SecurityHeadersConfig");
        Object hstsValue = security.get("StrictTransportSecurity");
        if (hstsValue != null) {
            Map<String, Object> hsts = objectMap(hstsValue, "StrictTransportSecurity");
            requireNonNegativeLong(hsts.get("AccessControlMaxAgeSec"), "AccessControlMaxAgeSec");
            if (hsts.get("IncludeSubdomains") != null) {
                requireBoolean(hsts, "IncludeSubdomains");
            }
            if (hsts.get("Preload") != null) {
                requireBoolean(hsts, "Preload");
            }
            requireBoolean(hsts, "Override");
        }
        Object contentTypeValue = security.get("ContentTypeOptions");
        if (contentTypeValue != null) {
            requireBoolean(objectMap(contentTypeValue, "ContentTypeOptions"), "Override");
        }
        Object frameValue = security.get("FrameOptions");
        if (frameValue != null) {
            Map<String, Object> frame = objectMap(frameValue, "FrameOptions");
            String option = requireSafeText(frame.get("FrameOption"), "FrameOption", 16);
            if (!FRAME_OPTIONS.contains(option)) {
                throw invalid("FrameOption must be DENY or SAMEORIGIN.");
            }
            requireBoolean(frame, "Override");
        }
        Object referrerValue = security.get("ReferrerPolicy");
        if (referrerValue != null) {
            Map<String, Object> referrer = objectMap(referrerValue, "ReferrerPolicy");
            String policy = requireSafeText(referrer.get("ReferrerPolicy"),
                    "ReferrerPolicy", 64);
            if (!REFERRER_POLICIES.contains(policy)) {
                throw invalid("Unsupported ReferrerPolicy: " + policy);
            }
            requireBoolean(referrer, "Override");
        }
        Object xssValue = security.get("XSSProtection");
        if (xssValue != null) {
            Map<String, Object> xss = objectMap(xssValue, "XSSProtection");
            requireBoolean(xss, "Protection");
            requireBoolean(xss, "Override");
            if (xss.get("ModeBlock") != null) {
                requireBoolean(xss, "ModeBlock");
            }
            if (xss.get("ReportUri") != null) {
                requireSafeText(xss.get("ReportUri"), "ReportUri", MAX_HEADER_VALUE_LENGTH);
            }
            if (Boolean.parseBoolean(String.valueOf(xss.get("ModeBlock")))
                    && xss.get("ReportUri") != null) {
                throw invalid("XSSProtection cannot specify both ModeBlock and ReportUri.");
            }
        }
        Object cspValue = security.get("ContentSecurityPolicy");
        if (cspValue != null) {
            Map<String, Object> csp = objectMap(cspValue, "ContentSecurityPolicy");
            String policy = requireNonBlankText(csp.get("ContentSecurityPolicy"),
                    "ContentSecurityPolicy");
            if (hasUnsafeControlCharacter(policy)) {
                throw invalid("ContentSecurityPolicy contains an invalid control character.");
            }
            if (policy.length() > MAX_HEADER_VALUE_LENGTH) {
                throw new AwsException("TooLongCSPInResponseHeadersPolicy",
                        "The Content-Security-Policy value exceeds the maximum length.", 400);
            }
            requireBoolean(csp, "Override");
        }
    }

    private static void validateServerTiming(Object value) {
        if (value == null) {
            return;
        }
        Map<String, Object> serverTiming = objectMap(value, "ServerTimingHeadersConfig");
        requireBoolean(serverTiming, "Enabled");
        Object samplingRate = serverTiming.get("SamplingRate");
        if (samplingRate == null) {
            return;
        }
        try {
            BigDecimal rate = new BigDecimal(samplingRate.toString());
            if (rate.compareTo(BigDecimal.ZERO) < 0
                    || rate.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw invalid("ServerTimingHeadersConfig SamplingRate must be between 0 and 100.");
            }
            if (rate.stripTrailingZeros().scale() > 4) {
                throw invalid("ServerTimingHeadersConfig SamplingRate supports at most four decimal places.");
            }
        } catch (NumberFormatException e) {
            throw invalid("ServerTimingHeadersConfig SamplingRate must be numeric.");
        }
    }

    static boolean isForbiddenRemoval(String name) {
        String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        return FORBIDDEN_REMOVALS.contains(normalized)
                || normalized.startsWith("x-amz-cf-")
                || normalized.startsWith("x-edge-");
    }

    private static List<?> requiredList(Map<String, Object> map, String key, boolean nonEmpty) {
        Object value = map.get(key);
        if (value == null) {
            throw invalid("CorsConfig " + key + " is required.");
        }
        List<?> list = objectList(value, key);
        if (nonEmpty && list.isEmpty()) {
            throw invalid("CorsConfig " + key + " cannot be empty.");
        }
        return list;
    }

    private static void requireBoolean(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null || !("true".equalsIgnoreCase(value.toString())
                || "false".equalsIgnoreCase(value.toString()))) {
            throw invalid(key + " must be true or false.");
        }
    }

    private static int requireNonNegativeLong(Object value, String field) {
        if (value == null) {
            throw invalid(field + " is required.");
        }
        try {
            long number = Long.parseLong(value.toString());
            if (number < 0 || number > Integer.MAX_VALUE) {
                throw invalid(field + " must be between 0 and " + Integer.MAX_VALUE + ".");
            }
            return (int) number;
        } catch (NumberFormatException e) {
            throw invalid(field + " must be a non-negative integer.");
        }
    }

    private static String requireHeaderName(Object value) {
        String name = requireSafeText(value, "Header", MAX_HEADER_NAME_LENGTH);
        if (!HEADER_NAME.matcher(name).matches()) {
            throw invalid("Invalid HTTP response header name: " + name);
        }
        return name;
    }

    private static String requireHeaderValue(Object value) {
        String text = value == null ? "" : value.toString();
        if (text.length() > MAX_HEADER_VALUE_LENGTH || hasUnsafeControlCharacter(text)) {
            throw invalid("Invalid HTTP response header value.");
        }
        return text;
    }

    private static String requireSafeText(Object value, String field, int maxLength) {
        String text = requireNonBlankText(value, field);
        if (text.length() > maxLength || hasUnsafeControlCharacter(text)) {
            throw invalid(field + " is invalid or exceeds its maximum length.");
        }
        return text;
    }

    private static String requireNonBlankText(Object value, String field) {
        if (value == null || value.toString().isBlank()) {
            throw invalid(field + " is required.");
        }
        return value.toString();
    }

    private static void validateCorsHeaderPatterns(
            List<?> values, String field, boolean allowEmbeddedWildcard) {
        Set<String> seen = new HashSet<>();
        for (Object value : values) {
            String header = requireSafeText(value, field, MAX_HEADER_NAME_LENGTH);
            long wildcardCount = header.chars().filter(character -> character == '*').count();
            boolean valid = "*".equals(header)
                    || (HEADER_NAME.matcher(header).matches()
                    && (allowEmbeddedWildcard ? wildcardCount <= 1 : wildcardCount == 0));
            if (!valid) {
                throw invalid(field + " contains an invalid HTTP header pattern: " + header);
            }
            if (!seen.add(header.toLowerCase(Locale.ROOT))) {
                throw invalid(field + " values must be unique.");
            }
        }
    }

    private static void validateOrigins(List<?> values) {
        Set<String> seen = new HashSet<>();
        for (Object value : values) {
            String origin = requireSafeText(
                    value, "AccessControlAllowOrigins entry", MAX_HEADER_VALUE_LENGTH);
            if (!isValidOriginPattern(origin)) {
                throw invalid("Invalid AccessControlAllowOrigins entry: " + origin);
            }
            if (!seen.add(origin.toLowerCase(Locale.ROOT))) {
                throw invalid("AccessControlAllowOrigins entries must be unique.");
            }
        }
    }

    private static boolean isValidOriginPattern(String origin) {
        if ("*".equals(origin)) {
            return true;
        }
        String authority = origin;
        int schemeSeparator = authority.indexOf("://");
        if (schemeSeparator >= 0) {
            String scheme = authority.substring(0, schemeSeparator);
            if (!scheme.matches("[A-Za-z][A-Za-z0-9+.-]*")) {
                return false;
            }
            authority = authority.substring(schemeSeparator + 3);
        }
        if (authority.isEmpty() || authority.indexOf('/') >= 0 || authority.indexOf('?') >= 0
                || authority.indexOf('#') >= 0 || authority.indexOf('@') >= 0) {
            return false;
        }
        String host = authority;
        String port = null;
        int colon = authority.lastIndexOf(':');
        if (colon >= 0 && authority.indexOf(':') == colon) {
            host = authority.substring(0, colon);
            port = authority.substring(colon + 1);
        }
        if (host.isEmpty() || (host.contains("*")
                && !(host.startsWith("*.") && host.indexOf('*', 1) < 0))) {
            return false;
        }
        if (!host.matches("(?:\\*\\.)?[A-Za-z0-9.-]+") || host.startsWith(".")
                || host.endsWith(".") || host.contains("..")) {
            return false;
        }
        return port == null || (!port.isEmpty() && port.matches("[0-9*]+"));
    }

    private static boolean hasUnsafeControlCharacter(String value) {
        return value.chars().anyMatch(character ->
                (character < 0x20 && character != '\t') || character == 0x7f);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value, String field) {
        if (!(value instanceof Map<?, ?>)) {
            throw invalid(field + " must be an object.");
        }
        return (Map<String, Object>) value;
    }

    private static List<?> objectList(Object value, String field) {
        if (!(value instanceof List<?> list)) {
            throw invalid(field + " must be a list.");
        }
        return list;
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidArgument", message, 400);
    }
}
