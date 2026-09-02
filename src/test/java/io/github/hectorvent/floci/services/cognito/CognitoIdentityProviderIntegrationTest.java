package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers CreateIdentityProvider/DescribeIdentityProvider/ListIdentityProviders/
 * UpdateIdentityProvider/DeleteIdentityProvider.
 *
 * <p>The response and update semantics asserted here were measured against the live
 * Cognito API; they are not derivable from the API reference. In particular
 * {@code IdpIdentifiers} is echoed by create and update only when the request supplied
 * the member, and members a request omits are preserved rather than cleared.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CognitoIdentityProviderIntegrationTest {

    private static String poolId;

    private static final String OIDC_DETAILS = """
            {
              "client_id": "idp-test-client",
              "client_secret": "idp-test-secret",
              "attributes_request_method": "GET",
              "oidc_issuer": "https://issuer.example.com",
              "authorize_scopes": "openid"
            }
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createPool() throws Exception {
        JsonNode response = cognitoJson("CreateUserPool", """
                {
                  "PoolName": "IdentityProviderTestPool"
                }
                """);
        poolId = response.path("UserPool").path("Id").asText();
    }

    @Test
    @Order(2)
    void createRejectsUnknownProviderType() {
        cognitoAction("CreateIdentityProvider", """
                {
                  "UserPoolId": "%s",
                  "ProviderName": "Bogus",
                  "ProviderType": "NotARealType",
                  "ProviderDetails": {"client_id": "x"}
                }
                """.formatted(poolId))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo(
                        "1 validation error detected: Value 'NotARealType' at 'providerType' failed to "
                                + "satisfy constraint: Member must satisfy enum value set: "
                                + "[Facebook, SAML, SignInWithApple, LoginWithAmazon, OIDC, Google]"));
    }

    @Test
    @Order(3)
    void createDefaultsAttributeMappingAndOmitsIdpIdentifiers() throws Exception {
        JsonNode provider = cognitoJson("CreateIdentityProvider", """
                {
                  "UserPoolId": "%s",
                  "ProviderName": "TestOidc",
                  "ProviderType": "OIDC",
                  "ProviderDetails": %s
                }
                """.formatted(poolId, OIDC_DETAILS)).path("IdentityProvider");

        assertEquals("TestOidc", provider.path("ProviderName").asText());
        assertEquals("OIDC", provider.path("ProviderType").asText());
        assertEquals("idp-test-client", provider.path("ProviderDetails").path("client_id").asText());
        assertEquals("sub", provider.path("AttributeMapping").path("username").asText());
        assertEquals(1, provider.path("AttributeMapping").size());
        assertTrue(provider.path("IdpIdentifiers").isMissingNode(),
                "AWS omits IdpIdentifiers from a create response that did not supply it");
    }

    @Test
    @Order(4)
    void describeAlwaysReturnsIdpIdentifiers() throws Exception {
        JsonNode provider = cognitoJson("DescribeIdentityProvider", """
                {
                  "UserPoolId": "%s",
                  "ProviderName": "TestOidc"
                }
                """.formatted(poolId)).path("IdentityProvider");

        assertTrue(provider.path("IdpIdentifiers").isArray(),
                "Describe returns IdpIdentifiers even when it is empty");
        assertEquals(0, provider.path("IdpIdentifiers").size());
        assertEquals(poolId, provider.path("UserPoolId").asText());
    }

    @Test
    @Order(5)
    void createRejectsDuplicateProviderName() {
        cognitoAction("CreateIdentityProvider", """
                {
                  "UserPoolId": "%s",
                  "ProviderName": "TestOidc",
                  "ProviderType": "OIDC",
                  "ProviderDetails": %s
                }
                """.formatted(poolId, OIDC_DETAILS))
                .then()
                .statusCode(400)
                .body("__type", equalTo("DuplicateProviderException"))
                .body("message", equalTo("TestOidc already exists for tenant " + poolId + "."));
    }

    @Test
    @Order(6)
    void createEchoesIdpIdentifiersWhenSupplied() throws Exception {
        JsonNode provider = cognitoJson("CreateIdentityProvider", """
                {
                  "UserPoolId": "%s",
                  "ProviderName": "TestOidcAliased",
                  "ProviderType": "OIDC",
                  "ProviderDetails": %s,
                  "AttributeMapping": {"email": "email", "username": "sub"},
                  "IdpIdentifiers": ["alias-keep"]
                }
                """.formatted(poolId, OIDC_DETAILS)).path("IdentityProvider");

        assertEquals(1, provider.path("IdpIdentifiers").size());
        assertEquals("alias-keep", provider.path("IdpIdentifiers").get(0).asText());
        assertEquals("email", provider.path("AttributeMapping").path("email").asText());
    }

    @Test
    @Order(7)
    void updateOmittingMembersPreservesThem() throws Exception {
        JsonNode updated = cognitoJson("UpdateIdentityProvider", """
                {
                  "UserPoolId": "%s",
                  "ProviderName": "TestOidcAliased",
                  "ProviderDetails": %s
                }
                """.formatted(poolId, OIDC_DETAILS)).path("IdentityProvider");

        assertTrue(updated.path("IdpIdentifiers").isMissingNode(),
                "the update response omits IdpIdentifiers regardless of the stored value");

        JsonNode described = cognitoJson("DescribeIdentityProvider", """
                {
                  "UserPoolId": "%s",
                  "ProviderName": "TestOidcAliased"
                }
                """.formatted(poolId)).path("IdentityProvider");

        assertEquals(1, described.path("IdpIdentifiers").size(),
                "omitting IdpIdentifiers preserves the stored value");
        assertEquals("alias-keep", described.path("IdpIdentifiers").get(0).asText());
        assertEquals("email", described.path("AttributeMapping").path("email").asText());
    }

    @Test
    @Order(8)
    void updateWithEmptyCollectionsClearsThem() throws Exception {
        JsonNode updated = cognitoJson("UpdateIdentityProvider", """
                {
                  "UserPoolId": "%s",
                  "ProviderName": "TestOidcAliased",
                  "ProviderDetails": %s,
                  "AttributeMapping": {},
                  "IdpIdentifiers": []
                }
                """.formatted(poolId, OIDC_DETAILS)).path("IdentityProvider");

        assertTrue(updated.path("IdpIdentifiers").isArray(),
                "a supplied IdpIdentifiers member is echoed even when empty");
        assertEquals(0, updated.path("IdpIdentifiers").size());
        assertEquals(0, updated.path("AttributeMapping").size());

        JsonNode described = cognitoJson("DescribeIdentityProvider", """
                {
                  "UserPoolId": "%s",
                  "ProviderName": "TestOidcAliased"
                }
                """.formatted(poolId)).path("IdentityProvider");

        assertEquals(0, described.path("AttributeMapping").size());
        assertEquals(0, described.path("IdpIdentifiers").size());
    }

    @Test
    @Order(9)
    void listReturnsSummariesWithoutProviderDetails() throws Exception {
        JsonNode providers = cognitoJson("ListIdentityProviders", """
                {
                  "UserPoolId": "%s"
                }
                """.formatted(poolId)).path("Providers");

        assertEquals(2, providers.size());
        for (JsonNode provider : providers) {
            assertTrue(provider.hasNonNull("ProviderName"));
            assertTrue(provider.hasNonNull("ProviderType"));
            assertTrue(provider.hasNonNull("CreationDate"));
            assertTrue(provider.hasNonNull("LastModifiedDate"));
            assertFalse(provider.has("ProviderDetails"),
                    "ListIdentityProviders returns summaries only, never provider credentials");
            assertFalse(provider.has("AttributeMapping"));
        }
    }

    @Test
    @Order(10)
    void describeAndUpdateReportMissingProvidersDifferently() {
        cognitoAction("DescribeIdentityProvider", """
                {
                  "UserPoolId": "%s",
                  "ProviderName": "NoSuchProvider"
                }
                """.formatted(poolId))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", equalTo(
                        "Identity provider NoSuchProvider for tenantId " + poolId + " does not exist."));

        cognitoAction("UpdateIdentityProvider", """
                {
                  "UserPoolId": "%s",
                  "ProviderName": "NoSuchProvider",
                  "ProviderDetails": %s
                }
                """.formatted(poolId, OIDC_DETAILS))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", equalTo(
                        "Identity provider NoSuchProvider in User Pool " + poolId + " does not exist."));
    }

    @Test
    @Order(11)
    void deleteRemovesProviderAndIsNotIdempotent() throws Exception {
        cognitoAction("DeleteIdentityProvider", """
                {
                  "UserPoolId": "%s",
                  "ProviderName": "TestOidcAliased"
                }
                """.formatted(poolId))
                .then()
                .statusCode(200);

        cognitoAction("DeleteIdentityProvider", """
                {
                  "UserPoolId": "%s",
                  "ProviderName": "TestOidcAliased"
                }
                """.formatted(poolId))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));

        cognitoAction("DeleteIdentityProvider", """
                {
                  "UserPoolId": "%s",
                  "ProviderName": "TestOidc"
                }
                """.formatted(poolId))
                .then()
                .statusCode(200);

        assertEquals(0, cognitoJson("ListIdentityProviders", """
                {
                  "UserPoolId": "%s"
                }
                """.formatted(poolId)).path("Providers").size());
    }

    /**
     * A pool recreated on a pinned id must not inherit the dead pool's providers: their
     * ProviderDetails carry the client_secret.
     */
    @Test
    @Order(12)
    void deletingAPoolRemovesItsIdentityProviders() throws Exception {
        String pinnedId = "us-east-1_idporph1";
        cognitoAction("CreateUserPool", """
                {
                  "PoolName": "IdpOrphanPool",
                  "UserPoolTags": {"floci:override-id": "%s"}
                }
                """.formatted(pinnedId))
                .then()
                .statusCode(200);

        cognitoAction("CreateIdentityProvider", """
                {
                  "UserPoolId": "%s",
                  "ProviderName": "OrphanOidc",
                  "ProviderType": "OIDC",
                  "ProviderDetails": %s
                }
                """.formatted(pinnedId, OIDC_DETAILS))
                .then()
                .statusCode(200);

        cognitoAction("DeleteUserPool", """
                {
                  "UserPoolId": "%s"
                }
                """.formatted(pinnedId))
                .then()
                .statusCode(200);

        cognitoAction("CreateUserPool", """
                {
                  "PoolName": "IdpOrphanPoolAgain",
                  "UserPoolTags": {"floci:override-id": "%s"}
                }
                """.formatted(pinnedId))
                .then()
                .statusCode(200);

        assertEquals(0, cognitoJson("ListIdentityProviders", """
                {
                  "UserPoolId": "%s"
                }
                """.formatted(pinnedId)).path("Providers").size(),
                "a recreated pool must not inherit the deleted pool's providers");

        cognitoAction("DeleteUserPool", """
                {
                  "UserPoolId": "%s"
                }
                """.formatted(pinnedId))
                .then()
                .statusCode(200);
    }

    /**
     * Two overlapping updates that touch different optional members must both survive.
     *
     * <p>Update is a read-modify-write: it copies the stored provider, applies only the
     * members the request supplied and writes the whole record back. Without a lock both
     * callers copy the same base, so the later write restores the other's omitted members
     * to their stale values and silently drops an update.
     *
     * <p>Measured against live Cognito in ap-southeast-1: an update that omits
     * {@code AttributeMapping} and {@code IdpIdentifiers} leaves both intact, so the
     * service merges, and it does so atomically. Reverting the lock in
     * {@code CognitoService.updateIdentityProvider} makes this test fail.
     */
    @Test
    @Order(13)
    void concurrentUpdatesOfDifferentMembersBothSurvive() throws Exception {
        String racePool = cognitoJson("CreateUserPool", """
                {
                  "PoolName": "idp-concurrency-pool"
                }
                """).path("UserPool").path("Id").asText();

        cognitoAction("CreateIdentityProvider", """
                {
                  "UserPoolId": "%s",
                  "ProviderName": "RaceOidc",
                  "ProviderType": "OIDC",
                  "ProviderDetails": %s,
                  "AttributeMapping": {"username": "sub"}
                }
                """.formatted(racePool, OIDC_DETAILS))
                .then()
                .statusCode(200);

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 25; round++) {
                String attribute = "email" + round;
                String identifier = "ident" + round;
                CyclicBarrier start = new CyclicBarrier(2);

                Future<?> mappingWriter = threads.submit(() -> {
                    start.await();
                    return cognitoAction("UpdateIdentityProvider", """
                            {
                              "UserPoolId": "%s",
                              "ProviderName": "RaceOidc",
                              "AttributeMapping": {"username": "sub", "%s": "%s"}
                            }
                            """.formatted(racePool, attribute, attribute));
                });
                Future<?> identifierWriter = threads.submit(() -> {
                    start.await();
                    return cognitoAction("UpdateIdentityProvider", """
                            {
                              "UserPoolId": "%s",
                              "ProviderName": "RaceOidc",
                              "IdpIdentifiers": ["%s"]
                            }
                            """.formatted(racePool, identifier));
                });
                mappingWriter.get(30, TimeUnit.SECONDS);
                identifierWriter.get(30, TimeUnit.SECONDS);

                JsonNode described = cognitoJson("DescribeIdentityProvider", """
                        {
                          "UserPoolId": "%s",
                          "ProviderName": "RaceOidc"
                        }
                        """.formatted(racePool)).path("IdentityProvider");

                assertTrue(described.path("AttributeMapping").has(attribute),
                        "round " + round + ": the concurrent IdpIdentifiers update discarded "
                                + "the AttributeMapping write");
                assertEquals(identifier, described.path("IdpIdentifiers").path(0).asText(),
                        "round " + round + ": the concurrent AttributeMapping update discarded "
                                + "the IdpIdentifiers write");
            }
        } finally {
            threads.shutdownNow();
            cognitoAction("DeleteUserPool", """
                    {
                      "UserPoolId": "%s"
                    }
                    """.formatted(racePool));
        }
    }

    @Test
    @Order(14)
    void deletePool() {
        cognitoAction("DeleteUserPool", """
                {
                  "UserPoolId": "%s"
                }
                """.formatted(poolId))
                .then()
                .statusCode(200);
    }
}
