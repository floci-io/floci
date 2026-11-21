package io.floci.aws.services.ec2;

import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.acm.AcmService;
import io.floci.aws.services.ec2.model.ClientVpnEndpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Stream;

import static io.floci.aws.testing.Ec2ServiceFixtures.ACCOUNT;
import static io.floci.aws.testing.Ec2ServiceFixtures.mockModeService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;

/**
 * CreateClientVpnEndpoint's ClientToken: a retry with the token returns the endpoint the first call
 * made, and a call with no token, or a blank one, makes an endpoint of its own, as the other EC2
 * creates that take a token do.
 */
class Ec2ClientVpnClientTokenTest {

    private static final String REGION = "us-east-1";

    private final Ec2ClientVpnService vpn = new Ec2ClientVpnService(
            AccountAwareStorageBackend.<ClientVpnEndpoint>inMemory(ACCOUNT), mockModeService(), mock(AcmService.class));

    @Test
    void aRetryWithTheSameTokenReturnsTheFirstEndpoint() {
        String first = createEndpoint("4f9a8d0c2b7e41f3");

        assertEquals(first, createEndpoint("4f9a8d0c2b7e41f3"));
        assertEquals(List.of(first), endpointIds());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    void aCreateWithoutATokenMakesAnEndpointOfItsOwn(String token) {
        String first = createEndpoint(token);
        String second = createEndpoint(token);

        assertNotEquals(first, second);
        assertEquals(Stream.of(first, second).sorted().toList(), endpointIds());
    }

    /** A certificate-authenticated endpoint with every optional member left to AWS's default. */
    private String createEndpoint(String clientToken) {
        ClientVpnEndpoint.Authentication certificate = new ClientVpnEndpoint.Authentication();
        certificate.setType("certificate-authentication");
        certificate.setClientRootCertificateChain("arn:aws:acm:us-east-1:000000000000:certificate/client-root");
        Ec2ClientVpnService.EndpointRequest request = new Ec2ClientVpnService.EndpointRequest("172.16.0.0/22",
                "arn:aws:acm:us-east-1:000000000000:certificate/server", List.of(certificate), false,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                clientToken);
        return vpn.createEndpoint(REGION, request, List.of()).getClientVpnEndpointId();
    }

    private List<String> endpointIds() {
        return vpn.describeEndpoints(REGION, List.of()).stream()
                .map(ClientVpnEndpoint::getClientVpnEndpointId).sorted().toList();
    }
}
