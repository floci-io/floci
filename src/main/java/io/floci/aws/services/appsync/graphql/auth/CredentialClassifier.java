package io.floci.aws.services.appsync.graphql.auth;

import java.util.Locale;
import java.util.Map;

public final class CredentialClassifier {

    private CredentialClassifier() {
    }

    public static ClassifiedMode classify(Map<String, String> headers) {
        String authorization = header(headers, "authorization");
        if (authorization != null && !authorization.isBlank()
                && authorization.startsWith("AWS4-HMAC-SHA256")) {
            return ClassifiedMode.AWS_IAM;
        }
        String apiKey = header(headers, "x-api-key");
        if (apiKey != null && !apiKey.isBlank()) {
            return ClassifiedMode.API_KEY;
        }
        if (authorization == null || authorization.isBlank()) {
            return ClassifiedMode.NONE;
        }
        if (isBearerJwt(authorization)) {
            return ClassifiedMode.BEARER_JWT;
        }
        return ClassifiedMode.AWS_LAMBDA;
    }

    static String header(Map<String, String> headers, String name) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        String direct = headers.get(name);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean isBearerJwt(String authorization) {
        if (authorization.length() < 7 || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        return hasJwtShape(authorization.substring(7));
    }

    /**
     * True when the value is three dot-separated base64url segments, so a Lambda token wrapped with
     * a prefix or suffix (for example {@code prefix:<jwt>}) is not taken for a JWT.
     */
    static boolean isBareJwt(String value) {
        if (!hasJwtShape(value)) {
            return false;
        }
        String token = value.trim();
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean base64Url = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_';
            if (c != '.' && !base64Url) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasJwtShape(String value) {
        if (value == null) {
            return false;
        }
        String token = value.trim();
        int first = token.indexOf('.');
        int second = token.indexOf('.', first + 1);
        if (first <= 0 || second <= first + 1) {
            return false;
        }
        int third = token.indexOf('.', second + 1);
        return third < 0 && second < token.length() - 1;
    }

    static String normalizeKey(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
