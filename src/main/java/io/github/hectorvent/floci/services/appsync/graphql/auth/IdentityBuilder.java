package io.github.hectorvent.floci.services.appsync.graphql.auth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IdentityBuilder {

    private IdentityBuilder() {
    }

    public static Map<String, Object> iam(
            String accountId,
            String accessKeyId,
            String username,
            String userArn,
            List<String> sourceIp
    ) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("accountId", nullToEmpty(accountId));
        identity.put("cognitoIdentityPoolId", "");
        identity.put("cognitoIdentityId", "");
        identity.put("sourceIp", sourceIpArray(sourceIp));
        identity.put("username", username != null ? username : accessKeyId);
        identity.put("userArn", nullToEmpty(userArn));
        identity.put("cognitoIdentityAuthType", "");
        identity.put("cognitoIdentityAuthProvider", "");
        identity.put("user", accessKeyId);
        return identity;
    }

    public static Map<String, Object> cognito(
            Map<String, Object> claims,
            List<String> sourceIp,
            String defaultAuthStrategy
    ) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("sub", claims.get("sub"));
        identity.put("issuer", claims.get("iss"));
        Object username = claims.get("cognito:username");
        identity.put("username", username != null ? username : claims.get("username"));
        identity.put("claims", claims);
        identity.put("sourceIp", sourceIpArray(sourceIp));
        identity.put("defaultAuthStrategy", defaultAuthStrategy);
        identity.put("groups", groups(claims));
        return identity;
    }

    public static Map<String, Object> oidc(Map<String, Object> claims) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("sub", claims.get("sub"));
        identity.put("issuer", claims.get("iss"));
        identity.put("claims", claims);
        return identity;
    }

    public static Map<String, Object> lambda(Map<String, Object> resolverContext) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("resolverContext", resolverContext == null ? Map.of() : resolverContext);
        return identity;
    }

    static Object groups(Map<String, Object> claims) {
        if (!claims.containsKey("cognito:groups")) {
            return null;
        }
        Object value = claims.get("cognito:groups");
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            return List.copyOf(list);
        }
        return value;
    }

    static List<String> sourceIpArray(List<String> sourceIp) {
        if (sourceIp == null || sourceIp.isEmpty()) {
            return List.of();
        }
        List<String> copy = new ArrayList<>();
        for (String ip : sourceIp) {
            if (ip != null && !ip.isBlank()) {
                copy.add(ip.trim());
            }
        }
        return List.copyOf(copy);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
