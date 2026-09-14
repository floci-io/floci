package io.github.hectorvent.floci.core.common.vtl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Implements the AWS VTL {@code $util} functions that API Gateway and AppSync specify
 * identically: string escaping, URL and base64 encoding, and JSON parsing. AWS documents
 * {@code $util} as one shared contract across both services' mapping-template specs, so
 * these implementations are shared rather than kept as two independently maintained
 * copies. Each service's own {@code $util} wrapper (API Gateway's
 * {@code VtlTemplateEngine.UtilVariable}, AppSync's {@code AppSyncUtil}) delegates the
 * overlapping functions here and keeps everything specific to its own template dialect
 * (error signaling, GraphQL-specific helpers, response overrides, etc.) to itself.
 */
public final class VtlUtilFunctions {

    private VtlUtilFunctions() {
    }

    /**
     * Escapes a string using EcmaScript/JavaScript string rules, matching AWS's
     * {@code $util.escapeJavaScript} (Apache Commons Lang escapeEcmaScript). Escapes:
     * backslash, double/single quotes, forward slash, control chars, and non-ASCII
     * characters (outside 0x20-0x7E) as unicode escape sequences.
     */
    public static String escapeJavaScript(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\'' -> sb.append("\\'");
                case '/' -> sb.append("\\/");
                case '\b' -> sb.append("\\b");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\f' -> sb.append("\\f");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (c < 0x20 || c > 0x7E) {
                        sb.append("\\u").append(String.format("%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** URL-encodes a string. */
    public static String urlEncode(String s) {
        if (s == null) return "";
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** URL-decodes a string. */
    public static String urlDecode(String s) {
        if (s == null) return "";
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    /**
     * Base64-encodes a string, UTF-8 encoded first. This is API Gateway's
     * {@code $util.base64Encode}, which takes a string.
     */
    public static String base64Encode(String s) {
        if (s == null) return "";
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Base64-encodes raw bytes. This is AppSync's {@code $util.base64Encode}, which
     * operates on a Blob rather than a string.
     */
    public static String base64Encode(byte[] data) {
        if (data == null) return "";
        return Base64.getEncoder().encodeToString(data);
    }

    /** Base64-decodes a string. */
    public static String base64Decode(String s) {
        if (s == null) return "";
        return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
    }

    /** Parses a JSON string into a Map/List structure navigable in VTL. */
    public static Object parseJson(ObjectMapper objectMapper, String s) {
        if (s == null || s.isEmpty()) return Map.of();
        try {
            return objectMapper.readValue(s, Object.class);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
