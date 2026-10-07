package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.core.common.OidcIssuerKeyLookup;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.WebIdentityToken;
import io.github.hectorvent.floci.core.common.WebIdentityTokenVerifier;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.testing.PartitionMatrix;
import io.github.hectorvent.floci.testing.PartitionMatrix.PartitionCase;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StsQueryHandlerTest {

    private static final String REGION = "us-east-1";
    private static final Pattern SECRET_ACCESS_KEY =
            Pattern.compile("<SecretAccessKey>([^<]+)</SecretAccessKey>");
    private static final Pattern SESSION_TOKEN =
            Pattern.compile("<SessionToken>([^<]+)</SessionToken>");

    private static final Pattern ARN = Pattern.compile("<Arn>([^<]+)</Arn>");

    private static StsQueryHandler newHandler() {
        IamRole role = new IamRole();
        role.setRoleName("TestRole");
        role.setArn("arn:aws:iam::000000000000:role/TestRole");
        return newHandler(role);
    }

    private static StsQueryHandler newHandler(IamRole role) {
        return newHandler(role, new RegionResolver(REGION, "000000000000"));
    }

    private static StsQueryHandler newHandlerForRegion(RegionResolver regionResolver) {
        return newHandler(null, regionResolver);
    }

    private static StsQueryHandler newHandler(IamRole role, RegionResolver regionResolver) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.IamServiceConfig iam = mock(EmulatorConfig.IamServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.iam()).thenReturn(iam);
        when(iam.enforcementEnabled()).thenReturn(false);

        IamService iamService = mock(IamService.class);
        when(iamService.findRole(anyString(), anyString())).thenReturn(Optional.ofNullable(role));

        return new StsQueryHandler(
                iamService,
                mock(AccountResolver.class),
                regionResolver,
                config,
                mock(AssumeRolePolicyEvaluator.class),
                mock(WebIdentityTrustPolicyEvaluator.class),
                mock(WebIdentityTokenVerifier.class),
                mock(OidcIssuerKeyLookup.class),
                mock(SAMLProviderService.class),
                mock(SAMLTrustPolicyEvaluator.class));
    }

    @Test
    void getSessionTokenIssuesFortyCharSecretAndTwoHundredCharToken() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();

        Response response = newHandler().handle("GetSessionToken", params);

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertEquals(40, extract(SECRET_ACCESS_KEY, body).length());
        assertEquals(200, extract(SESSION_TOKEN, body).length());
    }

    @Test
    void getSessionTokensAreUniqueAcrossCalls() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();

        String firstBody = (String) newHandler().handle("GetSessionToken", params).getEntity();
        String secondBody = (String) newHandler().handle("GetSessionToken", params).getEntity();

        assertNotEquals(extract(SECRET_ACCESS_KEY, firstBody), extract(SECRET_ACCESS_KEY, secondBody));
        assertNotEquals(extract(SESSION_TOKEN, firstBody), extract(SESSION_TOKEN, secondBody));
    }

    @Test
    void assumeRoleDeniesRoleThatDoesNotExist() {
        StsQueryHandler handler = newHandler(null);
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleArn", "arn:aws:iam::000000000000:role/definitely-does-not-exist");
        params.putSingle("RoleSessionName", "test-session");

        Response response = handler.handle("AssumeRole", params);

        assertEquals(403, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("<Code>AccessDenied</Code>"));
    }

    @Test
    void assumeRoleDeniesRoleWhenPathDiffers() {
        IamRole role = new IamRole();
        role.setRoleName("TestRole");
        role.setPath("/team/");
        role.setArn("arn:aws:iam::000000000000:role/team/TestRole");
        StsQueryHandler handler = newHandler(role);

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleArn", "arn:aws:iam::000000000000:role/other/TestRole");
        params.putSingle("RoleSessionName", "test-session");

        Response response = handler.handle("AssumeRole", params);

        assertEquals(403, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("<Code>AccessDenied</Code>"));
    }

    @Test
    void assumeRoleAllowsPartitionCorrectRoleArn() {
        IamRole role = new IamRole();
        role.setRoleName("TestRole");
        role.setPath("/");
        role.setArn("arn:aws:iam::000000000000:role/TestRole");
        StsQueryHandler handler = newHandler(role);

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleArn", "arn:aws-cn:iam::000000000000:role/TestRole");
        params.putSingle("RoleSessionName", "test-session");

        Response response = handler.handle("AssumeRole", params);

        assertEquals(200, response.getStatus(), (String) response.getEntity());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<Arn>arn:aws:sts::000000000000:assumed-role/TestRole/test-session</Arn>"),
                "AssumedRoleUser Arn must use the stored role's partition");
    }

    @Test
    void assumeRoleAllowsGovCloudPartitionRoleArn() {
        IamRole role = new IamRole();
        role.setRoleName("TestRole");
        role.setPath("/");
        role.setArn("arn:aws:iam::000000000000:role/TestRole");
        StsQueryHandler handler = newHandler(role);

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleArn", "arn:aws-us-gov:iam::000000000000:role/TestRole");
        params.putSingle("RoleSessionName", "test-session");

        Response response = handler.handle("AssumeRole", params);

        assertEquals(200, response.getStatus(), (String) response.getEntity());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<Arn>arn:aws:sts::000000000000:assumed-role/TestRole/test-session</Arn>"),
                "AssumedRoleUser Arn must use the stored role's partition");
    }

    @Test
    void roleArnMatchesValidatesPartitionsAndPaths() {
        IamRole role = new IamRole();
        role.setRoleName("TestRole");
        role.setPath("/team/");
        role.setArn("arn:aws:iam::000000000000:role/team/TestRole");

        assertTrue(StsQueryHandler.roleArnMatches("arn:aws:iam::000000000000:role/team/TestRole", role));
        assertTrue(StsQueryHandler.roleArnMatches("arn:aws-cn:iam::000000000000:role/team/TestRole", role));
        assertTrue(StsQueryHandler.roleArnMatches("arn:aws-us-gov:iam::000000000000:role/team/TestRole", role));

        assertFalse(StsQueryHandler.roleArnMatches("arn:aws:iam::000000000000:role/other/TestRole", role));
        assertFalse(StsQueryHandler.roleArnMatches("arn:aws:iam::111111111111:role/team/TestRole", role));
        assertFalse(StsQueryHandler.roleArnMatches("arn:aws:iam:us-east-1:000000000000:role/team/TestRole", role));
        assertFalse(StsQueryHandler.roleArnMatches("arn:aws:s3:::bucket", role));
        assertFalse(StsQueryHandler.roleArnMatches("not-an-arn", role));
        assertFalse(StsQueryHandler.roleArnMatches(null, role));
    }

    @ParameterizedTest
    @ValueSource(strings = {"900", "43200"})
    void assumeRoleAcceptsDurationSecondsAtTheLimits(String durationSeconds) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleArn", "arn:aws:iam::000000000000:role/TestRole");
        params.putSingle("RoleSessionName", "test-session");
        params.putSingle("DurationSeconds", durationSeconds);

        Response response = newHandler().handle("AssumeRole", params);

        assertEquals(200, response.getStatus(), (String) response.getEntity());
    }

    @ParameterizedTest
    @ValueSource(strings = {"900", "129600"})
    void getSessionTokenAcceptsDurationSecondsAtTheLimits(String durationSeconds) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("DurationSeconds", durationSeconds);

        Response response = newHandler().handle("GetSessionToken", params);

        assertEquals(200, response.getStatus(), (String) response.getEntity());
    }

    @ParameterizedTest
    @ValueSource(strings = {"900", "129600"})
    void getFederationTokenAcceptsDurationSecondsAtTheLimits(String durationSeconds) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("Name", "test-user");
        params.putSingle("DurationSeconds", durationSeconds);

        Response response = newHandler().handle("GetFederationToken", params);

        assertEquals(200, response.getStatus(), (String) response.getEntity());
    }

    @Test
    void assumeRoleRejectsDurationSecondsBelowMinimum() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleArn", "arn:aws:iam::000000000000:role/TestRole");
        params.putSingle("RoleSessionName", "test-session");
        params.putSingle("DurationSeconds", "899");

        Response response = newHandler().handle("AssumeRole", params);

        assertEquals(400, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("ValidationError"), body);
        assertTrue(body.contains("greater than or equal to 900"), body);
    }

    @Test
    void assumeRoleRejectsDurationSecondsAboveMaximum() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleArn", "arn:aws:iam::000000000000:role/TestRole");
        params.putSingle("RoleSessionName", "test-session");
        params.putSingle("DurationSeconds", "43201");

        Response response = newHandler().handle("AssumeRole", params);

        assertEquals(400, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("ValidationError"), body);
        assertTrue(body.contains("less than or equal to 43200"), body);
    }

    @Test
    void assumeRoleRejectsNonNumericDurationSeconds() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleArn", "arn:aws:iam::000000000000:role/TestRole");
        params.putSingle("RoleSessionName", "test-session");
        params.putSingle("DurationSeconds", "notanumber");

        Response response = newHandler().handle("AssumeRole", params);

        assertEquals(400, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("InvalidParameterValue"), body);
    }

    @Test
    void getSessionTokenRejectsDurationSecondsBelowMinimum() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("DurationSeconds", "899");

        Response response = newHandler().handle("GetSessionToken", params);

        assertEquals(400, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("ValidationError"), body);
        assertTrue(body.contains("greater than or equal to 900"), body);
    }

    @Test
    void getSessionTokenRejectsDurationSecondsAboveMaximum() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("DurationSeconds", "129601");

        Response response = newHandler().handle("GetSessionToken", params);

        assertEquals(400, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("ValidationError"), body);
        assertTrue(body.contains("less than or equal to 129600"), body);
    }

    @Test
    void getFederationTokenRejectsDurationSecondsAboveMaximum() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("Name", "test-user");
        params.putSingle("DurationSeconds", "129601");

        Response response = newHandler().handle("GetFederationToken", params);

        assertEquals(400, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("ValidationError"), body);
        assertTrue(body.contains("less than or equal to 129600"), body);
    }

    @Test
    void assumeRoleWithWebIdentityRejectsDurationSecondsBelowMinimum() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleArn", "arn:aws:iam::000000000000:role/TestRole");
        params.putSingle("RoleSessionName", "test-session");
        params.putSingle("WebIdentityToken", "token");
        params.putSingle("DurationSeconds", "899");

        Response response = newHandler().handle("AssumeRoleWithWebIdentity", params);

        assertEquals(400, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("ValidationError"), body);
        assertTrue(body.contains("greater than or equal to 900"), body);
    }

    @Test
    void assumeRoleWithWebIdentityRejectsMalformedRoleArnWhenTokenCannotBeVerified() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleArn", "not-an-arn");
        params.putSingle("RoleSessionName", "test-session");
        params.putSingle("WebIdentityToken", "opaque-token");

        Response response = newHandler(null).handle("AssumeRoleWithWebIdentity", params);

        assertEquals(400, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("<Code>ValidationError</Code>"));
    }

    @Test
    void assumeRoleWithWebIdentityRejectsNonexistentRoleWhenTokenCannotBeVerified() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleArn", "arn:aws:iam::000000000000:role/NonexistentRole");
        params.putSingle("RoleSessionName", "test-session");
        params.putSingle("WebIdentityToken", "opaque-token");

        Response response = newHandler(null).handle("AssumeRoleWithWebIdentity", params);

        assertEquals(403, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<Code>AccessDenied</Code>"), body);
        assertTrue(body.contains("Not authorized to perform sts:AssumeRoleWithWebIdentity on resource: "
                + "arn:aws:iam::000000000000:role/NonexistentRole"), body);
    }

    @Test
    void assumeRoleWithWebIdentitySucceedsWhenRoleExistsAndTokenCannotBeVerified() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleArn", "arn:aws:iam::000000000000:role/TestRole");
        params.putSingle("RoleSessionName", "test-session");
        params.putSingle("WebIdentityToken", "opaque-token");

        Response response = newHandler().handle("AssumeRoleWithWebIdentity", params);

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<AssumeRoleWithWebIdentityResult>"), body);
        assertTrue(body.contains("<Arn>arn:aws:sts::000000000000:assumed-role/TestRole/test-session</Arn>"), body);
    }

    @Test
    void assumeRoleWithWebIdentityRejectsRoleMismatchWhenTokenCannotBeVerified() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleArn", "arn:aws:iam::111111111111:role/TestRole");
        params.putSingle("RoleSessionName", "test-session");
        params.putSingle("WebIdentityToken", "opaque-token");

        Response response = newHandler().handle("AssumeRoleWithWebIdentity", params);

        assertEquals(403, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<Code>AccessDenied</Code>"), body);
    }

    @Test
    void assumeRoleWithSAMLRejectsDurationSecondsBelowMinimum() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleArn", "arn:aws:iam::000000000000:role/TestRole");
        params.putSingle("PrincipalArn", "arn:aws:iam::000000000000:saml-provider/test");
        params.putSingle("SAMLAssertion", "not-checked-before-duration-validation");
        params.putSingle("DurationSeconds", "899");

        Response response = newHandler().handle("AssumeRoleWithSAML", params);

        assertEquals(400, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("ValidationError"), body);
        assertTrue(body.contains("greater than or equal to 900"), body);
    }

    /** The root ARN GetCallerIdentity falls back to carries the deployment's partition, not {@code aws}. */
    @ParameterizedTest
    @MethodSource("io.github.hectorvent.floci.testing.PartitionMatrix#cases")
    void getCallerIdentityMintsTheRootArnInTheDeploymentsPartition(PartitionCase partitionCase) {
        Response response = newHandlerForRegion(PartitionMatrix.regionResolver(partitionCase))
                .handle("GetCallerIdentity", new MultivaluedHashMap<>());

        assertEquals(200, response.getStatus());
        String arn = extract(ARN, (String) response.getEntity());
        assertEquals("arn:" + partitionCase.partition() + ":iam::" + PartitionMatrix.ACCOUNT + ":root", arn);
        PartitionMatrix.assertGlobalArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource("io.github.hectorvent.floci.testing.PartitionMatrix#cases")
    void getFederationTokenMintsTheFederatedUserArnInTheDeploymentsPartition(PartitionCase partitionCase) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("Name", "bob");

        Response response = newHandlerForRegion(PartitionMatrix.regionResolver(partitionCase))
                .handle("GetFederationToken", params);

        assertEquals(200, response.getStatus());
        String arn = extract(ARN, (String) response.getEntity());
        assertEquals("arn:" + partitionCase.partition() + ":sts::" + PartitionMatrix.ACCOUNT
                + ":federated-user/bob", arn);
    }

    /**
     * A role and its account's OIDC provider are IAM resources of one partition, so a trust policy
     * naming the provider ARN CreateOpenIDConnectProvider returned keeps matching and the session
     * lands in the stored role's partition, whatever region AssumeRoleWithWebIdentity is signed for
     * and whichever partition its RoleArn names.
     */
    @Test
    void webIdentityMatchesTheProviderAndMintsTheSessionInTheRolesPartition() throws Exception {
        String issuer = "https://oidc.example.com/id/ABC";
        String roleArn = "arn:aws-cn:iam::000000000000:role/web-role";
        String requestedRoleArn = "arn:aws:iam::000000000000:role/web-role";
        String providerArn = "arn:aws-cn:iam::000000000000:oidc-provider/oidc.example.com/id/ABC";

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.IamServiceConfig iam = mock(EmulatorConfig.IamServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.iam()).thenReturn(iam);
        IamService iamService = mock(IamService.class);
        IamRole role = new IamRole();
        role.setArn(roleArn);
        role.setAssumeRolePolicyDocument("{}");
        when(iamService.findRole("000000000000", "web-role")).thenReturn(Optional.of(role));
        WebIdentityTokenVerifier verifier = mock(WebIdentityTokenVerifier.class);
        when(verifier.peekIssuer("jwt")).thenReturn(Optional.of(issuer));
        when(verifier.verify(eq("jwt"), any(), eq(issuer), anyString()))
                .thenReturn(new WebIdentityToken(issuer, "system:serviceaccount:default:app",
                        List.of("sts.amazonaws.com")));
        OidcIssuerKeyLookup keys = mock(OidcIssuerKeyLookup.class);
        when(keys.findVerificationKey(issuer)).thenReturn(Optional.of(mock(RSAPublicKey.class)));
        WebIdentityTrustPolicyEvaluator trust = mock(WebIdentityTrustPolicyEvaluator.class);
        when(trust.allows(eq("{}"), eq(providerArn), eq("oidc.example.com/id/ABC"), anyMap())).thenReturn(true);

        StsQueryHandler handler = new StsQueryHandler(iamService, mock(AccountResolver.class),
                new RegionResolver(REGION, "000000000000"), config, mock(AssumeRolePolicyEvaluator.class),
                trust, verifier, keys, mock(SAMLProviderService.class), mock(SAMLTrustPolicyEvaluator.class));
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleArn", requestedRoleArn);
        params.putSingle("RoleSessionName", "app");
        params.putSingle("WebIdentityToken", "jwt");

        Response response = handler.handle("AssumeRoleWithWebIdentity", params);

        assertEquals(200, response.getStatus(), (String) response.getEntity());
        verify(trust).allows(eq("{}"), eq(providerArn), eq("oidc.example.com/id/ABC"), anyMap());
        assertEquals("arn:aws-cn:sts::000000000000:assumed-role/web-role/app",
                extract(ARN, (String) response.getEntity()));
    }

    /** A session keeps its role's partition even when AssumeRole is signed for another one. */
    @Test
    void assumeRoleMintsTheSessionInTheRolesPartition() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleArn", "arn:aws:iam::000000000000:role/commercial-role");
        params.putSingle("RoleSessionName", "s");
        IamRole role = new IamRole();
        role.setRoleName("commercial-role");
        role.setArn("arn:aws:iam::000000000000:role/commercial-role");

        Response response = newHandler(role, new RegionResolver("cn-north-1", "000000000000"))
                .handle("AssumeRole", params);

        assertEquals(200, response.getStatus());
        assertEquals("arn:aws:sts::000000000000:assumed-role/commercial-role/s",
                extract(ARN, (String) response.getEntity()));
    }

    @Test
    void assumeRoleUsesRolesOwnRoleIdForAssumedRoleId() {
        IamRole role = new IamRole();
        role.setRoleName("sender");
        role.setRoleId("AROAKX9OBMP2TSQJZ48H");
        role.setArn("arn:aws:iam::000000000000:role/sender");
        StsQueryHandler handler = newHandler(role);

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("RoleArn", role.getArn());
        params.add("RoleSessionName", "my-session");

        Response response = handler.handle("AssumeRole", params);
        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<AssumedRoleId>AROAKX9OBMP2TSQJZ48H:my-session</AssumedRoleId>"), body);
    }

    private static String extract(Pattern pattern, String body) {
        Matcher matcher = pattern.matcher(body);
        assertTrue(matcher.find(), "expected " + pattern + " in " + body);
        return matcher.group(1);
    }
}
