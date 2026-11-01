package io.github.hectorvent.floci.services.route53;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.Route53Exception;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Route53DelegationSetsIamEnforcementIntegrationTest {
    private static final String ACCOUNT = "741686000030";

    @TestHTTPResource("/")
    URI baseUri;

    private final PartitionCleanup CLEANUP = new PartitionCleanup();
    @RegisterExtension
    final AfterAllCallback classCleanup = CLEANUP::afterEach;
    private String implicitDenyKey;
    private String explicitDenyKey;
    private String allowKey;
    private String scopedAllowKey;
    private String scopedDenyKey;
    private String selectedSet;
    private String otherSet;

    @BeforeAll
    void createSharedFixtures() {
        implicitDenyKey = userWithPolicy(null);
        explicitDenyKey = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                {"Effect":"Allow","Action":"*","Resource":"*"},
                {"Effect":"Deny","Action":"route53:*","Resource":"*"}]}
                """);
        allowKey = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"route53:*","Resource":"*"}]}
                """);
        Route53Client root = client(ACCOUNT);
        CLEANUP.register(root::close);
        selectedSet = createSet(root);
        CLEANUP.register(() -> root.deleteReusableDelegationSet(r -> r.id(selectedSet)));
        otherSet = createSet(root);
        CLEANUP.register(() -> root.deleteReusableDelegationSet(r -> r.id(otherSet)));
        scopedAllowKey = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Action":["route53:GetReusableDelegationSet","route53:DeleteReusableDelegationSet"],
                "Resource":"arn:aws:route53:::delegationset/%s"}]}
                """.formatted(selectedSet));
        scopedDenyKey = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                {"Effect":"Allow","Action":"*","Resource":"*"},
                {"Effect":"Deny","Action":["route53:GetReusableDelegationSet","route53:DeleteReusableDelegationSet"],
                "Resource":"arn:aws:route53:::delegationset/%s"}]}
                """.formatted(selectedSet));
    }

    @ParameterizedTest
    @CsvSource({"Create,true", "Get,true", "List,true", "Delete,true",
            "Create,false", "Get,false", "List,false", "Delete,false"})
    void missingPermissionsAndExplicitDeniesApplyToEveryOperation(String operation, boolean explicitDeny) {
        try (Route53Client client = client(explicitDeny ? explicitDenyKey : implicitDenyKey)) {
            Runnable call = switch (operation) {
                case "Create" -> () -> client.createReusableDelegationSet(r -> r.callerReference(UUID.randomUUID().toString()));
                case "Get" -> () -> client.getReusableDelegationSet(r -> r.id("NMissing"));
                case "List" -> () -> client.listReusableDelegationSets();
                case "Delete" -> () -> client.deleteReusableDelegationSet(r -> r.id("NMissing"));
                default -> throw new IllegalArgumentException(operation);
            };
            assertDenied(call);
        }
    }

    @Test
    void anAllowedCallerCanCompleteTheDelegationSetLifecycle() {
        try (Route53Client client = client(allowKey)) {
            String id = createSet(client);
            try {
                assertEquals(id, client.getReusableDelegationSet(r -> r.id(id)).delegationSet().id());
                assertEquals(id, client.listReusableDelegationSets().delegationSets().stream()
                        .filter(set -> set.id().equals(id)).findFirst().orElseThrow().id());
            } finally {
                client.deleteReusableDelegationSet(r -> r.id(id));
            }
        }
    }

    @Test
    void resourceScopedAllowsGrantOnlyTheSelectedSet() {
        try (Route53Client reader = client(scopedAllowKey)) {
            assertEquals(selectedSet, reader.getReusableDelegationSet(r -> r.id(selectedSet)).delegationSet().id());
            assertDenied(() -> reader.getReusableDelegationSet(r -> r.id(otherSet)));
            assertDenied(() -> reader.deleteReusableDelegationSet(r -> r.id(otherSet)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain", "encodedPrefix", "matrix", "matrixEncodedId"})
    void aScopedDenyCannotBeBypassedByEncodedIdsOrOperationHeaders(String variant) {
        String selected = selectedSet;
        String other = otherSet;
        String key = scopedDenyKey;
        String path = "/2013-04-01/delegationset/"
                + (variant.contains("Prefix") ? "%2Fdelegationset%2F" : "")
                + (variant.equals("matrixEncodedId") ? "%4E" + selected.substring(1) : selected)
                + (variant.startsWith("matrix") ? ";ignored=1" : "");
        given().urlEncodingEnabled(false).header("Authorization", auth(ACCOUNT, "route53"))
                .get(path).then().statusCode(200).body(containsString(selected));
        try (Route53Client reader = client(key)) {
            assertEquals(other, reader.getReusableDelegationSet(r -> r.id(other)).delegationSet().id());
            for (String method : new String[]{"GET", "DELETE"}) {
                given().urlEncodingEnabled(false).header("Authorization", auth(key, "route53"))
                        .header("X-Amz-Target", "Route53.ListReusableDelegationSets")
                        .queryParam("Action", "ListReusableDelegationSets")
                        .request(method, path)
                        .then().statusCode(403).body(containsString("<Code>AccessDenied</Code>"));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"Create,literal", "Get,literal", "List,literal", "Delete,literal",
            "Create,separator", "Get,separator", "List,separator", "Delete,separator"})
    void percentEncodedRouteLiteralsStillEnforceTheSelectedOperation(String operation, String variant) {
        String key = explicitDenyKey;
        String method = switch (operation) {
            case "Create" -> "POST";
            case "Delete" -> "DELETE";
            default -> "GET";
        };
        String path = (variant.equals("literal") ? "/2013-04-01/%64elegationset"
                : "/2013-04-01%2Fdelegationset;ignored=1")
                + (operation.equals("Get") || operation.equals("Delete") ? "/NMissing" : "");
        given().urlEncodingEnabled(false).header("Authorization", auth(key, "route53"))
                .contentType("application/xml").body("<CreateReusableDelegationSetRequest><CallerReference>"
                        + UUID.randomUUID() + "</CallerReference></CreateReusableDelegationSetRequest>")
                .request(method, path).then().statusCode(403).body(containsString("<Code>AccessDenied</Code>"));
    }

    private String userWithPolicy(String policy) {
        String user = "r53-delegation-" + UUID.randomUUID();
        String key = EnforcementFixtures.createUser(CLEANUP, baseUri.getPort(), auth(ACCOUNT, "iam"), user).accessKeyId();
        if (policy != null) {
            EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ACCOUNT, "iam"), user, "delegation", policy);
        }
        return key;
    }

    private Route53Client client(String key) {
        return AwsTestClients.create(Route53Client.builder(), key, Region.US_EAST_1, baseUri);
    }

    private String createSet(Route53Client client) {
        return client.createReusableDelegationSet(r -> r.callerReference(UUID.randomUUID().toString())).delegationSet().id();
    }

    private void assertDenied(Runnable call) {
        Route53Exception error = assertThrows(Route53Exception.class, call::run);
        assertEquals(403, error.statusCode());
        assertEquals("AccessDenied", error.awsErrorDetails().errorCode());
    }
}
