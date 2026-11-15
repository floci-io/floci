package io.floci.aws.services.ec2instanceconnect;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.IamEnforcementFilter;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.AccessKey;
import io.floci.aws.testing.IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The EC2 User Guide's OpenTunnel policies, through the path the tunnel handler takes: the shared
 * filter fills {@code ec2-instance-connect:remotePort}, {@code privateIpAddress} and
 * {@code maxTunnelDuration} from the query parameters, by the table in request-condition-keys.json.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class InstanceConnectTunnelConditionKeysIntegrationTest {

    private static final String ACTION = "ec2-instance-connect:OpenTunnel";
    private static final String ENDPOINT = "arn:aws:ec2:us-east-1:000000000000:instance-connect-endpoint/eice-0123456789abcdef0";

    @Inject
    IamService iam;
    @Inject
    IamEnforcementFilter enforcement;

    @Test
    void anAllowScopedToPortAddressAndDurationAppliesToTheTunnelsQuery() {
        String key = userWith("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"ec2-instance-connect:OpenTunnel",
                  "Resource":"*","Condition":{
                  "NumericEquals":{"ec2-instance-connect:remotePort":22},
                  "IpAddress":{"ec2-instance-connect:privateIpAddress":"10.0.0.0/24"},
                  "NumericLessThanEquals":{"ec2-instance-connect:maxTunnelDuration":600}}}]}""");

        assertDoesNotThrow(() -> open(key, "22", "10.0.0.5", "300"));
        assertDenied(key, "3389", "10.0.0.5", "300");
        assertDenied(key, "22", "10.0.1.5", "300");
        assertDenied(key, "22", "10.0.0.5", "900");
    }

    @Test
    void aDenyScopedToAPortRefusesThatPortOnly() {
        String key = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"ec2-instance-connect:OpenTunnel","Resource":"*"},
                  {"Effect":"Deny","Action":"ec2-instance-connect:OpenTunnel","Resource":"*",
                   "Condition":{"NumericEquals":{"ec2-instance-connect:remotePort":3389}}}]}""");

        assertDoesNotThrow(() -> open(key, "22", "10.0.0.5", "300"));
        assertDenied(key, "3389", "10.0.0.5", "300");
    }

    private String userWith(String policy) {
        String user = "eice-" + UUID.randomUUID().toString().substring(0, 8);
        iam.createUser(user, "/");
        iam.putUserPolicy(user, "tunnel", policy);
        AccessKey key = iam.createAccessKey(user);
        return key.getAccessKeyId();
    }

    private void open(String accessKeyId, String port, String ip, String max) {
        Map<String, String> query = Map.of("remotePort", port, "privateIpAddress", ip, "maxTunnelDuration", max);
        enforcement.authorizeRequestParameters(auth(accessKeyId, "ec2-instance-connect"), ACTION, ENDPOINT,
                query::get);
    }

    private void assertDenied(String accessKeyId, String port, String ip, String max) {
        assertThrows(AwsException.class, () -> open(accessKeyId, port, ip, max), port + " " + ip + " " + max);
    }
}
