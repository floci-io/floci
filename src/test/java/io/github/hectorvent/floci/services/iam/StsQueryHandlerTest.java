package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.core.common.OidcIssuerKeyLookup;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.WebIdentityTokenVerifier;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StsQueryHandlerTest {

    private static final String REGION = "us-east-1";
    private static final Pattern SECRET_ACCESS_KEY =
            Pattern.compile("<SecretAccessKey>([^<]+)</SecretAccessKey>");
    private static final Pattern SESSION_TOKEN =
            Pattern.compile("<SessionToken>([^<]+)</SessionToken>");

    private static StsQueryHandler newHandler() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.IamServiceConfig iam = mock(EmulatorConfig.IamServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.iam()).thenReturn(iam);
        when(iam.enforcementEnabled()).thenReturn(false);

        return new StsQueryHandler(
                mock(IamService.class),
                mock(AccountResolver.class),
                new RegionResolver(REGION, "000000000000"),
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

    private static String extract(Pattern pattern, String body) {
        Matcher matcher = pattern.matcher(body);
        assertTrue(matcher.find(), "expected " + pattern + " in " + body);
        return matcher.group(1);
    }
}
