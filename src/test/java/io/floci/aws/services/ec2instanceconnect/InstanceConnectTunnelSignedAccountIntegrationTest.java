package io.floci.aws.services.ec2instanceconnect;

import io.floci.aws.core.common.Digests;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.auth.SigV4Canonicalization;
import io.floci.aws.core.common.auth.SigV4RequestValidator;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.AccessKey;
import io.floci.aws.testing.TestAccounts;
import io.floci.aws.testing.ValidateSignaturesProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

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
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

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
        Instant now = Instant.now();
        String amzDate = AMZ_DATE.format(now);
        String date = amzDate.substring(0, 8);
        String scope = date + "/" + REGION + "/ec2-instance-connect/aws4_request";
        String unsigned = "instanceConnectEndpointId=" + endpointId + "&remotePort=22&privateIpAddress=10.92.1.200"
                + "&X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential="
                + (key.getAccessKeyId() + "/" + scope).replace("/", "%2F")
                + "&X-Amz-Date=" + amzDate + "&X-Amz-Expires=60&X-Amz-SignedHeaders=host";
        String canonicalRequest = "GET\n/openTunnel\n" + SigV4Canonicalization.canonicalQueryString(unsigned, true)
                + "\nhost:localhost\n\nhost\n" + SigV4Canonicalization.payloadHash(null, "host", new byte[0], false);
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + Digests.sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] signingKey = SigV4RequestValidator.deriveSigningKey(key.getSecretAccessKey(), date, REGION,
                "ec2-instance-connect");
        return unsigned + "&X-Amz-Signature="
                + SigV4RequestValidator.hexEncode(SigV4RequestValidator.hmacSha256(signingKey, stringToSign));
    }
}
