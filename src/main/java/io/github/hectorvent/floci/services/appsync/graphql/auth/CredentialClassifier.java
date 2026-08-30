package io.github.hectorvent.floci.services.appsync.graphql.auth;

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
        String token = authorization.substring(7).trim();
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
