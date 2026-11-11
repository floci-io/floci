package io.floci.aws.services.ec2instanceconnect;

import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.AccessKey;
import io.floci.aws.testing.TestAccounts;
import io.floci.aws.testing.ValidateSignaturesProfile;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With signature validation on, a tunnel signed by an IAM access key that belongs to another
 * account has to get past the signature check: the secret is stored in that account, so it is only
 * found once the request runs as that account.
 */
@QuarkusTest
@TestProfile(ValidateSignaturesProfile.class)
class InstanceConnectTunnelSignedAccountIntegrationTest {

    private static final String REGION = "us-east-1";

    @Inject
    InstanceConnectTunnelHandler handler;
    @Inject
    IamService iam;
    @Inject
    Ec2Service ec2;

    @Test
    void anAccessKeyOfAnotherAccountPassesTheSignatureCheck() throws Exception {
        String account = TestAccounts.randomId();
        AccessKey key = RequestScopes.callAs(account, () -> {
            iam.createUser("eice-user", "/");
            return iam.createAccessKey("eice-user");
        });
        String endpointId = RequestScopes.callAs(account, REGION, () -> {
            String vpcId = ec2.createVpc(REGION, "10.92.0.0/16", false).getVpcId();
            String subnetId = ec2.createSubnet(REGION, vpcId, "10.92.1.0/24", "us-east-1a").getSubnetId();
            return ec2.createInstanceConnectEndpoint(REGION, subnetId, List.of(), null, null, List.of(), null)
                    .getInstanceConnectEndpointId();
        });

        String query = signedQuery(key, endpointId);
        InstanceConnectTunnelHandler.Refusal refusal = assertThrows(InstanceConnectTunnelHandler.Refusal.class,
                () -> handler.resolve("/openTunnel", query, name -> "host".equals(name) ? "localhost" : null));
        // Past the signature and the endpoint lookup, refused only for want of an instance.
        assertEquals(404, refusal.status, refusal.getMessage());
        assertTrue(refusal.getMessage().startsWith("No running instance"), refusal.getMessage());

        String tampered = query.replace("remotePort=22", "remotePort=3389");
        assertEquals(403, assertThrows(InstanceConnectTunnelHandler.Refusal.class,
                () -> handler.resolve("/openTunnel", tampered, name -> "host".equals(name) ? "localhost" : null)).status);
    }

    /** A presigned tunnel URL as the AWS CLI signs it: GET, the query, the host header, an empty body. */
    private static String signedQuery(AccessKey key, String endpointId) throws Exception {
        return AwsRequestSigner.signedAs(key.getAccessKeyId(), key.getSecretAccessKey(), "ec2-instance-connect")
                .presignedUri("GET", URI.create("http://localhost/openTunnel"), Map.of(
                        "instanceConnectEndpointId", endpointId, "remotePort", "22",
                        "privateIpAddress", "10.92.1.200"), new byte[0]).getRawQuery();
    }
}
