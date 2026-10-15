package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.services.iam.model.AccessKey;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testutil.StsRequests;
import io.github.hectorvent.floci.testutil.StsRequests.Credentials;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
@TestProfile(StsFederationCallerEnforcementIntegrationTest.SignedEnforcement.class)
class StsFederationCallerEnforcementIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String ALLOW_STS = "{\"Version\":\"2012-10-17\",\"Statement\":["
            + "{\"Effect\":\"Allow\",\"Action\":\"sts:*\",\"Resource\":\"*\"}]}";

    @Inject IamService iam;
    @RegisterExtension final PartitionCleanup cleanup = new PartitionCleanup();

    public static class SignedEnforcement implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.auth.validate-signatures", "true", "floci.services.iam.enforcement-enabled", "true");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GetFederationToken", "GetSessionToken", "AssumeRole"})
    void issuedTemporaryCallerKeepsExactRefusal(String issuer) throws Exception {
        Credentials issued = issue(issuer);
        String identity = request(issued, "GetCallerIdentity", Map.of()).then().statusCode(200)
                .extract().path("GetCallerIdentityResponse.GetCallerIdentityResult.Arn");
        // Even missing required fields must reach the temporary-caller refusal before parsing.
        request(issued, "GetFederationToken", Map.of()).then().statusCode(403)
                .body("ErrorResponse.Error.Code", equalTo("AccessDenied"))
                .body("ErrorResponse.Error.Message", equalTo("Cannot call GetFederationToken with session credentials"));
        request(issued, "GetCallerIdentity", Map.of()).then().statusCode(200)
                .body("GetCallerIdentityResponse.GetCallerIdentityResult.Arn", equalTo(identity));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "wrong", "forged", "unknown", "expired"})
    void authenticationStillPrecedesTheCallerRestriction(String mode) throws Exception {
        Credentials issued = issue("GetFederationToken");
        Credentials caller = switch (mode) {
            case "missing" -> new Credentials(issued.key(), issued.secret(), null);
            case "wrong" -> new Credentials(issued.key(), issued.secret(), "wrong");
            case "forged" -> new Credentials(issued.key(), "wrong", issued.token());
            case "unknown" -> new Credentials("ASIAUNKNOWN", "unknown", "unknown");
            case "expired" -> {
                String key = "ASIA" + UUID.randomUUID().toString().replace("-", "");
                iam.registerSession(key, "expired", "token", null, Instant.EPOCH, null);
                cleanup.register(() -> iam.unregisterSession(ACCOUNT, key));
                yield new Credentials(key, "expired", "token");
            }
            default -> throw new IllegalArgumentException(mode);
        };
        request(caller, "GetFederationToken", Map.of("Name", "refused")).then().statusCode(403)
                .body("ErrorResponse.Error.Code", equalTo("forged".equals(mode)
                        ? "SignatureDoesNotMatch" : "InvalidClientTokenId"));
        request(issued, "GetCallerIdentity", Map.of()).then().statusCode(200);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void longTermPoliciesStillDecideIssuance(boolean allowed) throws Exception {
        Credentials user = user(allowed);
        Response result = request(user, "GetFederationToken", Map.of("Name", "longterm"));
        if (allowed) {
            remember(result, "GetFederationToken");
        } else {
            result.then().statusCode(403).body("ErrorResponse.Error.Code", equalTo("AccessDenied"))
                    .body("ErrorResponse.Error.Message", equalTo("User is not authorized to perform: sts:GetFederationToken"));
        }
    }

    @Test
    void federationSessionKeepsOtherIamAndStsRestrictions() throws Exception {
        Credentials issued = issue("GetFederationToken");
        request(issued, "ListUsers", Map.of()).then().statusCode(403)
                .body("ErrorResponse.Error.Code", equalTo("AccessDenied"));
        request(issued, "GetSessionToken", Map.of()).then().statusCode(403)
                .body("ErrorResponse.Error.Code", equalTo("AccessDenied"));
        request(issued, "GetCallerIdentity", Map.of()).then().statusCode(200);
    }

    private Credentials issue(String action) throws Exception {
        Credentials user = user(true);
        Map<String, String> parameters = new LinkedHashMap<>();
        if ("GetFederationToken".equals(action)) {
            parameters.put("Name", "federated");
            parameters.put("Policy", ALLOW_STS);
        } else if ("AssumeRole".equals(action)) {
            String name = "fed-caller-role-" + UUID.randomUUID();
            String arn = iam.createRole(name, "/", "{\"Version\":\"2012-10-17\",\"Statement\":["
                    + "{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"sts:AssumeRole\"}]}", null, 3600, null).getArn();
            cleanup.register(() -> iam.deleteRole(name));
            parameters.put("RoleArn", arn);
            parameters.put("RoleSessionName", "caller");
        }
        return remember(request(user, action, parameters), action);
    }

    private Credentials user(boolean allowed) {
        String name = "fed-enforced-" + UUID.randomUUID();
        iam.createUser(name, "/");
        cleanup.register(() -> iam.deleteUser(name));
        AccessKey key = iam.createAccessKey(name);
        cleanup.register(() -> iam.deleteAccessKey(name, key.getAccessKeyId()));
        if (allowed) {
            iam.putUserPolicy(name, "sts", ALLOW_STS);
            cleanup.register(() -> iam.deleteUserPolicy(name, "sts"));
        }
        return new Credentials(key.getAccessKeyId(), key.getSecretAccessKey(), null);
    }

    private Credentials remember(Response result, String action) {
        String prefix = action + "Response." + action + "Result.Credentials.";
        String key = result.xmlPath().getString(prefix + "AccessKeyId");
        if (key != null) {
            cleanup.register(() -> iam.unregisterSession(ACCOUNT, key));
        }
        result.then().statusCode(200);
        assertNotNull(key);
        return new Credentials(key, result.xmlPath().getString(prefix + "SecretAccessKey"),
                result.xmlPath().getString(prefix + "SessionToken"));
    }

    private Response request(Credentials caller, String action, Map<String, String> parameters) throws Exception {
        return StsRequests.request(caller, "ListUsers".equals(action) ? "iam" : "sts", action, parameters);
    }
}
