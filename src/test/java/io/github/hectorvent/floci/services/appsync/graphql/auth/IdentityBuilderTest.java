package io.github.hectorvent.floci.services.appsync.graphql.auth;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityBuilderTest {

    @Test
    void iamIdentityIncludesUserAndSourceIpArray() {
        Map<String, Object> identity = IdentityBuilder.iam(
                "000000000000", "AKIATEST", "alice", "arn:aws:iam::000000000000:user/alice", List.of("10.0.0.1"));
        assertEquals("AKIATEST", identity.get("user"));
        assertEquals("alice", identity.get("username"));
        assertEquals("arn:aws:iam::000000000000:user/alice", identity.get("userArn"));
        assertInstanceOf(List.class, identity.get("sourceIp"));
        assertEquals(List.of("10.0.0.1"), identity.get("sourceIp"));
        assertEquals("", identity.get("cognitoIdentityPoolId"));
    }

    @Test
    void cognitoGroupsNullWhenClaimMissing() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "abc");
        claims.put("iss", "https://cognito");
        claims.put("cognito:username", "bob");
        Map<String, Object> identity = IdentityBuilder.cognito(claims, List.of("127.0.0.1"), "ALLOW");
        assertNull(identity.get("groups"));
        assertEquals("bob", identity.get("username"));
        assertTrue(!identity.containsKey("cognitoUserPoolId"));
        assertInstanceOf(List.class, identity.get("sourceIp"));
    }

    @Test
    void cognitoGroupsEmptyListWhenClaimEmpty() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "abc");
        claims.put("cognito:groups", List.of());
        Map<String, Object> identity = IdentityBuilder.cognito(claims, List.of(), "DENY");
        assertEquals(List.of(), identity.get("groups"));
    }

    @Test
    void oidcIdentityHasSubIssuerClaims() {
        Map<String, Object> claims = Map.of("sub", "s1", "iss", "https://issuer");
        Map<String, Object> identity = IdentityBuilder.oidc(claims);
        assertEquals("s1", identity.get("sub"));
        assertEquals("https://issuer", identity.get("issuer"));
        assertEquals(claims, identity.get("claims"));
        assertTrue(!identity.containsKey("sourceIp"));
        assertEquals(3, identity.size());
    }

    @Test
    void lambdaIdentityIsResolverContextOnly() {
        Map<String, Object> identity = IdentityBuilder.lambda(Map.of("apple", "green"));
        assertEquals(Map.of("apple", "green"), identity.get("resolverContext"));
        assertEquals(1, identity.size());
    }
}
