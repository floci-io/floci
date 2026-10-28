package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.core.common.OidcIssuerKeyLookup;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.WebIdentityToken;
import io.github.hectorvent.floci.core.common.WebIdentityTokenVerifier;
import io.github.hectorvent.floci.services.iam.model.DelegationRequest;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.OutboundWebIdentityFederation;
import io.github.hectorvent.floci.testing.PartitionMatrix;
import io.github.hectorvent.floci.testing.PartitionMatrix.PartitionCase;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StsQueryHandlerTest {

    private static final String REGION = "us-east-1";
    private static final Pattern SECRET_ACCESS_KEY =
            Pattern.compile("<SecretAccessKey>([^<]+)</SecretAccessKey>");
    private static final Pattern SESSION_TOKEN =
            Pattern.compile("<SessionToken>([^<]+)</SessionToken>");

    private static final Pattern ARN = Pattern.compile("<Arn>([^<]+)</Arn>");
    private static final Pattern EXPIRATION = Pattern.compile("<Expiration>([^<]+)</Expiration>");

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
        return newHandler((IamRole) null, regionResolver);
    }

    private static StsQueryHandler newHandler(IamRole role, RegionResolver regionResolver) {
        IamService iamService = mock(IamService.class);
        when(iamService.findRole(anyString(), anyString())).thenReturn(Optional.ofNullable(role));
        return newHandler(iamService, regionResolver);
    }

    private static StsQueryHandler newHandler(IamService iamService, RegionResolver regionResolver) {
        return newHandler(iamService, regionResolver, Clock.systemUTC());
    }

    private static StsQueryHandler newHandler(IamService iamService, RegionResolver regionResolver, Clock clock) {
        return newHandler(iamService, regionResolver, clock, mock(DelegationRequestService.class));
    }

    private static StsQueryHandler newHandler(IamService iamService, RegionResolver regionResolver,
                                              Clock clock, DelegationRequestService delegation) {
        return newHandler(iamService, regionResolver, clock, delegation,
                mock(WebIdentityTokenIssuer.class));
    }

    private static StsQueryHandler newHandler(IamService iamService, RegionResolver regionResolver,
                                              Clock clock, DelegationRequestService delegation,
                                              WebIdentityTokenIssuer tokenIssuer) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.IamServiceConfig iam = mock(EmulatorConfig.IamServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.iam()).thenReturn(iam);
        when(iam.enforcementEnabled()).thenReturn(false);

        return new StsQueryHandler(
                iamService,
                new AccountResolver("000000000000"),
                regionResolver,
                config,
                mock(AssumeRolePolicyEvaluator.class),
                mock(WebIdentityTrustPolicyEvaluator.class),
                mock(WebIdentityTokenVerifier.class),
                mock(OidcIssuerKeyLookup.class),
                mock(SAMLProviderService.class),
                mock(SAMLTrustPolicyEvaluator.class),
                delegation,
                mock(CentralRootAccess.class),
                tokenIssuer,
                clock);
    }

    @ParameterizedTest
    @CsvSource({"ASIAtemporary, AKIAlongterm", "-, ASIAtemporary", "ASIAtemporary, -",
            "blank, ASIAtemporary", "space, ASIAtemporary"})
    void temporaryHeaderOrQueryCallerIsRejectedBeforeSessionRegistration(String headerKey, String queryKey) {
        for (String action : new String[]{"GetSessionToken", "GetFederationToken"}) {
            IamService service = mock(IamService.class);
            StsQueryHandler handler = newHandler(service, new RegionResolver(REGION, "000000000000"));
            setCaller(handler, headerKey, queryKey);
            Response response = handler.handle(action, new MultivaluedHashMap<>());
            assertEquals(403, response.getStatus());
            assertTrue(response.getEntity().toString().contains("<Code>AccessDenied</Code>"));
            assertTrue(response.getEntity().toString().contains("Cannot call " + action + " with session credentials"));
            verifyNoInteractions(service);
        }
    }

    @ParameterizedTest
    @CsvSource({"AKIAlongterm, ASIAtemporary", "-, AKIAlongterm", "-, -",
            "blank, AKIAlongterm", "space, AKIAlongterm", "blank, -", "space, -"})
    void longTermAndAnonymousCompatibilityCallsKeepExistingIssuance(String headerKey, String queryKey) {
        for (String action : new String[]{"GetSessionToken", "GetFederationToken"}) {
            StsQueryHandler handler = newHandler();
            setCaller(handler, headerKey, queryKey);
            MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
            if ("GetFederationToken".equals(action)) {
                params.putSingle("Name", "caller");
            }
            Response response = handler.handle(action, params);
            assertEquals(200, response.getStatus());
        }
    }

    /** The account's federation state with the feature on, which the operation reads the issuer from. */
    private static OutboundWebIdentityFederation enabledFederation() {
        OutboundWebIdentityFederation federation = new OutboundWebIdentityFederation();
        federation.setEnabled(true);
        federation.setIssuerIdentifier("https://sts.us-east-1.amazonaws.com/000000000000");
        return federation;
    }

    /**
     * A caller on temporary credentials "cannot use this operation to extend the lifetime of a
     * session beyond what was granted when the session was originally created", so the ceiling on
     * a web identity token is the session's own expiration. The boundary is that instant itself: a
     * token ending exactly with the session is issued, and one ending a second later is not, which
     * is why the clock is fixed here rather than read. A long-term caller has no session to
     * escalate, so only the duration's own 3600 holds it.
     */
    @ParameterizedTest
    @CsvSource({"ASIASESSION, 299, 200", "ASIASESSION, 300, 200", "ASIASESSION, 301, 403",
            "ASIASESSION, 3600, 403", "AKIALONGTERM, 3600, 200"})
    void aWebIdentityTokenCannotOutliveTheCallersSession(String accessKeyId, String duration,
                                                         int expectedStatus) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        IamService iamService = mock(IamService.class);
        when(iamService.findEnabledOutboundWebIdentityFederation())
                .thenReturn(Optional.of(enabledFederation()));
        when(iamService.findSessionExpiration("ASIASESSION"))
                .thenReturn(Optional.of(now.plusSeconds(300)));
        WebIdentityTokenIssuer tokenIssuer = mock(WebIdentityTokenIssuer.class);
        when(tokenIssuer.mint(any(), anyString(), anyString(), anyList(), any(), any(), any()))
                .thenReturn("header.claims.signature");
        StsQueryHandler handler = newHandler(iamService, new RegionResolver(REGION, "000000000000"),
                Clock.fixed(now, ZoneOffset.UTC), mock(DelegationRequestService.class),
                tokenIssuer);
        setCaller(handler, accessKeyId, "-");

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("Audience.member.1", "app");
        params.putSingle("SigningAlgorithm", "RS256");
        params.putSingle("DurationSeconds", duration);
        Response response = handler.handle("GetWebIdentityToken", params);

        String body = (String) response.getEntity();
        assertEquals(expectedStatus, response.getStatus(), body);
        if (expectedStatus == 403) {
            assertTrue(body.contains("<Code>SessionDurationEscalationException</Code>"), body);
            assertTrue(body.contains("You cannot use this operation to extend the lifetime of a"
                    + " session beyond what was granted when the session was originally created."),
                    body);
            verifyNoInteractions(tokenIssuer);
        } else {
            verify(tokenIssuer).mint(any(), anyString(), anyString(), anyList(), any(), any(),
                    any());
        }
    }

    /**
     * A temporary credential the session store no longer knows is refused as expired rather than
     * minted for. The lookup comes back empty for a session that has gone, which would otherwise
     * fall through to the account root ARN this file hands a caller it cannot resolve, and a
     * token's subject is signed and outlives the request that asked for it.
     */
    @Test
    void aTemporaryCredentialWithNoLiveSessionIsRefusedRatherThanSignedAsRoot() {
        IamService iamService = mock(IamService.class);
        when(iamService.findEnabledOutboundWebIdentityFederation())
                .thenReturn(Optional.of(enabledFederation()));
        WebIdentityTokenIssuer tokenIssuer = mock(WebIdentityTokenIssuer.class);
        StsQueryHandler handler = newHandler(iamService, new RegionResolver(REGION, "000000000000"),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
                mock(DelegationRequestService.class), tokenIssuer);
        setCaller(handler, "ASIAGONE", "-");

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("Audience.member.1", "app");
        params.putSingle("SigningAlgorithm", "RS256");
        Response response = handler.handle("GetWebIdentityToken", params);

        String body = (String) response.getEntity();
        assertEquals(403, response.getStatus(), body);
        assertTrue(body.contains("<Code>ExpiredTokenException</Code>"), body);
        assertTrue(body.contains("The security token included in the request has expired."), body);
        assertFalse(body.contains(":root"), body);
        verifyNoInteractions(tokenIssuer);
    }

    private static void setCaller(StsQueryHandler handler, String headerKey, String queryKey) {
        handler.headers = mock(HttpHeaders.class);
        if (!"-".equals(headerKey)) {
            String authorization = switch (headerKey) {
                case "blank" -> "";
                case "space" -> " ";
                default -> "AWS4-HMAC-SHA256 Credential=" + headerKey + "/20261013/us-east-1/sts/aws4_request";
            };
            when(handler.headers.getHeaderString("Authorization")).thenReturn(authorization);
        }
        handler.uriInfo = mock(UriInfo.class);
        MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
        if (!"-".equals(queryKey)) {
            query.putSingle("X-Amz-Credential", queryKey + "/20261013/us-east-1/sts/aws4_request");
        }
        when(handler.uriInfo.getQueryParameters()).thenReturn(query);
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
    void assumeRoleRecordsSessionTagsTransitiveKeysAndSourceIdentity() {
        IamRole role = new IamRole();
        role.setRoleName("TestRole");
        role.setArn("arn:aws:iam::000000000000:role/TestRole");
        IamService service = mock(IamService.class);
        when(service.findRole(anyString(), anyString())).thenReturn(Optional.of(role));
        StsQueryHandler handler = newHandler(service, new RegionResolver(REGION, "000000000000"));
        setCaller(handler, "AKIACALLER", "-");
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleArn", "arn:aws:iam::000000000000:role/TestRole");
        params.putSingle("RoleSessionName", "tagged-session");
        params.putSingle("Tags.member.1.Key", "team");
        params.putSingle("Tags.member.1.Value", "blue");
        params.putSingle("Tags.member.2.Key", "env");
        params.putSingle("Tags.member.2.Value", "qa");
        params.putSingle("TransitiveTagKeys.member.1", "team");
        params.putSingle("SourceIdentity", "alice");

        Response response = handler.handle("AssumeRole", params);

        assertEquals(200, response.getStatus());
        verify(service).setSessionTags(anyString(),
                eq(Map.of("team", "blue", "env", "qa")), eq(Set.of("team")), eq("alice"));
    }

    @Test
    void assumeRoleInheritsTransitiveSessionTagsAndSourceIdentityFromTheCaller() {
        IamRole role = new IamRole();
        role.setRoleName("TestRole");
        role.setArn("arn:aws:iam::000000000000:role/TestRole");
        IamService service = mock(IamService.class);
        when(service.findRole(anyString(), anyString())).thenReturn(Optional.of(role));
        when(service.transitiveSessionTags("ASIACALLER")).thenReturn(Map.of("team", "blue"));
        when(service.sessionSourceIdentity("ASIACALLER")).thenReturn("alice");
        StsQueryHandler handler = newHandler(service, new RegionResolver(REGION, "000000000000"));
        setCaller(handler, "ASIACALLER", "-");
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleArn", "arn:aws:iam::000000000000:role/TestRole");
        params.putSingle("RoleSessionName", "chained-session");
        params.putSingle("Tags.member.1.Key", "env");
        params.putSingle("Tags.member.1.Value", "qa");

        Response response = handler.handle("AssumeRole", params);

        assertEquals(200, response.getStatus());
        verify(service).setSessionTags(anyString(),
                eq(Map.of("team", "blue", "env", "qa")), eq(Set.of("team")), eq("alice"));
    }

    @Test
    void assumeRoleRejectsDuplicateSessionTagKeys() {
        StsQueryHandler handler = newHandler();
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleArn", "arn:aws:iam::000000000000:role/TestRole");
        params.putSingle("RoleSessionName", "test-session");
        params.putSingle("Tags.member.1.Key", "team");
        params.putSingle("Tags.member.1.Value", "blue");
        params.putSingle("Tags.member.2.Key", "team");
        params.putSingle("Tags.member.2.Value", "red");

        Response response = handler.handle("AssumeRole", params);

        assertEquals(400, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("<Code>ValidationError</Code>"));
    }

    @Test
    void assumeRoleRejectsAReusedInheritedTransitiveTagKey() {
        IamService service = mock(IamService.class);
        when(service.transitiveSessionTags("ASIACALLER")).thenReturn(Map.of("team", "blue"));
        StsQueryHandler handler = newHandler(service, new RegionResolver(REGION, "000000000000"));
        setCaller(handler, "ASIACALLER", "-");
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleArn", "arn:aws:iam::000000000000:role/TestRole");
        params.putSingle("RoleSessionName", "chained-session");
        params.putSingle("Tags.member.1.Key", "team");
        params.putSingle("Tags.member.1.Value", "red");

        Response response = handler.handle("AssumeRole", params);

        assertEquals(400, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("<Code>ValidationError</Code>"));
        assertTrue(((String) response.getEntity()).contains("transitive session tag"));
    }

    @Test
    void assumeRoleRejectsACaseOnlyReuseOfAnInheritedTransitiveTagKey() {
        IamService service = mock(IamService.class);
        when(service.transitiveSessionTags("ASIACALLER")).thenReturn(Map.of("team", "blue"));
        StsQueryHandler handler = newHandler(service, new RegionResolver(REGION, "000000000000"));
        setCaller(handler, "ASIACALLER", "-");
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleArn", "arn:aws:iam::000000000000:role/TestRole");
        params.putSingle("RoleSessionName", "chained-session");
        params.putSingle("Tags.member.1.Key", "TEAM");
        params.putSingle("Tags.member.1.Value", "red");

        Response response = handler.handle("AssumeRole", params);

        assertEquals(400, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("<Code>ValidationError</Code>"));
        assertTrue(((String) response.getEntity()).contains("transitive session tag"));
    }

    @Test
    void assumeRoleRejectsCaseOnlyDuplicateSessionTagKeys() {
        StsQueryHandler handler = newHandler();
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleArn", "arn:aws:iam::000000000000:role/TestRole");
        params.putSingle("RoleSessionName", "test-session");
        params.putSingle("Tags.member.1.Key", "team");
        params.putSingle("Tags.member.1.Value", "blue");
        params.putSingle("Tags.member.2.Key", "TEAM");
        params.putSingle("Tags.member.2.Value", "red");

        Response response = handler.handle("AssumeRole", params);

        assertEquals(400, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("<Code>ValidationError</Code>"));
    }

    @Test
    void assumeRoleRejectsATransitiveKeyTheRequestDoesNotPass() {
        StsQueryHandler handler = newHandler();
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleArn", "arn:aws:iam::000000000000:role/TestRole");
        params.putSingle("RoleSessionName", "test-session");
        params.putSingle("Tags.member.1.Key", "team");
        params.putSingle("Tags.member.1.Value", "blue");
        params.putSingle("TransitiveTagKeys.member.1", "env");

        Response response = handler.handle("AssumeRole", params);

        assertEquals(400, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("<Code>ValidationError</Code>"));
    }

    @Test
    void assumeRoleCannotChangeAnInheritedSourceIdentity() {
        IamRole role = new IamRole();
        role.setRoleName("TestRole");
        role.setArn("arn:aws:iam::000000000000:role/TestRole");
        IamService service = mock(IamService.class);
        when(service.findRole(anyString(), anyString())).thenReturn(Optional.of(role));
        when(service.sessionSourceIdentity("ASIACALLER")).thenReturn("alice");
        StsQueryHandler handler = newHandler(service, new RegionResolver(REGION, "000000000000"));
        setCaller(handler, "ASIACALLER", "-");
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleArn", "arn:aws:iam::000000000000:role/TestRole");
        params.putSingle("RoleSessionName", "chained-session");
        params.putSingle("SourceIdentity", "bob");

        Response response = handler.handle("AssumeRole", params);

        assertEquals(403, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("<Code>AccessDenied</Code>"));
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
    void assumeRoleOnARoleArnFromAnotherPartitionRegistersTheAccountsOneRole() {
        String storedArn = "arn:aws:iam::000000000000:role/TestRole";
        IamService iamService = mock(IamService.class);
        when(iamService.findRole("000000000000", "TestRole"))
                .thenReturn(Optional.of(new IamRole("AROA-test", "TestRole", "/", storedArn, "{}")));
        StsQueryHandler handler = newHandler(iamService, new RegionResolver(REGION, "000000000000"));

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleArn", "arn:aws-cn:iam::000000000000:role/TestRole");
        params.putSingle("RoleSessionName", "test-session");
        Response response = handler.handle("AssumeRole", params);

        assertEquals(200, response.getStatus(), (String) response.getEntity());
        verify(iamService).registerSession(anyString(), anyString(), anyString(), eq(storedArn), any(),
                any(), eq("000000000000"), eq("test-session"), eq("AROA-test:test-session"));
    }

    @Test
    void roleArnMatchesValidatesPartitionsAndPaths() {
        IamRole role = new IamRole();
        role.setRoleName("TestRole");
        role.setPath("/team/");
        role.setArn("arn:aws:iam::000000000000:role/team/TestRole");

        assertTrue(IamService.roleArnMatches("arn:aws:iam::000000000000:role/team/TestRole", role));
        assertTrue(IamService.roleArnMatches("arn:aws-cn:iam::000000000000:role/team/TestRole", role));
        assertTrue(IamService.roleArnMatches("arn:aws-us-gov:iam::000000000000:role/team/TestRole", role));

        assertFalse(IamService.roleArnMatches("arn:aws:iam::000000000000:role/other/TestRole", role));
        assertFalse(IamService.roleArnMatches("arn:aws:iam::111111111111:role/team/TestRole", role));
        assertFalse(IamService.roleArnMatches("arn:aws:iam:us-east-1:000000000000:role/team/TestRole", role));
        assertFalse(IamService.roleArnMatches("arn:aws:s3:::bucket", role));
        assertFalse(IamService.roleArnMatches("not-an-arn", role));
        assertFalse(IamService.roleArnMatches(null, role));
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

    @Test
    void getAccessKeyInfoAnswersTheAccountThatHoldsTheKey() {
        IamService service = mock(IamService.class);
        when(service.findAccountForAccessKeyId("AKIAIOSFODNN7EXAMPLE"))
                .thenReturn(Optional.of("111122223333"));
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("AccessKeyId", "AKIAIOSFODNN7EXAMPLE");

        Response response = newHandler(service, new RegionResolver(REGION, "000000000000"))
                .handle("GetAccessKeyInfo", params);

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<GetAccessKeyInfoResponse"), body);
        assertTrue(body.contains("<Account>111122223333</Account>"), body);
        // The lookup is the state-independent one, not the authentication path.
        verify(service).findAccountForAccessKeyId("AKIAIOSFODNN7EXAMPLE");
        verify(service, never()).resolveAccountId(anyString());
    }

    @Test
    void getAccessKeyInfoRefusesAKeyNobodyHolds() {
        // The operation models no error at all, and the page says only that a deleted key "might
        // return an error that the key doesn't exist", so this is the published common error
        // nearest that sentence rather than a reading.
        IamService service = mock(IamService.class);
        when(service.findAccountForAccessKeyId(anyString())).thenReturn(Optional.empty());
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("AccessKeyId", "AKIAABSENTKEY0000000");

        Response response = newHandler(service, new RegionResolver(REGION, "000000000000"))
                .handle("GetAccessKeyInfo", params);

        assertEquals(400, response.getStatus());
        assertTrue(response.getEntity().toString().contains("<Code>InvalidParameterValue</Code>"));
    }

    @ParameterizedTest
    @CsvSource({
        "-, Value null at 'accessKeyId' failed to satisfy constraint: Member must not be null",
        "AKIATOOSHORT, Value 'AKIATOOSHORT' at 'accessKeyId' failed to satisfy constraint: "
                + "Member must have length greater than or equal to 16",
        "AKIA-NOT-A-VALID-KEY-ID, Member must satisfy regular expression pattern",
    })
    void getAccessKeyInfoReadsTheIdsShapeBeforeAnyLookup(String accessKeyId, String expected) {
        IamService service = mock(IamService.class);
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        if (!"-".equals(accessKeyId)) {
            params.add("AccessKeyId", accessKeyId);
        }

        Response response = newHandler(service, new RegionResolver(REGION, "000000000000"))
                .handle("GetAccessKeyInfo", params);

        assertEquals(400, response.getStatus());
        // The Query error body escapes the apostrophes the message quotes the member with.
        String body = ((String) response.getEntity()).replace("&apos;", "'");
        assertTrue(body.contains("<Code>ValidationError</Code>"), body);
        assertTrue(body.contains(expected), body);
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void getAccessKeyInfoReadsAPresentBlankIdAsAValue(String accessKeyId) {
        // Present and empty is a value, not an absence: it fails the minimum and is quoted in the
        // message, which is what IAM answers for an empty RoleName over this same wire.
        IamService service = mock(IamService.class);
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("AccessKeyId", accessKeyId);

        Response response = newHandler(service, new RegionResolver(REGION, "000000000000"))
                .handle("GetAccessKeyInfo", params);

        assertEquals(400, response.getStatus());
        String body = ((String) response.getEntity()).replace("&apos;", "'");
        assertTrue(body.contains("Value '" + accessKeyId + "' at 'accessKeyId' failed to satisfy "
                + "constraint: Member must have length greater than or equal to 16"), body);
        verifyNoInteractions(service);
    }

    @Test
    void getAccessKeyInfoRefusesAnIdPastTheMaximum() {
        IamService service = mock(IamService.class);
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("AccessKeyId", "A".repeat(129));

        Response response = newHandler(service, new RegionResolver(REGION, "000000000000"))
                .handle("GetAccessKeyInfo", params);

        assertEquals(400, response.getStatus());
        assertTrue(response.getEntity().toString()
                .contains("Member must have length less than or equal to 128"));
        verifyNoInteractions(service);
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
                trust, verifier, keys, mock(SAMLProviderService.class), mock(SAMLTrustPolicyEvaluator.class),
                mock(DelegationRequestService.class), mock(CentralRootAccess.class),
                mock(WebIdentityTokenIssuer.class));
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

    /**
     * Smithy's date-time format carries at most millisecond precision and asks serializers to truncate
     * anything finer, so the XML drops the sub-millisecond digits while the session keeps the full instant.
     */
    @ParameterizedTest
    @CsvSource({
            "2026-10-05T05:47:29.777826302Z, 2026-10-05T05:47:29.777Z",
            "2026-10-05T05:47:29.010000000Z, 2026-10-05T05:47:29.010Z",
            "2026-10-05T05:47:29.000999999Z, 2026-10-05T05:47:29Z",
            "2026-10-05T05:47:29Z, 2026-10-05T05:47:29Z"
    })
    void assumeRoleWritesExpirationAtMillisecondPrecision(String expiresAt, String expected) {
        IamRole role = new IamRole();
        role.setRoleName("TestRole");
        role.setArn("arn:aws:iam::000000000000:role/TestRole");
        IamService iamService = mock(IamService.class);
        when(iamService.findRole(anyString(), anyString())).thenReturn(Optional.of(role));
        Instant expiration = Instant.parse(expiresAt);
        Clock clock = Clock.fixed(expiration.minusSeconds(900), ZoneOffset.UTC);
        StsQueryHandler handler = newHandler(iamService, new RegionResolver(REGION, "000000000000"), clock);

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleArn", role.getArn());
        params.putSingle("RoleSessionName", "s");
        params.putSingle("DurationSeconds", "900");

        Response response = handler.handle("AssumeRole", params);

        assertEquals(200, response.getStatus(), (String) response.getEntity());
        assertEquals(expected, extract(EXPIRATION, (String) response.getEntity()));
        ArgumentCaptor<Instant> sessionExpiration = ArgumentCaptor.forClass(Instant.class);
        verify(iamService).registerSession(anyString(), anyString(), anyString(), eq(role.getArn()),
                sessionExpiration.capture(), isNull(), eq("000000000000"), eq("s"), anyString());
        assertEquals(expiration, sessionExpiration.getValue());
    }

    private static String extract(Pattern pattern, String body) {
        Matcher matcher = pattern.matcher(body);
        assertTrue(matcher.find(), "expected " + pattern + " in " + body);
        return matcher.group(1);
    }

    /**
     * The delegated session is the customer's grant, so it is registered in the customer's
     * account, as a delegated session carrying the identity that approved the request, and with no
     * issuing key. The request names a different owner here: accepting one records the identity
     * that called it and "this is the identity that gets mapped to the delegated credential", so a
     * session carrying the owner would hand over the wrong permissions on every request that was
     * forwarded for approval. A partner key that happened to name an IAM user would otherwise
     * become the session's issuer and bound it to that user's own permissions, which is the
     * opposite of what the delegation gave away, and registering the approver as an issued
     * session's role ARN would have it resolved by name.
     */
    @Test
    void theDelegatedSessionBelongsToTheCustomerAndCarriesItsApprover() {
        DelegationRequest request = new DelegationRequest();
        request.setDelegationRequestId("dr-0123456789abcdef01234567");
        request.setOwnerAccountId("000000000002");
        request.setOwnerId("arn:aws:iam::000000000002:user/claimant");
        request.setApproverId("arn:aws:iam::000000000002:user/admin");
        Instant expiry = Instant.parse("2026-01-01T02:00:00Z");
        IamService iamService = mock(IamService.class);
        DelegationRequestService delegation = mock(DelegationRequestService.class);
        when(delegation.redeem("000000000001", "token-1"))
                .thenReturn(new DelegationRequestService.Redeemed(request, expiry));
        StsQueryHandler handler = newHandler(iamService, new RegionResolver(REGION, "000000000001"),
                Clock.systemUTC(), delegation);

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("TradeInToken", "token-1");
        Response response = handler.handle("GetDelegatedAccessToken", params);

        assertEquals(200, response.getStatus(), (String) response.getEntity());
        assertTrue(((String) response.getEntity()).contains(
                "<AssumedPrincipal>arn:aws:iam::000000000002:user/admin</AssumedPrincipal>"),
                (String) response.getEntity());
        verify(iamService).registerDelegatedSession(anyString(), anyString(), anyString(),
                eq("arn:aws:iam::000000000002:user/admin"), eq(expiry), isNull(),
                eq("000000000002"));
        verify(iamService, never()).registerIssuedSession(anyString(), anyString(), anyString(),
                anyString(), any(), any(), anyString(), any());
    }
}
