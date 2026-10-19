package io.github.hectorvent.floci.services.ec2instanceconnect;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.IamEnforcementFilter;
import io.github.hectorvent.floci.core.common.SessionAccountLookup;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceConnectEndpoint;
import io.github.hectorvent.floci.services.iam.IamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InstanceConnectTunnelHandlerTest {

    private static final String EICE = "eice-0123456789abcdef0";
    private static final String HOST = EICE + ".localhost.floci.io";
    private static final String ARN = "arn:aws:ec2:us-east-1:000000000000:instance-connect-endpoint/" + EICE;
    /**
     * Produced by AWS CLI 2.38.1's own signer ({@code InstanceConnectEndpointRequestSigner}) with access
     * key AKIAFLOCITUNNELTEST1 and secret {@code tunnelsecret}: the exact URL {@code open-tunnel} dials.
     */
    private static final String CLI_QUERY = "instanceConnectEndpointId=" + EICE
            + "&remotePort=22&privateIpAddress=10.0.1.10&maxTunnelDuration=3600&X-Amz-Algorithm=AWS4-HMAC-SHA256"
            + "&X-Amz-Credential=AKIAFLOCITUNNELTEST1%2F20261019%2Fus-east-1%2Fec2-instance-connect%2Faws4_request"
            + "&X-Amz-Date=20261019T183743Z&X-Amz-Expires=60&X-Amz-SignedHeaders=host"
            + "&X-Amz-Signature=52d3a1720534fa6940d19223dba7516507774db4c7f24e50c92116cd63fee0b2";
    private static final Instant NOW = Instant.parse("2026-10-19T18:38:13Z");

    private Ec2Service ec2;
    private EmulatorConfig config;
    private IamService iam;
    private SessionAccountLookup sessions;
    private IamEnforcementFilter enforcement;
    private InstanceConnectTunnelHandler handler;
    private Instance instance;
    private InstanceConnectEndpoint endpoint;

    @BeforeEach
    void setUp() {
        ec2 = mock(Ec2Service.class);
        config = mock(EmulatorConfig.class, Answers.RETURNS_DEEP_STUBS);
        iam = mock(IamService.class);
        sessions = mock(SessionAccountLookup.class);
        enforcement = mock(IamEnforcementFilter.class);
        when(sessions.resolveAccountId(anyString())).thenReturn(Optional.empty());
        when(iam.findSecretKey(eq("AKIAFLOCITUNNELTEST1"), nullable(String.class)))
                .thenReturn(Optional.of("tunnelsecret"));
        handler = new InstanceConnectTunnelHandler(ec2, null, config, iam, new AccountResolver("000000000000"),
                sessions, enforcement, Clock.fixed(NOW, ZoneOffset.UTC));
        endpoint = new InstanceConnectEndpoint();
        endpoint.setInstanceConnectEndpointId(EICE);
        endpoint.setInstanceConnectEndpointArn(ARN);
        endpoint.setVpcId("vpc-1");
        instance = new Instance();
        instance.setInstanceId("i-0123456789abcdef0");
        instance.setPrivateIpAddress("10.0.1.10");
        instance.setContainerBridgeIp("192.168.97.5");
        when(ec2.findUsableInstanceConnectEndpoint(anyString(), anyString())).thenReturn(Optional.empty());
        when(ec2.findUsableInstanceConnectEndpoint("us-east-1", EICE)).thenReturn(Optional.of(endpoint));
        when(ec2.findRunningInstanceByPrivateIp(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(ec2.findRunningInstanceByPrivateIp("us-east-1", "vpc-1", "10.0.1.10")).thenReturn(Optional.of(instance));
        when(ec2.instanceConnectEndpointMayReach(eq(endpoint), eq(instance), anyInt())).thenReturn(true);
    }

    @Test
    void resolvesTheCliUrlToTheInstancesReachableAddress() {
        InstanceConnectTunnelHandler.Target target = resolve(CLI_QUERY);

        assertEquals("192.168.97.5", target.host());
        assertEquals(22, target.port());
        assertEquals(3600, target.maxSeconds());
    }

    @Test
    void refusesWhatAwsRefuses() {
        assertEquals(403, refusal(CLI_QUERY.replace("ec2-instance-connect%2Faws4", "ec2%2Faws4")));
        assertEquals(403, refusal(CLI_QUERY.replace("X-Amz-Algorithm=AWS4-HMAC-SHA256&", "")));
        assertEquals(400, refusal(CLI_QUERY.replace("remotePort=22", "remotePort=6443")));
        assertEquals(400, refusal(CLI_QUERY.replace("maxTunnelDuration=3600", "maxTunnelDuration=3601")));
        assertEquals(404, refusal(CLI_QUERY.replace("instanceConnectEndpointId=" + EICE,
                "instanceConnectEndpointId=eice-0000000000000000f")));
        assertEquals(404, refusal(CLI_QUERY.replace("10.0.1.10", "10.0.1.99")));
        when(ec2.instanceConnectEndpointMayReach(eq(endpoint), eq(instance), anyInt())).thenReturn(false);
        assertEquals(403, refusal(CLI_QUERY), "security groups deny");
    }

    @Test
    void expiryIsBoundedAndAFutureSigningTimeIsRefused() {
        assertEquals(403, refusal(CLI_QUERY.replace("X-Amz-Date=20261019T183743Z", "X-Amz-Date=20261019T183600Z")),
                "signed 133 s ago, valid 60 s");
        assertEquals(403, refusal(CLI_QUERY.replace("X-Amz-Expires=60", "X-Amz-Expires=0")));
        assertEquals(403, refusal(CLI_QUERY.replace("X-Amz-Expires=60", "X-Amz-Expires=604801")));
        assertEquals(403, refusal(CLI_QUERY.replace("X-Amz-Expires=60", "X-Amz-Expires=9223372036854775807")),
                "used to overflow Instant.plusSeconds into a 500");
        assertEquals(403, refusal(CLI_QUERY.replace("X-Amz-Date=20261019T183743Z", "X-Amz-Date=20261019T185000Z")),
                "signed twelve minutes ahead");
        assertEquals(403, refusal(CLI_QUERY.replace("X-Amz-Date=20261019T183743Z", "X-Amz-Date=yesterday")));
    }

    @Test
    void withSignatureValidationTheCliSignatureVerifiesAndATamperedOneDoesNot() {
        when(config.auth().validateSignatures()).thenReturn(true);

        assertEquals(22, resolve(CLI_QUERY).port());
        assertEquals(403, refusal(CLI_QUERY.replace("privateIpAddress=10.0.1.10", "privateIpAddress=10.0.1.11")),
                "the target is covered by the signature");
        assertEquals(403, refusal(CLI_QUERY.replace("X-Amz-Signature=52", "X-Amz-Signature=53")));
        when(iam.findSecretKey(eq("AKIAFLOCITUNNELTEST1"), nullable(String.class))).thenReturn(Optional.empty());
        assertEquals(403, refusal(CLI_QUERY), "an access key Floci never issued");
    }

    @Test
    void withoutSignatureValidationTheSignatureIsNotChecked() {
        assertEquals(22, resolve(CLI_QUERY.replace("X-Amz-Signature=52", "X-Amz-Signature=53")).port());
    }

    @Test
    void withIamEnforcementOpenTunnelIsAuthorizedOnTheEndpoint() {
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        when(iam.isKnownAccessKey("AKIAFLOCITUNNELTEST1")).thenReturn(true);

        resolve(CLI_QUERY);
        verify(enforcement).authorizeAdditionalResource(
                "AWS4-HMAC-SHA256 Credential=AKIAFLOCITUNNELTEST1/20261019/us-east-1/ec2-instance-connect/aws4_request",
                "ec2-instance-connect:OpenTunnel", ARN);

        doThrow(new AwsException("AccessDenied", "denied", 403)).when(enforcement)
                .authorizeAdditionalResource(anyString(), anyString(), anyString());
        assertEquals(403, refusal(CLI_QUERY));

        when(iam.isKnownAccessKey("AKIAFLOCITUNNELTEST1")).thenReturn(false);
        assertEquals(403, refusal(CLI_QUERY), "an unknown key is refused under enforcement");
    }

    private InstanceConnectTunnelHandler.Target resolve(String query) {
        return handler.resolve("/openTunnel", query, name -> "host".equals(name) ? HOST : null);
    }

    private int refusal(String query) {
        return assertThrows(InstanceConnectTunnelHandler.Refusal.class, () -> resolve(query)).status;
    }
}
