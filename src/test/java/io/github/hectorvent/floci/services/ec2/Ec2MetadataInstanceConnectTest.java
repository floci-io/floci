package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2instanceconnect.InstanceConnectKeys;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The IMDS paths the unmodified EC2 Instance Connect agent reads, in the shape it reads them:
 * with a trailing slash, and {@code active-keys} probed with HEAD before it is fetched.
 */
class Ec2MetadataInstanceConnectTest {

    @Test
    void servesTheAgentsPathsWithTrailingSlashesAndHead() throws Exception {
        InstanceConnectKeys keys = mock(InstanceConnectKeys.class);
        when(keys.activeKeysDocument(anyString(), eq("i-0123456789abcdef0"), anyString())).thenReturn(Optional.empty());
        when(keys.activeKeysDocument(anyString(), eq("i-0123456789abcdef0"), eq("admin")))
                .thenReturn(Optional.of("#Timestamp=1\nssh-ed25519 AAAA\nc2ln\n\n"));
        when(keys.signerCertificateChain(anyString())).thenReturn("CHAIN");
        Map<String, String> staples = new LinkedHashMap<>();
        staples.put("AA11", "c3RhcGxlMQ==");
        staples.put("BB22", "c3RhcGxlMg==");
        when(keys.ocspStaples(anyString())).thenReturn(staples);

        try (Ec2MetadataTestFixture imds = new Ec2MetadataTestFixture().withInstanceConnect(keys)) {
            imds.instance().setRegion("us-east-1");
            imds.start();
            String token = imds.putToken("60").body();
            String keysPath = "/latest/meta-data/managed-ssh-keys/";

            assertEquals("i-0123456789abcdef0", imds.get("/latest/meta-data/instance-id/", token).body());
            assertEquals("amazonaws.com", imds.get("/latest/meta-data/services/domain/", token).body());
            assertEquals(200, imds.head(keysPath + "active-keys/admin/", token).statusCode());
            assertEquals(404, imds.head(keysPath + "active-keys/root/", token).statusCode());
            assertEquals("#Timestamp=1\nssh-ed25519 AAAA\nc2ln\n\n",
                    imds.get(keysPath + "active-keys/admin/", token).body());
            assertEquals("CHAIN", imds.get(keysPath + "signer-cert/", token).body());
            assertEquals("AA11\nBB22", imds.get(keysPath + "signer-ocsp/", token).body());
            assertEquals("c3RhcGxlMg==", imds.get(keysPath + "signer-ocsp/BB22", token).body());
            assertEquals(404, imds.get(keysPath + "signer-ocsp/CC33", token).statusCode());
        }
    }

    @Test
    void managedSshKeysAreAbsentWithoutInstanceConnect() throws Exception {
        try (Ec2MetadataTestFixture imds = new Ec2MetadataTestFixture()) {
            imds.start();
            String token = imds.putToken("60").body();
            assertEquals(404, imds.get("/latest/meta-data/managed-ssh-keys/signer-cert/", token).statusCode());
        }
    }
}
