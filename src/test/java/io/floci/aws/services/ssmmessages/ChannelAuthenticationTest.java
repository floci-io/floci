package io.floci.aws.services.ssmmessages;

import io.floci.aws.core.common.AccountResolver;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.IamEnforcementFilter;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.testing.ParallelLocks;
import io.floci.aws.testutil.AwsRequestSigner;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.net.HostAndPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class ChannelAuthenticationTest {

    private static final String ACCOUNT = "111122223333";
    private static final String REGION = "eu-west-1";
    private static final String INSTANCE = "i-0123456789abcdef0";
    private static final String KEY = "ASIAINSTANCE";
    private static final String SECRET = "instance-secret";
    private static final String TOKEN = "agent+session/token=";
    private static final String GATEWAY = "ssmmessages";
    private static final String CREATE = "ssmmessages:CreateControlChannel";
    private static final String SESSION_ARN = "arn:aws:ssm:eu-west-1:111122223333:session/example-session";
    private static final Instant NOW = Instant.parse("2026-11-23T21:00:00Z");
    private static final URI CONTROL = URI.create("http://localhost:4566/v1/control-channel/" + INSTANCE);
    private static final URI DATA = URI.create("http://localhost:4566/v1/data-channel/example-session");
    private static final byte[] BODY = "{\"MessageSchemaVersion\":\"1.0\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EMPTY = new byte[0];

    private final IamService iam = mock(IamService.class);
    private final IamEnforcementFilter iamEnforcement = mock(IamEnforcementFilter.class);
    private final ChannelAuthentication authentication = new ChannelAuthentication(iam, new AccountResolver(ACCOUNT),
            iamEnforcement, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void signedAgentPostIdentifiesTheInstanceFromItsImdsCredential() throws Exception {
        instanceCredentials();
        HttpServerRequest request = agentRequest(HttpMethod.POST, CONTROL, BODY, GATEWAY);
        assertEquals(new ChannelAuthentication.AgentIdentity(ACCOUNT, REGION, INSTANCE),
                authentication.authenticateAgent(request, BODY, CREATE));
    }

    @Test
    void signedAgentPostAcceptsHttp2AuthorityAsTheSignedHost() throws Exception {
        instanceCredentials();
        HttpServerRequest request = http2AgentPost(CONTROL.getHost());

        assertEquals(new ChannelAuthentication.AgentIdentity(ACCOUNT, REGION, INSTANCE),
                authentication.authenticateAgent(request, BODY, CREATE));
    }

    @Test
    void changingTheHttp2AuthorityInvalidatesTheSignature() throws Exception {
        instanceCredentials();
        HttpServerRequest request = http2AgentPost("other-host");

        assertThrows(AwsException.class, () -> authentication.authenticateAgent(request, BODY, CREATE));
    }

    @Test
    void signedAgentUpgradeUsesTheSameIdentityBinding() throws Exception {
        instanceCredentials();
        HttpServerRequest request = agentRequest(HttpMethod.GET, DATA, EMPTY, GATEWAY);
        assertEquals(Optional.of(new ChannelAuthentication.AgentIdentity(ACCOUNT, REGION, INSTANCE)),
                authentication.authenticateAgentUpgradeIfClaimed(request, "ssmmessages:OpenDataChannel", SESSION_ARN));
        // OpenDataChannel acts on the session, so a deny on one session's ARN reaches the agent too.
        verify(iamEnforcement).authorizeRequestParameters(anyString(), eq("ssmmessages:OpenDataChannel"),
                eq(SESSION_ARN), any());
    }

    @Test
    void changingTheAgentPostBodyInvalidatesItsSignature() throws Exception {
        instanceCredentials();
        HttpServerRequest request = agentRequest(HttpMethod.POST, CONTROL, BODY, GATEWAY);
        assertThrows(AwsException.class, () -> authentication.authenticateAgent(request, EMPTY, CREATE));
    }

    @Test
    void aSignedNonImdsCredentialCannotActAsAnAgent() throws Exception {
        when(iam.findSecretKey(KEY, TOKEN)).thenReturn(Optional.of(SECRET));
        HttpServerRequest request = agentRequest(HttpMethod.POST, CONTROL, BODY, GATEWAY);
        assertThrows(AwsException.class, () -> authentication.authenticateAgent(request, BODY, CREATE));
    }

    @Test
    void agentCredentialsSignedForAnotherServiceAreRefused() throws Exception {
        instanceCredentials();
        HttpServerRequest request = agentRequest(HttpMethod.POST, CONTROL, BODY, "ssm");
        assertThrows(AwsException.class, () -> authentication.authenticateAgent(request, BODY, CREATE));
    }

    @Test
    void aPresignedClientUpgradeResolvesItsIdentity() throws Exception {
        when(iam.resolveAccountId(KEY)).thenReturn(Optional.of(ACCOUNT));
        when(iam.findSecretKey(KEY, TOKEN)).thenReturn(Optional.of(SECRET));
        URI presigned = signer(KEY, SECRET, GATEWAY).presignedUri("GET", DATA,
                Map.of("X-Amz-Security-Token", TOKEN), EMPTY);
        assertEquals(Optional.of(KEY),
                authentication.authorizeClient(request(HttpMethod.GET, presigned, Map.of()), ACCOUNT, REGION, SESSION_ARN));
    }

    @Test
    void aHeaderSignedClientUpgradeMustMatchTheGrantAccount() throws Exception {
        when(iam.resolveAccountId(KEY)).thenReturn(Optional.of(ACCOUNT));
        when(iam.findSecretKey(KEY, TOKEN)).thenReturn(Optional.of(SECRET));
        HttpServerRequest signed = agentRequest(HttpMethod.GET, DATA, EMPTY, GATEWAY);
        assertThrows(AwsException.class, () -> authentication.authorizeClient(signed, "444455556666", REGION, SESSION_ARN));
    }

    @Test
    void aPartialSigningClaimIsRefused() {
        URI partial = URI.create(DATA + "?X-Amz-Signature=invalid");
        HttpServerRequest request = request(HttpMethod.GET, partial, Map.of());
        assertThrows(AwsException.class, () -> authentication.authorizeClient(request, ACCOUNT, REGION, SESSION_ARN));
    }

    private void instanceCredentials() {
        when(iam.findEc2InstanceIdentity(KEY, TOKEN)).thenReturn(Optional.of(
                new IamService.Ec2InstanceIdentity(ACCOUNT, INSTANCE)));
        when(iam.findSecretKey(KEY, TOKEN)).thenReturn(Optional.of(SECRET));
    }

    private static HttpServerRequest agentRequest(HttpMethod method, URI uri, byte[] body, String service)
            throws Exception {
        Map<String, String> headers = signer(KEY, SECRET, service).headersFor(method.name(), uri,
                Map.of("x-amz-security-token", TOKEN), body);
        headers.put("X-Amz-Security-Token", TOKEN);
        return request(method, uri, headers);
    }

    private static HttpServerRequest http2AgentPost(String authorityHost) throws Exception {
        Map<String, String> bodyHeaders = Map.of("content-type", "application/json",
                "content-length", Integer.toString(BODY.length), "x-amz-security-token", TOKEN);
        Map<String, String> signed = signer(KEY, SECRET, GATEWAY).headersFor("POST", CONTROL, bodyHeaders, BODY);
        HttpServerRequest request = request(HttpMethod.POST, CONTROL, signed);
        request.headers().remove("Host").addAll(bodyHeaders);
        when(request.version()).thenReturn(HttpVersion.HTTP_2);
        when(request.authority()).thenReturn(HostAndPort.create(authorityHost, CONTROL.getPort()));
        return request;
    }

    private static AwsRequestSigner signer(String key, String secret, String service) {
        return AwsRequestSigner.signedAs(key, secret, service).inRegion(REGION).signedAt(NOW);
    }

    private static HttpServerRequest request(HttpMethod method, URI uri, Map<String, String> signed) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap().add("Host", uri.getAuthority()).addAll(signed);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.method()).thenReturn(method);
        when(request.path()).thenReturn(uri.getRawPath());
        when(request.query()).thenReturn(uri.getRawQuery());
        when(request.headers()).thenReturn(headers);
        when(request.getHeader("Authorization")).thenReturn(headers.get("Authorization"));
        return request;
    }
}
