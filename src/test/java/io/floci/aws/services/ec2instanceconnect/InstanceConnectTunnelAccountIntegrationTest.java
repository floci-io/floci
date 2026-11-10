package io.floci.aws.services.ec2instanceconnect;

import io.floci.aws.core.common.XmlParser;
import io.floci.aws.testing.EnforcementFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tunnel is a Vert.x route, outside the JAX-RS filters that set the request's account. It must
 * still read the endpoint from the account the presigned credential names: before, every lookup
 * fell back to the default account, so another account's endpoint was never found and a caller
 * from any account reached the default account's instances.
 */
@QuarkusTest
class InstanceConnectTunnelAccountIntegrationTest {

    private static final String ACCOUNT = "111122223333";

    @Inject
    InstanceConnectTunnelHandler handler;

    @Test
    void theEndpointIsLookedUpInTheCredentialsAccountOnly() {
        String vpcId = XmlParser.extractFirst(ec2("CreateVpc", "CidrBlock", "10.91.0.0/16"), "vpcId", null);
        String subnetId = XmlParser.extractFirst(
                ec2("CreateSubnet", "VpcId", vpcId, "CidrBlock", "10.91.1.0/24"), "subnetId", null);
        String endpointId = XmlParser.extractFirst(
                ec2("CreateInstanceConnectEndpoint", "SubnetId", subnetId), "instanceConnectEndpointId", null);
        assertTrue(endpointId.startsWith("eice-"), endpointId);

        InstanceConnectTunnelHandler.Refusal asOwner = refusal(ACCOUNT, endpointId);
        assertEquals(404, asOwner.status);
        assertTrue(asOwner.getMessage().startsWith("No running instance"),
                "the owner's credential finds the endpoint: " + asOwner.getMessage());

        InstanceConnectTunnelHandler.Refusal asDefault = refusal("000000000000", endpointId);
        assertEquals(404, asDefault.status);
        assertTrue(asDefault.getMessage().startsWith("No available EC2 Instance Connect Endpoint"),
                "another account's credential does not: " + asDefault.getMessage());
    }

    private InstanceConnectTunnelHandler.Refusal refusal(String accessKey, String endpointId) {
        String query = "instanceConnectEndpointId=" + endpointId + "&remotePort=22&privateIpAddress=10.91.1.200&"
                + EnforcementFixtures.presignedQuery(accessKey, "ec2-instance-connect");
        return assertThrows(InstanceConnectTunnelHandler.Refusal.class,
                () -> handler.resolve("/openTunnel", query, name -> null));
    }

    private static String ec2(String action, String... params) {
        RequestSpecification request = given().formParam("Action", action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20261019/us-east-1/ec2/aws4_request");
        for (int i = 0; i < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        return request.when().post("/").then().statusCode(200).extract().asString();
    }
}
